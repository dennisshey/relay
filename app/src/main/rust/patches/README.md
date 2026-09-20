# Rebuilding `libaviary_imessage.so`

The native iMessage library builds from `../aviary_imessage` against OpenBubbles' rustpush.
rustpush is not vendored here — it is cloned into a scratch directory whose absolute path is
hard-coded in `aviary_imessage/Cargo.toml`, and that directory does not survive between
sessions. These patches are what has to be re-applied to a fresh clone, so a wipe costs an
afternoon instead of a week.

Upstream commits these were generated against:

| repo | path | commit |
| --- | --- | --- |
| rustpush | (root) | `f35c4ee062b3c3eae54dc96b89b90ee99f5e1d0c` |
| apple-private-apis | `third_party/apple-private-apis` | `b8598d2c1656ae73fbe0f1be1b18222ea7cadb0a` |
| open-absinthe | `open-absinthe` | `1f8dc73a311e7b4d94a868972a6816c8a2c14e44` |

## Restore

```sh
SCRATCH=$(grep -o '/[^"]*/rustpush' ../aviary_imessage/Cargo.toml | head -1)
git config --global url."https://github.com/".insteadOf "git@github.com:"
git clone --recursive https://github.com/OpenBubbles/rustpush.git "$SCRATCH"
git -C "$SCRATCH" apply "$PWD/01-rustpush.patch"
git -C "$SCRATCH/third_party/apple-private-apis" apply "$PWD/02-apple-private-apis.patch"
git -C "$SCRATCH/open-absinthe" apply "$PWD/03-open-absinthe.patch"
# The NAC emulator runs Apple's own binary, which lives in this repo, not in the patch.
cp ../aviary_imessage/vendor/IMDAppleServices "$SCRATCH/open-absinthe/src/IMDAppleServices"
```

Then build, from `../aviary_imessage`:

```sh
export ANDROID_NDK_HOME=/opt/homebrew/share/android-commandlinetools/ndk/26.3.11579264
export CMAKE_TOOLCHAIN_FILE=$PWD/android_arm64.cmake   # unicorn's QEMU needs it
cargo ndk -t arm64-v8a -o ../../jniLibs build --release
rm -rf jniLibs                                          # a stray copy lands here
```

## What the patches do

**01-rustpush** — Points `FAIRPLAY_KEYS` at the committed `certs/legacy-fairplay` cert; the
`certs/fairplay/4056…` certs it ships referencing are not in the repo, and albert.apple.com
rejects them anyway (it answers with an empty `<dict/>`, surfacing as `AlbertCertParseError`).
Makes the `<Protocol>` regex span newlines. Strips `tel:` handles before registering, since
Mac-class validation data only registers email handles and a phone handle fails the whole
request with status 6001. Drops the anisette provider's `X-Mme-Client-Info` and sends the
OSConfig's device id instead — the provider's own values disagree with the Mac identity we
present, and Apple answers that mismatch with an UNAUTHORIZED "add a trusted phone number".

**02-apple-private-apis** — Restores the on-device Apple ADI provider (`adi_proxy.rs`,
`store_services_core.rs`, `anisette_headers_provider.rs`) that upstream deleted, from commit
`91e6890~1`, and adds a `local-anisette` feature plus an `SSCAnisetteProvider` that implements
the current `AnisetteProvider` trait on top of it. Without this the only options are a public
anisette server or OpenBubbles' ClearADI, whose open crate is a `todo!()` stub — this app
generates anisette on the phone and talks to no third-party server.

**03-open-absinthe** — The NAC port. Upstream's `ValidationCtx` is three `todo!()`s; this runs
Apple's `IMDAppleServices` (x86-64 Mach-O, vendored) under unicorn and answers the ~37
CoreFoundation/IOKit calls it makes with the configured Mac identity. Ported from pypush
`emulated/{nac,jelly}.py` @ `09aea207`.

`tests/nac_reference.rs` checks it end to end against pypush's published reference hardware.
Byte length is the signal: a hardware hook that silently misses shortens the blob and Apple
rejects registration with an opaque 6001. A healthy run is **338-byte request, 698-byte
session-info, 517-byte validation data**; ROM and MLB dropping out takes the last to 389.

```sh
cd "$SCRATCH/open-absinthe"
NAC_REF_DIR=/tmp/nacref NAC_REF_PYTHON=/path/to/python-with-requests \
  cargo test --release -- --ignored --nocapture
```

(`NAC_REF_PYTHON` needs `requests`: Apple's `initializeValidation` answers curl and urllib
with status 5003 but accepts requests. `NAC_REF_DIR` needs a `refhw.json` holding pypush's
`emulated/data.plist` values — the test names the fields it reads.)
