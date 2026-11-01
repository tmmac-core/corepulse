// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import com.tellmemoreaboutcore.miner.core.LogEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Gemeinsamer Zustand zwischen Dienst und Oberflaeche (Spike: ein Prozess, ein Singleton). */
object MiningState {
    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val pool: String? = null,
        val hashrate: Double = 0.0,
        val accepted: Int = 0,
        val rejected: Int = 0,
        val shareDifficulty: Double = 0.0,
        val batteryTempC: Float? = null,
        val thermalStatus: Int? = null,
        val flags: String = "",
        /** Warum zuletzt gestoppt wurde (Hitze, Speicher, Pool …); null = nichts zu melden. Text erst bei der Anzeige. */
        val notice: UiText? = null,
        /** Hitze-Pause: rechnet gerade nicht, laeuft nach dem Abkuehlen von selbst weiter. */
        val cooling: Boolean = false,
        val batteryPercent: Int? = null,
        /** Gerade laeuft der Support-Abschnitt (25 s je 100 min fuer TMMAC). */
        val supportActive: Boolean = false,
        /** Fuer die Bilanz nach dem Stopp. */
        val startedAtMs: Long = 0,
        val maxTempC: Float? = null,
        /** Bilanz des letzten Laufs ("Gestoppt nach 12 min · 3 Shares …"); null = noch kein Lauf. Text erst bei der Anzeige. */
        val summary: RunSummary? = null,
        /** Hashrate der letzten Messungen (10-s-Takt, max. SPARK_POINTS) fuer die Verlaufskurve; nur lokal. */
        val history: List<Float> = emptyList(),
        /** Anzahl Messungen mit Rechenleistung seit Start; treibt den Mess-Puls (history bleibt bei 60 stehen). */
        val measurements: Int = 0,
        /** Blockspur: letzte Ereignisse, J = neuer Job vom Pool, S = Share angenommen, P = Support-Abschnitt. */
        val trail: String = "",
        /** Zahl aller Ereignisse der Blockspur seit Start: treibt das Einlaufen (trail allein bleibt bei vollen 40 gleich). */
        val events: Int = 0,
        /** Vorbereitung: 0 = Speicher pruefen, 1 = Rechenwerk aufbauen und selbst testen (danach Phase MINING). */
        val prepStep: Int = 0,
        /** Gerechnete Hashes seit Start (inkl. Support-Abschnitt), fuer die Bilanz nach dem Stopp. */
        val hashes: Long = 0,
        /** Letzter angenommener Share (Wanduhr), 0 = noch keiner. */
        val lastShareAtMs: Long = 0,
        /** Laedt gerade (Kabel/kabellos). */
        val charging: Boolean? = null,
        /** Akkustand und Zeit beim Start bzw. beim Abziehen des Kabels — fuer "Akku pro Stunde". */
        val batteryRefPercent: Int? = null,
        val batteryRefAtMs: Long = 0,
        /** Netzverkehr der App beim Start (Bytes), fuer "Datenmenge seit Start". */
        val netBytesAtStart: Long = -1,
        /** In-App-Log als Ereignisse; Text in der App-Sprache erst bei der Anzeige ([LogEntry.text]). */
        val log: List<LogEntry> = emptyList(),
    )

    enum class Phase { IDLE, PREPARING, MINING, STOPPING }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(block: (Snapshot) -> Snapshot) = _state.update(block)

    /** Ereignis an die Blockspur haengen (max. TRAIL_LENGTH). */
    fun mark(event: Char) = _state.update { it.copy(trail = (it.trail + event).takeLast(TRAIL_LENGTH), events = it.events + 1) }

    const val TRAIL_LENGTH = 40

    /**
     * In-App-Log (bleibt im Speicher der App). Gespeichert wird das Ereignis, nicht der Text: Die Anzeige
     * rendert es in der aktuellen App-Sprache, ein Sprachwechsel erfasst so das ganze Log.
     * Nur im Debug-Build zusaetzlich nach logcat (Tag CoreMiner), immer deutsch ([LogEvent.german]), weil
     * Auswerte-Skripte darauf greppen; im Release nicht, weil logcat in Fehlerberichte an Hersteller wandern kann.
     * [logcat] ersetzt die deutsche Zeile, wenn ein Wert erst mit Android-Ressourcen deutsch wird ([UiText]).
     */
    fun log(event: LogEvent, logcat: (() -> String)? = null) {
        if (BuildConfig.DEBUG) android.util.Log.i("CoreMiner", logcat?.invoke() ?: event.german())
        val entry = LogEntry(System.currentTimeMillis(), event)
        _state.update { it.copy(log = (it.log + entry).takeLast(300)) }
    }
}
