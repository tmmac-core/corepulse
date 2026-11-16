// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Bausteine aus dem Abgleich mit der iPhone-App (15.11.2026): Symbole, Kennzahl mit Mono-Ziffern und leiser
 * Einheit, 2×2-Raster, Hinweis-Kasten in drei Arten, Verlauf mit Skala, Knoepfe. Jeder Bildschirm nutzt nur diese
 * und die Bausteine aus Ui.kt, damit die App eine Oberflaeche bleibt.
 */

/** Symbole der App: aus Linien gezeichnet wie die Weltkugel (keine Symbol-Bibliothek, kein Emoji, kein Fremdcode). */
internal enum class Glyph { GEAR, CHECK, THERMO, INFO, WARN, ERROR, PAUSE, MOON, STOP, PLAY, CHEVRON, EXTERNAL, PAYOUT, CPU, BACK, WEB }

/** [glyph] auf einem 24er-Raster, [size] gross; reine Zeichnung, fuer TalkBack unsichtbar (der Text daneben spricht). */
@Composable
internal fun Icon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val u = this.size.minDimension / 24f
        val line = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
            if (close) close()
        }
        when (glyph) {
            Glyph.GEAR -> gear(color, u)
            Glyph.CHECK -> {
                drawCircle(color, 9f * u, style = line)
                drawPath(path(7.6f to 12.3f, 10.7f to 15.3f, 16.4f to 9.2f), color, style = line)
            }
            Glyph.THERMO -> {
                drawRoundRect(color, p(10f, 3f), Size(4f * u, 12.5f * u), CornerRadius(2f * u), style = line)
                drawCircle(color, 3.6f * u, p(12f, 17.4f))
                drawLine(color, p(12f, 9f), p(12f, 16f), 1.7f * u, StrokeCap.Round)
            }
            Glyph.INFO -> {
                drawCircle(color, 9f * u, style = line)
                drawCircle(color, 1.1f * u, p(12f, 8f))
                drawLine(color, p(12f, 11.2f), p(12f, 16.4f), 1.8f * u, StrokeCap.Round)
            }
            Glyph.WARN -> {
                drawPath(path(12f to 3.6f, 21.2f to 19.6f, 2.8f to 19.6f, close = true), color, style = line)
                drawLine(color, p(12f, 9.6f), p(12f, 14f), 1.8f * u, StrokeCap.Round)
                drawCircle(color, 1.1f * u, p(12f, 16.8f))
            }
            Glyph.ERROR -> {
                drawCircle(color, 9f * u, style = line)
                drawLine(color, p(9f, 9f), p(15f, 15f), 1.8f * u, StrokeCap.Round)
                drawLine(color, p(15f, 9f), p(9f, 15f), 1.8f * u, StrokeCap.Round)
            }
            Glyph.PAUSE -> {
                drawCircle(color, 9f * u, style = line)
                drawLine(color, p(10f, 8.8f), p(10f, 15.2f), 1.9f * u, StrokeCap.Round)
                drawLine(color, p(14f, 8.8f), p(14f, 15.2f), 1.9f * u, StrokeCap.Round)
            }
            Glyph.MOON -> {
                val full = Path().apply { addOval(Rect(p(11.5f, 12.5f), 8f * u)) }
                val cut = Path().apply { addOval(Rect(p(16.2f, 8.4f), 7.2f * u)) }
                drawPath(Path().apply { op(full, cut, PathOperation.Difference) }, color, style = line)
            }
            Glyph.STOP -> drawRoundRect(color, p(6.5f, 6.5f), Size(11f * u, 11f * u), CornerRadius(1.5f * u))
            Glyph.PLAY -> drawPath(path(8f to 5.5f, 19f to 12f, 8f to 18.5f, close = true), color)
            Glyph.CHEVRON -> drawPath(path(9.5f to 6.5f, 15f to 12f, 9.5f to 17.5f), color, style = line)
            Glyph.BACK -> {
                drawLine(color, p(5f, 12f), p(19f, 12f), 1.7f * u, StrokeCap.Round)
                drawPath(path(11f to 6f, 5f to 12f, 11f to 18f), color, style = line)
            }
            Glyph.EXTERNAL -> {
                drawLine(color, p(8f, 16f), p(16f, 8f), 1.7f * u, StrokeCap.Round)
                drawPath(path(9.5f to 8f, 16f to 8f, 16f to 14.5f), color, style = line)
            }
            // Weltkugel: die Zeile oeffnet eine Webseite (Pool), sie erklaert nichts (darum kein Info-Symbol)
            Glyph.WEB -> {
                drawCircle(color, 9f * u, style = line)
                drawOval(color, p(8.2f, 3f), Size(7.6f * u, 18f * u), style = line)
                drawLine(color, p(3.4f, 12f), p(20.6f, 12f), 1.7f * u, StrokeCap.Round)
            }
            Glyph.PAYOUT -> {
                drawLine(color, p(12f, 4f), p(12f, 14.6f), 1.7f * u, StrokeCap.Round)
                drawPath(path(7.6f to 10.4f, 12f to 14.8f, 16.4f to 10.4f), color, style = line)
                drawLine(color, p(6f, 19f), p(18f, 19f), 1.7f * u, StrokeCap.Round)
            }
            Glyph.CPU -> {
                drawRoundRect(color, p(6.5f, 6.5f), Size(11f * u, 11f * u), CornerRadius(2f * u), style = line)
                drawRoundRect(color, p(10f, 10f), Size(4f * u, 4f * u), CornerRadius(0.6f * u))
                for (x in listOf(9.5f, 14.5f)) {
                    drawLine(color, p(x, 3.5f), p(x, 6.5f), 1.6f * u, StrokeCap.Round)
                    drawLine(color, p(x, 17.5f), p(x, 20.5f), 1.6f * u, StrokeCap.Round)
                    drawLine(color, p(3.5f, x), p(6.5f, x), 1.6f * u, StrokeCap.Round)
                    drawLine(color, p(17.5f, x), p(20.5f, x), 1.6f * u, StrokeCap.Round)
                }
            }
        }
    }
}

/** Zahnrad: acht Zaehne um einen Ring. */
private fun DrawScope.gear(color: Color, u: Float) {
    val c = Offset(12f * u, 12f * u)
    for (i in 0 until 8) rotate(i * 45f, c) {
        drawRoundRect(color, Offset(10.4f * u, 2.6f * u), Size(3.2f * u, 4.4f * u), CornerRadius(0.9f * u))
    }
    drawCircle(color, 6.1f * u, c, style = Stroke(2.6f * u))
    drawCircle(color, 2.5f * u, c, style = Stroke(1.5f * u))
}

/**
 * Wert einer Kennzahl: Ziffern laut (Mono 22, ink), Einheiten leise (Mono 13) auf derselben Grundlinie:
 * "17 H/s", "~4 min", "85 %". Das Leerzeichen gehoert zur leisen Einheit, in Mono 22 waere die Luecke doppelt
 * breit (daran scheiterte Mono am 30.10.; Loesung aus der iPhone-App).
 */
internal fun styledValue(value: String, numberColor: Color = Brand.ink, unitColor: Color = Brand.inkQuiet): AnnotatedString {
    val number = SpanStyle(fontFamily = Type.mono, fontWeight = FontWeight.Bold, fontSize = Scale.figure, color = numberColor)
    val unit = SpanStyle(fontFamily = Type.mono, fontWeight = FontWeight.Bold, fontSize = Scale.small, color = unitColor)
    return buildAnnotatedString {
        var run = StringBuilder()
        var runIsNumber = true
        fun flush() {
            if (run.isEmpty()) return
            withStyle(if (runIsNumber) number else unit) { append(run.toString()) }
            run = StringBuilder()
        }
        for (ch in value) {
            if (ch == ' ' || ch == ' ') { if (runIsNumber) { flush(); runIsNumber = false }; run.append(ch); continue }
            val isNumber = ch.isDigit() || ch in "~−-+±.,–≈<約"
            if (isNumber != runIsNumber && run.isNotEmpty()) flush()
            runIsNumber = isNumber
            run.append(ch)
        }
        flush()
    }
}

/** Ziffern in Mono fett, alles andere bleibt in der Schrift des umgebenden Textes ("50" Mono, " %" Textschrift). */
internal fun digitsInMono(text: String): AnnotatedString = buildAnnotatedString {
    val mono = SpanStyle(fontFamily = Type.mono, fontWeight = FontWeight.Bold)
    for (ch in text) if (ch.isDigit()) withStyle(mono) { append(ch) } else append(ch)
}

/**
 * Kennzahl: Wert oben, Mono-Beschriftung darunter, optional eine leise Zeile. Ein TalkBack-Stopp je Kennzahl.
 * [word] = der Wert ist ein Wort ("warm"): dann in der Textschrift und in [valueColor] statt in Mono.
 */
@Composable
internal fun Figure(
    label: String, value: String, note: String? = null, modifier: Modifier = Modifier,
    word: Boolean = false, valueColor: Color = Brand.ink, spoken: String? = null,
) {
    val a11y = listOfNotNull(label, spoken ?: value, note).joinToString(", ")
    Column(modifier.clearAndSetSemantics { contentDescription = a11y }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (word) Text(
            value, style = Type.body(Scale.figure, valueColor, FontWeight.SemiBold).copy(lineHeight = 28.sp), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = Scale.small, maxFontSize = Scale.figure),
        ) else Text(
            styledValue(value, valueColor), style = Type.data(Scale.figure).copy(lineHeight = 28.sp), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = Scale.small, maxFontSize = Scale.figure),
        )
        Text(label.caps(), style = Type.kick.copy(color = Brand.inkQuiet, letterSpacing = 0.1.em))
        note?.let { Text(it, style = Type.body(Scale.small, Brand.inkMuted)) }
    }
}

/** Kennzahlen als 2×2-Raster; bei grosser Schrift untereinander, damit nichts abgeschnitten wird. */
@Composable
internal fun FigureGrid(figures: List<@Composable (Modifier) -> Unit>) {
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        figures.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                if (row.size < columns) Box(Modifier.weight(1f))
            }
        }
    }
}

/** Art eines Hinweises: das Symbol traegt die Bedeutung, die Farbe verstaerkt sie nur. */
internal enum class NoticeKind { INFO, PAUSE, WARN, ERROR }

/**
 * Hinweis-Kasten: info (neutral), pause und warn (gold), error (coral). Ein Schutz-Stopp (Hitze, Akku) ist kein
 * Fehler und darum gold. Erscheint ohne Zutun: TalkBack sagt ihn an (WCAG 4.1.3), Fehler sofort.
 */
@Composable
internal fun NoticeBox(kind: NoticeKind, title: String, text: String? = null, modifier: Modifier = Modifier) {
    val color = when (kind) { NoticeKind.INFO -> Brand.inkMuted; NoticeKind.PAUSE, NoticeKind.WARN -> Brand.gold; NoticeKind.ERROR -> Brand.coral }
    val glyph = when (kind) { NoticeKind.INFO -> Glyph.INFO; NoticeKind.PAUSE -> Glyph.PAUSE; NoticeKind.WARN -> Glyph.WARN; NoticeKind.ERROR -> Glyph.ERROR }
    Row(
        modifier.fillMaxWidth().background(Brand.surface1, Brand.card)
            .border(1.dp, if (kind == NoticeKind.INFO) Brand.borderMid else color.copy(alpha = 0.6f), Brand.card)
            .padding(14.dp)
            .semantics(mergeDescendants = true) { liveRegion = if (kind == NoticeKind.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(glyph, color, Modifier.padding(top = 1.dp), size = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Type.body(Scale.body, Brand.ink, FontWeight.SemiBold))
            text?.let { Text(it, style = Type.body(Scale.body)) }
        }
    }
}

/** Runde Obergrenze der Skala (1500, 2000, 3000 …) mit 10 % Luft ueber [max]. */
internal fun niceCeiling(max: Float): Int {
    var magnitude = 100
    while (true) {
        // Stufen mit gerader Mitte (0 / 1500 / 3000); 10 % Luft, damit die Kurve nicht an der Oberkante klebt
        for (f in intArrayOf(10, 20, 30, 40, 50, 60, 80)) if (magnitude * f / 10 >= max * 1.1f) return magnitude * f / 10
        magnitude *= 10
    }
}

/**
 * Verlauf der Rechenleistung (lokal aus den eigenen Messwerten, kein Netz) mit leiser Skala ab 0: so sieht normales
 * Schwanken ruhig aus statt wie ein Absturz. Linie in ink-muted, Flaeche neutral (kein Gruen als Deko), nur der juengste Punkt blitzt bei
 * einer neuen Messung kurz in Signal-Gruen (kinetisch, an Daten gekoppelt). Skala rechts wie auf dem iPhone.
 */
@Composable
internal fun RateChart(
    values: List<Float>, locale: java.util.Locale, startLabel: String, nowLabel: String,
    modifier: Modifier = Modifier, description: String? = null,
) {
    val pulse = remember { Animatable(0f) }
    val still = reduceMotion()
    LaunchedEffect(values.size, values.lastOrNull()) {
        if (!still && values.isNotEmpty()) { pulse.snapTo(1f); pulse.animateTo(0f, tween(900)) }
    }
    val top = niceCeiling(values.maxOrNull()?.coerceAtLeast(1f) ?: 1000f)
    val height = 112.dp
    val inset = 8.dp
    // Achsen wachsen hoechstens auf das 1,3-Fache: bei 200 % Schrift frassen die Zahlen sonst die Kurve auf und
    // "vor 3 min" wurde abgeschnitten. Die Werte selbst liest TalkBack vor und stehen gross als Kennzahl darueber.
    val fontScale = LocalDensity.current.fontScale
    val axis = Type.data(Scale.micro * (minOf(fontScale, 1.3f) / fontScale), Brand.inkQuiet)
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { description?.let { contentDescription = it } }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val y0 = size.height - inset.toPx()
            val y1 = inset.toPx()
            for (g in 0..2) {
                val y = y0 - (y0 - y1) * g / 2f
                drawLine(Brand.borderSoft, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            if (values.isEmpty()) return@Canvas
            // Solange die 10 min nicht voll sind, nutzt die Kurve die ganze Breite (statt eines Krakels am rechten
            // Rand, Design-Review 30.10.); danach rueckt sie nach links weiter. Die juengste Messung steht rechts.
            val n = (values.size - 1).coerceIn(1, SPARK_POINTS - 1)
            fun x(i: Int) = size.width * (n - (values.size - 1 - i)).coerceAtLeast(0) / n
            fun y(v: Float) = y0 - (y0 - y1) * (v / top).coerceIn(0f, 1f)
            val end = if (values.size == 1) Offset(size.width, y(values[0])) else Offset(x(values.lastIndex), y(values.last()))
            if (values.size >= 2) {
                val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
                val area = Path().apply { addPath(line); lineTo(x(values.lastIndex), y0); lineTo(x(0), y0); close() }
                drawPath(area, Brush.verticalGradient(listOf(Brand.ink.copy(alpha = 0.10f), Brand.ink.copy(alpha = 0.02f)), startY = y1, endY = y0))
                drawPath(line, Brand.inkMuted, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            }
            val p = pulse.value
            drawCircle(lerpColor(Brand.inkMuted, Brand.greenPrimary, p), radius = (3f + 2.5f * p).dp.toPx(), center = end)
        }
        // Unter der Kurve: wie weit sie zurueckreicht, rechts "jetzt"
        Row(Modifier.clearAndSetSemantics {}) {
            Text(startLabel, style = axis, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(nowLabel, style = axis, maxLines = 1)
        }
        }
        Column(Modifier.height(height).clearAndSetSemantics {}, verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            for (g in 2 downTo 0) Text(formatRate(top * g / 2.0, locale), style = axis)
        }
    }
}

internal fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/** Der eine gefuellte gruene Knopf je Bildschirm (Kanon-Regel 1a). Gesperrt: surface-3, nie blasses Gruen. */
@Composable
internal fun PrimaryCta(text: String, modifier: Modifier = Modifier, glyph: Glyph? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = Brand.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.greenPrimary, contentColor = Brand.onGreen,
            disabledContainerColor = Brand.surface3, disabledContentColor = Brand.inkQuiet,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        glyph?.let { Icon(it, LocalContentColor.current, Modifier.padding(end = 8.dp), size = 18.dp) }
        Text(text, style = Type.body(Scale.lead, LocalContentColor.current, FontWeight.SemiBold))
    }
}

/** Zweiter Knopf: umrandet, nicht gefuellt ("Stoppen", "QR-Code scannen"). */
@Composable
internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, glyph: Glyph? = null, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = Brand.button,
        border = BorderStroke(1.dp, Brand.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Brand.surface1, contentColor = Brand.ink, disabledContentColor = Brand.inkQuiet),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        glyph?.let { Icon(it, LocalContentColor.current, Modifier.padding(end = 8.dp), size = 18.dp) }
        Text(text, style = Type.body(Scale.lead, LocalContentColor.current, FontWeight.Medium))
    }
}

/** Leiser Knopf ohne Rahmen mit voller 48-dp-Tippflaeche ("Abdunkeln", "Später eintragen"). */
@Composable
internal fun QuietAction(text: String, modifier: Modifier = Modifier, glyph: Glyph? = null, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 56.dp).clip(Brand.button).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        glyph?.let { Icon(it, Brand.inkMuted, Modifier.padding(end = 8.dp), size = 18.dp) }
        Text(text, style = Type.body(Scale.body, Brand.inkMuted, FontWeight.Medium))
    }
}

/** Symbol-Knopf (Zahnrad, Zurueck): 48 dp Tippflaeche, runde Ruheflaeche in surface-3. [label] liest TalkBack vor. */
@Composable
internal fun IconButton(glyph: Glyph, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).background(Brand.surface3, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Icon(glyph, Brand.inkMuted, size = 22.dp)
        }
    }
}

/**
 * Zeile, die weiterfuehrt: Symbol, Beschriftung leise, Wert in Mono, Pfeil ("Auszahlung an cb29…4963 ›").
 * Eigene Flaeche mit Radius card, 56 dp hoch, ganze Zeile tippbar; [a11y] ersetzt die Einzelteile fuer TalkBack.
 */
@Composable
internal fun NavRow(
    glyph: Glyph, label: String, value: String, a11y: String, modifier: Modifier = Modifier, mono: Boolean = true,
    tail: String? = null, external: Boolean = false, onClick: () -> Unit,
) {
    // Ohne eigenen Rahmen: die Zeilen stehen gruppiert in einem Panel, mit Trennlinien dazwischen
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = a11y; role = Role.Button }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(glyph, Brand.inkQuiet)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = Type.body(Scale.body))
            // Mono nur fuer Ziffernfolgen (Adresse); Woerter stehen in der Textschrift
            // Wert und Woerter dahinter ("50" Mono, "% · 2 von 4 Kernen" Textschrift) in EINEM Text mit festem
            // Leerzeichen: als getrennte Teile rutschte bei 200 % Schrift das "%" allein in die naechste Zeile
            if (value.isNotEmpty() && tail != null) Text(
                buildAnnotatedString {
                    if (mono) withStyle(SpanStyle(fontFamily = Type.mono, fontWeight = FontWeight.Normal)) { append(value) } else append(value)
                    append("\u00A0"); append(tail)
                },
                style = Type.body(Scale.body, Brand.ink, FontWeight.SemiBold),
            ) else if (value.isNotEmpty()) Text(value, style = if (mono) Type.data(Scale.body, Brand.ink) else Type.body(Scale.body, Brand.ink, FontWeight.SemiBold))
            else tail?.let { Text(it, style = Type.body(Scale.body, Brand.ink, FontWeight.SemiBold)) }
        }
        Icon(if (external) Glyph.EXTERNAL else Glyph.CHEVRON, Brand.inkQuiet, size = 16.dp)
    }
}

/** Trennstrich in der Breite eines Symbols, fuer Listen mit Symbolen. */
@Composable
internal fun Gap(width: Dp) { Box(Modifier.width(width)) }

/** Adresse kurz: "cb29…4963". */
internal fun shortAddress(address: String) = if (address.length > 10) address.take(4) + "…" + address.takeLast(4) else address

/** Laufzeit kurz: "0:08", "12:40", "1:02:03". */
internal fun elapsedShort(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}
