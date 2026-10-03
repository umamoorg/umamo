package org.umamo.interop.uma

import org.umamo.format.uma.puppet.UmaAxis
import org.umamo.format.uma.puppet.UmaDrawable
import org.umamo.format.uma.puppet.UmaMesh
import org.umamo.format.uma.puppet.UmaMeshBlendShape
import org.umamo.format.uma.puppet.UmaMeshCell
import org.umamo.format.uma.puppet.UmaMeshForm
import org.umamo.format.uma.puppet.UmaMeshGrid
import org.umamo.format.uma.puppet.UmaParameter
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.positionsFromDeltas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the reader's conversion of the 0.4.0 mesh shape (UMA §4.10): one array, the canvas mesh, with every
 * delta measured from it whatever space the keyforms were in.  A keyed drawable takes its reference cell's shape
 * as its base and has every keyform and blend form measured again from it, each rebuilding to the float 0.4.0
 * showed; an unkeyed drawable keeps its one array, and one under a deformer is reported for the loader to map.
 */
class UmaLegacyMeshTest {
	// A canvas mesh four thousand pixels out, the magnitude a corpus canvas has.
	private val canvas = floatArrayOf(4000f, 3000f, 4010f, 3000f, 4010f, 3010f)
	private val uvs = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
	private val indices = intArrayOf(0, 1, 2)

	// A warp child's keyforms on its 0..1 lattice, at keys -1, 0 (the default), and 1.
	private val latticeShapes =
		listOf(
			floatArrayOf(0.20f, 0.30f, 0.21f, 0.30f, 0.21f, 0.31f),
			floatArrayOf(0.25f, 0.35f, 0.26f, 0.35f, 0.26f, 0.36f),
			floatArrayOf(0.30f, 0.40f, 0.31f, 0.40f, 0.31f, 0.41f),
		)

	/**
	 * A delta array the way 0.4.0 measured it: the shape minus the canvas mesh, in float.
	 *
	 * @param FloatArray shape The keyform's absolute positions.
	 * @return FloatArray The deltas.
	 */
	private fun canvasDeltas(shape: FloatArray): FloatArray = FloatArray(shape.size) { componentIndex -> shape[componentIndex] - canvas[componentIndex] }

	/**
	 * A puppet entry holding [drawables] and the one parameter P (default 0).
	 *
	 * @param List<UmaDrawable> drawables The drawables.
	 * @return UmaPuppet The entry.
	 */
	private fun puppet(drawables: List<UmaDrawable>): UmaPuppet = UmaPuppet(parameters = listOf(UmaParameter("P", "P", -1f, 1f, 0f)), drawables = drawables)

	/**
	 * A drawable over the 0.4.0 mesh.
	 *
	 * @param String         id             The id.
	 * @param String?        parentDeformer The parent deformer, or null.
	 * @param UmaMeshGrid?   geometry       The keyform grid, or null.
	 * @param List?          blendShapes    The blend shapes, or null.
	 * @return UmaDrawable The drawable.
	 */
	private fun legacyDrawable(id: String, parentDeformer: String?, geometry: UmaMeshGrid?, blendShapes: List<UmaMeshBlendShape>? = null): UmaDrawable =
		UmaDrawable(id = id, name = id, parentDeformer = parentDeformer, mesh = UmaMesh(positions = canvas.copyOf(), uvs = uvs, indices = indices), geometry = geometry, blendShapes = blendShapes)

	/** A keyed warp child's base is its default cell's shape, and every form rebuilds to what 0.4.0 showed. */
	@Test
	fun aKeyedDrawableIsMeasuredAgainFromItsReferenceCell() {
		val oldDeltas = latticeShapes.map(::canvasDeltas)
		val grid = UmaMeshGrid(listOf(UmaAxis("P", listOf(-1f, 0f, 1f))), oldDeltas.mapIndexed { keyIndex, deltas -> UmaMeshCell(listOf(keyIndex), deltas) })
		val blendDeltas = canvasDeltas(floatArrayOf(0.27f, 0.37f, 0.28f, 0.37f, 0.28f, 0.38f))
		val blend = UmaMeshBlendShape("P", listOf(0f, 1f), 0, listOf(null, UmaMeshForm(blendDeltas)))
		val record = puppet(listOf(legacyDrawable("D", "W", grid, listOf(blend))))
		val model = UmaPuppetImport.modelOf(record)
		val drawable = model.drawables.single()
		val mesh = drawable.mesh!!

		assertEquals(canvas.toList(), mesh.positions.toList(), "the canvas mesh is the 0.4.0 array")
		assertEquals(positionsFromDeltas(canvas, oldDeltas[1]).toList(), mesh.localPositions.toList(), "the base is the default cell as 0.4.0 rebuilt it")
		for ((cellIndex, cell) in drawable.geometryGrid!!.cells.withIndex()) {
			val shown = positionsFromDeltas(canvas, oldDeltas[cellIndex])
			assertEquals(shown.map(Float::toRawBits), positionsFromDeltas(mesh.localPositions, cell.form.positionDeltas).map(Float::toRawBits), "cell $cellIndex rebuilds to what 0.4.0 showed")
		}
		val form = drawable.blendShapes.single().forms[1]!!
		assertEquals(positionsFromDeltas(canvas, blendDeltas).map(Float::toRawBits), positionsFromDeltas(mesh.localPositions, form.positionDeltas).map(Float::toRawBits), "the blend form rebuilds to what 0.4.0 showed")
		assertTrue(drawable.geometryGrid!!.cells[1].form.positionDeltas.all { delta -> delta == 0f }, "the reference cell's delta is zero")
		assertTrue(UmaPuppetImport.basesToDerive(record, model).isEmpty(), "a keyed drawable needs no derivation")
	}

	/**
	 * An unkeyed drawable keeps its one array; under a deformer - no grid, or a rest-only one - it is reported,
	 * since its base has to come from the deformer's inverse.
	 */
	@Test
	fun anUnkeyedDrawableUnderADeformerIsReportedForTheLoader() {
		val restOnly = UmaMeshGrid(emptyList(), listOf(UmaMeshCell(emptyList(), FloatArray(canvas.size))))
		val record =
			puppet(
				listOf(
					legacyDrawable("Root", null, null),
					legacyDrawable("Child", "W", null),
					legacyDrawable("RestOnlyChild", "W", restOnly),
				),
			)
		val model = UmaPuppetImport.modelOf(record)
		for (drawable in model.drawables) {
			assertSame(drawable.mesh!!.positions, drawable.mesh!!.localPositions, "${drawable.id.raw} keeps its one array")
		}
		assertEquals(listOf(DrawableId("Child"), DrawableId("RestOnlyChild")), UmaPuppetImport.basesToDerive(record, model))
	}

	/** A mesh in today's shape is read as written, and never reported, whatever its parent. */
	@Test
	fun aMeshInTodaysShapeIsNeverReported() {
		val local = floatArrayOf(0.2f, 0.3f, 0.21f, 0.3f, 0.21f, 0.31f)
		val record = puppet(listOf(UmaDrawable(id = "Child", name = "Child", parentDeformer = "W", mesh = UmaMesh(canvasPositions = canvas, localPositions = local, uvs = uvs, indices = indices))))
		val model = UmaPuppetImport.modelOf(record)
		assertEquals(local.toList(), model.drawables.single().mesh!!.localPositions.toList())
		assertTrue(UmaPuppetImport.basesToDerive(record, model).isEmpty())
	}
}