# CorePulse

XCB (Core Coin) mit dem Android-Handy minen: ein Bildschirm, eine Core-Adresse, Start.
CorePulse rechnet für den Pool [Catch That Rabbit](https://catchthatrabbit.com), der XCB an deine Adresse auszahlt.

Ein unabhängiges Projekt von [Tell Me More About Core (TMMAC)](https://tellmemoreaboutcore.com/de/), keine
offizielle App von Core oder CoDeTech. [English: README.md](README.md)

## Herunterladen

- **Download-Seite:** [tellmemoreaboutcore.com/de/corepulse](https://tellmemoreaboutcore.com/de/corepulse/)
- **Direkt:** [neuestes Release auf GitHub](https://github.com/tmmac-core/corepulse/releases/latest)
  (`CorePulse-<version>.apk` und `SHA256SUMS`)

Läuft ab **Android 9**, nur auf Handys mit **64-Bit-ARM-Prozessor (arm64)**, also fast jedem Handy der letzten
Jahre. CorePulse steht nicht im Play Store, weil Google und Apple Mining auf dem Gerät in ihren Stores nicht
erlauben. Android fragt einmal, ob dein Browser oder Dateimanager Apps installieren darf.

### Prüfen, ob die APK echt ist

Jedes Release ist mit dem TMMAC-Release-Schlüssel signiert. Das Zertifikat ändert sich nie:

```
SHA-256: c63d4ca2ccb8987ce294c2594d3bc288414cc96f8334cd8b6333a3aed08ed808
```

```bash
shasum -a 256 -c SHA256SUMS --ignore-missing            # Prüfsumme der Datei
apksigner verify --print-certs CorePulse-<version>.apk  # Zertifikat (build-tools des Android SDK)
```

Android selbst installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte App.

## So funktioniert es

1. **Core-Adresse** eingeben (oder scannen) und die Leistung wählen. Deine Coins gehen direkt vom Pool an diese
   Adresse; CorePulse verwahrt sie nie.
2. Den Zustimmungskasten lesen und bestätigen: Er erklärt Last, Wärme, Akku, Strom, den Support-Anteil und was
   der Pool sieht.
3. **Start** tippen. Das Minen läuft als Vordergrund-Dienst mit Benachrichtigung; die App zeigt Hashrate, Shares
   und den Ertrag beim Pool.

**Wärme und Akku:** Ab 50 °C Akkutemperatur macht CorePulse Pause und rechnet unter 45 °C weiter; ab 55 °C, oder
wenn das Handy in 15 Minuten nicht abkühlt, stoppt es. Ohne Ladekabel stoppt es bei 20 % Akku. Am besten mit
Netzteil an der Steckdose minen.

**Support-Anteil (0,42 %):** Alle 100 Minuten rechnet die App an zufälliger Stelle 25 Sekunden für die
TMMAC-Adresse `cb292120d3abc3b8330a26daf4beb94b1f6a9c7b4963` (Worker `app-support`), über eine kurze zweite
Verbindung zum selben Catch-That-Rabbit-Server. Das sind 0,42 % der Rechenzeit, fest und für alle gleich; so
finanziert sich die Weiterentwicklung. Es fließt kein Geld durch die App. Code:
[`SupportShare.kt`](miner-core/src/main/kotlin/com/tellmemoreaboutcore/miner/core/SupportShare.kt).

Kein versprochener Ertrag: Was Minen bringt, hängt von deinem Handy, dem Netz und dem Pool ab.

## Netz und Datenschutz

- Kein Konto, kein Tracking, keine Analyse, kein Update-Check.
- Die App verbindet sich nur mit Catch That Rabbit: dem Pool (`de`, `fi`, `hk`, `sg` `.catchthatrabbit.com`) und
  seiner Statistik-Schnittstelle. Der Pool sieht deine Adresse, den Namen im Pool, die Hashrate und deine IP-Adresse.
- Die Kamera wird nur auf deinen Tipp genutzt, um einen Adress-QR-Code zu scannen.
- Links (Datenschutz, Quellcode, Pool-Statistik) öffnen erst auf deinen Tipp den Browser.

[Datenschutzerklärung](https://tellmemoreaboutcore.com/de/datenschutz/#apps)

## Selbst bauen

Voraussetzungen: JDK 17, Android SDK Platform 37, NDK 29.0.14206865, CMake 3.31.6.

```bash
git clone --recurse-submodules https://github.com/tmmac-core/corepulse.git
cd corepulse
./gradlew :miner-core:test                 # Tests für Pool-Protokoll, Mining-Logik und Pool-Statistik
./gradlew :app:assembleDebug               # Debug-APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Der native Selbsttest in `:miner-core:test` braucht vorher die JNI-Bibliothek für den eigenen Rechner, sonst wird
er übersprungen:

```bash
cmake -S native -B build/host -DCMAKE_BUILD_TYPE=Release && cmake --build build/host
```

Ein Debug-Build kann eine Release-Installation nicht aktualisieren (andere Signatur). Release bauen und signieren:
[docs/RELEASE.md](docs/RELEASE.md).

**Aufbau:** `app/` Oberfläche und Mining-Dienst · `miner-core/` Pool-Protokoll, Mining-Logik und Statistik (reines
Kotlin, mit Tests) · `native/` RandomY (BSD-3, Submodul) und SHA3-512 per JNI · `third_party/licenses/`
Lizenztexte.

## Feedback und Sicherheit

- Fehler und Fragen: [GitHub Issues](https://github.com/tmmac-core/corepulse/issues) oder das
  [Kontaktformular](https://tellmemoreaboutcore.com/de/impressum/#contact).
- Sicherheitslücken bitte vertraulich melden, siehe [SECURITY.md](SECURITY.md).
- Mitmachen: [CONTRIBUTING.md](CONTRIBUTING.md).

## Lizenz

GPL-3.0 mit Zusätzen nach §7 (Namensnennung, eigener Name für veränderte Fassungen): [LICENSE](LICENSE),
[NOTICE.md](NOTICE.md), [TRADEMARKS.md](TRADEMARKS.md). Copyright (C) 2026 TMMAC.
