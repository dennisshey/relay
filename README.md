# Relay

An on-device unified messaging app for Android — **SMS/MMS, iMessage, Signal, and Instagram DMs** in one inbox, with **no relay server**. Credentials and messages stay encrypted on the device; only each protocol's own traffic leaves the phone, direct to its service.

Built for the Sidephone **SP-01** (arm64, Android 12).

## Highlights

- **iMessage** fully on-device via OpenBubbles' [`rustpush`](https://github.com/OpenBubbles/rustpush): activation, anisette (Apple's `libstoreservicescore` ADI), Apple ID login + 2FA, and NAC "validation data" generated locally by emulating `IMDAppleServices` under a bundled CPU emulator — no Mac relay.
- **Signal** as a linked device, **Instagram** DMs, and **SMS/MMS** as the system default messaging app.
- One shared cross-transport notifier and inbox.

## Releases

Every push builds a signed release APK via GitHub Actions (see `.github/workflows/release.yml`). Pushing a tag like `v0.1.0` also publishes the APK to a GitHub Release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Grab `relay-<version>.apk` from the release (or from the workflow's artifacts) and sideload it.

## Building

The Kotlin/Compose app builds with Gradle (`./gradlew :app:assembleRelease`, JDK 17). The native iMessage library (`app/src/main/jniLibs/arm64-v8a/libaviary_imessage.so`) is **committed prebuilt** — its Rust source (a fork of `rustpush` + JNI glue) is developed out-of-tree, so CI and normal Gradle builds just package the existing binary.

Release APKs are signed with the committed Android **debug key** (well-known password `android`) so every build — CI or local — shares one signature and stays update-compatible.
