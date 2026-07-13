package org.umamo.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes

/** The panel-elevation margin between the area edge and the framed viewport content. */
private val VIEWPORT_FRAME_INSET = 4.dp

/**
 * Frames a GPU viewport surface in the panel elevation: a panelBackground margin around the content plus
 * a 1.dp panelBorder rounded frame, with the content clipped to that rounded shape.  This is the
 * "Panel Elevation -> Border -> content" framing the UV editor (and, later, the 2D viewport) sits in, so
 * the grid / texture raster is visibly framed rather than running to the area edge.
 *
 * The [content] is measured at the FRAMED size (inside the inset) because it receives the inner box's
 * BoxWithConstraints scope: a viewport that reports its pixel size to a render service therefore resizes
 * to the framed region, not the whole area, and its overlays align to that raster.
 *
 * パネル段差の枠にビューポートを収める。内側の余白と 1.dp の角丸枠、角丸クリップ。content は枠内寸法で測る。
 *
 * @param Modifier modifier The layout modifier for the outer (full-area) box.
 * @param Function content The framed viewport body, receiving the framed constraints.
 */
@Composable
internal fun FramedViewport(
	modifier: Modifier = Modifier,
	content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Box(modifier = modifier.fillMaxSize().background(colors.panelBackground)) {
		BoxWithConstraints(
			modifier =
				Modifier
					.fillMaxSize()
					.padding(VIEWPORT_FRAME_INSET)
					.clip(shapes.medium)
					.border(1.dp, colors.panelBorder, shapes.medium),
			content = content,
		)
	}
}
