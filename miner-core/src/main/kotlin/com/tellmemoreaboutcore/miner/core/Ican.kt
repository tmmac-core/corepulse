// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import java.math.BigInteger

/**
 * Core-Adressen nach CIP-100 (ICAN): 2 Zeichen Netz (CB Mainnet, AB Testnet, CE privat),
 * 2 Pruefziffern, 40 Hex-Zeichen. Pruefung wie IBAN (ISO 7064 MOD-97-10): die ersten
 * vier Zeichen ans Ende, Buchstaben A–F durch 10–15 ersetzen, Rest mod 97 muss 1 sein.
 * So faellt ein Tippfehler auf, bevor Mining-Ertrag an eine falsche Adresse geht.
 */
object Ican {
    private val FORMAT = Regex("^(CB|AB|CE)[0-9]{2}[0-9A-F]{40}$")
    private val NINETY_SEVEN = BigInteger.valueOf(97)

    enum class Network { MAINNET, TESTNET, PRIVATE }

    sealed interface Result {
        data class Valid(val address: String, val network: Network) : Result
        data object WrongFormat : Result
        data object WrongChecksum : Result
    }

    /** Nimmt nackte Adressen (auch mit Leerzeichen, klein geschrieben), `corepass:<adresse>` und `payto://xcb/<adresse>?…`. */
    fun parse(input: String): Result {
        val address = normalize(input)
        if (!FORMAT.matches(address)) return Result.WrongFormat
        if (!checksumOk(address)) return Result.WrongChecksum
        val network = when (address.substring(0, 2)) {
            "CB" -> Network.MAINNET
            "AB" -> Network.TESTNET
            else -> Network.PRIVATE
        }
        return Result.Valid(address.lowercase(), network)
    }

    internal fun normalize(input: String): String {
        var s = input.trim()
        // payto kennt auch "adresse:betrag" → Doppelpunkt beendet die Adresse
        val prefixed = Regex("^(?:payto://xcb/|corepass:)([^?#/:]+)", RegexOption.IGNORE_CASE).find(s)
        if (prefixed != null) s = prefixed.groupValues[1]
        return s.replace(SPACE, "").uppercase()
    }

    /**
     * Alle gueltigen Core-Adressen in einem beliebigen Text, z. B. einem gescannten QR-Code
     * (nackte Adresse, `corepass:`, `payto://xcb/`, Text mit Leerzeichen-Gruppen). Ohne Duplikate,
     * in Fundreihenfolge. Der Aufrufer entscheidet, was bei 0 oder mehreren Treffern passiert.
     */
    fun findAll(text: String): List<Result.Valid> {
        return CANDIDATE.findAll(text)
            .filterNot { m -> SPACE.containsMatchIn(m.value) && partOfLongerHex(text, m.range) }
            .mapNotNull { parse(it.value) as? Result.Valid }
            .distinctBy { it.address }
            .toList()
    }

    /**
     * Gruppierte Treffer ("CB29 2120 …") koennen der Anfang oder das Ende einer laengeren
     * gruppierten Hex-Kette sein (Hash, Schluessel). Steht direkt davor oder danach eine
     * weitere Gruppe aus 1–4 Hex-Zeichen, ist es keine Adresse. Normale Woerter stoeren nicht.
     */
    private fun partOfLongerHex(text: String, range: IntRange): Boolean {
        val before = text.substring(0, range.first)
        val after = text.substring(range.last + 1)
        return HEX_GROUP_BEFORE.containsMatchIn(before) || HEX_GROUP_AFTER.containsMatchIn(after)
    }

    // Leerraum innerhalb einer Adresse: Leerzeichen, Tab, Zeilenumbruch, geschuetztes Leerzeichen (Webseiten)
    private val SPACE = Regex("[\\s\\u00A0]")

    // Bis zu zwei Leerraum-Zeichen zwischen den Zeichen (4er-Gruppen); die Pruefsumme siebt Zufallstreffer aus
    private val CANDIDATE = Regex("(?<![0-9A-Za-z])(?:cb|ab|ce)(?:[\\s\\u00A0]{0,2}[0-9a-f]){42}(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
    private val HEX_GROUP_BEFORE = Regex("(?:^|[^0-9A-Za-z])[0-9a-f]{1,4}[\\s\\u00A0]{1,2}$", RegexOption.IGNORE_CASE)
    private val HEX_GROUP_AFTER = Regex("^[\\s\\u00A0]{1,2}[0-9a-f]{1,4}(?:[^0-9A-Za-z]|$)", RegexOption.IGNORE_CASE)

    private fun checksumOk(address: String): Boolean {
        val rearranged = address.substring(4) + address.substring(0, 4)
        val digits = buildString {
            for (c in rearranged) {
                if (c.isDigit()) append(c) else append(c - 'A' + 10)
            }
        }
        return BigInteger(digits).mod(NINETY_SEVEN) == BigInteger.ONE
    }
}
