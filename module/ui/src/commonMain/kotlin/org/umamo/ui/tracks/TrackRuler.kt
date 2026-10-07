package org.umamo.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.field.roundToDecimals
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The ruler strip: evenly spaced domain ticks above the track lanes, with the playhead and the draggable
 * column separator.
 *
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Dp labelColumnWidth The label column's width.
 * @param Function formatTick Renders a tick value.
 * @param Dp markRadius The track region's end inset, so ticks line up with the marks below.
 */
@Composable
internal fun TrackRuler(
	axis: TrackAxis,
	playhead: Float?,
	labelColumnWidth: Dp,
	formatTick: (Float) -> String,
	markRadius: Dp,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	// Computed once per axis and shared by the tick lines and the labels: the canvas lambda re-executes on
	// every playhead frame during a scrub, and ticks() does log10/pow work plus a list build per call.
	val ticks = remember(axis) { axis.ticks() }
	Row(modifier = Modifier.fillMaxWidth().height(TRACK_RULER_HEIGHT).background(colors.headerBackground)) {
		Box(modifier = Modifier.width(labelColumnWidth))
		Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
		Box(modifier = Modifier.weight(1f).fillMaxSize().clipToBounds()) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				val inset = markRadius.toPx()
				for (tick in ticks) {
					val x = laneX(axis.fractionOf(tick), size.width, inset)
					drawLine(colors.panelBorder, Offset(x, size.height * 0.5f), Offset(x, size.height), strokeWidth = 1f)
				}
				playhead?.let { value -> drawPlayhead(value, axis, colors.accent, inset) }
			}
			// Tick labels ride above the canvas so they are not clipped by the lane below.  Each is centered
			// on the same inset laneX its tick line is drawn at - positioning by raw fraction instead would
			// drift the labels up to a mark radius off their lines and push the domain-max label entirely
			// past the clipped box's edge, hiding every ruler's max label.
			for (tick in ticks) {
				Text(
					text = formatTick(tick),
					style = typography.labelSmall,
					color = colors.textMuted,
					maxLines = 1,
					modifier = Modifier.centeredAtTick(axis.fractionOf(tick), markRadius),
				)
			}
		}
	}
}

/**
 * Centers a composable on the ruler x of [fraction] - the same inset [laneX] mapping the tick lines use -
 * clamped inward so the end labels stay fully visible inside the clipped ruler.
 *
 * @param Float fraction The 0..1 position across the domain.
 * @param Dp markRadius The track region's end inset.
 * @return Modifier The positioning modifier.
 */
private fun Modifier.centeredAtTick(fraction: Float, markRadius: Dp): Modifier =
	this.layout { measurable, constraints ->
		val placeable = measurable.measure(constraints.copy(minWidth = 0))
		val tickX = laneX(fraction, constraints.maxWidth.toFloat(), markRadius.toPx())
		val labelX =
			(tickX - placeable.width * 0.5f)
				.toInt()
				.coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
		layout(placeable.width, placeable.height) {
			placeable.placeRelative(labelX, 0)
		}
	}

/**
 * A tick label rounded to two decimals with a trailing ".0" trimmed, so an integral domain rules 10 / 20
 * rather than 10.0 / 20.0.
 *
 * Through kit's roundToDecimals, which ROUNDS: the axis generates ticks by repeated float addition, so a
 * 0.2-step tick arrives as 0.59999996 - truncating it would label "0.59" beside neighbours reading 0.2 and
 * 0.4, and would truncate negative ticks the other way, toward zero rather than away from it.
 *
 * @param Float value The tick value.
 * @return String The label.
 */
fun defaultTickLabel(value: Float): String {
	val rounded = roundToDecimals(value, 2)
	return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
}