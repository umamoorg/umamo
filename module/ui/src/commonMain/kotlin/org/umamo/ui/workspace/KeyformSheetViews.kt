package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter

/**
 * What the shell's keyform-sheet commands need from one open sheet area: its current selection resolved
 * to editable triples, selection maintenance, and the frame-all view op.
 *
 * The lambdas read the area's LIVE view state at invocation time, so a registered surface never goes
 * stale between edits - the same dispatch-time-only rule as HoveredSurfaceTracker.
 *
 * @property Function selectedTracks The selection resolved against the area's current projection.
 * @property Function hasSelection Whether the area has any selected keys.
 * @property Function clearSelection Drops the area's key selection.
 * @property Function frameAll Resets the area's zoom window to the whole domain.
 * @property Function armBoxSelect Arms the area's marquee, so the next drag selects a region of keys.
 */
internal class KeyformSheetSurface(
	val selectedTracks: () -> List<Triple<KeyformTrackRef, Parameter, Int>>,
	val hasSelection: () -> Boolean,
	val clearSelection: () -> Unit,
	val frameAll: () -> Unit,
	val armBoxSelect: () -> Unit,
	/**
	 * Whether this sheet's box-select marquee is armed, and how to disarm it.
	 *
	 * Exposed rather than left as private view state so the shell's Escape ladder can cancel the gesture:
	 * an armed marquee hides the OS cursor, so a mode with no way out strands the pointer invisible.
	 */
	val boxSelectArmed: () -> Boolean,
	val disarmBoxSelect: () -> Unit,
	/**
	 * Nudges the sheet's whole key selection by [Float] of each key's parameter range, as one undo step,
	 * and re-points the selection at where the keys landed.
	 *
	 * On the SURFACE rather than left to the command because a crossing renumbers the axis - the same
	 * reason the mark drag routes through the sheet.  A nudge that moved a key past its neighbour used to
	 * leave the selection naming whichever key took its place, so the next press moved the wrong one.
	 */
	val nudgeSelection: (Float) -> Unit,
)

/**
 * The shell-wide registry of open keyform-sheet areas, keyed by area id.
 *
 * The sheet's commands (delete/nudge selected keys, frame all) are registered ONCE at shell level and
 * resolve the acting sheet through this at dispatch time.  Registering them per area looked simpler but
 * was wrong twice over: CommandRegistry is last-write-wins per id, so a second sheet area clobbered the
 * first's handlers, and disposing either area unregistered the shared ids out from under the survivor.
 */
internal class KeyformSheetViews {
	private val surfacesByAreaId = LinkedHashMap<String, KeyformSheetSurface>()

	/**
	 * The one sheet with an armed box-select marquee, or null when none has one.
	 *
	 * Arming is a per-area affair but Escape is a shell-level key, so the ladder asks here rather than
	 * reaching into a space's private view state.  First match wins: two sheets can only both be armed if
	 * the user armed one, moved to the other and armed that too, and cancelling either is a fine answer.
	 *
	 * @return KeyformSheetSurface? The armed sheet, or null.
	 */
	fun armedBoxSelect(): KeyformSheetSurface? = surfacesByAreaId.values.firstOrNull { surface -> surface.boxSelectArmed() }

	/**
	 * Registers [surface] as area [areaId]'s sheet.
	 *
	 * @param String areaId The hosting leaf's area id.
	 * @param KeyformSheetSurface surface The area's command surface.
	 */
	fun register(areaId: String, surface: KeyformSheetSurface) {
		surfacesByAreaId[areaId] = surface
	}

	/**
	 * Removes [surface] from [areaId], by identity - a replacement registered by a newer composition of
	 * the same area must not be torn down by the older one's dispose.
	 *
	 * @param String areaId The hosting leaf's area id.
	 * @param KeyformSheetSurface surface The surface the caller registered.
	 */
	fun unregister(areaId: String, surface: KeyformSheetSurface) {
		if (surfacesByAreaId[areaId] === surface) {
			surfacesByAreaId.remove(areaId)
		}
	}

	/**
	 * The sheet a VIEW command (frame all) acts on: the hovered sheet area when there is one, else the
	 * only open sheet.  Two sheets with neither hovered is genuinely ambiguous, so it resolves nothing.
	 *
	 * @param String? hoveredAreaId The last-touched keyform-sheet area, or null.
	 * @return KeyformSheetSurface? The acting sheet, or null.
	 */
	fun resolve(hoveredAreaId: String?): KeyformSheetSurface? =
		surfacesByAreaId[hoveredAreaId] ?: surfacesByAreaId.values.singleOrNull()

	/**
	 * The sheet a SELECTION command (delete, nudge) acts on: [resolve]'s answer, else the unique sheet
	 * that actually has keys selected - a selection is itself a statement about which sheet is meant.
	 *
	 * @param String? hoveredAreaId The last-touched keyform-sheet area, or null.
	 * @return KeyformSheetSurface? The acting sheet, or null.
	 */
	fun resolveForSelection(hoveredAreaId: String?): KeyformSheetSurface? =
		resolve(hoveredAreaId) ?: surfacesByAreaId.values.singleOrNull { surface -> surface.hasSelection() }
}

/**
 * The shell's keyform-sheet registry, or null outside an editor shell (previews, tests).
 *
 * Static because the holder instance never changes for a shell's lifetime; only its contents do.
 */
internal val LocalKeyformSheetViews = staticCompositionLocalOf<KeyformSheetViews?> { null }
