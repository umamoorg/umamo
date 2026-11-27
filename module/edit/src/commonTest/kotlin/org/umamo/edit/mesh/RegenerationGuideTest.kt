package org.umamo.edit.mesh

import org.umamo.edit.mesh.RegenerationFixtures.TILE_SIZE
import org.umamo.edit.mesh.RegenerationFixtures.drawableId
import org.umamo.edit.mesh.RegenerationFixtures.gridIndices
import org.umamo.edit.mesh.RegenerationFixtures.gridPoints
import org.umamo.geometry.triangulation.refine.areaScaleOf
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guide that keeps a rig through a regeneration: the old mesh at the art points the pins and the
 * transfer read, a field for the rest world, the keyform-space base, and the base moved by every
 * keyform cell and blend form - the forms at the base's scale, not their own - with every referenced
 * vertex but the pins as a candidate; and a generated vertex on an old vertex's point copies it exactly.
 */
class RegenerationGuideTest {
	@Test
	fun aGuideCarriesTheRestWorldTheBaseAndEveryForm() {
		val model = RegenerationFixtures.model()
		val input = RegenerationFixtures.readyInput(model)
		val mesh = model.drawables.first { drawable -> drawable.id == drawableId }.mesh!!
		val world = FloatArray(mesh.positions.size) { component -> 2f * mesh.positions[component] + 7f }
		val guide = assertNotNull(model.deformationGuideFor(input, world))
		val baseScale = areaScaleOf(gridPoints, gridIndices, mesh.localPositions)

		assertContentEquals(gridPoints, guide.points, "the guide lies on the art points the transfer reads")
		assertContentEquals(input.oldVertexPoints, guide.points, "read off the input, prepared once")
		assertContentEquals(gridIndices, guide.triangles)
		assertEquals(5, guide.fieldCount, "the world, the base, two keyform cells, and one blend form")
		assertContentEquals(world, guide.fields[0])
		assertEquals(areaScaleOf(gridPoints, gridIndices, world), guide.fieldScales[0])
		assertContentEquals(mesh.localPositions, guide.fields[1])
		assertEquals(baseScale, guide.fieldScales[1])

		for (field in 2 until 5) {
			assertEquals(baseScale, guide.fieldScales[field], "form $field reads at the base's scale")
		}

		val firstCell = RegenerationFixtures.deltasFor(mesh.vertexCount, 1)
		assertContentEquals(FloatArray(firstCell.size) { component -> mesh.localPositions[component] + firstCell[component] }, guide.fields[2])
		// Every referenced vertex but the glued ones (2 and 4).
		assertContentEquals(intArrayOf(0, 1, 3, 5, 6, 7, 8), guide.candidates)
	}

	@Test
	fun withoutAWorldTheGuideHoldsTheRigAlone() {
		val model = RegenerationFixtures.model()
		val guide = assertNotNull(model.deformationGuideFor(RegenerationFixtures.readyInput(model), null))

		assertEquals(4, guide.fieldCount)
	}

	@Test
	fun aFormThatMovesNothingAddsNoField() {
		val model = RegenerationFixtures.model()
		val drawable = model.drawables.first { candidate -> candidate.id == drawableId }
		val still =
			drawable.copy(
				geometryGrid = KeyformGrid(listOf(KeyformAxis(RegenerationFixtures.parameterId, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(2 * drawable.mesh!!.vertexCount))))),
				blendShapes = emptyList(),
			)
		val stillModel = model.copy(drawables = model.drawables.map { candidate -> if (candidate.id == drawableId) still else candidate })

		assertEquals(1, assertNotNull(stillModel.deformationGuideFor(RegenerationFixtures.readyInput(stillModel), null)).fieldCount, "only the base")
	}

	@Test
	fun aDrawableThatCannotBeRegeneratedHasNoGuide() {
		val model = RegenerationFixtures.model()
		val input = RegenerationFixtures.readyInput(model)
		val withoutMesh = model.copy(drawables = model.drawables.map { candidate -> if (candidate.id == drawableId) candidate.copy(mesh = null) else candidate })
		val otherMesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 8f, 0f, 0f, 8f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
		val withOtherMesh = model.copy(drawables = model.drawables.map { candidate -> if (candidate.id == drawableId) candidate.copy(mesh = otherMesh) else candidate })

		assertNull(withoutMesh.deformationGuideFor(input, null))
		assertNull(withOtherMesh.deformationGuideFor(input, null), "an input read off another mesh describes nothing here")
	}

	@Test
	fun aGeneratedVertexOnAnOldVertexCopiesItExactly() {
		val model = RegenerationFixtures.model(atlas = RegenerationFixtures.placedAtlas())
		val input = RegenerationFixtures.readyInput(model)
		val guide = assertNotNull(model.deformationGuideFor(input, null))
		// A coarse mesh over the tile plus one guide candidate at its exact point, as the mesher keeps it.
		val kept = guide.candidates.first { vertex -> vertex == 3 }
		val keptX = guide.points[2 * kept].toDouble()
		val keptY = guide.points[2 * kept + 1].toDouble()
		val coarse = doubleArrayOf(-4.0, -4.0, TILE_SIZE + 4.0, -4.0, TILE_SIZE + 4.0, TILE_SIZE + 4.0, -4.0, TILE_SIZE + 4.0, keptX, keptY)
		val built = RegenerationFixtures.built(model, RegenerationFixtures.generated(coarse, input.pinPoints))
		val newMesh = built.edit.newMesh
		val oldMesh = model.drawables.first { drawable -> drawable.id == drawableId }.mesh!!
		val newVertex = 4
		val source = built.edit.vertexSources[newVertex]

		assertTrue(source is VertexSource.Weighted && source.oldIndices.contentEquals(intArrayOf(kept)) && source.weights.contentEquals(floatArrayOf(1f)), "$source")
		assertEquals(oldMesh.positions[2 * kept], newMesh.positions[2 * newVertex])
		assertEquals(oldMesh.positions[2 * kept + 1], newMesh.positions[2 * newVertex + 1])
		assertEquals(oldMesh.localPositions[2 * kept], newMesh.localPositions[2 * newVertex])
		assertEquals(oldMesh.uvs[2 * kept], newMesh.uvs[2 * newVertex])
		assertEquals(oldMesh.uvs[2 * kept + 1], newMesh.uvs[2 * newVertex + 1])
	}
}