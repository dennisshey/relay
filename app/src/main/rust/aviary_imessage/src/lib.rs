//! Relay iMessage JNI wrapper around OpenBubbles' rustpush.
//!
//! Exposes a small, config-agnostic API to Kotlin:
//!   init → login → submit2fa → register → sendText → poll
//!
//! Anisette (ADI/OTP) is generated ON-DEVICE via the local provider grafted into
//! omnisette (Apple's libstoreservicescore.so, loaded by android-loader).
//! IDS "validation data" (NAC/absinthe) comes from whatever `OSConfig` the caller
//! supplies as a plist — today a `RelayConfig` (host/code point at the NAC source);
//! a fully-local absinthe can be swapped in later without touching this layer.

use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use serde::{Deserialize, Serialize};
use tokio::runtime::Runtime;
use tokio::sync::Mutex as AsyncMutex;

use omnisette::{ArcAnisetteClient, DefaultAnisetteProvider};

use rustpush::macos::MacOSConfig;
use rustpush::{
    authenticate_apple, login_apple_delegates, register, APSConnection, APSConnectionResource,
    APSMessage, AppleAccount, ConversationData, DebugMutex, IDSNGMIdentity, IDSUser,
    Attachment, EditMessage, IMClient, IndexedMessagePart, LoginDelegate, Message, MessageInst,
    MessagePart, MessageParts, MessageType, MMCSFile, NormalMessage, OSConfig, ReactMessage,
    Reaction, ReactMessageType, TokenProvider, UnsendMessage, MADRID_SERVICE,
};
use rustpush::statuskit::{
    ChannelInterestToken, StatusKitClient, StatusKitMessage, StatusKitState,
};

/// Anisette (device-attestation) provider. remote-anisette-v3 → a public anisette server
/// (ani.sidestore.io): only attestation headers leave the device; credentials go direct to Apple.
/// ClearADI's local provider is closed-source (stub → panics), so a fresh login needs this.
type Prov = DefaultAnisetteProvider;
type Anisette = ArcAnisetteClient<Prov>;

/// iMessage only, for now. (IDSService isn't re-exported by rustpush, so the slice is inlined
/// at each call site where the type is inferred.)

/// Persisted so relaunch skips login/register.
#[derive(Serialize, Deserialize, Clone)]
struct SavedState {
    // Tolerate an older/partial state.plist that predates this field: a missing `push` must not
    // discard the whole registration (users/identity). It re-activates instead of hard-failing.
    #[serde(default)]
    push: rustpush::APSState,
    #[serde(default)]
    users: Vec<IDSUser>,
    identity: IDSNGMIdentity,
}

struct AviaryImessage {
    config: Arc<dyn OSConfig>,
    config_dir: PathBuf,
    anisette: Anisette,
    connection: APSConnection,
    // The logged-in Apple account, held across the login → register flow.
    account: Option<Arc<DebugMutex<AppleAccount<Prov>>>>,
    users: Vec<IDSUser>,
    identity: IDSNGMIdentity,
    client: Option<IMClient>,
    subscription: Option<tokio::sync::broadcast::Receiver<APSMessage>>,
    // StatusKit: receives contacts' Focus/DND status so we can show "Delivered Quietly".
    statuskit: Option<Arc<StatusKitClient<Prov>>>,
    sk_want: std::collections::HashSet<String>, // handles we want focus status for (people we message)
    sk_token: Option<ChannelInterestToken>,     // holds the focus-channel subscriptions alive
    sk_dirty: bool,                             // re-subscribe only when the want-set changed
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static STATE: OnceLock<AsyncMutex<Option<AviaryImessage>>> = OnceLock::new();

fn rt() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        Runtime::new().expect("failed to build tokio runtime")
    })
}

fn state_cell() -> &'static AsyncMutex<Option<AviaryImessage>> {
    STATE.get_or_init(|| AsyncMutex::new(None))
}

// ---- JSON helpers ----------------------------------------------------------

fn ok(mut extra: serde_json::Value) -> String {
    if let Some(obj) = extra.as_object_mut() {
        obj.insert("ok".into(), serde_json::Value::Bool(true));
        serde_json::to_string(&extra).unwrap_or_else(|_| "{\"ok\":true}".into())
    } else {
        "{\"ok\":true}".into()
    }
}

fn err(msg: impl std::fmt::Display) -> String {
    serde_json::json!({ "ok": false, "error": msg.to_string() }).to_string()
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|j| j.into()).unwrap_or_default()
}

fn ret(env: &JNIEnv, s: String) -> jstring {
    env.new_string(s)
        .map(|o| o.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Run an async block on the shared runtime with the (locked) global state.
fn with_state<F, Fut>(f: F) -> String
where
    F: FnOnce(&'static AsyncMutex<Option<AviaryImessage>>) -> Fut,
    Fut: std::future::Future<Output = String>,
{
    // Catch panics HERE, before they unwind across the extern "C" JNI boundary (which aborts the
    // whole app). A panic deep in rustpush (e.g. an unexpected send failure) becomes an error the
    // Kotlin side can handle instead of a crash.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        rt().block_on(async move { f(state_cell()).await })
    }));
    result.unwrap_or_else(|e| {
        let msg = e.downcast_ref::<&str>().map(|s| s.to_string())
            .or_else(|| e.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "native panic".to_string());
        log::error!("[relay] caught native panic: {msg}");
        serde_json::json!({ "ok": false, "error": format!("native panic: {msg}") }).to_string()
    })
}

// ---- init ------------------------------------------------------------------

/// config_plist: a RelayConfig (or any OSConfig) serialized as XML plist.
/// lib_dir: directory containing `lib/arm64-v8a/libstoreservicescore.so` (+ libCoreADI.so).
/// config_dir: writable dir for anisette provisioning + saved state.
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    config_plist: JString,
    lib_dir: JString,
    config_dir: JString,
) -> jstring {
    let _ = android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Debug),
    );
    // Log the source location of any panic (e.g. a todo!() deep in a dependency) BEFORE it
    // unwinds into our catch_unwind — the caught payload has the message but not the file:line.
    static PANIC_HOOK: std::sync::Once = std::sync::Once::new();
    PANIC_HOOK.call_once(|| {
        let default = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let loc = info.location()
                .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
                .unwrap_or_else(|| "<unknown>".to_string());
            log::error!("[relay] PANIC at {loc}: {info}");
            default(info);
        }));
    });
    let config_plist = jstr(&mut env, &config_plist);
    let lib_dir = PathBuf::from(jstr(&mut env, &lib_dir)); // has lib/<arch>/libstoreservicescore.so for on-device ADI
    let config_dir = PathBuf::from(jstr(&mut env, &config_dir));

    let out = with_state(move |cell| async move {
        // Init exactly once. start(), the message poll, and canReach() can all call nativeInit at
        // launch; without this guard each spins up its OWN push connection, and their device
        // activations race on the same keystore key ("activation:<serial>") — a CSR ends up signed
        // with a key another attempt already overwrote, so albert rejects it and returns an empty
        // <dict/>. Holding the lock through init serializes duplicate callers: the first
        // initializes, the rest observe Some and return the current status.
        let mut guard = cell.lock().await;
        if let Some(s) = guard.as_ref() {
            let registered = !s.users.is_empty() && !s.users[0].registration.is_empty();
            return ok(serde_json::json!({ "registered": registered }));
        }

        // On-device config: the OABS blob copied by OpenBubbles' Mac Hardware Info.app
        // (a one-time extract). Validation data (NAC) is generated on-device via the
        // emulator — no relay.
        let config: MacOSConfig = match parse_oabs_config(config_plist.trim()) {
            Ok(c) => c,
            Err(e) => return err(format!("bad Mac config: {e}")),
        };
        let config: Arc<dyn OSConfig> = Arc::new(config);

        let _ = std::fs::create_dir_all(&config_dir);

        // The keystore global must be initialized before any key op (activation RSA key,
        // IDS keys). Software-backed, persisted to keystore.plist so keys survive relaunch.
        init_keystore_once(&config_dir);

        // Restore prior push state if present.
        let state_path = config_dir.join("state.plist");
        let saved: Option<SavedState> = match std::fs::read(&state_path) {
            Ok(b) => {
                log::info!("[relay] state.plist found ({} bytes)", b.len());
                match plist::from_bytes::<SavedState>(&b) {
                    Ok(s) => {
                        log::info!(
                            "[relay] state.plist parsed: keypair={}, users={}, registered={}",
                            s.push.keypair.is_some(), s.users.len(),
                            s.users.first().map(|u| !u.registration.is_empty()).unwrap_or(false),
                        );
                        Some(s)
                    }
                    Err(e) => {
                        log::error!("[relay] state.plist FAILED to parse: {e} — re-activation will be forced");
                        None
                    }
                }
            }
            Err(e) => { log::info!("[relay] no state.plist ({e})"); None }
        };

        let (connection, aps_err) = APSConnectionResource::new(
            config.clone(),
            saved.as_ref().map(|s| s.push.clone()),
        )
        .await;
        if let Some(e) = aps_err {
            return err(format!("aps connect: {e}"));
        }

        // Fully on-device anisette via Apple's libstoreservicescore.so (local ADI). The provider
        // loads it from lib_dir/lib/<arch>/ and persists provisioning state there.
        let anisette: Anisette = omnisette::default_provider(
            config.get_gsa_config(&*connection.state.read().await, false),
            lib_dir.clone(),
        );

        let subscription = connection.messages_cont.subscribe();

        let (users, identity, mut client) = if let Some(s) = saved {
            (s.users, s.identity, None)
        } else {
            (Vec::new(), IDSNGMIdentity::new().unwrap(), None)
        };

        // If we already have a registration, build the client immediately.
        let registered = !users.is_empty() && !users[0].registration.is_empty();
        if registered {
            client = Some(
                build_client(connection.clone(), users.clone(), identity.clone(), config.clone(), config_dir.clone())
                    .await,
            );
        }

        *guard = Some(AviaryImessage {
            config,
            config_dir,
            anisette,
            connection,
            account: None,
            users,
            identity,
            client,
            subscription: Some(subscription),
            statuskit: None,
            sk_want: std::collections::HashSet::new(),
            sk_token: None,
            sk_dirty: false,
        });

        ok(serde_json::json!({ "registered": registered }))
    });
    ret(&env, out)
}

async fn build_client(
    connection: APSConnection,
    users: Vec<IDSUser>,
    identity: IDSNGMIdentity,
    config: Arc<dyn OSConfig>,
    config_dir: PathBuf,
) -> IMClient {
    let save_dir = config_dir.clone();
    IMClient::new(
        connection,
        users,
        identity,
        &[&MADRID_SERVICE],
        config_dir.join("id_cache.plist"),
        config,
        Box::new(move |updated_users| {
            // Persist refreshed keys.
            if let Ok(bytes) = std::fs::read(save_dir.join("state.plist")) {
                if let Ok(mut s) = plist::from_bytes::<SavedState>(&bytes) {
                    s.users = updated_users;
                    if let Ok(buf) = plist_to_vec(&s) {
                        let _ = std::fs::write(save_dir.join("state.plist"), buf);
                    }
                }
            }
        }),
    )
    .await
}

/// Build a StatusKitClient for RECEIVING contacts' Focus/DND status (so we can label sent
/// messages "Delivered Quietly"). The receive path never uses the account token, so we hand it
/// a throwaway AppleAccount (constructed, never logged in) — no Apple-account persistence needed.
/// Shared channel keys persist to statuskit.plist so they survive relaunches.
async fn build_statuskit(
    connection: APSConnection,
    anisette: Anisette,
    config: Arc<dyn OSConfig>,
    identity: rustpush::IdentityManager,
    config_dir: PathBuf,
) -> Option<Arc<StatusKitClient<Prov>>> {
    let gsa = config.get_gsa_config(&*connection.state.read().await, false);
    let account = AppleAccount::new_with_anisette(gsa, anisette).ok()?;
    let token_provider = TokenProvider::new(Arc::new(DebugMutex::new(account)), config.clone());

    let sk_path = config_dir.join("statuskit.plist");
    let sk_state: StatusKitState = std::fs::read(&sk_path)
        .ok()
        .and_then(|b| plist::from_bytes(&b).ok())
        .unwrap_or_default();
    let persist_path = sk_path.clone();
    Some(
        StatusKitClient::new(
            sk_state,
            Box::new(move |state| {
                if let Ok(buf) = plist_to_vec(state) {
                    let _ = std::fs::write(&persist_path, buf);
                }
            }),
            token_provider,
            connection,
            config,
            identity,
        )
        .await,
    )
}

/// Install the software keystore backend once, persisting to <config_dir>/keystore.plist.
fn init_keystore_once(config_dir: &std::path::Path) {
    use keystore::software::{NoEncryptor, SoftwareKeystore, SoftwareKeystoreState};
    let ks_path = config_dir.join("keystore.plist");
    let state: SoftwareKeystoreState = std::fs::read(&ks_path)
        .ok()
        .and_then(|b| plist::from_bytes(&b).ok())
        .unwrap_or_default();
    let save_path = ks_path;
    keystore::init_keystore(SoftwareKeystore {
        state: std::sync::RwLock::new(state),
        update_state: Box::new(move |st| {
            if let Ok(buf) = plist_to_vec(st) {
                let _ = std::fs::write(&save_path, buf);
            }
        }),
        encryptor: NoEncryptor,
    });
}

// ---- OABS (OpenBubbles Mac Hardware Info) config parsing -------------------

enum PbField {
    Bytes(Vec<u8>),
    Varint(u64),
}

fn read_varint(b: &[u8], i: &mut usize) -> u64 {
    let mut r = 0u64;
    let mut shift = 0;
    while *i < b.len() {
        let byte = b[*i];
        *i += 1;
        r |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            break;
        }
        shift += 7;
    }
    r
}

/// Minimal protobuf field reader (wire types 0 varint and 2 length-delimited).
fn parse_pb(b: &[u8]) -> std::collections::HashMap<u64, PbField> {
    let mut m = std::collections::HashMap::new();
    let mut i = 0;
    while i < b.len() {
        let tag = read_varint(b, &mut i);
        let fnum = tag >> 3;
        let wt = tag & 7;
        match wt {
            0 => {
                let v = read_varint(b, &mut i);
                m.insert(fnum, PbField::Varint(v));
            }
            2 => {
                let len = read_varint(b, &mut i) as usize;
                if i + len > b.len() {
                    break;
                }
                m.insert(fnum, PbField::Bytes(b[i..i + len].to_vec()));
                i += len;
            }
            _ => break,
        }
    }
    m
}

fn pb_str(m: &std::collections::HashMap<u64, PbField>, f: u64) -> String {
    match m.get(&f) {
        Some(PbField::Bytes(b)) => String::from_utf8_lossy(b).into_owned(),
        _ => String::new(),
    }
}
fn pb_bytes(m: &std::collections::HashMap<u64, PbField>, f: u64) -> Vec<u8> {
    match m.get(&f) {
        Some(PbField::Bytes(b)) => b.clone(),
        _ => Vec::new(),
    }
}

/// Parse the base64 `OABS` blob from Mac Hardware Info.app into a MacOSConfig.
fn parse_oabs_config(b64: &str) -> Result<MacOSConfig, String> {
    use base64::Engine;
    use rustpush::macos::HardwareConfig;
    // Be forgiving about how the OABS blob is encoded:
    //  - strip whitespace/newlines (pasted configs often wrap across lines),
    //  - accept URL-safe base64 (`-`/`_`) by mapping it to the standard alphabet,
    //  - tolerate missing `=` padding.
    let normalized: String = b64
        .chars()
        .filter(|c| !c.is_whitespace())
        .map(|c| match c {
            '-' => '+',
            '_' => '/',
            other => other,
        })
        .filter(|c| *c != '=')
        .collect();
    let raw = base64::engine::general_purpose::STANDARD_NO_PAD
        .decode(&normalized)
        .map_err(|e| format!("base64: {e}"))?;
    let body = raw
        .strip_prefix(b"OABS\x00")
        .ok_or("missing OABS magic")?;
    let outer = parse_pb(body);
    let inner_bytes = match outer.get(&1) {
        Some(PbField::Bytes(b)) => b.clone(),
        _ => return Err("missing inner hw info".into()),
    };
    let inner = parse_pb(&inner_bytes);

    let mac = pb_bytes(&inner, 2);
    let io_mac_address: [u8; 6] = mac.as_slice().try_into().map_err(|_| "bad mac len")?;

    let hw = HardwareConfig {
        product_name: pb_str(&inner, 1),
        io_mac_address,
        platform_serial_number: pb_str(&inner, 3),
        platform_uuid: pb_str(&inner, 4),
        root_disk_uuid: pb_str(&inner, 5),
        board_id: pb_str(&inner, 6),
        os_build_num: pb_str(&inner, 7),
        platform_serial_number_enc: pb_bytes(&inner, 8),
        platform_uuid_enc: pb_bytes(&inner, 9),
        root_disk_uuid_enc: pb_bytes(&inner, 10),
        rom: pb_bytes(&inner, 11),
        rom_enc: pb_bytes(&inner, 12),
        mlb: pb_str(&inner, 13),
        mlb_enc: pb_bytes(&inner, 14),
    };
    let protocol_version = match outer.get(&3) {
        Some(PbField::Varint(v)) => *v as u32,
        _ => 1640,
    };
    Ok(MacOSConfig {
        inner: hw,
        version: pb_str(&outer, 2),
        protocol_version,
        device_id: pb_str(&outer, 4),
        icloud_ua: pb_str(&outer, 5),
        aoskit_version: pb_str(&outer, 6),
        udid: None,
    })
}

fn plist_to_vec<T: Serialize>(v: &T) -> Result<Vec<u8>, plist::Error> {
    let mut buf = Vec::new();
    plist::to_writer_xml(std::io::Cursor::new(&mut buf), v)?;
    Ok(buf)
}

// ---- login -----------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeLogin(
    mut env: JNIEnv,
    _class: JClass,
    email: JString,
    password: JString,
) -> jstring {
    let email = jstr(&mut env, &email);
    let password = jstr(&mut env, &password);

    let out = with_state(move |cell| async move {
        log::info!("[relay] nativeLogin: acquiring state lock");
        let mut guard = cell.lock().await;
        log::info!("[relay] nativeLogin: got state lock, authenticating");
        let s = match guard.as_mut() {
            Some(s) => s,
            None => return err("not initialized"),
        };

        let gsa = s
            .config
            .get_gsa_config(&*s.connection.state.read().await, false);
        let mut account = match AppleAccount::new_with_anisette(gsa, s.anisette.clone()) {
            Ok(a) => a,
            Err(e) => return err(format!("account: {e}")),
        };

        use rustpush::LoginState;
        use sha2::{Digest, Sha256};
        // login_email_pass expects SHA256(raw password), not the raw bytes.
        let hashed_password = Sha256::digest(password.as_bytes());
        let login = match account
            .login_email_pass(&email, &hashed_password)
            .await
        {
            Ok(l) => l,
            Err(e) => return err(format!("login: {e}")),
        };

        match login {
            LoginState::LoggedIn => {
                s.account = Some(Arc::new(DebugMutex::new(account)));
                finish_login(s).await
            }
            LoginState::NeedsDevice2FA => {
                // Push the 6-digit code to the user's trusted Apple devices.
                if let Err(e) = account.send_2fa_to_devices().await {
                    s.account = Some(Arc::new(DebugMutex::new(account)));
                    return err(format!("send 2fa to devices: {e}"));
                }
                s.account = Some(Arc::new(DebugMutex::new(account)));
                ok(serde_json::json!({ "state": "needs_2fa", "kind": "device" }))
            }
            LoginState::Needs2FAVerification => {
                // Code already dispatched.
                s.account = Some(Arc::new(DebugMutex::new(account)));
                ok(serde_json::json!({ "state": "needs_2fa", "kind": "device" }))
            }
            LoginState::NeedsSMS2FA => {
                let _ = account.send_2fa_to_devices().await;
                s.account = Some(Arc::new(DebugMutex::new(account)));
                ok(serde_json::json!({ "state": "needs_2fa", "kind": "sms" }))
            }
            other => {
                s.account = Some(Arc::new(DebugMutex::new(account)));
                err(format!("unexpected login state: {other:?}"))
            }
        }
    });
    ret(&env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeSubmit2fa(
    mut env: JNIEnv,
    _class: JClass,
    code: JString,
) -> jstring {
    let code = jstr(&mut env, &code);
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() {
            Some(s) => s,
            None => return err("not initialized"),
        };
        let account = match s.account.clone() {
            Some(a) => a,
            None => return err("no pending login"),
        };
        use rustpush::LoginState;
        let result = account.lock().await.verify_2fa(code).await;
        match result {
            Ok(LoginState::LoggedIn) => finish_login(s).await,
            Ok(_) => err("2fa incomplete"),
            Err(e) => err(format!("verify 2fa: {e}")),
        }
    });
    ret(&env, out)
}

/// After a successful password/2FA login, fetch IDS delegate + authenticate → IDSUser.
async fn finish_login(s: &mut AviaryImessage) -> String {
    let account = match s.account.clone() {
        Some(a) => a,
        None => return err("no account"),
    };
    let delegates = {
        let acc = account.lock().await;
        match login_apple_delegates(&*acc, None, s.config.as_ref(), &[LoginDelegate::IDS]).await {
            Ok(d) => d,
            Err(e) => return err(format!("delegates: {e}")),
        }
    };
    let ids = match delegates.ids {
        Some(i) => i,
        None => return err("no ids delegate"),
    };
    let user = match authenticate_apple(ids, s.config.as_ref()).await {
        Ok(u) => u,
        Err(e) => return err(format!("authenticate: {e}")),
    };
    s.users = vec![user];
    ok(serde_json::json!({ "state": "logged_in" }))
}

// ---- register --------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeRegister(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = with_state(|cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() {
            Some(s) => s,
            None => return err("not initialized"),
        };
        if s.users.is_empty() {
            return err("not logged in");
        }

        if s.users[0].registration.is_empty() {
            let push = s.connection.state.read().await.clone();
            if let Err(e) = register(
                s.config.as_ref(),
                &push,
                &[&MADRID_SERVICE],
                &mut s.users,
                &s.identity,
            )
            .await
            {
                return err(format!("register: {e}"));
            }
        }

        // Persist and build the live client.
        let saved = SavedState {
            push: s.connection.state.read().await.clone(),
            users: s.users.clone(),
            identity: s.identity.clone(),
        };
        if let Ok(buf) = plist_to_vec(&saved) {
            let _ = std::fs::write(s.config_dir.join("state.plist"), buf);
        }

        let client = build_client(
            s.connection.clone(),
            s.users.clone(),
            s.identity.clone(),
            s.config.clone(),
            s.config_dir.clone(),
        )
        .await;

        let handles = client.identity.get_handles().await;
        s.client = Some(client);
        ok(serde_json::json!({ "state": "registered", "handles": handles }))
    });
    ret(&env, out)
}

// ---- send ------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeSendText(
    mut env: JNIEnv,
    _class: JClass,
    participants_json: JString,
    text: JString,
    reply_guid: JString,
    guid: JString,
) -> jstring {
    let participants_json = jstr(&mut env, &participants_json);
    let text = jstr(&mut env, &text);
    let reply_guid = jstr(&mut env, &reply_guid);
    let guid = jstr(&mut env, &guid);
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() {
            Some(s) => s,
            None => return err("not initialized"),
        };
        let participants: Vec<String> = match serde_json::from_str::<Vec<String>>(&participants_json) {
            Ok(p) => p.iter().map(|h| format_handle(h)).collect(),
            Err(e) => return err(format!("bad participants: {e}")),
        };
        let want_handles = participants.clone();
        // Scope the client borrow so we can update sk_want (focus-status subscriptions) after.
        let send_result = {
            let client = match s.client.as_ref() {
                Some(c) => c,
                None => return err("not registered"),
            };
            let handles = client.identity.get_handles().await;
            let handle = match handles.first() {
                Some(h) => h.clone(),
                None => return err("no handle"),
            };

            let mut normal = NormalMessage::new(text, MessageType::IMessage);
            // Inline reply: point at the quoted message's guid (part 0 = its text body).
            if !reply_guid.is_empty() {
                normal.reply_guid = Some(reply_guid);
                normal.reply_part = Some("0".to_string());
            }
            let mut msg = MessageInst::new(
                ConversationData {
                    participants,
                    cv_name: None,
                    sender_guid: None,
                    after_guid: None,
                },
                &handle,
                Message::Message(normal),
            );
            // Use the caller's pre-generated guid so the DB row already carries it before we
            // send — otherwise a fast Delivered receipt can race in before the id is stored.
            if !guid.is_empty() {
                msg.id = guid;
            }
            client.send(&mut msg).await.map(|_| msg.id)
        };
        match send_result {
            Ok(id) => {
                // Track these recipients so the poll subscribes to their Focus/DND status
                // (for the "Delivered Quietly" label). Mark dirty so we re-subscribe once.
                for h in want_handles {
                    if s.sk_want.insert(h) { s.sk_dirty = true; }
                }
                ok(serde_json::json!({ "guid": id }))
            }
            Err(e) => err(format!("send: {e}")),
        }
    });
    ret(&env, out)
}

/// File extension for a downloaded attachment, by MIME.
fn mime_ext(mime: &str) -> &'static str {
    match mime {
        "image/jpeg" => ".jpg",
        "image/png" => ".png",
        "image/gif" => ".gif",
        "image/heic" => ".heic",
        "image/webp" => ".webp",
        "video/mp4" => ".mp4",
        "video/quicktime" => ".mov",
        _ => ".bin",
    }
}

/// Apple UTI for an outgoing attachment, by MIME.
fn uti_for(mime: &str) -> &'static str {
    match mime {
        "image/jpeg" => "public.jpeg",
        "image/png" => "public.png",
        "image/gif" => "com.compuserve.gif",
        "image/heic" => "public.heic",
        "image/webp" => "org.webmproject.webp",
        "video/mp4" => "public.mpeg-4",
        "video/quicktime" => "com.apple.quicktime-movie",
        _ => "public.data",
    }
}

/// Map a tapback emoji to the classic iMessage reaction, or a custom emoji for anything else.
fn reaction_from_emoji(e: &str) -> Reaction {
    match e {
        "❤️" | "❤" => Reaction::Heart,
        "👍" => Reaction::Like,
        "👎" => Reaction::Dislike,
        "😂" => Reaction::Laugh,
        "‼️" | "‼" => Reaction::Emphasize,
        "❓" | "?" => Reaction::Question,
        other => Reaction::Emoji(other.to_string()),
    }
}

/// Build the ConversationData + our handle for an outbound message to `participants_json`.
async fn convo_for(
    s: &AviaryImessage,
    participants_json: &str,
) -> Result<(ConversationData, String), String> {
    let client = s.client.as_ref().ok_or("not registered")?;
    let participants: Vec<String> = serde_json::from_str::<Vec<String>>(participants_json)
        .map_err(|e| format!("bad participants: {e}"))?
        .iter()
        .map(|h| format_handle(h))
        .collect();
    let handle = client
        .identity
        .get_handles()
        .await
        .first()
        .cloned()
        .ok_or("no handle")?;
    Ok((
        ConversationData { participants, cv_name: None, sender_guid: None, after_guid: None },
        handle,
    ))
}

/// Send a tapback reaction to a message. `enable=false` removes it.
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeSendReaction(
    mut env: JNIEnv,
    _class: JClass,
    participants_json: JString,
    target_guid: JString,
    target_text: JString,
    emoji: JString,
    enable: jni::sys::jboolean,
) -> jstring {
    let participants_json = jstr(&mut env, &participants_json);
    let target_guid = jstr(&mut env, &target_guid);
    let target_text = jstr(&mut env, &target_text);
    let emoji = jstr(&mut env, &emoji);
    let enable = enable != 0;
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() { Some(s) => s, None => return err("not initialized") };
        let (convo, handle) = match convo_for(s, &participants_json).await {
            Ok(v) => v,
            Err(e) => return err(e),
        };
        let client = s.client.as_ref().unwrap();
        let react = ReactMessage {
            to_uuid: target_guid,
            to_part: Some(0),
            reaction: ReactMessageType::React { reaction: reaction_from_emoji(&emoji), enable },
            to_text: target_text,
            embedded_profile: None,
        };
        let mut msg = MessageInst::new(convo, &handle, Message::React(react));
        match client.send(&mut msg).await {
            Ok(_) => ok(serde_json::json!({ "guid": msg.id })),
            Err(e) => err(format!("send: {e}")),
        }
    });
    ret(&env, out)
}

/// Send a typing indicator (true = started typing, false = stopped).
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeSendTyping(
    mut env: JNIEnv,
    _class: JClass,
    participants_json: JString,
    typing: jni::sys::jboolean,
) -> jstring {
    let participants_json = jstr(&mut env, &participants_json);
    let typing = typing != 0;
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() { Some(s) => s, None => return err("not initialized") };
        let (convo, handle) = match convo_for(s, &participants_json).await {
            Ok(v) => v,
            Err(e) => return err(e),
        };
        let client = s.client.as_ref().unwrap();
        let mut msg = MessageInst::new(convo, &handle, Message::Typing(typing, None));
        match client.send(&mut msg).await {
            Ok(_) => ok(serde_json::json!({})),
            Err(e) => err(format!("send: {e}")),
        }
    });
    ret(&env, out)
}

/// Send a read receipt for message `guid` back to the sender.
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeSendRead(
    mut env: JNIEnv,
    _class: JClass,
    participants_json: JString,
    guid: JString,
) -> jstring {
    let participants_json = jstr(&mut env, &participants_json);
    let guid = jstr(&mut env, &guid);
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() { Some(s) => s, None => return err("not initialized") };
        let (convo, handle) = match convo_for(s, &participants_json).await {
            Ok(v) => v,
            Err(e) => return err(e),
        };
        let client = s.client.as_ref().unwrap();
        let mut receipt = MessageInst::new(convo, &handle, Message::Read);
        receipt.id = guid;
        match client.send(&mut receipt).await {
            Ok(_) => ok(serde_json::json!({})),
            Err(e) => err(format!("send: {e}")),
        }
    });
    ret(&env, out)
}

/// Edit a previously-sent message: replace its text with [new_text].
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeSendEdit(
    mut env: JNIEnv,
    _class: JClass,
    participants_json: JString,
    target_guid: JString,
    new_text: JString,
) -> jstring {
    let participants_json = jstr(&mut env, &participants_json);
    let target_guid = jstr(&mut env, &target_guid);
    let new_text = jstr(&mut env, &new_text);
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() { Some(s) => s, None => return err("not initialized") };
        let (convo, handle) = match convo_for(s, &participants_json).await {
            Ok(v) => v,
            Err(e) => return err(e),
        };
        let client = s.client.as_ref().unwrap();
        let edit = EditMessage {
            tuuid: target_guid,
            edit_part: 0,
            new_parts: NormalMessage::new(new_text, MessageType::IMessage).parts,
        };
        let mut msg = MessageInst::new(convo, &handle, Message::Edit(edit));
        match client.send(&mut msg).await {
            Ok(_) => ok(serde_json::json!({})),
            Err(e) => err(format!("send: {e}")),
        }
    });
    ret(&env, out)
}

/// Unsend (retract) a previously-sent message.
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeSendUnsend(
    mut env: JNIEnv,
    _class: JClass,
    participants_json: JString,
    target_guid: JString,
) -> jstring {
    let participants_json = jstr(&mut env, &participants_json);
    let target_guid = jstr(&mut env, &target_guid);
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() { Some(s) => s, None => return err("not initialized") };
        let (convo, handle) = match convo_for(s, &participants_json).await {
            Ok(v) => v,
            Err(e) => return err(e),
        };
        let client = s.client.as_ref().unwrap();
        let unsend = UnsendMessage { tuuid: target_guid, edit_part: 0 };
        let mut msg = MessageInst::new(convo, &handle, Message::Unsend(unsend));
        match client.send(&mut msg).await {
            Ok(_) => ok(serde_json::json!({})),
            Err(e) => err(format!("send: {e}")),
        }
    });
    ret(&env, out)
}

/// Send a media attachment (image/video/file) from a local [path], with an optional caption.
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeSendMedia(
    mut env: JNIEnv,
    _class: JClass,
    participants_json: JString,
    path: JString,
    mime: JString,
    filename: JString,
    caption: JString,
    guid: JString,
) -> jstring {
    let participants_json = jstr(&mut env, &participants_json);
    let path = jstr(&mut env, &path);
    let mime = jstr(&mut env, &mime);
    let filename = jstr(&mut env, &filename);
    let caption = jstr(&mut env, &caption);
    let guid = jstr(&mut env, &guid);
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() { Some(s) => s, None => return err("not initialized") };
        let (convo, handle) = match convo_for(s, &participants_json).await {
            Ok(v) => v,
            Err(e) => return err(e),
        };
        // MMCS needs to read the file twice: once to compute chunk signatures, once to upload.
        let prepared = {
            let file = match std::fs::File::open(&path) {
                Ok(f) => f,
                Err(e) => return err(format!("open: {e}")),
            };
            match MMCSFile::prepare_put(file).await {
                Ok(p) => p,
                Err(e) => return err(format!("prepare: {e}")),
            }
        };
        let attachment = {
            let file = match std::fs::File::open(&path) {
                Ok(f) => f,
                Err(e) => return err(format!("open2: {e}")),
            };
            let name = if filename.is_empty() { "attachment".to_string() } else { filename };
            match Attachment::new_mmcs(&s.connection, &prepared, file, &mime, uti_for(&mime), &name, |_, _| {})
                .await
            {
                Ok(a) => a,
                Err(e) => return err(format!("upload: {e}")),
            }
        };
        let mut parts = vec![IndexedMessagePart {
            part: MessagePart::Attachment(attachment),
            idx: None,
            ext: None,
        }];
        if !caption.is_empty() {
            parts.push(IndexedMessagePart {
                part: MessagePart::Text(caption, Default::default()),
                idx: None,
                ext: None,
            });
        }
        let mut normal = NormalMessage::new(String::new(), MessageType::IMessage);
        normal.parts = MessageParts(parts);
        let mut msg = MessageInst::new(convo, &handle, Message::Message(normal));
        if !guid.is_empty() {
            msg.id = guid;
        }
        match s.client.as_ref().unwrap().send(&mut msg).await {
            Ok(_) => ok(serde_json::json!({ "guid": msg.id })),
            Err(e) => err(format!("send: {e}")),
        }
    });
    ret(&env, out)
}

// ---- reachability (IDS lookup) ---------------------------------------------

/// Normalize a raw address to an IDS handle: "tel:+1..." or "mailto:...".
fn format_handle(a: &str) -> String {
    let a = a.trim();
    if a.starts_with("tel:") || a.starts_with("mailto:") {
        return a.to_string();
    }
    if a.contains('@') {
        return format!("mailto:{a}");
    }
    let digits: String = a.chars().filter(|c| c.is_ascii_digit()).collect();
    let e164 = if a.starts_with('+') {
        format!("+{digits}")
    } else if digits.len() == 10 {
        format!("+1{digits}") // assume US/Canada when no country code
    } else {
        format!("+{digits}")
    };
    format!("tel:{e164}")
}

/// Returns {"reachable": bool} — whether `address` is registered on iMessage.
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeCanReach(
    mut env: JNIEnv,
    _class: JClass,
    address: JString,
) -> jstring {
    let address = jstr(&mut env, &address);
    let out = with_state(move |cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() {
            Some(s) => s,
            None => return err("not initialized"),
        };
        let client = match s.client.as_ref() {
            Some(c) => c,
            None => return err("not registered"),
        };
        let handles = client.identity.get_handles().await;
        let handle = match handles.first() {
            Some(h) => h.clone(),
            None => return err("no handle"),
        };
        let target = format_handle(&address);
        match client
            .identity
            .validate_targets(&[target.clone()], "com.apple.madrid", &handle)
            .await
        {
            Ok(valid) => ok(serde_json::json!({ "reachable": valid.iter().any(|v| v == &target) })),
            Err(e) => err(format!("lookup: {e}")),
        }
    });
    ret(&env, out)
}

// ---- poll (incoming) -------------------------------------------------------

/// Waits up to timeout_ms for one incoming message; returns it as JSON or {ok,empty}.
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativePoll(
    env: JNIEnv,
    _class: JClass,
    timeout_ms: jni::sys::jlong,
) -> jstring {
    let out = with_state(move |cell| async move {
        let dur = std::time::Duration::from_millis(timeout_ms.max(0) as u64);

        // Take the subscription out under a SHORT-lived lock, then RELEASE the global state lock
        // before awaiting recv(). Holding it across the (up to 30s) wait would serialize every other
        // native call behind the poll loop. The subscription lives outside the state only for this await.
        let taken = {
            let mut guard = cell.lock().await;
            let s = match guard.as_mut() {
                Some(s) => s,
                None => return err("not initialized"),
            };
            s.subscription.take()
        };
        // If another poll currently holds the subscription, idle (without the lock) and return empty
        // instead of busy-spinning — keeps the log clean when more than one poll loop is running.
        let mut sub = match taken {
            Some(sub) => sub,
            None => {
                tokio::time::sleep(std::time::Duration::from_millis(500)).await;
                return ok(serde_json::json!({ "empty": true }));
            }
        };

        let recv = tokio::time::timeout(dur, sub.recv()).await;

        // Re-acquire to put the subscription back and process the message.
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() {
            Some(s) => s,
            None => return err("not initialized"),
        };
        s.subscription = Some(sub);

        let aps_msg = match recv {
            Ok(Ok(m)) => m,
            Ok(Err(_)) => return ok(serde_json::json!({ "empty": true })), // lagged/closed
            Err(_) => return ok(serde_json::json!({ "empty": true })),     // timeout
        };

        // Lazily stand up StatusKit once we have a client, so we receive contacts' Focus/DND
        // status (for "Delivered Quietly"). Built off the same push connection + identity.
        if s.statuskit.is_none() {
            if let Some(client) = s.client.as_ref() {
                let sk = build_statuskit(
                    s.connection.clone(),
                    s.anisette.clone(),
                    s.config.clone(),
                    client.identity.clone(),
                    s.config_dir.clone(),
                )
                .await;
                if sk.is_some() {
                    log::info!("[relay] statuskit ready");
                }
                s.statuskit = sk;
            }
        }

        // Feed the message to StatusKit first: it handles Focus key-sharing + status channel
        // updates. A StatusChanged tells us whether a contact currently silences our messages.
        if let Some(sk) = s.statuskit.clone() {
            match sk.handle(aps_msg.clone()).await {
                Ok(Some(StatusKitMessage::StatusChanged { user, allowed, mode })) => {
                    log::info!("[relay] focus status: {user} allowed={allowed} mode={mode:?}");
                    return ok(serde_json::json!({
                        "empty": false, "status_update": true, "user": user, "allowed": allowed,
                    }));
                }
                Ok(None) => {}
                Err(e) => log::warn!("[relay] statuskit handle: {e}"),
            }
            // Only (re)subscribe when the set of people we message changed — avoids churning the
            // presence channels (and waking the radio) on every poll.
            if s.sk_dirty && !s.sk_want.is_empty() {
                let want: Vec<String> = s.sk_want.iter().cloned().collect();
                s.sk_token = Some(sk.request_handles(&want).await);
                s.sk_dirty = false;
            }
        }

        let client = match s.client.as_ref() {
            Some(c) => c,
            None => return ok(serde_json::json!({ "empty": true })),
        };

        match client.handle(aps_msg).await {
            Ok(Some(m)) => {
                // Delivery/read receipts acknowledge one of our sent messages (m.id = its guid).
                let kind = msg_kind(&m.message);
                if kind == "delivered" || kind == "read" {
                    log::info!("[relay] receipt {} for {}", kind, m.id);
                    return ok(serde_json::json!({
                        "empty": false, "receipt": kind, "guid": m.id,
                    }));
                }
                let handles = client.identity.get_handles().await;
                let sender = m.sender.clone().unwrap_or_default();
                let from_me = handles.iter().any(|h| h == &sender);
                // Classic iMessage: ack the sender so they see "Delivered" and Apple stops
                // re-delivering this message. The receipt reuses the original's guid.
                if !from_me && m.send_delivered {
                    if let Some(convo) = m.conversation.clone() {
                        let my_handle = convo
                            .participants
                            .iter()
                            .find(|p| handles.iter().any(|h| h == *p))
                            .cloned()
                            .or_else(|| handles.first().cloned())
                            .unwrap_or_default();
                        let mut receipt = MessageInst::new(convo, &my_handle, Message::Delivered);
                        receipt.id = m.id.clone();
                        match client.send(&mut receipt).await {
                            Ok(_) => log::info!("[relay] sent Delivered for {}", m.id),
                            Err(e) => log::warn!("[relay] delivered-receipt failed: {e}"),
                        }
                    }
                }
                // Tapback/reaction on a message.
                if let Message::React(r) = &m.message {
                    if let ReactMessageType::React { reaction, enable } = &r.reaction {
                        let from = if from_me { "me".to_string() } else { sender.clone() };
                        return ok(serde_json::json!({
                            "empty": false, "reaction": true, "target": r.to_uuid,
                            "emoji": reaction_emoji(reaction), "enable": enable, "from": from,
                        }));
                    }
                    return ok(serde_json::json!({ "empty": true }));
                }
                // Typing indicator from the other party.
                if let Message::Typing(active, _) = &m.message {
                    let parts: Vec<String> = m.conversation.as_ref()
                        .map(|c| c.participants.clone()).unwrap_or_default();
                    let mut others: Vec<String> = parts.iter()
                        .filter(|p| !handles.iter().any(|h| h == *p)).cloned().collect();
                    others.sort();
                    if others.is_empty() { others.push(sender.clone()); }
                    return ok(serde_json::json!({
                        "empty": false, "typing": *active, "chat": others.join(";"),
                    }));
                }
                // Message edited or unsent by the other party.
                if let Message::Edit(e) = &m.message {
                    return ok(serde_json::json!({
                        "empty": false, "edit": true, "target": e.tuuid, "text": e.new_parts.raw_text(),
                    }));
                }
                if let Message::Unsend(u) = &m.message {
                    return ok(serde_json::json!({ "empty": false, "unsend": true, "target": u.tuuid }));
                }
                let text = normal_text(&m.message);
                let reply_to = if let Message::Message(n) = &m.message {
                    n.reply_guid.clone()
                } else {
                    None
                };
                let participants: Vec<String> = m
                    .conversation
                    .as_ref()
                    .map(|c| c.participants.clone())
                    .unwrap_or_default();
                // Conversation key = the OTHER participants (everything that isn't one of my
                // own handles), sorted. Same key whether I sent or received.
                let mut others: Vec<String> = participants
                    .iter()
                    .filter(|p| !handles.iter().any(|h| h == *p))
                    .cloned()
                    .collect();
                others.sort();
                if others.is_empty() && !from_me {
                    others.push(sender.clone());
                }
                let chat = others.join(";");
                let address = others.first().cloned().unwrap_or_else(|| sender.clone());

                // Download the first attachment (image/video/file) to a local file, if any.
                let mut media_path: Option<String> = None;
                let mut media_mime: Option<String> = None;
                if let Message::Message(n) = &m.message {
                    if let Some(part) =
                        n.parts.0.iter().find(|p| matches!(p.part, MessagePart::Attachment(_)))
                    {
                        if let MessagePart::Attachment(att) = &part.part {
                            let dir = s.config_dir.join("attachments");
                            let _ = std::fs::create_dir_all(&dir);
                            let path = dir.join(format!("{}{}", m.id, mime_ext(&att.mime)));
                            match std::fs::File::create(&path) {
                                Ok(file) => match att.get_attachment(&s.connection, file, |_, _| {}).await {
                                    Ok(_) => {
                                        media_path = Some(path.to_string_lossy().into_owned());
                                        media_mime = Some(att.mime.clone());
                                    }
                                    Err(e) => log::warn!("[relay] attachment download failed: {e}"),
                                },
                                Err(e) => log::warn!("[relay] attachment file: {e}"),
                            }
                        }
                    }
                }
                // The caption is the text parts only; strip the object-replacement placeholders
                // that stand in for attachments in raw_text.
                let clean_text =
                    text.as_ref().map(|t| t.replace('\u{fffc}', "").replace('\u{fffd}', ""));

                log::info!(
                    "[relay] recv id={} sender={} from_me={} kind={} media={} parts={:?}",
                    m.id, sender, from_me, msg_kind(&m.message), media_path.is_some(), participants
                );
                let has_content = media_path.is_some()
                    || clean_text.as_deref().map(|t| !t.is_empty()).unwrap_or(false);
                if !has_content {
                    // Non-text (delivery/read receipt, typing, etc.) — no inbox row yet.
                    ok(serde_json::json!({ "empty": true }))
                } else {
                    ok(serde_json::json!({
                        "empty": false,
                        "guid": m.id,
                        "sender": sender,
                        "from_me": from_me,
                        "chat": chat,
                        "address": address,
                        "text": clean_text.unwrap_or_default(),
                        "reply_to": reply_to,
                        "timestamp": m.sent_timestamp,
                        "mentioned": mentions_me(&m.message, &handles),
                        "media_path": media_path,
                        "media_mime": media_mime,
                    }))
                }
            }
            Ok(None) => ok(serde_json::json!({ "empty": true })),
            Err(e) => err(format!("handle: {e}")),
        }
    });
    ret(&env, out)
}

/// Diagnostic: the iMessage handles this identity is registered with (emails, and — if
/// the Apple ID has it — the tel: phone number, inherited like a Mac gets it).
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeHandles(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = with_state(|cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() {
            Some(s) => s,
            None => return err("not initialized"),
        };
        let client = match s.client.as_ref() {
            Some(c) => c,
            None => return err("no client"),
        };
        let handles = client.identity.get_handles().await;
        log::info!("[relay] handles = {:?}", handles);
        ok(serde_json::json!({ "handles": handles }))
    });
    ret(&env, out)
}

/// Diagnostic: generate anisette headers via the on-device ADI provider and return them.
/// Reveals whether the local ADI is producing valid-looking anisette (X-Apple-I-MD*).
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeTestAnisette(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = with_state(|cell| async move {
        let mut guard = cell.lock().await;
        let s = match guard.as_mut() {
            Some(s) => s,
            None => return err("not initialized"),
        };
        let mut anis = s.anisette.lock().await;
        let map: std::collections::HashMap<String, String> = match anis.get_headers().await {
            Ok(h) => h.clone(),
            Err(e) => return err(format!("anisette: {e}")),
        };
        ok(serde_json::json!({ "headers": map }))
    });
    ret(&env, out)
}

fn normal_text(m: &Message) -> Option<String> {
    match m {
        Message::Message(n) => Some(n.parts.raw_text()),
        _ => None,
    }
}

/// True if this message @-mentions one of our own handles (used to decide whether a muted
/// group should still notify).
fn mentions_me(m: &Message, handles: &[String]) -> bool {
    if let Message::Message(n) = m {
        n.parts.0.iter().any(|p| {
            matches!(&p.part, MessagePart::Mention(uri, _) if handles.iter().any(|h| h == uri))
        })
    } else {
        false
    }
}

/// The emoji for a tapback reaction (standard tapbacks + custom emoji).
fn reaction_emoji(r: &Reaction) -> Option<String> {
    Some(
        match r {
            Reaction::Heart => "❤️",
            Reaction::Like => "👍",
            Reaction::Dislike => "👎",
            Reaction::Laugh => "😂",
            Reaction::Emphasize => "‼️",
            Reaction::Question => "❓",
            Reaction::Emoji(e) => return Some(e.clone()),
            _ => return None,
        }
        .to_string(),
    )
}

fn msg_kind(m: &Message) -> &'static str {
    match m {
        Message::Message(_) => "text",
        Message::Delivered => "delivered",
        Message::Read => "read",
        Message::Typing(..) => "typing",
        Message::React(_) => "react",
        Message::Edit(_) => "edit",
        Message::Unsend(_) => "unsend",
        _ => "other",
    }
}

/// Smoke test: emulate x86-64 `mov rax, 0x1234; ret` under Unicorn and return RAX.
/// Proves the embedded CPU emulator runs on-device (foundation for on-device NAC).
/// Returns 0x1234 on success, 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_sidephone_aviary_imessage_ImessageNative_nativeUcSmoke(
    _env: JNIEnv,
    _class: JClass,
) -> jni::sys::jlong {
    use unicorn_engine::unicorn_const::{Arch, Mode, Prot};
    use unicorn_engine::{RegisterX86, Unicorn};
    let code: [u8; 8] = [0x48, 0xC7, 0xC0, 0x34, 0x12, 0x00, 0x00, 0xC3];
    let run = || -> Result<u64, unicorn_engine::unicorn_const::uc_error> {
        let mut uc = Unicorn::new(Arch::X86, Mode::MODE_64)?;
        uc.mem_map(0x1000, 0x1000, Prot::ALL)?;
        uc.mem_write(0x1000, &code)?;
        uc.emu_start(0x1000, 0x1000 + code.len() as u64 - 1, 0, 0)?;
        uc.reg_read(RegisterX86::RAX)
    };
    run().map(|v| v as jni::sys::jlong).unwrap_or(0)
}
