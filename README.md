# Relay

One inbox for all my messages — SMS/MMS, iMessage, Signal, and Instagram DMs — running entirely on the phone. There's no relay server in the middle despite the name; everything talks straight to each service, and your credentials and messages never leave the device except as that service's own normal traffic.

I built it for my Sidephone SP-01 (arm64, Android 12), but it's a regular Android app.

## Why

I was tired of juggling four apps, and I especially didn't want a middleman server holding my logins to make iMessage work on Android. Most of the options out there (Beeper, AirMessage, the usual OpenBubbles setups) lean on a Mac you leave running or a server somewhere. I wanted the whole thing to happen locally, on the phone, with nothing of mine sitting on someone else's box.

## The iMessage part

This is the interesting bit, so it's worth explaining.

Getting iMessage working on a non-Apple device means convincing Apple's identity servers that you're a real Apple device. Normally that's done by a Mac or a server that signs things for you. Relay does all of it on the phone instead, built on top of OpenBubbles' [rustpush](https://github.com/OpenBubbles/rustpush) engine with a thin JNI layer around it.

Here's the chain that runs locally when you sign in:

- **Activation.** The app activates against Apple's `albert.apple.com` using a bundled Apple device certificate, and gets back a real device cert and a push token — the same handshake a genuine device does.
- **Anisette.** Apple wants some device-attestation headers on every request. Instead of phoning a public anisette server for those (which is what a lot of clients do), Relay loads Apple's own `libstoreservicescore` library on-device through an ELF loader and generates them itself. Those Apple libraries ride along in the app's assets.
- **Sign-in.** Normal Apple ID login and the 2-factor code, straight to Apple.
- **The hard part — validation data.** Apple's registration step needs a signed blob that's normally produced by a real device's `IMDAppleServices` binary. Relay produces it by literally *emulating that binary* on the phone inside a small x86-64 CPU emulator (unicorn), faking out the ~37 CoreFoundation/IOKit calls it makes so it thinks it's running on your Mac's hardware. It's a port of the approach pypush pioneered. This was, by a wide margin, the fussiest thing to get right.
- **Register.** Your email handles register with Apple and messages turn blue.

### You need a Mac config, once

That emulation step needs a hardware identity to pretend to be. You generate it one time by running OpenBubbles' "Mac Hardware Info" tool on a Mac you actually own — it spits out a blob with the serial, board id, ROM, and the FairPlay-encrypted device keys. You paste that into Relay during setup and it's stored on the phone. It's not in this repo, and it never goes anywhere except to Apple as part of registering.

One honest limitation: a Mac's identity only lets you register your **email** iMessage handles. Registering your **phone number** to iMessage needs iPhone-class validation data, which you can't get this way — so texts to your number stay green (SMS). Email iMessage works fully.

## The rest

Signal connects as a linked device (like linking Signal Desktop), Instagram does DMs, and Relay sets itself as the system SMS/MMS app. Everything lands in one shared inbox with one notifier.

## Getting a build

Every push builds a signed release APK through GitHub Actions. If I push a version tag it also drops the APK onto a GitHub Release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Otherwise the APK is sitting in the workflow run's artifacts. Download `relay-<version>.apk` and sideload it.

## Building it yourself

The app itself is Kotlin + Compose and builds the normal way with Gradle (`./gradlew :app:assembleRelease`, JDK 17). The native iMessage library (`app/src/main/jniLibs/arm64-v8a/libaviary_imessage.so`) is checked in already-compiled, because its Rust source (my fork of rustpush plus the JNI glue) lives outside this repo and needs Apple binaries to build — so CI and a normal Gradle build just package the existing `.so`. And yes, a GitHub-built APK does full on-device iMessage exactly like a local one; it still asks for the Mac config and your Apple ID on first run.

Releases are signed with a checked-in Android debug key (the well-known `android` password), so every build — mine, yours, or CI's — comes out with the same signature and can update over the last one.

## Credit

The heavy lifting on the iMessage protocol is [OpenBubbles / rustpush](https://github.com/OpenBubbles/rustpush) and the pypush project it descends from. Relay is the Android app and the on-device wiring around them.
