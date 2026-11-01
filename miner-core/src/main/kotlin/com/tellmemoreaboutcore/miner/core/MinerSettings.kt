// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

/**
 * Wie viel der CPU der Miner nutzen darf. Bewusst wenige Stufen: einfacher als ein Schieberegler.
 * 25 % fuer heisse Handys: das S20 FE erreichte mit 50 % nach 8 min Thermal CRITICAL und rechnete danach
 * nur noch ~1/3 der Zeit (Handy-Test 16.10.) — weniger Kerne ohne Pausen koennen mehr schaffen.
 */
enum class CpuShare(val percent: Int) {
    QUARTER(25),
    HALF(50),
    THREE_QUARTERS(75),
    ALL(100);

    /** Rechen-Threads fuer [cores] Kerne, mindestens 1 (50 % von 6 Kernen = 3, 75 % von 6 = 5). */
    fun threadsFor(cores: Int): Int = ((cores * percent + 50) / 100).coerceIn(1, cores.coerceAtLeast(1))
}

/**
 * Worker-Name, unter dem das Geraet im Pool erscheint. Belegt ist bei Catch That Rabbit nur
 * Kleinbuchstaben/Ziffern/Bindestrich bis 15 Zeichen (Test-Worker `tmmac-mac-arm64`, Pool-API 15.10.2026);
 * mehr wird nicht zugelassen, damit der Pool den Namen nicht verwirft.
 */
object WorkerName {
    const val MAX_LENGTH = 15
    private val ALLOWED = Regex("[a-z0-9-]")

    /** Grossbuchstaben → klein, Leerzeichen/Unterstrich → Bindestrich, alles andere raus, gekuerzt. */
    fun sanitize(input: String): String = input.lowercase()
        .map { if (it == ' ' || it == '_') '-' else it }
        .filter { ALLOWED.matches(it.toString()) }
        .joinToString("")
        .take(MAX_LENGTH)

    fun isValid(name: String): Boolean = name.isNotEmpty() && name == sanitize(name) && name.any { it.isLetterOrDigit() }

    /**
     * Neutraler Vorschlag, z. B. "phone-7k2f" (bis 17.10. "handy-", bestehende Namen bleiben). Nicht das Geraetemodell: CTR zeigt Worker-Namen
     * oeffentlich zu jeder Adresse, das Modell wuerde dort mehr ueber den Nutzer verraten als noetig.
     */
    fun suggest(random: java.util.Random = java.security.SecureRandom()): String =
        "phone-" + (1..4).map { "abcdefghijkmnpqrstuvwxyz23456789"[random.nextInt(32)] }.joinToString("")
}
