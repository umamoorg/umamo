package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The editor surface the pointer last touched: an opaque workspace-leaf area id plus the space kind
 * hosting it.  The single answer to "which area does the pointer mean", for every space alike - the UV
 * editor does not participate in the GPU service and a panel participates in nothing, yet all of them
 * need that question resolved at dispatch time.
 *
 * @property String areaId The last-touched leaf's area id.
 * @property SpaceKind kind The space kind that leaf hosts.
 */
internal data class HoveredSurface(val areaId: String, val kind: SpaceKind)

/**
 * The shell-wide holder of the last-touched editor surface.
 *
 * DISPATCH-TIME ONLY: command handlers read [lastTouched] inside their handler bodies at invocation
 * time, never during composition - it is a non-reactive var, so a composition-time gate would go stale
 * without recomposing.  Composition gates key off a latch's own area id instead (ActiveOperator.areaId,
 * ActiveSelectTool.areaId), and a request that must execute in one area carries the id resolved at
 * dispatch in its payload rather than re-reading this at collect time.
 *
 * It means "the last area touched that still exists": moving off an area deliberately does NOT clear it
 * (otherwise every shortcut would die whenever the pointer rested on the menu bar, the tab strip, or the
 * status bar), but an area that is closed, joined away, or switched out from under the pointer releases
 * it via [releaseArea] - the same eviction-on-dispose the other per-area registries do.
 *
 * Stamped by [stampsHoveredSurface], installed once on every workspace leaf, so coverage is a property
 * of the area tree rather than something each space has to remember to opt into.
 */
internal class HoveredSurfaceTracker {
	/** The surface the pointer last touched, or null before any was touched (or after that area died). */
	var lastTouched: HoveredSurface? = null

	/**
	 * Releases [areaId]'s claim on the pointer, if it holds one.
	 *
	 * Guarded on the id rather than clearing unconditionally: leaves come and go while the pointer sits
	 * elsewhere (a workspace switch disposes a whole tree), and a dying area must not wipe a stamp that
	 * belongs to a surviving one.
	 *
	 * @param String areaId The disposing leaf's area id.
	 */
	fun releaseArea(areaId: String) {
		if (lastTouched?.areaId == areaId) {
			lastTouched = null
		}
	}
}

/**
 * Stamps [tracker] with this node's area whenever the pointer is over it.
 *
 * Observes the Initial pass without consuming, so it reports the pointer no matter which descendant owns
 * the gesture - the same mechanism the shell's cursor overlays rely on (see observeWindowPointer).  Exit
 * events are ignored, keeping the tracker's "last touched" meaning.
 *
 * @param HoveredSurfaceTracker? tracker The shell's tracker, or null outside a shell (previews, tests).
 * @param String areaId The hosting leaf's area id.
 * @param SpaceKind kind The space that leaf currently hosts.
 * @return Modifier This modifier with the observer attached, or unchanged when there is no tracker.
 * @warning [kind] MUST stay in the pointerInput key set.  Change Editor Type rewrites a leaf's kind while
 *   leaving its id alone, and the tree keys leaf composition on that id, so the leaf survives the change -
 *   an observer keyed on the id alone would keep reporting the kind the area had when it was first
 *   composed, forever.  Keys live in here rather than at the call site so they cannot be forgotten.
 */
internal fun Modifier.stampsHoveredSurface(tracker: HoveredSurfaceTracker?, areaId: String, kind: SpaceKind): Modifier {
	if (tracker == null) {
		return this
	}
	return pointerInput(tracker, areaId, kind) {
		val stamp = HoveredSurface(areaId, kind)
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				// Every pointer move over every leaf reaches here, so re-stamping an unchanged value would
				// be the common case; compare first and leave the field alone when nothing moved areas.
				if (event.type != PointerEventType.Exit && tracker.lastTouched != stamp) {
					tracker.lastTouched = stamp
				}
			}
		}
	}
}

/**
 * The shell's hovered-surface tracker, or null outside an editor shell (previews, tests).  Every leaf
 * stamps it; the shell's command tables resolve it at dispatch through CommandRouting.
 */
internal val LocalHoveredSurfaceTracker = staticCompositionLocalOf<HoveredSurfaceTracker?> { null }
