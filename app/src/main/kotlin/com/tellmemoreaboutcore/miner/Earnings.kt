// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tellmemoreaboutcore.miner.core.LogEvent
import com.tellmemoreaboutcore.miner.core.LogKey
import com.tellmemoreaboutcore.miner.core.PoolApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Letzter Stand der CTR-API; [failed] = letzter Abruf ging schief (alter Stand bleibt sichtbar). */
internal data class PoolLoad(val snapshot: PoolApi.Snapshot? = null, val failed: Boolean = false)

/**
 * Pool-Daten von der CTR-API (freigegeben, nur dieser Betreiber). Waehrend des Minings alle
 * 10 min, sonst einmal. Ohne Adresse wird nichts geladen — kein Netzverkehr, solange niemand ihn braucht.
 */
@Composable
internal fun rememberPoolLoad(poolHost: String?, address: String?, live: Boolean): PoolLoad {
    // Schluessel ist die API-Region, nicht der volle Host: Wechsel von "kein Pool" zu "de…" laedt nicht doppelt
    val base = PoolApi.baseUrl(poolHost)
    var load by remember { mutableStateOf(PoolLoadCache.get(base, address) ?: PoolLoad()) }
    LaunchedEffect(base, address, live) {
        if (address == null) return@LaunchedEffect
        // Drehen baut die Activity neu: einen frischen Stand nicht sofort erneut abfragen, sondern im Takt bleiben.
        // Start/Stopp (anderes live) laedt wie bisher sofort neu.
        PoolLoadCache.get(base, address, live)?.snapshot?.let { cached ->
            val wait = 10 * 60_000L - (System.currentTimeMillis() - cached.fetchedAtMs)
            if (wait > 0) { if (!live) return@LaunchedEffect; delay(wait) }
        }
        while (true) {
            val result = withContext(Dispatchers.IO) { runCatching { PoolApi.fetch(poolHost, address) } }
            load = result.fold({ PoolLoad(it) }, { load.copy(failed = true) })
            if (result.isSuccess) PoolLoadCache.put(base, address, live, load)
            result.exceptionOrNull()?.let { MiningState.log(LogEvent(LogKey.POOL_STATS_FAILED, listOf(it.message))) }
            if (!live) break
            delay(if (result.isSuccess) 10 * 60_000L else 60_000L)
        }
    }
    return load
}

/** Letzter erfolgreicher Abruf je API-Region und Adresse, nur im Speicher: uebersteht Drehen, nicht den Prozess. */
private object PoolLoadCache {
    private var key: Pair<String, String>? = null
    private var live = false
    private var value: PoolLoad? = null
    @Synchronized fun get(base: String, address: String?): PoolLoad? = value.takeIf { address != null && key == (base to address) }
    /** Nur wenn derselbe Abruf-Modus (Minen oder nicht) den Stand geholt hat. */
    @Synchronized fun get(base: String, address: String, live: Boolean): PoolLoad? = get(base, address)?.takeIf { this.live == live }
    @Synchronized fun put(base: String, address: String, live: Boolean, load: PoolLoad) { key = base to address; this.live = live; value = load }
}

/**
 * XCB mit so vielen Stellen, dass kleine Betraege nicht als 0 erscheinen; ab 1 XCB zwei Nachkommastellen wie bei
 * Geld (Design-Runde 30.10.: "1,234" las sich wie tausend). Trennzeichen nach [locale].
 */
internal fun formatXcb(xcb: Double, locale: Locale): String = when {
    xcb >= 1 -> "%.2f".format(locale, xcb)
    xcb >= 0.01 -> "%.3f".format(locale, xcb)
    else -> "%.4f".format(locale, xcb)
}

private fun formatPoolRate(hs: Double, locale: Locale): String = when {
    hs >= 1e6 -> "%.1f MH/s".format(locale, hs / 1e6)
    hs >= 1e3 -> "%.1f kH/s".format(locale, hs / 1e3)
    else -> "%.0f H/s".format(locale, hs)
}

/**
 * Wo das Guthaben liegt, wenn es auf mehrere Regionen verteilt ist: Jede CTR-Region zahlt fuer sich aus,
 * ein Rest unter der Schwelle wartet dort, bis wieder dort gemint wird. null = nichts Erklaerenswertes.
 */
internal fun regionSplit(snap: PoolApi.Snapshot, t: Texts): String? {
    val open = snap.openRegions
    val parts = open.joinToString(" · ") { r ->
        val a = r.account!!
        "${r.region.uppercase()} ${formatXcb(a.balanceXcb + a.immatureXcb, t.locale)}"
    }
    return when {
        snap.incomplete -> t.get(R.string.region_incomplete)
        // Reste unter der Schwelle zahlt CTR nicht aus, sie warten, bis dort weiter gemint wird
        open.size > 1 -> t.get(R.string.region_split, parts, formatXcb(snap.settings.payoutThresholdXcb, t.locale))
        else -> null
    }
}

/**
 * Kurzfassung von [regionSplit] fuer die eingeklappte Laufansicht: Betraege je Region mit der Schwelle je Region.
 * Fehlt eine Region, steht immer der Hinweis darauf da (sonst waere die Summe still falsch, Skeptiker-Review 17.10.).
 */
internal fun regionShort(snap: PoolApi.Snapshot, t: Texts): String? {
    val open = snap.openRegions
    return when {
        snap.incomplete -> t.get(R.string.region_incomplete)
        open.size > 1 -> t.get(
            R.string.region_short,
            open.joinToString(" · ") { r ->
                val a = r.account!!
                "${r.region.uppercase()} ${formatXcb(a.balanceXcb + a.immatureXcb, t.locale)}"
            },
            formatXcb(snap.settings.payoutThresholdXcb, t.locale),
        )
        else -> null
    }
}

/** "~6 min", "~2 h": grobe Dauer fuer Laien; [approx] = false laesst die Tilde (bzw. 約) weg. */
internal fun roughDuration(seconds: Double, t: Texts, approx: Boolean = true): String {
    val plain = when {
        seconds < 90 -> t.get(R.string.duration_seconds, seconds.roundToInt().coerceAtLeast(1))
        seconds < 90 * 60 -> t.get(R.string.duration_minutes, (seconds / 60).roundToInt())
        else -> t.get(R.string.duration_hours, (seconds / 3600).roundToInt())
    }
    return if (approx) t.get(R.string.duration_approx, plain) else plain
}

/** Wie lange ein Share im Mittel dauert: Share-Difficulty Hashes pro Share ÷ Hashes pro Sekunde. */
internal fun secondsPerShare(shareDifficulty: Double, hashrate: Double): Double? =
    if (shareDifficulty > 0 && hashrate > 0) shareDifficulty / hashrate else null

/** "vor 3 min" fuer den letzten Share. */
internal fun ago(ms: Long, t: Texts): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> t.get(R.string.ago_just_now)
        s < 3600 -> t.get(R.string.ago_minutes, (s / 60).toInt())
        else -> t.get(R.string.ago_hours_minutes, (s / 3600).toInt(), (s / 60 % 60).toInt())
    }
}

/** Akkuverbrauch pro Stunde seit Start/Abziehen; erst nach 10 min, vorher ist die Zahl Rauschen. */
internal fun batteryPerHour(s: MiningState.Snapshot, now: Long): Double? {
    val ref = s.batteryRefPercent ?: return null
    val pct = s.batteryPercent ?: return null
    if (s.charging != false || s.batteryRefAtMs == 0L) return null
    val hours = (now - s.batteryRefAtMs) / 3_600_000.0
    if (hours < 1.0 / 6) return null
    return (ref - pct) / hours
}

/** Datenmenge der App seit Start, z. B. "0,4 MB" / "0.4 MB"; null = vom System nicht gemeldet. */
internal fun dataSinceStart(start: Long, nowBytes: Long, locale: Locale): String? {
    if (start < 0 || nowBytes < 0) return null
    val bytes = (nowBytes - start).coerceAtLeast(0)
    val mb = bytes / 1_000_000.0
    return when {
        bytes < 100_000 -> "${(bytes + 999) / 1000} KB"  // kleine Mengen nicht als "0,0 MB"
        mb < 10 -> "%.1f MB".format(locale, mb)
        else -> "%.0f MB".format(locale, mb)
    }
}

/**
 * Ertrag als Schaetzung: XCB pro Tag bei der aktuellen Leistung (abzueglich Pool-Gebuehr und Support-Anteil),
 * Guthaben im Pool, Auszahlungsregel und Anteil am Pool. Alle Zahlen kommen vom Pool selbst, kein Kurs, kein Euro.
 */
@Composable
internal fun EarningsPanel(load: PoolLoad, hashrate: Double, computing: Boolean, explain: Boolean) {
    val snap = load.snapshot
    val t = rememberTexts()
    val locale = t.locale
    Panel {
        Kick(stringResource(R.string.earnings_kick))
        if (snap == null) {
            Text(
                stringResource(if (load.failed) R.string.earnings_unreachable else R.string.earnings_loading),
                style = Type.body(14.sp),
            )
            return@Panel
        }
        val perDay = if (computing) PoolApi.xcbPerDay(hashrate, snap.network, snap.settings.feePercent) * SUPPORT_FACTOR else null
        val perDayA11y = perDay?.let { stringResource(R.string.earnings_per_day_a11y, formatXcb(it, locale)) }
            ?: stringResource(R.string.earnings_no_estimate_a11y)
        // FlowRow: bei grosser Schrift rutscht die Einheit unter die Zahl, statt Zeichen fuer Zeichen umzubrechen
        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                perDay?.let { "≈ " + formatXcb(it, locale) } ?: "–",
                style = Type.display(48.sp).copy(lineHeight = 0.92.em), maxLines = 1,
                autoSize = TextAutoSize.StepBased(maxFontSize = 48.sp),
                modifier = Modifier.semantics { contentDescription = perDayA11y },
            )
            Text(stringResource(R.string.earnings_per_day_unit), style = Type.body(14.sp), modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            stringResource(R.string.earnings_basis_with_support, snap.settings.feePercent),
            style = Type.body(13.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val account = snap.account
            Figure(
                stringResource(R.string.earnings_in_pool), account?.let { formatXcb(it.balanceXcb, locale) } ?: "–",
                when {
                    account == null -> stringResource(R.string.earnings_unavailable)
                    account.immatureXcb > 0 -> stringResource(R.string.earnings_unconfirmed, formatXcb(account.immatureXcb, locale))
                    else -> stringResource(R.string.earnings_not_paid_yet)
                },
                Modifier.weight(1f),
            )
            Figure(
                stringResource(R.string.earnings_payout),
                stringResource(R.string.earnings_payout_from, formatXcb(snap.settings.payoutThresholdXcb, locale)),
                snap.settings.payoutIntervalMin?.let {
                    stringResource(R.string.earnings_payout_interval, roughDuration(it * 60.0, t, approx = false))
                } ?: "XCB",
                Modifier.weight(1f),
            )
        }
        (if (explain) regionSplit(snap, t) else regionShort(snap, t))?.let { Text(it, style = Type.body(13.sp)) }
        if (!explain) return@Panel
        if (computing && snap.network.poolHashrate > 0) {
            val share = hashrate / snap.network.poolHashrate * 100
            Text(
                stringResource(
                    R.string.earnings_pool_share,
                    formatRate(hashrate, locale), formatPoolRate(snap.network.poolHashrate, locale), share,
                ),
                style = Type.data(13.sp, Brand.inkMuted),
            )
        }
        Text(stringResource(R.string.earnings_pplns_explainer), style = Type.body(13.sp))
    }
}

/**
 * Quittung nach dem Stopp (Design-Runde 30.10.): Kennzahlen statt eines Satzes. "Dein Anteil" ist rechnerisch:
 * gerechnete Hashes ÷ Netz-Difficulty × Blockbelohnung, abzueglich Pool-Gebuehr und Support-Anteil — so viel
 * entspricht der Lauf im Mittel, ausgezahlt wird nach den Bloecken des Pools (PPLNS), nicht garantiert. [footer]
 * nimmt den Link zur Pool-Seite auf.
 */
@Composable
internal fun Receipt(summary: RunSummary, load: PoolLoad, footer: @Composable () -> Unit) {
    val t = rememberTexts()
    val locale = t.locale
    val snap = load.snapshot
    val estimate = snap?.takeIf { summary.hashes > 0 }?.let {
        // xcbPerDay ist linear in der Hashrate: H Hashes an einem Tag = Rate H / 86.400 s
        PoolApi.xcbPerDay(summary.hashes / 86_400.0, it.network, it.settings.feePercent) * SUPPORT_FACTOR
    }
    Panel(spacing = 14.dp) {
        Kick(stringResource(R.string.receipt_kick))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Figure(stringResource(R.string.receipt_duration), summary.duration(t), modifier = Modifier.weight(1f))
            Figure(stringResource(R.string.figure_shares), "${summary.accepted}", modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Figure(
                stringResource(R.string.receipt_hashes),
                if (summary.hashes > 0) NumberFormat.getIntegerInstance(locale).format(summary.hashes) else "–",
                modifier = Modifier.weight(1f),
            )
            Figure(
                stringResource(R.string.receipt_max_temp), summary.maxTempC?.let { stringResource(R.string.value_celsius, it) } ?: "–",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Figure(
                stringResource(R.string.receipt_estimate),
                when {
                    estimate == null -> "–"
                    estimate < 0.0001 -> "< " + formatXcb(0.0001, locale)
                    else -> "≈ " + formatXcb(estimate, locale)
                },
                stringResource(R.string.receipt_estimate_note), Modifier.weight(1f),
            )
            val account = snap?.account
            Figure(
                stringResource(R.string.earnings_in_pool), account?.let { formatXcb(it.balanceXcb, locale) } ?: "–",
                when {
                    snap == null -> null
                    account == null -> stringResource(R.string.earnings_unavailable)
                    account.immatureXcb > 0 -> stringResource(R.string.earnings_unconfirmed, formatXcb(account.immatureXcb, locale))
                    else -> stringResource(R.string.earnings_not_paid_yet)
                },
                Modifier.weight(1f),
            )
        }
        snap?.account?.let { account ->
            Text(
                stringResource(
                    R.string.balance_payout_line,
                    formatXcb(snap.settings.payoutThresholdXcb, locale), formatXcb(account.paidXcb, locale),
                ),
                style = Type.body(13.sp),
            )
        }
        snap?.let { regionSplit(it, t) }?.let { Text(it, style = Type.body(13.sp)) }
        footer()
    }
}

/** Anteil, der beim Nutzer bleibt: 0,42 % Support-Anteil fuer TMMAC gehen ab (MiningService.SUPPORT_PERCENT). */
internal const val SUPPORT_FACTOR = 1 - 0.0042
