// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Ein-Verbindungs-Pool: beantwortet jede Zeile mit [reply]; empfangene Zeilen landen in [received]. */
internal class FakePool(val reply: (String) -> List<String>) : AutoCloseable {
    val server = ServerSocket(0)
    val received = LinkedBlockingQueue<String>()
    val endpoint = PoolEndpoint("127.0.0.1", server.localPort)
    private val t = thread(isDaemon = true) {
        runCatching {
            server.accept().use { s ->
                val r = s.getInputStream().bufferedReader()
                val w = s.getOutputStream().bufferedWriter()
                while (true) {
                    val line = r.readLine() ?: break
                    received += line
                    reply(line).forEach { w.write(it + "\n") }
                    w.flush()
                }
            }
        }
    }
    override fun close() = server.close()
}
