package org.umamo.edit

import org.umamo.runtime.model.PuppetModel

/**
 * The editor's interaction mode, Blender-style. Object mode selects whole entities (parts, drawables,
 * deformers) and shows the rendered puppet; Edit mode dives into the active entity's interior (mesh,
 * warp lattice, or rotation pivot). The mode scopes hit-testing and tool behaviour. v1 implements
 * Object fully and leaves Edit as a routed stub.
 *
 * エディタの操作モード（Blender 流）。オブジェクトモードは要素全体を選択し、編集モードは内部を編集する。
 */
enum class EditorMode {
	/** Whole-entity selection and posing — the default. */
	Object,

	/** Interior editing of the active entity (stubbed in v1). */
	Edit,
}

/**
 * Whether this mode pins the pose.
 *
 * Edit mode edits the neutral state of the base mesh, so for its duration the rig is shown at rest and
 * the pose Object mode left is held as it is: nothing may move it, and it returns to the viewport when
 * Edit mode is left.  The one rule every part of the editor that shows or writes the pose reads.
 */
val EditorMode.pinsPose: Boolean get() = this == EditorMode.Edit

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