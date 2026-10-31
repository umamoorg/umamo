package org.umamo.edit.keyform

import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.KeyformAim
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ParameterSelection
import org.umamo.edit.PoseLatticeTestRig.cage
import org.umamo.edit.PoseLatticeTestRig.model
import org.umamo.edit.PoseLatticeTestRig.parameterP
import org.umamo.edit.PoseLatticeTestRig.parameterQ
import org.umamo.edit.PoseLatticeTestRig.posing
import org.umamo.edit.PoseLatticeTestRig.second
import org.umamo.edit.PoseLatticeTestRig.shaped
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.channelGridsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what a Pose-mode key writes on the posed warps: plain I takes each lattice plus the channel values held unkeyed
 * on the same warp, and each choice of the key-type pie takes its own tracks - a channel-only key leaving the warp's
 * held lattice in place, the colors following the runtime target - while an empty session refuses and no other mode
 * answers at all.
 */
class PoseKeyTracksTest {
	/**
	 * A session posed at P = 0.5 over the cage and the second warp, targeting P for keying.
	 *
	 * @param RuntimeTarget target The document's runtime target.
	 * @return EditorSession The session.
	 */
	private fun session(target: RuntimeTarget = RuntimeTarget.NoTarget): EditorSession {
		val session = posing(listOf(SelectionTarget.Deformer(cage), SelectionTarget.Deformer(second)), mapOf(parameterP to 0.5f), model().copy(runtimeTarget = target))
		session.setParameterSelection(ParameterSelection(setOf(parameterP), parameterP))

		return session
	}

	/**
	 * The channel [channel] of [warpId].
	 *
	 * @param DeformerId warpId The warp.
	 * @param FormChannel channel The channel.
	 * @return KeyableTarget The target.
	 */
	private fun channelOf(
		warpId: DeformerId,
		channel: FormChannel,
	): KeyableTarget = KeyableTarget(KeyformOwner.Deformer(warpId), channel)

	/**
	 * Holds [opacity] as [warpId]'s unkeyed opacity at the pose.
	 *
	 * @param EditorSession session The session.
	 * @param DeformerId warpId The warp.
	 * @param Float opacity The value typed.
	 */
	private fun holdOpacity(
		session: EditorSession,
		warpId: DeformerId,
		opacity: Float,
	) {
		session.commitPendingChannelEdit(channelOf(warpId, FormChannel.OPACITY), ChannelValue.Scalar(opacity), DeformerChange.SetOpacity(warpId, opacity))
	}

	/**
	 * The keys [warpId]'s [channel] track holds on [parameterId], or null when the channel is not keyed on it.
	 *
	 * @param EditorSession session The session.
	 * @param DeformerId warpId The warp.
	 * @param FormChannel channel The channel.
	 * @param ParameterId parameterId The parameter.
	 * @return List<Float>? The keys.
	 */
	private fun channelKeys(
		session: EditorSession,
		warpId: DeformerId,
		channel: FormChannel,
		parameterId: ParameterId = parameterP,
	): List<Float>? {
		val grid = session.model.value.channelGridsOf(KeyformOwner.Deformer(warpId))?.get(channel) ?: return null

		return grid.axes.firstOrNull { axis -> axis.parameterId == parameterId }?.keys?.toList()
	}

	@Test
	fun heldTakesEachLatticeAndTheValuesHeldOnThatWarp() {
		val session = session()
		holdOpacity(session, cage, 0.25f)

		assertEquals(
			listOf(
				KeyformTrackRef.Geometry(KeyformOwner.Deformer(cage)),
				KeyformTrackRef.Channel(channelOf(cage, FormChannel.OPACITY)),
				KeyformTrackRef.Geometry(KeyformOwner.Deformer(second)),
			),
			session.posedKeyTracks(PoseKeyKind.Held),
		)
	}

	@Test
	fun plainKeyStoresTheHeldValueAndLeavesAnotherWarpsHeldValueAlone() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), mapOf(parameterP to 0.5f))
		session.setParameterSelection(ParameterSelection(setOf(parameterP), parameterP))
		holdOpacity(session, cage, 0.25f)
		holdOpacity(session, second, 0.75f)
		val stepsBefore = session.historyView.value.steps.size

		session.captureKeysOnTracks(assertNotNull(session.posedKeyTracks(PoseKeyKind.Held)), null, KeyformAim.Pose)

		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one step")
		assertEquals(listOf(-1f, 0f, 0.5f, 1f), channelKeys(session, cage, FormChannel.OPACITY), "the opacity gained the axis, keyed at the pose")
		assertEquals(ChannelValue.Scalar(0.25f), session.model.value.channelValueAt(channelOf(cage, FormChannel.OPACITY), session.pose.value))
		assertTrue(channelOf(cage, FormChannel.OPACITY) !in session.pendingChannelEdits.value, "the key consumed it")
		assertEquals(ChannelValue.Scalar(0.75f), session.pendingChannelEdits.value[channelOf(second, FormChannel.OPACITY)], "the unposed warp's value is still held")
		assertNull(channelKeys(session, second, FormChannel.OPACITY))
	}

	@Test
	fun anOpacityKeyKeepsTheWarpsHeldLattice() {
		val session = session()
		val form = shaped(1f, 2f)
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to form))

		session.captureKeysOnTracks(assertNotNull(session.posedKeyTracks(PoseKeyKind.Opacity)), null, KeyformAim.Pose)

		assertEquals(listOf(-1f, 0f, 0.5f, 1f), channelKeys(session, cage, FormChannel.OPACITY))
		assertEquals(listOf(-1f, 0f, 0.5f, 1f), channelKeys(session, second, FormChannel.OPACITY))
		assertEquals(setOf(cage), session.pendingLatticeEdits.value.keys, "a channel key leaves the lattice held")
	}

	@Test
	fun colorsFollowTheRuntimeTarget() {
		val everyColor = session().posedKeyTracks(PoseKeyKind.Colors)
		assertEquals(
			listOf(
				KeyformTrackRef.Channel(channelOf(cage, FormChannel.MULTIPLY_COLOR)),
				KeyformTrackRef.Channel(channelOf(cage, FormChannel.SCREEN_COLOR)),
				KeyformTrackRef.Channel(channelOf(second, FormChannel.MULTIPLY_COLOR)),
				KeyformTrackRef.Channel(channelOf(second, FormChannel.SCREEN_COLOR)),
			),
			everyColor,
		)

		assertEquals(emptyList(), session(RuntimeTarget.Cubism40).posedKeyTracks(PoseKeyKind.Colors), "a 4.0 target keys no colors")
		assertEquals(listOf(FormChannel.MULTIPLY_COLOR, FormChannel.SCREEN_COLOR), RuntimeTarget.Cubism42.keyableColorChannels())
	}

	@Test
	fun aColorsKeyStoresTheShownColors() {
		val session = session()
		val tint = ColorRgb(0.5f, 0.25f, 1f)
		session.commitPendingChannelEdit(channelOf(cage, FormChannel.MULTIPLY_COLOR), ChannelValue.Color(tint), DeformerChange.SetMultiplyColor(cage, tint))

		session.captureKeysOnTracks(assertNotNull(session.posedKeyTracks(PoseKeyKind.Colors)), null, KeyformAim.Pose)

		assertEquals(ChannelValue.Color(tint), session.model.value.channelValueAt(channelOf(cage, FormChannel.MULTIPLY_COLOR), session.pose.value))
		assertEquals(listOf(-1f, 0f, 0.5f, 1f), channelKeys(session, second, FormChannel.SCREEN_COLOR), "an unheld color pins what is shown")
		assertTrue(session.pendingChannelEdits.value.isEmpty())
	}

	@Test
	fun allTakesTheLatticeOpacityAndColors() {
		val tracks = assertNotNull(session(RuntimeTarget.Cubism40).posedKeyTracks(PoseKeyKind.All))

		assertEquals(
			listOf(
				KeyformTrackRef.Geometry(KeyformOwner.Deformer(cage)),
				KeyformTrackRef.Channel(channelOf(cage, FormChannel.OPACITY)),
				KeyformTrackRef.Geometry(KeyformOwner.Deformer(second)),
				KeyformTrackRef.Channel(channelOf(second, FormChannel.OPACITY)),
			),
			tracks,
			"no colors under a 4.0 target",
		)
		assertEquals(listOf(KeyformTrackRef.Geometry(KeyformOwner.Deformer(cage)), KeyformTrackRef.Geometry(KeyformOwner.Deformer(second))), session().posedKeyTracks(PoseKeyKind.Lattice))
	}

	@Test
	fun aRemovalTakesTheHeldSetsKeysAtThePose() {
		val session = session()
		holdOpacity(session, cage, 0.25f)
		session.captureKeysOnTracks(assertNotNull(session.posedKeyTracks(PoseKeyKind.All)), null, KeyformAim.Pose)
		holdOpacity(session, cage, 0.5f)

		session.removeKeysOnTracks(assertNotNull(session.posedKeyTracks(PoseKeyKind.Held)), null, KeyformAim.Pose)

		assertEquals(listOf(-1f, 1f), (session.model.value.deformerById[cage] as Deformer.Warp).geometryGrid?.axes?.single()?.keys?.toList(), "the lattice key at the pose goes")
		assertEquals(listOf(-1f, 0f, 1f), channelKeys(session, cage, FormChannel.OPACITY), "the held opacity's key at the pose goes")
		assertEquals(listOf(-1f, 0f, 0.5f, 1f), channelKeys(session, second, FormChannel.OPACITY), "a channel holding nothing is not in the held set")
	}

	@Test
	fun anEmptySessionRefusesWithANotice() {
		val session = EditorSession(model(withWarps = false))
		session.setMode(EditorMode.Pose)

		assertEquals(emptyList(), session.posedKeyTracks(PoseKeyKind.Held))
		assertEquals("notice.keyform.noTarget", session.notice.value?.messageKey)
	}

	@Test
	fun noOtherModeAnswers() {
		val session = session()
		session.setMode(EditorMode.Object)
		assertNull(session.posedKeyTracks(PoseKeyKind.All))

		session.setMode(EditorMode.Edit)
		assertNull(session.posedKeyTracks(PoseKeyKind.All))
	}

	@Test
	fun aLinkedPadParksTheChosenTracks() {
		val session = session()
		session.setParameterSelection(ParameterSelection(setOf(parameterP, parameterQ), parameterQ))
		val tracks = assertNotNull(session.posedKeyTracks(PoseKeyKind.Opacity))

		session.captureKeysOnTracks(tracks, null, KeyformAim.Pose)
		assertEquals(tracks, assertNotNull(session.pendingParameterChoice.value).tracks)

		session.resolveParameterChoice(parameterQ)

		assertEquals(listOf(-1f, 0f, 1f), channelKeys(session, cage, FormChannel.OPACITY, parameterQ))
		assertEquals(listOf(-1f, 0f, 1f), channelKeys(session, second, FormChannel.OPACITY, parameterQ))
	}
}