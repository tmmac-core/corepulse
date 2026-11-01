// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParsingTest {
    @Test fun hexMitUndOhnePraefix() {
        val expected = ByteArray(32).also { it[31] = 0x0f }
        assertArrayEquals(expected, "0x0f".hexToBytes32())
        assertArrayEquals(expected, "0X0F".hexToBytes32())
        assertArrayEquals(expected, "f".hexToBytes32())
    }

    @Test fun hexZuLang() {
        assertThrows<IllegalArgumentException> { ("0x" + "00".repeat(33)).hexToBytes32() }
    }

    /** Boundary 2^256 / 537.000 → Difficulty ≈ 537.000 (Wert aus dem Pool-Log). */
    @Test fun difficulty() {
        val boundary = java.math.BigInteger.ONE.shiftLeft(256).divide(java.math.BigInteger.valueOf(537_000))
        val job = Job(ByteArray(32), boundary.toString(16).hexToBytes32())
        assertEquals(537_000.0, job.difficulty, 1.0)
    }
}
