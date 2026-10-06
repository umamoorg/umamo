package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.OperatorParameter
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.withParameter
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the UV Edit overlay's modal transform ([UvEditModalTransform]) over a real session, without Compose:
 * what a latch captures and when it drops, what a drive previews, what a confirm commits and registers,
 * that the proportional radius is always the shown surface's - read through the host's holder each time,
 * except for a confirmed step's strip adjustment, which writes back to the surface the gesture ran on - and
 * that an abandon clears only this area's latch.
 */
class UvEditModalTransformTest {
	/** Where the pointer rests as the gesture latches: the gesture measures from here. */
	private val gestureStart = Offset(200f, 150f)

	/** Forty pixels right of [gestureStart]: ten display texels at the rig's zoom. */
	private val tenTexelsRight = Offset(240f, 150f)

	/** The quad's stored coordinates as the rig builds them. */
	private val quadUvs = listOf(100f / 256, 1f - 100f / 256, 120f / 256, 1f - 100f / 256, 120f / 256, 1f - 120f / 256, 100f / 256, 1f - 120f / 256)

	/**
	 * The transform under test, the holders the overlay would hand it, and every model it pushed.
	 *
	 * @property EditorSession session The session.
	 * @property MutableState shownFrame The shown surface's frame, as the overlay's live holder.
	 * @property MutableState radiusHolder The shown surface's radius state, as the overlay's live holder.
	 * @property UvEditModalTransform transform The transform.
	 * @property MutableList pushed Every preview model pushed.
	 */
	private class Rig(
		val session: EditorSession,
		val shownFrame: MutableState<UvEditFrame>,
		val radiusHolder: MutableState<MutableState<Float?>>,
		val transform: UvEditModalTransform,
		val pushed: MutableList<PuppetModel>,
	)

	/**
	 * A transform for the left area over [session], showing the rig's page with an unseeded radius.
	 *
	 * @param EditorSession session The session.
	 * @return Rig The transform and its holders.
	 */
	private fun rigOver(session: EditorSession): Rig {
		val pushed = ArrayList<PuppetModel>()
		val shownFrame = mutableStateOf(uvRigPageFrame())
		val radiusHolder = mutableStateOf<MutableState<Float?>>(mutableStateOf(null))
		val transform = UvEditModalTransform(LEFT_AREA_ID, session, shownFrame, radiusHolder) { model -> pushed.add(model) }
		return Rig(session, shownFrame, radiusHolder, transform, pushed)
	}

	/**
	 * Latches [kind] in the left area and begins the gesture the way the overlay's effect does.
	 *
	 * @param MeshOperatorKind kind The operator.
	 */
	private fun Rig.latch(kind: MeshOperatorKind) {
		session.beginUvOperator(kind, LEFT_AREA_ID)
		assertEquals(kind, session.activeUvOperator.value?.kind, "the session latched the operator")
		transform.gesture.lastPointer = gestureStart
		val frame = shownFrame.value
		transform.begin(kind, uvRigGeometries(session.model.value, frame), session.meshSelection.value, frame)
	}

	/**
	 * One drive at [pointer].
	 *
	 * @param Offset pointer The virtual pointer.
	 * @return Boolean Whether a preview was driven.
	 */
	private fun Rig.driveTo(pointer: Offset): Boolean = transform.drivePreview(pointer, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

	/**
	 * The shown surface switches to the rig's source layer, as the host hands over the layer's frame and its
	 * own radius state.
	 *
	 * @return MutableState<Float?> The layer's radius state.
	 */
	private fun Rig.showLayer(): MutableState<Float?> {
		val layerRadius = mutableStateOf<Float?>(null)
		shownFrame.value = uvRigLayerFrame()
		radiusHolder.value = layerRadius
		return layerRadius
	}

	/** A latch whose selection emptied before the capture drops the operator and captures nothing. */
	@Test
	fun aLatchOverNothingDrops() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA_ID)
		val frame = rig.shownFrame.value

		rig.transform.begin(MeshOperatorKind.Grab, uvRigGeometries(rig.session.model.value, frame), MeshSelectionOps.clear(rig.session.meshSelection.value), frame)

		assertNull(rig.session.activeUvOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertFalse(rig.transform.end(), "there was no gesture to tear down")
	}

	/** A Grab previews without committing, and the confirm commits the preview as one registered step. */
	@Test
	fun aGrabPreviewsThenCommitsOneStep() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		val session = rig.session
		val stepsBefore = session.historyView.value.steps.size
		rig.latch(MeshOperatorKind.Grab)

		assertTrue(rig.driveTo(tenTexelsRight))
		assertEquals(110f / 256, rig.pushed.single().drawables.first { drawable -> drawable.id == UV_RIG_QUAD }.mesh!!.uvs[0], "the preview moved vertex 0")
		assertEquals(quadUvs, uvRigUvsOf(session, UV_RIG_QUAD), "a preview commits nothing")

		rig.transform.confirm()

		assertEquals(listOf(110f / 256) + quadUvs.drop(1), uvRigUvsOf(session, UV_RIG_QUAD))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals(LEFT_AREA_ID, assertNotNull(session.adjustableOperation.value).areaId)
		assertNull(session.activeUvOperator.value)
		assertTrue(rig.transform.end(), "the gesture is torn down by the caller's effect")
		assertFalse(rig.transform.end(), "and only once")
	}

	/**
	 * Over a layer placed rotated and scaled - this placement's display round trip drifts every one of the
	 * quad's u coordinates by an ulp or two in floats - the confirm writes only the moved vertex: every other
	 * stored coordinate stays bit-identical.
	 */
	@Test
	fun aConfirmLeavesUnmovedCoordinatesBitIdentical() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		val binding =
			DrawableLayerBinding(
				layerKey = UV_RIG_LAYER_KEY,
				placement = AtlasPlacement(pageIndex = 0, positionX = 140f, positionY = 90f, scaleX = 0.7f, scaleY = 0.7f, rotationDegrees = 17f),
				pageWidth = UV_RIG_PAGE_SIDE,
				pageHeight = UV_RIG_PAGE_SIDE,
			)
		val frame = assertNotNull(sourceLayerEditFrame(binding, UV_RIG_LAYER_SIDE, UV_RIG_LAYER_SIDE))
		val rig = rigOver(session)
		rig.shownFrame.value = frame
		val camera = uvRigCameraOver(uvRigGeometries(session.model.value, frame))
		session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA_ID)
		rig.transform.gesture.lastPointer = gestureStart
		rig.transform.begin(MeshOperatorKind.Grab, uvRigGeometries(session.model.value, frame), session.meshSelection.value, frame)

		rig.transform.drivePreview(tenTexelsRight, camera, UV_RIG_AREA_SIZE)
		rig.transform.confirm()

		val committed = uvRigUvsOf(session, UV_RIG_QUAD)
		assertNotEquals(quadUvs.take(2), committed.take(2), "vertex 0 moved")
		assertEquals(quadUvs.drop(2), committed.drop(2), "every other coordinate bit-identical")
	}

	/** A confirm before any drive has no preview to commit: no step, no registration, and the latch clears. */
	@Test
	fun aConfirmBeforeAnyDriveCommitsNothing() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		val stepsBefore = rig.session.historyView.value.steps.size
		rig.latch(MeshOperatorKind.Grab)

		rig.transform.confirm()

		assertEquals(stepsBefore, rig.session.historyView.value.steps.size)
		assertNull(rig.session.adjustableOperation.value)
		assertNull(rig.session.activeUvOperator.value)
	}

	/** The first latch seeds the shown surface's radius from its size, even with proportional editing off. */
	@Test
	fun theFirstLatchSeedsTheShownSurfacesRadius() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))

		rig.latch(MeshOperatorKind.Grab)

		assertEquals(UV_RIG_PAGE_SIDE / 8f, rig.radiusHolder.value.value)
	}

	/** The wheel grows the texel radius one step, leaves the session's world radius alone, and re-drives at once. */
	@Test
	fun theWheelResizesTheRadiusAndReDrives() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(tenTexelsRight)

		rig.transform.onScroll(-1f, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

		assertEquals(UV_RIG_PAGE_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(rig.radiusHolder.value.value), 1e-4f)
		assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld)
		assertEquals(2, rig.pushed.size, "the scroll drove a second preview")
	}

	/** After the host hands over another surface's radius, the wheel seeds and resizes that one and leaves the old one be. */
	@Test
	fun theWheelFollowsTheHoldersSwap() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		val pageRadius = rig.radiusHolder.value
		val layerRadius = rig.showLayer()

		rig.transform.onScroll(-1f, UV_RIG_PAGE_CAMERA, UV_RIG_AREA_SIZE)

		assertEquals(UV_RIG_LAYER_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(layerRadius.value), 1e-4f, "seeded from the layer, then grown")
		assertEquals(UV_RIG_PAGE_SIDE / 8f, pageRadius.value, "the page's radius is untouched")
	}

	/** A confirmed step's strip adjustment writes back to the radius of the surface the gesture ran on. */
	@Test
	fun anAdjustmentWritesTheRadiusTheConfirmCaptured() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
		val rig = rigOver(session)
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(tenTexelsRight)
		rig.transform.confirm()
		val pageRadius = rig.radiusHolder.value
		val layerRadius = rig.showLayer()
		val record = assertNotNull(session.adjustableOperation.value)
		val sizeRow = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.PROPORTIONAL_SIZE } as OperatorParameter.FloatParameter

		session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.PROPORTIONAL_SIZE, sizeRow.copy(value = 12f)))

		assertEquals(12f, pageRadius.value, "the step's own surface")
		assertNull(layerRadius.value, "not the surface shown now")
		assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld, "nor the session's world radius")
	}

	/** Proportional turned on mid-gesture weights the neighbors within the shown surface's radius. */
	@Test
	fun aMidGestureProportionalChangeWeightsTheNeighbors() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.latch(MeshOperatorKind.Grab)
		val entry = assertNotNull(rig.transform.gesture.capture).transform.entries.single()
		assertTrue(entry.influence.isEmpty(), "the latch took no weights with proportional off")

		rig.transform.reapplyProportional(ProportionalEditState(ProportionalFalloff.Linear, 10f))

		assertTrue(entry.influence.isNotEmpty(), "vertex 1 lies twenty texels away, inside the page's thirty-two")
	}

	/** Another area's latch drives nothing here. */
	@Test
	fun anotherAreasLatchDrivesNothing() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginUvOperator(MeshOperatorKind.Grab, RIGHT_AREA_ID)

		assertFalse(rig.driveTo(tenTexelsRight))
		assertTrue(rig.pushed.isEmpty())
	}

	/** Abandoning a live gesture clears this area's latch and asks for the resync. */
	@Test
	fun abandoningALiveGestureClearsItsLatch() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.latch(MeshOperatorKind.Grab)
		rig.driveTo(tenTexelsRight)

		assertTrue(rig.transform.abandon(), "a gesture was in flight")

		assertNull(rig.session.activeUvOperator.value)
		assertNull(rig.transform.gesture.capture)
		assertEquals(quadUvs, uvRigUvsOf(rig.session, UV_RIG_QUAD), "nothing committed")
		assertFalse(rig.transform.abandon(), "and only once")
	}

	/** Abandoning never clears a latch another area holds. */
	@Test
	fun abandoningLeavesAnotherAreasLatch() {
		val rig = rigOver(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
		rig.session.beginUvOperator(MeshOperatorKind.Grab, RIGHT_AREA_ID)
		val latched = rig.session.activeUvOperator.value

		assertFalse(rig.transform.abandon())

		assertSame(latched, rig.session.activeUvOperator.value)
	}

	private companion object {
		const val LEFT_AREA_ID = "left"
		const val RIGHT_AREA_ID = "right"
	}
}