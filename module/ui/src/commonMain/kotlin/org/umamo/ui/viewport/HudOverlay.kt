package org.umamo.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformAxisConstraint
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.partNameByDrawable
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
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
				liveMeshOperator.kind != MeshOperatorKind.VertexSlide &&
				!session.activeMeshOperatorSuppressesProportional
		ModalOperatorBadge(
			operatorKind = liveOperator.kind,
			axisConstraint = axisConstraint,
			proportionalState = if (showProportional) proportionalState else null,
			proportionalRadius = if (showProportional) proportionalState.radiusWorld.roundToInt() else null,
			modifier = modifier,
		)
	}

	// The area-wide info chips: the top-left active-mesh label and the bottom-left zoom readout.  The
	// UV editor gets the same chips through its own assembly, UvHudOverlay below.
	ActiveMeshInfoLabel(session = session, modifier = modifier)
	ViewportZoomBadge(camera = liveCamera, modifier = modifier)
}

/**
 * The UV editor's HUD layer, the sibling assembly of [ViewportHudOverlay]: the modal-operator status
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
	ActiveMeshInfoLabel(session = session, modifier = modifier)
	ViewportZoomBadge(camera = liveCamera, modifier = modifier)
}

/** How wide each active-mesh info row may get before it ellipsizes, so one long name cannot cover the art. */
private val ACTIVE_MESH_INFO_MAX_WIDTH = 260.dp

/**
 * The top-left active-mesh info chip shared by the 2D viewport and the UV editor: which drawable
 * element clicks and operators land on.  Row 1 is "Part | Drawable" (the part omitted, pipe and all,
 * for a drawable no part owns); row 2 is the innermost parent deformer's name (the row omitted for an
 * undeformed drawable).  Edit mode annotates the edit-target mesh; Object mode the object selection's
 * active drawable; with neither resolved the chip renders nothing, so the hosts need no gate of their
 * own.  Every name is user data, rendered verbatim (never localized) and capped at
 * [ACTIVE_MESH_INFO_MAX_WIDTH] with an ellipsis - draw-only per the HUD contract, so no tooltip
 * carries the full name.
 *
 * @param EditorSession session The session whose mode, selections, and model resolve the active mesh.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
private fun ActiveMeshInfoLabel(
	session: EditorSession,
	modifier: Modifier = Modifier,
) {
	val editorMode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	val objectSelection by session.selection.collectAsState()
	val model by session.model.collectAsState()
	// Both lookups walk the whole model, so they are remembered per model instance - above the
	// resolution null-gates, so a selection change that toggles the chip never recomputes them.
	val partNameOfDrawable = remember(model) { model.partNameByDrawable() }
	val deformerNameById = remember(model) { model.deformers.associate { deformer -> deformer.id to deformer.name } }
	val activeDrawableId =
		when (editorMode) {
			EditorMode.Edit -> meshSelection.activeDrawableId
			EditorMode.Object -> (objectSelection.active as? SelectionTarget.Drawable)?.id
		} ?: return
	val activeDrawable = model.drawables.firstOrNull { drawable -> drawable.id == activeDrawableId } ?: return
	val partName = partNameOfDrawable[activeDrawable.id]
	val meshRow = if (partName != null) "$partName | ${activeDrawable.name}" else activeDrawable.name
	val deformerName = activeDrawable.parentDeformerId?.let { deformerId -> deformerNameById[deformerId] }
	val hudColors = LocalUmamoColors.current
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
		// One chip holding both rows (never two chips): one background reads as one fact about one mesh.
		Column(
			modifier =
				Modifier
					.padding(8.dp)
					.background(hudColors.viewportBadgeBackground, RoundedCornerShape(4.dp))
					.padding(horizontal = 8.dp, vertical = 3.dp),
		) {
			Text(
				text = meshRow,
				style = LocalUmamoTypography.current.labelMedium,
				color = hudColors.viewportBadgeText,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = ACTIVE_MESH_INFO_MAX_WIDTH),
			)
			if (deformerName != null) {
				Text(
					text = deformerName,
					style = LocalUmamoTypography.current.labelSmall,
					color = hudColors.viewportBadgeText,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.widthIn(max = ACTIVE_MESH_INFO_MAX_WIDTH),
				)
			}
		}
	}
}

/**
 * The bottom-left zoom readout shared by the 2D viewport and the UV editor.  Reads the LIVE service
 * camera, never the displayed frame's - the raster lands a few frames behind the wheel, and a readout
 * lagging the gesture reads as broken.  Renders nothing until the area's first fit publishes a camera.
 *
 * @param ViewportCamera? camera The area's live service camera, or null before the first fit.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
private fun ViewportZoomBadge(
	camera: ViewportCamera?,
	modifier: Modifier = Modifier,
) {
	if (camera == null) {
		return
	}
	val hudColors = LocalUmamoColors.current
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
		Text(
			text = "${(camera.zoom * 100f).roundToInt()}%",
			color = hudColors.viewportBadgeText,
			style = LocalUmamoTypography.current.labelSmall,
			modifier =
				Modifier
					.padding(8.dp)
					.background(hudColors.viewportBadgeBackground, RoundedCornerShape(4.dp))
					.padding(horizontal = 6.dp, vertical = 2.dp),
		)
	}
}

/**
 * The modal status badge (top center): the operator's name, the axis lock, and optionally the
 * proportional-editing segment - so the gesture's state reads without glancing at the status bar.
 * Shared by the two HUD assemblies, [ViewportHudOverlay] and [UvHudOverlay]; the assembly decides
 * whether the proportional segment applies and in which units the radius reads (world px in the
 * viewport, texels in UV).
 *
 * @param MeshOperatorKind operatorKind The live operator.
 * @param TransformAxisConstraint? axisConstraint The axis lock, or null when unconstrained.
 * @param ProportionalEditState? proportionalState The proportional segment's state, or null to hide it.
 * @param Int? proportionalRadius The rounded influence radius in the caller's units, or null to hide.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
private fun ModalOperatorBadge(
	operatorKind: MeshOperatorKind,
	axisConstraint: TransformAxisConstraint?,
	proportionalState: ProportionalEditState?,
	proportionalRadius: Int?,
	detail: String = "",
	modifier: Modifier = Modifier,
) {
	val hudColors = LocalUmamoColors.current
	val operatorLabel =
		when (operatorKind) {
			MeshOperatorKind.Grab -> stringResource(Res.string.status_bind_grab)
			MeshOperatorKind.Scale -> stringResource(Res.string.status_bind_scale)
			MeshOperatorKind.Rotate -> stringResource(Res.string.status_bind_rotate)
			MeshOperatorKind.VertexSlide -> stringResource(Res.string.cmd_mesh_vertex_slide)
		}
	val axisSuffix =
		when (axisConstraint) {
			TransformAxisConstraint.AxisX -> "  ${stringResource(Res.string.hud_along_x)}"
			TransformAxisConstraint.AxisZ -> "  ${stringResource(Res.string.hud_along_z)}"
			null -> ""
		}
	val proportionalSuffix =
		if (proportionalState != null && proportionalRadius != null) {
			val connectedSuffix = if (proportionalState.connectedOnly) "  ${stringResource(Res.string.hud_connected)}" else ""
			"  ${stringResource(Res.string.hud_proportional, falloffLabel(proportionalState.falloff), proportionalRadius)}$connectedSuffix"
		} else {
			""
		}
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
		Text(
			text = operatorLabel + axisSuffix + proportionalSuffix + detail,
			style = LocalUmamoTypography.current.labelMedium,
			color = hudColors.viewportBadgeText,
			modifier =
				Modifier
					.padding(top = 8.dp)
					.background(hudColors.viewportBadgeBackground, RoundedCornerShape(4.dp))
					.padding(horizontal = 8.dp, vertical = 3.dp),
		)
	}
}

/**
 * The localized short name of a proportional falloff curve, for the modal status badge and the header's
 * falloff dropdown (the palette commands carry their own longer titles).
 *
 * @param ProportionalFalloff falloff The falloff curve.
 * @return String The localized falloff name.
 */
@Composable
internal fun falloffLabel(falloff: ProportionalFalloff): String =
	when (falloff) {
		ProportionalFalloff.Smooth -> stringResource(Res.string.falloff_smooth)
		ProportionalFalloff.Sphere -> stringResource(Res.string.falloff_sphere)
		ProportionalFalloff.Root -> stringResource(Res.string.falloff_root)
		ProportionalFalloff.Sharp -> stringResource(Res.string.falloff_sharp)
		ProportionalFalloff.Linear -> stringResource(Res.string.falloff_linear)
		ProportionalFalloff.Constant -> stringResource(Res.string.falloff_constant)
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