package org.umamo.ui.transform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Pose
import org.umamo.edit.pinsPose
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.WarpLatticeForm

/*
 * Where a session's element edits land.  Edit and Pose mode share one way of working - the same overlay, picks,
 * select modes, and G / R / S - and differ in exactly this: Edit mode captures at rest and reshapes the rest
 * lattice, Pose mode captures at the pose with the pending lattices in place and holds what it shapes pending.
 * Every capture and every write path reads the stage rather than the mode, so the difference lives here alone.
 */

/** The pose a session's elements are captured at, and how a lattice edit made there lands. */
internal sealed interface ElementEditStage {
	/** The pose to capture at, by parameter id; a parameter it does not name sits at its default. */
	val pose: Map<ParameterId, Float>

	/** The lattices standing in for their warps' grid blends at [pose]. */
	val latticeOverrides: Map<DeformerId, WarpLatticeForm>

	/** Edit mode: the rest pose, where a lattice edit reshapes every form of the warp and the art stays put. */
	data object Rest : ElementEditStage {
		override val pose: Map<ParameterId, Float> = emptyMap()

		override val latticeOverrides: Map<DeformerId, WarpLatticeForm> = emptyMap()
	}

	/**
	 * Pose mode: the session's pose with its pending lattices in place, where a lattice edit is held pending at the
	 * pose and the art follows it.
	 *
	 * @property Map<ParameterId, Float> pose The pose.
	 * @property Map<DeformerId, WarpLatticeForm> latticeOverrides The pending lattices.
	 */
	class Posed(
		override val pose: Map<ParameterId, Float>,
		override val latticeOverrides: Map<DeformerId, WarpLatticeForm>,
	) : ElementEditStage
}

/**
 * The stage [mode] edits at: at rest while it pins the pose, else at [pose] with [pendingLattices] in place.
 *
 * @param EditorMode mode The editor mode.
 * @param Pose pose The rig's pose.
 * @param Map<DeformerId, WarpLatticeForm> pendingLattices The session's pending lattices.
 * @return ElementEditStage The stage.
 */
internal fun elementEditStageOf(
	mode: EditorMode,
	pose: Pose,
	pendingLattices: Map<DeformerId, WarpLatticeForm>,
): ElementEditStage = if (mode.pinsPose) ElementEditStage.Rest else ElementEditStage.Posed(pose, pendingLattices)

/**
 * The stage the session edits at now ([elementEditStageOf] over its live mode, pose, and pending lattices).
 *
 * @return ElementEditStage The stage.
 */
internal fun EditorSession.elementEditStage(): ElementEditStage = elementEditStageOf(mode.value, pose.value, pendingLatticeEdits.value)

/**
 * The stage [session] edits at ([elementEditStageOf]), collected so a change of its mode, pose, or pending lattices
 * recomposes the caller, and remembered so every reader in one composition agrees on the same instance.
 *
 * @param EditorSession session The session.
 * @return ElementEditStage The stage.
 */
@Composable
internal fun rememberElementEditStage(session: EditorSession): ElementEditStage {
	val mode by session.mode.collectAsState()
	val pose by session.pose.collectAsState()
	val pendingLattices by session.pendingLatticeEdits.collectAsState()

	return remember(mode, pose, pendingLattices) { elementEditStageOf(mode, pose, pendingLattices) }
}