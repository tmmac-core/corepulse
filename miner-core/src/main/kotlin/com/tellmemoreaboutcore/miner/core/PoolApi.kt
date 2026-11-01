// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Oeffentliche Web-API von Catch That Rabbit (open-ethereum-pool-Stil), fuer Ertrag, Guthaben und Pool-Anteil.
 * erlaubt, weil derselbe Betreiber wie der Pool — er kennt Adresse, IP und Hashrate ohnehin.
 * Keine andere Stelle, kein Kurs. Nur HTTPS-GET, nichts wird gesendet ausser der Adresse im Pfad.
 *
 * Endpunkte je Region (`https://<de|fi|sg|hk>-api.catchthatrabbit.com`):
 *   /api/settings           → PayoutThreshold (in 1e-9 XCB), PayoutInterval ("240m"), PoolFee (%)
 *   /api/stats              → hashrate (Pool, H/s), blockReward (1e-18 XCB), nodes[0].difficulty/.blocktime (s)
 *   /api/accounts/<adresse> → stats.balance / .immature / .paid (je 1e-9 XCB), currentHashrate
 * Betraege rechnet der Pool in 1e-9 XCB (wie "Shannon" bei open-ethereum-pool); geprueft 16.10.:
 * PayoutThreshold 100000000 = 0,1 XCB, blockReward 5e18 = 5 XCB.
 *
 * Jede Region ist ein eigener Pool mit eigenem Guthaben, jede zahlt erst ab der Schwelle aus (geprueft 17.10.:
 * dieselbe Adresse hatte in DE, FI und SG je einen eigenen offenen Betrag). Das Konto wird deshalb in allen
 * Regionen abgefragt und summiert; Einstellungen und Netzdaten kommen von der Region des aktuellen Servers.
 */
object PoolApi {
    data class Settings(val payoutThresholdXcb: Double, val payoutIntervalMin: Int?, val feePercent: Double)
    data class Network(val poolHashrate: Double, val difficulty: Double, val blockTimeS: Double, val blockRewardXcb: Double)
    data class Account(val balanceXcb: Double, val immatureXcb: Double, val paidXcb: Double)
    /** Konto in einer Region; [account] null = Region gerade nicht abrufbar. */
    data class RegionAccount(val region: String, val account: Account?)

    data class Snapshot(val settings: Settings, val network: Network, val regions: List<RegionAccount>, val fetchedAtMs: Long) {
        /** Summe ueber alle abrufbaren Regionen; null = keine Region abrufbar (oder keine Adresse). */
        val account: Account? = regions.mapNotNull { it.account }.takeIf { it.isNotEmpty() }?.let { known ->
            Account(known.sumOf { it.balanceXcb }, known.sumOf { it.immatureXcb }, known.sumOf { it.paidXcb })
        }
        /** Mindestens eine Region fehlt in der Summe. */
        val incomplete: Boolean get() = account != null && regions.any { it.account == null }
        /** Regionen mit offenem Betrag (Guthaben oder unbestaetigt), groesster zuerst. */
        val openRegions: List<RegionAccount> get() = regions
            .filter { (it.account?.let { a -> a.balanceXcb + a.immatureXcb } ?: 0.0) > 0 }
            .sortedByDescending { it.account!!.balanceXcb + it.account.immatureXcb }
    }

    val REGIONS = listOf("de", "fi", "sg", "hk")

    private val json = Json { ignoreUnknownKeys = true }
    private const val NANO = 1e9

    /** API-Host zur Pool-Region; unbekannt → DE. */
    fun baseUrl(poolHost: String?): String {
        val region = poolHost?.substringBefore('.')?.lowercase()?.takeIf { it in REGIONS } ?: "de"
        return regionUrl(region)
    }

    private fun regionUrl(region: String) = "https://$region-api.catchthatrabbit.com"


    fun parseSettings(body: String): Settings {
        val o = json.parseToJsonElement(body).jsonObject
        return Settings(
            payoutThresholdXcb = o.num("PayoutThreshold") / NANO,
            payoutIntervalMin = o.str("PayoutInterval")?.removeSuffix("m")?.toIntOrNull(),
            feePercent = o.num("PoolFee"),
        )
    }

    fun parseStats(body: String): Network {
        val o = json.parseToJsonElement(body).jsonObject
        val node = o["nodes"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("keine Netzdaten")
        return Network(
            poolHashrate = o.num("hashrate"),
            difficulty = node.num("difficulty"),
            blockTimeS = node.num("blocktime"),
            blockRewardXcb = o.num("blockReward") / 1e18,
        )
    }

    fun parseAccount(body: String): Account {
        val stats = json.parseToJsonElement(body).jsonObject["stats"]?.jsonObject ?: error("kein Konto")
        return Account(stats.num("balance") / NANO, stats.num("immature") / NANO, stats.num("paid") / NANO)
    }

    /**
     * Erwarteter Ertrag in XCB pro Tag fuer [hashrate]: Anteil an der Netz-Hashrate (Difficulty ÷ Blockzeit)
     * × Bloecke pro Tag × Blockbelohnung, abzueglich Pool-Gebuehr. Ein Mittelwert: Der Pool teilt nach Shares
     * (PPLNS), kurzfristig schwankt es.
     */
    fun xcbPerDay(hashrate: Double, net: Network, feePercent: Double): Double {
        if (hashrate <= 0 || net.difficulty <= 0 || net.blockTimeS <= 0) return 0.0
        val networkHashrate = net.difficulty / net.blockTimeS
        val blocksPerDay = 86_400 / net.blockTimeS
        return hashrate / networkHashrate * blocksPerDay * net.blockRewardXcb * (1 - feePercent / 100)
    }

    /** Alles in einem Durchgang; [address] null = nur Pool-/Netzdaten. Blockiert, also nicht im UI-Thread. */
    fun fetch(poolHost: String?, address: String?, get: (String) -> String = ::httpGet): Snapshot {
        val base = baseUrl(poolHost)
        return Snapshot(
            settings = parseSettings(get("$base/api/settings")),
            network = parseStats(get("$base/api/stats")),
            // 404 = Adresse hat dort noch nie gemint → 0; andere Fehler → unbekannt (null), nicht "0" vortaeuschen
            regions = address?.let { a ->
                REGIONS.map { r ->
                    RegionAccount(r, runCatching { parseAccount(get("${regionUrl(r)}/api/accounts/$a")) }.getOrElse { e ->
                        if (e.message == "HTTP 404") Account(0.0, 0.0, 0.0) else null
                    })
                }
            } ?: emptyList(),
            fetchedAtMs = System.currentTimeMillis(),
        )
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/json")
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { it.readBounded() }
        } finally {
            c.disconnect()
        }
    }

    private fun JsonObject.num(key: String): Double = this[key].numOrNull() ?: 0.0
    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    // Die API liefert Zahlen teils als String ("534722549"), teils als Zahl
    private fun JsonElement?.numOrNull(): Double? = this?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
}

/** Groesste API-Antwort; echte Antworten sind wenige KiB. */
private const val MAX_BODY = 1 shl 20

/** Liest hoechstens [max] Bytes (readNBytes gibt es erst ab API 33), sonst IOException. */
internal fun java.io.InputStream.readBounded(max: Int = MAX_BODY): String {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (out.size() + n > max) throw java.io.IOException("API-Antwort zu gross (> $max Byte)")
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}
