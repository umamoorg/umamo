package org.umamo.edit.keyform

import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.KeyformAction
import org.umamo.edit.KeyformAim
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ParameterSelection
import org.umamo.edit.PoseLatticeTestRig.cage
import org.umamo.edit.PoseLatticeTestRig.parameterP
import org.umamo.edit.PoseLatticeTestRig.parameterQ
import org.umamo.edit.PoseLatticeTestRig.posing
import org.umamo.edit.PoseLatticeTestRig.second
import org.umamo.edit.PoseLatticeTestRig.shaped
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins how a key captures a warp's lattice: the pending Pose-mode shape lands at the pose whether the pose sits on a
 * key, between keys, or on a parameter the warp was never keyed on; with nothing pending the key pins what is shown;
 * several warps key in one step; a linked pad asks for the axis; and a removal takes the key under the pose but never
 * a warp's last axis.
 */
class LatticeCaptureTest {
	/**
	 * The lattice track of [warpId].
	 *
	 * @param DeformerId warpId The warp.
	 * @return KeyformTrackRef The track.
	 */
	private fun track(warpId: DeformerId): KeyformTrackRef = KeyformTrackRef.Geometry(KeyformOwner.Deformer(warpId))

	/**
	 * A session posed at [pose] over the cage and the second warp, targeting [targeted] for keying, with [forms] held
	 * pending.
	 *
	 * @param Map<ParameterId, Float> pose The pose.
	 * @param Map<DeformerId, WarpLatticeForm> forms The pending lattices.
	 * @param Set<ParameterId> targeted The parameters targeted for keying.
	 * @return EditorSession The session.
	 */
	private fun session(
		pose: Map<ParameterId, Float>,
		forms: Map<DeformerId, WarpLatticeForm>,
		targeted: Set<ParameterId> = setOf(parameterP),
	): EditorSession {
		val session = posing(listOf(SelectionTarget.Deformer(cage), SelectionTarget.Deformer(second)), pose)
		session.setParameterSelection(ParameterSelection(targeted, targeted.last()))

		if (forms.isNotEmpty()) {
			session.commitPendingLatticeEdits(DeformerChange.PoseLattice(forms.keys.toList(), MeshOperatorKind.Grab), forms)
		}

		return session
	}

	/**
	 * The keys [warpId]'s grid holds on [parameterId].
	 *
	 * @param EditorSession session The session.
	 * @param DeformerId warpId The warp.
	 * @param ParameterId parameterId The parameter.
	 * @return List<Float> The keys.
	 */
	private fun keysOf(
		session: EditorSession,
		warpId: DeformerId,
		parameterId: ParameterId,
	): List<Float> {
		val grid = assertNotNull((session.model.value.deformers.first { deformer -> deformer.id == warpId } as Deformer.Warp).geometryGrid)

		return assertNotNull(grid.axes.firstOrNull { axis -> axis.parameterId == parameterId }).keys.toList()
	}

	@Test
	fun aKeyBetweenKeysStoresThePendingShape() {
		val form = shaped(1f, 2f)
		val session = session(mapOf(parameterP to 0.5f), mapOf(cage to form))
		val stepsBefore = session.historyView.value.steps.size

		session.captureKeysOnTracks(listOf(track(cage)), null, KeyformAim.Pose)

		assertEquals(listOf(-1f, 0.5f, 1f), keysOf(session, cage, parameterP))
		assertContentEquals(form.controlPoints, assertNotNull(session.model.value.warpGridLatticeAt(cage, session.pose.value)).controlPoints)
		assertTrue(session.pendingLatticeEdits.value.isEmpty(), "the key consumed it")
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.keyform.insert", session.historyView.value.steps.last().labelKey)
	}

	@Test
	fun aKeyOnAKeyOverwritesThatCell() {
		val form = shaped(1f, 2f)
		val session = session(mapOf(parameterP to 1f), mapOf(cage to form))

		session.captureKeysOnTracks(listOf(track(cage)), null, KeyformAim.Pose)

		assertEquals(listOf(-1f, 1f), keysOf(session, cage, parameterP))
		assertContentEquals(form.controlPoints, assertNotNull(session.model.value.warpGridLatticeAt(cage, session.pose.value)).controlPoints)
	}

	@Test
	fun aParameterTheWarpWasNeverKeyedOnBecomesAnAxis() {
		val form = shaped(1f, 2f)
		val session = session(mapOf(parameterP to 0.5f, parameterQ to 0.25f), mapOf(cage to form), targeted = setOf(parameterQ))
		val elsewhere = mapOf(parameterP to 0.5f, parameterQ to -1f)
		val shownElsewhere = assertNotNull(session.model.value.warpGridLatticeAt(cage, elsewhere)).controlPoints

		session.captureKeysOnTracks(listOf(track(cage)), null, KeyformAim.Pose)

		assertEquals(listOf(-1f, 0f, 0.25f, 1f), keysOf(session, cage, parameterQ), "seeded at min, default, max, then the capture")
		assertContentEquals(form.controlPoints, assertNotNull(session.model.value.warpGridLatticeAt(cage, session.pose.value)).controlPoints)
		assertContentEquals(shownElsewhere, assertNotNull(session.model.value.warpGridLatticeAt(cage, elsewhere)).controlPoints, "away from the key nothing moved")
	}

	@Test
	fun withNothingPendingTheKeyPinsWhatIsShown() {
		val session = session(mapOf(parameterP to 0.5f), emptyMap())
		val shown = assertNotNull(session.model.value.warpGridLatticeAt(cage, session.pose.value)).controlPoints

		session.captureKeysOnTracks(listOf(track(cage)), null, KeyformAim.Pose)

		assertEquals(listOf(-1f, 0.5f, 1f), keysOf(session, cage, parameterP))
		assertContentEquals(shown, assertNotNull(session.model.value.warpGridLatticeAt(cage, session.pose.value)).controlPoints)
	}

	@Test
	fun severalWarpsKeyInOneStep() {
		val cageForm = shaped(1f, 2f)
		val secondForm = shaped(3f, 4f)
		val session = session(mapOf(parameterP to 0.5f), mapOf(cage to cageForm, second to secondForm))
		val stepsBefore = session.historyView.value.steps.size

		session.captureKeysOnTracks(listOf(track(cage), track(second)), null, KeyformAim.Pose)

		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertContentEquals(cageForm.controlPoints, assertNotNull(session.model.value.warpGridLatticeAt(cage, session.pose.value)).controlPoints)
		assertContentEquals(secondForm.controlPoints, assertNotNull(session.model.value.warpGridLatticeAt(second, session.pose.value)).controlPoints)

		session.undo()
		assertEquals(setOf(cage, second), session.pendingLatticeEdits.value.keys, "undo returns the shapes to pending")
	}

	@Test
	fun aLinkedPadAsksForTheAxis() {
		val form = shaped(1f, 2f)
		val session = session(mapOf(parameterP to 0.5f, parameterQ to 0.25f), mapOf(cage to form, second to shaped(3f, 4f)), targeted = setOf(parameterP, parameterQ))
		val modelBefore = session.model.value

		session.captureKeysOnTracks(listOf(track(cage), track(second)), null, KeyformAim.Pose)

		val request = assertNotNull(session.pendingParameterChoice.value)
		assertSame(modelBefore, session.model.value, "nothing is written while it waits")
		assertEquals(listOf(track(cage), track(second)), request.tracks)
		assertEquals(KeyformAction.Capture, request.action)

		session.resolveParameterChoice(parameterQ)

		assertNull(session.pendingParameterChoice.value)
		assertTrue(keysOf(session, cage, parameterQ).contains(0.25f))
		assertTrue(keysOf(session, second, parameterQ).contains(0.25f))
	}

	@Test
	fun aRemovalTakesTheKeyUnderThePoseButNeverTheLastAxis() {
		val session = session(mapOf(parameterP to 0.5f), mapOf(cage to shaped(1f, 2f)))
		session.captureKeysOnTracks(listOf(track(cage)), null, KeyformAim.Pose)

		session.removeKeysOnTracks(listOf(track(cage), track(second)), null, KeyformAim.Pose)
		assertEquals(listOf(-1f, 1f), keysOf(session, cage, parameterP), "the key at the pose goes, the warp without one is left alone")

		session.commitPose(org.umamo.edit.ParameterChange.SetValue(listOf(parameterP)), session.pose.value + (parameterP to 1f))
		val modelBefore = session.model.value
		session.removeKeysOnTracks(listOf(track(cage)), null, KeyformAim.Pose)
		assertSame(modelBefore, session.model.value, "a warp's last axis is never collapsed")
	}

	@Test
	fun aPinnedModeKeysTheRestLatticeNotThePendingShape() {
		val form = shaped(1f, 2f)
		val session = session(mapOf(parameterP to 0.5f), mapOf(cage to form))
		val rest = mapOf(parameterP to 0f)
		val shownAtRest = assertNotNull(session.model.value.warpGridLatticeAt(cage, rest)).controlPoints
		session.setMode(EditorMode.Edit)

		session.captureKeysOnTracks(listOf(track(cage)), null, KeyformAim.Pose)

		assertEquals(listOf(-1f, 0f, 1f), keysOf(session, cage, parameterP), "Edit mode keys at rest")
		assertContentEquals(shownAtRest, assertNotNull(session.model.value.warpGridLatticeAt(cage, rest)).controlPoints)
	}
}