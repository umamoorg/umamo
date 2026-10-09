package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.ActiveMeshInfoLabel
import org.umamo.ui.viewport.LocalAreaOverlays
import org.umamo.ui.viewport.ModalOperatorBadge
import org.umamo.ui.viewport.ViewportZoomBadge
import kotlin.math.roundToInt

/**
 * The viewport HUD layer: informational chrome drawn over every other overlay - the modal-operator
 * status badge (operator name plus the axis lock), the top-left active-mesh info chip, and the
 * bottom-left zoom readout.  Draw-only - it installs no pointer input, so it can sit topmost without
 * stealing gestures from the gizmo overlays.  The 2D cursor is a control marker, not HUD chrome, and
 * draws in its own sibling overlay (Cursor2dOverlay.kt); near-cursor notices live at the shell level
 * (ShellCursorOverlays.kt), where one instance escapes area bounds and follows the pointer across
 * areas without duplicating.
 *
 * The zoom readout reads the LIVE [liveCamera]: the wheel updates it immediately, where the frame
 * camera lags the raster by a few frames.  Only the INITIATING area shows the badge: the operator
 * latch names its area, so the gate is reactive.
 *
 * @param String areaId This viewport's area id (gates the badge to the initiating area).
 * @param EditorSession session The session whose operator state and selections this HUD surfaces.
 * @param ViewportCamera? liveCamera The area's live service camera feeding the zoom readout; null before the first fit.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
fun ViewportHudOverlay(
	areaId: String,
	session: EditorSession,
	liveCamera: ViewportCamera?,
	modifier: Modifier = Modifier,
) {
	val meshOperator by session.activeMeshOperator.collectAsState()
	val objectOperator by session.activeObjectOperator.collectAsState()
	val axisConstraint by session.axisConstraint.collectAsState()
	val proportionalEdit by session.proportionalEdit.collectAsState()

	// The modal status badge (top center): only the INITIATING area shows it - the latch itself names
	// the area, so the gate is reactive.  The proportional segment rides only for the operators that
	// weight it: Edit-mode G / S / R (mesh operators), never Vertex Slide (single-vertex,
	// positions-only), a suppressed latch (the duplicate / rip auto-grab), or object-mode transforms.
	val liveOperator = meshOperator ?: objectOperator
	if (liveOperator != null && liveOperator.areaId == areaId) {
		val proportionalState = proportionalEdit
		val liveMeshOperator = meshOperator
		val showProportional =
			proportionalState != null &&
				liveMeshOperator != null &&
				session.meshOperatorTakesProportional(liveMeshOperator.kind)
		ModalOperatorBadge(
			operatorKind = liveOperator.kind,
			axisConstraint = axisConstraint,
			proportionalState = if (showProportional) proportionalState else null,
			proportionalRadius = if (showProportional) proportionalState.radiusWorld.roundToInt() else null,
			modifier = modifier,
		)
	}

	// The area-wide info chips: the top-left active-mesh label and the bottom-left zoom readout.  The
	// UV editor gets the same chips through its own assembly, UvHudOverlay.  They are what the area's
	// General Information overlay toggle covers; the operator badge above is feedback and always shows.
	if (LocalAreaOverlays.current?.effectiveInfo != false) {
		ActiveMeshInfoLabel(session = session, modifier = modifier)
		ViewportZoomBadge(camera = liveCamera, modifier = modifier)
	}
}