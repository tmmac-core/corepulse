// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tellmemoreaboutcore.miner.core.PoolApi
import kotlinx.coroutines.delay

/**
 * Laufansicht nach dem Abgleich mit der iPhone-App (15.11.2026): Kopfzeile mit Puls und Laufzeit, eine grosse Zahl,
 * ein Satz, Verlauf, vier Kennzahlen, Guthaben. Jeder Teil bekommt nur die Werte, die er zeigt: der Dienst meldet
 * alle 10 s und bei jedem Log-Eintrag einen neuen Zustand, die Laufzeit zaehlt jede Sekunde. Neu gezeichnet wird so
 * nur der Teil, dessen Werte sich geaendert haben (jedes Neuzeichnen kostet Waerme und damit Rechenleistung).
 */
@Composable
internal fun RunningContent(
    s: MiningState.Snapshot, address: String?, threads: Int, cores: Int, worker: String, poolLoad: PoolLoad,
    explain: Boolean, onExplain: () -> Unit, openPool: () -> Unit, onSettings: () -> Unit,
) {
    val pool = s.pool.takeIf { s.phase == MiningState.Phase.MINING }
    val computing = pool != null && !s.cooling
    val server = pool?.substringBefore('.')?.uppercase()

    // Leichter Impuls bei jedem angenommenen Fund: das Handy meldet sich, ohne dass man hinsieht
    val view = LocalView.current
    LaunchedEffect(s.accepted) {
        if (s.accepted > 0) view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
    val t = rememberTexts()

    // Startschritte, bis der erste Job vom Pool da ist (Design-Runde 30.10.: zeigen, was wirklich passiert)
    // Bis zur ersten Messung (10 s nach der ersten Aufgabe) bleiben die Schritte stehen: eine Laufansicht ohne Zahl
    // saehe wie ein Defekt aus
    val starting = (s.events == 0 || s.measurements == 0) && !s.cooling &&
        (s.phase == MiningState.Phase.PREPARING || s.phase == MiningState.Phase.MINING)
    val temp = s.batteryTempC ?: 0f
    val kick = when {
        starting -> stringResource(R.string.running_kick_preparing)
        s.phase == MiningState.Phase.STOPPING -> stringResource(R.string.running_kick_stopping)
        s.cooling -> stringResource(R.string.running_kick_cooling)
        s.supportActive -> stringResource(R.string.running_kick_support)
        pool == null -> stringResource(R.string.running_kick_waiting)
        else -> stringResource(R.string.running_kick_mining, server ?: "")
    }
    RunHeader(kick, beat = s.accepted, tick = s.measurements.takeIf { computing }, startedAtMs = s.startedAtMs, onSettings = onSettings)

    if (starting) {
        Preparing(phase = s.phase, prepStep = s.prepStep, hasPool = s.pool != null)
        return
    }

    when {
        // Ohne Rechnen keine Zahl: eine stehengebliebene Zahl wuerde Leistung vortaeuschen
        s.phase == MiningState.Phase.STOPPING -> NoticeBox(NoticeKind.INFO, stringResource(R.string.status_stopping))
        s.cooling -> NoticeBox(NoticeKind.PAUSE, stringResource(R.string.running_kick_cooling), stringResource(R.string.running_cooling))
        pool == null -> NoticeBox(NoticeKind.WARN, stringResource(R.string.running_kick_waiting), stringResource(R.string.running_waiting))
        else -> {
            Hero(rate = s.hashrate, first = s.measurements == 1, measured = s.measurements > 0)
            val (glyph, tint, sentence) = when {
                s.supportActive -> Triple(Glyph.INFO, Brand.inkMuted, stringResource(R.string.running_support))
                temp >= 45f -> Triple(Glyph.THERMO, Brand.gold, stringResource(R.string.running_hot, temp))
                temp >= 40f -> Triple(Glyph.THERMO, Brand.inkMuted, stringResource(R.string.running_warm, temp))
                address != null -> Triple(Glyph.CHECK, Brand.inkMuted, stringResource(R.string.running_for_address, shortAddress(address)))
                else -> Triple(Glyph.CHECK, Brand.inkMuted, stringResource(R.string.running_ok))
            }
            // Was die Zahl bedeutet: grob der Ertrag pro Tag bei dieser Rechenleistung. Beschriftet als Schaetzung;
            // ohne Pool-Statistik (kein Netz) entfaellt die Zeile, statt einen Strich zu zeigen
            val perDay = poolLoad.snapshot?.takeIf { computing && s.measurements > 0 }?.let {
                PoolApi.xcbPerDay(s.hashrate, it.network, it.settings.feePercent) * SUPPORT_FACTOR
            }?.takeIf { it >= 0.0001 } // darunter stuende "0,0000": dann lieber die Kern-Zeile
            // Genau zwei Zeilen (Vorgabe: nicht ueberladen): der Ertrag ersetzt die Kern-Zeile, die schon auf der
            // Startseite und in den Einstellungen steht; ohne Pool-Statistik bleibt die Kern-Zeile
            StatusLine(
                glyph, tint, sentence,
                perDay?.let { stringResource(R.string.running_per_day, formatXcb(it, t.locale)) }
                    ?: pluralStringResource(R.plurals.power_option_cores, cores, threads, cores),
            )
        }
    }

    if (s.history.isNotEmpty() || computing) RateChartPanel(s.history, s.hashrate, t)

    LiveFigures(
        accepted = s.accepted, rejected = s.rejected, lastShareAtMs = s.lastShareAtMs,
        perShare = secondsPerShare(s.shareDifficulty, s.hashrate).takeIf { computing },
        tempC = s.batteryTempC, batteryPercent = s.batteryPercent, charging = s.charging,
        batteryRefPercent = s.batteryRefPercent, batteryRefAtMs = s.batteryRefAtMs, t = t,
    )

    // Immer sichtbar: Guthaben, Schaetzung, Auszahlungsregel. Eingeklappt: Erklaertexte
    PoolPanel(poolLoad, hashrate = s.hashrate.takeIf { computing }, explain = explain)
    Panel(spacing = 0.dp) {
        if (address != null) {
            LinkRow(stringResource(R.string.setup_pool_link), mark = "↗", onClick = openPool)
            Hairline()
        }
        LinkRow(stringResource(R.string.how_it_works_show), mark = if (explain) "−" else "+", expanded = explain, onClick = onExplain)
        if (explain) Explainer(s, worker, t)
    }
}

/** "XCB-MINING · SERVER DE" mit Fund-Puls links, Laufzeit und Zahnrad rechts: eine Zeile statt zweier. */
@Composable
private fun RunHeader(kick: String, beat: Int, tick: Int?, startedAtMs: Long, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Live-Region auf der Phase (Minen, Abkuehlen, Support …), nicht auf dem Satz mit der Temperatur: sonst sagt
        // TalkBack bei warmem Handy alle 10 s den ganzen Satz an (Skeptiker 30.10.)
        Kick(kick, Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, beat = beat, tick = tick)
        if (startedAtMs > 0) Elapsed(startedAtMs)
        IconButton(Glyph.GEAR, stringResource(R.string.settings_title), onClick = onSettings)
    }
}

/** Laufzeit; zaehlt selbst, damit nicht die ganze Seite jede Sekunde neu gezeichnet wird. */
@Composable
private fun Elapsed(startedAtMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val text = elapsedShort(now - startedAtMs)
    val a11y = stringResource(R.string.running_elapsed_a11y, text)
    Text(text, style = Type.data(Scale.small, Brand.inkMuted), modifier = Modifier.semantics { contentDescription = a11y })
}

/** Wanduhr im 30-s-Takt fuer "letzter vor 3 min" und "Akku pro Stunde": genau genug, ohne Sekundentakt. */
@Composable
private fun rememberSlowClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

/**
 * Die eine grosse Zahl, links, mit der Einheit leise auf der Grundlinie. Die erste Messung zaehlt von 0 hoch
 * (900 ms): der Moment, in dem das Handy loslegt. Danach gleitet jede Messung ein (180 ms, --motion-base).
 * autoSize: bei grosser Schrift kleiner statt abgeschnitten, sonst stuende eine falsche Zahl da.
 */
@Composable
private fun Hero(rate: Double, first: Boolean, measured: Boolean) {
    val locale = rememberTexts().locale
    val still = reduceMotion()
    val countUp = remember { Animatable(0f) }
    var counted by remember { mutableStateOf(false) }
    val firstRate = first && !still
    LaunchedEffect(firstRate) {
        if (!firstRate) return@LaunchedEffect
        countUp.snapTo(0f)
        countUp.animateTo(rate.toFloat(), tween(900, easing = MotionEase))
        counted = true
    }
    val shown = if (measured) formatRate(rate, locale) else "–"
    val a11y = if (measured) stringResource(R.string.running_rate_a11y, shown) else stringResource(R.string.running_not_computing_a11y)
    val style = Type.display(Scale.hero).copy(lineHeight = 0.92.em)
    // Ueberzeile: ohne sie ist "1643 H/s" fuer Laien eine Zahl ohne Bedeutung (Skeptiker 16.11.). Dasselbe Wort wie
    // im Zustimmungstext ("deine Rechenleistung"), kein neues.
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
        stringResource(R.string.running_rate_unit).caps(), style = Type.kick.copy(color = Brand.inkQuiet),
        modifier = Modifier.clearAndSetSemantics {},
    )
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = a11y },
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (firstRate && !counted) Text(
            formatRate(countUp.value.toDouble(), locale), style = style, maxLines = 1,
            autoSize = TextAutoSize.StepBased(maxFontSize = Scale.hero), modifier = Modifier.weight(1f, fill = false),
        ) else AnimatedContent(
            targetState = shown,
            transitionSpec = {
                if (still) EnterTransition.None togetherWith ExitTransition.None
                else (slideInVertically(tween(180, easing = MotionEase)) { it / 3 } + fadeIn(tween(180))) togetherWith fadeOut(tween(120))
            },
            label = "hashrate", modifier = Modifier.weight(1f, fill = false),
        ) { value -> Text(value, style = style, maxLines = 1, autoSize = TextAutoSize.StepBased(maxFontSize = Scale.hero)) }
        Text("H/s", style = Type.data(Scale.lead, Brand.inkQuiet).copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 10.dp))
    }
    }
}

/** Ein ruhiger Satz, was gerade passiert, darunter leise die Rechenkerne. Symbol + Wort, nie Farbe allein. */
@Composable
private fun StatusLine(glyph: Glyph, tint: androidx.compose.ui.graphics.Color, sentence: String, detail: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(glyph, tint, Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sentence, style = Type.body(Scale.lead, Brand.ink))
            Text(detail, style = Type.body(Scale.small, Brand.inkMuted))
        }
    }
}

/** Vom Tippen auf "Mining starten" bis zur ersten Aufgabe vom Pool: nur die Schritte, keine leeren Kennzahlen. */
@Composable
private fun Preparing(phase: MiningState.Phase, prepStep: Int, hasPool: Boolean) {
    val steps = listOf(
        stringResource(R.string.start_step_memory), stringResource(R.string.start_step_build),
        stringResource(R.string.start_step_server), stringResource(R.string.start_step_job),
    )
    val step = when {
        phase == MiningState.Phase.PREPARING -> prepStep.coerceIn(0, 1)
        !hasPool -> 2
        else -> 3
    }
    Text(stringResource(R.string.status_preparing), style = Type.display(Scale.title), modifier = Modifier.padding(top = 12.dp).semantics { heading() })
    Text(stringResource(R.string.preparing_hint), style = Type.body(Scale.lead))
    Panel { StartSteps(steps, step, Modifier.padding(vertical = 4.dp)) }
}

/** Verlauf im Panel: Eyebrow, Kurve mit Skala, darunter wie weit sie zurueckreicht und "jetzt". */
@Composable
private fun RateChartPanel(history: List<Float>, hashrate: Double, t: Texts) {
    val locale = t.locale
    val span = (history.size - 1).coerceAtLeast(0) * 10
    val a11y = if (history.size >= 2) stringResource(
        R.string.running_curve_a11y,
        formatRate(history.min().toDouble(), locale), formatRate(history.max().toDouble(), locale), formatRate(hashrate, locale),
    ) else stringResource(R.string.running_curve_filling)
    Panel {
        // Einheit ueber der Achse: "2000 / 1000 / 0" allein sagte nicht, was gemessen wird
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kick(stringResource(R.string.chart_kick), Modifier.weight(1f), color = Brand.inkQuiet)
            Text("H/s", style = Type.data(Scale.micro, Brand.inkQuiet), modifier = Modifier.clearAndSetSemantics {})
        }
        RateChart(
            history, locale,
            startLabel = when {
                span < 60 -> stringResource(R.string.running_curve_filling)
                history.size >= SPARK_POINTS -> stringResource(R.string.running_curve_start)
                else -> t.get(R.string.ago_minutes, span / 60)
            },
            nowLabel = stringResource(R.string.running_curve_now), description = a11y,
        )
    }
}

/** Funde, naechster Fund, Waerme, Akku. */
@Composable
private fun LiveFigures(
    accepted: Int, rejected: Int, lastShareAtMs: Long, perShare: Double?, tempC: Float?,
    batteryPercent: Int?, charging: Boolean?, batteryRefPercent: Int?, batteryRefAtMs: Long, t: Texts,
) {
    val now = rememberSlowClock()
    val drain = batteryDrainPerHour(batteryRefPercent, batteryPercent, charging, batteryRefAtMs, now)
    Panel {
        FigureGrid(listOf(
            { m ->
                Figure(
                    stringResource(R.string.figure_shares), "$accepted",
                    // "letzter vor 3 min" gehoert zu den Funden; unter "Naechster Fund" widersprach es der Beschriftung
                    when {
                        rejected > 0 -> pluralStringResource(R.plurals.share_note_rejected, rejected, rejected)
                        lastShareAtMs > 0 -> stringResource(R.string.share_note_last, ago(now - lastShareAtMs, t))
                        else -> null
                    },
                    m,
                )
            },
            { m ->
                Figure(
                    stringResource(R.string.figure_next), perShare?.let { roughDuration(it, t) } ?: "–",
                    perShare?.let { stringResource(R.string.figure_next_note) }, m,
                )
            },
            { m -> HeatFigure(stringResource(R.string.figure_heat), tempC, m) },
            { m ->
                Figure(
                    stringResource(R.string.figure_battery), batteryPercent?.let { stringResource(R.string.value_percent, it) } ?: "–",
                    when {
                        charging == true -> stringResource(R.string.battery_note_charging)
                        drain != null -> stringResource(R.string.battery_note_drain, drain.coerceAtLeast(0.0))
                        else -> null
                    },
                    m,
                )
            },
        ))
    }
}

/**
 * Waerme als Wort (normal, warm, sehr warm), die gemessene Akku-Temperatur leise darunter. Gold erst ab 45 °C:
 * die App pausiert ab 50 °C, darueber altert der Akku schneller (Skeptiker-Review 17.10.).
 */
@Composable
internal fun HeatFigure(label: String, tempC: Float?, modifier: Modifier = Modifier) {
    if (tempC == null) { Figure(label, "–", modifier = modifier); return }
    val word = stringResource(when { tempC < 40f -> R.string.temp_normal; tempC < 45f -> R.string.temp_warm; else -> R.string.temp_hot })
    Figure(
        label, word, stringResource(R.string.figure_heat_note, stringResource(R.string.value_celsius, tempC)), modifier,
        word = true, valueColor = if (tempC >= 45f) Brand.gold else Brand.ink,
    )
}

/** Erklaertexte der Laufansicht (eingeklappt): Rhythmus der Funde, Name im Pool, Datenmenge, Begriffe. */
@Composable
private fun Explainer(s: MiningState.Snapshot, worker: String, t: Texts) {
    val uid = LocalContext.current.applicationInfo.uid
    val perShare = secondsPerShare(s.shareDifficulty, s.hashrate)
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (perShare != null) Text(stringResource(R.string.running_share_interval, roughDuration(perShare, t)), style = Type.body(Scale.small))
        Text(stringResource(R.string.running_worker_name, worker), style = Type.data(Scale.small, Brand.inkMuted))
        dataSinceStart(s.netBytesAtStart, appNetBytes(uid), t.locale)?.let {
            Text(stringResource(R.string.running_data_since_start, it), style = Type.data(Scale.small, Brand.inkMuted))
        }
        Glossary()
    }
}

/** Begriffe, die auf dem Bildschirm stehen, in Laiensprache (Skeptiker-Review 17.10.). */
@Composable
internal fun Glossary() {
    Kick(stringResource(R.string.glossary_kick), Modifier.padding(top = 10.dp))
    Term(stringResource(R.string.running_rate_unit) + " · H/s", stringResource(R.string.glossary_rate))
    Term(stringResource(R.string.figure_shares), stringResource(R.string.running_share_explainer))
    Term(stringResource(R.string.glossary_pool_term), stringResource(R.string.glossary_pool))
    Term(stringResource(R.string.glossary_unconfirmed_term), stringResource(R.string.glossary_unconfirmed))
    Term(stringResource(R.string.glossary_regions_term), stringResource(R.string.glossary_regions))
    Term(stringResource(R.string.running_kick_support), stringResource(R.string.support_text))
}

/** Ein Eintrag der Begriffsliste: Wort hell, Erklaerung gedaempft. */
@Composable
private fun Term(term: String, text: String) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(term, style = Type.body(Scale.small, Brand.ink, FontWeight.SemiBold))
        Text(text, style = Type.body(Scale.small))
    }
}

/** Netzverkehr dieser App (Bytes hin + zurueck), -1 = nicht gemeldet. */
private fun appNetBytes(uid: Int): Long {
    val rx = android.net.TrafficStats.getUidRxBytes(uid)
    val tx = android.net.TrafficStats.getUidTxBytes(uid)
    return if (rx < 0 || tx < 0) -1 else rx + tx
}
