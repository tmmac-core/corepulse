# Release bauen und signieren

Wer eine Release-APK baut, signiert sie mit dem TMMAC-Schluessel. Android installiert ein Update nur, wenn es mit
demselben Schluessel signiert ist wie die installierte Version. Geht der Schluessel verloren, koennen bestehende
Nutzer kein Update mehr einspielen. Gelangt er in fremde Haende, kann jemand gefaelschte Updates verteilen.

## Wo was liegt

| Was | Wo | Nie |
|---|---|---|
| Schluesseldatei | `~/.android-keys/core-mobile-miner-release.p12` (Mac) | im Repo, in Cloud-Ordnern, im Chat |
| Passwort | macOS-Schluesselbund (Dienst `core-mobile-miner-release`, Konto `tmmac-miner`) + Passwort-Manager | in `gradle.properties`, Dateien, Chat |
| Sicherungen | 2 Kopien der Datei an getrennten Orten (die `.p12` ist selbst passwortgeschuetzt), Passwort getrennt davon | zusammen mit dem Passwort |

## Einmalig: Schluessel anlegen (nur der Schluesselinhaber, im eigenen Terminal)

```bash
mkdir -p ~/.android-keys && chmod 700 ~/.android-keys
JAVA_HOME=$(echo ~/.local/jdk/jdk-17*/Contents/Home)
"$JAVA_HOME/bin/keytool" -genkeypair -v -storetype PKCS12 \
  -keystore ~/.android-keys/core-mobile-miner-release.p12 \
  -alias tmmac-miner -keyalg RSA -keysize 4096 -validity 18250 \
  -dname "CN=TMMAC, O=TMMAC"
chmod 600 ~/.android-keys/core-mobile-miner-release.p12
```

keytool fragt zweimal nach dem Passwort. Danach das Passwort in den Schluesselbund legen. Ohne Wert hinter `-w`
fragt `security` das Passwort ab, damit es nicht in der Shell-History landet:

```bash
security add-generic-password -a tmmac-miner -s core-mobile-miner-release -w
```

Dann dasselbe Passwort im Passwort-Manager speichern und die zwei Sicherungen der `.p12`-Datei anlegen.

## Jedes Release

1. `versionCode` erhoehen und `versionName` setzen (`app/build.gradle.kts`). Auch jede Test-APK, die das Haus
   verlaesst, bekommt einen neuen `versionCode`, sonst gilt sie nicht als Update.
2. Veroeffentlicht wird nur aus einem signierten Tag (`git tag -s v1.0.0`), gebaut aus genau diesem Stand ohne
   lokale Aenderungen. Die Commit-ID in der APK (`META-INF/version-control-info.textproto`) muss zum Tag passen.
3. Bauen. Der Build liest das Passwort aus dem Schluesselbund. Beim ersten Mal fragt macOS evtl. nach, ob `security`
   zugreifen darf.
   ```bash
   JAVA_HOME=$(echo ~/.local/jdk/jdk-17*/Contents/Home) ./gradlew :app:assembleRelease
   ```
4. Pruefen: `scripts/verify-release.sh`. Ausgabe sind der Zertifikats-Fingerabdruck, die Version und die SHA-256 der APK.
   Das Skript bricht ab ohne v3-Signatur, bei einer debuggable-APK oder einer Entwicklungs-Version (`spike`).
5. Das Skript vergleicht mit dem Fingerabdruck unten und bricht bei einem fremden Schluessel ab.
6. Die APK auf ein Testgeraet installieren und kurz minen lassen.
7. GitHub Release anlegen (nur mit Freigabe des Schluesselinhabers): APK + SHA-256 + Fingerabdruck in die Release-Notiz.

Signiert wird nur lokal auf dem Mac, nicht in CI. Der Schluessel verlaesst den Rechner nie.

## Fingerabdruck

Zertifikat des TMMAC-Release-Schluessels (CN=TMMAC, O=TMMAC, RSA 4096, angelegt 16.10.2026). Er bleibt bei jedem
Release gleich und kommt auf die Download-Seite und in jede Release-Notiz. Das Skript liest diese Zeile:

Zertifikat SHA-256: c63d4ca2ccb8987ce294c2594d3bc288414cc96f8334cd8b6333a3aed08ed808
