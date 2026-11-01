package org.umamo.edit

import org.umamo.runtime.model.PuppetModel

/**
 * The editor's interaction mode, Blender-style. Object mode selects whole entities (parts, drawables,
 * deformers) and places them at rest; Edit mode dives into the active entity's interior (mesh or warp
 * lattice) at rest; Pose mode poses deformers at the current parameter values, for
 * keying.  The mode scopes hit-testing and tool behaviour.
 *
 * Posing has a mode of its own rather than living in Object mode, so a drag in Object mode always means
 * "move the rest placement" and never silently means "pose this" depending on what is selected.
 */
enum class EditorMode {
	/** Whole-entity selection and rest placement — the default. */
	Object,

	/** Interior editing of the active entity, at rest. */
	Edit,

	/** Posing deformers at the current parameter values, for keying. */
	Pose,
}

/**
 * Whether this mode pins the pose.
 *
 * Edit mode edits the neutral state of the base mesh, so for its duration the rig is shown at rest and
 * the pose Object mode left is held as it is: nothing may move it, and it returns to the viewport when
 * Edit mode is left.  The one rule every part of the editor that shows or writes the pose reads.  Pose
 * mode is where the pose is authored, so it never pins it.
 */
val EditorMode.pinsPose: Boolean get() = this == EditorMode.Edit

/**
 * Whether this mode works on the session's elements - mesh vertices, edges, and faces, or lattice points, grid
 * lines, and cells - rather than on whole objects.
 *
 * Edit and Pose mode share one way of working: the viewport picks the elements of the session's owners, the select
 * modes switch the element domain, and G / R / S move what the elements cover.  What tells them apart is where the
 * edit lands, which [pinsPose] decides: Edit mode shapes the rest state, Pose mode shapes the pose and holds the result
 * pending until it is keyed.  A check that means "points rather than objects" reads this rule; a check that means "at
 * rest" (topology, UVs, mesh generation) stays on Edit mode itself.
 */
val EditorMode.editsElements: Boolean get() = this != EditorMode.Object

/**
 * Whether keys may be inserted, removed, or moved in this mode.
 *
 * Edit mode edits the rest state alone, so it changes no key: the keyform sheet still shows the keys and selects
 * them, and inserting, removing, or moving one waits for Object or Pose mode.  The one rule every key-changing
 * command, menu row, and sheet gesture reads.
 */
val EditorMode.editsKeys: Boolean get() = this != EditorMode.Edit

/**
 * The pose this mode shows, and so the pose every view and every edit aimed at "the pose" evaluates at:
 * the rig's pose, and while the mode pins it, every parameter at its default.
 *
 * A pure rule rather than a session read, so a view that collects the session's pose and mode resolves
 * exactly what EditorSession.shownPose does.  The pinned form is an explicit map of the defaults, never an
 * empty one, so a caller that writes it somewhere writes the whole rest pose.
 *
 * @param PuppetModel model The document model, whose parameters supply the defaults.
 * @param Pose pose The rig's pose (the session's pose, held as it is while pinned).
 * @return Pose The pose to show.
 */
fun EditorMode.shownPose(model: PuppetModel, pose: Pose): Pose =
	if (pinsPose) {
		model.parameters.associate { parameter -> parameter.id to parameter.default }
	} else {
		pose
	}