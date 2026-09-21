package org.umamo.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * status bar), but an area that is closed or joined away releases it via [releaseArea] - the same
 * eviction-on-dispose the other per-area registries do.  An area switched to another space keeps its
 * stamp under its new kind through [restampKind]: the area still exists and the pointer has not moved,
 * so it goes on being the answer, and a header-dropdown switch sends no pointer event over the leaf to
 * wait for.  Its strip-host stamp asserts the OLD kind, so the leaf drops that first through
 * [releaseStripHost] and the re-stamp claims it again only when the new kind hosts a strip.
 *
 * Stamped by [stampsHoveredSurface], installed once on every workspace leaf, so coverage is a property
 * of the area tree rather than something each space has to remember to opt into.
 *
 * [lastTouchedStripHost] is the one deliberate reach-back: the operation settings strip exists only in
 * a work surface (hostsOperationStrip), so a document-wide operation fired over a panel needs the work
 * surface the pointer touched LAST, however long ago, to place its strip.  It places a panel for an
 * operation that already ran; no command routes an action through it.  It must never name an area
 * that no longer hosts a strip: the area's host refuses a record naming a non-hosting kind, and the
 * strip would show nowhere.
 *
 * [observedKind] is the one read composition MAY make, and it exposes the kind alone on purpose.  The
 * status bar suggests shortcuts for the space under the pointer, which needs a reactive read; an area
 * id read reactively is what let an overlay gate itself onto "the active area" and paint in two
 * viewports at once.  A kind cannot do that: two viewports share one, so it names no area to gate on.
 */
internal class HoveredSurfaceTracker {
	/** The surface the pointer last touched, or null before any was touched (or after that area died). */
	var lastTouched: HoveredSurface? = null
		set(value) {
			field = value
			observedKind = value?.kind
		}

	/**
	 * The kind of [lastTouched], as snapshot state - display chrome's reactive view of where the pointer
	 * is.  Written only through [lastTouched], so the two cannot disagree.
	 */
	var observedKind: SpaceKind? by mutableStateOf(null)
		private set

	/**
	 * The strip-hosting surface the pointer last touched, or null before any was (or after it died or
	 * stopped hosting a strip).
	 */
	var lastTouchedStripHost: HoveredSurface? = null

	/**
	 * Releases [areaId]'s strip-host claim, if it holds one - the leaf calls this when its space changes,
	 * because the claim asserts the kind the area had when touched and that kind is now gone.  The
	 * general stamp is untouched; the area still exists.
	 *
	 * @param String areaId The leaf whose space changed.
	 */
	fun releaseStripHost(areaId: String) {
		if (lastTouchedStripHost?.areaId == areaId) {
			lastTouchedStripHost = null
		}
	}

	/**
	 * Re-stamps [areaId] under [kind] when it is the last-touched surface - the leaf calls this when its
	 * space changes, so dispatch, the status bar, and the palette all read the space the area hosts NOW
	 * rather than the one it hosted when the pointer last moved over it.
	 *
	 * Does what a pointer event over the switched area would: the stamp takes the new kind, and the
	 * strip-host claim follows when that kind hosts a strip.  A stamp naming another area is left alone -
	 * a space switch says nothing about where the pointer is.
	 *
	 * @param String areaId The leaf whose space changed.
	 * @param SpaceKind kind The space that leaf hosts now.
	 */
	fun restampKind(areaId: String, kind: SpaceKind) {
		val current = lastTouched
		if (current == null || current.areaId != areaId || current.kind == kind) {
			return
		}
		val stamp = HoveredSurface(areaId, kind)
		lastTouched = stamp
		if (kind.hostsOperationStrip) {
			lastTouchedStripHost = stamp
		}
	}

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
		if (lastTouchedStripHost?.areaId == areaId) {
			lastTouchedStripHost = null
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
					if (kind.hostsOperationStrip) {
						tracker.lastTouchedStripHost = stamp
					}
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