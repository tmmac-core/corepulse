// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

/**
 * Zustimmung vor dem ersten Minen (rechtliche Schutzmassnahme, kein Design-Extra).
 * Gespeichert wird nur auf dem Handy: die angenommene Textversion und der Zeitpunkt, ohne Backup.
 */
object Consent {
    /**
     * Version der Hinweistexte (`consent_*` in `app/src/main/res/values(-xx)/strings.xml`). Bei jeder Textaenderung
     * erhoehen, dann fragt die App erneut; `ConsentTest` prueft per Hash, dass das nicht vergessen wird.
     */
    const val VERSION = 4

    /** Pref-Schluessel in den SharedPreferences "miner" (App und Dienst). */
    const val PREF_VERSION = "consentVersion"
    const val PREF_AT = "consentAt"

    /** Fragen, solange keine oder nur eine aeltere Textversion angenommen wurde (0 = nie). */
    fun needed(acceptedVersion: Int): Boolean = acceptedVersion < VERSION
}
