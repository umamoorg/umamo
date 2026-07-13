package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformAxisConstraint
import org.umamo.render.ViewportCamera
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon
import kotlin.math.roundToInt

/**
 * The viewport HUD layer: viewport-wide chrome drawn over every other overlay - the 2D cursor (present
 * in both modes) and the modal-operator status badge (operator name plus the axis lock).  Draw-only -
 * it installs no pointer input, so it can sit topmost without stealing gestures from the gizmo
 * overlays.  Near-cursor notices live at the shell level (ShellCursorOverlays.kt), where one
 * instance escapes area bounds and follows the pointer across areas without duplicating.
 *
 * The cursor projects through the DISPLAYED frame's camera (like every world-anchored overlay drawing)
 * so it never swims against the raster.  Only the INITIATING area shows the badge: the operator latch
 * names its area, so the gate is reactive.
 *
 * ビューポート HUD 層。2D カーソルとモーダル操作の状態バッジを描く。描画のみで、ポインタ入力は
 * 持たない。カーソル付近の通知はシェル層に移動した。
 *
 * @param String areaId This viewport's area id (gates the badge to the initiating area).
 * @param EditorSession session The session whose cursor / operator state this HUD surfaces.
 * @param ViewportCamera? camera The displayed frame's camera (world<->screen); null skips world-anchored drawing.
 * @param Int widthPx The viewport width in px.
 * @param Int heightPx The viewport height in px.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
fun ViewportHudOverlay(
	areaId: String,
	session: EditorSession,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val cursor by session.cursor2d.collectAsState()
	val meshOperator by session.activeMeshOperator.collectAsState()
	val objectOperator by session.activeObjectOperator.collectAsState()
	val axisConstraint by session.axisConstraint.collectAsState()
	val proportionalEdit by session.proportionalEdit.collectAsState()
	val hudColors = LocalUmamoColors.current

	// The 2D cursor: the authored dashed-ring crosshair (LocalUmamoIcons.cursor2d, axis-colored arm
	// tips) at its world position, in both modes (it anchors pivots and snaps regardless of mode),
	// projected through the frame camera and drawn at a screen-constant size.
	val cursorToDraw = cursor
	if (cursorToDraw != null && camera != null) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			drawCursorMarker(
				center = worldToScreen(cursorToDraw.worldX, cursorToDraw.worldY, camera, IntSize(widthPx, heightPx)),
				tint = hudColors.viewportBadgeText,
			)
		}
	}

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
		)
	}
}

/**
 * The cursor crosshair marker (the authored dashed-ring icon) at a projected screen point, drawn at a
 * screen-constant size - shared by the viewport HUD (the world-space 2D cursor) and the UV editor's
 * overlay (the UV cursor).
 *
 * @param Offset center The marker's centre in area-local pixels.
 * @param Color tint The icon tint.
 */
internal fun DrawScope.drawCursorMarker(center: Offset, tint: Color) {
	val iconSizePx = 36.dp.toPx()
	// drawIcon fills the DrawScope's square, so shrink the bounds to an icon-sized box centered
	// on the projected point (negative insets are fine when the cursor sits near an edge).
	inset(
		left = center.x - iconSizePx / 2f,
		top = center.y - iconSizePx / 2f,
		right = size.width - center.x - iconSizePx / 2f,
		bottom = size.height - center.y - iconSizePx / 2f,
	) {
		drawIcon(LocalUmamoIcons.cursor2d, tint)
	}
}

/**
 * The modal status badge (top center): the operator's name, the axis lock, and optionally the
 * proportional-editing segment - so the gesture's state reads without glancing at the status bar.
 * Shared by the viewport HUD and the UV editor's overlay; the caller decides whether the proportional
 * segment applies and in which units the radius reads (world px in the viewport, texels in UV).
 *
 * モーダル操作の状態バッジ（上中央）。演算子名・軸ロック・プロポーショナル状態を表示する。
 *
 * @param MeshOperatorKind operatorKind The live operator.
 * @param TransformAxisConstraint? axisConstraint The axis lock, or null when unconstrained.
 * @param ProportionalEditState? proportionalState The proportional segment's state, or null to hide it.
 * @param Int? proportionalRadius The rounded influence radius in the caller's units, or null to hide.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun ModalOperatorBadge(
	operatorKind: MeshOperatorKind,
	axisConstraint: TransformAxisConstraint?,
	proportionalState: ProportionalEditState?,
	proportionalRadius: Int?,
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
			text = operatorLabel + axisSuffix + proportionalSuffix,
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
