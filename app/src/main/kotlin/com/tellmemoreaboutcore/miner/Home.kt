// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tellmemoreaboutcore.miner.core.CpuShare
import kotlinx.coroutines.delay

/**
 * Startseite und Laufansicht: ein Bildschirm, der Knopf sitzt fest unten. Ohne Lauf: Begruessung (erstes Mal) bzw.
 * Quittung des letzten Laufs, dazu zwei Zeilen, die in die Einrichtung fuehren (Auszahlung, Leistung). Die
 * Einrichtung selbst liegt in den Einstellungen (Zahnrad): wer wiederkommt, will starten (Design-Abgleich 15.11.2026).
 */
@Composable
internal fun HomeScreen(
    s: MiningState.Snapshot, idle: Boolean, mainnet: String?, worker: String, share: CpuShare, threads: Int, cores: Int,
    poolLoad: PoolLoad, explain: Boolean, onExplain: () -> Unit, openPool: () -> Unit,
    onSettings: () -> Unit, onAddress: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onDim: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Edge-to-Edge (Pflicht ab Android 15): Status- und Navigationsleiste selbst freihalten.
    // Inhalt scrollt, der Start-/Stopp-Knopf bleibt unten immer sichtbar.
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        // Beim Wechsel Start ↔ Lauf nach oben: sonst steht nach dem Stopp die Quittung ausserhalb des Bildschirms
        val scroll = rememberScrollState()
        LaunchedEffect(idle) { scroll.scrollTo(0) }
        // Kurz nach "Stoppen" ist "Mining starten" gesperrt: der Knopf erscheint an derselben Stelle, ein doppelter
        // Tipp startete sonst sofort neu (Befund der iPhone-App, 09.11.2026)
        var startLocked by remember { mutableStateOf(false) }
        val ranBefore = remember { booleanArrayOf(false) }
        LaunchedEffect(idle) {
            if (!idle) ranBefore[0] = true
            else if (ranBefore[0]) { startLocked = true; delay(1_000); startLocked = false }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewport = maxHeight
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).heightIn(min = viewport).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
                // Ohne Lauf: Kopf oben, die Zeilen fuer den naechsten Lauf unten ueber dem Knopf (Daumenzone) statt
                // einer leeren unteren Bildschirmhaelfte. Reicht der Inhalt ueber den Bildschirm, scrollt er normal.
                verticalArrangement = if (idle) Arrangement.SpaceBetween else Arrangement.spacedBy(16.dp),
            ) {
                if (idle) IdleContent(s, mainnet, share, threads, cores, poolLoad, openPool, onSettings, onAddress)
                else RunningContent(
                    s, address = mainnet, threads = threads, cores = cores, worker = worker, poolLoad = poolLoad,
                    explain = explain, onExplain = onExplain, openPool = openPool, onSettings = onSettings,
                )
            }
            // Inhalt blendet unten weich aus, statt hart an der Knopfleiste abgeschnitten zu sein. 40 dp: bei 24 dp
            // schmierte der Verlauf Kacheltext an (Befund der iPhone-App)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Brand.canvas))),
            )
        }
        Column(
            Modifier.fillMaxWidth().background(Brand.canvas).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (idle) StartBar(hasAddress = mainnet != null, locked = startLocked, onStart = onStart, onAddress = onAddress)
            else {
                val stopping = s.phase == MiningState.Phase.STOPPING
                val stop: @Composable (Modifier) -> Unit = { m ->
                    SecondaryButton(
                        stringResource(if (stopping) R.string.status_stopping else R.string.stop_button), m,
                        glyph = Glyph.STOP.takeIf { !stopping }, enabled = !stopping, onClick = onStop,
                    )
                }
                // Von Hand abdunkeln; ohne "Bildschirm anlassen" gibt es nichts abzudunkeln
                val dim: @Composable (Modifier) -> Unit = { m ->
                    onDim?.let { QuietAction(stringResource(R.string.button_dim), m, glyph = Glyph.MOON, onClick = it) }
                }
                // Bei grosser Schrift untereinander: nebeneinander brachen die Woerter mitten im Wort um (Test bei 200 %)
                if (LocalDensity.current.fontScale > 1.3f) { stop(Modifier.fillMaxWidth()); dim(Modifier.fillMaxWidth()) }
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { stop(Modifier.weight(1f)); dim(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Der eine gruene Knopf: ohne Adresse fuehrt er zum Eintragen, statt tot zu sein und eine Begruendung zu brauchen.
 * [locked] nur fuer die Sekunde nach "Stoppen".
 */
@Composable
private fun StartBar(hasAddress: Boolean, locked: Boolean, onStart: () -> Unit, onAddress: () -> Unit) {
    if (hasAddress) PrimaryCta(stringResource(R.string.start_button), glyph = Glyph.PLAY, enabled = !locked, onClick = onStart)
    else PrimaryCta(stringResource(R.string.cta_enter_address), onClick = onAddress)
    Text(
        stringResource(R.string.start_footer),
        style = Type.body(Scale.small, Brand.inkQuiet), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IdleContent(
    s: MiningState.Snapshot, mainnet: String?, share: CpuShare, threads: Int, cores: Int, poolLoad: PoolLoad,
    openPool: () -> Unit, onSettings: () -> Unit, onAddress: () -> Unit,
) {
    val t = rememberTexts()
    val summary = s.summary
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Kick("TMMAC · CorePulse", Modifier.weight(1f))
        IconButton(Glyph.GEAR, stringResource(R.string.settings_title), onClick = onSettings)
    }
    // Stoppgrund zuerst: der Hinweis sagt warum, die Quittung was der Lauf gebracht hat
    s.notice?.let { notice ->
        val kind = noticeKind(notice.id)
        NoticeBox(
            kind,
            stringResource(if (kind == NoticeKind.ERROR) R.string.alert_stopped_title else R.string.notice_title_guard),
            notice.resolve(t),
        )
    }
    if (summary == null) {
        // XCB statt "Core": gemint wird die Coin, nicht die Marke
        Text(stringResource(R.string.setup_title), style = Type.display(Scale.title), modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        // Beim ersten Mal die ganze Erklaerung, danach ein Satz: wer wiederkommt, will starten
        Text(stringResource(if (mainnet != null) R.string.setup_intro_short else R.string.setup_intro), style = Type.body(Scale.lead))
    } else {
        ReceiptHeader(summary, Modifier.padding(top = 4.dp))
        ReceiptFigures(summary)
    }
    // Quittung wie auf dem iPhone: erst das Gemessene, dann der Pool-Stand, dann die Zeilen fuer den naechsten Lauf
    if (summary != null) PoolPanel(poolLoad, hashrate = null, explain = false, receiptHashes = summary.hashes)
    }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Im scrollbaren Teil, nicht in der festen Knopfleiste: bei 200 % Schrift nahm die Leiste sonst 40 % der Hoehe
    val context = LocalContext.current
    val asksForNotifications = mainnet != null && Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    Panel(spacing = 0.dp) {
        if (mainnet != null) {
            NavRow(
                Glyph.PAYOUT, stringResource(R.string.payout_to), shortAddress(mainnet),
                a11y = stringResource(R.string.address_compact_a11y, grouped(mainnet)), onClick = onAddress,
            )
            Hairline()
        }
        // Die Leistung bestimmt Waerme und Akku: sichtbar und einen Tipp entfernt, auch wenn sie in den Einstellungen wohnt
        val level = if (share == CpuShare.ALL) stringResource(R.string.power_option_all) else stringResource(R.string.value_percent, share.percent)
        val of = "· " + pluralStringResource(R.plurals.power_option_cores, cores, threads, cores)
        // Nur die Ziffern in Mono; das Leerzeichen vor "%" waere in Mono doppelt breit, darum gehoert es zum Rest
        val cut = level.indexOfFirst { it == ' ' || it == '\u00A0' }
        val number = if (share == CpuShare.ALL || cut < 0) level else level.substring(0, cut)
        val rest = if (share == CpuShare.ALL || cut < 0) of else level.substring(cut + 1) + " " + of
        NavRow(
            Glyph.CPU, stringResource(R.string.power_kick), number, a11y = stringResource(R.string.power_kick) + ", $level $of",
            mono = share != CpuShare.ALL, tail = rest, onClick = onSettings,
        )
        if (summary != null && mainnet != null) {
            Hairline()
            val pool = stringResource(R.string.setup_pool_link)
            NavRow(Glyph.WEB, pool, "", a11y = pool, external = true, onClick = openPool)
        }
    }
    if (asksForNotifications) Text(stringResource(R.string.start_reason_notifications), style = Type.body(Scale.small))
    }
}

/** Schutz-Stopp (Hitze, Akku, Speicher) oder Fehler? Der Schutz-Stopp ist kein Fehler und wird gold gezeigt. */
internal fun noticeKind(id: Int): NoticeKind = when (id) {
    R.string.notice_low_battery, R.string.notice_too_hot, R.string.notice_not_cooling,
    R.string.notice_no_temperature, R.string.notice_low_memory -> NoticeKind.WARN
    else -> NoticeKind.ERROR
}
