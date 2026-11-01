// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * TMMAC-Kanon fuer die App (tmmac-brand-design: brand/tokens.css v7.3, Richtung A "Puls").
 * Farben = OKLCH-Tokens nach sRGB umgerechnet. Dunkel ist Kanon, kein Light-Mode.
 * Gruen-Regel: statisches Signal-Gruen NUR auf "Mining starten"; sonst nur kinetisch an echten Daten
 * (Share-Herzschlag, Endpunkt der Kurve). Schalter/Auswahl in --green-floor / --green-rule.
 */
internal object Brand {
    val canvas = Color(0xFF030504)
    val surface1 = Color(0xFF0C0E0C)
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
    /** Panel-Kante (Weiss 7 %) und helle Oberkante (Weiss 12 %) wie die Website-Karten. */
    val edge = Color(0x12FFFFFF)
    val lit = Color(0x1FFFFFFF)
    val button = RoundedCornerShape(8.dp)  // --r-button, keine Pill-Buttons
    val card = RoundedCornerShape(12.dp)   // --r-card
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
    fun body(size: TextUnit = 15.sp, color: Color = Brand.inkMuted, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = text, fontWeight = weight, fontSize = size, color = color, lineHeight = size * 1.5)
    fun data(size: TextUnit = 15.sp, color: Color = Brand.ink) =
        TextStyle(fontFamily = mono, fontSize = size, color = color, lineHeight = size * 1.45)
    /** Mono-Eyebrow: Versalien, Laufweite 0,16 em (Website .f-kick). */
    val kick = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.16.em, color = Brand.inkMuted)
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
        Text(text.uppercase(), style = Type.kick.copy(color = color), modifier = Modifier.semantics { contentDescription = text; heading() })
    }
}

/** Panel wie die Website-Karten: surface-1, 1-px-Kante, hellere Oberkante, Radius 12. */
@Composable
internal fun Panel(modifier: Modifier = Modifier, spacing: Dp = 10.dp, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(Brand.surface1, Brand.card)
            .border(1.dp, Brand.edge, Brand.card)
            .drawBehind {
                // helle Oberkante (--lit), innerhalb der Rundung
                drawLine(Brand.lit, Offset(12.dp.toPx(), 0.5f), Offset(size.width - 12.dp.toPx(), 0.5f), 1.dp.toPx())
            }
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
            Text(title, style = Type.body(16.sp, Brand.ink, FontWeight.Medium))
            Text(detail, style = Type.body(13.sp))
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
        Text(title, style = Type.body(16.sp, Brand.ink, FontWeight.Medium))
        Text(detail, style = Type.body(13.sp))
    }
}

/**
 * Kennzahl wie .f-nums auf der Website: Linie oben, Mono-Label, Zahl in Display. Mono wurde am 30.10. probiert
 * (Kanon §2.4) und verworfen: die Leerzeichen vor "%" und "°C" werden in fester Breite zu Luecken.
 * Ein TalkBack-Stopp je Kennzahl; bei grosser Schrift wird die Zahl kleiner statt mitten in der Zahl umzubrechen.
 */
@Composable
internal fun Figure(label: String, value: String, note: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.borderMid))
        Text(
            label.uppercase(), style = Type.kick.copy(fontSize = 11.sp, color = Brand.inkQuiet),
            modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = label },
        )
        Text(
            value, style = Type.display(28.sp),
            maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 28.sp),
            modifier = Modifier.padding(top = 4.dp),
        )
        note?.let { Text(it, style = Type.body(12.sp, Brand.inkQuiet)) }
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
        Text(text, style = Type.body(15.sp, Brand.ink, FontWeight.Medium), modifier = Modifier.weight(1f))
        Text(mark, style = Type.data(15.sp, Brand.inkQuiet), modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * Verlauf der Hashrate (lokal aus den eigenen Messwerten, kein Netz). Linie in ink-muted, Flaeche in Pine,
 * nur der juengste Punkt in Signal-Gruen und nur, wenn er gerade neu ist (pulst kurz) — kinetisch, an Daten gekoppelt.
 */
@Composable
internal fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, description: String? = null) {
    val pulse = remember { Animatable(0f) }
    val still = reduceMotion()
    LaunchedEffect(values.size, values.lastOrNull()) {
        if (!still && values.isNotEmpty()) { pulse.snapTo(1f); pulse.animateTo(0f, tween(900)) }
    }
    Canvas(modifier.fillMaxWidth().height(56.dp).semantics { description?.let { contentDescription = it } }) {
        if (values.size < 2) {
            // Leerzustand: gestrichelte Grundlinie, bis zwei Messwerte da sind
            drawLine(
                Brand.borderMid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())),
            )
            return@Canvas
        }
        val max = values.max().coerceAtLeast(1f)
        val min = (values.min() * 0.85f).coerceAtMost(max * 0.85f)
        // Solange die 10 min nicht voll sind, nutzt die Kurve die ganze Breite (statt eines Krakels am rechten Rand,
        // Design-Review 30.10.); danach rueckt sie nach links weiter. Die juengste Messung steht immer rechts ("jetzt").
        val n = (values.size - 1).coerceIn(1, SPARK_POINTS - 1)
        fun x(i: Int) = size.width * (n - (values.size - 1 - i)).coerceAtLeast(0) / n
        fun y(v: Float) = size.height - 4.dp.toPx() - (size.height - 8.dp.toPx()) * (v - min) / (max - min).coerceAtLeast(1f)
        val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val area = Path().apply { addPath(line); lineTo(x(values.lastIndex), size.height); lineTo(x(0), size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(Brand.greenPine.copy(alpha = 0.55f), Color.Transparent)))
        drawPath(line, Brand.inkMuted, style = Stroke(1.5.dp.toPx()))
        val end = Offset(x(values.lastIndex), y(values.last()))
        val p = pulse.value
        drawCircle(lerp(Brand.inkMuted, Brand.greenPrimary, p), radius = (3f + 2.5f * p).dp.toPx(), center = end)
    }
}

/** 60 Messungen à 10 s = die letzten 10 Minuten. */
internal const val SPARK_POINTS = 60

/** Pine-Licht hinter dem Wichtigen (Website .hx::before), nie als Flaeche. */
internal fun Modifier.pineGlow(centerY: Float = 0.3f, strength: Float = 0.45f) = drawBehind {
    drawRect(
        Brush.radialGradient(
            listOf(Brand.greenPine.copy(alpha = strength), Color.Transparent),
            center = Offset(size.width / 2, size.height * centerY),
            radius = size.maxDimension * 0.55f,
        ),
    )
}

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
                        i < current -> Text("✓", style = Type.data(13.sp, Brand.inkMuted))
                        i == current -> Box(Modifier.size(8.dp).background(lerp(Brand.inkQuiet, Brand.greenFloor, beat), RoundedCornerShape(1.dp)))
                        else -> Box(Modifier.size(6.dp).border(1.dp, Brand.borderMid, RoundedCornerShape(1.dp)))
                    }
                }
                Text(
                    text,
                    style = Type.body(15.sp, if (i == current) Brand.ink else if (i < current) Brand.inkMuted else Brand.inkQuiet,
                        if (i == current) FontWeight.Medium else FontWeight.Normal),
                )
            }
        }
    }
}

/** --motion-base des Kanons (cubic-bezier(.2, 0, .13, 1)). */
internal val MotionEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0.13f, 1f)
