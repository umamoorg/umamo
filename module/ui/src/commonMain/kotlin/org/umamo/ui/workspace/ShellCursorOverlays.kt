package org.umamo.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.umamo.edit.NoticePlacement
import org.umamo.ui.kit.PieMenuOverlay
import org.umamo.ui.kit.Text
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.noticeText
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
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
 * シェル直上のカーソル付近通知。エリアツリーの上に一つだけ描画され、パネル上でも正しい位置に出る。
 * 数秒後に自動的に消える。描画のみでポインタ入力は持たない。
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
	val colors = LocalUmamoColors.current
	BoxWithConstraints(modifier = modifier.fillMaxSize()) {
		// Anchor beside the pointer, falling back to the lower centre when no pointer has entered yet
		// (a keyboard-triggered notice can arrive before any pointer event).
		val anchor = pointerPosition ?: Offset(constraints.maxWidth / 2f, constraints.maxHeight * 0.75f)
		Text(
			text = noticeText(current.messageKey),
			style = LocalUmamoTypography.current.labelMedium,
			color = colors.viewportBadgeText,
			modifier =
				Modifier
					.layout { measurable, constraints ->
						val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
						layout(constraints.maxWidth, constraints.maxHeight) {
							// Below-right of the pointer, clamped fully inside the window.
							val gapPx = SHELL_POINTER_GAP.toPx()
							val clampedX = (anchor.x + gapPx).roundToInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
							val clampedY = (anchor.y + gapPx).roundToInt().coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
							placeable.place(clampedX, clampedY)
						}
					}
					.background(colors.viewportBadgeBackground, RoundedCornerShape(4.dp))
					.padding(horizontal = 8.dp, vertical = 3.dp),
		)
	}
}
