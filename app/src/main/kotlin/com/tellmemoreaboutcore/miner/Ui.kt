// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * TMMAC-Kanon fuer die App (tmmac-brand-design: brand/tokens.css v7.3, Richtung A "Puls").
 * Farben = OKLCH-Tokens nach sRGB umgerechnet. Dunkel ist Kanon, kein Light-Mode.
 * Gruen-Regel: statisches Signal-Gruen NUR auf dem einen gefuellten Knopf; sonst nur kinetisch an echten Daten
 * (Share-Herzschlag, Endpunkt der Kurve). Schalter/Auswahl in --green-floor / --green-rule.
 */
internal object Brand {
    val canvas = Color(0xFF030504)
    val surface1 = Color(0xFF0C0E0C)
    val surface2 = Color(0xFF121413)
    val surface3 = Color(0xFF191B19)
    val surface4 = Color(0xFF272A28)
    val ink = Color(0xFFEFF3F0)
    val inkMuted = Color(0xFFB4B9B5)
    val inkQuiet = Color(0xFF7E817E)
    val borderSoft = Color(0xFF151715)
    val borderMid = Color(0xFF363837)
    val borderStrong = Color(0xFF626462)
    val greenPine = Color(0xFF00331E)
    val greenRule = Color(0xFF009162)   // nur Rahmen
    val greenFloor = Color(0xFF426E59)  // ruhige Flaechen (Schalter, gueltig-Punkt)
    val greenPrimary = Color(0xFF20CF6E)
    val onGreen = Color(0xFF020A05)
    val coral = Color(0xFFE4573F)       // Fehler
    val gold = Color(0xFFE1A536)        // Pause, Warnung, Schutz-Stopp
    /** Panel-Kante (Weiss 7 %): auf guenstigen LCDs verschwimmt surface-1 sonst mit dem Canvas. */
    val edge = Color(0x12FFFFFF)
    val button = RoundedCornerShape(8.dp)  // --r-button, keine Pill-Buttons
    val card = RoundedCornerShape(12.dp)   // --r-card
}

/**
 * Schriftskala: acht Groessen, jede mit genau einer Rolle (Design-Audit 15.11.: vorher 14 Groessen, davon mehrere
 * nur 1 sp auseinander, was unruhig statt abgestuft wirkt). Neue Groessen nur hier, nie als Zahl im Baustein.
 */
internal object Scale {
    val micro = 11.sp   // Eyebrow, Achsen der Kurve, Log
    val small = 13.sp   // Notizen, Erklaertext, Einheiten
    val body = 15.sp    // Zeilen, Fliesstext, Eingaben
    val lead = 17.sp    // Einleitungssatz, Knoepfe
    val figure = 22.sp  // Kennzahl
    val title = 40.sp   // Seitentitel, Guthaben
    val big = 60.sp     // Dauer in der Quittung
    val hero = 88.sp    // Rechenleistung (Lauf, Ruhemodus)
}

/**
 * Schriften (Alternativen zum Original; vorlaeufig F1, Vergleich in tmmac-brand-design
 * briefings/outputs/_decisions/miner-app-font-comparison.html). Die Dateinamen sind rollenbezogen
 * (display/text/mono): ein Wechsel, auch auf die lizenzierten CF-Originale, ist nur ein Dateitausch.
 * Alle drei SIL OFL, Lizenztexte in third_party/fonts.
 */
internal object Type {
    private val display = FontFamily(Font(R.font.display_extrabold, FontWeight.ExtraBold))
    private val text = FontFamily(
        Font(R.font.text_regular, FontWeight.Normal),
        Font(R.font.text_medium, FontWeight.Medium),
        Font(R.font.text_semibold, FontWeight.SemiBold),
    )
    val mono = FontFamily(Font(R.font.mono_regular, FontWeight.Normal), Font(R.font.mono_bold, FontWeight.Bold))

    /** Aussage-Zahl/Titel: Zeilenhoehe 0,84-0,9, enge Laufweite, feste Ziffernbreite (Kanon §2.4 Ausnahme). */
    fun display(size: TextUnit, color: Color = Brand.ink) = TextStyle(
        fontFamily = display, fontWeight = FontWeight.ExtraBold, fontSize = size, color = color,
        letterSpacing = (-0.03).em, lineHeight = size * 0.92, fontFeatureSettings = "tnum",
    )
    fun body(size: TextUnit = Scale.body, color: Color = Brand.inkMuted, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = text, fontWeight = weight, fontSize = size, color = color, lineHeight = size * 1.5)
    fun data(size: TextUnit = Scale.body, color: Color = Brand.ink) =
        TextStyle(fontFamily = mono, fontSize = size, color = color, lineHeight = size * 1.45)
    /** Mono-Eyebrow: Versalien, Laufweite 0,16 em (Website .f-kick). */
    val kick = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = Scale.micro, letterSpacing = 0.16.em, color = Brand.inkMuted)
}

/** "Bewegung entfernen" in den Bedienungshilfen: Animationen aus. */
@Composable
internal fun reduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Das 7-px-Quadrat der Website (.f-sq). Mit [beat] (z. B. Zahl angenommener Shares) blitzt es bei jeder
 * Aenderung 1,2 s in Signal-Gruen auf und faellt auf Grau zurueck (home.css f-beat) — Gruen nur am echten Ereignis.
 * [tick] (z. B. Zahl der Messungen) schlaegt leiser in --green-floor: der Puls ist sichtbar, auch bevor der erste
 * Share kommt (Design-Review 30.10.: "der Puls fehlt, wo die App Pulse heisst").
 */
@Composable
internal fun Square(beat: Int? = null, size: Int = 7, tick: Int? = null) {
    val glow = remember { Animatable(0f) }
    val soft = remember { Animatable(0f) }
    val still = reduceMotion()
    LaunchedEffect(beat) {
        if (beat != null && beat > 0 && !still) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(1200))
        }
    }
    LaunchedEffect(tick) {
        if (tick != null && tick > 0 && !still) {
            soft.snapTo(1f)
            soft.animateTo(0f, tween(900))
        }
    }
    val g = glow.value
    val base = lerp(Brand.inkQuiet, Brand.greenFloor, soft.value)
    Box(
        Modifier.size(size.dp)
            .drawBehind {
                if (g > 0f) drawCircle(Brand.greenPrimary.copy(alpha = 0.5f * g), radius = this.size.minDimension * (0.5f + 1.2f * g))
            }
            .background(lerp(base, Brand.greenPrimary, g), RoundedCornerShape(1.dp)),
    )
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/** Mono-Eyebrow mit Quadrat davor. */
@Composable
internal fun Kick(text: String, modifier: Modifier = Modifier, beat: Int? = null, color: Color = Brand.inkMuted, tick: Int? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Square(beat, tick = tick)
        // Versalien liest der Screenreader sonst teils buchstabenweise vor; Eyebrows sind Abschnittsanfaenge,
        // TalkBack springt mit der Ueberschriften-Navigation dorthin (ui-critic 30.10.)
        Text(text.caps(), style = Type.kick.copy(color = color), modifier = Modifier.semantics { contentDescription = text; heading() })
    }
}

/** Panel: surface-1, feine 1-px-Kante, Radius 12. Ruhig wie auf dem iPhone, ohne helle Oberkante (Design-Abgleich 15.11.). */
@Composable
internal fun Panel(modifier: Modifier = Modifier, spacing: Dp = 10.dp, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(Brand.surface1, Brand.card)
            .border(1.dp, Brand.edge, Brand.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content() }
}

/** Ein-/Aus-Zeile; Spur in --green-floor statt Signal-Gruen (Kanon-Regel 1b). */
@Composable
internal fun Toggle(title: String, detail: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        // toggleable statt selectable: TalkBack sagt "an/aus" statt "ausgewählt"
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.body(Scale.body, Brand.ink, FontWeight.Medium))
            Text(detail, style = Type.body(Scale.small))
        }
        Box(
            Modifier.size(width = 44.dp, height = 26.dp)
                .background(if (checked) Brand.greenFloor else Brand.surface4, RoundedCornerShape(13.dp))
                // Aus-Zustand mit Rand wie MD3, sonst ist die Spur auf canvas kaum zu sehen (1,4:1, WCAG 1.4.11)
                .then(if (checked) Modifier else Modifier.border(1.5.dp, Brand.borderStrong, RoundedCornerShape(13.dp))),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(4.dp).size(18.dp).background(if (checked) Brand.ink else Brand.inkQuiet, RoundedCornerShape(9.dp)))
        }
    }
}

/** Wie [Toggle], aber ohne Schalter: fuer feste Einstellungen, die trotzdem sichtbar sein sollen. */
@Composable
internal fun Info(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Type.body(Scale.body, Brand.ink, FontWeight.Medium))
        Text(detail, style = Type.body(Scale.small))
    }
}

/**
 * Zeile einer Linkliste (statt loser Textlinks): Titel links, Zeichen rechts ("→" Seite in der App, "↗" Browser,
 * "+"/"−" aufklappen). 52 dp hoch, ganze Zeile tippbar. [expanded] sagt TalkBack "ausgeklappt"/"eingeklappt".
 */
@Composable
internal fun LinkRow(text: String, mark: String = "→", expanded: Boolean? = null, onClick: () -> Unit) {
    val state = expanded?.let { androidx.compose.ui.res.stringResource(if (it) R.string.state_expanded else R.string.state_collapsed) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.body(Scale.body, Brand.ink, FontWeight.Medium), modifier = Modifier.weight(1f))
        // Drei Bedeutungen, drei Zeichen in einer Groesse: › Seite in der App, ↗ nach draussen, +/− aufklappen
        when (mark) {
            "→" -> Icon(Glyph.CHEVRON, Brand.inkQuiet, size = 16.dp)
            "↗" -> Icon(Glyph.EXTERNAL, Brand.inkQuiet, size = 16.dp)
            else -> Text(mark, style = Type.data(Scale.body, Brand.inkQuiet), modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** 60 Messungen à 10 s = die letzten 10 Minuten. */
internal const val SPARK_POINTS = 60

/** Duenne Trennlinie. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Brand.borderSoft))
}


/**
 * Hashrate fuer Anzeige und Benachrichtigung. Vierstellig ohne Trennzeichen ("1160" statt "1.160"): im Deutschen las
 * sich der Tausenderpunkt wie ein Komma ("1,16 H/s?", Design-Runde 30.10.). Ab 10.000 mit dem Trennzeichen der
 * App-Sprache, wie es auch viele Sprachen selbst halten (CLDR: Gruppierung erst ab fuenf Stellen, z. B. es, pl).
 */
/**
 * Grossschreibung in der Sprache der App. Ohne Sprachangabe wird aus dem tuerkischen "i" ein "I"
 * statt "İ" ("PIL" statt "PİL").
 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun String.caps(): String =
    uppercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])

internal fun formatRate(rate: Double, locale: java.util.Locale): String {
    val n = Math.round(rate).coerceAtLeast(0)
    return if (n < 10_000) n.toString() else java.text.NumberFormat.getIntegerInstance(locale).format(n)
}

/**
 * Puls-Spur der Laufansicht (aus der Blockspur, Design-Runde 30.10.): ein Strich je Ereignis, aelteste links.
 * Jobs als kurze Striche in --green-floor, Shares hoch, Support-Abschnitt in --ink-quiet. Ein neues Ereignis laeuft
 * von rechts ein und leuchtet kurz in Signal-Gruen auf — Gruen nur am echten Ereignis. [events] zaehlt alle Ereignisse
 * (bei voller Spur aendert sich [trail] sonst nicht mehr, wenn zwei gleiche aufeinander folgen).
 */
@Composable
internal fun Blockspur(trail: String, events: Int, modifier: Modifier = Modifier, height: Dp = 40.dp, description: String? = null) {
    val flash = remember { Animatable(0f) }
    val slide = remember { Animatable(0f) }
    val still = reduceMotion()
    LaunchedEffect(events) {
        if (still || trail.isEmpty()) return@LaunchedEffect
        launch { slide.snapTo(1f); slide.animateTo(0f, tween(420, easing = MotionEase)) }
        flash.snapTo(1f); flash.animateTo(0f, tween(900))
    }
    Canvas(modifier.fillMaxWidth().height(height).clipToBounds().semantics { description?.let { contentDescription = it } }) {
        val n = MiningState.TRAIL_LENGTH
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val step = w + gap
        val empty = n - trail.length
        // Freie Plaetze als Grundlinie, damit die Spur von Anfang an ihre Breite zeigt
        for (i in 0 until empty) drawRect(Brand.surface4, Offset(i * step, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()))
        val shift = slide.value * step
        for (i in empty until n) {
            val c = trail[i - empty]
            val x = i * step + shift
            val h = if (c == 'S') size.height else size.height * 0.36f
            val base = if (c == 'P') Brand.inkQuiet else Brand.greenFloor
            val color = if (i == n - 1) lerp(base, Brand.greenPrimary, flash.value) else base
            drawRect(color, Offset(x, size.height - h), Size(w, h))
        }
    }
}

/** Jobs und angenommene Shares in der Spur, fuer TalkBack. */
internal fun trailCounts(trail: String) = trail.count { it == 'J' } to trail.count { it == 'S' }

/**
 * Startschritte statt eines Ladekreises (Design-Runde 30.10.): erledigte mit Haken, der laufende pulst leise in
 * --green-floor, kommende gedaempft. Ein TalkBack-Knoten mit "Schritt 2 von 4: …", als Live-Region.
 */
@Composable
internal fun StartSteps(steps: List<String>, current: Int, modifier: Modifier = Modifier) {
    val a11y = androidx.compose.ui.res.stringResource(R.string.start_step_a11y, current + 1, steps.size, steps[current])
    val still = reduceMotion()
    val pulse = rememberInfiniteTransition(label = "step")
    val beat by pulse.animateFloat(
        0f, if (still) 0f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "beat",
    )
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = a11y; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        steps.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        i < current -> Text("✓", style = Type.data(Scale.small, Brand.inkMuted))
                        i == current -> Box(Modifier.size(8.dp).background(lerp(Brand.inkQuiet, Brand.greenFloor, beat), RoundedCornerShape(1.dp)))
                        else -> Box(Modifier.size(6.dp).border(1.dp, Brand.borderMid, RoundedCornerShape(1.dp)))
                    }
                }
                Text(
                    text,
                    style = Type.body(Scale.body, if (i == current) Brand.ink else if (i < current) Brand.inkMuted else Brand.inkQuiet,
                        if (i == current) FontWeight.Medium else FontWeight.Normal),
                )
            }
        }
    }
}

/** --motion-base des Kanons (cubic-bezier(.2, 0, .13, 1)). */
internal val MotionEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0.13f, 1f)
