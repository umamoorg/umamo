package org.umamo.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import org.umamo.ui.theme.LocalUmamoColors

/** How long the indeterminate bar's segment takes to cross the track once, in milliseconds. */
private const val INDETERMINATE_CROSSING_MS = 1200

/** The indeterminate bar's segment, as a fraction of the track. */
private const val INDETERMINATE_SEGMENT = 0.3f

/**
 * A thin progress bar: the track with the done fraction filled from the leading edge, or, when the work
 * cannot say how far along it is, a segment sweeping across the track.  Size it with [modifier].
 *
 * @param Float?   fraction How much is done, 0..1, or null for work that cannot say.
 * @param Modifier modifier The layout modifier; the bar fills it.
 */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	val track = colors.sliderTrack
	val fill = colors.accent
	val semantic =
		modifier.semantics {
			progressBarRangeInfo = if (fraction == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
		}

	if (fraction != null) {
		Canvas(modifier = semantic) {
			val radius = CornerRadius(size.height / 2f)
			drawRoundRect(track, cornerRadius = radius)
			drawRoundRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
		}

		return
	}

	val sweep by rememberInfiniteTransition(label = "progress").animateFloat(
		initialValue = -INDETERMINATE_SEGMENT,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(INDETERMINATE_CROSSING_MS, easing = LinearEasing), RepeatMode.Restart),
		label = "progress sweep",
	)
	Canvas(modifier = semantic) {
		val radius = CornerRadius(size.height / 2f)
		drawRoundRect(track, cornerRadius = radius)
		val start = (sweep * size.width).coerceAtLeast(0f)
		val end = ((sweep + INDETERMINATE_SEGMENT) * size.width).coerceAtMost(size.width)

		if (end > start) {
			drawRoundRect(fill, topLeft = Offset(start, 0f), size = Size(end - start, size.height), cornerRadius = radius)
		}
	}
}