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
 * ONE resolver backs it: the hovered surface (area id + space kind), stamped by every workspace leaf, so
 * it answers "what is the pointer on" for all nine space kinds.  Exactly one, deliberately: a second
 * resolver scoped to 2D viewports could only mean "the last viewport touched, however long ago" rather
 * than "the one under the pointer", and the two would disagree the moment the pointer moved to another
 * space.  Every command resolves a surface the user is actually pointing at, or none at all.
 *
 * A command that needs a viewport and finds none does NOTHING; it does not reach back to a viewport the
 * pointer has left.  That is Blender's rule and it is the whole point of the seam.
 *
 * Every answer is resolved at DISPATCH time, inside a handler body, never latched at registration - the
 * same contract HoveredSurfaceTracker carries.  The backing read is a non-reactive var, so a value
 * captured during composition goes stale without recomposing.
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
 * Holds nothing but its one resolver on purpose.  Folding the per-area registries (the camera hub, the
 * open keyform sheets) in here would look tidier and would be wrong: their lookups carry fallbacks that
 * are correct for some commands and actively harmful for others, and a shared helper cannot express
 * that.  Commands needing a registry take it directly and pick their own lookup, keyed off an area id
 * resolved here.
 *
 * @param Function hoveredSurface Resolves the last-touched editor surface (area id + space kind).
 * @warning The resolver must read LIVE state, not a value captured when the instance was built - one
 *   instance serves the whole shell for its lifetime, across document swaps and area-tree edits, so a
 *   snapshot would answer with wherever the pointer was at first composition forever.
 */
internal class CommandRouting(
	private val hoveredSurface: () -> HoveredSurface?,
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
	 * The 2D viewport under the pointer, or null when the pointer is on anything else.
	 *
	 * @return String? The hovered viewport's area id, or null.
	 * @note There is deliberately no "last viewport touched" fallback.  A command that needs a viewport and
	 *   gets null here must do nothing - acting in a viewport the pointer has left is precisely the failure
	 *   a single hovered-surface resolver exists to rule out.
	 */
	fun viewportArea(): String? = areaOf(SpaceKind.Viewport2D)

	/**
	 * Where a modal Grab / Scale / Rotate runs.
	 *
	 * @return TransformTarget? The hovered surface when it can host a transform, else null - the pointer is
	 *   on a panel, or on nothing, and there is nowhere to run the gesture.
	 * @warning Neither branch falls back to the other.  A hovered UV editor resolves to [TransformTarget.Uv]
	 *   even outside Edit mode (the session refuses it there), and a hovered panel resolves to nothing at
	 *   all; either fallback would start a gesture in an area the pointer is not over, which is precisely
	 *   the bug hovered-area routing exists to prevent.
	 */
	fun transformTarget(): TransformTarget? {
		val hovered = hoveredSurface() ?: return null
		return when (hovered.kind) {
			SpaceKind.UvEditor -> TransformTarget.Uv(hovered.areaId)
			SpaceKind.Viewport2D -> TransformTarget.Viewport(hovered.areaId)
			else -> null
		}
	}

	/**
	 * Where a Box / Circle select tool arms: the hovered 2D viewport, or the hovered UV editor in Edit mode.
	 * Anything else - a panel, a UV editor in Object mode, nothing at all - arms nowhere.
	 *
	 * @param EditorSession? session The active session, or null when no document is open.
	 * @return String? The arming area's id, or null when no surface can host the tool.
	 * @note The Edit-mode gate on the UV branch is load-bearing, unlike [transformTarget]'s missing one:
	 *   EditorSession.beginBoxSelect / beginCircleSelect are mode-agnostic and arm unconditionally in
	 *   Object mode, while the UV editor's overlay only composes in Edit mode.  Arming there in Object
	 *   mode would latch a tool nothing can drive or cancel by pointer.  Read the session's mode HERE,
	 *   inside the resolver, so it is sampled at dispatch.
	 */
	fun selectToolArea(session: EditorSession?): String? {
		val hovered = hoveredSurface() ?: return null
		return when (hovered.kind) {
			SpaceKind.Viewport2D -> hovered.areaId
			SpaceKind.UvEditor -> hovered.areaId.takeIf { session?.mode?.value == EditorMode.Edit }
			else -> null
		}
	}
}
