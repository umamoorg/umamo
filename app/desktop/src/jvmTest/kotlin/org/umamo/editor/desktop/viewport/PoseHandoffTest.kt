package org.umamo.editor.desktop.viewport

import org.umamo.render.puppet.ModelUpdateKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the render loop's pose rule: a positions-only model push alone rebuilds no pose, because the
 * renderer keeps the pose it has and refreshes the two things that read positions itself; a change to
 * any of the pose's own inputs, or a structural push, rebuilds it.  Pure, like the pairing rule, so the
 * matrix is testable without a render thread.
 */
class PoseHandoffTest {
	@Test
	fun changedPoseInputsRebuildThePose() {
		assertTrue(poseFollowsHandoff(poseInputsChanged = true, modelUpdate = null))
		assertTrue(poseFollowsHandoff(poseInputsChanged = true, modelUpdate = ModelUpdateKind.PositionsOnly))
	}

	@Test
	fun aPositionsOnlyPushAloneKeepsThePose() {
		assertFalse(poseFollowsHandoff(poseInputsChanged = false, modelUpdate = ModelUpdateKind.PositionsOnly))
	}

	@Test
	fun aStructuralPushRebuildsThePose() {
		assertTrue(poseFollowsHandoff(poseInputsChanged = false, modelUpdate = ModelUpdateKind.Structural))
	}

	@Test
	fun nothingChangedKeepsThePose() {
		assertFalse(poseFollowsHandoff(poseInputsChanged = false, modelUpdate = null))
	}
}