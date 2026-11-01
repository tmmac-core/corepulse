// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Engine gegen die Host-Lib: Boundary ff… = jeder Hash ist ein Treffer. Light-Modus haelt den Test klein. */
class MinerEngineTest {
    @Test fun findetTrefferPausiertOhneJobUndLaeuftWeiter() {
        assumeTrue(nativeLibLoadable, "Host-Lib nicht gebaut")
        val hits = CountDownLatch(3)
        val engine = MinerEngine(threads = 1, fullMem = false, onSolution = { _, _, hash ->
            assertEquals(32, hash.size)
            hits.countDown()
        })
        engine.start()
        try {
            engine.setJob(Job(ByteArray(32), ByteArray(32) { 0xff.toByte() }))
            assertTrue(hits.await(30, TimeUnit.SECONDS), "keine Treffer")
            assertTrue(engine.totalHashes >= 3)

            engine.clearJob()
            Thread.sleep(500)  // laufender nativeSearch-Aufruf endet (mit Boundary ff… nach 1 Hash)
            val paused = engine.totalHashes
            Thread.sleep(700)
            assertEquals(paused, engine.totalHashes, "ohne Job darf nicht gerechnet werden")

            // Neuer Job (z. B. nach Neuverbindung) → Threads rechnen wieder
            engine.setJob(Job(ByteArray(32) { 1 }, ByteArray(32) { 0xff.toByte() }))
            val before = engine.totalHashes
            val deadline = System.nanoTime() + 10_000_000_000L
            while (engine.totalHashes < before + 2 && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(engine.totalHashes >= before + 2, "nach neuem Job wird nicht weitergerechnet")

            // Hitze-Pause: trotz Job keine Hashes, danach ohne Neuaufbau weiter
            engine.paused = true
            Thread.sleep(500)
            val cooled = engine.totalHashes
            Thread.sleep(700)
            assertEquals(cooled, engine.totalHashes, "in der Hitze-Pause darf nicht gerechnet werden")
            engine.paused = false
            val warm = engine.totalHashes
            val until = System.nanoTime() + 10_000_000_000L
            while (engine.totalHashes < warm + 2 && System.nanoTime() < until) Thread.sleep(50)
            assertTrue(engine.totalHashes >= warm + 2, "nach der Hitze-Pause wird nicht weitergerechnet")
        } finally {
            engine.stop()
        }
    }

    @Test fun freigabeWirdNachgeholtWennEinThreadNochRechnet() {
        // Ohne Native-Lib: ein Thread "rechnet" ueber die Stopp-Frist hinaus, bis der Latch faellt
        val busy = CountDownLatch(1)
        val worker = Thread { busy.await() }.apply { isDaemon = true; start() }
        val releases = AtomicInteger()
        val done = MinerEngine.releaseWhenDone(listOf(worker), 100_000_000L) { releases.incrementAndGet() }
        try {
            assertFalse(done, "Thread rechnet noch, darf nicht als fertig gelten")
            assertEquals(0, releases.get(), "Dataset unter einem rechnenden Thread freigegeben")
            assertFalse(MinerEngine.awaitReleases(200), "neuer Start darf vor der Freigabe nicht loslaufen")
        } finally {
            busy.countDown()
        }
        assertTrue(MinerEngine.awaitReleases(5_000), "Freigabe nicht nachgeholt")
        assertEquals(1, releases.get())
    }

    @Test fun freigabeSofortWennAlleThreadsFertig() {
        val worker = Thread {}.apply { start() }
        val releases = AtomicInteger()
        assertTrue(MinerEngine.releaseWhenDone(listOf(worker), 1_000_000_000L) { releases.incrementAndGet() })
        assertEquals(1, releases.get())
        assertTrue(MinerEngine.awaitReleases(0))
    }
}
