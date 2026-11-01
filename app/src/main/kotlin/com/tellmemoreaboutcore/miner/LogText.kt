// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import androidx.annotation.StringRes
import com.tellmemoreaboutcore.miner.core.LogEvent
import com.tellmemoreaboutcore.miner.core.LogKey

/**
 * Text, der erst bei der Anzeige in der App-Sprache entsteht (Hinweise nach einem Stopp). Gespeichert
 * wird nur Ressource + Werte, damit ein Sprachwechsel auch schon angezeigte Hinweise mitnimmt.
 */
data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    internal fun resolve(t: Texts): String = t.get(id, *args.toTypedArray())
}

/**
 * Bilanz des letzten Laufs als Daten (Quittung nach dem Stopp). [text] ist die Kurzfassung in einem Satz
 * ("Gestoppt nach 12 min · 3 Shares angenommen · max. 38 °C") fuer TalkBack.
 */
data class RunSummary(val minutes: Long, val accepted: Int, val maxTempC: Float?, val hashes: Long = 0) {
    internal fun text(t: Texts): String {
        val shares = t.plural(R.plurals.summary_shares, accepted, accepted)
        val duration = duration(t)
        return maxTempC?.let { t.get(R.string.summary_stopped_with_temp, duration, shares, t.get(R.string.summary_max_temp, it)) }
            ?: t.get(R.string.summary_stopped, duration, shares)
    }

    /** "unter 1 min", "12 min", "2 h 05 min". */
    internal fun duration(t: Texts): String = when {
        minutes < 1 -> t.get(R.string.summary_under_minute)
        minutes < 60 -> t.get(R.string.duration_minutes, minutes.toInt())
        else -> t.get(R.string.duration_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}

/** Ein Eintrag im In-App-Log: Zeit + Ereignis; Text erst bei der Anzeige ([text]). */
data class LogEntry(val atMs: Long, val event: LogEvent) {
    internal fun text(t: Texts): String = "%tT %s".format(atMs, event.render(t))
}

/**
 * Log-Zeile in der App-Sprache, Zahlen im Format der App-Locale (Resources.getString formatiert mit ihr).
 * Fehlende Werte (null) erscheinen leer statt als "null"; [UiText] wird in derselben Sprache aufgeloest.
 */
internal fun LogEvent.render(t: Texts): String {
    val values = args.map { a ->
        when (a) {
            null -> ""
            is UiText -> a.resolve(t)
            else -> a
        }
    }
    return t.get(key.res, *values.toTypedArray()).trim()
}

/** Je Schluessel eine Ressource; `when` ohne else: ein neuer Schluessel ohne Text kompiliert nicht. */
@get:StringRes
private val LogKey.res: Int get() = when (this) {
    LogKey.SHARE_DROPPED_NO_POOL -> R.string.log_share_dropped_no_pool
    LogKey.SERVER_CHOICE -> R.string.log_server_choice
    LogKey.SERVER_CHOICE_HOME -> R.string.log_server_choice_home
    LogKey.BACK_TO_HOME -> R.string.log_back_to_home
    LogKey.SHARES_UNANSWERED -> R.string.log_shares_unanswered
    LogKey.UNREADABLE_POOL_MESSAGE -> R.string.log_unreadable_pool_message
    LogKey.POOL_ERROR -> R.string.log_pool_error
    LogKey.JOB_UNREADABLE -> R.string.log_job_unreadable
    LogKey.SUPPORT_FAILED -> R.string.log_support_failed
    LogKey.SUPPORT_LOGIN_REJECTED -> R.string.log_support_login_rejected
    LogKey.SUPPORT_SKIPPED -> R.string.log_support_skipped
    LogKey.SUPPORT_RUNNING -> R.string.log_support_running
    LogKey.SUPPORT_DONE -> R.string.log_support_done
    LogKey.RANDOMY_READY -> R.string.log_randomy_ready
    LogKey.SELFTEST_RETRY_WITHOUT_JIT -> R.string.log_selftest_retry_without_jit
    LogKey.STOP_THREAD_STILL_RUNNING -> R.string.log_stop_thread_still_running
    LogKey.THREAD_NO_VM -> R.string.log_thread_no_vm
    LogKey.THREAD_SELFTEST_FAILED -> R.string.log_thread_selftest_failed
    LogKey.THREAD_ENDED -> R.string.log_thread_ended
    LogKey.TEST_NO_HEAT_PAUSE -> R.string.log_test_no_heat_pause
    LogKey.ERROR -> R.string.log_error
    LogKey.STARTING -> R.string.log_starting
    LogKey.SHARE_FOUND -> R.string.log_share_found
    LogKey.START_FAILED -> R.string.log_start_failed
    LogKey.SUPPORT_SHARE_ACCEPTED -> R.string.log_support_share_accepted
    LogKey.SUPPORT_SHARE_REJECTED -> R.string.log_support_share_rejected
    LogKey.SUPPORT_ON -> R.string.log_support_on
    LogKey.CONNECTED -> R.string.log_connected
    LogKey.DISCONNECTED -> R.string.log_disconnected
    LogKey.LOGIN_REJECTED -> R.string.log_login_rejected
    LogKey.SHARE_ACCEPTED -> R.string.log_share_accepted
    LogKey.SHARE_REJECTED -> R.string.log_share_rejected
    LogKey.MEASUREMENT -> R.string.log_measurement
    LogKey.SUPPORT_BALANCE -> R.string.log_support_balance
    LogKey.HEAT_PAUSE -> R.string.log_heat_pause
    LogKey.HEAT_RESUME -> R.string.log_heat_resume
    LogKey.STOPPED_WITH_NOTICE -> R.string.log_stopped_with_notice
    LogKey.STOPPED -> R.string.log_stopped
    LogKey.POOL_STATS_FAILED -> R.string.log_pool_stats_failed
}
