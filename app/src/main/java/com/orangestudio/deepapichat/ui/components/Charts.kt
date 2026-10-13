package com.orangestudio.deepapichat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.orangestudio.deepapichat.core.AppJson
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A chart the model asked for, drawn from data rather than from an image.
 *
 * The model emits a fenced `chart` block whose body is JSON. Two shapes are
 * accepted, because both are natural things for a model to write: parallel
 * `labels` and `values` arrays, or a list of `{label, value}` points. Whichever
 * arrives, [points] is the single form the renderer draws.
 */
@Serializable
data class ChartSpec(
    val type: String = "bar",
    val title: String = "",
    val labels: List<String> = emptyList(),
    val values: List<Double> = emptyList(),
    val data: List<ChartPoint> = emptyList(),
) {
    enum class Kind { Bar, Line, Pie }

    val kind: Kind
        get() = when (type.trim().lowercase()) {
            "line", "lines", "curve", "area" -> Kind.Line
            "pie", "donut", "doughnut" -> Kind.Pie
            else -> Kind.Bar
        }

    val points: List<ChartPoint>
        get() = when {
            data.isNotEmpty() -> data
            labels.isNotEmpty() && labels.size == values.size ->
                labels.indices.map { ChartPoint(labels[it], values[it]) }
            else -> emptyList()
        }

    /** A pie of negatives and a bar of nothing are both undrawable. */
    val drawable: Boolean
        get() = when (kind) {
            Kind.Pie -> points.count { it.value > 0.0 } >= 1
            else -> points.isNotEmpty()
        }
}

@Serializable
data class ChartPoint(val label: String = "", val value: Double = 0.0)

/** Fence labels that mean "draw this". */
private val CHART_FENCES = setOf("chart", "graph", "plot")

fun isChartFence(tag: String): Boolean = tag.trim().lowercase() in CHART_FENCES

/**
 * Parses a chart fence body. Returns null for anything we cannot draw, and the
 * caller then shows the fence as ordinary code: a chart we failed to understand
 * must still be readable, never silently swallowed.
 */
fun parseChart(body: String): ChartSpec? {
    val text = body.trim().removePrefix("```").removeSuffix("```").trim()
    if (text.isEmpty()) return null
    val spec = runCatching {
        AppJson.compact.decodeFromString(ChartSpec.serializer(), text)
    }.getOrNull() ?: return null
    return spec.takeIf { it.drawable }
}

@Composable
internal fun ChartBlock(spec: ChartSpec, styles: MdStyles, style: TextStyle) {
    val outline = RoundedCornerShape(8.dp)
    val measurer = rememberTextMeasurer()
    val axisStyle = style.copy(
        fontSize = style.fontSize * 0.78f,
        color = styles.muted,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outline)
            .border(1.dp, styles.border, outline)
            .padding(12.dp),
    ) {
        if (spec.title.isNotBlank()) {
            Text(
                text = spec.title,
                style = style.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (spec.kind == ChartSpec.Kind.Pie) {
            val slices = spec.points.filter { it.value > 0.0 }
            Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                drawPie(slices, measurer, axisStyle)
            }
            Spacer(Modifier.height(8.dp))
            PieLegend(slices, styles, axisStyle)
        } else {
            Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                drawPlot(spec, measurer, axisStyle, styles)
            }
        }
    }
}

@Composable
private fun PieLegend(slices: List<ChartPoint>, styles: MdStyles, textStyle: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        slices.forEachIndexed { index, point ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(sliceColour(styles, index)),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "${point.label}  ${trimNumber(point.value)}", style = textStyle)
            }
        }
    }
}

/** Bars and lines share one value axis, so zero is placed once for both. */
private fun DrawScope.drawPlot(
    spec: ChartSpec,
    measurer: TextMeasurer,
    axisStyle: TextStyle,
    styles: MdStyles,
) {
    val points = spec.points
    if (points.isEmpty()) return

    val bottomPad = 20.dp.toPx()
    val topPad = 14.dp.toPx()
    val plotHeight = (size.height - bottomPad - topPad).coerceAtLeast(1f)

    // A value axis that includes zero, so bars grow from a real baseline and
    // negative values point the other way instead of being clipped away.
    val lo = min(0.0, points.minOf { it.value })
    val hi = max(0.0, points.maxOf { it.value })
    val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0

    fun yFor(value: Double): Float = topPad + plotHeight * (1.0 - (value - lo) / span).toFloat()

    val zeroY = yFor(0.0)
    drawLine(
        color = styles.border,
        start = Offset(0f, zeroY),
        end = Offset(size.width, zeroY),
        strokeWidth = 1.dp.toPx(),
    )

    val slot = size.width / points.size
    val labelY = size.height - bottomPad + 2.dp.toPx()

    when (spec.kind) {
        ChartSpec.Kind.Line -> {
            val centres = points.mapIndexed { index, _ -> slot * index + slot / 2f }
            for (index in 0 until points.size - 1) {
                drawLine(
                    color = styles.accent,
                    start = Offset(centres[index], yFor(points[index].value)),
                    end = Offset(centres[index + 1], yFor(points[index + 1].value)),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            points.forEachIndexed { index, point ->
                val centre = Offset(centres[index], yFor(point.value))
                drawCircle(color = styles.accent, radius = 3.5.dp.toPx(), center = centre)
                drawCircle(
                    color = styles.codeSurface,
                    radius = 1.5.dp.toPx(),
                    center = centre,
                )
            }
        }

        else -> {
            val barWidth = (slot * 0.6f).coerceAtMost(46.dp.toPx())
            points.forEachIndexed { index, point ->
                val left = slot * index + (slot - barWidth) / 2f
                val top = min(yFor(point.value), zeroY)
                val height = abs(zeroY - yFor(point.value)).coerceAtLeast(1.dp.toPx())
                drawRoundRect(
                    color = styles.accent,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                val valueText = measurer.measure(trimNumber(point.value), axisStyle)
                drawText(
                    textLayoutResult = valueText,
                    topLeft = Offset(
                        left + (barWidth - valueText.size.width) / 2f,
                        top - valueText.size.height - 2.dp.toPx(),
                    ),
                )
            }
        }
    }

    // Category labels, centred under each slot and squeezed to it so a long name
    // cannot run into its neighbour.
    points.forEachIndexed { index, point ->
        if (point.label.isBlank()) return@forEachIndexed
        val laid = measurer.measure(point.label, axisStyle)
        val room = (slot - 4.dp.toPx()).coerceAtLeast(1f)
        val squeeze = (room / laid.size.width).coerceAtMost(1f)
        val x = slot * index + (slot - laid.size.width * squeeze) / 2f
        withTransform({ scale(squeeze, 1f, pivot = Offset(x, labelY)) }) {
            drawText(textLayoutResult = laid, topLeft = Offset(x, labelY))
        }
    }
}

private fun DrawScope.drawPie(
    slices: List<ChartPoint>,
    measurer: TextMeasurer,
    axisStyle: TextStyle,
) {
    val total = slices.sumOf { it.value }
    if (total <= 0.0) return
    val diameter = min(size.width, size.height)
    val topLeft = Offset((size.width - diameter) / 2f, 0f)
    val arcSize = Size(diameter, diameter)
    var start = -90f
    slices.forEachIndexed { index, point ->
        val sweep = (point.value / total * 360.0).toFloat()
        drawArc(
            color = sliceColours[index % sliceColours.size],
            startAngle = start,
            sweepAngle = (sweep - 1.5f).coerceAtLeast(0.5f),
            useCenter = true,
            topLeft = topLeft,
            size = arcSize,
        )
        start += sweep
    }
    // The largest slice gets its share written on the ring.
    if (slices.size > 1) {
        val biggest = slices.maxBy { it.value }
        val share = "${(biggest.value / total * 100).toInt()}%"
        val laid = measurer.measure(share, axisStyle)
        drawText(
            textLayoutResult = laid,
            topLeft = Offset((size.width - laid.size.width) / 2f, diameter / 2f - laid.size.height / 2f),
        )
    }
}

private val sliceColours = listOf(
    Color(0xFF4C8DFF),
    Color(0xFFFF8A4C),
    Color(0xFF41C7A0),
    Color(0xFFE06C9F),
    Color(0xFFB48CFF),
    Color(0xFFE8C34A),
    Color(0xFF6FD3E8),
    Color(0xFF9CCB5A),
)

private fun sliceColour(styles: MdStyles, index: Int): Color =
    if (index == 0) styles.accent else sliceColours[index % sliceColours.size]

/** 12.0 reads better as 12, and 12.34 keeps two decimals. */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
