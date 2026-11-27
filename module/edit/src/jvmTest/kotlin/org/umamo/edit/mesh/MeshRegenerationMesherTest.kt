package org.umamo.edit.mesh

import org.umamo.edit.mesh.RegenerationFixtures.drawableId
import org.umamo.format.art.LayerRaster
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.generateArtMesh
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The transfer over a mesh the real art mesher made: a disc's birth quad, glued at two corners,
 * regenerated at Standard.  The mesher keeps the glued corners as pins and the transfer keeps their
 * glue, and since a birth quad's canvas is its art moved by the layer's origin, every new vertex -
 * the outline vertices outside the quad included - lands exactly there.
 */
class MeshRegenerationMesherTest {
	private val rasterSize = 100
	private val originX = 200f
	private val originY = 300f

	@Test
	fun aRealMeshKeepsGlueAndLandsOnTheArtPlusTheOrigin() {
		val raster = discRaster()
		val model = birthQuadModel()
		val input = RegenerationFixtures.readyInput(model)
		assertContentEquals(intArrayOf(0, 2), input.pinnedOldVertices)
		val generated = assertNotNull(generateArtMesh(raster, ArtMeshPreset.Standard.settings, input.pinPoints).mesh)
		val built = model.meshRegeneration(input, generated) as? MeshRegeneration.Built ?: error("refused: ${model.meshRegeneration(input, generated)}")
		assertTrue(built.extrapolatedVertices.isNotEmpty(), "the outline reaches past the quad")
		val edited = model.withMeshTopologyEdit(drawableId, built.edit)
		assertEquals(listOf(generated.pinVertices[0] to 0, generated.pinVertices[1] to 1), edited.glues.single().pairs.map { it.indexA to it.indexB })
		val newMesh = edited.drawables.first { it.id == drawableId }.mesh!!
		assertTrue(newMesh.localPositions === newMesh.positions, "a root drawable keeps one shared array")

		for (vertex in 0 until generated.vertexCount) {
			assertEquals(generated.positions[2 * vertex] + originX, newMesh.positions[2 * vertex], 1e-3f, "vertex $vertex x")
			assertEquals(generated.positions[2 * vertex + 1] + originY, newMesh.positions[2 * vertex + 1], 1e-3f, "vertex $vertex y")
		}
	}

	/**
	 * A disc of radius 40 in the middle of the raster.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun discRaster(): LayerRaster {
		val rgba = ByteArray(rasterSize * rasterSize * 4)

		for (row in 0 until rasterSize) {
			for (column in 0 until rasterSize) {
				val offsetX = column + 0.5 - 50.0
				val offsetY = row + 0.5 - 50.0

				if (offsetX * offsetX + offsetY * offsetY <= 40.0 * 40.0) {
					rgba[(row * rasterSize + column) * 4 + 3] = 0xFF.toByte()
				}
			}
		}

		return LayerRaster(rasterSize, rasterSize, rgba)
	}

	/**
	 * The disc's birth quad (its opaque bounds plus 2 px, in art pixels), moved to the layer's canvas
	 * origin, glued at its top-left and bottom-right corners to the partner.
	 *
	 * @return PuppetModel The model.
	 */
	private fun birthQuadModel(): PuppetModel {
		val corners = floatArrayOf(8f, 8f, 92f, 8f, 92f, 92f, 8f, 92f)
		val canvas = FloatArray(8) { component -> corners[component] + if (component % 2 == 0) originX else originY }
		val uvs = FloatArray(8) { component -> corners[component] / rasterSize }
		val mesh = DrawableMesh.withLocalEqualToCanvas(canvas, uvs, intArrayOf(0, 1, 2, 0, 2, 3))
		val atlas = PuppetAtlas(tiles = listOf(AtlasTile(RegenerationFixtures.tileId, "tile", rasterSize, rasterSize)))
		val glue = Glue(drawableId, RegenerationFixtures.partnerId, listOf(GluePair(0, 0, 0.5f, 0.5f), GluePair(2, 1, 0.5f, 0.5f)))

		return RegenerationFixtures.modelOf(listOf(RegenerationFixtures.riggedDrawable(mesh), RegenerationFixtures.partnerDrawable()), listOf(glue), atlas)
	}
}