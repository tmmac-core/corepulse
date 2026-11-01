// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

/**
 * Duenne Schicht ueber die JNI-Bibliothek `libcoreminer_jni` (RandomY + SHA3-512).
 * Auf Android laedt [System.loadLibrary] sie aus der APK; auf dem Server zeigt
 * `-Djava.library.path` auf den Host-Build.
 */
object NativeRandomY {
    init {
        System.loadLibrary("coreminer_jni")
    }

    /**
     * Cache/Dataset anlegen. fullMem=false: Light-Modus (weniger RAM, langsamer).
     * useJit=false: Interpreter (Referenz fuer den Selbsttest). 0 = Fehler.
     */
    @JvmStatic external fun nativeInit(fullMem: Boolean, initThreads: Int, useJit: Boolean): Long

    /** Tatsaechlich aktive RandomX-Flags (JIT, HARD_AES, FULL_MEM …) nach dem Init. */
    @JvmStatic external fun nativeFlags(handle: Long): Int

    @JvmStatic external fun nativeCreateVm(handle: Long): Long
    @JvmStatic external fun nativeDestroyVm(vm: Long)
    @JvmStatic external fun nativeRelease(handle: Long)
    @JvmStatic external fun nativeHash(vm: Long, header: ByteArray, nonce: Long): ByteArray?

    /** Index des ersten Treffers in [startNonce, startNonce+count) oder -1; Hash landet in [outHash]. */
    @JvmStatic external fun nativeSearch(
        vm: Long, header: ByteArray, startNonce: Long, count: Int, boundary: ByteArray, outHash: ByteArray,
    ): Int

    @JvmStatic external fun nativeSha3_512(data: ByteArray): ByteArray

    // Werte aus randomx.h (randomx_flags)
    const val FLAG_HARD_AES = 2
    const val FLAG_FULL_MEM = 4
    const val FLAG_JIT = 8
}
