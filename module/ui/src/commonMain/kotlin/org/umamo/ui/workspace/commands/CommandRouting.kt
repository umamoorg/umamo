package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.workspace.HoveredSurface
import org.umamo.ui.workspace.SpaceKind

/*
 * The ONE place a command asks "which area does the pointer mean".
 *
 * Blender's hovered-area routing - the key acts where the pointer is - needs the same question answered
 * by a dozen commands, and answering it inline gave a dozen slightly different answers.  Every rule that
 * reads the pointer's surface lives here, so a change to the routing model is a change to this file
 * rather than an archaeology exercise across the command tables.
 *
 * Two resolvers back it, and they are NOT interchangeable:
 *  - the hovered surface (area id + space kind) is stamped by EVERY workspace leaf, panels included, so
 *    it answers "what is the pointer on" for all nine space kinds;
 *  - the active viewport area is stamped by 2D viewports ALONE - deliberately not by the UV editor or by
 *    any panel, so the object and select-tool latches keep meaning "a viewport" (see
 *    PuppetViewportService.activeAreaId).
 *
 * That asymmetry is why a key pressed over a panel does nothing for the view commands (no camera is
 * registered under a panel's area) while still resolving a viewport for a transform.  Collapsing the two
 * is a deliberate future step, not an oversight.
 *
 * Every answer is resolved at DISPATCH time, inside a handler body, never latched at registration - the
 * same contract HoveredSurfaceTracker and PuppetViewportService.activeAreaId both carry.  Both backing
 * reads are non-reactive vars, so a value captured during composition goes stale without recomposing.
 */

/**
 * Where a modal transform (Grab / Scale / Rotate) runs: the pointer's UV editor, or its 2D viewport.
 *
 * A sealed pair rather than a bare area id because the two cases dispatch to different session
 * operators over different data - texture coordinates versus geometry - and a `when` over this makes the
 * compiler enforce that a caller handled both.
 *
 * @property String areaId The area the gesture belongs to; only that area's overlay drives it.
 */
internal sealed interface TransformTarget {
	val areaId: String

	/** A hovered UV editor: the gesture transforms texture coordinates. */
	data class Uv(override val areaId: String) : TransformTarget

	/** The pointer's 2D viewport: the gesture transforms geometry, in whichever mode is active. */
	data class Viewport(override val areaId: String) : TransformTarget
}

/**
 * A dispatching command's view of where the pointer is.
 *
 * Holds nothing but its two resolvers on purpose.  Folding the per-area registries (the camera hub, the
 * open keyform sheets) in here would look tidier and would be wrong: their lookups carry fallbacks that
 * are correct for some commands and actively harmful for others, and a shared helper cannot express
 * that.  Commands needing a registry take it directly and pick their own lookup, keyed off an area id
 * resolved here.
 *
 * @param Function hoveredSurface Resolves the last-touched editor surface (area id + space kind).
 * @param Function activeViewportArea Resolves the 2D viewport the pointer last addressed.
 * @warning Both resolvers must read LIVE state, not a value captured when the instance was built.  The
 *   render service behind [activeViewportArea] is swapped on a document change, so a lambda closing over
 *   a plain `service` variable would keep answering with the one that existed at first composition -
 *   null before any document opens - and every viewport-targeted command would silently no-op for the
 *   app's lifetime.  One instance serves the whole shell precisely because it holds no such snapshot;
 *   that matters for the groups whose effects deliberately do not re-register on a document swap.
 */
internal class CommandRouting(
	private val hoveredSurface: () -> HoveredSurface?,
	private val activeViewportArea: () -> String?,
) {
	/**
	 * The editor surface the pointer last touched, or null before any was touched.
	 *
	 * @return HoveredSurface? The last-touched surface, or null.
	 */
	fun hovered(): HoveredSurface? = hoveredSurface()

	/**
	 * Whether the pointer last touched a surface of [kind].
	 *
	 * @param SpaceKind kind The space kind to test for.
	 * @return Boolean True when the last-touched surface hosts that kind.
	 */
	fun isHovering(kind: SpaceKind): Boolean = hoveredSurface()?.kind == kind

	/**
	 * The hovered area's id, but only when that area hosts [kind] - the "is the pointer over one of
	 * these, and if so which one" question.
	 *
	 * @param SpaceKind kind The space kind the caller acts on.
	 * @return String? The hovered area's id when it hosts [kind], else null.
	 */
	fun areaOf(kind: SpaceKind): String? = hoveredSurface()?.takeIf { surface -> surface.kind == kind }?.areaId

	/**
	 * The hovered area's id whatever space it hosts.
	 *
	 * @return String? The last-touched area's id, or null before any surface was touched.
	 * @warning Kind-agnostic BY DESIGN, and narrowing it to one kind is a behavior change disguised as a
	 *   cleanup.  Its callers put the id into a session request payload whose collectors gate on their
	 *   own area id, so an id naming the "wrong" kind of surface simply matches no collector and the
	 *   request no-ops - while Select Linked genuinely executes in a viewport AND in a UV editor.
	 */
	fun hoveredAreaIdAnyKind(): String? = hoveredSurface()?.areaId

	/**
	 * The 2D viewport the pointer last addressed, or null before it ever entered one.
	 *
	 * @return String? The active viewport's area id, or null.
	 */
	fun viewportArea(): String? = activeViewportArea()

	/**
	 * Where a modal Grab / Scale / Rotate runs.
	 *
	 * @return TransformTarget? The target surface, or null when the pointer has never touched a viewport
	 *   and is not over a UV editor - then there is nowhere to run the gesture.
	 * @warning A hovered UV editor does NOT fall back to the viewport.  Resolving it to
	 *   [TransformTarget.Viewport] when the UV operator declines (it refuses outside Edit mode) would
	 *   start a grab in an area the pointer is not over, which is precisely the bug hovered-area routing
	 *   exists to prevent.
	 */
	fun transformTarget(): TransformTarget? {
		val hovered = hoveredSurface()
		if (hovered?.kind == SpaceKind.UvEditor) {
			return TransformTarget.Uv(hovered.areaId)
		}
		return activeViewportArea()?.let { areaId -> TransformTarget.Viewport(areaId) }
	}

	/**
	 * Where a Box / Circle select tool arms: the hovered UV editor in Edit mode, else the pointer's 2D
	 * viewport.  A hovered UV editor in Object mode resolves to null rather than falling back to a
	 * viewport the pointer is not over.
	 *
	 * @param EditorSession? session The active session, or null when no document is open.
	 * @return String? The arming area's id, or null when no surface can host the tool.
	 * @note The Edit-mode gate is load-bearing, unlike [transformTarget]'s missing one:
	 *   EditorSession.beginBoxSelect / beginCircleSelect are mode-agnostic and arm unconditionally in
	 *   Object mode, while the UV editor's overlay only composes in Edit mode.  Arming there in Object
	 *   mode would latch a tool nothing can drive or cancel by pointer.  Read the session's mode HERE,
	 *   inside the resolver, so it is sampled at dispatch.
	 */
	fun selectToolArea(session: EditorSession?): String? {
		val hovered = hoveredSurface()
		if (hovered?.kind == SpaceKind.UvEditor) {
			return if (session?.mode?.value == EditorMode.Edit) {
				hovered.areaId
			} else {
				null
			}
		}
		return activeViewportArea()
	}
}
