package org.umamo.interop.art

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.art.alphaChannel
import org.umamo.geometry.predicate.orient2d
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.ArtMeshSettings
import org.umamo.interop.art.mesh.meshCoversOpaquePixels
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A birth mesh: the mesher's mesh placed as a drawable's, every vertex's canvas position and art-frame
 * coordinate two views of the same art pixel, every art pixel inside it, the quad's winding kept, and
 * a layer the mesher cannot mesh refused with its reason.
 */
class BirthMeshesTest {
	/** A 40 x 30 raster with an opaque disc of radius 12 at (20, 15) and a faint ring of alpha 60 around it. */
	private val disc =
		LayerRaster(
			40,
			30,
			ByteArray(40 * 30 * 4) { component ->
				val pixel = component / 4
				val distance = hypot(pixel % 40 + 0.5 - 20.0, pixel / 40 + 0.5 - 15.0)

				when {
					component % 4 != 3 -> 0
					distance <= 12.0 -> 0xFF.toByte()
					distance <= 14.0 -> 60
					else -> 0
				}
			},
		)

	private fun pending(settings: ArtMeshSettings = ArtMeshPreset.Standard.settings.copy(alphaThreshold = 1)): PendingBirth =
		PendingBirth(DrawableId("ArtMesh1"), "Disc", disc, layerLeft = 100, layerTop = 50, settings = settings)

	private fun bornMesh(): DrawableMesh {
		val outcome = BirthMesher.outcomeOf(pending(), BirthMesher.fit(pending()))

		return (outcome as BirthOutcome.Born).mesh
	}

	@Test
	fun aPlacedMeshSamplesTheArtUnderEveryVertexAndCoversItAll() {
		val mesh = bornMesh()

		assertTrue(mesh.vertexCount > 4, "a mesh, not a quad: ${mesh.vertexCount}")
		assertTrue(mesh.localPositions === mesh.positions, "a root drawable's base is its canvas mesh")

		for (vertex in 0 until mesh.vertexCount) {
			assertTrue(abs(mesh.uvs[2 * vertex] * 40f + 100f - mesh.positions[2 * vertex]) < 1e-3f, "vertex $vertex: x")
			assertTrue(abs(mesh.uvs[2 * vertex + 1] * 30f + 50f - mesh.positions[2 * vertex + 1]) < 1e-3f, "vertex $vertex: y")
		}

		val artPoints = FloatArray(mesh.uvs.size) { component -> mesh.uvs[component] * if (component % 2 == 0) 40f else 30f }
		assertTrue(meshCoversOpaquePixels(disc.alphaChannel(), 1, artPoints, mesh.indices), "the faint ring is covered too, at the import's threshold")
	}

	@Test
	fun aPlacedMeshKeepsTheQuadsWinding() {
		val mesh = bornMesh()
		val quad = birthQuad()
		val quadSign = orientationOf(quad, 0)

		for (triangle in 0 until mesh.triangleCount) {
			assertEquals(quadSign, orientationOf(mesh, triangle), "triangle $triangle winds like the quad")
		}
	}

	@Test
	fun aLayerTheMesherCannotMeshIsRefusedWithItsReason() {
		// Art only at alpha 60, meshed under a threshold of 200 - an atlas threshold above the import's.
		val faintArt = LayerRaster(10, 10, ByteArray(10 * 10 * 4) { component -> if (component % 4 == 3) 60 else 0 })
		val faint = PendingBirth(DrawableId("ArtMesh1"), "Faint", faintArt, layerLeft = 0, layerTop = 0, settings = ArtMeshPreset.Standard.settings.copy(alphaThreshold = 200))

		assertEquals(BirthOutcome.Refused(BirthRefusal.NothingOpaque), BirthMesher.outcomeOf(faint, BirthMesher.fit(faint)))
		assertEquals(BirthOutcome.Refused(BirthRefusal.NothingOpaque), BirthMesher.resolveSequentially(listOf(faint)).outcomes[faint.drawableId])
	}

	/**
	 * The birth quad over the disc's art, the mesh an import gives in the Quad mode.
	 *
	 * @return DrawableMesh The quad.
	 */
	private fun birthQuad(): DrawableMesh {
		val layer =
			object : SourceLayer {
				override val id = LayerId("lyid:1")
				override val idIsStable = true
				override val name = "Disc"
				override val groupPath = ""
				override val order = 0
				override val bounds = LayerBounds(100, 50, 40, 30)
				override val raster = disc
				override val kind = SourceLayerKind.Raster
				override val visible = true
				override val opacity = 1f
				override val clipped = false
				override val blend = LayerBlend.Normal
			}

		return SourceArtImport.birthMeshFor(layer, alphaThreshold = 1, margin = 2)!!
	}

	/**
	 * The sign of one triangle's orientation on its canvas positions.
	 *
	 * @param DrawableMesh mesh     The mesh.
	 * @param Int          triangle The triangle.
	 * @return Int 1 counterclockwise in raw coordinates, -1 clockwise, 0 flat.
	 */
	private fun orientationOf(mesh: DrawableMesh, triangle: Int): Int {
		val first = mesh.indices[3 * triangle]
		val second = mesh.indices[3 * triangle + 1]
		val third = mesh.indices[3 * triangle + 2]
		val area =
			orient2d(
				mesh.positions[2 * first].toDouble(),
				mesh.positions[2 * first + 1].toDouble(),
				mesh.positions[2 * second].toDouble(),
				mesh.positions[2 * second + 1].toDouble(),
				mesh.positions[2 * third].toDouble(),
				mesh.positions[2 * third + 1].toDouble(),
			)

		return area.compareTo(0.0)
	}
}