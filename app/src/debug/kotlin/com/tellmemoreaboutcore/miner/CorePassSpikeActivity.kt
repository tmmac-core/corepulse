// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.tellmemoreaboutcore.miner.core.Ican

/**
 * Spike (nur Debug): protokolliert, was CorePass beim Ruecksprung liefert. Core-ID nur gekuerzt
 * im Log (Anfang + Ende reichen zum Vergleich mit der Empfangsadresse in CorePass).
 */
class CorePassSpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val id = uri?.getQueryParameter("coreID") ?: uri?.getQueryParameter("coreId")
        val check = id?.let { Ican.parse(it) }
        Log.i(
            "CoreMinerSpike",
            "Ruecksprung: params=${uri?.queryParameterNames}, session=${uri?.getQueryParameter("session")}, " +
                "coreID=${id?.let { it.take(6) + "…" + it.takeLast(4) }}, ican=${check?.javaClass?.simpleName}, " +
                "netz=${(check as? Ican.Result.Valid)?.network}, signaturLaenge=${uri?.getQueryParameter("signature")?.length}, " +
                "referrer=$referrer",
        )
        finish()
    }
}
