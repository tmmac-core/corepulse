// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

/**
 * Known-Answer-Test fuer den kompletten Rechenweg (SHA3-512 → RandomY), bevor gemint wird.
 * Ein falsch rechnender ARM64-JIT faellt sonst erst nach ~30 min als abgelehnter Share auf
 * (Share-Difficulty ~537.000). Erwartete Werte: siehe [VECTORS], Herkunft im Kommentar dort.
 */
object HashSelfTest {
    class Vector(val headerHex: String, val nonce: Long, val expectedHex: String)

    /** Eingaben ohne erwartete Werte — `--kat` im CLI rechnet sie aus. */
    val INPUTS: List<Pair<String, Long>> = listOf(
        "00".repeat(32) to 0L,
        (0 until 32).joinToString("") { "%02x".format(it) } to 0x0e8aa21adaea1f31L,
        "ff".repeat(32) to -1L,
    )

    // Erste drei: am 15.10.2026 auf Apple M5 mit Interpreter UND ARM64-JIT gerechnet (identisch), CLI `--kat`.
    val VECTORS: List<Vector> = listOf(
        Vector(INPUTS[0].first, INPUTS[0].second, "1193e25e06f67879d119c8dd17da03659d699011d850c76689caafb823c86fed"),
        Vector(INPUTS[1].first, INPUTS[1].second, "926512322587a4497ace8fcfe89a0373ab618c814e2f59b74b376af5508507b6"),
        Vector(INPUTS[2].first, INPUTS[2].second, "e5cd291c9b6ab686e5f943ca987b45940e31ba95c9db32e2a8984d064524dcfd"),
        // Echte Shares, am 15.10.2026 vom Mac (ARM64-JIT) gefunden und von Catch That Rabbit ANGENOMMEN
        // → vom Pool bestaetigter Rechenweg, nicht nur Interpreter gegen JIT
        Vector(
            "00a4a2a4594f3d9ac20ff1bd73d8659d6b5ecbe2769a2689c7893223d41d1dd5", unsignedHex("b2d501cac4596576"),
            "0000048afa6573559e4b75f4fa47e9ff7c4bf30d3460a68e3705984abd0d0d2a",
        ),
        Vector(
            "b2953b92102404326f9558585b75c7a6c55244d059beb8d534ace0d136a1cea6", unsignedHex("8fdae1b19b109dcf"),
            "00001149e24ffedf602cef1ce8f30cde5f5b0aff05347cdc9005a045e09c18ab",
        ),
    )

    private fun unsignedHex(s: String): Long = java.lang.Long.parseUnsignedLong(s, 16)

    /** null = alle Vektoren stimmen, sonst Beschreibung der ersten Abweichung. */
    fun run(vm: Long): String? {
        for (v in VECTORS) {
            val got = NativeRandomY.nativeHash(vm, v.headerHex.hexToBytes32(), v.nonce)?.toHex()
            if (got != v.expectedHex) return "Selbsttest: header ${v.headerHex.take(8)}… nonce ${v.nonce} → $got, erwartet ${v.expectedHex}"
        }
        return null
    }
}
