package org.umamo.ui.kit.field

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A coordination seam between an in-flight number-field scrub and the editor shell's Escape precedence,
 * mirroring the row-drag seam.  A scrubbing field never takes focus, so Escape reaches the shell's root
 * handler, where the arms below this one would close the overlay the field sits in or clear the selection
 * that put its row on screen - either of which unmounts the field mid-drag.  While a scrub is in flight the
 * field parks its cancel here; the shell checks it ahead of those arms and cancels the scrub instead.  One
 * pointer means at most one scrub anywhere, so a single shared slot serves every field.  Holds null whenever
 * no scrub is in flight.  Only the scrubbing field should write it.
 *
 * @property Function cancel Cancels the in-flight scrub, or null when none is in flight.
 */
class ScrubCancelController {
	var cancel: (() -> Unit)? by mutableStateOf(null)

	/**
	 * Clears the slot if it still holds [parked] - the field's own cancel - and leaves it alone otherwise, so
	 * a field ending its scrub late never drops the cancel another field has since parked.
	 *
	 * @param Function parked The cancel the releasing field parked.
	 */
	fun release(parked: () -> Unit) {
		if (cancel === parked) {
			cancel = null
		}
	}
}

/**
 * Supplies the [ScrubCancelController] the shell shares with every number field under it.  Defaults to a
 * standalone instance so a field hosted without the shell still composes (its scrubs simply cannot be
 * cancelled from the keyboard).
 */
val LocalScrubCancel = staticCompositionLocalOf { ScrubCancelController() }