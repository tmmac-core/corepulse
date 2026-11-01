// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/**
 * Texte und Zahlenformat fuer Hilfsfunktionen ausserhalb von Composables (Dienst, Formatierer).
 * Als Schnittstelle, damit die Formatierer ohne Android testbar bleiben: ein Test reicht eine Attrappe
 * mit festen Texten und Locale herein.
 */
internal interface Texts {
    /** Sprache fuer Zahlen (Dezimal- und Tausendertrennzeichen) — folgt der App-Sprache, nicht hart kodiert. */
    val locale: Locale
    fun get(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

/** Texte aus den App-Ressourcen; Locale aus der aktuellen Konfiguration (folgt Handy- bzw. App-Sprache). */
internal fun Resources.texts(): Texts = object : Texts {
    override val locale: Locale get() = configuration.locales[0]
    override fun get(id: Int, vararg args: Any): String = if (args.isEmpty()) getString(id) else getString(id, *args)
    override fun plural(id: Int, count: Int, vararg args: Any): String = getQuantityString(id, count, *args)
}

/** [Texts] im Composable; neu bei Sprachwechsel (Konfigurationsaenderung). */
@Composable
internal fun rememberTexts(): Texts {
    val config = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(config, resources) { resources.texts() }
}
