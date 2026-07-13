package org.umamo.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/** Longest gap between two presses that still counts as a double click. */
private const val DEFAULT_DOUBLE_CLICK_MILLIS = 300L

/**
 * A single- and double-click detector that fires the single-click action IMMEDIATELY on press, with no
 * wait for a possible second click. `Modifier.clickable` and `detectTapGestures(onDoubleTap = …)` both
 * defer the single click by the platform double-tap window (~200-300ms) to disambiguate it; this does
 * not - it dispatches [onSingle] on the first primary press and, if a second primary press lands within
 * [doubleClickMillis], also dispatches [onDouble]. So a double click runs the single action once and then
 * the double action; callers rely on the double action replacing what the single one showed (e.g. an
 * inline rename field taking over a header), the same tradeoff the outliner rows accept.
 *
 * Secondary (right) presses and already-consumed presses are ignored, so a wrapping context menu still
 * opens and child controls that consume their own press are not double-handled. Uses raw pointerInput,
 * which never requests focus, so keyboard dispatch stays on the shell root.
 *
 * 単クリックを押下時に即時発火する単／двойクリック検出。既定の待ち時間で単クリックを遅延させない。
 *
 * @param Function onSingle          Called on a primary press that is not the second half of a double click.
 * @param Function onDouble          Called on a second primary press within [doubleClickMillis].
 * @param Long     doubleClickMillis The double-click window in milliseconds.
 * @return Modifier The modifier with the press-timing detector attached.
 */
@Composable
fun Modifier.singleOrDoubleClick(
	onSingle: () -> Unit,
	onDouble: () -> Unit,
	doubleClickMillis: Long = DEFAULT_DOUBLE_CLICK_MILLIS,
): Modifier {
	// pointerInput(Unit) never restarts, so it would otherwise capture the first composition's lambdas;
	// read the latest through these (matching the kit Slider / the outliner row gesture).
	val currentOnSingle by rememberUpdatedState(onSingle)
	val currentOnDouble by rememberUpdatedState(onDouble)
	return this.pointerInput(Unit) {
		awaitPointerEventScope {
			var lastPressUptime = 0L
			while (true) {
				val event = awaitPointerEvent()
				if (
					event.type != PointerEventType.Press ||
					event.buttons.isSecondaryPressed ||
					event.changes.any { change -> change.isConsumed }
				) {
					continue
				}
				val pressUptime = event.changes.first().uptimeMillis
				if (pressUptime - lastPressUptime <= doubleClickMillis) {
					currentOnDouble()
					// Reset so a third quick press starts a fresh single rather than a second double.
					lastPressUptime = 0L
				} else {
					currentOnSingle()
					lastPressUptime = pressUptime
				}
			}
		}
	}
}
