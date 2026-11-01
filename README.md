# CorePulse

Mine XCB (Core Coin) on your Android phone: one screen, one Core address, start.
CorePulse computes for the pool [Catch That Rabbit](https://catchthatrabbit.com), which pays XCB to your address.

CorePulse is an independent project by [Tell Me More About Core (TMMAC)](https://tellmemoreaboutcore.com), not an
official app of Core or CoDeTech. [Deutsch: README.de.md](README.de.md)

## Download

- **Download page:** [tellmemoreaboutcore.com/corepulse](https://tellmemoreaboutcore.com/corepulse/)
- **Direct:** [latest release on GitHub](https://github.com/tmmac-core/corepulse/releases/latest)
  (`CorePulse-<version>.apk` and `SHA256SUMS`)

Runs on **Android 9 or newer**, only on phones with a **64-bit ARM processor (arm64)**, which is nearly every
phone from the last years. CorePulse is not in the Play Store, because Google and Apple do not allow mining on the
device in their stores. Android asks once to allow installs from your browser or file manager.

### Check that the APK is genuine

Every release is signed with the TMMAC release key. The certificate never changes:

```
SHA-256: c63d4ca2ccb8987ce294c2594d3bc288414cc96f8334cd8b6333a3aed08ed808
```

```bash
shasum -a 256 -c SHA256SUMS --ignore-missing            # file checksum
apksigner verify --print-certs CorePulse-<version>.apk  # certificate (Android SDK build-tools)
```

Android itself only installs an update if it is signed with the same key as the installed app.

## How it works

1. Enter (or scan) your **Core address** and pick how much power to use. Your coins go straight from the pool to
   this address; CorePulse never holds them.
2. Read and accept the consent box: it explains load, heat, battery, electricity, the support share and what the
   pool sees.
3. Tap **Start**. Mining runs as a foreground service with a notification; the app shows hash rate, shares and
   earnings from the pool.

**Heat and battery:** if the battery gets too warm, CorePulse pauses by itself and stops if the phone does not cool
down. At low battery it stops so the phone does not run empty. Best to mine while charging from a wall charger.

**Support share (0.42 %):** every 100 minutes, at a random moment, the app computes for 25 seconds for the TMMAC
address `cb292120d3abc3b8330a26daf4beb94b1f6a9c7b4963` (worker `app-support`), over a short second connection to
the same Catch That Rabbit server. That is 0.42 % of the computing time, fixed and the same for everyone; this is
how development is funded. No money flows through the app. Code:
[`SupportShare.kt`](miner-core/src/main/kotlin/com/tellmemoreaboutcore/miner/core/SupportShare.kt).

No promised earnings: what mining brings depends on your phone, the network and the pool.

## Network and privacy

- No account, no tracking, no analytics, no update check.
- The app connects only to Catch That Rabbit: the mining pool (`de`, `fi`, `hk`, `sg` `.catchthatrabbit.com`) and
  its statistics API. The pool sees your address, worker name, hash rate and IP address.
- The camera is only used, on your tap, to scan an address QR code.
- Links (privacy policy, source code, pool statistics) open in your browser only when you tap them.

Privacy policy: [tellmemoreaboutcore.com/privacy/#apps](https://tellmemoreaboutcore.com/privacy/#apps)

## Build from source

Requirements: JDK 17, Android SDK Platform 37, NDK 29.0.14206865, CMake 3.31.6.

```bash
git clone --recurse-submodules https://github.com/tmmac-core/corepulse.git
cd corepulse
./gradlew :miner-core:test                 # protocol, mining logic and pool statistics tests
./gradlew :app:assembleDebug               # debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The native hash self-test in `:miner-core:test` needs the JNI library built for your computer first; without it
that test is skipped:

```bash
cmake -S native -B build/host -DCMAKE_BUILD_TYPE=Release && cmake --build build/host
```

A debug build cannot update a release install (different signature). Release builds and signing:
[docs/RELEASE.md](docs/RELEASE.md).

**Layout:** `app/` user interface and mining service · `miner-core/` pool protocol, mining logic and statistics
(plain Kotlin, with tests) · `native/` RandomY (BSD-3, submodule) and SHA3-512 via JNI · `third_party/licenses/`
licence texts.

## Feedback and security

- Bugs and questions: [GitHub Issues](https://github.com/tmmac-core/corepulse/issues) or the
  [contact form](https://tellmemoreaboutcore.com/imprint/#contact).
- Security problems: please report them privately, see [SECURITY.md](SECURITY.md).
- Contributions: [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

GPL-3.0 with additional terms under section 7 (attribution, own name for modified versions): [LICENSE](LICENSE),
[NOTICE.md](NOTICE.md), [TRADEMARKS.md](TRADEMARKS.md). Copyright (C) 2026 TMMAC.
