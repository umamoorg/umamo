package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Text
import org.umamo.ui.properties.MutedNote
import org.umamo.ui.properties.TexelPositionFields
import org.umamo.ui.properties.rememberFieldScrubPreview
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.viewport.uv.LocalUvSidebarSurface
import org.umamo.ui.viewport.uv.UvMedianMove
import org.umamo.ui.viewport.uv.moveUvMedianAdjustable
import org.umamo.ui.viewport.uv.previewUvMedianMove
import org.umamo.ui.viewport.uv.uvCoveredMedian
import org.umamo.ui.viewport.uv.uvCoveredVertices
import org.umamo.ui.viewport.uv.uvMedianMove

/**
 * The move the panel's writes drive, captured at the first write and kept until a commit: every frame of a scrub
 * drives the same capture, though each frame's preview rebuilds the surface the readout comes from.
 *
 * @param Function capture Builds the move from the committed coordinates, or null when nothing is covered.
 */
private class UvMedianWrites(private val capture: () -> UvMedianMove?) {
	private var captured: UvMedianMove? = null

	/**
	 * The move, captured on first use.
	 *
	 * @return UvMedianMove? The move, or null when nothing is covered.
	 */
	fun move(): UvMedianMove? = captured ?: capture().also { move -> captured = move }
}

/**
 * The UV editor's Item tab in Edit mode: where the covered texture coordinates sit on the shown page or source
 * artwork, "Vertex:" for one and "Median:" for several, their mean across every shown mesh in the edit, in texels
 * from the surface's top-left with y down.  A typed or scrubbed value moves them all together as the UV editor's
 * Grab would, one undo step the operation strip can adjust, and while a UV transform runs, here or in another UV
 * editor, the numbers follow it.  With nothing covered it says so.
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
internal fun UvMedianPanel(context: SidebarContext) {
	val session = context.session ?: return
	val surface = LocalUvSidebarSurface.current ?: return
	val selection = context.meshSelection
	// The readout comes from the surface as it looks now, a drag's preview included, so it follows the drag.
	val median = remember(surface.geometries, selection) { uvCoveredMedian(uvCoveredVertices(selection, surface.geometries)) }

	if (median == null) {
		MutedNote(Res.string.sidebar_item_nothing_selected)

		return
	}

	val frame = surface.frame
	val liveSurface = rememberUpdatedState(surface)
	// Keyed on the committed model, never on the surface: the panel's own scrub previews through the render sync,
	// which rebuilds the surface every frame, and a capture taken from a preview would measure its move from there.
	val writes =
		remember(context.puppet, selection, frame) {
			UvMedianWrites {
				uvMedianMove(uvCoveredVertices(selection, liveSurface.value.geometries), frame, liveSurface.value.radiusState)
			}
		}
	val scrub = rememberFieldScrubPreview(enabled = true)
	val areaId = context.areaId
	Text(
		text = stringResource(if (median.vertexCount == 1) Res.string.sidebar_edit_vertex else Res.string.sidebar_edit_median),
		style = LocalUmamoTypography.current.labelMedium,
		color = LocalUmamoColors.current.text,
	)
	TexelPositionFields(
		texelX = median.displayX,
		texelY = frame.displayYOfTexelY(median.displayY),
		xDescription = Res.string.sidebar_uv_median_x_description,
		yDescription = Res.string.sidebar_uv_median_y_description,
		// A typed value, a chevron step, or a scrub's release puts the median's axis coordinate there.
		onCommit = { axis, texelValue ->
			scrub.commit { writes.move()?.let { move -> session.moveUvMedianAdjustable(move, axis, texelValue, areaId) } }
		},
		onPreview = { axis, texelValue ->
			scrub.preview(writes.move()?.let { move -> session.previewUvMedianMove(move, axis, texelValue) })
		},
		onScrubCancel = { scrub.end() },
	)
}