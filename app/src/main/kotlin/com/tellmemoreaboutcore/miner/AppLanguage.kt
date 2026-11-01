// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Sprache der App von Hand waehlen, Standard bleibt "wie das Handy".
 *
 * Ab Android 13 uebernimmt das System die Wahl (LocaleManager): gilt fuer Activity, Dienst und Benachrichtigung
 * und steht auch unter Einstellungen → Apps → CorePulse → Sprache (generateLocaleConfig in app/build.gradle.kts). Darunter merkt sich die
 * App die Wahl selbst (nur lokal, Prefs ohne Backup) und setzt sie in [wrap] fuer Activity und Dienst ein.
 */
internal object AppLanguage {
    /** Sprachen der App (wie localeFilters in app/build.gradle.kts). */
    val TAGS = listOf("de", "en", "ja", "nl", "ro")
    private const val PREF = "language"

    /** Gewaehlte Sprache, "" = wie das Handy. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }
                ?.get(0)?.language?.takeIf { it in TAGS } ?: ""
        } else {
            prefs(context).getString(PREF, "")?.takeIf { it in TAGS } ?: ""
        }

    /** Setzt die Sprache ("" = wie das Handy) und baut die Activity in der neuen Sprache neu auf. */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Das System baut die Activity selbst neu auf
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString(PREF, tag).apply()
            activity.recreate()
        }
    }

    /**
     * Bis Android 12: Kontext in der gewaehlten Sprache fuer attachBaseContext (Activity und Dienst). Ein schon
     * laufender Dienst behaelt bis zum naechsten Start seine Sprache (Benachrichtigung), bewusst so gelassen.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString(PREF, "")?.takeIf { it in TAGS } ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }
        return base.createConfigurationContext(config)
    }

    /**
     * Sprache, in der die App ohne eigene Wahl erscheint: die Handysprache, wenn die App sie kann, sonst Englisch
     * (Rueckfall values/).
     */
    fun systemTag(): String = Resources.getSystem().configuration.locales[0].language.takeIf { it in TAGS } ?: "en"

    /** Eigenname der Sprache ("Deutsch", "日本語"), wie in den Android-Einstellungen; in jeder App-Sprache gleich. */
    fun nativeName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("miner", Context.MODE_PRIVATE)
}
