// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Support-Anteil gegen Fake-Pools auf Loopback, mit verkuerzten Zeiten — kein echtes Netz, kein Mining. */
class SupportShareTest {
    private val supportHeader = "33".repeat(32)
    private val target = "0x" + "00".repeat(3) + "ff".repeat(29)

    private fun supportPool() = FakePool { line ->
        when {
            "eth_submitLogin" in line -> listOf("""{"id":1,"jsonrpc":"2.0","result":true}""")
            "eth_getWork" in line -> listOf("""{"id":5,"jsonrpc":"2.0","result":["0x$supportHeader","0x${"22".repeat(32)}","$target"]}""")
            "eth_submitWork" in line -> {
                val id = Regex(""""id":(\d+)""").find(line)!!.groupValues[1]
                listOf("""{"id":$id,"jsonrpc":"2.0","result":true}""")
            }
            else -> emptyList()
        }
    }

    /** Abschnitt gleich am Fensteranfang statt an zufaelliger Stelle. */
    private val atStart = object : Random() { override fun nextDouble() = 0.0 }

    private fun userJob() = Job(ByteArray(32) { 0x11 }, ByteArray(32) { 0x7f })

    private fun until(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    @Test fun anteilIst042Prozent() {
        assertEquals(0.0042, SupportShare.SLICE_MS.toDouble() / SupportShare.WINDOW_MS, 0.00005)
    }

    @Test fun abschnittRechnetFuerTmmacUndKehrtZurueck() {
        supportPool().use { pool ->
            val jobs = CopyOnWriteArrayList<Job?>()
            val slices = CopyOnWriteArrayList<Boolean>()
            val results = CopyOnWriteArrayList<Boolean>()
            val share = SupportShare(
                connect = { l -> StratumClient(listOf(pool.endpoint), TMMAC_SUPPORT_ADDRESS, SUPPORT_WORKER, l) },
                setJob = { jobs += it },
                events = object : SupportShare.Events {
                    override fun onSlice(active: Boolean) { slices += active }
                    override fun onShareResult(accepted: Boolean, error: String?) { results += accepted }
                },
                windowMs = 60_000, sliceMs = 600, drainMs = 2_000, random = atStart,
            )
            val user = userJob()
            share.onUserJob(user)
            share.start()
            try {
                assertTrue(until() { jobs.any { it != null && it.headerHex == supportHeader } }, "kein Support-Job: $jobs")
                val support = jobs.last()!!
                // Login mit TMMAC-Adresse und eigenem Worker-Namen
                val login = pool.received.poll(1, TimeUnit.SECONDS)!!
                assertTrue(TMMAC_SUPPORT_ADDRESS in login && "\"worker\":\"$SUPPORT_WORKER\"" in login, login)

                // Waehrend des Abschnitts: neuer Nutzer-Job wird nur gemerkt, Funde gehen richtig raus
                val user2 = userJob()
                share.onUserJob(user2)
                assertSame(support, jobs.last(), "Nutzer-Job darf den Abschnitt nicht verdraengen")
                // Rechen-Threads zaehlen je Job mit (MinerEngine): nur Support-Jobs gehen in die TMMAC-Bilanz
                support.hashes.addAndGet(1_000)
                user2.hashes.addAndGet(5)
                assertTrue(share.submitIfSupport(support, 0x42L, ByteArray(32)), "Support-Fund nicht an Support")
                assertFalse(share.submitIfSupport(user2, 0x43L, ByteArray(32)), "Nutzer-Fund an Support umgeleitet")
                val submit = generateSequence { pool.received.poll(2, TimeUnit.SECONDS) }.first { "eth_submitWork" in it }
                assertTrue("\"0x0000000000000042\"" in submit, submit)

                // Danach zurueck auf den neuesten Nutzer-Job
                assertTrue(until { jobs.last() === user2 }, "nicht zurueck zum Nutzer: $jobs")
                assertEquals(listOf(true, false), slices.toList())
                assertTrue(until { results.isNotEmpty() }, "Antwort auf den Support-Fund fehlt")
                assertEquals(listOf(true), results.toList())
                assertTrue(until { share.supportHashes == 1_000L }, "Bilanz: ${share.supportHashes}")
                assertTrue(share.supportMillis in 550..1_500, "Abschnittsdauer: ${share.supportMillis}")
            } finally {
                share.stop()
            }
        }
    }

    @Test fun ohneSupportPoolBleibtAllesBeimNutzer() {
        val dead = ServerSocket(0).let { val p = it.localPort; it.close(); PoolEndpoint("127.0.0.1", p) }
        val jobs = CopyOnWriteArrayList<Job?>()
        val slices = CopyOnWriteArrayList<Boolean>()
        val logs = CopyOnWriteArrayList<String>()
        val share = SupportShare(
            connect = { l -> StratumClient(listOf(dead), TMMAC_SUPPORT_ADDRESS, SUPPORT_WORKER, l, initialBackoffMs = 50, connectTimeoutMs = 200) },
            setJob = { jobs += it },
            events = object : SupportShare.Events {
                override fun onSlice(active: Boolean) { slices += active }
                override fun onLog(event: LogEvent) { logs += event.german() }
            },
            windowMs = 1_000, sliceMs = 100, jobWaitMs = 300, drainMs = 0, random = atStart,
        )
        val user = userJob()
        share.onUserJob(user)
        share.start()
        try {
            assertTrue(until { logs.any { "entfällt" in it } }, "Ausfall nicht gemeldet: $logs")
            assertEquals(listOf<Job?>(user), jobs.toList(), "Nutzer-Job darf nie wegfallen")
            assertEquals(emptyList<Boolean>(), slices.toList())
        } finally {
            share.stop()
        }
    }

    @Test fun nutzerVerbindungWegImAbschnitt() {
        supportPool().use { pool ->
            val jobs = CopyOnWriteArrayList<Job?>()
            val share = SupportShare(
                connect = { l -> StratumClient(listOf(pool.endpoint), TMMAC_SUPPORT_ADDRESS, SUPPORT_WORKER, l) },
                setJob = { jobs += it },
                windowMs = 60_000, sliceMs = 800, drainMs = 0, random = atStart,
            )
            share.onUserJob(userJob())
            share.start()
            try {
                assertTrue(until() { jobs.lastOrNull()?.headerHex == supportHeader }, "kein Support-Job")
                val support = jobs.last()
                // Nutzer-Pool weg: Support rechnet weiter, am Ende nichts mehr zu rechnen
                share.onUserDisconnected()
                assertSame(support, jobs.last())
                assertTrue(until { jobs.last() == null }, "nach dem Abschnitt muss ohne Nutzer-Job pausiert werden: $jobs")
                assertNull(jobs.last())
            } finally {
                share.stop()
            }
        }
    }
}
