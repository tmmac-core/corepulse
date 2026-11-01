// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Rechen-Threads ueber RandomY. Jeder Thread hat eine eigene VM und einen eigenen
 * Nonce-Bereich (Zufallsstart, damit mehrere Geraete derselben Wallet nichts doppelt rechnen).
 * Ohne Job (vor dem ersten Job oder nach [clearJob]) warten die Threads, statt zu rechnen.
 */
class MinerEngine(
    private val threads: Int,
    private val fullMem: Boolean,
    private val onSolution: (job: Job, nonce: Long, hash: ByteArray) -> Unit,
    private val log: (LogEvent) -> Unit = {},
    private val useJit: Boolean = true,
    /** Threads fuer den einmaligen Dataset-Aufbau; darf mehr sein als [threads]. */
    private val initThreads: Int = threads,
) {
    private val hashes = AtomicLong(0)
    private val random = SecureRandom()
    @Volatile private var job: Job? = null
    @Volatile private var running = false

    /** Hitze-Pause: Threads warten wie ohne Job, Dataset und VMs bleiben erhalten (Weiterlaufen ohne Neuaufbau). */
    @Volatile var paused = false
    private var handle = 0L
    private val workers = mutableListOf<Thread>()
    private val liveWorkers = AtomicInteger(0)

    /** Gesamtzahl gerechneter Hashes seit Start (fuer Hashrate-Anzeige). */
    val totalHashes: Long get() = hashes.get()

    /** Rechen-Threads, die tatsaechlich laufen (0 trotz Start = VM-Anlage gescheitert). */
    val activeWorkers: Int get() = liveWorkers.get()

    var activeFlags = 0
        private set

    /**
     * Baut Cache/Dataset (dauert je nach Geraet Sekunden bis Minuten), prueft den Rechenweg
     * gegen [HashSelfTest] und startet die Threads. Rechnet der JIT falsch, wird ohne JIT neu
     * aufgebaut; rechnet auch der Interpreter falsch, wird nicht gemint (Exception).
     */
    fun start() {
        check(!running) { "laeuft bereits" }
        // Dataset eines vorigen Laufs noch belegt (Thread rechnete beim Stopp noch): erst dessen Freigabe
        // abwarten, sonst laegen zwei Datasets (~2 x 264 MiB) im Speicher und Android beendet den Prozess
        check(awaitReleases(RELEASE_WAIT_MS)) { "Vorheriger Lauf gibt den Speicher noch frei" }
        val t0 = System.nanoTime()
        handle = initVerified(useJit)
        activeFlags = NativeRandomY.nativeFlags(handle)
        log(LogEvent(LogKey.RANDOMY_READY, listOf(
            (System.nanoTime() - t0) / 1e9,
            activeFlags and NativeRandomY.FLAG_JIT != 0,
            activeFlags and NativeRandomY.FLAG_FULL_MEM != 0,
            activeFlags and NativeRandomY.FLAG_HARD_AES != 0,
        )))
        running = true
        // Handle fest mitgeben: stop() setzt [handle] sofort zurueck, ein spaet startender Thread laese sonst 0
        val h = handle
        repeat(threads) { i ->
            workers += Thread({ work(i, h) }, "miner-$i").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY  // Handy bleibt bedienbar
                start()
            }
        }
    }

    private fun initVerified(jit: Boolean): Long {
        val h = NativeRandomY.nativeInit(fullMem, initThreads.coerceAtLeast(1), jit)
        check(h != 0L) { "RandomY konnte nicht initialisiert werden (Speicher?)" }
        val vm = NativeRandomY.nativeCreateVm(h)
        if (vm == 0L) {
            // Die JNI-Schicht faellt bei JIT-Problemen schon selbst auf den Interpreter zurueck;
            // scheitert es trotzdem, fehlt Speicher — ein Neuaufbau ohne JIT wuerde nur erneut scheitern
            NativeRandomY.nativeRelease(h)
            error("RandomY-VM konnte nicht angelegt werden (Speicher?)")
        }
        val error = HashSelfTest.run(vm)
        NativeRandomY.nativeDestroyVm(vm)
        if (error == null) return h
        NativeRandomY.nativeRelease(h)
        if (jit) {
            log(LogEvent(LogKey.SELFTEST_RETRY_WITHOUT_JIT, listOf(error)))
            return initVerified(jit = false)
        }
        error("Rechenweg falsch, Mining abgebrochen: $error")
    }

    fun setJob(newJob: Job) {
        job = newJob
    }

    /** Kein gueltiger Job mehr (z. B. Pool-Verbindung weg): Threads pausieren statt fuer nichts zu heizen. */
    fun clearJob() {
        job = null
    }

    fun stop() {
        running = false
        val h = handle
        handle = 0L
        val stopped = workers.toList()
        workers.clear()
        // Rechnet ein Thread nach der Frist noch (sehr langsames Geraet), darf das Dataset nicht sofort weg:
        // die Freigabe holt ein Aufraeum-Thread nach, sobald alle Threads fertig sind (Review 30.10.)
        val allDone = releaseWhenDone(stopped, STOP_TIMEOUT_NANOS) { if (h != 0L) NativeRandomY.nativeRelease(h) }
        if (!allDone) log(LogEvent(LogKey.STOP_THREAD_STILL_RUNNING))
    }

    private fun work(index: Int, dataset: Long) {
        val vm = NativeRandomY.nativeCreateVm(dataset)
        if (vm == 0L) {
            log(LogEvent(LogKey.THREAD_NO_VM, listOf(index)))
            return
        }
        // Jede VM selbst pruefen (HashSelfTest.VECTORS): faengt z. B. einen nicht geleerten
        // Instruktions-Cache beim ersten JIT-Lauf ab, den der Test im Init nur fuer dessen VM abdeckt
        HashSelfTest.run(vm)?.let { error ->
            log(LogEvent(LogKey.THREAD_SELFTEST_FAILED, listOf(index, error)))
            NativeRandomY.nativeDestroyVm(vm)
            return
        }
        liveWorkers.incrementAndGet()
        val outHash = ByteArray(32)
        try {
            var current: Job? = null
            var nonce = 0L
            while (running) {
                val j = job
                if (j == null || paused) {
                    Thread.sleep(200)
                    continue
                }
                if (j !== current) {
                    current = j
                    nonce = random.nextLong()
                }
                val hit = NativeRandomY.nativeSearch(vm, j.header, nonce, BATCH, j.boundary, outHash)
                if (hit >= 0) {
                    hashes.addAndGet((hit + 1).toLong())
                    j.hashes.addAndGet((hit + 1).toLong())
                    onSolution(j, nonce + hit, outHash.copyOf())
                    nonce += hit + 1
                } else {
                    hashes.addAndGet(BATCH.toLong())
                    j.hashes.addAndGet(BATCH.toLong())
                    nonce += BATCH
                }
            }
        } catch (t: Throwable) {
            // Auf Android beendet eine unbehandelte Exception in irgendeinem Thread den ganzen Prozess
            log(LogEvent(LogKey.THREAD_ENDED, listOf(index, t.javaClass.simpleName, t.message)))
        } finally {
            liveWorkers.decrementAndGet()
            NativeRandomY.nativeDestroyVm(vm)
        }
    }

    companion object {
        /** Klein genug, dass ein neuer Job nach Bruchteilen einer Sekunde greift. */
        private const val BATCH = 16
        private const val STOP_TIMEOUT_NANOS = 5_000_000_000L
        /** So lange wartet ein neuer Start auf die nachgeholte Freigabe eines vorigen Laufs. */
        private const val RELEASE_WAIT_MS = 60_000L

        /** Nachgeholte Freigaben, die noch auf Rechen-Threads warten; prozessweit, weil jeder Lauf eine neue Engine ist. */
        private val releaseLock = ReentrantLock()
        private val released = releaseLock.newCondition()
        private var pendingReleases = 0

        /**
         * Wartet auf [threads] (eine gemeinsame Frist [timeoutNanos]) und ruft dann [release]. Laeuft danach noch
         * einer, uebernimmt ein Aufraeum-Thread: er wartet ohne Frist und gibt erst dann frei; bis dahin meldet
         * [awaitReleases] "belegt". Jede Engine gibt nur ihr eigenes Dataset frei, ein neuer Start kann also kein
         * noch rechnendes freigeben. Rueckgabe: alle Threads waren innerhalb der Frist fertig.
         */
        internal fun releaseWhenDone(threads: List<Thread>, timeoutNanos: Long, release: () -> Unit): Boolean {
            val deadline = System.nanoTime() + timeoutNanos
            threads.forEach { it.join(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)) }
            if (threads.none { it.isAlive }) {
                release()
                return true
            }
            releaseLock.withLock { pendingReleases++ }
            Thread({
                try {
                    threads.forEach { t ->
                        // Nicht abbrechen lassen: ein vorzeitiges Ende gaebe das Dataset unter einem Rechen-Thread frei
                        while (t.isAlive) runCatching { t.join() }
                    }
                    // Ungefangen beendete eine Exception hier auf Android den ganzen Prozess
                    runCatching(release)
                } finally {
                    releaseLock.withLock { pendingReleases--; released.signalAll() }
                }
            }, "miner-release").apply { isDaemon = true; start() }
            return false
        }

        /** Wartet bis zu [timeoutMs], bis keine nachgeholte Freigabe mehr aussteht; true = Speicher frei. */
        fun awaitReleases(timeoutMs: Long): Boolean {
            var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            releaseLock.withLock {
                while (pendingReleases > 0) {
                    if (left <= 0) return false
                    left = released.awaitNanos(left)
                }
                return true
            }
        }
    }
}
