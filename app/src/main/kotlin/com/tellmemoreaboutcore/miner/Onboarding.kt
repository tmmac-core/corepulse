// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Schritte des Einstiegs; die Zustimmung ist Schritt [AGREE_STEP], die Adresse der letzte. */
private const val STEPS = 5
private const val AGREE_STEP = 3

/**
 * Einstieg beim ersten Start und nach jeder neuen Textversion (Consent.VERSION in miner-core): fuenf kurze Schritte
 * statt einer Textwand im Dialog (Abgleich mit der iPhone-App, 15.11.2026). Der Wortlaut der Hinweise ist
 * unveraendert (consent_*, ConsentTest prueft per Hash), nur verteilt: Akku & Waerme (2), was der Pool sieht (3),
 * Beta, Support-Anteil, Ertrag samt Lizenz und Haekchen (4). Wer zustimmt, hat alle Schritte davor durchlaufen;
 * ueberspringen geht nicht. System-Zurueck geht einen Schritt zurueck, im ersten Schritt verlaesst es die App.
 * Gespeichert wird die Zustimmung mit dem Haekchen-Schritt ([onAgree]); die Adresse (5) laesst sich auch spaeter
 * eintragen und entfaellt, wenn schon eine gueltige gespeichert ist (Update von einer aelteren Textversion).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Onboarding(
    step: Int, onStep: (Int) -> Unit, address: AddressInput, openPage: (Uri) -> Unit,
    onLanguage: () -> Unit, onAgree: () -> Unit, onFinish: () -> Unit,
) {
    BackHandler(enabled = step > 0) { onStep(step - 1) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var openLicense by rememberSaveable { mutableStateOf<String?>(null) }
    val de = LocalConfiguration.current.locales[0].language == "de"
    // Jeder Schritt beginnt oben
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    val stepA11y = stringResource(R.string.step_of, step + 1, STEPS)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) IconButton(Glyph.BACK, stringResource(R.string.back)) { onStep(step - 1) } else Spacer(Modifier.size(48.dp))
            // Fortschritt als fuenf Striche; TalkBack hoert "Schritt 2 von 5"
            Row(
                Modifier.weight(1f).padding(horizontal = 12.dp).clearAndSetSemantics { contentDescription = stepA11y },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                repeat(STEPS) { i ->
                    // Offene Schritte in borderStrong: surface-4 hatte auf dem Grund nur ~1,4:1 (WCAG 1.4.11 verlangt 3:1)
                    Box(Modifier.size(width = 24.dp, height = 4.dp).background(if (i == step) Brand.ink else if (i < step) Brand.inkMuted else Brand.borderStrong, RoundedCornerShape(2.dp)))
                }
            }
            LanguageButton(onLanguage)
        }
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                0 -> {
                    StepHeader("TMMAC · CorePulse", stringResource(R.string.setup_title))
                    Text(stringResource(R.string.setup_intro), style = Type.body(Scale.lead))
                }
                1 -> {
                    StepHeader(stringResource(R.string.consent_load_head), stringResource(R.string.intro_heat_title))
                    Text(stringResource(R.string.consent_load), style = Type.body(Scale.lead))
                    // Als Hinweis, nicht als Zeile im Panel: dort sah es aus wie ein Schalter ohne Schalter
                    NoticeBox(NoticeKind.INFO, stringResource(R.string.toggle_screen_title), stringResource(R.string.toggle_screen_detail))
                }
                2 -> {
                    StepHeader(stringResource(R.string.about_connections_title), stringResource(R.string.intro_sent_title))
                    Panel(spacing = 12.dp) {
                        Bullet(stringResource(R.string.consent_pool), stringResource(R.string.consent_pool_head))
                        Bullet(stringResource(R.string.about_connection_stats))
                        Bullet(stringResource(R.string.about_connection_support))
                    }
                    Text(stringResource(R.string.about_connections_none), style = Type.body(Scale.body, Brand.ink))
                }
                AGREE_STEP -> {
                    StepHeader(stringResource(R.string.consent_kick), stringResource(R.string.consent_title))
                    Panel(spacing = 12.dp) {
                        listOf(
                            R.string.consent_beta_head to R.string.consent_beta,
                            R.string.consent_share_head to R.string.consent_share,
                            R.string.consent_earn_head to R.string.consent_earn,
                        ).forEach { (head, text) -> Bullet(stringResource(text), stringResource(head)) }
                    }
                    Panel(spacing = 0.dp) {
                        LicenseText(stringResource(R.string.consent_notice), "NOTICE.txt", openLicense) { openLicense = it }
                        Hairline()
                        LinkRow(stringResource(R.string.about_legal_privacy), mark = "↗") { openPage(privacyPage(de)) }
                    }
                    CheckRow(stringResource(R.string.consent_agree), agreed) { agreed = it }
                }
                else -> {
                    StepHeader(stringResource(R.string.earnings_payout), stringResource(R.string.address_title))
                    AddressEditor(address)
                }
            }
        }
        val ime = WindowInsets.isImeVisible
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (ime) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                step < AGREE_STEP -> PrimaryCta(stringResource(R.string.button_next)) { onStep(step + 1) }
                step == AGREE_STEP -> {
                    // Platz bleibt reserviert, sonst springt der Knopf beim Ankreuzen unter dem Finger weg.
                    // Unsichtbar auch fuer TalkBack, sonst liest er den Hinweis trotz Haekchen vor.
                    Text(
                        stringResource(R.string.consent_need), style = Type.body(Scale.small),
                        modifier = Modifier.padding(bottom = 4.dp).alpha(if (agreed) 0f else 1f).then(if (agreed) Modifier.clearAndSetSemantics {} else Modifier),
                    )
                    PrimaryCta(stringResource(R.string.button_next), enabled = agreed) {
                        onAgree()
                        if (address.mainnet != null) onFinish() else onStep(step + 1)
                    }
                }
                else -> {
                    // Kein gesperrter gruener Block: ohne Adresse ist "Später eintragen" der Knopf (die Startseite
                    // fuehrt mit "Core-Adresse eintragen" hierher zurueck), mit gueltiger Adresse wird er zu "Fertig"
                    if (address.mainnet != null) PrimaryCta(stringResource(R.string.button_done), onClick = onFinish)
                    else SecondaryButton(stringResource(R.string.button_later), Modifier.fillMaxWidth(), onClick = onFinish)
                }
            }
        }
    }
}

/** Kopf eines Schritts: Mono-Eyebrow, Display-Titel (Ueberschrift fuer TalkBack). */
@Composable
private fun StepHeader(kick: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        Kick(kick)
        Text(title, style = Type.display(Scale.title), modifier = Modifier.semantics { heading() })
    }
}
