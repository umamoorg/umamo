package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins the batch mesh folds against the per-drawable ones they replace in the commits and the modal drive:
 * the same model content as folding entry by entry, every untouched drawable shared, the same instance back
 * on every no-op (which is how a session tells a no-op commit from an edit), and only the first drawable of
 * a duplicated id touched, as the per-drawable fold's first-match lookup does.
 */
class MeshBatchFoldTest {
	private val first = DrawableId("first")
	private val second = DrawableId("second")
	private val third = DrawableId("third")

	@Test
	fun theBatchFoldsTheSameModelAsTheSerialFold() {
		val model = model()
		val edits = mapOf(first to triangle(5f), third to triangle(7f))

		val batch = model.withMeshPositions(edits)
		val serial = edits.entries.fold(model) { folded, (drawableId, positions) -> folded.withMeshPositions(drawableId, positions) }

		assertEquals(serial.drawables.map { drawable -> drawable.id }, batch.drawables.map { drawable -> drawable.id })
		for (drawableIndex in serial.drawables.indices) {
			val serialMesh = serial.drawables[drawableIndex].mesh
			val batchMesh = batch.drawables[drawableIndex].mesh
			assertSame(serialMesh?.positions, batchMesh?.positions, "drawable $drawableIndex's positions")
			assertSame(serialMesh?.uvs, batchMesh?.uvs, "and its uvs")
		}
		assertSame(model.drawables[1], batch.drawables[1], "an untouched drawable is shared")
	}

	@Test
	fun theUvBatchReplacesUvsAndSharesThePositions() {
		val model = model()
		val newUvs = triangle(0.5f)

		val batch = model.withMeshUvs(mapOf(second to newUvs))

		val mesh = batch.drawables[1].mesh!!
		assertSame(newUvs, mesh.uvs)
		assertSame(model.drawables[1].mesh!!.positions, mesh.positions, "the rest geometry is shared")
		assertSame(model.drawables[0], batch.drawables[0])
	}

	@Test
	fun everyNoOpReturnsTheSameModel() {
		val model = model()
		val held = model.drawables[0].mesh!!

		assertSame(model, model.withMeshPositions(emptyMap()), "no edits")
		assertSame(model, model.withMeshPositions(mapOf(DrawableId("missing") to triangle(1f))), "an unknown id")
		assertSame(model, model.withMeshPositions(mapOf(first to held.positions)), "the array it holds")
		assertSame(model, model.withMeshPositions(mapOf(first to FloatArray(4))), "a length mismatch")
		assertSame(model, model.withMeshUvs(mapOf(first to held.uvs)), "the uvs it holds")
		val meshless = model.copy(drawables = model.drawables + drawable("bare", null))
		assertSame(meshless, meshless.withMeshPositions(mapOf(DrawableId("bare") to triangle(1f))), "a drawable with no mesh")
	}

	@Test
	fun aNoOpBesideAnEditIsSkipped() {
		val model = model()
		val moved = triangle(9f)

		val batch = model.withMeshPositions(mapOf(first to FloatArray(4), second to moved))

		assertNotSame(model, batch)
		assertSame(model.drawables[0], batch.drawables[0], "the mismatched entry is left alone")
		assertSame(moved, batch.drawables[1].mesh!!.positions)
	}

	@Test
	fun onlyTheFirstDrawableOfAnIdIsTouched() {
		val model = model()
		val duplicated = model.copy(drawables = model.drawables + drawable(first.raw, triangle(0f)))
		val moved = triangle(3f)

		val batch = duplicated.withMeshPositions(mapOf(first to moved))

		assertSame(moved, batch.drawables[0].mesh!!.positions, "the first one moves")
		assertSame(duplicated.drawables[3], batch.drawables[3], "the second one does not, as with the per-drawable fold")
		assertContentEquals(duplicated.withMeshPositions(first, moved).drawables[3].mesh!!.positions, batch.drawables[3].mesh!!.positions)
	}

	/**
	 * Three meshed drawables, each a triangle.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable(first.raw, triangle(0f)), drawable(second.raw, triangle(1f)), drawable(third.raw, triangle(2f))),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * One drawable over [positions], with its own uvs, or none when [positions] is null.
	 *
	 * @param String id The drawable id.
	 * @param FloatArray? positions The rest positions, or null for no mesh.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, positions: FloatArray?): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = positions?.let { meshPositions -> DrawableMesh(meshPositions, triangle(0.25f), intArrayOf(0, 1, 2)) },
			geometryGrid = null,
		)

	/**
	 * A triangle's interleaved coordinates, offset by [offset], a new array each call.
	 *
	 * @param Float offset The offset added to every coordinate.
	 * @return FloatArray The coordinates.
	 */
	private fun triangle(offset: Float): FloatArray = floatArrayOf(offset, offset, offset + 1f, offset, offset, offset + 1f)
}