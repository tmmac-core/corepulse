// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.Locale

/**
 * Debug-logcat und CLI muessen byte-genau den deutschen Wortlaut von vor der Uebersetzung behalten:
 * Auswerte-Skripte greppen darauf. Erwartete Texte aus dem alten Code bzw. dem Handy-Nachtlauf 16./17.10.
 */
class LogEventTest {
    private fun e(key: LogKey, vararg args: Any?) = LogEvent(key, args.toList())

    /** Je Schluessel mindestens ein Fall. */
    private val cases: List<Pair<LogEvent, String>> = listOf(
        // StratumClient
        e(LogKey.SHARE_DROPPED_NO_POOL, 0x1fL) to "Share verworfen: keine Pool-Verbindung (nonce 0x000000000000001f)",
        e(LogKey.SERVER_CHOICE_HOME, "de 43 ms, fi 58 ms, sg 186 ms, hk 205 ms", "de") to
            "Server-Wahl: de 43 ms, fi 58 ms, sg 186 ms, hk 205 ms, Heimat de",
        e(LogKey.SERVER_CHOICE, "fi 5068 ms, sg 5200 ms, hk 5220 ms, de –") to
            "Server-Wahl: fi 5068 ms, sg 5200 ms, hk 5220 ms, de –",
        e(LogKey.BACK_TO_HOME, "de", 84L, "sg") to
            "Zurueck zum Heimat-Server: de 84 ms (statt sg; jede Region fuehrt ein eigenes Guthaben)",
        e(LogKey.SHARES_UNANSWERED, 2) to "2 Share(s) ohne Pool-Antwort (Verbindung weg)",
        e(LogKey.UNREADABLE_POOL_MESSAGE, "{kaputt") to "Unlesbare Pool-Nachricht: {kaputt",
        e(LogKey.POOL_ERROR, 7, "low difficulty") to "Pool-Fehler (id=7): low difficulty",
        e(LogKey.POOL_ERROR, null, "x") to "Pool-Fehler (id=null): x",
        e(LogKey.JOB_UNREADABLE, "[\"zz\",\"0\",\"1\"]") to "Job nicht lesbar: [\"zz\",\"0\",\"1\"]",
        // SupportShare
        e(LogKey.SUPPORT_FAILED, "IllegalStateException", "kaputt") to "Support-Anteil beendet: IllegalStateException kaputt",
        e(LogKey.SUPPORT_FAILED, "NullPointerException", null) to "Support-Anteil beendet: NullPointerException",
        e(LogKey.SUPPORT_LOGIN_REJECTED, "invalid address") to "Support-Anteil: Pool lehnt ab (invalid address)",
        e(LogKey.SUPPORT_SKIPPED) to "Support-Anteil entfällt diesmal: Pool nicht erreichbar",
        e(LogKey.SUPPORT_RUNNING, 25L) to "Support-Anteil läuft (25 s)",
        e(LogKey.SUPPORT_DONE, 26192L) to "Support-Anteil beendet: 26192 Hashes für TMMAC",
        // MinerEngine
        e(LogKey.RANDOMY_READY, 2.4123, true, true, true) to
            "RandomY bereit in 2,4 s, JIT=true, FullMem=true, HardAES=true, Selbsttest ok",
        e(LogKey.SELFTEST_RETRY_WITHOUT_JIT, "Selbsttest: header 00000000… nonce 0 → ab, erwartet cd") to
            "Selbsttest: header 00000000… nonce 0 → ab, erwartet cd → neuer Versuch ohne JIT",
        e(LogKey.STOP_THREAD_STILL_RUNNING) to "Stopp: Thread rechnet noch, RandomY-Speicher wird danach freigegeben",
        e(LogKey.THREAD_NO_VM, 3) to "Thread 3: VM konnte nicht angelegt werden",
        e(LogKey.THREAD_SELFTEST_FAILED, 1, "Selbsttest: x") to "Thread 1: Selbsttest: x → Thread rechnet nicht",
        e(LogKey.THREAD_ENDED, 0, "OutOfMemoryError", "Java heap") to "Thread 0 beendet: OutOfMemoryError Java heap",
        e(LogKey.THREAD_ENDED, 2, "InterruptedException", null) to "Thread 2 beendet: InterruptedException",
        // App
        e(LogKey.TEST_NO_HEAT_PAUSE) to "TEST: Hitze-Pause und Schutzabschaltung der App aus, nur Android schuetzt",
        e(LogKey.ERROR, "RuntimeException", "boom") to "Fehler: RuntimeException boom",
        e(LogKey.ERROR, "RuntimeException", null) to "Fehler: RuntimeException",
        e(LogKey.STARTING, "handy-sv5v", 4, "samsung", "SM-G780F", "13", 2269L, 5459L) to
            "Starte: Worker handy-sv5v, 4 Threads, samsung SM-G780F, Android 13, RAM frei 2269 von 5459 MiB",
        e(LogKey.SHARE_FOUND, 0x0f3e5b9a1L) to "Share gefunden (nonce 00000000f3e5b9a1)",
        e(LogKey.START_FAILED, "RandomY konnte nicht initialisiert werden (Speicher?)") to
            "Start fehlgeschlagen: RandomY konnte nicht initialisiert werden (Speicher?)",
        e(LogKey.SUPPORT_SHARE_ACCEPTED) to "Support-Share angenommen",
        e(LogKey.SUPPORT_SHARE_REJECTED, "stale") to "Support-Share abgelehnt: stale",
        e(LogKey.SUPPORT_SHARE_REJECTED, null) to "Support-Share abgelehnt: null",
        e(LogKey.SUPPORT_ON, 0.42) to "Support-Anteil an (0,42 %)",
        e(LogKey.CONNECTED, "de.catchthatrabbit.com:8008") to "Angemeldet bei de.catchthatrabbit.com:8008",
        e(LogKey.DISCONNECTED, "de.catchthatrabbit.com:8008: Connection reset") to
            "Getrennt: de.catchthatrabbit.com:8008: Connection reset",
        e(LogKey.LOGIN_REJECTED, "invalid address") to "Login abgelehnt: invalid address",
        e(LogKey.SHARE_ACCEPTED) to "Share angenommen",
        e(LogKey.SHARE_REJECTED, "low difficulty share") to "Share abgelehnt: low difficulty share",
        e(LogKey.MEASUREMENT, 1206.4, 4, 37.5f, 1) to "MESS 1206 H/s, Threads 4, Akku 37,5 °C, Thermal 1",
        e(LogKey.MEASUREMENT, 1050.0, 4, 45.2f, 4) to "MESS 1050 H/s, Threads 4, Akku 45,2 °C, Thermal 4",
        e(LogKey.MEASUREMENT, 0.0, 0, Float.NaN, "-") to "MESS 0 H/s, Threads 0, Akku NaN °C, Thermal -",
        // Wie supportBalance in MiningService rechnet: 25.000 ms Support in 4.807 s Laufzeit
        e(LogKey.SUPPORT_BALANCE, 26192L, 5116301L, 100.0 * 26192 / 5116301, 25_000 / 1e3, 4807.0, 25_000 / 10.0 / 4807.0) to
            "Support-Bilanz: TMMAC 26192 von 5116301 Hashes (0,512 %), 25 von 4807 s (0,520 %)",
        e(LogKey.SUPPORT_BALANCE, 0L, 718900L, 0.0, 0.0, 601.0, 0.0) to
            "Support-Bilanz: TMMAC 0 von 718900 Hashes (0,000 %), 0 von 601 s (0,000 %)",
        e(LogKey.HEAT_PAUSE, 50.1f, 3) to "Hitze-Pause (Akku 50,1 °C, Thermal 3)",
        e(LogKey.HEAT_PAUSE, Float.NaN, "-") to "Hitze-Pause (Akku NaN °C, Thermal -)",
        e(LogKey.HEAT_RESUME, 61.7) to "Weiter nach 62 s Hitze-Pause",
        e(LogKey.STOPPED_WITH_NOTICE, "Akku bei 20 %. Zum Schutz gestoppt") to "Gestoppt: Akku bei 20 %. Zum Schutz gestoppt",
        e(LogKey.STOPPED) to "Gestoppt",
        e(LogKey.POOL_STATS_FAILED, "timeout") to "Pool-Statistik nicht abrufbar: timeout",
    )

    @TestFactory fun deutscherWortlautJeSchluessel(): List<DynamicTest> = cases.map { (event, expected) ->
        DynamicTest.dynamicTest("${event.key} ${event.args}") { assertEquals(expected, event.german()) }
    }

    @Test fun jederSchluesselHatEinenFall() {
        assertEquals(LogKey.entries.toSet(), cases.map { it.first.key }.toSet())
    }

    @Test fun unabhaengigVonDerStandardLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            assertEquals("MESS 1050 H/s, Threads 4, Akku 45,2 °C, Thermal 4",
                LogEvent(LogKey.MEASUREMENT, listOf(1050.0, 4, 45.2f, 4)).german())
        } finally {
            Locale.setDefault(before)
        }
    }
}
