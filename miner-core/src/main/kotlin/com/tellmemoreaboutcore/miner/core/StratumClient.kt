// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Catch-That-Rabbit-Server (README coreminer, Port 8008, ETHPROXY). */
val CTR_POOLS = listOf(
    PoolEndpoint("de.catchthatrabbit.com", 8008),
    PoolEndpoint("fi.catchthatrabbit.com", 8008),
    PoolEndpoint("sg.catchthatrabbit.com", 8008),
    PoolEndpoint("hk.catchthatrabbit.com", 8008),
)

/**
 * Arbeitspaket vom Pool: Header-Hash und Share-Ziel (je 32 Byte). Den ebenfalls gelieferten Seed
 * braucht der Hash nicht — RandomY nutzt wie coreminer den festen Cache-Key {53,54,55,56,57}.
 */
class Job(val header: ByteArray, val boundary: ByteArray) {
    val headerHex: String = header.toHex()
    /** Auf diesem Job gerechnete Hashes (MinerEngine zaehlt mit); Beleg fuer den Support-Anteil. */
    val hashes = java.util.concurrent.atomic.AtomicLong(0)
    /** Grobe Share-Difficulty (2^256 / boundary), nur fuer Anzeige und Logs. */
    val difficulty: Double get() = Math.pow(2.0, 256.0) / java.math.BigInteger(1, boundary).toDouble()
}

data class PoolEndpoint(val host: String, val port: Int) {
    override fun toString() = "$host:$port"
}

/**
 * Pool-Client fuer das ETHPROXY-Protokoll, so wie coreminer es mit Catch That Rabbit spricht
 * (`stratum1+tcp://WALLET.WORKER@host:8008`, XcbStratumClient.cpp):
 *   → eth_submitLogin [wallet] + Feld "worker"   ← result true
 *   → eth_getWork []                              ← result [header, seed, target, …]
 *   ← Pool schiebt neue Jobs als {"id":0,"result":[…]}
 *   → eth_submitWork [nonce, header, hash]        ← result true/false
 * Zeilenbasiertes JSON ueber TCP. Mit [rankByLatency] wird beim Start der schnellste Server gewaehlt
 * ([PoolRanking]) und bleibt fuer den ganzen Lauf die Heimat: Jede CTR-Region ist ein eigener Pool mit eigenem
 * Guthaben und zahlt erst ab 0,1 XCB aus, jeder Wechsel liesse einen Rest liegen (Nachtlauf 17.10.). Bei
 * Verbindungsabbruch erst derselbe Server erneut, nach zwei Fehlschlaegen in Folge der naechste der Liste;
 * laeuft die Sitzung danach auf einem Ausweich-Server, prueft der Client alle [recheckIntervalMs], ob die
 * Heimat wieder erreichbar ist, und kehrt dann zurueck. Ein schnellerer Server allein ist kein Wechselgrund. Lehnt der Pool das Login ab
 * (z. B. Adresse gesperrt), gibt der Client auf: ein neuer Versuch aendert daran nichts.
 */
class StratumClient(
    private val endpoints: List<PoolEndpoint>,
    private val wallet: String,
    private val worker: String,
    private val listener: Listener,
    private val readTimeoutMs: Int = 60_000,  // Pool schiebt alle paar Sekunden Jobs; Stille = tote Verbindung
    private val initialBackoffMs: Long = 2_000L,
    private val connectTimeoutMs: Int = 10_000,
    /** Vor dem ersten Verbinden und nach jedem kompletten Durchlauf den schnellsten Server zuerst. */
    private val rankByLatency: Boolean = false,
    private val recheckIntervalMs: Long = 15 * 60_000L,
    private val ranker: (List<PoolEndpoint>) -> List<PoolRanking.Measurement> = { PoolRanking.rank(it) },
) {
    interface Listener {
        /** Angemeldet und bereit fuer Jobs. */
        fun onConnected(endpoint: PoolEndpoint) {}
        fun onDisconnected(reason: String) {}
        /** Pool lehnt die Anmeldung ab; der Client hat sich beendet. */
        fun onLoginRejected(reason: String) {}
        fun onJob(job: Job)
        fun onShareResult(accepted: Boolean, error: String?) {}
        fun onLog(event: LogEvent) {}
        /** Schwerer Fehler (z. B. Speichermangel) im Pool-Thread; der Client hat sich beendet. */
        fun onFatal(reason: String) {}
    }

    private class LoginRejected(message: String) : IOException(message)

    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private val nextId = AtomicInteger(100)
    private val pendingSubmits = ConcurrentHashMap.newKeySet<Int>()
    // Shares gehen ueber einen eigenen Thread raus: ein haengender Socket blockiert so keinen Rechen-Thread
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "stratum-send").apply { isDaemon = true } }
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var loggedIn = false
    /** Zeitpunkt des Logins der laufenden Sitzung, 0 = (noch) kein Login. */
    @Volatile private var loginAtNanos = 0L
    private var thread: Thread? = null
    /** Prueft die Heimat; schlaeft bis zu [recheckIntervalMs] und muss beim Stopp geweckt werden. */
    private var recheckThread: Thread? = null
    /** Server der laufenden Sitzung. */
    @Volatile private var currentEndpoint: PoolEndpoint? = null
    /** Heimat-Server: bei der ersten Messung mit Messwert der schnellste erreichbare, danach fest fuer den ganzen Lauf. */
    @Volatile private var home: PoolEndpoint? = null
    /** Neue Reihenfolge nach der Rueckkehr zur Heimat; runLoop uebernimmt sie. */
    @Volatile private var pendingOrder: List<PoolEndpoint>? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::runLoop, "stratum").apply { isDaemon = true; start() }
        if (rankByLatency && endpoints.size > 1) {
            recheckThread = Thread(::recheckLoop, "stratum-recheck").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        thread?.interrupt()
        // Sonst schlaeft er bis zu 15 min weiter und haelt den Client im Speicher (Release-Review 28.10.)
        recheckThread?.interrupt()
        sender.shutdownNow()
    }

    /** Darf aus jedem Thread kommen; kehrt sofort zurueck. */
    fun submit(job: Job, nonce: Long, hash: ByteArray) {
        if (!running.get()) return
        runCatching { sender.execute { sendSubmit(job, nonce, hash) } }
    }

    private fun sendSubmit(job: Job, nonce: Long, hash: ByteArray) {
        val id = nextId.getAndIncrement()
        pendingSubmits.add(id)
        val sent = loggedIn && send(buildJsonObject {
            put("id", id)
            put("jsonrpc", "2.0")
            put("method", "eth_submitWork")
            put("params", buildJsonArray {
                add(JsonPrimitive("0x%016x".format(nonce)))
                add(JsonPrimitive("0x" + job.headerHex))
                add(JsonPrimitive("0x" + hash.toHex()))
            })
            put("worker", worker)
        })
        if (!sent) {
            // Zwischen Abbruch und Neuverbindung: Job ist danach meist veraltet, daher nicht puffern
            pendingSubmits.remove(id)
            listener.onLog(LogEvent(LogKey.SHARE_DROPPED_NO_POOL, listOf(nonce)))
        }
    }

    /** Hashrate an den Pool melden (Anzeige im Pool-Dashboard), Format wie coreminer. */
    fun reportHashrate(hashesPerSecond: Long, clientId: String) {
        if (!loggedIn) return
        send(buildJsonObject {
            put("id", 9)
            put("jsonrpc", "2.0")
            put("method", "eth_submitHashrate")
            put("params", buildJsonArray {
                add(JsonPrimitive("0x" + hashesPerSecond.toString(16).padStart(64, '0')))  // 32 Byte
                add(JsonPrimitive(clientId))
            })
            put("worker", worker)
        })
    }

    private fun runLoop() {
        var index = 0
        var failuresHere = 0
        var backoffMs = initialBackoffMs
        var order = endpoints
        while (running.get()) {
            val switchTo = pendingOrder
            if (switchTo != null) {
                pendingOrder = null
                order = switchTo
                index = 0
                failuresHere = 0
            } else if (rankByLatency && index % endpoints.size == 0 && failuresHere == 0) {
                // Neu messen beim Start und wenn alle Server einmal durch sind (Netz gewechselt, Server zurueck)
                val ranked = runCatching { ranker(endpoints) }.getOrElse { endpoints.map { PoolRanking.Measurement(it, null) } }
                if (!running.get()) break
                // Heimat nur aus echter Messung: ohne Messwert (Flugmodus, kein Netz) waere es sonst fest der
                // erste der Liste (DE), und recheckLoop holte den ganzen Lauf dorthin zurueck (Review 30.10.).
                // Dann bleibt sie offen und wird beim naechsten Durchlauf mit Messwert bestimmt.
                val first = home == null && ranked.any { it.millis != null }
                if (first) home = ranked.first { it.millis != null }.endpoint
                order = homeFirst(ranked)
                val list = ranked.joinToString(", ") {
                    "${it.endpoint.host.substringBefore('.')} ${it.millis?.let { ms -> "$ms ms" } ?: "–"}"
                }
                listener.onLog(
                    if (first) LogEvent(LogKey.SERVER_CHOICE_HOME, listOf(list, home?.host?.substringBefore('.')))
                    else LogEvent(LogKey.SERVER_CHOICE, listOf(list))
                )
            }
            val endpoint = order[index % order.size]
            loginAtNanos = 0L  // vor connect zuruecksetzen, sonst zaehlt ein toter Server als "gute Sitzung"
            try {
                session(endpoint)
            } catch (e: LoginRejected) {
                running.set(false)
                sender.shutdownNow()
                listener.onLoginRejected(e.message ?: "unbekannt")
            } catch (e: Exception) {
                if (!running.get()) break
                listener.onDisconnected("${endpoint}: ${(e.message ?: e.javaClass.simpleName).take(MAX_LOG_TEXT)}")
            } catch (t: Throwable) {
                // OutOfMemoryError & Co.: ungefangen im Thread beendet Android den ganzen Prozess
                running.set(false)
                sender.shutdownNow()
                listener.onFatal(t.toString().take(MAX_LOG_TEXT))
            } finally {
                endSession()
            }
            if (!running.get()) break
            // session() endet immer mit Exception; gut lief sie, wenn das Login mind. 60 s hielt
            // (ein Pool, der nach dem Login sofort trennt, soll nicht alle 2 s angefragt werden)
            val healthy = loginAtNanos != 0L && System.nanoTime() - loginAtNanos >= HEALTHY_SESSION_NANOS
            if (healthy) {
                backoffMs = initialBackoffMs
                failuresHere = 0
            } else if (++failuresHere >= 2) {
                index++
                failuresHere = 0
            }
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
            if (!healthy) backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    /** Erreichbare Heimat zuerst, Rest nach Messung; ohne Heimat-Messwert die reine Messreihenfolge. */
    private fun homeFirst(ranked: List<PoolRanking.Measurement>): List<PoolEndpoint> {
        val h = home
        val all = ranked.map { it.endpoint }
        return if (h != null && ranked.any { it.endpoint == h && it.millis != null }) listOf(h) + (all - h) else all
    }

    /** Laeuft die Sitzung auf einem Ausweich-Server, regelmaessig pruefen, ob die Heimat wieder da ist. */
    private fun recheckLoop() {
        while (running.get()) {
            try { Thread.sleep(recheckIntervalMs) } catch (_: InterruptedException) { return }
            val current = currentEndpoint
            val h = home
            if (!running.get() || !loggedIn || current == null || h == null || current == h) continue
            val ranked = runCatching { ranker(endpoints) }.getOrNull() ?: continue
            val homeMillis = ranked.firstOrNull { it.endpoint == h }?.millis ?: continue
            // Nicht mitten in eine Share-Antwort hinein trennen (max. 10 s warten)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (pendingSubmits.isNotEmpty() && System.nanoTime() < deadline) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { return }
            }
            if (!running.get() || currentEndpoint != current) continue
            listener.onLog(LogEvent(LogKey.BACK_TO_HOME, listOf(h.host.substringBefore('.'), homeMillis, current.host.substringBefore('.'))))
            pendingOrder = homeFirst(ranked)
            runCatching { socket?.close() }
        }
    }

    private fun endSession() {
        currentEndpoint = null
        loggedIn = false
        writer = null
        runCatching { socket?.close() }
        socket = null
        if (pendingSubmits.isNotEmpty()) {
            listener.onLog(LogEvent(LogKey.SHARES_UNANSWERED, listOf(pendingSubmits.size)))
            pendingSubmits.clear()
        }
    }

    private fun session(endpoint: PoolEndpoint) {
        Socket().use { s ->
            s.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            s.keepAlive = true
            socket = s
            currentEndpoint = endpoint
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

            send(buildJsonObject {
                put("id", 1)
                put("jsonrpc", "2.0")
                put("method", "eth_submitLogin")
                put("params", buildJsonArray { add(JsonPrimitive(wallet)) })
                put("worker", worker)
            })

            var silentTimeouts = 0
            while (running.get()) {
                val line = try {
                    reader.readLineBounded() ?: throw IOException("Pool hat die Verbindung beendet")
                } catch (e: java.net.SocketTimeoutException) {
                    // Lange Stille kann ein langer Block sein: einmal per getWork nachfragen, erst dann trennen
                    if (++silentTimeouts >= 2 || !loggedIn) throw e
                    send(buildJsonObject {
                        put("id", 5); put("jsonrpc", "2.0"); put("method", "eth_getWork")
                        put("params", JsonArray(emptyList()))
                    })
                    continue
                }
                silentTimeouts = 0
                if (line.isBlank()) continue
                handle(line, endpoint)
            }
        }
    }

    private fun handle(line: String, endpoint: PoolEndpoint) {
        val msg = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrElse {
            listener.onLog(LogEvent(LogKey.UNREADABLE_POOL_MESSAGE, listOf(line.take(MAX_LOG_TEXT))))
            return
        }
        val id = (msg["id"] as? JsonPrimitive)?.intOrNull
        val result = msg["result"]
        val error = msg["error"]?.takeIf { it !is JsonNull }

        when {
            id == 1 -> {
                if (result is JsonPrimitive && result.booleanOrNull == true) {
                    loggedIn = true
                    loginAtNanos = System.nanoTime()
                    listener.onConnected(endpoint)
                    send(buildJsonObject {
                        put("id", 5); put("jsonrpc", "2.0"); put("method", "eth_getWork")
                        put("params", JsonArray(emptyList()))
                    })
                } else {
                    throw LoginRejected(describe(error ?: result))
                }
            }
            result is JsonArray && result.size >= 3 -> parseJob(result)?.let(listener::onJob)
            id != null && pendingSubmits.remove(id) -> {
                val ok = result is JsonPrimitive && result.booleanOrNull == true
                listener.onShareResult(ok, if (ok) null else describe(error ?: result))
            }
            error != null -> listener.onLog(LogEvent(LogKey.POOL_ERROR, listOf(id, describe(error))))
        }
    }

    private fun parseJob(arr: JsonArray): Job? = runCatching {
        Job(header = arr[0].jsonPrimitive.content.hexToBytes32(), boundary = arr[2].jsonPrimitive.content.hexToBytes32())
    }.getOrElse {
        listener.onLog(LogEvent(LogKey.JOB_UNREADABLE, listOf(arr.toString().take(MAX_LOG_TEXT))))
        null
    }

    /** Pool-Text fuers Log, gekuerzt: ein Pool (oder ein Angreifer auf der unverschluesselten Verbindung) bestimmt die Laenge. */
    private fun describe(e: JsonElement?): String = when (e) {
        null -> "unbekannt"
        is JsonObject -> (e["message"] as? JsonPrimitive)?.contentOrNull ?: e.toString()
        else -> e.toString()
    }.take(MAX_LOG_TEXT)

    /** false = nicht gesendet (keine Verbindung oder Schreibfehler). */
    @Synchronized
    private fun send(obj: JsonObject): Boolean {
        val w = writer ?: return false
        return try {
            w.write(obj.toString())
            w.write("\n")
            w.flush()
            true
        } catch (e: IOException) {
            runCatching { socket?.close() }
            false
        }
    }
}

private const val HEALTHY_SESSION_NANOS = 60_000_000_000L

/** Laengste Pool-Zeile; echte Nachrichten sind < 1 KiB. */
internal const val MAX_POOL_LINE = 64 * 1024
/** Laengster Pool-Text im Log. */
internal const val MAX_LOG_TEXT = 200

/**
 * Wie readLine(), aber eine Zeile ueber [max] Zeichen wirft eine IOException → normaler Neuverbindungs-Pfad.
 * Ohne Grenze waechst eine Zeile ohne Zeilenumbruch bis zum OutOfMemoryError.
 */
internal fun BufferedReader.readLineBounded(max: Int = MAX_POOL_LINE): String? {
    val sb = StringBuilder()
    while (true) {
        val c = read()
        if (c == -1) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString().removeSuffix("\r")
        if (sb.length >= max) throw IOException("Pool-Zeile zu lang (> $max Zeichen)")
        sb.append(c.toChar())
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Hex (mit/ohne 0x) → 32 Byte, links mit Nullen aufgefuellt (wie coreminer fuer kurze Targets). */
internal fun String.hexToBytes32(): ByteArray {
    val h = removePrefix("0x").removePrefix("0X").padStart(64, '0')
    require(h.length == 64) { "kein 32-Byte-Wert: $this" }
    return ByteArray(32) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
