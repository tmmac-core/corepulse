// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** TMMAC-Support-Adresse: Empfaenger des festen Support-Anteils. */
const val TMMAC_SUPPORT_ADDRESS = "cb292120d3abc3b8330a26daf4beb94b1f6a9c7b4963"

/** Worker-Name der Support-Verbindung in der Pool-Statistik der TMMAC-Adresse. */
const val SUPPORT_WORKER = "app-support"

/**
 * Support-Anteil: 0,42 % der Rechenzeit gehen an die TMMAC-Support-Adresse und
 * finanzieren die Weiterentwicklung. Fest, nicht abschaltbar, offen in der App genannt.
 *
 * Ablauf: In jedem Fenster von 100 min rechnet das Handy einmal 25 s (25 / 6.000 s = 0,42 %) fuer TMMAC,
 * an zufaelliger Stelle im Fenster. Dafuer oeffnet die Klasse kurz eine zweite Pool-Verbindung mit der
 * TMMAC-Adresse ([connect]), gibt deren Jobs an die Rechen-Threads ([setJob]) und danach wieder die Jobs
 * des Nutzers. Ein Fund geht an die Verbindung, von der sein Job stammt ([submitIfSupport]).
 *
 * Warum zufaellige Lage statt fester Minute: Wer kuerzer als 100 min mint, traegt im Mittel trotzdem
 * genau 0,42 % bei, nicht 0 % oder ueberproportional viel. Geld fliesst nie durch die App: Der Pool
 * zahlt beide Adressen getrennt aus. Kommt fuer TMMAC kein Job (Pool nicht erreichbar, Login abgelehnt),
 * entfaellt der Abschnitt — zulasten von TMMAC, nie des Nutzers.
 */
class SupportShare(
    /** Baut die Support-Verbindung (TMMAC-Adresse, [SUPPORT_WORKER]); wird je Abschnitt neu aufgerufen. */
    private val connect: (StratumClient.Listener) -> StratumClient,
    /** Job fuer die Rechen-Threads; null = nichts zu rechnen. */
    private val setJob: (Job?) -> Unit,
    private val events: Events = object : Events {},
    private val windowMs: Long = WINDOW_MS,
    private val sliceMs: Long = SLICE_MS,
    /** So lange darf die Support-Verbindung bis zum ersten Job brauchen, sonst entfaellt der Abschnitt. */
    private val jobWaitMs: Long = 15_000L,
    /** Nach dem Abschnitt offen fuer Antworten auf eben gesendete Funde. */
    private val drainMs: Long = 3_000L,
    private val random: Random = SecureRandom(),
) {
    interface Events {
        /** Support-Abschnitt beginnt (true) oder endet (false). */
        fun onSlice(active: Boolean) {}
        fun onShareResult(accepted: Boolean, error: String?) {}
        fun onLog(event: LogEvent) {}
    }

    private val lock = Any()
    private val doneHashes = java.util.concurrent.atomic.AtomicLong(0)
    private val doneNanos = java.util.concurrent.atomic.AtomicLong(0)

    /** Bisher fuer TMMAC gerechnete Hashes (abgeschlossene Abschnitte). */
    val supportHashes: Long get() = doneHashes.get()

    /** Bisherige Dauer der Support-Abschnitte (ab erstem TMMAC-Job bis zur Rueckgabe an den Nutzer). */
    val supportMillis: Long get() = doneNanos.get() / 1_000_000
    @Volatile private var running = false
    private var thread: Thread? = null
    // Alles Folgende nur unter lock
    private var userJob: Job? = null
    private var supportJob: Job? = null
    private var sliceOpen = false
    private var supportClient: StratumClient? = null
    /** Jobs der Support-Verbindung (Identitaet, nicht Inhalt: beide Verbindungen koennen denselben Header bekommen). */
    private val supportJobs: MutableSet<Job> = Collections.newSetFromMap(IdentityHashMap())

    init {
        require(sliceMs in 1 until windowMs) { "Abschnitt muss kuerzer als das Fenster sein" }
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            thread = Thread(::loop, "support-share").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        val t = synchronized(lock) {
            running = false
            thread
        }
        t?.interrupt()
        // Laufenden Abschnitt sauber beenden lassen (zaehlt seine Hashes, schliesst die Verbindung): sonst fehlt er in der Bilanz
        if (t != null && t !== Thread.currentThread()) t.join(2_000)
        val c = synchronized(lock) {
            sliceOpen = false
            supportJob = null
            supportJobs.clear()
            supportClient.also { supportClient = null }
        }
        c?.stop()
    }

    /** Neuer Job von der Verbindung des Nutzers. Waehrend eines Support-Abschnitts wird er nur gemerkt. */
    fun onUserJob(job: Job) = synchronized(lock) {
        userJob = job
        if (supportJob == null) setJob(job)
    }

    /** Verbindung des Nutzers weg: dessen Job ist ungueltig. Ein laufender Support-Abschnitt rechnet weiter. */
    fun onUserDisconnected() = synchronized(lock) {
        userJob = null
        if (supportJob == null) setJob(null)
    }

    /**
     * Stammt [job] von der Support-Verbindung, geht der Fund dorthin (true). Sonst false: Der Aufrufer
     * reicht ihn an die Verbindung des Nutzers.
     */
    fun submitIfSupport(job: Job, nonce: Long, hash: ByteArray): Boolean {
        val c = synchronized(lock) { if (job in supportJobs) supportClient else return false }
        // Verbindung schon zu: Fund verfaellt (Sekundenbruchteile am Abschnittsende), nie an den Nutzer umbuchen
        c?.submit(job, nonce, hash)
        return true
    }

    private fun loop() {
        try {
            while (running) {
                val windowStart = System.nanoTime()
                val offset = (random.nextDouble() * (windowMs - sliceMs)).toLong()
                Thread.sleep(offset)
                if (!running) return
                runSlice()
                val rest = windowMs - (System.nanoTime() - windowStart) / 1_000_000
                if (rest > 0) Thread.sleep(rest)
            }
        } catch (_: InterruptedException) {
            // stop()
        } catch (t: Throwable) {
            // Ein Fehler hier darf das Mining des Nutzers nicht beenden (Android killt sonst den Prozess)
            events.onLog(LogEvent(LogKey.SUPPORT_FAILED, listOf(t.javaClass.simpleName, t.message)))
        }
    }

    private fun runSlice() {
        val firstJob = CountDownLatch(1)
        val client = connect(object : StratumClient.Listener {
            override fun onJob(job: Job) {
                synchronized(lock) {
                    if (!sliceOpen) return
                    supportJobs += job
                    supportJob = job
                    setJob(job)
                }
                firstJob.countDown()
            }
            override fun onDisconnected(reason: String) = synchronized(lock) {
                // Zurueck auf den Nutzer; kommt die Verbindung im Abschnitt wieder, geht es dort weiter
                if (supportJob != null) {
                    supportJob = null
                    setJob(userJob)
                }
            }
            override fun onLoginRejected(reason: String) = events.onLog(LogEvent(LogKey.SUPPORT_LOGIN_REJECTED, listOf(reason)))
            // Nur der Support-Abschnitt faellt aus; das Mining des Nutzers laeuft weiter
            override fun onFatal(reason: String) = events.onLog(LogEvent(LogKey.SUPPORT_FAILED, listOf("Pool", reason)))
            override fun onShareResult(accepted: Boolean, error: String?) = events.onShareResult(accepted, error)
        })
        val started = synchronized(lock) {
            if (running) { sliceOpen = true; supportClient = client }
            running
        }
        if (!started) { client.stop(); return }
        var active = false
        var activeSince = 0L
        try {
            client.start()
            if (!firstJob.await(jobWaitMs, TimeUnit.MILLISECONDS)) {
                events.onLog(LogEvent(LogKey.SUPPORT_SKIPPED))
                return
            }
            active = true
            activeSince = System.nanoTime()
            events.onSlice(true)
            events.onLog(LogEvent(LogKey.SUPPORT_RUNNING, listOf(sliceMs / 1000)))
            Thread.sleep(sliceMs)
        } finally {
            synchronized(lock) {
                sliceOpen = false
                if (supportJob != null) {
                    supportJob = null
                    setJob(userJob)
                }
            }
            if (active) {
                doneNanos.addAndGet(System.nanoTime() - activeSince)
                events.onSlice(false)
            }
            // Antworten auf gerade gesendete Funde abwarten; bei stop() hat der schon geschlossen
            try {
                if (running) Thread.sleep(drainMs)
            } finally {
                val sliceHashes = synchronized(lock) {
                    // Erst nach der Nachlaufzeit zaehlen: so sind auch die letzten Pakete der Rechen-Threads drin
                    val n = supportJobs.sumOf { it.hashes.get() }
                    supportJobs.clear()
                    if (supportClient === client) supportClient = null
                    n
                }
                doneHashes.addAndGet(sliceHashes)
                client.stop()
                if (active) events.onLog(LogEvent(LogKey.SUPPORT_DONE, listOf(sliceHashes)))
            }
        }
    }

    companion object {
        /** 100 min */
        const val WINDOW_MS = 6_000_000L
        /** 25 s = 0,42 % von 100 min */
        const val SLICE_MS = 25_000L
    }
}
