#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md
#
# Prueft eine signierte Release-APK und gibt aus, was veroeffentlicht wird: Zertifikats-Fingerabdruck (muss bei
# jedem Release gleich bleiben) und SHA-256 der Datei (Pruefsumme fuer die Download-Seite). Ablauf: docs/RELEASE.md
set -euo pipefail

apk="${1:-app/build/outputs/apk/release/app-release.apk}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
apksigner="$(ls -d "$sdk"/build-tools/*/apksigner | sort -V | tail -1)"

[[ -f "$apk" ]] || { echo "APK fehlt: $apk" >&2; exit 1; }
# apksigner ist ein Java-Programm; ohne JAVA_HOME findet macOS keine Laufzeit
[[ -n "${JAVA_HOME:-}" ]] || export JAVA_HOME="$(echo ~/.local/jdk/jdk-17*/Contents/Home)"
certs="$("$apksigner" verify --verbose --print-certs "$apk")"  # bricht bei ungueltiger Signatur ab (set -e)

echo "== Signatur"
grep -E "Verified using v[0-9.]+ scheme.*true|certificate (DN|SHA-256 digest)" <<<"$certs" | sort -u

# v3 haelt die Schluessel-Rotation offen (docs/RELEASE.md); ohne v3 nicht veroeffentlichen
grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" <<<"$certs" \
  || { echo "!! v3-Signatur fehlt — nicht veroeffentlichen" >&2; exit 3; }

aapt2="$(ls -d "$sdk"/build-tools/*/aapt2 | sort -V | tail -1)"
badging="$("$aapt2" dump badging "$apk")"
grep -q "application-debuggable" <<<"$badging" && { echo "!! debuggable-APK — nicht veroeffentlichen" >&2; exit 4; }
grep -qE "versionName='[^']*(spike|SNAPSHOT)" <<<"$badging" && { echo "!! Entwicklungs-versionName — nicht veroeffentlichen" >&2; exit 5; }
echo "== Version"
grep -oE "versionCode='[0-9]+' versionName='[^']*'" <<<"$badging"

echo "== SHA-256 der Datei"
shasum -a 256 "$apk"

# Erwarteter Fingerabdruck steht nach dem ersten Release in docs/RELEASE.md; weicht er ab, nicht veroeffentlichen
expected="$(grep -oE '^Zertifikat SHA-256: [0-9a-fA-F:]+' "$(dirname "$0")/../docs/RELEASE.md" | awk '{print $3}' || true)"
if [[ -n "$expected" ]]; then
  # Zeilenformat je nach apksigner-Version "Signer #1 certificate …" oder "V2 Signer: certificate …"
  actual="$(awk '/certificate SHA-256 digest/ {print $NF; exit}' <<<"$certs")"
  norm() { tr -d ':' | tr 'A-F' 'a-f'; }
  if [[ "$(norm <<<"$actual")" == "$(norm <<<"$expected")" ]]; then
    echo "== Fingerabdruck stimmt mit docs/RELEASE.md ueberein"
  else
    echo "!! FALSCHER SCHLUESSEL: $actual statt $expected — nicht veroeffentlichen" >&2; exit 2
  fi
fi
