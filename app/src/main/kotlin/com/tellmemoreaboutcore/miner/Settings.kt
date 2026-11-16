// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tellmemoreaboutcore.miner.core.CpuShare
import com.tellmemoreaboutcore.miner.core.Ican
import com.tellmemoreaboutcore.miner.core.WorkerName

/** Alles, was das Adressfeld braucht: Wert, Pruefung, Scan-Ergebnis und die Wege, eine Adresse hineinzubekommen. */
internal class AddressInput(
    val wallet: String, val check: Ican.Result, val mainnet: Ican.Result.Valid?, @StringRes val scanMessage: Int?,
    val onWallet: (String) -> Unit, val onPaste: () -> Unit, val onScan: () -> Unit, val openPage: (Uri) -> Unit,
)

/** Kopf einer Unterseite: Zurueck-Knopf, Eyebrow, Titel in Display. */
@Composable
internal fun PageHeader(kick: String?, title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(Glyph.BACK, stringResource(R.string.back), onClick = onBack)
        Spacer(Modifier.weight(1f))
        trailing()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        kick?.let { Kick(it) }
        Text(title, style = Type.display(Scale.title), modifier = Modifier.semantics { heading() })
    }
}

/**
 * Einstellungen (Zahnrad auf der Startseite): Auszahlung, Leistung, Sprache, Hilfe. Eine eigene Seite mit
 * Zurueck-Knopf und System-Zurueck, wie auf Android ueblich (das iPhone zeigt dafuer ein Blatt mit "Fertig").
 * Oeffnet auch waehrend eines Laufs: Adresse, Name und Leistung sind dann gesperrt ([locked]), sie gelten erst ab
 * dem naechsten Start; alles andere wirkt sofort.
 */
@Composable
internal fun SettingsScreen(
    s: MiningState.Snapshot, address: AddressInput, locked: Boolean, onAddress: () -> Unit,
    worker: String, onWorker: (String) -> Unit,
    share: CpuShare, onShare: (CpuShare) -> Unit, cores: Int,
    keepScreenOn: Boolean, onKeepScreenOn: () -> Unit,
    onLanguage: () -> Unit, onAbout: () -> Unit, onCopyLog: (String) -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showHow by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Ohne Markenzeile: sie steht auf der Startseite, hier wiederholte sie sich nur
        PageHeader(null, stringResource(R.string.settings_title), onBack)
        if (locked) NoticeBox(NoticeKind.INFO, stringResource(R.string.settings_locked))

        Panel {
            Kick(stringResource(R.string.earnings_payout))
            val mainnet = address.mainnet
            SettingRow(
                stringResource(R.string.address_kick), mainnet?.let { shortAddress(it.address) } ?: "–",
                a11y = mainnet?.let { stringResource(R.string.address_compact_a11y, grouped(it.address)) } ?: stringResource(R.string.cta_enter_address),
                enabled = !locked, onClick = onAddress,
            )
            Hairline()
            WorkerEditor(worker, onWorker, locked)
        }

        Panel {
            Kick(stringResource(R.string.power_kick))
            PowerOptions(share, onShare, cores, enabled = !locked)
            Text(stringResource(R.string.power_hint), style = Type.body(Scale.small))
            Hairline(Modifier.padding(vertical = 4.dp))
            // Viele Handys (z. B. Samsung) bremsen den Miner, sobald der Bildschirm ausgeht
            Toggle(stringResource(R.string.toggle_screen_title), stringResource(R.string.toggle_screen_detail), keepScreenOn, onKeepScreenOn)
            Hairline(Modifier.padding(vertical = 4.dp))
            // Fest eingebaut (19.10.): kein Schalter, aber weiter offen genannt
            Info(stringResource(R.string.running_kick_support), stringResource(R.string.support_text))
        }

        Panel(spacing = 0.dp) {
            val tag = LocalConfiguration.current.locales[0].language.takeIf { it in AppLanguage.TAGS } ?: "en"
            SettingRow(
                stringResource(R.string.language_title), AppLanguage.nativeName(tag),
                a11y = stringResource(R.string.language_a11y, AppLanguage.nativeName(tag)), mono = false, onClick = onLanguage,
            )
        }

        Panel(spacing = 0.dp) {
            Kick(stringResource(R.string.settings_help), Modifier.padding(bottom = 6.dp))
            LinkRow(stringResource(R.string.how_it_works_show), mark = if (showHow) "−" else "+", expanded = showHow) { showHow = !showHow }
            if (showHow) Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Glossary() }
            Hairline()
            LinkRow(stringResource(R.string.about_title), onClick = onAbout)
            Hairline()
            // Fuer Tests und Fehlermeldungen: Messwerte, Blockspur und Log zum Kopieren, standardmaessig eingeklappt
            LinkRow(stringResource(R.string.details_show), mark = if (showDetails) "−" else "+", expanded = showDetails) { showDetails = !showDetails }
            if (showDetails) Details(s, onCopyLog)
        }
    }
}

/** Zeile "Titel … Wert ›" in einem Panel; ganze Zeile tippbar, 52 dp hoch. Gesperrt: ohne Pfeil, gedaempft. */
@Composable
private fun SettingRow(title: String, value: String, a11y: String, enabled: Boolean = true, mono: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (enabled) Modifier.clickableRow(onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = "$title, $a11y"; if (enabled) role = Role.Button else disabled() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Type.body(Scale.body, Brand.ink, FontWeight.Medium), modifier = Modifier.weight(1f))
        val tint = if (enabled) Brand.inkMuted else Brand.inkQuiet
        Text(value, style = if (mono) Type.data(Scale.body, tint) else Type.body(Scale.body, tint))
        if (enabled) Icon(Glyph.CHEVRON, Brand.inkQuiet, size = 16.dp)
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit) = clickableNoRole(onClick)

/** Tippbar ohne eigene Rolle: die Rolle setzt der Aufrufer in seiner Semantik. */
internal fun Modifier.clickableNoRole(onClick: () -> Unit) = this.clickable(onClick = onClick)

/** Der Name ist vorbelegt; die meisten muessen ihn nie anfassen, darum als Zeile mit "Anpassen". */
@Composable
private fun WorkerEditor(worker: String, onWorker: (String) -> Unit, locked: Boolean) {
    var editing by rememberSaveable { mutableStateOf(false) }
    // Entwurf getrennt vom gespeicherten Namen: uebernommen wird nur ein gueltiger, der aber sofort beim Tippen.
    // "Speichern" schliesst nur das Feld; wer stattdessen "Zurueck" tippt, verliert die Aenderung nicht
    var draft by rememberSaveable { mutableStateOf(worker) }
    val ok = WorkerName.isValid(draft)
    val focus = LocalFocusManager.current
    // Erst Fokus weg, dann Feld schliessen: sonst bleibt die Tastatur offen
    val close = { if (ok) { onWorker(draft); focus.clearFocus(force = true); editing = false } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.worker_kick), style = Type.body(Scale.body, Brand.ink, FontWeight.Medium), modifier = Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                if (editing && !locked) Field(
                    draft, { draft = WorkerName.sanitize(it); if (WorkerName.isValid(draft)) onWorker(draft) }, label = stringResource(R.string.worker_kick), placeholder = "phone-1234",
                    next = false, onDone = close, error = if (ok) null else stringResource(R.string.worker_invalid),
                ) else Text(worker, style = Type.data(Scale.body, if (locked) Brand.inkQuiet else Brand.ink))
            }
            if (!locked && !editing) TextLink(stringResource(R.string.worker_edit)) { draft = worker; editing = true }
            if (!locked && editing && ok) TextLink(stringResource(R.string.worker_save), onClick = close)
        }
        Text(
            stringResource(when { !editing || locked -> R.string.worker_explainer; ok -> R.string.worker_public_warning; else -> R.string.worker_invalid }),
            style = Type.body(Scale.small, if (editing && !ok && !locked) Brand.coral else Brand.inkMuted),
        )
    }
}

/** Leistungsstufen 25/50/75/Alle. Bei grosser Schrift 2 × 2 statt 4 nebeneinander (ui-critic 30.10.). */
@Composable
private fun PowerOptions(share: CpuShare, onShare: (CpuShare) -> Unit, cores: Int, enabled: Boolean) {
    val rows = if (LocalDensity.current.fontScale > 1.3f) CpuShare.entries.chunked(2) else listOf(CpuShare.entries)
    Column(Modifier.fillMaxWidth().selectableGroup().alpha(if (enabled) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    val selected = option == share
                    Column(
                        Modifier.weight(1f)
                            .background(if (selected) Brand.surface3 else Brand.canvas, Brand.button)
                            // Auswahl nur ueber den Rahmen (--green-rule ist border-only), Flaeche bleibt neutral.
                            // Ungewaehlt mit sichtbarem Rand: borderStrong hat 3,25:1 auf surface-1 (WCAG 1.4.11),
                            // borderMid nur 1,6:1 (Audit 15.11.)
                            .border(1.dp, if (selected) Brand.greenRule else Brand.borderStrong, Brand.button)
                            // selectable statt clickable: Screenreader sagt "ausgewählt" / "nicht ausgewählt"
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onShare(option) })
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Gleiche Hoehe fuer Mono ("50 %") und Textschrift ("Alle"), sonst steht die letzte Kachel tiefer
                        Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                            // Ziffern in Mono, "%" und das Wort "Alle" in der Textschrift (Kanon); in Mono waere die
                            // Luecke vor "%" doppelt breit
                            val level = if (option == CpuShare.ALL) stringResource(R.string.power_option_all) else stringResource(R.string.value_percent, option.percent)
                            Text(
                                digitsInMono(level),
                                style = Type.body(Scale.lead, if (selected) Brand.ink else Brand.inkMuted, FontWeight.SemiBold).copy(lineHeight = 22.sp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            // Kurz und einzeilig: "2 von 4 Kernen" brach in jeder Kachel auf zwei bis drei Zeilen um
                            option.threadsFor(cores).let { pluralStringResource(R.plurals.power_cores_short, it, it) },
                            style = Type.body(Scale.small, Brand.inkQuiet), maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Messwerte, Blockspur und Log (fuer Tests und Fehlermeldungen). */
@Composable
private fun Details(s: MiningState.Snapshot, onCopyLog: (String) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.details_stats, s.shareDifficulty, s.thermalStatus?.toString() ?: "-", s.flags),
            style = Type.data(Scale.small, Brand.inkMuted),
        )
        // Blockspur: jeder Job vom Pool laeuft als Strich ein, ein Fund hoch. Seit dem Abgleich mit dem iPhone hier
        // statt in der Laufansicht: sie braucht eine Legende, um verstanden zu werden
        if (s.trail.isNotEmpty()) {
            val (jobs, shares) = trailCounts(s.trail)
            Kick(stringResource(R.string.details_trail_title), color = Brand.inkQuiet)
            Blockspur(s.trail, s.events, description = stringResource(R.string.running_trail_a11y, jobs, shares))
            Text(stringResource(R.string.details_trail_legend), style = Type.body(Scale.small, Brand.inkQuiet))
        }
        // Erst hier in Text: in der aktuellen App-Sprache, auch fuer Eintraege von vor einem Sprachwechsel
        val logTexts = rememberTexts()
        val logLines = remember(s.log, logTexts) { s.log.map { it.text(logTexts) } }
        // Ohne Lauf seit dem App-Start gibt es kein Log: kein Knopf, der Leeres kopiert, kein leerer Kasten
        if (logLines.isNotEmpty()) SecondaryButton(stringResource(R.string.details_copy_log), Modifier.fillMaxWidth()) { onCopyLog(logLines.joinToString("\n")) }
        if (logLines.isNotEmpty()) Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).background(Brand.canvas, Brand.card)
                .verticalScroll(rememberScrollState(), reverseScrolling = true).padding(12.dp),
        ) {
            logLines.forEach { Text(it, style = Type.data(Scale.micro, Brand.inkMuted)) }
        }
    }
}

/**
 * Adress-Seite: Feld mit "Einfügen", QR-Scan, Hilfe fuer alle ohne Adresse. Erreichbar von der Startseite
 * ("Auszahlung an … ›" bzw. der Knopf "Core-Adresse eintragen") und aus den Einstellungen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddressScreen(address: AddressInput, locked: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageHeader(stringResource(R.string.earnings_payout), stringResource(R.string.address_title), onBack)
            if (locked) {
                NoticeBox(NoticeKind.INFO, stringResource(R.string.settings_locked))
                address.mainnet?.let { Panel { Text(grouped(it.address), style = Type.data(Scale.body, Brand.ink)) } }
            } else AddressEditor(address)
        }
        // Bei offener Tastatur bleibt der Knopf direkt ueber ihr sichtbar
        if (!locked) Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (WindowInsets.isImeVisible) 8.dp else 16.dp)) {
            PrimaryCta(stringResource(R.string.button_done), enabled = address.mainnet != null, onClick = onBack)
        }
    }
}

/** Adressfeld mit Pruefung, Scan und der Hilfe "Noch keine Core-Adresse?"; auch der letzte Schritt des Einstiegs. */
@Composable
internal fun AddressEditor(a: AddressInput) {
    val t = rememberTexts()
    val error = when {
        a.scanMessage != null -> stringResource(a.scanMessage)
        a.mainnet == null && a.wallet.isNotBlank() -> walletHint(a.wallet, a.check, t)
        else -> null
    }
    Panel {
        // Aktion ("Einfuegen") neben dem Feld statt in einer Kopfzeile (Design-Review 30.10.)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field(a.wallet, a.onWallet, label = stringResource(R.string.address_kick), placeholder = "cb…", next = false, error = error) }
            TextLink(stringResource(R.string.address_paste), onClick = a.onPaste)
        }
        when {
            // Scan-Ergebnis kommt ohne Tippen: TalkBack liest es vor (Live-Region); Tipp-Hinweise nicht, sonst redet er bei jedem Zeichen
            a.scanMessage != null -> Text(stringResource(a.scanMessage), style = Type.body(Scale.small, Brand.coral), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            a.mainnet != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 6.dp).size(6.dp).background(Brand.greenFloor, RoundedCornerShape(3.dp)))
                Column {
                    Text(stringResource(R.string.address_valid_payout_to), style = Type.body(Scale.small))
                    Text(grouped(a.mainnet.address), style = Type.data(Scale.small, Brand.ink))
                }
            }
            else -> Text(walletHint(a.wallet, a.check, t), style = Type.body(Scale.small, if (a.wallet.isBlank()) Brand.inkMuted else Brand.coral))
        }
        SecondaryButton(stringResource(R.string.address_scan_button), Modifier.fillMaxWidth(), onClick = a.onScan)
        Text(stringResource(R.string.address_scan_privacy), style = Type.body(Scale.small, Brand.inkQuiet))
    }
    // Gleich sichtbar statt hinter einem Link: ohne Adresse kommt niemand weiter (Laien-Review 16.10.)
    if (a.mainnet == null) Panel(spacing = 6.dp) {
        Text(stringResource(R.string.address_none_title), style = Type.body(Scale.body, Brand.ink, FontWeight.Medium), modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.address_none_body), style = Type.body(Scale.body))
        LinkRow(stringResource(R.string.address_corepass_store_link), mark = "↗") { a.openPage(Uri.parse("https://play.google.com/store/apps/details?id=net.corepass.app")) }
    }
}

/**
 * Eingabefeld im Panel: Mono, ohne Material-Rahmen; Fokus zeigt eine --green-rule-Linie (Rahmen, nie Flaeche).
 * [label] liest TalkBack vor (die sichtbare Beschriftung steht ausserhalb des Felds); Unterlinie borderStrong fuer ≥3:1.
 */
@Composable
internal fun Field(
    value: String, onValue: (String) -> Unit, label: String, placeholder: String, next: Boolean,
    onDone: (() -> Unit)? = null, error: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = Type.data(Scale.body, Brand.ink),
        cursorBrush = SolidColor(Brand.greenPrimary),
        keyboardOptions = plainKeyboard.copy(imeAction = if (next) ImeAction.Next else ImeAction.Done),
        keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
            // Fehler am Feld: TalkBack liest ihn beim Feld vor, nicht nur als Text darunter (WCAG 3.3.1)
            .semantics { contentDescription = label; if (error != null) error(error) },
        decorationBox = { inner ->
            // Unterlinie am unteren Rand der 48-dp-Flaeche, Text direkt darueber
            Column(Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = Type.data(Scale.body, Brand.inkQuiet))
                    inner()
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(if (focused) Brand.greenRule else Brand.borderStrong))
            }
        },
    )
}

/** Text-Knopf mit 48-dp-Tippflaeche (Material-Mindestmass), auch wenn die Schrift kleiner ist. */
@Composable
internal fun TextLink(text: String, color: androidx.compose.ui.graphics.Color = Brand.ink, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickableNoRole(onClick).semantics { role = Role.Button }.padding(end = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = Type.body(Scale.body, color, FontWeight.Medium)) }
}
