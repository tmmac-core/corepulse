// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ConsentTest {
    @Test
    fun versionVergleich() {
        assertTrue(Consent.needed(0), "nie zugestimmt")
        assertTrue(Consent.needed(Consent.VERSION - 1), "aeltere Textversion")
        assertFalse(Consent.needed(Consent.VERSION), "aktuelle Version")
        assertFalse(Consent.needed(Consent.VERSION + 1), "neuere Version (Downgrade der App)")
    }

    /** Alle 5 Sprachen haben dieselben consent_*-Schluessel, keiner leer. */
    @Test
    fun alleSprachenVollstaendig() {
        val en = consentStrings("values")
        assertTrue(en.size >= 10, "consent_* in values/strings.xml nicht gefunden: ${en.keys}")
        for (dir in LANG_DIRS) {
            val other = consentStrings(dir)
            assertEquals(en.keys, other.keys, "$dir: andere consent_*-Schluessel")
            other.forEach { (k, v) -> assertTrue(v.isNotBlank(), "$dir/$k ist leer") }
        }
    }

    /**
     * Die Texte passen zum Hash der aktuellen [Consent.VERSION]. Wer einen Satz aendert, muss die Version
     * erhoehen (sonst wird niemand erneut gefragt) und den neuen Hash hier eintragen.
     */
    @Test
    fun textAenderungBrauchtNeueVersion() {
        val recorded = mapOf(
            1 to "8f78e17cb7da4de9c13c08e8a5004aa2d553e16b240e8c7bc845912a81f3436b",
            // 28.10.: Leistungsstufe statt "Volllast", "Name im Pool" statt "Geraetename", oeffentliche Pool-Statistik
            2 to "8838473f40e01208ed96cfe38091e2551809264f2cdc534c024cd06fc1da6db9",
            // 30.10.: RO "Toate" statt "Tot" (alle Kerne), JA 温かく statt 熱く ("warm", nicht "heiss")
            3 to "c7997e6051154d16bfd1e53d0f976cae21cddd63e3cc690709045bbca44c43d3",
            // 30.10.: fette Stichworte je Punkt (consent_*_head), NOTICE mit "Core marks" (Core-Zeichen nicht unter der GPL)
            4 to "3d93acabf1605805a0a4d8e6ced83282067376597f8793563364f0a53fe5e087",
        )
        val md = MessageDigest.getInstance("SHA-256")
        for (dir in listOf("values") + LANG_DIRS) {
            consentStrings(dir).toSortedMap().forEach { (k, v) -> md.update("$dir/$k=$v\n".toByteArray()) }
        }
        // Die Lizenz- und Haftungsbedingungen, auf die der Dialog verweist (als licenses/NOTICE.txt in der App)
        md.update(File("../NOTICE.md").readBytes())
        val got = md.digest().joinToString("") { "%02x".format(it) }
        val want = recorded[Consent.VERSION]
        when {
            want.isNullOrEmpty() -> fail<Unit>("Kein Hash fuer Version ${Consent.VERSION} eingetragen; eintragen: $got")
            want != got -> fail<Unit>("Hinweistexte geaendert (Hash $got, eingetragen $want): Consent.VERSION erhoehen und neuen Hash eintragen")
        }
    }

    /** Der Dialog zeigt genau diese Punkte; wer einen streicht, aendert den Inhalt der Zustimmung. */
    @Test
    fun dialogZeigtAllePunkte() {
        val about = File("../app/src/main/kotlin/com/tellmemoreaboutcore/miner/About.kt").readText()
        val shown = Regex("""R\.string\.(consent_[a-z_]+)""").findAll(about).map { it.groupValues[1] }.toSet()
        val notes = setOf("consent_beta", "consent_load", "consent_share", "consent_earn", "consent_pool")
        assertTrue(shown.containsAll(notes), "About.kt zeigt nicht alle Hinweise: fehlt ${notes - shown}")
        assertEquals(consentStrings("values").keys, shown, "About.kt nutzt andere consent_*-Texte als strings.xml")
    }

    /** "kostenlos/free" nur fuer CorePass, freie Software oder freien Speicher – nie fuer die App ohne den Anteil. */
    @Test
    fun keinKostenlosOhneAnteil() {
        val free = Regex("""kostenlos|gratis|gratuit|無料|\bfree\b""", RegexOption.IGNORE_CASE)
        val allowed = Regex("""CorePass|Free software|free (RAM|memory)""")
        for (dir in listOf("values") + LANG_DIRS) {
            File(RES, "$dir/strings.xml").readLines().filter { free.containsMatchIn(it) && !allowed.containsMatchIn(it) }
                .forEach { fail<Unit>("$dir/strings.xml: \"kostenlos\" ohne Anteil: ${it.trim()}") }
        }
    }

    /** Nie "Spende/donation" fuer den festen Anteil, nie "gemined" (in allen sichtbaren Texten). */
    @Test
    fun verboteneWoerter() {
        for (dir in listOf("values") + LANG_DIRS) {
            val text = File(RES, "$dir/strings.xml").readText()
            for (bad in listOf("Spende", "spende", "donation", "Donation", "gemined", "passives Einkommen", "passive income")) {
                assertFalse(text.contains(bad), "$dir/strings.xml enthaelt \"$bad\"")
            }
        }
    }

    private companion object {
        /** Gradle startet die Tests im Modulordner miner-core. */
        val RES = File("../app/src/main/res")
        val LANG_DIRS = listOf("values-de", "values-ja", "values-nl", "values-ro")
        val STRING = Regex("""<string name="(consent_[a-z_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        fun consentStrings(dir: String): Map<String, String> =
            STRING.findAll(File(RES, "$dir/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
