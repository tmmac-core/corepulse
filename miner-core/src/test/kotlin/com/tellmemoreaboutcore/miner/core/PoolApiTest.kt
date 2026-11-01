// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Gegen echte Antworten der CTR-API (gespeichert 16.10. unter test/resources/ctr), ohne Netz. */
class PoolApiTest {
    private fun res(name: String) = javaClass.getResource("/ctr/$name")!!.readText()

    @Test fun settingsInXcb() {
        val s = PoolApi.parseSettings(res("settings.json"))
        assertEquals(0.1, s.payoutThresholdXcb, 1e-12)
        assertEquals(240, s.payoutIntervalMin)
        assertEquals(0.5, s.feePercent, 1e-9)
    }

    @Test fun netzdatenAuchAlsStringZahlen() {
        val n = PoolApi.parseStats(res("stats.json"))
        assertEquals(5.0, n.blockRewardXcb, 1e-9)
        assertTrue(n.difficulty > 1e8, "Difficulty: ${n.difficulty}")
        assertTrue(n.blockTimeS in 5.0..12.0, "Blockzeit: ${n.blockTimeS}")
        assertTrue(n.poolHashrate > 0)
    }

    @Test fun kontoInXcb() {
        val a = PoolApi.parseAccount(res("account.json"))
        assertTrue(a.balanceXcb in 0.0..10.0, "Guthaben: ${a.balanceXcb}")
    }

    @Test fun ertragProTag() {
        // Blockzeit kuerzt sich heraus: H/s × 86.400 × 5 XCB × 0,995 ÷ Difficulty = 1.540 × 86.400 × 4,975 ÷ 534,7 Mio. ≈ 1,238
        val net = PoolApi.Network(poolHashrate = 6.4e6, difficulty = 534_722_549.0, blockTimeS = 7.6367, blockRewardXcb = 5.0)
        assertEquals(1.2379, PoolApi.xcbPerDay(1540.0, net, 0.5), 0.001)
        assertEquals(0.0, PoolApi.xcbPerDay(0.0, net, 0.5))
    }

    @Test fun regionZumPoolServer() {
        assertEquals("https://fi-api.catchthatrabbit.com", PoolApi.baseUrl("fi.catchthatrabbit.com:8008"))
        assertEquals("https://de-api.catchthatrabbit.com", PoolApi.baseUrl(null))
        assertEquals("https://de-api.catchthatrabbit.com", PoolApi.baseUrl("127.0.0.1:1"))
    }

    @Test fun kontoFehltIstKeinFehler() {
        val snap = PoolApi.fetch("de.catchthatrabbit.com:8008", "cb00") { url ->
            when {
                url.endsWith("/api/settings") -> res("settings.json")
                url.endsWith("/api/stats") -> res("stats.json")
                else -> error("HTTP 404")
            }
        }
        assertEquals(0.0, snap.account!!.balanceXcb)
        assertEquals(0.1, snap.settings.payoutThresholdXcb, 1e-12)
    }

    @Test fun kontoNichtErreichbarIstUnbekannt() {
        val snap = PoolApi.fetch(null, "cb00") { url ->
            when {
                url.endsWith("/api/settings") -> res("settings.json")
                url.endsWith("/api/stats") -> res("stats.json")
                else -> error("HTTP 503")
            }
        }
        assertNull(snap.account)
    }

    @Test fun guthabenUeberAlleRegionen() {
        // Wie am 17.10. beobachtet: dieselbe Adresse hat in mehreren Regionen getrennte Betraege
        val konto = { bal: Long, imm: Long, paid: Long -> """{"stats":{"balance":$bal,"immature":$imm,"paid":$paid}}""" }
        val snap = PoolApi.fetch("fi.catchthatrabbit.com:8008", "cb00") { url ->
            when {
                url.endsWith("/api/settings") -> res("settings.json")
                url.endsWith("/api/stats") -> res("stats.json")
                url.startsWith("https://de-api") -> konto(81_871_310, 5_734_431, 368_299_984)
                url.startsWith("https://fi-api") -> konto(91_198_202, 0, 0)
                url.startsWith("https://sg-api") -> konto(3_886_285, 0, 0)
                else -> error("HTTP 404")
            }
        }
        assertEquals(0.176955797, snap.account!!.balanceXcb, 1e-9)
        assertEquals(0.005734431, snap.account!!.immatureXcb, 1e-9)
        assertEquals(0.368299984, snap.account!!.paidXcb, 1e-9)
        assertEquals(listOf("fi", "de", "sg"), snap.openRegions.map { it.region })
        assertEquals(false, snap.incomplete)
    }

    @Test fun fehlendeRegionMachtSummeUnvollstaendig() {
        val snap = PoolApi.fetch(null, "cb00") { url ->
            when {
                url.endsWith("/api/settings") -> res("settings.json")
                url.endsWith("/api/stats") -> res("stats.json")
                url.startsWith("https://hk-api") -> error("HTTP 503")
                else -> """{"stats":{"balance":100000000,"immature":0,"paid":0}}"""
            }
        }
        assertEquals(0.3, snap.account!!.balanceXcb, 1e-9)
        assertTrue(snap.incomplete)
    }

    @Test fun ohneAdresseKeinKontoAbruf() {
        val snap = PoolApi.fetch(null, null) { url ->
            when {
                url.endsWith("/api/settings") -> res("settings.json")
                url.endsWith("/api/stats") -> res("stats.json")
                else -> error("unerwarteter Abruf $url")
            }
        }
        assertNull(snap.account)
    }

    @Test fun apiAntwortIstBegrenzt() {
        assertEquals("{}", "{}".byteInputStream().readBounded())
        assertEquals(1000, ByteArray(1000) { 'a'.code.toByte() }.inputStream().readBounded(max = 1000).length)
        org.junit.jupiter.api.assertThrows<java.io.IOException> {
            ByteArray(1001) { 'a'.code.toByte() }.inputStream().readBounded(max = 1000)
        }
    }
}
