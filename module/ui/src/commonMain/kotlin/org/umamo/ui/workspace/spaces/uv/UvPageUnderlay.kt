package org.umamo.ui.workspace.spaces.uv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.viewport.RenderedFrame

/**
 * The UV editor's underlay: the area's frame as the GL engine rendered it, drawn over the whole area - or
 * the plain viewport backdrop color for the moment before the first GL frame lands.
 *
 * The frame is the whole scene: the shown surface (an atlas page or a source layer's artwork, upright,
 * correctly sampled, sharing the puppet's texture), the panel color the engine paints around it with the
 * 1 dp border just outside its edge, and the Edit-mode wireframe over both.  Nothing is clipped, so a
 * mesh off the surface stays visible over the surround and the border.
 *
 * @param RenderedFrame? rendered The displayed GL frame, or null before the first frame.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun UvPageUnderlay(
	rendered: RenderedFrame?,
	modifier: Modifier = Modifier,
) {
	if (rendered == null) {
		// Before the first frame there is no camera to draw anything through, so the area is just the
		// backdrop color the frame will arrive over - no grid of its own to swap out a moment later.
		Box(modifier = modifier.fillMaxSize().background(LocalUmamoColors.current.viewportGridBackground))
		return
	}
	Image(
		bitmap = rendered.bitmap,
		contentDescription = null,
		modifier = modifier.fillMaxSize(),
		contentScale = ContentScale.FillBounds,
	)
}