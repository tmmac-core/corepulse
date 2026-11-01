// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** ETHPROXY gegen einen Fake-Pool auf Loopback — kein echtes Netz, kein Mining. */
class StratumClientTest {
    private val header = "11".repeat(32)
    private val target = "0x" + "00".repeat(3) + "ff".repeat(29)

    private class Recorder : StratumClient.Listener {
        val connected = CountDownLatch(1)
        val job = CountDownLatch(1)
        val share = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val shareResults = CopyOnWriteArrayList<Boolean>()
        val rejectReasons = CopyOnWriteArrayList<String>()
        val disconnects = CopyOnWriteArrayList<String>()
        @Volatile var lastJob: Job? = null
        @Volatile var connectedTo: PoolEndpoint? = null
        override fun onConnected(endpoint: PoolEndpoint) { connectedTo = endpoint; connected.countDown() }
        override fun onJob(job: Job) { lastJob = job; this.job.countDown() }
        override fun onShareResult(accepted: Boolean, error: String?) { shareResults += accepted; share.countDown() }
        override fun onLoginRejected(reason: String) { rejectReasons += reason; rejected.countDown() }
        override fun onDisconnected(reason: String) { disconnects += reason }
    }

    @Test fun loginJobUndShareAngenommen() {
        FakePool { line ->
            when {
                "eth_submitLogin" in line -> listOf("""{"id":1,"jsonrpc":"2.0","result":true}""")
                "eth_getWork" in line -> listOf("""{"id":5,"jsonrpc":"2.0","result":["0x$header","0x${"22".repeat(32)}","$target"]}""")
                "eth_submitWork" in line -> {
                    val id = Regex(""""id":(\d+)""").find(line)!!.groupValues[1]
                    listOf("""{"id":$id,"jsonrpc":"2.0","result":true}""")
                }
                else -> emptyList()
            }
        }.use { pool ->
            val rec = Recorder()
            val client = StratumClient(listOf(pool.endpoint), "cb00", "test-worker", rec)
            client.start()
            try {
                assertTrue(rec.job.await(5, TimeUnit.SECONDS), "kein Job")
                val login = pool.received.poll(1, TimeUnit.SECONDS)!!
                assertTrue("\"worker\":\"test-worker\"" in login && "cb00" in login, login)
                assertEquals(header, rec.lastJob!!.headerHex)
                assertEquals(0x00.toByte(), rec.lastJob!!.boundary[2])

                client.submit(rec.lastJob!!, 0x1234L, ByteArray(32))
                assertTrue(rec.share.await(5, TimeUnit.SECONDS), "keine Share-Antwort")
                assertEquals(listOf(true), rec.shareResults.toList())
                val submit = generateSequence { pool.received.poll(1, TimeUnit.SECONDS) }.first { "eth_submitWork" in it }
                assertTrue("\"0x0000000000001234\"" in submit, submit)
            } finally {
                client.stop()
            }
        }
    }

    @Test fun loginAbgelehntBeendetDenClient() {
        FakePool { line ->
            if ("eth_submitLogin" in line) listOf("""{"id":1,"jsonrpc":"2.0","result":null,"error":{"code":-1,"message":"invalid address"}}""")
            else emptyList()
        }.use { pool ->
            val rec = Recorder()
            val client = StratumClient(listOf(pool.endpoint), "cb00", "w", rec, initialBackoffMs = 50)
            client.start()
            try {
                assertTrue(rec.rejected.await(5, TimeUnit.SECONDS), "Ablehnung nicht gemeldet")
                assertEquals(listOf("invalid address"), rec.rejectReasons.toList())
                assertEquals(1L, rec.connected.count, "abgelehnt heisst nicht verbunden")
                Thread.sleep(300)  // 6 Backoff-Runden à 50 ms: ein Regress wuerde jetzt neu verbinden
                assertEquals(1, pool.received.count { "eth_submitLogin" in it }, "nach Ablehnung kein neues Login")
                assertEquals(emptyList<String>(), rec.disconnects.toList(), "nach Ablehnung keine Neuverbindungsversuche")
            } finally {
                client.stop()
            }
        }
    }

    @Test fun totenServerUeberspringen() {
        val dead = ServerSocket(0).let { val p = it.localPort; it.close(); PoolEndpoint("127.0.0.1", p) }
        FakePool { line ->
            if ("eth_submitLogin" in line) listOf("""{"id":1,"jsonrpc":"2.0","result":true}""") else emptyList()
        }.use { pool ->
            val rec = Recorder()
            val client = StratumClient(listOf(dead, pool.endpoint), "cb00", "w", rec, initialBackoffMs = 50, connectTimeoutMs = 500)
            client.start()
            try {
                // zwei Fehlschlaege auf dem toten Server, dann Wechsel (Connect-Timeout kurz, falls eine Firewall verwirft)
                assertTrue(rec.connected.await(10, TimeUnit.SECONDS), "kein Failover")
                assertEquals(pool.endpoint, rec.connectedTo)
            } finally {
                client.stop()
            }
        }
    }

    @Test fun stopWecktDenPruefThread() {
        val dead = List(2) { ServerSocket(0).let { val p = it.localPort; it.close(); PoolEndpoint("127.0.0.1", p) } }
        val rec = object : StratumClient.Listener { override fun onJob(job: Job) {} }
        fun recheckThreads() = Thread.getAllStackTraces().keys.filter { it.name == "stratum-recheck" }.toSet()
        val before = recheckThreads()
        // Pruef-Intervall 1 h: ohne interrupt() liefe der Thread nach stop() so lange weiter
        val client = StratumClient(dead, "cb00", "w", rec, initialBackoffMs = 50, connectTimeoutMs = 200,
            rankByLatency = true, recheckIntervalMs = 3_600_000L, ranker = { eps -> eps.map { PoolRanking.Measurement(it, null) } })
        client.start()
        val started = recheckThreads() - before
        assertEquals(1, started.size, "Pruef-Thread nicht gestartet")
        client.stop()
        started.forEach { it.join(2_000) }
        assertTrue(started.none { it.isAlive }, "Pruef-Thread laeuft nach stop() weiter")
    }

    @Test fun bleibtBeiDerHeimatAuchWennEinAndererSchnellerWird() {
        val login: (String) -> List<String> = { line ->
            if ("eth_submitLogin" in line) listOf("""{"id":1,"jsonrpc":"2.0","result":true}""") else emptyList()
        }
        FakePool(login).use { home -> FakePool(login).use { other ->
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val ranker: (List<PoolEndpoint>) -> List<PoolRanking.Measurement> = {
                if (calls.getAndIncrement() == 0) listOf(PoolRanking.Measurement(home.endpoint, 50), PoolRanking.Measurement(other.endpoint, null))
                else listOf(PoolRanking.Measurement(other.endpoint, 20), PoolRanking.Measurement(home.endpoint, 250))
            }
            val connected = CopyOnWriteArrayList<PoolEndpoint>()
            val rec = object : StratumClient.Listener {
                override fun onConnected(endpoint: PoolEndpoint) { connected += endpoint }
                override fun onJob(job: Job) {}
            }
            val client = StratumClient(listOf(home.endpoint, other.endpoint), "cb00", "w", rec,
                initialBackoffMs = 50, rankByLatency = true, recheckIntervalMs = 200, ranker = ranker)
            client.start()
            try {
                Thread.sleep(1_500)  // mehrere Pruefungen; "other" ist deutlich schneller, trotzdem kein Wechsel
                assertEquals(listOf(home.endpoint), connected.toList())
            } finally {
                client.stop()
            }
        } }
    }

    @Test fun kehrtNachAusfallZurHeimatZurueck() {
        // Heimat: die ersten zwei Verbindungen bricht sie sofort ab (Ausfall), danach meldet sie an
        val homeServer = ServerSocket(0)
        val home = PoolEndpoint("127.0.0.1", homeServer.localPort)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val s = homeServer.accept()
                    if (accepted.incrementAndGet() <= 2) { s.close(); continue }
                    thread(isDaemon = true) {
                        s.use {
                            val r = it.getInputStream().bufferedReader()
                            val w = it.getOutputStream().bufferedWriter()
                            while (true) {
                                val line = r.readLine() ?: break
                                if ("eth_submitLogin" in line) { w.write("""{"id":1,"jsonrpc":"2.0","result":true}""" + "\n"); w.flush() }
                            }
                        }
                    }
                }
            }
        }
        FakePool { line ->
            if ("eth_submitLogin" in line) listOf("""{"id":1,"jsonrpc":"2.0","result":true}""") else emptyList()
        }.use { backup ->
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val ranker: (List<PoolEndpoint>) -> List<PoolRanking.Measurement> = {
                if (calls.getAndIncrement() == 0) listOf(PoolRanking.Measurement(home, 20), PoolRanking.Measurement(backup.endpoint, 50))
                else listOf(PoolRanking.Measurement(backup.endpoint, 10), PoolRanking.Measurement(home, 30))
            }
            val connected = CopyOnWriteArrayList<PoolEndpoint>()
            val both = CountDownLatch(2)
            val rec = object : StratumClient.Listener {
                override fun onConnected(endpoint: PoolEndpoint) { connected += endpoint; both.countDown() }
                override fun onJob(job: Job) {}
            }
            val client = StratumClient(listOf(home, backup.endpoint), "cb00", "w", rec,
                initialBackoffMs = 50, rankByLatency = true, recheckIntervalMs = 300, ranker = ranker)
            client.start()
            try {
                // Heimat faellt aus → Ausweich-Server; Heimat wieder erreichbar → zurueck, obwohl der Ausweich schneller misst
                assertTrue(both.await(10, TimeUnit.SECONDS), "keine Rueckkehr: $connected")
                assertEquals(listOf(backup.endpoint, home), connected.take(2))
            } finally {
                client.stop()
                homeServer.close()
            }
        }
    }

    @Test fun ohneMesswertKeineFesteHeimat() {
        // Start im Flugmodus: keine Messung. Der erste Server der Liste (wie DE) nimmt anfangs keine Verbindung an,
        // der zweite schon. Frueher wurde der erste trotzdem Heimat und recheckLoop holte zu ihm zurueck.
        val firstServer = ServerSocket(0)
        val first = PoolEndpoint("127.0.0.1", firstServer.localPort)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val s = firstServer.accept()
                    if (accepted.incrementAndGet() <= 2) { s.close(); continue }
                    thread(isDaemon = true) {
                        s.use {
                            val r = it.getInputStream().bufferedReader()
                            val w = it.getOutputStream().bufferedWriter()
                            while (true) {
                                val line = r.readLine() ?: break
                                if ("eth_submitLogin" in line) { w.write("""{"id":1,"jsonrpc":"2.0","result":true}""" + "\n"); w.flush() }
                            }
                        }
                    }
                }
            }
        }
        FakePool { line ->
            if ("eth_submitLogin" in line) listOf("""{"id":1,"jsonrpc":"2.0","result":true}""") else emptyList()
        }.use { second ->
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val ranker: (List<PoolEndpoint>) -> List<PoolRanking.Measurement> = { eps ->
                // Erst gar kein Messwert, danach misst der erste sogar schneller
                if (calls.getAndIncrement() == 0) eps.map { PoolRanking.Measurement(it, null) }
                else listOf(PoolRanking.Measurement(first, 10), PoolRanking.Measurement(second.endpoint, 50))
            }
            val connected = CopyOnWriteArrayList<PoolEndpoint>()
            val logs = CopyOnWriteArrayList<LogEvent>()
            val onSecond = CountDownLatch(1)
            val rec = object : StratumClient.Listener {
                override fun onConnected(endpoint: PoolEndpoint) { connected += endpoint; if (endpoint == second.endpoint) onSecond.countDown() }
                override fun onJob(job: Job) {}
                override fun onLog(event: LogEvent) { logs += event }
            }
            val client = StratumClient(listOf(first, second.endpoint), "cb00", "w", rec,
                initialBackoffMs = 50, rankByLatency = true, recheckIntervalMs = 200, ranker = ranker)
            client.start()
            try {
                assertTrue(onSecond.await(10, TimeUnit.SECONDS), "kein Ausweich-Server: $connected")
                Thread.sleep(1_500)  // mehrere Pruefungen; ohne Heimat kein Rueckwechsel zum ersten
                assertEquals(listOf(second.endpoint), connected.toList())
                assertTrue(logs.none { it.key == LogKey.SERVER_CHOICE_HOME }, "Heimat ohne Messwert gesetzt: $logs")
            } finally {
                client.stop()
                firstServer.close()
            }
        }
    }

    @Test fun ueberlangePoolZeileFuehrtZuNeuverbindungStattAbsturz() {
        // 1 MiB ohne Zeilenumbruch: frueher wuchs readLine() bis zum OutOfMemoryError
        val huge = "x".repeat(1 shl 20)
        FakePool { line ->
            when {
                "eth_submitLogin" in line -> listOf("""{"id":1,"jsonrpc":"2.0","result":true}""")
                "eth_getWork" in line -> listOf(huge)
                else -> emptyList()
            }
        }.use { pool ->
            val rec = Recorder()
            val client = StratumClient(listOf(pool.endpoint), "cb00", "w", rec, initialBackoffMs = 50)
            client.start()
            try {
                assertTrue(rec.connected.await(5, TimeUnit.SECONDS), "kein Login")
                val deadline = System.nanoTime() + 5_000_000_000L
                while (rec.disconnects.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
                val reason = rec.disconnects.firstOrNull() ?: error("Abbruch nicht gemeldet")
                assertTrue("zu lang" in reason, reason)
                assertTrue(reason.length <= MAX_LOG_TEXT + 40, "Grund ungekuerzt: ${reason.length} Zeichen")
            } finally {
                client.stop()
            }
        }
    }

    @Test fun begrenztesZeilenlesen() {
        fun reader(text: String) = java.io.BufferedReader(java.io.StringReader(text))
        val r = reader("eins\r\nzwei\ndrei")
        assertEquals("eins", r.readLineBounded())
        assertEquals("zwei", r.readLineBounded())
        assertEquals("drei", r.readLineBounded())
        assertEquals(null, r.readLineBounded())
        assertEquals("a".repeat(10), reader("a".repeat(10) + "\n").readLineBounded(max = 10))
        org.junit.jupiter.api.assertThrows<java.io.IOException> { reader("a".repeat(11) + "\n").readLineBounded(max = 10) }
    }
}
