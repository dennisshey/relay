# Relay

One inbox for everything I text on: SMS/MMS, iMessage, Signal, and Instagram DMs, all running on the phone itself. The name is a bit of a misnomer, since there's no relay server in the middle. Each account talks straight to its own service, and nothing of mine (logins, messages) lands on anyone else's machine.

I wrote it for my Sidephone SP-01 (arm64, Android 12), but it's an ordinary Android app.

## Why

I was sick of bouncing between four apps, and I really didn't want some middleman server holding my Apple login just to get iMessage on Android. The existing options (Beeper, AirMessage, most OpenBubbles setups) lean on a Mac you keep running or a server somewhere. I wanted it all to happen locally, on the phone, with none of my stuff sitting on a box I don't control.

## iMessage

This is the part worth explaining.

Running iMessage on a non-Apple device comes down to convincing Apple's identity servers that you're a real Apple device. Usually a Mac or a server does that signing for you. Relay does it on the phone, on top of OpenBubbles' [rustpush](https://github.com/OpenBubbles/rustpush) engine with a thin JNI layer wrapped around it.

What runs locally when you sign in:

Activation. The app activates against Apple's `albert.apple.com` with a bundled Apple device certificate and gets back a real device cert plus a push token.

Anisette. Apple wants device-attestation headers (`X-Apple-I-MD*`) on its requests. Plenty of clients fetch those from a public anisette server; Relay instead loads Apple's own `libstoreservicescore` library on-device through an ELF loader and produces them itself. Those Apple libraries ship in the app's assets.

Sign-in. Ordinary Apple ID login plus the 2-factor code, direct to Apple.

Validation data. This was the hard one. Registration needs a signed blob that a genuine device's `IMDAppleServices` binary produces. Relay produces it by emulating that binary on the phone inside a small x86-64 CPU emulator (unicorn), answering the roughly 37 CoreFoundation and IOKit calls it makes so it believes it's running on your Mac's hardware. It's a port of what pypush figured out.

Register. Your email handles register and messages go blue.

### The one-time Mac config

The emulation needs a hardware identity to imitate. You make it once by running OpenBubbles' "Mac Hardware Info" tool on a Mac you own; it produces a blob with the serial, board id, ROM, and the FairPlay-encrypted device keys. You paste that into Relay at setup and it stays on the phone. It isn't in this repo, and it only ever goes to Apple as part of registering.

A limit worth knowing: a Mac's identity registers your email iMessage handles only. Your phone number needs iPhone-class validation data, which this approach can't produce, so texts to your number stay green over SMS. Email iMessage is fully there.

## Signal

Relay joins your Signal account as a linked device, the same way Signal Desktop does. You open Signal on your phone, go to Linked Devices, and scan the QR code Relay shows. Under the hood that's a provisioning handshake: Relay generates an ephemeral keypair, your primary phone encrypts the account keys to it, and they pass through Signal's server without the server ever seeing them. Your existing phone stays the primary device and Relay is just a second linked one, so adding it doesn't touch or re-register your number.

From there it speaks the real Signal protocol through Signal's own `libsignal` library, so messages are end-to-end encrypted the normal way. A few details it handles: the linked-device name is encrypted before it's sent, so the server never learns what you named it; the protocol store (identity keys, sessions, sender keys) is persisted so the link survives restarts; and it shows Signal's own Delivered/Read status on messages you send.

## Instagram

Instagram DMs go over Instagram's private mobile API. You log in with your username and password. If Instagram asks for a 2-factor code, or throws a checkpoint because it doesn't recognize the login, Relay walks you through it (sometimes that means approving the login once in the real Instagram app and then retrying). It keeps a stable device fingerprint so Instagram doesn't treat every launch as a brand-new device, and it stores the session encrypted on the phone. You get sending, receiving, and Sent/Seen status on your threads.

## Getting a build

Every push builds a signed release APK in GitHub Actions. Push a version tag and it also uploads the APK to a GitHub Release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Without a tag, the APK is in the workflow run's artifacts. Grab `relay-<version>.apk` and sideload it.

## Building it yourself

The app is Kotlin and Compose, built the normal way with Gradle (`./gradlew :app:assembleRelease`, JDK 17). The native iMessage library (`app/src/main/jniLibs/arm64-v8a/libaviary_imessage.so`) is checked in already compiled. Its Rust source (my fork of rustpush plus the JNI glue) lives outside this repo and needs Apple binaries to build, so CI and a plain Gradle build just package the existing `.so`. A CI-built APK does full on-device iMessage just like a local one; it still asks for the Mac config and your Apple ID the first time you run it.

Releases are signed with a checked-in Android debug key (the standard `android` password), so every build (mine, yours, CI's) shares one signature and can update over the last.

## Credit

The real work on the iMessage protocol is [OpenBubbles / rustpush](https://github.com/OpenBubbles/rustpush) and the pypush project it grew out of. Relay is the Android app and the on-device wiring around them.
