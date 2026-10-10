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
import org.umamo.ui.workspace.LocalOperationStripInset
import kotlin.math.roundToInt

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
internal fun ActiveMeshInfoLabel(
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
internal fun ViewportZoomBadge(
	camera: ViewportCamera?,
	modifier: Modifier = Modifier,
) {
	if (camera == null) {
		return
	}
	val hudColors = LocalUmamoColors.current
	// The operation settings strip owns the bottom-left while it shows; the badge lifts above it.
	val stripInset = LocalOperationStripInset.current
	Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
		Text(
			text = "${(camera.zoom * 100f).roundToInt()}%",
			color = hudColors.viewportBadgeText,
			style = LocalUmamoTypography.current.labelSmall,
			modifier =
				Modifier
					.padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + stripInset)
					.background(hudColors.viewportBadgeBackground, RoundedCornerShape(4.dp))
					.padding(horizontal = 6.dp, vertical = 2.dp),
		)
	}
}

/**
 * The modal status badge (top center): the operator's name, the axis lock, and optionally the
 * proportional-editing segment - so the gesture's state reads without glancing at the status bar.
 * Shared by the two HUD assemblies, [org.umamo.ui.viewport.viewport2d.ViewportHudOverlay] and [org.umamo.ui.viewport.uv.UvHudOverlay]; the assembly decides
 * whether the proportional segment applies and in which units the radius reads (world px in the
 * viewport, texels in UV).
 *
 * @param MeshOperatorKind operatorKind The live operator.
 * @param TransformAxisConstraint? axisConstraint The axis lock, or null when unconstrained.
 * @param ProportionalEditState? proportionalState The proportional segment's state, or null to hide it.
 * @param Int? proportionalRadius The rounded influence radius in the caller's units, or null to hide.
 * @param String detail Text the assembly appends after the segments (the placement readout), or empty.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun ModalOperatorBadge(
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
		ProportionalFalloff.InverseSquare -> stringResource(Res.string.falloff_inverse_square)
		ProportionalFalloff.Sharp -> stringResource(Res.string.falloff_sharp)
		ProportionalFalloff.Linear -> stringResource(Res.string.falloff_linear)
		ProportionalFalloff.Constant -> stringResource(Res.string.falloff_constant)
		ProportionalFalloff.Random -> stringResource(Res.string.falloff_random)
	}