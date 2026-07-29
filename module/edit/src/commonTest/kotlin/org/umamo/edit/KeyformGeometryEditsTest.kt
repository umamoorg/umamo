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

	/** A key dragged past its neighbour clamps rather than crossing it. */
	@Test
	fun movingAGeometryKeyClampsAtItsNeighbour() {
		val moved = model().withGeometryKeyMoved(KeyformOwner.Drawable(drawableId), parameter, keyIndex = 1, toValue = 999f)
		val keys = keysOf(moved)
		assertTrue(keys[1] < 30f, "the middle key must stop short of the one at 30 (got ${keys[1]})")
		assertEquals(listOf(-30f, 30f), listOf(keys[0], keys[2]), "its neighbours do not move")
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
}
