// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNull

/** Prueft den Rechenweg der Host-Lib (build/host) mit und ohne JIT; ohne Host-Build uebersprungen. */
class HashSelfTestTest {
    private fun check(jit: Boolean) {
        assumeTrue(nativeLibLoadable, "Host-Lib nicht gebaut (cmake -S native -B build/host …)")
        val h = NativeRandomY.nativeInit(false, 1, jit)
        val vm = NativeRandomY.nativeCreateVm(h)
        try {
            assertNull(HashSelfTest.run(vm))
        } finally {
            NativeRandomY.nativeDestroyVm(vm)
            NativeRandomY.nativeRelease(h)
        }
    }

    @Test fun interpreter() = check(jit = false)
    @Test fun jit() = check(jit = true)
}
