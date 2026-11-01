// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val SITE = "https://tellmemoreaboutcore.com"

/** Oeffentliches Repo; der Link zeigt auf den Tag genau dieser Version (Quellcode-Angebot nach GPL §6). */
private const val SOURCE_REPO = "https://github.com/tmmac-core/corepulse"

/** Datenschutzerklaerung, Abschnitt zu den Apps (Deutsch auf der deutschen Seite, sonst Englisch). */
private fun privacyPage(de: Boolean): Uri = Uri.parse(if (de) "$SITE/de/datenschutz/#apps" else "$SITE/privacy/#apps")

/** Fremdcode in der App; Lizenztexte liegen in third_party/licenses (als Assets eingebunden). */
private data class Notice(val name: String, val license: String, val file: String)

private val NOTICES = listOf(
    Notice("RandomY", "BSD-3-Clause", "RandomY-BSD-3.txt"),
    Notice("ZXing (core, Android Embedded)", "Apache-2.0", "Apache-2.0.txt"),
    Notice("Barlow", "OFL-1.1", "Barlow-OFL.txt"),
    Notice("Manrope", "OFL-1.1", "Manrope-OFL.txt"),
    Notice("Spline Sans Mono", "OFL-1.1", "SplineSansMono-OFL.txt"),
    Notice("AndroidX, Jetpack Compose, Kotlin, kotlinx", "Apache-2.0", "Apache-2.0.txt"),
)

/**
 * Wer steckt dahinter, was ist XCB, was sendet die App. Antwort auf die Vertrauensfrage aus dem Laien-Review
 * (16.10.): "Ist das Malware?" Bewusst kurz gehalten; Einzelheiten stehen in der
 * Datenschutzerklaerung, die Lizenztexte (Pflicht bei Weitergabe) sind eingeklappt.
 */
@Composable
internal fun AboutScreen(openPage: (Uri) -> Unit, onBack: () -> Unit, onLanguage: () -> Unit) {
    BackHandler(onBack = onBack)
    var openLicense by rememberSaveable { mutableStateOf<String?>(null) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().pineGlow(centerY = 0f, strength = 0.3f).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TextLink(stringResource(R.string.about_back), onClick = onBack) }
            LanguageButton(onLanguage)
        }
        Kick(stringResource(R.string.about_title))
        Text("CorePulse", style = Type.display(44.sp), modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = Type.data(13.sp, Brand.inkQuiet))

        Section(stringResource(R.string.about_publisher_title)) {
            Text(stringResource(R.string.about_publisher_body), style = Type.body(14.sp))
            // Rechtstexte der Website; der Abschnitt #apps beschreibt die Verbindungen dieser App.
            // Deutsch fuehrt auf die deutschen Seiten, alle anderen Sprachen auf die englischen.
            // Als Linkliste mit "↗" (oeffnet den Browser) statt loser Textlinks (Design-Review 30.10.)
            val de = LocalConfiguration.current.locales[0].language == "de"
            Column {
                Hairline()
                LinkRow("tellmemoreaboutcore.com", mark = "↗") { openPage(Uri.parse("https://tellmemoreaboutcore.com")) }
                Hairline()
                LinkRow(stringResource(R.string.about_legal_imprint), mark = "↗") {
                    openPage(Uri.parse(if (de) "$SITE/de/impressum/" else "$SITE/imprint/"))
                }
                Hairline()
                LinkRow(stringResource(R.string.about_legal_privacy), mark = "↗") { openPage(privacyPage(de)) }
                Hairline()
                // Rueckmeldungen ueber das Kontaktformular im Impressum (in der Datenschutzerklaerung beschrieben):
                // nur ein Browser-Link, die App selbst sendet nichts.
                LinkRow(stringResource(R.string.about_feedback), mark = "↗") {
                    openPage(Uri.parse(if (de) "$SITE/de/impressum/#contact" else "$SITE/imprint/#contact"))
                }
            }
            Text(
                stringResource(R.string.about_feedback_hint, stringResource(R.string.details_show), stringResource(R.string.details_copy_log)),
                style = Type.body(13.sp, Brand.inkQuiet),
            )
        }

        Section(stringResource(R.string.about_xcb_title)) {
            Text(stringResource(R.string.about_xcb_body), style = Type.body(14.sp))
        }

        Section(stringResource(R.string.about_connections_title)) {
            Bullet(stringResource(R.string.about_connection_pool))
            Bullet(stringResource(R.string.about_connection_stats))
            Bullet(stringResource(R.string.about_connection_support))
            Text(stringResource(R.string.about_connections_none), style = Type.body(14.sp, Brand.ink))
        }

        Section(stringResource(R.string.about_license_title)) {
            Text("© 2026 TMMAC (tellmemoreaboutcore.com)", style = Type.body(14.sp, Brand.ink, FontWeight.Medium))
            Text(stringResource(R.string.about_license_body), style = Type.body(14.sp))
            Hairline()
            LinkRow(stringResource(R.string.about_source), mark = "↗") {
                openPage(Uri.parse("$SOURCE_REPO/tree/v${BuildConfig.VERSION_NAME}"))
            }
            Hairline()
            LinkRow(stringResource(R.string.about_oss_title), mark = if (showLicenses) "−" else "+", expanded = showLicenses) {
                showLicenses = !showLicenses
            }
            if (showLicenses) {
                LicenseText(stringResource(R.string.about_license_notice), "NOTICE.txt", openLicense) { openLicense = it }
                LicenseText(stringResource(R.string.about_license_gpl), "GPL-3.0.txt", openLicense) { openLicense = it }
                // Dateiname als Bezeichnung (wie die Website-Adresse oben): ohne neuen, zu uebersetzenden Text
                LicenseText("TRADEMARKS.md", "TRADEMARKS.txt", openLicense) { openLicense = it }
                NOTICES.forEach { n ->
                    LicenseText("${n.name} · ${n.license}", n.file, openLicense, key = n.name) { openLicense = it }
                }
            }
        }
    }
}

/** Aufklappbarer Lizenztext aus assets/licenses; [key] unterscheidet Eintraege mit derselben Datei (Apache-2.0). */
@Composable
private fun LicenseText(label: String, file: String, open: String?, key: String = file, onOpen: (String?) -> Unit) {
    val context = LocalContext.current
    val missing = stringResource(R.string.about_license_missing)
    LinkRow(label, mark = if (open == key) "−" else "+", expanded = open == key) { onOpen(if (open == key) null else key) }
    if (open == key) {
        val text = remember(file) {
            runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
                .getOrDefault(missing)
        }
        Text(
            text, style = Type.data(11.sp, Brand.inkMuted),
            modifier = Modifier.fillMaxWidth().background(Brand.surface1, Brand.card).padding(12.dp),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Panel {
        Kick(title)
        content()
    }
}

/** Aufzaehlungspunkt mit haengendem Einzug; das Quadrat ist Zeichnung, TalkBack liest nur den Text. */
@Composable
private fun Bullet(text: String, head: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 9.dp).size(4.dp).background(Brand.inkQuiet, RoundedCornerShape(1.dp)))
        // Stichwort hell und fett ueber dem Satz: wer nur ueberfliegt, sieht die fuenf Punkte trotzdem (Zustimmung v4)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            head?.let { Text(it, style = Type.body(14.sp, Brand.ink, FontWeight.SemiBold)) }
            Text(text, style = Type.body(14.sp))
        }
    }
}

/**
 * Zustimmung vor dem ersten Minen (rechtliche Schutzmassnahme, ersetzt den Hinweis mit nur
 * einem Knopf): Hinweise, Lizenz und Haftung aufklappbar, Datenschutz im Browser, Start erst mit Haekchen.
 * Zurueck/daneben tippen bricht den Start ab. Die Texte sind versioniert (Consent.VERSION in miner-core).
 */
@Composable
internal fun ConsentNotice(openPage: (Uri) -> Unit, onAgree: () -> Unit, onDismiss: () -> Unit) {
    var agreed by rememberSaveable { mutableStateOf(false) }
    var openLicense by rememberSaveable { mutableStateOf<String?>(null) }
    val de = LocalConfiguration.current.locales[0].language == "de"
    val title = stringResource(R.string.consent_title)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            // paneTitle: TalkBack sagt beim Oeffnen den Titel statt nur "Dialog"
            Modifier.padding(20.dp).fillMaxWidth().background(Brand.surface1, Brand.card).border(1.dp, Brand.borderMid, Brand.card)
                .semantics { paneTitle = title }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Nur die Hinweise scrollen; Haekchen und Knopf bleiben auch auf kleinen Handys sichtbar
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Kick(stringResource(R.string.consent_kick))
                Text(title, style = Type.display(28.sp), modifier = Modifier.semantics { heading() })
                listOf(
                    R.string.consent_beta_head to R.string.consent_beta,
                    R.string.consent_load_head to R.string.consent_load,
                    R.string.consent_share_head to R.string.consent_share,
                    R.string.consent_earn_head to R.string.consent_earn,
                    R.string.consent_pool_head to R.string.consent_pool,
                ).forEach { (head, text) -> Bullet(stringResource(text), stringResource(head)) }
                LicenseText(stringResource(R.string.consent_notice), "NOTICE.txt", openLicense) { openLicense = it }
                TextLink(stringResource(R.string.about_legal_privacy), color = Brand.inkMuted) { openPage(privacyPage(de)) }
            }
            CheckRow(stringResource(R.string.consent_agree), agreed) { agreed = it }
            // Platz bleibt reserviert, sonst springt der Knopf beim Ankreuzen unter dem Finger weg.
            // Unsichtbar auch fuer TalkBack, sonst liest er den Hinweis trotz Haekchen vor.
            Text(
                stringResource(R.string.consent_need), style = Type.body(13.sp),
                modifier = Modifier.alpha(if (agreed) 0f else 1f).then(if (agreed) Modifier.clearAndSetSemantics {} else Modifier),
            )
            Button(
                onClick = onAgree, enabled = agreed, shape = Brand.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.greenPrimary, contentColor = Brand.onGreen,
                    disabledContainerColor = Brand.surface3, disabledContentColor = Brand.inkQuiet,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.start_button), style = Type.body(16.sp, LocalContentColor.current, FontWeight.SemiBold)) }
        }
    }
}

/** Haekchen-Zeile; Rahmen und Flaeche in --green-rule/--green-floor statt Signal-Gruen (Kanon-Regel 1b). */
@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Brand.button)
            .background(Brand.surface3, Brand.button)
            .border(1.dp, if (checked) Brand.greenRule else Brand.borderMid, Brand.button)
            // toggleable mit Role.Checkbox: TalkBack sagt "Kontrollkaestchen, aktiviert/nicht aktiviert"
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            // Nur Rahmen und Zeichen: TalkBack sagt schon "Kontrollkaestchen, aktiviert"
            Modifier.size(22.dp).clearAndSetSemantics {}
                .background(if (checked) Brand.greenFloor else Color.Transparent, RoundedCornerShape(4.dp))
                // Leer in inkQuiet (4,4:1 auf surface3); borderStrong lag mit 2,9:1 unter WCAG 1.4.11
                .border(1.5.dp, if (checked) Brand.greenRule else Brand.inkQuiet, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", style = Type.body(15.sp, Brand.ink, FontWeight.Bold))
        }
        Text(text, style = Type.body(15.sp, Brand.ink, FontWeight.Medium), modifier = Modifier.weight(1f))
    }
}

/** Weltkugel fuer den Sprach-Knopf: aus Linien gezeichnet (keine Symbol-Bibliothek, kein Emoji), Farbe wie der Text. */
@Composable
private fun Globe(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(14.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx())
        val r = size.minDimension / 2 - stroke.width / 2
        drawCircle(color, r, style = stroke)
        // Laengengrad als schmale Ellipse, Aequator als Linie
        drawOval(
            color, topLeft = androidx.compose.ui.geometry.Offset(center.x - r * 0.45f, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 2), style = stroke,
        )
        drawLine(color, androidx.compose.ui.geometry.Offset(center.x - r, center.y), androidx.compose.ui.geometry.Offset(center.x + r, center.y), stroke.width)
    }
}

/**
 * Knopf "Sprache" oben rechts: Weltkugel + Kuerzel der aktuellen App-Sprache (DE, EN, …). 48 dp hoch;
 * TalkBack liest "Sprache ändern, aktuell Deutsch".
 */
@Composable
internal fun LanguageButton(onClick: () -> Unit) {
    val config = LocalConfiguration.current
    val tag = config.locales[0].language.takeIf { it in AppLanguage.TAGS } ?: "en"
    val a11y = stringResource(R.string.language_a11y, AppLanguage.nativeName(tag))
    Row(
        Modifier.heightIn(min = 48.dp).clip(Brand.button).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Globe(Brand.inkMuted)
        Text(tag.uppercase(), style = Type.kick, modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * Sprachwahl: "Wie das Handy" plus die fuenf App-Sprachen in ihrem Eigennamen (so findet man die eigene Sprache
 * auch dann, wenn die App gerade eine fremde zeigt). Auswahl nur ueber den Rahmen (--green-rule).
 */
@Composable
internal fun LanguagePicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Brand.surface1, Brand.card).border(1.dp, Brand.borderMid, Brand.card)
                .padding(20.dp).verticalScroll(rememberScrollState()).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Kick(stringResource(R.string.language_title), Modifier.padding(bottom = 6.dp))
            val options = listOf("" to stringResource(R.string.language_system, AppLanguage.nativeName(AppLanguage.systemTag()))) +
                AppLanguage.TAGS.map { it to AppLanguage.nativeName(it) }
            options.forEach { (tag, name) ->
                val selected = tag == current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(Brand.button)
                        .background(if (selected) Brand.surface3 else Color.Transparent, Brand.button)
                        .border(1.dp, if (selected) Brand.greenRule else Brand.borderSoft, Brand.button)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(tag) })
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name, style = Type.body(16.sp, if (selected) Brand.ink else Brand.inkMuted, FontWeight.Medium),
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) Box(Modifier.size(7.dp).background(Brand.greenFloor, RoundedCornerShape(1.dp)))
                }
            }
        }
    }
}
