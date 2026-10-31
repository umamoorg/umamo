package org.umamo.edit

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.umamo.edit.PoseLatticeTestRig.cage
import org.umamo.edit.PoseLatticeTestRig.parameterP
import org.umamo.edit.PoseLatticeTestRig.posing
import org.umamo.edit.PoseLatticeTestRig.second
import org.umamo.edit.PoseLatticeTestRig.shaped
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the one rule for "this track holds an edit": a typed value holds its channel track and a Pose-mode lattice its
 * warp's geometry track, and the session shows them only in a mode that shows the pose they were made for.
 */
class HeldTracksTest {
	private val opacity = KeyableTarget(KeyformOwner.Deformer(second), FormChannel.OPACITY)

	/**
	 * A session posed at P = 0.5 over the cage, holding a shaped lattice on the cage and a typed opacity on the second
	 * warp.
	 *
	 * @return EditorSession The session.
	 */
	private fun holding(): EditorSession {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), mapOf(parameterP to 0.5f))
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to shaped(1f, 2f)))
		session.commitPendingChannelEdit(opacity, ChannelValue.Scalar(0.25f), DeformerChange.SetOpacity(second, 0.25f))

		return session
	}

	@Test
	fun aValueHoldsItsChannelAndALatticeItsGeometry() {
		val held = heldTracks(mapOf(opacity to ChannelValue.Scalar(0.25f)), mapOf(cage to shaped(1f, 2f)))

		assertEquals(setOf(KeyformTrackRef.Channel(opacity), KeyformTrackRef.Geometry(KeyformOwner.Deformer(cage))), held)
	}

	@Test
	fun theSessionShowsThemOnlyWhereThePoseShows() =
		runTest {
			val session = holding()
			val everyHeld = setOf(KeyformTrackRef.Channel(opacity), KeyformTrackRef.Geometry(KeyformOwner.Deformer(cage)))
			assertEquals(everyHeld, session.shownHeldTracks.first(), "Pose mode")

			session.setMode(EditorMode.Object)
			assertEquals(everyHeld, session.shownHeldTracks.first(), "Object mode")

			session.setMode(EditorMode.Edit)
			assertTrue(session.shownHeldTracks.first().isEmpty(), "Edit mode shows rest, where nothing is held")
		}
}