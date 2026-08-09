package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.viewport.RenderedFrame
import org.umamo.ui.viewport.worldToScreen

/**
 * The UV editor's page underlay: the atlas page rendered by the GL engine (upright, correctly
 * sampled, sharing the puppet's texture), clipped to the page's on-screen rectangle with a 1.dp
 * frame drawn around it - or the themed grid placeholder until the first GL frame lands.
 *
 * The page rectangle is the full UV tile (display space [0, 0]-[pageWidth, pageHeight]) projected
 * through the FRAME's camera, so it tracks pan / zoom glued to the rendered texture.  The grid +
 * texture raster is clipped to it so the grid does not spill past the texture onto the panel
 * elevation; the wireframe overlays above are deliberately unclipped, so UVs outside the tile stay
 * visible.
 *
 * @param RenderedFrame? rendered The displayed GL frame, or null before the first frame.
 * @param Int pageWidth The shown page's width in texels.
 * @param Int pageHeight The shown page's height in texels.
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun UvPageUnderlay(
	rendered: RenderedFrame?,
	pageWidth: Int,
	pageHeight: Int,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	if (rendered == null) {
		// Pre-first-frame placeholder: the themed grid backdrop until the GL frame lands and the
		// framed texture takes over.
		EmptyViewportBackdrop()
		return
	}
	val uiColors = LocalUmamoColors.current
	val cornerLowerLeft = worldToScreen(0f, 0f, rendered.camera, IntSize(widthPx, heightPx))
	val cornerUpperRight = worldToScreen(pageWidth.toFloat(), pageHeight.toFloat(), rendered.camera, IntSize(widthPx, heightPx))
	val textureRect =
		Rect(
			left = minOf(cornerLowerLeft.x, cornerUpperRight.x),
			top = minOf(cornerLowerLeft.y, cornerUpperRight.y),
			right = maxOf(cornerLowerLeft.x, cornerUpperRight.x),
			bottom = maxOf(cornerLowerLeft.y, cornerUpperRight.y),
		)
	Image(
		bitmap = rendered.bitmap,
		contentDescription = null,
		modifier =
			modifier.fillMaxSize().drawWithContent {
				clipRect(textureRect.left, textureRect.top, textureRect.right, textureRect.bottom) { this@drawWithContent.drawContent() }
			},
		contentScale = ContentScale.FillBounds,
	)
	// The 1.dp frame around the texture (the page-elevation border of the diagram).
	Canvas(modifier = modifier.fillMaxSize()) {
		drawRect(
			color = uiColors.panelBorder,
			topLeft = Offset(textureRect.left, textureRect.top),
			size = Size(textureRect.width, textureRect.height),
			style = Stroke(width = 1.dp.toPx()),
		)
	}
}