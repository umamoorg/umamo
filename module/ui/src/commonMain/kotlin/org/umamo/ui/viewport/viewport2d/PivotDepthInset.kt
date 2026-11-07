package org.umamo.ui.viewport.viewport2d

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.render.WorldAxisColors
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import kotlin.math.roundToInt

// The floor picture's size inside the badge.
private val INSET_WIDTH = 160.dp
private val INSET_HEIGHT = 80.dp

// How long the pivot glides to a new depth, in milliseconds.
private const val DEPTH_GLIDE_MS = 120

// The depth line's dash pattern (on, off) and the pivot dot's radius.
private val DEPTH_DASH_ON = 4.dp
private val DEPTH_DASH_OFF = 3.dp
private val PIVOT_DOT_RADIUS = 3.dp

// The depth axis's color, from the world axes' own palette like the axis-lock guides.
private val DEPTH_AXIS_COLOR = Color(WorldAxisColors.Classic.yRed, WorldAxisColors.Classic.yGreen, WorldAxisColors.Classic.yBlue)

/**
 * The depth rotation's inset (bottom right): the floor grid receding into the screen, the art's plane across it, and the
 * pivot pushed into it along a dashed depth line, over the depth readout.  Shown while the area's Rotate turns in
 * depth about a locked axis.  Draw-only - it takes no pointer input, so the area's corner stays reachable.
 *
 * The pivot glides to each new depth in the DRAW phase (the animation is read inside the canvas), so a wheel step
 * recomposes the readout once and the glide never recomposes anything.
 *
 * @param Float pivotDepth The pivot's depth along world Y, in world px, positive into the screen.
 * @param Float gridScale The area grid's major spacing, in world px, the floor's line spacing.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun PivotDepthInset(
	pivotDepth: Float,
	gridScale: Float,
	modifier: Modifier = Modifier,
) {
	val hudColors = LocalUmamoColors.current
	val readout = stringResource(Res.string.hud_pivot_depth, pivotDepth.roundToInt())
	val shownDepth = remember { Animatable(pivotDepth) }

	LaunchedEffect(pivotDepth) {
		shownDepth.animateTo(pivotDepth, tween(DEPTH_GLIDE_MS))
	}

	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			modifier =
				Modifier
					.padding(8.dp)
					.background(hudColors.viewportBadgeBackground, RoundedCornerShape(4.dp))
					.padding(6.dp)
					// Named for accessibility and for tests: the canvas is otherwise invisible to the semantics tree.
					.semantics(mergeDescendants = true) { contentDescription = readout },
		) {
			Canvas(modifier = Modifier.size(INSET_WIDTH, INSET_HEIGHT).clipToBounds()) {
				val geometry = pivotDepthInsetGeometry(shownDepth.value, gridScale, size.width, size.height)

				for (line in geometry.gridLines) {
					drawLine(color = hudColors.viewportGridLineMajor, start = line.start, end = line.end, strokeWidth = 1f)
				}

				drawLine(color = hudColors.viewportGridLineMinor, start = Offset(0f, geometry.horizonY), end = Offset(size.width, geometry.horizonY), strokeWidth = 1f)
				drawLine(color = hudColors.viewportMarquee, start = geometry.artPlane.start, end = geometry.artPlane.end, strokeWidth = 2f)
				drawLine(
					color = DEPTH_AXIS_COLOR,
					start = geometry.artCenter,
					end = geometry.pivot,
					strokeWidth = 1.5f,
					pathEffect = PathEffect.dashPathEffect(floatArrayOf(DEPTH_DASH_ON.toPx(), DEPTH_DASH_OFF.toPx())),
				)
				drawCircle(color = DEPTH_AXIS_COLOR, radius = PIVOT_DOT_RADIUS.toPx(), center = geometry.pivot)
			}

			Text(
				text = readout,
				color = hudColors.viewportBadgeText,
				style = LocalUmamoTypography.current.labelSmall,
				modifier = Modifier.padding(top = 2.dp),
			)
		}
	}
}