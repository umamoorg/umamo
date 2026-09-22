package org.umamo.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Longest gap between two presses that still counts as a double click.  One fixed window for every caller
 * and platform, rather than each gesture reading its own view configuration, so a double click feels the
 * same in every panel and the timing core stays free of composition.
 */
private const val DOUBLE_CLICK_MILLIS = 300L

/**
 * The press-to-press timing behind every double click in the editor.  Pure state with no Compose types:
 * [singleOrDoubleClick] runs on it, and a gesture that cannot use that modifier (the workspace tabs, whose
 * double click shares a gesture with a reorder drag) calls it from its own pointer loop.
 *
 * Only two consecutive unmodified presses pair.  A modified press (one carrying a selection modifier) is
 * its own action, such as toggling a row in or out of the selection, so it never completes a double click
 * and never arms one.
 *
 * @param Long windowMillis The double-click window in milliseconds, inclusive.
 */
class DoubleClickTracker(private val windowMillis: Long = DOUBLE_CLICK_MILLIS) {
	/** The uptime of the press waiting for its second half, or null when none is pending. */
	private var pendingPressUptime: Long? = null

	/**
	 * Records a press and reports whether it completes a double click.  A completed double clears the
	 * pending press, so a third quick press starts a fresh single rather than a second double.
	 *
	 * @param Long    uptimeMillis The press's event time.
	 * @param Boolean modified     Whether the press carries a selection modifier.
	 * @return Boolean True when this press is the second half of a double click.
	 */
	fun registerPress(uptimeMillis: Long, modified: Boolean = false): Boolean {
		val pending = pendingPressUptime
		if (modified) {
			pendingPressUptime = null
			return false
		}
		if (pending != null && uptimeMillis - pending <= windowMillis) {
			pendingPressUptime = null
			return true
		}
		pendingPressUptime = uptimeMillis
		return false
	}

	/**
	 * Forgets the pending press, so the next press starts fresh.  For a press that turned into something
	 * other than a click, such as a drag.
	 */
	fun reset() {
		pendingPressUptime = null
	}
}

/**
 * A single- and double-click detector that fires the single-click action IMMEDIATELY on press, with no
 * wait for a possible second click.  `Modifier.clickable` and `detectTapGestures(onDoubleTap = …)` both
 * defer the single click by the platform double-tap window to disambiguate it; this does not.  It
 * dispatches [onSingle] on a primary press and, if the press is the second half of a double click (see
 * [DoubleClickTracker]), dispatches [onDouble] instead.  So a double click runs the single action once and
 * then the double action; callers rely on the double action replacing what the single one showed (e.g. an
 * inline rename field taking over a row).
 *
 * [onSingle] receives the press's keyboard modifiers.  A press holding Ctrl, Meta, or Shift, the selection
 * modifiers, always goes to [onSingle] and never takes part in a double click, so Ctrl-clicking a row twice
 * toggles it twice rather than renaming it.
 *
 * Secondary (right) presses and already-consumed presses are ignored, so a wrapping context menu still
 * opens and child controls that consume their own press are not double-handled.  Uses raw pointerInput,
 * which never requests focus, so keyboard dispatch stays on the shell root.  While [enabled] is false no
 * pointer handling is installed at all, and a press pending from before is forgotten.
 *
 * @param Function onSingle          Called on a primary press that is not the second half of a double click.
 * @param Function onDouble          Called on a second unmodified primary press within [doubleClickMillis].
 * @param Boolean  enabled           Whether presses are handled at all.
 * @param Long     doubleClickMillis The double-click window in milliseconds.
 * @return Modifier The modifier with the press-timing detector attached.
 */
@Composable
fun Modifier.singleOrDoubleClick(
	onSingle: (PointerKeyboardModifiers) -> Unit,
	onDouble: () -> Unit,
	enabled: Boolean = true,
	doubleClickMillis: Long = DOUBLE_CLICK_MILLIS,
): Modifier {
	// The pointer loop restarts only when the window changes, so it would otherwise keep the lambdas of the
	// composition it started in; read the latest through these (matching the kit Slider).
	val currentOnSingle by rememberUpdatedState(onSingle)
	val currentOnDouble by rememberUpdatedState(onDouble)
	if (!enabled) {
		return this
	}
	return this.pointerInput(doubleClickMillis) {
		val tracker = DoubleClickTracker(doubleClickMillis)
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent()
				if (
					event.type != PointerEventType.Press ||
					event.buttons.isSecondaryPressed ||
					event.changes.any { change -> change.isConsumed }
				) {
					continue
				}
				val modifiers = event.keyboardModifiers
				val modified = modifiers.isCtrlPressed || modifiers.isMetaPressed || modifiers.isShiftPressed
				if (tracker.registerPress(event.changes.first().uptimeMillis, modified)) {
					currentOnDouble()
				} else {
					currentOnSingle(modifiers)
				}
			}
		}
	}
}