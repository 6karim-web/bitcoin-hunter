# Bitcoin ECKey Benchmark — Android App

A performance benchmark tool that measures how many `ECKey` (secp256k1 keypair) objects your Android device's CPU can generate per second, using the **bitcoinj-core 0.16.2** library.

## What it does

- Generates random Bitcoin private keys using `ECKey` (bitcoinj)
- Derives the legacy (P2PKH / `1xxx`) Bitcoin address for each key
- Displays a live **keys/second** counter and total count
- Logs sample key/address snippets to a scrolling terminal console
- Optionally compares each generated address against a **Target Address** (string comparison test) — shows an AlertDialog with the WIF key if matched

## Setup

### Requirements

- Android Studio Hedgehog (2023.1) or newer
- Android SDK 34
- JDK 17
- Device or emulator running Android 8.0+ (API 26+)

### Open in Android Studio

1. Clone or download this project
2. Open **Android Studio → File → Open** and select this folder
3. Wait for Gradle sync to complete (downloads bitcoinj and dependencies)
4. Run on a physical device or emulator (`Shift+F10`)

## Key Dependencies

```gradle
implementation 'org.bitcoinj:bitcoinj-core:0.16.2'
```

Configured in `app/build.gradle` with packaging conflict resolution for duplicate META-INF files that bitcoinj's transitive dependencies introduce.

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

INTERNET permission is declared in the manifest as required for bitcoinj's peer discovery infrastructure (even if not actively connecting peers in this benchmark).

## Architecture

- `MainActivity.kt` — single-activity app
- Benchmark runs on a daemon background `Thread` (not coroutines, for minimal overhead)
- `AtomicLong` counters for thread-safe key counting without locking overhead
- UI updates via `Handler(Looper.getMainLooper())` every 1 second
- Console is capped at 200 lines to prevent unbounded memory growth

## Educational Purpose

This app is a CPU performance benchmark and an educational demonstration of how Bitcoin private keys are generated. The secp256k1 key space has 2²⁵⁶ possible values — randomly finding a specific funded wallet is computationally infeasible.
