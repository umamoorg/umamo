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
