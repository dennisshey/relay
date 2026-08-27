# Aviary roadmap

Architecture rule that everything below follows: **credentials and plaintext messages
never leave the SP-01**. Any protocol that can't be done that way doesn't get done.

## Milestone 1 — finish Signal (linked device)

Done and verified on-device:
- Provisioning websocket, QR generation, ProvisionEnvelope decryption
  (`transport/signal/`).
- `libsignal-android:0.86.5` integrated (needs Kotlin 2.1 + core-library
  desugaring + arm64 abiFilter; the `libsignal_jni_testing.so` is excluded).
- **Signal CA trust**: `chat.signal.org` uses Signal's private CA, so we bundle
  `res/raw/whisper_store.bks` (Signal-Android's keystore, alias `signal-messenger-ca`,
  password `whisper`) and trust exactly it — `SignalTrust`. Verified: the QR now
  renders, i.e. TLS to Signal's servers succeeds and the handshake reaches the
  scan step.

Done and VERIFIED against live servers (linked a real device — appears in
Signal → Linked devices as "device N"):
- **Key generation** (`SignalKeys`): registration IDs, signed prekeys, Kyber
  last-resort + one-time prekeys for both ACI and PNI, via libsignal.
- **Device-name encryption** (`DeviceName`).
- **Registration** (`SignalApi.linkDevice`): `PUT /v1/devices/link` with account
  attributes + prekeys, then one-time prekey upload; credentials stored encrypted
  (`SignalAccount`).

Two bugs found and fixed while getting the link to succeed:
- **409 on link** = the account requires capabilities the new device must declare
  (`DeviceController.isCapabilityDowngrade`). The `capabilities` map must include
  `spqr` (required for all new devices) + the account's non-downgradeable
  capabilities. We now send `{spqr, usernameChangeSyncMessage, storage,
  profiles_v2, optionalPhoneNumber}` — all `true`.
- **"Invalid QR"** = a reused provisioning socket showed a stale UUID (Signal's
  provisioning address expires in minutes). `beginLinking` now tears down any
  prior session and fetches a fresh UUID each time.

ProvisionMessage field numbers in `SignalTransport.onProvisionMessage` (aci=8,
pni=10, pniIdentityKey pub/priv=11/12) are confirmed correct by the successful link.

Receive loop — BUILT (verified: websocket authenticates + connects):
- `SignalReceiver`: authenticated websocket to
  `wss://chat.signal.org/v1/websocket/?login=<aci.deviceId>&password=…`; parses
  `WebSocketMessage` → `Envelope`; decrypts double-ratchet (type 1) and prekey
  (type 3) via `SessionCipher`, and sealed-sender (type 6) via
  `SealedSessionCipher` with the production trust roots; parses `Content` →
  `DataMessage.body`; writes into `UnifiedRepository`; acks each frame.
- `AviaryProtocolStore`: persistent `SignalProtocolStore` (in-memory + Keystore-
  encrypted prefs), re-seeded on restart.
- `SignalReceiveService`: foreground service (`dataSync`) keeping the socket alive;
  started from `MainActivity` when linked.
- Registration now persists the ACI pre-keys' PRIVATE halves into the store.

⚠️ **Re-link required to receive on an existing link.** A device linked before this
milestone uploaded pre-keys whose private halves were never stored, so it can't
decrypt new sessions. Unlink the old "Aviary (SP-01)" in Signal and link again.

Proto field numbers used (from SignalService.proto): Envelope type=1, clientTs=5,
sourceDeviceId=7, content=8, serverTs=10, sourceServiceId=11; Content.dataMessage=1;
DataMessage.body=1, timestamp=7.

Not yet handled on receive: groups, attachments, sync/sent-transcript messages
(so your own Note-to-Self won't show), PNI-addressed messages, receipts/typing.

Known robustness gaps (follow-up):
- **No deauth detection / auto-reconnect.** Unlinking the device on the primary
  phone doesn't update Aviary — it keeps stored creds and shows "Connected" until
  a re-link. The receiver also doesn't reconnect after a drop. TODO: on websocket
  close/401, set status to NeedsSetup("Re-link"), and add backoff reconnect while
  registered. The foreground-service notification text is currently static.

Remaining (next step):
- **Send**: session establishment (fetch recipient prekey bundles from
  `/v2/keys/<serviceId>/*`) + encrypt via `SessionCipher`/`SealedSessionCipher` +
  `PUT /v1/messages/<recipient>`; wire `SignalTransport.sendText`.

### How to test the linking (milestone 1)
Open **Accounts → Signal → Link device**, then on your primary Signal phone go to
**Settings → Linked devices → Link new device** and scan the QR. On success Aviary
should appear in that Linked-devices list and the screen shows "Linked as device N".
This uses your real account and counts against Signal's 5-linked-device limit; you
can unlink it from the same screen.

Reference implementations: signal-cli (`AsamK/signal-cli`) and Molly
(`mollyim/mollyim-android`) — both use the same linked-device flow.

## Milestone 2 — iMessage via rustpush

[rustpush](https://github.com/TaeHagen/rustpush) is the reverse-engineered
iMessage/APNs client that OpenBubbles ships. Integration plan:

1. Cross-compile rustpush for `aarch64-linux-android` (cargo-ndk).
2. Wrap send/receive/login in a small JNI/UniFFI layer.
3. `IMessageTransport` implements the existing `MessageTransport` interface.
4. Requires Apple **hardware identifiers from a Mac you own** (OpenBubbles' documented
   flow) — one-time extraction, stored encrypted on-device.

Risk: Apple periodically breaks third-party clients; expect maintenance. Using your own
Mac's identifiers with your own Apple ID keeps this personal-use.

## Milestone 3 — Instagram DMs (deliberately last)

Meta offers **no API for personal DMs**. Options, all unofficial:

- MQTT-over-realtime client (what `mautrix-meta` / messagix implements, in Go).
- Instagram private web API (fragile, aggressive bot detection).

Both risk an account ban and need constant upkeep. Recommendation: keep the stub until
milestones 1–2 are stable, then port messagix's protocol. The transport interface is
ready when the decision is made.

## App-level backlog

- MMS download/attachments (transacted PDU fetch via carrier MMSC).
- RCS is not feasible without Google's Jibe backend — SMS/MMS only.
- Contact avatars, message search, per-conversation notification settings.
- Backup/restore of the encrypted store.
