// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Eigenes SHA3-512 (native/src/sha3.c) gegen Referenzwerte aus Python `hashlib.sha3_512`. */
class Sha3Test {
    private fun sha3(data: ByteArray): String {
        assumeTrue(nativeLibLoadable, "Host-Lib nicht gebaut")
        return NativeRandomY.nativeSha3_512(data).toHex()
    }

    @Test fun leer() = assertEquals(
        "a69f73cca23a9ac5c8b567dc185a756e97c982164fe25859e0d1dcc1475c80a615b2123af1f5f94c11e3e9402c3ac558f500199d95b6d3e301758586281dcd26",
        sha3(ByteArray(0)),
    )

    @Test fun abc() = assertEquals(
        "b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0",
        sha3("abc".toByteArray()),
    )

    /** 200 Byte = mehr als ein Block (Rate 72 Byte), prueft das Absorbieren ueber Blockgrenzen. */
    @Test fun mehrereBloecke() = assertEquals(
        "ea5d05f19348dd589793354793a15f37a73b4c0bb4e750b9a00757dfce2f8b65a64191bb9b137de00feef6474cfd47abf7880efbc51614a5715df12cfe0caee3",
        sha3(ByteArray(200) { it.toByte() }),
    )
}
