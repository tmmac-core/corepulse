// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MinerSettingsTest {
    @Test fun threads() {
        assertEquals(2, CpuShare.QUARTER.threadsFor(8))
        assertEquals(2, CpuShare.QUARTER.threadsFor(6))
        assertEquals(1, CpuShare.QUARTER.threadsFor(2))
        assertEquals(4, CpuShare.HALF.threadsFor(8))
        assertEquals(6, CpuShare.THREE_QUARTERS.threadsFor(8))
        assertEquals(8, CpuShare.ALL.threadsFor(8))
        assertEquals(3, CpuShare.HALF.threadsFor(6))
        assertEquals(5, CpuShare.THREE_QUARTERS.threadsFor(6))
        assertEquals(1, CpuShare.HALF.threadsFor(1))
        assertEquals(1, CpuShare.ALL.threadsFor(0))
    }

    @Test fun workerName() {
        repeat(50) {
            val name = WorkerName.suggest()
            assertTrue(WorkerName.isValid(name) && name.matches(Regex("phone-[a-z0-9]{4}")), name)
        }
        assertEquals(15, WorkerName.sanitize("ein-sehr-langer-worker-name").length)
        assertEquals("alex-s23", WorkerName.sanitize("Alex_S23!"))
        assertTrue(WorkerName.isValid("tmmac-mac-arm64"))
        assertFalse(WorkerName.isValid(""))
        assertFalse(WorkerName.isValid("---"))
        assertFalse(WorkerName.isValid("Gross"))
    }
}
