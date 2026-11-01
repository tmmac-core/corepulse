// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Waehlt automatisch den naechsten Pool-Server: misst parallel, wie schnell sich eine
 * TCP-Verbindung zu jedem aufbauen laesst, und sortiert danach. Gesendet wird dabei nichts;
 * die Server gehoeren alle zum selben Pool, der die IP beim Minen ohnehin sieht.
 * Nicht erreichbare Server ruecken ans Ende (in der urspruenglichen Reihenfolge), damit der
 * Ausfall-Wechsel sie trotzdem noch versucht, falls die Messung nur zufaellig scheiterte.
 */
object PoolRanking {
    /** Ergebnis je Server: Verbindungsaufbau in ms, null = nicht erreichbar. */
    data class Measurement(val endpoint: PoolEndpoint, val millis: Long?)

    fun rank(
        endpoints: List<PoolEndpoint>,
        timeoutMs: Int = 3_000,
        connectMillis: (PoolEndpoint, Int) -> Long? = ::tcpConnectMillis,
    ): List<Measurement> {
        if (endpoints.size <= 1) return endpoints.map { Measurement(it, null) }
        val pool = Executors.newFixedThreadPool(endpoints.size) { r -> Thread(r, "pool-ping").apply { isDaemon = true } }
        try {
            val futures = endpoints.map { e -> pool.submit<Long?> { runCatching { connectMillis(e, timeoutMs) }.getOrNull() } }
            val measured = endpoints.zip(futures).map { (e, f) ->
                Measurement(e, runCatching { f.get(timeoutMs + 1_000L, TimeUnit.MILLISECONDS) }.getOrNull())
            }
            // sortedBy ist stabil: gleich schnelle und nicht erreichbare behalten ihre Reihenfolge
            return measured.sortedBy { it.millis ?: Long.MAX_VALUE }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun tcpConnectMillis(endpoint: PoolEndpoint, timeoutMs: Int): Long {
        val t0 = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs) }
        return (System.nanoTime() - t0) / 1_000_000
    }
}
