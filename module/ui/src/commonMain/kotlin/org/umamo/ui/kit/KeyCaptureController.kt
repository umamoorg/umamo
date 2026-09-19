package org.umamo.ui.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A coordination seam between a control that is capturing the next key press (the keybindings editor's chord
 * chip) and a host that runs its own keyboard dispatch.
 *
 * The host's root key handler previews every key before the focused control sees it, so a capture cannot defend
 * itself: the host would take Escape for whatever overlay the control sits in and close it, where the control
 * means Escape to cancel the capture alone.  This is narrower than [InlineEditController] on purpose.  A text
 * field inside an overlay gives Escape to the overlay, which is what a rigger expects of a dialog; a capture is
 * an armed state that owns the WHOLE keyboard - any key may be the one being bound - so while one is live the
 * host stands aside entirely and the control's own handler decides every key, Escape included.
 *
 * A count rather than a flag: one control can end its capture in the same pass another begins, and the order
 * of those two must not leave the host thinking nothing is capturing.
 */
class KeyCaptureController {
	private var liveCaptures by mutableIntStateOf(0)

	/** Whether a control is capturing key presses now. */
	val active: Boolean
		get() = liveCaptures > 0

	/** Marks a capture as begun; pair every call with one [end]. */
	fun begin() {
		liveCaptures++
	}

	/** Marks a capture as over, whether it bound a key or was cancelled. */
	fun end() {
		liveCaptures = (liveCaptures - 1).coerceAtLeast(0)
	}
}

/**
 * Supplies the [KeyCaptureController] a host shares with the capturing controls nested under it.  Defaults to a
 * standalone instance, so a control used without a coordinating host still works - with no host previewing keys
 * there is nobody to stand aside.
 */
val LocalKeyCapture = staticCompositionLocalOf { KeyCaptureController() }