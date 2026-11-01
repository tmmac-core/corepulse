// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Kommandozeilen-Miner fuer den End-to-End-Test vom Server:
 *   --wallet <cb…> [--worker name] [--threads n] [--seconds n] [--light] [--no-jit]
 *   --kat   rechnet die Selbsttest-Eingaben mit Interpreter und JIT (Referenzwerte fuer HashSelfTest)
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 1, partialWindows = true)
        .filter { it[0].startsWith("--") }
        .associate { it[0].removePrefix("--") to it.getOrNull(1)?.takeUnless { v -> v.startsWith("--") } }

    if (opts.containsKey("kat")) return printKnownAnswers()

    val walletInput = opts["wallet"] ?: error("--wallet fehlt")
    val wallet = when (val r = Ican.parse(walletInput)) {
        is Ican.Result.Valid -> r.address
        Ican.Result.WrongFormat -> error("Keine Core-Adresse: $walletInput")
        Ican.Result.WrongChecksum -> error("Pruefsumme falsch (Tippfehler?): $walletInput")
    }
    val worker = opts["worker"] ?: "mobile-test"
    val threads = opts["threads"]?.toInt() ?: 2
    val seconds = opts["seconds"]?.toLong() ?: 600
    val fullMem = !opts.containsKey("light")

    val acceptedCount = AtomicInteger()
    val rejectedCount = AtomicInteger()
    fun log(s: String) = println("[%tT] %s".format(System.currentTimeMillis(), s))

    lateinit var client: StratumClient
    val loginRejected = java.util.concurrent.atomic.AtomicBoolean(false)  // Listener-Thread setzt, Messschleife liest
    val engine = MinerEngine(threads, fullMem, onSolution = { job, nonce, hash ->
        log("Share gefunden: job=%s nonce=0x%016x hash=%s".format(job.headerHex, nonce, hash.toHex()))
        client.submit(job, nonce, hash)
    }, log = { log(it.german()) }, useJit = !opts.containsKey("no-jit"))

    client = StratumClient(CTR_POOLS, wallet, worker, object : StratumClient.Listener {
        override fun onConnected(endpoint: PoolEndpoint) = log("Verbunden mit $endpoint")
        override fun onDisconnected(reason: String) {
            engine.clearJob()
            log("Getrennt: $reason")
        }
        override fun onLoginRejected(reason: String) {
            loginRejected.set(true)
            log("Login abgelehnt, Client beendet: $reason")
        }
        override fun onLog(event: LogEvent) = log(event.german())
        override fun onJob(job: Job) {
            log("Neuer Job ${job.headerHex.take(12)}… Share-Difficulty ≈ %.0f".format(job.difficulty))
            engine.setJob(job)
        }
        override fun onShareResult(accepted: Boolean, error: String?) {
            if (accepted) acceptedCount.incrementAndGet() else rejectedCount.incrementAndGet()
            log(if (accepted) "Share ANGENOMMEN" else "Share ABGELEHNT: $error")
        }
    })

    log("Wallet $wallet, Worker $worker, $threads Threads, ${if (fullMem) "Full" else "Light"}-Modus")
    engine.start()
    client.start()

    val start = System.nanoTime()
    var lastHashes = 0L
    var lastT = start
    val clientId = "0x" + java.util.UUID.randomUUID().toString().replace("-", "").padEnd(64, '0')
    while ((System.nanoTime() - start) / 1e9 < seconds && !loginRejected.get()) {
        Thread.sleep(10_000)
        val now = System.nanoTime()
        val h = engine.totalHashes
        val rate = (h - lastHashes) / ((now - lastT) / 1e9)
        lastHashes = h; lastT = now
        client.reportHashrate(rate.toLong(), clientId)
        log("Hashrate %.0f H/s · angenommen %d · abgelehnt %d".format(rate, acceptedCount.get(), rejectedCount.get()))
    }
    client.stop()
    engine.stop()
    log("Ende: angenommen ${acceptedCount.get()}, abgelehnt ${rejectedCount.get()}, Hashes ${engine.totalHashes}")
}

/** Selbsttest-Eingaben mit Interpreter und JIT rechnen; nur wenn beide gleich sind, taugen sie als Referenz. */
private fun printKnownAnswers() {
    fun hashes(jit: Boolean): Pair<Int, List<String>> {
        val h = NativeRandomY.nativeInit(false, 1, jit)
        check(h != 0L) { "Init fehlgeschlagen" }
        val vm = NativeRandomY.nativeCreateVm(h)
        check(vm != 0L) { "VM fehlgeschlagen" }
        val flags = NativeRandomY.nativeFlags(h)
        val out = HashSelfTest.INPUTS.map { (header, nonce) -> NativeRandomY.nativeHash(vm, header.hexToBytes32(), nonce)!!.toHex() }
        NativeRandomY.nativeDestroyVm(vm)
        NativeRandomY.nativeRelease(h)
        return flags to out
    }
    val (fi, interp) = hashes(jit = false)
    val (fj, jit) = hashes(jit = true)
    println("Interpreter flags=$fi, JIT flags=$fj")
    HashSelfTest.INPUTS.forEachIndexed { i, (header, nonce) ->
        println("Vector(\"$header\", 0x%016xL, \"${interp[i]}\"),  // JIT ${if (jit[i] == interp[i]) "gleich" else "ABWEICHEND ${jit[i]}"}".format(nonce))
    }
}
