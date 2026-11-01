// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Server-Wahl ohne echtes Netz: die Antwortzeiten kommen aus einer Tabelle. */
class PoolRankingTest {
    private val de = PoolEndpoint("de.example", 1)
    private val fi = PoolEndpoint("fi.example", 1)
    private val sg = PoolEndpoint("sg.example", 1)
    private val hk = PoolEndpoint("hk.example", 1)

    private fun rank(times: Map<PoolEndpoint, Long?>) =
        PoolRanking.rank(listOf(de, fi, sg, hk)) { e, _ -> times[e] ?: throw java.io.IOException("nicht erreichbar") }

    @Test fun schnellsterZuerst() {
        // Nutzer in Asien: Singapur und Hongkong vor Europa
        val r = rank(mapOf(de to 240L, fi to 260L, sg to 35L, hk to 20L))
        assertEquals(listOf(hk, sg, de, fi), r.map { it.endpoint })
        assertEquals(20L, r.first().millis)
    }

    @Test fun nichtErreichbareAnsEndeInAlterReihenfolge() {
        val r = rank(mapOf(fi to 50L, hk to 80L))
        assertEquals(listOf(fi, hk, de, sg), r.map { it.endpoint })
        assertEquals(listOf(50L, 80L, null, null), r.map { it.millis })
    }

    @Test fun ohneNetzBleibtDieListe() {
        assertEquals(listOf(de, fi, sg, hk), rank(emptyMap()).map { it.endpoint })
    }

    @Test fun haengenderServerBremstNichtLaengerAlsDasZeitlimit() {
        val t0 = System.nanoTime()
        val r = PoolRanking.rank(listOf(de, fi), timeoutMs = 200) { e, _ -> if (e == de) { Thread.sleep(10_000); 1L } else 30L }
        assertEquals(listOf(fi, de), r.map { it.endpoint })
        assert((System.nanoTime() - t0) / 1_000_000 < 3_000) { "Messung wartet zu lange" }
    }
}
