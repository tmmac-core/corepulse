# CorePulse

> Früherer Arbeitstitel: Core Mobile Miner (interne Namen wie der Tag `CoreMiner` im Log behalten ihn).

Android-App, mit der jeder per Handy XCB (Core Coin) über den Pool Catch That Rabbit mint —
ein Bildschirm, eine Adresse, Start. Von TMMAC (tellmemoreaboutcore.com).
Läuft auf Android 9 und neuer, nur auf Handys mit 64-Bit-ARM-Prozessor (arm64). [English: README.md](README.md)

**Download:** https://tellmemoreaboutcore.com/de/corepulse/ oder direkt das
[neueste Release](https://github.com/tmmac-core/corepulse/releases/latest) (signierte APK mit SHA-256 und
Zertifikats-Fingerabdruck; Fingerabdruck auch in `docs/RELEASE.md`).

**Stack:** Kotlin + Jetpack Compose, RandomY (BSD-3, Submodul) per NDK/JNI, eigener Stratum-Client.

## Support-Anteil (0,42 %)

So finanziert sich die Weiterentwicklung: Alle 100 Minuten rechnet die App an zufälliger Stelle
25 Sekunden für die TMMAC-Adresse `cb292120d3abc3b8330a26daf4beb94b1f6a9c7b4963` (Worker `app-support`),
über eine kurze zweite Verbindung zum selben Catch-That-Rabbit-Server. Das sind 0,42 % der Rechenzeit.
Der Anteil ist fest eingebaut und für alle gleich; die App nennt ihn vor dem ersten Start, im
Startbildschirm, beim Ertrag und auf der Seite „Über diese App“. Es fließt kein Geld durch die App.
Code: `miner-core/.../SupportShare.kt`.

## Bauen

```
git submodule update --init
./gradlew :miner-core:test
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Der native Selbsttest in `:miner-core:test` braucht vorher die JNI-Bibliothek für den eigenen Rechner, sonst wird
er übersprungen: `cmake -S native -B build/host -DCMAKE_BUILD_TYPE=Release && cmake --build build/host`.

Voraussetzungen: JDK 17, Android SDK Platform 37, NDK 29.0.14206865, CMake 3.31.6.

## Aufbau

- `app/` — Oberfläche und Mining-Dienst
- `miner-core/` — Pool-Protokoll, Mining-Logik, Pool-Statistik (ohne Android, mit Tests)
- `native/` — RandomY-Anbindung und SHA3-512
- `third_party/licenses/` — Lizenztexte der Fremdteile

## Lizenz

GPL-3.0 mit Zusätzen nach §7 (Namensnennung, eigener Name für Nachbauten) — siehe `LICENSE`,
`NOTICE.md` und `TRADEMARKS.md`. Copyright (C) 2026 TMMAC.
