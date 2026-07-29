package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The geometry-track key ops - the half of the keyform sheet that has no channel behind it.
 *
 * Worth its own suite because geometry is the majority of what a corpus rig puts on screen, and because
 * its hide semantics differ per owner: a drawable falls back to its rest mesh when its grid goes away, a
 * deformer has no rest lattice and would vanish.
 */
class KeyformGeometryEditsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val drawableId = DrawableId("d")
	private val deformerId = DeformerId("w")
	private val parameter = Parameter(angleX, "ParamAngleX", min = -30f, max = 30f, default = 0f)

	/** A three-key mesh-delta grid over ParamAngleX, one delta per key. */
	private fun meshGrid(): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			axes = listOf(KeyformAxis(angleX, floatArrayOf(-30f, 0f, 30f))),
			cells =
				listOf(
					KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(-1f, 0f))),
					KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(0f, 0f))),
					KeyformCell(intArrayOf(2), MeshDeltaForm(floatArrayOf(1f, 0f))),
				),
		)

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(parameter),
			parts = emptyList(),
			deformers =
				listOf(
					Deformer.Warp(
						id = deformerId,
						name = "w",
						parent = null,
						partId = null,
						rows = 2,
						columns = 2,
						isQuadTransform = true,
						geometryGrid =
							KeyformGrid(
								axes = listOf(KeyformAxis(angleX, floatArrayOf(-30f, 0f, 30f))),
								cells =
									listOf(
										KeyformCell(intArrayOf(0), WarpLatticeForm(FloatArray(8))),
										KeyformCell(intArrayOf(1), WarpLatticeForm(FloatArray(8))),
										KeyformCell(intArrayOf(2), WarpLatticeForm(FloatArray(8))),
									),
							),
					),
				),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "d",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = null,
						geometryGrid = meshGrid(),
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * A two-parameter model whose drawable keys on the VERTICAL axis only - the linked-pad shape.
	 *
	 * A pad targets both its axes but reports only the horizontal one as active, so this is the model where
	 * "write on the active parameter" and "write on the one the rigger meant" give different answers.
	 */
	private fun padModel(): PuppetModel {
		val vertical = Parameter(angleY, "ParamAngleY", min = -30f, max = 30f, default = 0f)
		val onVertical = KeyformGrid(listOf(KeyformAxis(angleY, floatArrayOf(-30f, 0f, 30f))), meshGrid().cells)
		val base = model()
		return base.copy(
			parameters = listOf(parameter, vertical),
			drawables = listOf(base.drawables.single().copy(geometryGrid = onVertical)),
		)
	}

	/** The drawable's geometry axis keys after an edit. */
	private fun keysOf(puppet: PuppetModel): List<Float> =
		assertNotNull(puppet.drawables.single().geometryGrid).axes.single().keys.toList()

	/** Moving a geometry key repositions it without touching the cells it addresses. */
	@Test
	fun movingAGeometryKeyRepositionsIt() {
		val moved = model().withGeometryKeyMoved(KeyformOwner.Drawable(drawableId), parameter, keyIndex = 1, toValue = 12f)
		assertEquals(listOf(-30f, 12f, 30f), keysOf(moved))
		assertEquals(
			listOf(-1f, 0f, 1f),
			assertNotNull(moved.drawables.single().geometryGrid)
				.cells
				.sortedBy { cell -> cell.coordinate[0] }
				.map { cell -> cell.form.positionDeltas[0] },
			"a move changes key positions only - the cells stay where they were",
		)
	}

	/**
	 * A key dragged past its neighbour CROSSES it, taking its own form along.
	 *
	 * The axis re-sorts and the cells permute to match, so the deformation the rigger authored stays with
	 * the key they dragged rather than staying at the ordinal it vacated.
	 */
	@Test
	fun movingAGeometryKeyCrossesItsNeighbour() {
		val moved = model().withGeometryKeyMoved(KeyformOwner.Drawable(drawableId), parameter, keyIndex = 1, toValue = 99f)
		assertEquals(listOf(-30f, 30f, 99f), keysOf(moved), "the axis re-sorts")
		assertEquals(
			listOf(-1f, 1f, 0f),
			assertNotNull(moved.drawables.single().geometryGrid)
				.cells
				.sortedBy { cell -> cell.coordinate[0] }
				.map { cell -> cell.form.positionDeltas[0] },
			"the moved key's own form (0) travelled to the end; the key it passed (1) shifted down",
		)
	}

	/** Inserting a geometry key holds the interpolated form, so the deformation through it is unchanged. */
	@Test
	fun insertingAGeometryKeyHoldsTheInterpolatedForm() {
		val inserted = model().withGeometryKeyInserted(KeyformOwner.Drawable(drawableId), parameter, 15f)
		assertEquals(listOf(-30f, 0f, 15f, 30f), keysOf(inserted))
		val grid = assertNotNull(inserted.drawables.single().geometryGrid)
		// Halfway between the deltas at 0 and 30, which hold 0 and 1.
		val insertedCell = assertNotNull(grid.cells.firstOrNull { cell -> cell.coordinate[0] == 2 })
		assertEquals(0.5f, insertedCell.form.positionDeltas[0], 1e-5f)
	}

	/** Removing down past two keys collapses a drawable's axis - it falls back to its rest mesh. */
	@Test
	fun removingCollapsesADrawablesAxis() {
		val owner = KeyformOwner.Drawable(drawableId)
		val once = model().withGeometryKeyRemoved(owner, parameter, keyIndex = 1)
		assertEquals(listOf(-30f, 30f), keysOf(once))
		val twice = once.withGeometryKeyRemoved(owner, parameter, keyIndex = 1)
		assertNull(twice.drawables.single().geometryGrid, "below two keys the axis collapses entirely")
	}

	/** The same removal is REFUSED on a deformer, which has no rest lattice to fall back to. */
	@Test
	fun removingRefusesToUnkeyADeformer() {
		val owner = KeyformOwner.Deformer(deformerId)
		val once = model().withGeometryKeyRemoved(owner, parameter, keyIndex = 1)
		val warp = once.deformers.single() as Deformer.Warp
		assertEquals(listOf(-30f, 30f), assertNotNull(warp.geometryGrid).axes.single().keys.toList())
		val refused = once.withGeometryKeyRemoved(owner, parameter, keyIndex = 1)
		assertSame(once, refused, "collapsing a deformer's last axis would hide it, so it is refused")
	}

	/** A part carries no geometry, so every op leaves the model alone rather than throwing. */
	@Test
	fun ownersWithoutGeometryAreLeftAlone() {
		val puppet = model()
		val owner = KeyformOwner.Glue(drawableId, drawableId)
		assertSame(puppet, puppet.withGeometryKeyMoved(owner, parameter, keyIndex = 0, toValue = 10f))
		assertSame(puppet, puppet.withGeometryKeyInserted(owner, parameter, 10f))
		assertSame(puppet, puppet.withGeometryKeyRemoved(owner, parameter, keyIndex = 0))
	}

	/**
	 * Two keys close enough to draw on the same pixel are still individually addressable.
	 *
	 * The case that made dragging one of a near-coincident pair move the other: a value-based lookup
	 * resolves to whichever key is nearer, and within EPS_KEY that is a coin toss.  Nothing forbids keys
	 * this close - forbidding them would be the wrong cure - so the ordinal has to carry the identity.
	 */
	@Test
	fun nearCoincidentKeysAreStillIndividuallyAddressable() {
		val owner = KeyformOwner.Drawable(drawableId)
		// Walk the middle key to within a hair of the last one, then move the LAST one and check the
		// middle stayed put - a value-based lookup would have grabbed whichever it reached first.
		val crowded = model().withGeometryKeyMoved(owner, parameter, keyIndex = 1, toValue = 30f)
		val middle = keysOf(crowded)[1]
		assertTrue(30f - middle < 0.01f, "the middle key should have clamped right up against the last (at $middle)")
		val moved = crowded.withGeometryKeyMoved(owner, parameter, keyIndex = 2, toValue = 99f)
		assertEquals(middle, keysOf(moved)[1], "moving the last key must not disturb the one beside it")
	}

	/**
	 * A pose resolves to the ordinal of the key it is standing on, and to nothing between keys.
	 *
	 * What `Alt+I` acts on.  Removing "the nearest key" instead would guess at which one the user meant,
	 * and a wrong guess silently destroys authored work.
	 */
	@Test
	fun aPoseResolvesToTheKeyItStandsOn() {
		val puppet = model()
		val geometry = KeyformTrackRef.Geometry(KeyformOwner.Drawable(drawableId))
		assertEquals(1, puppet.trackKeyIndexAtPose(geometry, parameter, mapOf(angleX to 0f)))
		assertEquals(2, puppet.trackKeyIndexAtPose(geometry, parameter, mapOf(angleX to 30f)))
		assertEquals(-1, puppet.trackKeyIndexAtPose(geometry, parameter, mapOf(angleX to 15f)), "between keys")
	}

	/** An owner with no geometry resolves to nothing rather than throwing. */
	@Test
	fun aTrackWithNoGridResolvesToNothing() {
		val glue = KeyformTrackRef.Geometry(KeyformOwner.Glue(drawableId, drawableId))
		assertEquals(-1, model().trackKeyIndexAtPose(glue, parameter, mapOf(angleX to 0f)))
	}

	/**
	 * Moving several keys to one destination is ONE undo step, and each lands on its own track.
	 *
	 * The summary-row drag: marks stacked at a value stay stacked, and one Ctrl+Z restores all of them.
	 */
	@Test
	fun movingSeveralKeysToOneValueIsOneStep() {
		val session = EditorSession(model())
		val drawableTrack = KeyformTrackRef.Geometry(KeyformOwner.Drawable(drawableId))
		val deformerTrack = KeyformTrackRef.Geometry(KeyformOwner.Deformer(deformerId))
		session.moveTrackKeys(
			listOf(Triple(drawableTrack, parameter, 1), Triple(deformerTrack, parameter, 1)),
			toValue = 12f,
		)

		assertEquals(listOf(-30f, 12f, 30f), keysOf(session.model.value), "the drawable's key moved")
		val warp = session.model.value.deformers.single() as Deformer.Warp
		assertEquals(
			listOf(-30f, 12f, 30f),
			assertNotNull(warp.geometryGrid).axes.single().keys.toList(),
			"and so did the deformer's, to the same place",
		)

		session.undo()
		assertEquals(listOf(-30f, 0f, 30f), keysOf(session.model.value), "one undo restores both")
		assertTrue(!session.canUndo.value, "because there was only ever one step")
	}

	/**
	 * With two parameters targeted and no axis named, an unaimed key edit PARKS instead of guessing.
	 *
	 * The linked-pad case: a 2D pad targets both its axes but reports only the horizontal one as active, so
	 * writing on the active one would silently always key the horizontal parameter and leave the vertical
	 * section reading as unkeyed.  Answering the prompt replays the very same edit with the axis filled in.
	 */
	@Test
	fun anAmbiguousTargetParksTheEditUntilAnAxisIsPicked() {
		// The drawable keys on the VERTICAL axis only, while the pad reports the horizontal one as active -
		// so writing on the active parameter would refuse outright and the pick has to be what decides.
		val session = EditorSession(padModel())
		session.setParameterSelection(ParameterSelection(setOf(angleX, angleY), active = angleX))
		val track = KeyformTrackRef.Geometry(KeyformOwner.Drawable(drawableId))

		session.captureKeyOnTrack(track, parameterId = null, aim = KeyformAim.Position(15f, keyIndex = null))
		val parked = assertNotNull(session.pendingParameterChoice.value, "the edit parks rather than guessing")
		assertEquals(KeyformAction.Capture, parked.action)
		assertContentEquals(listOf(angleX, angleY), parked.candidates, "listed in model order")
		assertEquals(listOf(-30f, 0f, 30f), keysOf(session.model.value), "and nothing was written yet")

		session.resolveParameterChoice(angleY)
		assertNull(session.pendingParameterChoice.value, "answering clears the prompt")
		assertEquals(listOf(-30f, 0f, 15f, 30f), keysOf(session.model.value), "the key landed on the PICKED axis")
	}

	/** A caller that names its axis - every sheet lane does - never sees the prompt. */
	@Test
	fun aNamedAxisBypassesTheChoicePrompt() {
		val session = EditorSession(padModel())
		session.setParameterSelection(ParameterSelection(setOf(angleX, angleY), active = angleX))
		val track = KeyformTrackRef.Geometry(KeyformOwner.Drawable(drawableId))

		session.captureKeyOnTrack(track, parameterId = angleY, aim = KeyformAim.Position(15f, keyIndex = null))
		assertNull(session.pendingParameterChoice.value, "a named axis is the answer, so nothing is asked")
		assertEquals(listOf(-30f, 0f, 15f, 30f), keysOf(session.model.value))
	}

	/**
	 * Dragging a multi-key selection moves every member by the same distance, keeping their spacing.
	 *
	 * The whole point of a group drag: a delta applied per key preserves the shape the rigger authored,
	 * where the summary drag's send-them-all-to-one-value deliberately does not.
	 */
	@Test
	fun draggingASelectionMovesEveryKeyByTheSameDistance() {
		val session = EditorSession(model())
		val track = KeyformTrackRef.Geometry(KeyformOwner.Drawable(drawableId))
		// -30 and 0, dragged a tenth of the 60-wide range: +6 each.
		val landed = session.dragTrackKeys(listOf(Triple(track, parameter, 0), Triple(track, parameter, 1)), fraction = 0.1f)

		assertEquals(listOf(-24f, 6f, 30f), keysOf(session.model.value))
		assertEquals(listOf(0, 1), landed, "neither key crossed anything, so both keep their ordinals")
		session.undo()
		assertEquals(listOf(-30f, 0f, 30f), keysOf(session.model.value), "one undo restores the whole drag")
		assertTrue(!session.canUndo.value, "because it was one step")
	}

	/**
	 * The drag stops at the MOST CONSTRAINED member rather than clamping each key on its own.
	 *
	 * Per-key clamping would let the keys nearest the end pile up while the rest kept travelling, silently
	 * destroying the spacing - which is exactly the thing a group drag exists to preserve.
	 */
	@Test
	fun aGroupDragStopsAtItsMostConstrainedMember() {
		val session = EditorSession(model())
		val track = KeyformTrackRef.Geometry(KeyformOwner.Drawable(drawableId))
		// The key at 30 is already at the maximum, so the whole drag is refused rather than collapsing the pair.
		session.dragTrackKeys(listOf(Triple(track, parameter, 1), Triple(track, parameter, 2)), fraction = 0.5f)
		assertEquals(listOf(-30f, 0f, 30f), keysOf(session.model.value), "nothing moved, so nothing piled up")

		// Half of that is reachable: the key at 0 can take +15, the key at 30 cannot move at all.
		session.dragTrackKeys(listOf(Triple(track, parameter, 0), Triple(track, parameter, 1)), fraction = 0.25f)
		assertEquals(listOf(-15f, 15f, 30f), keysOf(session.model.value), "both moved the same 15, and the gap is intact")
	}

	/** A key that crosses a neighbour reports its NEW ordinal, so the caller's selection can follow it. */
	@Test
	fun aGroupDragReportsWhereEachKeyLanded() {
		val session = EditorSession(model())
		val track = KeyformTrackRef.Geometry(KeyformOwner.Drawable(drawableId))
		// Only the FIRST key is selected; dragging it +40 carries it past the key at 0.
		val landed = session.dragTrackKeys(listOf(Triple(track, parameter, 0)), fraction = 40f / 60f)
		assertEquals(listOf(0f, 10f, 30f), keysOf(session.model.value), "it crossed the key at 0 and the axis re-sorted")
		assertEquals(listOf(1), landed, "and it now sits at ordinal 1")
	}

	/** Each member is clamped to its OWN parameter's range, so a mixed batch cannot push one out of range. */
	@Test
	fun aBatchMoveClampsPerParameter() {
		val session = EditorSession(model())
		val track = KeyformTrackRef.Geometry(KeyformOwner.Drawable(drawableId))
		session.moveTrackKeys(listOf(Triple(track, parameter, 1)), toValue = 9999f)
		assertEquals(30f, keysOf(session.model.value).last(), "clamped to the parameter's maximum")
	}
}
