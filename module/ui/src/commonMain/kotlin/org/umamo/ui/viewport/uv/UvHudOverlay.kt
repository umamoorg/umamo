package org.umamo.ui.viewport.uv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.render.ViewportCamera
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.ActiveMeshInfoLabel
import org.umamo.ui.viewport.LocalAreaOverlays
import org.umamo.ui.viewport.ModalOperatorBadge
import org.umamo.ui.viewport.ViewportZoomBadge
import kotlin.math.roundToInt

/**
 * The UV editor's HUD layer, the sibling assembly of [org.umamo.ui.viewport.viewport2d.ViewportHudOverlay]: the modal-operator status
 * badge (gated on the UV operator latch), the top-left active-mesh info chip, and the bottom-left zoom
 * readout.  Informational chrome only - the UV cursor and every transform affordance are gesture
 * controls and stay in UvEditGizmoOverlay.  Draw-only: it installs no pointer input, so the host mounts it
 * last and nothing below loses a gesture.
 *
 * The badge's proportional segment reads the UV editor's display-unit (texel) radius, not the
 * session's world radius - the host owns that state and the gizmo overlay's gesture machinery writes
 * it, so it is passed in rather than collected here.
 *
 * @param String areaId This UV editor's area id (gates the badge to the initiating area).
 * @param EditorSession session The session whose operator state and selections this HUD surfaces.
 * @param ViewportCamera? liveCamera The area's live service camera feeding the zoom readout; null before the first fit.
 * @param Float? proportionalRadiusDisplay The proportional influence radius in display (texel) units, or null when unseeded.
 * @param PlacementDragStatus? placementDragStatus The running placement gesture's readout, or null when none runs.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun UvHudOverlay(
	areaId: String,
	session: EditorSession,
	liveCamera: ViewportCamera?,
	proportionalRadiusDisplay: Float?,
	placementDragStatus: PlacementDragStatus?,
	modifier: Modifier = Modifier,
) {
	val uvOperator by session.activeUvOperator.collectAsState()
	val axisConstraint by session.axisConstraint.collectAsState()
	val proportionalEdit by session.proportionalEdit.collectAsState()
	// The modal status badge (top center): only the INITIATING area shows it - the latch itself names
	// the area, so the gate is reactive.  An Object-mode latch is a placement gesture: the badge says
	// so and carries the host-owned drag readout (the snapped delta, angle, or factor, and any overlap
	// or off-page warning), since the Object overlay that computes it is a sibling and can only reach
	// this chrome through the host.
	val badgeOperator = uvOperator?.takeIf { operator -> operator.areaId == areaId }
	if (badgeOperator != null) {
		val badgeRadius = if (proportionalEdit != null && placementDragStatus == null) proportionalRadiusDisplay?.roundToInt() else null
		ModalOperatorBadge(
			operatorKind = badgeOperator.kind,
			axisConstraint = axisConstraint,
			proportionalState = if (badgeRadius != null) proportionalEdit else null,
			proportionalRadius = badgeRadius,
			detail = placementDragStatus?.let { status -> placementBadgeDetail(status) } ?: "",
			modifier = modifier,
		)
	}
	// The info chips are what the area's General Information overlay toggle covers; the operator badge
	// above is feedback and always shows.
	if (LocalAreaOverlays.current?.effectiveInfo != false) {
		ActiveMeshInfoLabel(session = session, modifier = modifier)
		ViewportZoomBadge(camera = liveCamera, modifier = modifier)
	}
}

/**
 * The placement gesture's badge segment: the "Placement" tag, the snapped readout for the running
 * operator (pixel delta, page-space angle, or scale factor), and the overlap / off-page warning when
 * the drag currently collides.  Numbers are pre-formatted here because the resource formatter takes
 * plain placeholders only.
 *
 * @param PlacementDragStatus status The drag's live readout.
 * @return String The text appended to the operator badge.
 */
@Composable
private fun placementBadgeDetail(status: PlacementDragStatus): String {
	val readout =
		when (status.operatorKind) {
			MeshOperatorKind.Grab -> stringResource(Res.string.hud_delta_px, status.deltaX, status.deltaY)
			MeshOperatorKind.Rotate -> stringResource(Res.string.hud_angle_degrees, roundedTo(status.angleDegrees, 10))
			MeshOperatorKind.Scale ->
				if (status.factorX == status.factorY) {
					stringResource(Res.string.hud_scale_factor, roundedTo(status.factorX, 1000))
				} else {
					stringResource(Res.string.hud_scale_factor, "${roundedTo(status.factorX, 1000)}, ${roundedTo(status.factorY, 1000)}")
				}
			MeshOperatorKind.VertexSlide -> ""
		}
	val warning =
		when {
			status.overlapCount > 0 -> "  ${stringResource(Res.string.hud_overlapping)}"
			status.offPage -> "  ${stringResource(Res.string.hud_off_page)}"
			else -> ""
		}
	return "  ${stringResource(Res.string.hud_placement)}  $readout$warning"
}

/**
 * A float rounded to a fixed number of decimal steps, rendered without platform formatting.
 *
 * @param Float value The value.
 * @param Int stepsPerUnit 10 for one decimal, 1000 for three.
 * @return String The rounded value's text.
 */
private fun roundedTo(value: Float, stepsPerUnit: Int): String = ((value * stepsPerUnit).roundToInt() / stepsPerUnit.toDouble()).toString()