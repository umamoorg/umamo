package org.umamo.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.NoticePlacement
import org.umamo.edit.SelectionTarget
import org.umamo.ui.kit.PieMenuOverlay
import org.umamo.ui.kit.TooltipCard
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.noticeText
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.pick_hover_deformer
import org.umamo.ui.resources.pick_hover_drawable
import org.umamo.ui.resources.pick_hover_part
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.drawCursor
import org.umamo.ui.viewport.pieMenuEntriesFor
import org.umamo.ui.viewport.pieMenuTitleFor
import kotlin.math.roundToInt

/** How long (ms) a near-cursor notice stays visible before it auto-dismisses. */
private const val SHELL_NOTICE_VISIBLE_MS = 3000L

/** Gap (dp) between the pointer and the notice badge, so the badge never sits under the cursor. */
private val SHELL_POINTER_GAP = 14.dp

/**
 * Observes every pointer event in the Initial pass without consuming, reporting the latest position.
 * Installed on the shell's root surface (an ancestor of every area), so it sees the pointer no matter
 * which panel or overlay owns the gesture - consumption is advisory and never hides events from an
 * Initial-pass ancestor, so this keeps tracking even while an open pie swallows input.  The reported
 * positions anchor the shell-level cursor overlays (the pie menu and near-cursor notices).
 *
 * ポインタ位置の観測。Initial パスで消費せずに監視するため、どのオーバーレイがジェスチャを
 * 所有していても最新位置が得られる。シェル直上のカーソル追従オーバーレイのアンカーになる。
 *
 * @param Function onPointer Receives each event's latest position in shell-root pixels.
 */
internal suspend fun PointerInputScope.observeWindowPointer(onPointer: (Offset) -> Unit) {
	awaitPointerEventScope {
		while (true) {
			val event = awaitPointerEvent(PointerEventPass.Initial)
			event.changes.lastOrNull()?.let { change -> onPointer(change.position) }
		}
	}
}

/**
 * The shell-level radial pie menu host: observes the open document's pie latch and renders the entry
 * ring at the window-space pointer position, frozen at open (the pie opens under the hand,
 * Blender-style).  Living ABOVE the area tree - not inside a viewport's clipped box - is what lets
 * the ring escape area bounds, and being the only instance structurally prevents any cross-viewport
 * duplication (a per-viewport copy could never be gated consistently).
 * [PieMenuOverlay] clamps the centre to the window so the ring never clips (Blender's
 * constrain-to-screen); entries dispatch through the command registry, and opening / closing go
 * through the session so the shell's key ladder routes Escape and the 1..N digits unchanged.
 *
 * シェル直上のパイメニューホスト。エリアツリーの上に一つだけ描画されるため、ビューポート境界を
 * はみ出せて、複数ビューポートでの重複表示も構造的に起きない。中心はウィンドウ内にクランプされる。
 *
 * @param Offset? pointerPosition The last pointer position in shell-root pixels, or null before any
 *   pointer event (a keyboard-opened pie then centres in the window).
 * @param Modifier modifier The layout modifier (the shell passes a window fill).
 */
@Composable
internal fun ShellPieMenuHost(pointerPosition: Offset?, modifier: Modifier = Modifier) {
	val session = LocalEditorSession.current ?: return
	val activePie by session.activePieMenu.collectAsState()
	val kind = activePie ?: return
	val entries = pieMenuEntriesFor(kind)
	if (entries.isEmpty()) {
		return
	}
	BoxWithConstraints(modifier = modifier.fillMaxSize()) {
		// The anchor freezes at open (keyed on the pie kind): passing the live pointer would drag the
		// whole ring - labels and all - around with the mouse instead of letting the pointer travel
		// OVER a stationary pie to pick by direction.
		val fallbackCenter = Offset(constraints.maxWidth / 2f, constraints.maxHeight / 2f)
		val frozenCenter = remember(kind) { pointerPosition ?: fallbackCenter }
		PieMenuOverlay(
			entries = entries,
			center = frozenCenter,
			onDismiss = { session.closePieMenu() },
			title = pieMenuTitleFor(kind),
		)
	}
}

/**
 * The shell-level near-cursor notice: transient [NoticePlacement.NearCursor] notices (Blender's
 * "can't do this because" style) beside the live window-space pointer, auto-dismissed after a few
 * seconds.  One instance above the area tree replaces the per-viewport copies, so the badge follows
 * the pointer across areas without duplicating, escapes area bounds, and appears at the true pointer
 * even when it hovers a non-viewport panel (the per-area version rendered in whichever viewport the
 * pointer last touched).  Draw-only - installs no pointer input.
 *
 * @param Offset? pointerPosition The last pointer position in shell-root pixels, or null before any
 *   pointer event (the notice then anchors at the window's lower centre).
 * @param Modifier modifier The layout modifier (the shell passes a window fill).
 */
@Composable
internal fun ShellNearCursorNotice(pointerPosition: Offset?, modifier: Modifier = Modifier) {
	val session = LocalEditorSession.current ?: return
	val notice by session.notice.collectAsState()
	val current = notice ?: return
	if (current.placement != NoticePlacement.NearCursor) {
		return
	}
	// Auto-dismiss after the visible window; keyed on the serial so a repeated notice restarts the
	// timer, and clearNotice only clears if a newer notice has not replaced this one meanwhile.
	LaunchedEffect(current.serial) {
		delay(SHELL_NOTICE_VISIBLE_MS)
		session.clearNotice(current.serial)
	}
	BoxWithConstraints(modifier = modifier.fillMaxSize()) {
		// Anchor beside the pointer, falling back to the lower centre when no pointer has entered yet
		// (a keyboard-triggered notice can arrive before any pointer event).
		val anchor = pointerPosition ?: Offset(constraints.maxWidth / 2f, constraints.maxHeight * 0.75f)
		TooltipCard(
			text = noticeText(current.messageKey),
			modifier = Modifier.nearPointer(anchor, SHELL_POINTER_GAP),
		)
	}
}

/**
 * Places this node just below-right of [anchor], clamped fully inside the parent, while still measuring
 * against the whole window.  The placement every shell cursor overlay shares - a near-cursor notice and
 * the relation-pick badge differ only in their gap, not in how they are pinned.
 *
 * @param Offset anchor The pointer position in shell-root pixels.
 * @param Dp gap The offset from the pointer, clearing whatever is drawn at it.
 * @return Modifier The positioning modifier.
 */
private fun Modifier.nearPointer(anchor: Offset, gap: Dp): Modifier =
	layout { measurable, constraints ->
		val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
		layout(constraints.maxWidth, constraints.maxHeight) {
			val gapPx = gap.toPx()
			val clampedX = (anchor.x + gapPx).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
			val clampedY = (anchor.y + gapPx).roundToInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
			placeable.place(clampedX, clampedY)
		}
	}

/**
 * The shell-level relation-pick overlay: while a Properties field's eyedropper is armed, this draws the
 * eyedropper cursor at the pointer and a badge naming what a click would bind ("Drawable: Hair Front").
 *
 * It lives ABOVE the area tree for the same reason the pie menu does - the cursor must be visible the
 * instant the pick is armed, over panels and the outliner too, not only inside a viewport's clipped box.
 * The resolving surfaces publish what is under the pointer ([RelationPickController.hover]); this only
 * names it, so the badge reads identically whether the pointer is over the viewport or an outliner row.
 * Installs no pointer input, so it never steals a gesture from the surfaces below.
 *
 * シェル直上のリレーション選択重畳。武装中はポインタ位置にスポイトカーソルと取得対象名のバッジを描く。
 *
 * @param Offset? pointerPosition The last pointer position in shell-root pixels, or null before any event.
 * @param Modifier modifier The layout modifier (the shell passes a window fill).
 */
@Composable
internal fun ShellRelationPickOverlay(pointerPosition: Offset?, modifier: Modifier = Modifier) {
	val picker = LocalRelationPick.current
	picker.request ?: return
	val anchor = pointerPosition ?: return

	BoxWithConstraints(modifier = modifier.fillMaxSize()) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			drawCursor(LocalUmamoCursors.eyedropper, anchor)
		}
		// The kit's tooltip card, positioned at the cursor rather than hover-anchored to a control - the
		// chrome stays owned by Tooltip.kt so this label can never drift from every other tooltip.
		if (picker.hoveredTarget != null) {
			TooltipCard(
				text = relationPickLabel(picker.hoveredTarget),
				modifier = Modifier.nearPointer(anchor, SHELL_POINTER_GAP),
			)
		}
	}
}

/**
 * The badge text for what a pick would bind: the entity's kind and name, or the "nothing under the pointer"
 * prompt.  Names come from the open document, falling back to the raw id for an unnamed entity.
 *
 * @param SelectionTarget? target The entity under the pointer, or null.
 * @return String The localized badge label.
 */
@Composable
private fun relationPickLabel(target: SelectionTarget?): String {
	val puppet = LocalPuppet.current
	if (target == null || puppet == null) {
		return ""
	}
	return when (target) {
		is SelectionTarget.Drawable ->
			stringResource(
				Res.string.pick_hover_drawable,
				puppet.drawables.firstOrNull { it.id == target.id }?.name?.ifBlank { target.id.raw } ?: target.id.raw,
			)

		is SelectionTarget.Part ->
			stringResource(
				Res.string.pick_hover_part,
				puppet.parts.firstOrNull { it.id == target.id }?.name?.ifBlank { target.id.raw } ?: target.id.raw,
			)

		is SelectionTarget.Deformer ->
			stringResource(
				Res.string.pick_hover_deformer,
				puppet.deformers.firstOrNull { it.id == target.id }?.name?.ifBlank { target.id.raw } ?: target.id.raw,
			)
	}
}
