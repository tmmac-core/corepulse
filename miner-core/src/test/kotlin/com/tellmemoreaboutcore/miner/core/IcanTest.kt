// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IcanTest {
    // Oeffentliche TMMAC-Support-Adresse (tellmemoreaboutcore.com)
    private val donation = "cb292120d3abc3b8330a26daf4beb94b1f6a9c7b4963"

    @Test fun `gueltige Mainnet-Adresse`() {
        val r = Ican.parse(donation)
        assertTrue(r is Ican.Result.Valid)
        assertEquals(Ican.Network.MAINNET, (r as Ican.Result.Valid).network)
    }

    @Test fun `Grossschreibung, Leerzeichen und payto werden akzeptiert`() {
        assertTrue(Ican.parse("  CB29 2120 D3AB C3B8 330A 26DA F4BE B94B 1F6A 9C7B 4963 ") is Ican.Result.Valid)
        assertTrue(Ican.parse("payto://xcb/$donation?donate=1&item=Test") is Ican.Result.Valid)
    }

    @Test fun `corepass-Praefix und payto mit Betrag`() {
        assertEquals(donation, (Ican.parse("corepass:$donation") as Ican.Result.Valid).address)
        assertEquals(donation, (Ican.parse("payto://xcb/$donation:12.5") as Ican.Result.Valid).address)
    }

    @Test fun `findAll holt Adressen aus QR-Texten`() {
        val second = "cb270000000000000000000000000000000000000001"
        val testnet = "ab472120d3abc3b8330a26daf4beb94b1f6a9c7b4963"
        assertEquals(listOf(donation), Ican.findAll("payto://xcb/$donation?amount=xcb:1").map { it.address })
        assertEquals(listOf(donation), Ican.findAll("corepass:${donation.uppercase()}").map { it.address })
        assertEquals(listOf(donation), Ican.findAll("Spende an CB29 2120 D3AB C3B8 330A 26DA F4BE B94B 1F6A 9C7B 4963 danke").map { it.address })
        assertEquals(listOf(donation, second), Ican.findAll("$donation\n$second\n$donation").map { it.address })
        assertEquals(Ican.Network.TESTNET, Ican.findAll(testnet).single().network)
        // Falsche Pruefsumme und zu lange Hex-Ketten zaehlen nicht
        assertEquals(emptyList<Ican.Result.Valid>(), Ican.findAll(donation.dropLast(1) + "4"))
        assertEquals(emptyList<Ican.Result.Valid>(), Ican.findAll("0x" + donation + "ff"))
        assertEquals(emptyList<Ican.Result.Valid>(), Ican.findAll("https://example.com"))
    }

    @Test fun `Leerraum-Varianten wie in PDFs und Webseiten`() {
        val grouped = "CB29 2120 D3AB C3B8 330A 26DA F4BE B94B 1F6A 9C7B 4963"
        for (sep in listOf("\t", "\n", "\u00A0", "  ")) {
            val variant = grouped.replace(" ", sep)
            assertEquals(donation, (Ican.parse(variant) as? Ican.Result.Valid)?.address, "parse mit ${sep.map { it.code }}")
            assertEquals(listOf(donation), Ican.findAll(variant).map { it.address }, "findAll mit ${sep.map { it.code }}")
        }
    }

    @Test fun `laengere Hex-Kette ist keine Adresse`() {
        // Gruppiert: die ersten 44 Zeichen haetten eine gueltige Pruefsumme, gehoeren aber zu einer laengeren Kette
        assertEquals(emptyList<Ican.Result.Valid>(), Ican.findAll("CB29 2120 D3AB C3B8 330A 26DA F4BE B94B 1F6A 9C7B 4963 12"))
        assertEquals(emptyList<Ican.Result.Valid>(), Ican.findAll("ab12 CB29 2120 D3AB C3B8 330A 26DA F4BE B94B 1F6A 9C7B 4963"))
        assertEquals(emptyList<Ican.Result.Valid>(), Ican.findAll(donation + "ff"))
        // Normale Woerter davor und danach bleiben erlaubt
        assertEquals(listOf(donation), Ican.findAll("Adresse CB29 2120 D3AB C3B8 330A 26DA F4BE B94B 1F6A 9C7B 4963 danke").map { it.address })
    }

    @Test fun `vertippte Ziffer faellt auf`() {
        assertEquals(Ican.Result.WrongChecksum, Ican.parse(donation.dropLast(1) + "4"))
        assertEquals(Ican.Result.WrongChecksum, Ican.parse(donation.replaceRange(10, 12, "ba")))
    }

    @Test fun `fremde Formate werden abgelehnt`() {
        assertEquals(Ican.Result.WrongFormat, Ican.parse("0x12ab"))
        assertEquals(Ican.Result.WrongFormat, Ican.parse("xy292120d3abc3b8330a26daf4beb94b1f6a9c7b4963"))
        assertEquals(Ican.Result.WrongFormat, Ican.parse(donation + "0"))
    }
}
