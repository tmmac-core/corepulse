// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tellmemoreaboutcore.miner.core.LogEvent
import com.tellmemoreaboutcore.miner.core.LogKey
import com.tellmemoreaboutcore.miner.core.PoolApi
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
internal fun batteryDrainPerHour(refPercent: Int?, percent: Int?, charging: Boolean?, refAtMs: Long, now: Long): Double? {
    val ref = refPercent ?: return null
    val pct = percent ?: return null
    if (charging != false || refAtMs == 0L) return null
    val hours = (now - refAtMs) / 3_600_000.0
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
 * Guthaben und Ertrag beim Pool. Das Gemessene steht gross (Guthaben), das Geschaetzte als Kennzahl daneben und ist
 * als Schaetzung beschriftet: der Pool teilt nach Funden (PPLNS), der echte Ertrag schwankt und ist nicht garantiert.
 * Alle Zahlen kommen vom Pool selbst, kein Kurs, kein Euro.
 * Waehrend des Laufs ([receiptHashes] = null): "XCB pro Tag" bei der aktuellen Leistung ([hashrate], null = rechnet
 * gerade nicht). Nach dem Lauf: "Dein Anteil" = gerechnete Hashes ÷ Netz-Difficulty × Blockbelohnung. Beides
 * abzueglich Pool-Gebuehr und Support-Anteil.
 */
@Composable
internal fun PoolPanel(load: PoolLoad, hashrate: Double?, explain: Boolean, receiptHashes: Long? = null) {
    val snap = load.snapshot
    val t = rememberTexts()
    val locale = t.locale
    Panel(spacing = 12.dp) {
        Kick(stringResource(R.string.earnings_in_pool), color = Brand.inkQuiet)
        if (snap == null) {
            Text(stringResource(if (load.failed) R.string.earnings_unreachable else R.string.earnings_loading), style = Type.body(Scale.body))
            return@Panel
        }
        val account = snap.account
        val balance = account?.let { formatXcb(it.balanceXcb, locale) } ?: "–"
        val note = when {
            account == null -> stringResource(R.string.earnings_unavailable)
            account.immatureXcb > 0 -> stringResource(R.string.earnings_balance_note) + " · " +
                stringResource(R.string.earnings_unconfirmed, formatXcb(account.immatureXcb, locale))
            else -> stringResource(R.string.earnings_balance_note)
        }
        Column(Modifier.clearAndSetSemantics { contentDescription = "$balance XCB, $note" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // FlowRow: bei grosser Schrift rutscht die Einheit unter die Zahl, statt Zeichen fuer Zeichen umzubrechen
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    balance, style = Type.display(Scale.title).copy(lineHeight = 0.92.em), maxLines = 1,
                    autoSize = TextAutoSize.StepBased(maxFontSize = Scale.title),
                )
                Text("XCB", style = Type.data(Scale.small, Brand.inkQuiet).copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 5.dp))
            }
            Text(note, style = Type.body(Scale.small))
        }
        val paid: @Composable (Modifier) -> Unit = { m ->
            Figure(stringResource(R.string.earnings_paid), account?.let { formatXcb(it.paidXcb, locale) } ?: "–", note = stringResource(R.string.earnings_paid_note), modifier = m)
        }
        // Im Lauf steht "XCB pro Tag" schon unter der grossen Zahl, hier nicht noch einmal (Vorgabe: nicht ueberladen)
        if (receiptHashes == null) FigureGrid(listOf(paid)) else FigureGrid(listOf(
            { m ->
                // xcbPerDay ist linear in der Hashrate: H Hashes an einem Tag = Rate H / 86.400 s
                val estimate = receiptHashes.takeIf { it > 0 }?.let {
                    PoolApi.xcbPerDay(it / 86_400.0, snap.network, snap.settings.feePercent) * SUPPORT_FACTOR
                }
                Figure(
                    stringResource(R.string.receipt_estimate),
                    when {
                        estimate == null -> "–"
                        estimate < 0.0001 -> "< " + formatXcb(0.0001, locale)
                        else -> t.get(R.string.duration_approx, formatXcb(estimate, locale))
                    },
                    stringResource(R.string.receipt_estimate_note), m,
                )
            },
            paid,
        ))
        val threshold = formatXcb(snap.settings.payoutThresholdXcb, locale)
        Text(
            snap.settings.payoutIntervalMin?.let { stringResource(R.string.earnings_payout_rule, threshold, roughDuration(it * 60.0, t, approx = false).replace(' ', '\u00A0')) }
                ?: stringResource(R.string.earnings_payout_rule_plain, threshold),
            style = Type.body(Scale.small),
        )
        // Zugeklappt nur Zahlen und die eine Regel; Herleitung und Regionen gehoeren zur Erklaerung (Design-Audit 15.11.)
        if (!explain) {
            // Fehlt eine Region, steht der Hinweis immer da: sonst waere die Summe still falsch (Skeptiker-Review 17.10.)
            if (snap.incomplete) Text(stringResource(R.string.region_incomplete), style = Type.body(Scale.small))
            return@Panel
        }
        if (receiptHashes == null) Text(stringResource(R.string.earnings_basis_with_support, snap.settings.feePercent), style = Type.body(Scale.small))
        regionSplit(snap, t)?.let { Text(it, style = Type.body(Scale.small)) }
        if (receiptHashes != null) return@Panel
        if (hashrate != null && snap.network.poolHashrate > 0) {
            val share = hashrate / snap.network.poolHashrate * 100
            Text(
                stringResource(R.string.earnings_pool_share, formatRate(hashrate, locale), formatPoolRate(snap.network.poolHashrate, locale), share),
                style = Type.data(Scale.small, Brand.inkMuted),
            )
        }
        Text(stringResource(R.string.earnings_pplns_explainer), style = Type.body(Scale.small))
    }
}

/**
 * Quittung nach dem Stopp, Kopf: wann der Lauf begann (Eyebrow) und wie lange er dauerte (gross). Die Dauer ist
 * gemessen, darum ist sie die grosse Zahl; der Anteil ist geschaetzt und steht als Kennzahl im Pool-Panel.
 */
@Composable
internal fun ReceiptHeader(summary: RunSummary, modifier: Modifier = Modifier) {
    val t = rememberTexts()
    val started = summary.startedAtMs.takeIf { it > 0 }?.let {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(t.locale, "dMMMHm")
        java.text.SimpleDateFormat(pattern, t.locale).format(java.util.Date(it))
    }
    val kick = listOfNotNull(stringResource(R.string.receipt_kick), started).joinToString(" · ")
    val duration = summary.duration(t)
    Column(
        modifier.clearAndSetSemantics { contentDescription = "$kick, ${summary.text(t)}"; heading() },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(kick.caps(), style = Type.kick.copy(color = Brand.inkQuiet))
        Text(
            duration, style = Type.display(Scale.big).copy(lineHeight = 0.95.em), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = Scale.figure, maxFontSize = Scale.big),
        )
    }
}

/** Quittung, Kennzahlen: Funde, mittlere Rechenleistung, hoechste Waerme, Akku seit dem Start. */
@Composable
internal fun ReceiptFigures(summary: RunSummary) {
    val t = rememberTexts()
    val locale = t.locale
    val figures = mutableListOf<@Composable (Modifier) -> Unit>(
        { m ->
            Figure(
                stringResource(R.string.figure_shares), "${summary.accepted}",
                if (summary.rejected > 0) pluralStringResource(R.plurals.share_note_rejected, summary.rejected, summary.rejected) else null, m,
            )
        },
        { m ->
            val rate = summary.averageRate
            Figure(
                stringResource(R.string.receipt_avg_rate), rate?.let { formatRate(it, locale) + " H/s" } ?: "–",
                if (summary.hashes > 0) stringResource(R.string.receipt_hashes_note, NumberFormat.getIntegerInstance(locale).format(summary.hashes)) else null,
                m, spoken = rate?.let { stringResource(R.string.running_rate_a11y, formatRate(it, locale)) },
            )
        },
        { m -> HeatFigure(stringResource(R.string.receipt_peak_heat), summary.maxTempC, m) },
    )
    // Ohne Messwert keine Kachel: ein Strich sagt nichts
    summary.batteryChange?.let { change ->
        figures += { m ->
            // Bei 0 ein Wort: "±0 %" las sich wie ein Rechenausdruck
            if (change == 0) Figure(
                stringResource(R.string.figure_battery), stringResource(R.string.receipt_battery_same),
                stringResource(R.string.receipt_battery_note), m, word = true,
            ) else Figure(
                stringResource(R.string.figure_battery), (if (change > 0) "+" else "−") + stringResource(R.string.value_percent, kotlin.math.abs(change)),
                stringResource(R.string.receipt_battery_note), m,
            )
        }
    }
    Panel { FigureGrid(figures) }
}

/** Anteil, der beim Nutzer bleibt: 0,42 % Support-Anteil fuer TMMAC gehen ab (MiningService.SUPPORT_PERCENT). */
internal const val SUPPORT_FACTOR = 1 - 0.0042
