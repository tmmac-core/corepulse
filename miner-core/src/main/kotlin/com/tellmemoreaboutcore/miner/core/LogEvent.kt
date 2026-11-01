// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import java.util.Locale

/**
 * Art einer Log-Zeile. Core und App melden Ereignisse statt fertiger Saetze: Die App zeigt sie in ihrer
 * Sprache (je Schluessel eine String-Ressource), CLI und Debug-logcat nehmen [LogEvent.german].
 *
 * Auch die Zeilen der App stehen hier (Abschnitt "App"): So gibt es genau einen deutschen Wortlaut je
 * Zeile, festgehalten im Code und per Test (LogEventTest) gesichert. Auswerte-Skripte greppen im logcat
 * auf diese Texte — Wortlaut und Zahlenformat nur zusammen mit den Skripten aendern.
 */
enum class LogKey {
    // StratumClient
    SHARE_DROPPED_NO_POOL,
    SERVER_CHOICE,
    SERVER_CHOICE_HOME,
    BACK_TO_HOME,
    SHARES_UNANSWERED,
    UNREADABLE_POOL_MESSAGE,
    POOL_ERROR,
    JOB_UNREADABLE,

    // SupportShare
    SUPPORT_FAILED,
    SUPPORT_LOGIN_REJECTED,
    SUPPORT_SKIPPED,
    SUPPORT_RUNNING,
    SUPPORT_DONE,

    // MinerEngine
    RANDOMY_READY,
    SELFTEST_RETRY_WITHOUT_JIT,
    STOP_THREAD_STILL_RUNNING,
    THREAD_NO_VM,
    THREAD_SELFTEST_FAILED,
    THREAD_ENDED,

    // App
    TEST_NO_HEAT_PAUSE,
    ERROR,
    STARTING,
    SHARE_FOUND,
    START_FAILED,
    SUPPORT_SHARE_ACCEPTED,
    SUPPORT_SHARE_REJECTED,
    SUPPORT_ON,
    CONNECTED,
    DISCONNECTED,
    LOGIN_REJECTED,
    SHARE_ACCEPTED,
    SHARE_REJECTED,
    MEASUREMENT,
    SUPPORT_BALANCE,
    HEAT_PAUSE,
    HEAT_RESUME,
    STOPPED_WITH_NOTICE,
    STOPPED,
    POOL_STATS_FAILED,
}

/**
 * Ein Log-Ereignis: [key] plus Werte. Die Werte haben genau die Typen, die das Format erwartet
 * (z. B. Double fuer "%.0f"), damit die App sie in ihrer Locale formatieren kann.
 */
data class LogEvent(val key: LogKey, val args: List<Any?> = emptyList()) {
    /**
     * Deutscher Wortlaut wie vor der Uebersetzung (Zahlen mit Komma), ohne Zeitstempel.
     * Fuer CLI und Debug-logcat; byte-genau, weil Skripte darauf greppen.
     */
    fun german(): String {
        fun a(i: Int): Any? = args.getOrNull(i)
        fun f(format: String): String = String.format(Locale.GERMAN, format, *args.toTypedArray())
        return when (key) {
            LogKey.SHARE_DROPPED_NO_POOL -> f("Share verworfen: keine Pool-Verbindung (nonce 0x%016x)")
            LogKey.SERVER_CHOICE -> "Server-Wahl: ${a(0)}"
            LogKey.SERVER_CHOICE_HOME -> "Server-Wahl: ${a(0)}, Heimat ${a(1)}"
            LogKey.BACK_TO_HOME -> "Zurueck zum Heimat-Server: ${a(0)} ${a(1)} ms " +
                "(statt ${a(2)}; jede Region fuehrt ein eigenes Guthaben)"
            LogKey.SHARES_UNANSWERED -> "${a(0)} Share(s) ohne Pool-Antwort (Verbindung weg)"
            LogKey.UNREADABLE_POOL_MESSAGE -> "Unlesbare Pool-Nachricht: ${a(0)}"
            LogKey.POOL_ERROR -> "Pool-Fehler (id=${a(0)}): ${a(1)}"
            LogKey.JOB_UNREADABLE -> "Job nicht lesbar: ${a(0)}"

            LogKey.SUPPORT_FAILED -> "Support-Anteil beendet: ${a(0)} ${a(1) ?: ""}".trim()
            LogKey.SUPPORT_LOGIN_REJECTED -> "Support-Anteil: Pool lehnt ab (${a(0)})"
            LogKey.SUPPORT_SKIPPED -> "Support-Anteil entfällt diesmal: Pool nicht erreichbar"
            LogKey.SUPPORT_RUNNING -> f("Support-Anteil läuft (%d s)")
            LogKey.SUPPORT_DONE -> f("Support-Anteil beendet: %d Hashes für TMMAC")

            LogKey.RANDOMY_READY -> f("RandomY bereit in %.1f s, JIT=%s, FullMem=%s, HardAES=%s, Selbsttest ok")
            LogKey.SELFTEST_RETRY_WITHOUT_JIT -> "${a(0)} → neuer Versuch ohne JIT"
            LogKey.STOP_THREAD_STILL_RUNNING -> "Stopp: Thread rechnet noch, RandomY-Speicher wird danach freigegeben"
            LogKey.THREAD_NO_VM -> "Thread ${a(0)}: VM konnte nicht angelegt werden"
            LogKey.THREAD_SELFTEST_FAILED -> "Thread ${a(0)}: ${a(1)} → Thread rechnet nicht"
            LogKey.THREAD_ENDED -> "Thread ${a(0)} beendet: ${a(1)} ${a(2) ?: ""}".trim()

            LogKey.TEST_NO_HEAT_PAUSE -> "TEST: Hitze-Pause und Schutzabschaltung der App aus, nur Android schuetzt"
            LogKey.ERROR -> "Fehler: ${a(0)} ${a(1) ?: ""}".trim()
            LogKey.STARTING -> "Starte: Worker ${a(0)}, ${a(1)} Threads, ${a(2)} ${a(3)}, Android ${a(4)}, " +
                String.format(Locale.GERMAN, "RAM frei %d von %d MiB", a(5), a(6))
            LogKey.SHARE_FOUND -> f("Share gefunden (nonce %016x)")
            LogKey.START_FAILED -> "Start fehlgeschlagen: ${a(0)}"
            LogKey.SUPPORT_SHARE_ACCEPTED -> "Support-Share angenommen"
            LogKey.SUPPORT_SHARE_REJECTED -> "Support-Share abgelehnt: ${a(0)}"
            LogKey.SUPPORT_ON -> f("Support-Anteil an (%.2f %%)")
            LogKey.CONNECTED -> "Angemeldet bei ${a(0)}"
            LogKey.DISCONNECTED -> "Getrennt: ${a(0)}"
            LogKey.LOGIN_REJECTED -> "Login abgelehnt: ${a(0)}"
            LogKey.SHARE_ACCEPTED -> "Share angenommen"
            LogKey.SHARE_REJECTED -> "Share abgelehnt: ${a(0)}"
            LogKey.MEASUREMENT -> f("MESS %.0f H/s, Threads %d, Akku %.1f °C, Thermal %s")
            LogKey.SUPPORT_BALANCE -> f("Support-Bilanz: TMMAC %d von %d Hashes (%.3f %%), %.0f von %.0f s (%.3f %%)")
            LogKey.HEAT_PAUSE -> f("Hitze-Pause (Akku %.1f °C, Thermal %s)")
            LogKey.HEAT_RESUME -> f("Weiter nach %.0f s Hitze-Pause")
            LogKey.STOPPED_WITH_NOTICE -> "Gestoppt: ${a(0)}"
            LogKey.STOPPED -> "Gestoppt"
            LogKey.POOL_STATS_FAILED -> "Pool-Statistik nicht abrufbar: ${a(0)}"
        }
    }
}
