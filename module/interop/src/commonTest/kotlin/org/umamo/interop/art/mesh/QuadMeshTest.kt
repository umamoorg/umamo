package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaChannel
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.art.alphaChannel
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.predicate.orient2d
import org.umamo.interop.art.SourceArtImport
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.isUntouchedBirthQuad
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * quadMeshOver: the import's quad made from an alpha channel - the art's bounds grown by the margin, in
 * the import's corner order and diagonal, unclamped - and, with glued points, the quad and the points
 * triangulated together, each point a vertex at its exact Float position.
 */
class QuadMeshTest {
	/** A 20 x 10 raster whose art fills columns 5 to 9 and rows 3 to 6. */
	private val block = rasterOf(20, 10) { column, row -> column in 5..9 && row in 3..6 }

	@Test
	fun aQuadIsTheArtsBoundsGrownByTheMargin() {
		val quad = assertNotNull(quadMeshOver(block.alphaChannel(), alphaThreshold = 1, margin = 2))

		assertContentEquals(floatArrayOf(3f, 1f, 12f, 1f, 12f, 9f, 3f, 9f), quad.positions)
		assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3), quad.triangles)
		assertEquals(0, quad.pinVertices.size)
		assertTiles(quad, 3.0, 1.0, 12.0, 9.0)
	}

	@Test
	fun itIsTheImportsQuadInTheArtFrame() {
		val disc = rasterOf(40, 30) { column, row -> inDisc(column, row, 20.0, 15.0, 12.0) }
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
		val birth = assertNotNull(SourceArtImport.birthMeshFor(layer, alphaThreshold = 1, margin = 2))
		val quad = assertNotNull(quadMeshOver(disc.alphaChannel(), alphaThreshold = 1, margin = 2))
		val lifted =
			DrawableMesh.withLocalEqualToCanvas(
				positions = FloatArray(8) { component -> quad.positions[component] + if (component % 2 == 0) 100f else 50f },
				uvs = FloatArray(8) { component -> quad.positions[component] / if (component % 2 == 0) 40f else 30f },
				indices = quad.triangles,
			)

		assertContentEquals(birth.positions, lifted.positions)
		assertContentEquals(birth.uvs, lifted.uvs)
		assertContentEquals(birth.indices, lifted.indices)
		assertTrue(lifted.isUntouchedBirthQuad(lifted.uvs, 40, 30, 100f, 50f), "a reload takes it for the import's own")
	}

	@Test
	fun faintArtCountsOnlyAtTheThreshold() {
		val faintCorner =
			channelOf(20, 12) { column, row ->
				when {
					column in 5..9 && row in 3..6 -> 255
					column == 15 && row == 8 -> 60
					else -> 0
				}
			}

		assertContentEquals(floatArrayOf(5f, 3f, 10f, 3f, 10f, 7f, 5f, 7f), assertNotNull(quadMeshOver(faintCorner, alphaThreshold = 61, margin = 0)).positions)
		assertContentEquals(floatArrayOf(5f, 3f, 16f, 3f, 16f, 9f, 5f, 9f), assertNotNull(quadMeshOver(faintCorner, alphaThreshold = 60, margin = 0)).positions)
	}

	@Test
	fun nothingOpaqueIsNoQuadEvenWithPins() {
		val empty = rasterOf(8, 8) { _, _ -> false }

		assertNull(quadMeshOver(empty.alphaChannel(), alphaThreshold = 1, margin = 2))
		assertNull(quadMeshOver(empty.alphaChannel(), alphaThreshold = 1, margin = 2, pins = doubleArrayOf(4.0, 4.0)))
		assertNull(quadMeshOver(rasterOf(8, 8, alpha = 40) { _, _ -> true }.alphaChannel(), alphaThreshold = 41, margin = 2))
	}

	@Test
	fun artAtTheRasterEdgeReachesPastIt() {
		val corner = rasterOf(10, 10) { column, row -> column < 3 && row < 2 }
		val quad = assertNotNull(quadMeshOver(corner.alphaChannel(), alphaThreshold = 1, margin = 2))

		assertContentEquals(floatArrayOf(-2f, -2f, 5f, -2f, 5f, 4f, -2f, 4f), quad.positions)
	}

	@Test
	fun pinsComeBackBitExactInPinOrder() {
		val pins = doubleArrayOf(8.3, 5.7, 6.1, 4.9, 9.05, 3.35)
		val quad = assertNotNull(quadMeshOver(block.alphaChannel(), alphaThreshold = 1, margin = 2, pins = pins))

		assertEquals(3, quad.pinVertices.size)
		assertEquals(3, quad.pinVertices.toSet().size, "each pin its own vertex")

		for (pin in 0 until 3) {
			val vertex = quad.pinVertices[pin]

			assertEquals(pins[2 * pin].toFloat().toRawBits(), quad.positions[2 * vertex].toRawBits(), "pin $pin x")
			assertEquals(pins[2 * pin + 1].toFloat().toRawBits(), quad.positions[2 * vertex + 1].toRawBits(), "pin $pin y")
		}

		assertContentEquals(floatArrayOf(3f, 1f, 12f, 1f, 12f, 9f, 3f, 9f), quad.positions.copyOf(8), "the corners stay first and in order")
		assertEquals(7, quad.vertexCount)
		assertTiles(quad, 3.0, 1.0, 12.0, 9.0)
	}

	@Test
	fun aFarPinGrowsTheQuadAroundIt() {
		val quad = assertNotNull(quadMeshOver(block.alphaChannel(), alphaThreshold = 1, margin = 2, pins = doubleArrayOf(30.5, -4.2)))

		assertContentEquals(floatArrayOf(3f, -7f, 33f, -7f, 33f, 9f, 3f, 9f), quad.positions.copyOf(8))
		assertEquals(5, quad.vertexCount)
		assertEquals(30.5f, quad.positions[2 * quad.pinVertices[0]])
		assertEquals((-4.2).toFloat(), quad.positions[2 * quad.pinVertices[0] + 1])
		assertTiles(quad, 3.0, -7.0, 33.0, 9.0)
	}

	@Test
	fun aPinOnAnEdgeAtMarginZeroMakesNoFlatTriangle() {
		val quad = assertNotNull(quadMeshOver(block.alphaChannel(), alphaThreshold = 1, margin = 0, pins = doubleArrayOf(7.0, 3.0)))

		assertEquals(5, quad.vertexCount)
		assertEquals(4, quad.pinVertices[0])
		assertEquals(3, quad.triangleCount)
		assertTiles(quad, 5.0, 3.0, 10.0, 7.0)
	}

	@Test
	fun aPinOnACornerKeepsTheImportsTriangles() {
		val quad = assertNotNull(quadMeshOver(block.alphaChannel(), alphaThreshold = 1, margin = 0, pins = doubleArrayOf(10.0, 7.0)))

		assertContentEquals(floatArrayOf(5f, 3f, 10f, 3f, 10f, 7f, 5f, 7f), quad.positions)
		assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3), quad.triangles)
		assertContentEquals(intArrayOf(2), quad.pinVertices)
	}

	@Test
	fun aPinIsPlacedByItsFloatValue() {
		// 10.0000001 rounds to 10f: the pin is the corner, and the rectangle does not grow to 11.
		val quad = assertNotNull(quadMeshOver(block.alphaChannel(), alphaThreshold = 1, margin = 0, pins = doubleArrayOf(10.0000001, 7.0000001)))

		assertContentEquals(floatArrayOf(5f, 3f, 10f, 3f, 10f, 7f, 5f, 7f), quad.positions)
		assertContentEquals(intArrayOf(2), quad.pinVertices)
	}

	@Test
	fun twoPinsAtOnePointShareAVertex() {
		val quad = assertNotNull(quadMeshOver(block.alphaChannel(), alphaThreshold = 1, margin = 2, pins = doubleArrayOf(7.25, 5.5, 7.25, 5.5)))

		assertEquals(5, quad.vertexCount)
		assertEquals(quad.pinVertices[0], quad.pinVertices[1])
		assertTiles(quad, 3.0, 1.0, 12.0, 9.0)
	}

	@Test
	fun aMaskedChannelQuadsOnlyItsRegion() {
		val twoPieces = rasterOf(30, 10) { column, row -> row in 2..7 && (column in 2..8 || column in 20..26) }
		val rightPiece = channelOf(30, 10) { column, row -> if (column >= 15) twoPieces.alphaChannel().alphaAt(column, row) else 0 }
		val quad = assertNotNull(quadMeshOver(rightPiece, alphaThreshold = 1, margin = 1))

		assertContentEquals(floatArrayOf(19f, 1f, 28f, 1f, 28f, 9f, 19f, 9f), quad.positions)
	}

	@Test
	fun rejectsBadInput() {
		val channel = block.alphaChannel()

		assertFailsWith<IllegalArgumentException> { quadMeshOver(channel, alphaThreshold = 1, margin = -1) }
		assertFailsWith<IllegalArgumentException> { quadMeshOver(channel, alphaThreshold = 1, margin = 2, pins = doubleArrayOf(1.0)) }
		assertFailsWith<IllegalArgumentException> { quadMeshOver(channel, alphaThreshold = 1, margin = 2, pins = doubleArrayOf(1.0, Double.NaN)) }
		assertFailsWith<IllegalArgumentException> { quadMeshOver(channel, alphaThreshold = 1, margin = 2, pins = doubleArrayOf(Double.POSITIVE_INFINITY, 1.0)) }
		assertFailsWith<IllegalArgumentException> { quadMeshOver(channel, alphaThreshold = 0, margin = 2) }
	}

	/**
	 * An alpha channel answered by a function.
	 *
	 * @param Int                width   The width.
	 * @param Int                height  The height.
	 * @param (Int, Int) -> Int  alphaOf The alpha of pixel (column, row).
	 * @return AlphaChannel The channel.
	 */
	private fun channelOf(width: Int, height: Int, alphaOf: (Int, Int) -> Int): AlphaChannel =
		object : AlphaChannel {
			override val width: Int = width
			override val height: Int = height

			override fun alphaAt(column: Int, row: Int): Int = alphaOf(column, row)
		}

	/**
	 * Asserts the mesh tiles the rectangle exactly: every triangle positively oriented on its Float
	 * positions, every directed edge used once, every vertex used, and the triangles' areas summing to
	 * the rectangle's.
	 *
	 * @param PlanarTriangleMesh mesh   The mesh.
	 * @param Double             left   The rectangle's left edge.
	 * @param Double             top    Its top edge.
	 * @param Double             right  Its right edge.
	 * @param Double             bottom Its bottom edge.
	 */
	private fun assertTiles(mesh: PlanarTriangleMesh, left: Double, top: Double, right: Double, bottom: Double) {
		val used = BooleanArray(mesh.vertexCount)
		val directed = HashSet<Pair<Int, Int>>()
		var areaSum = 0.0

		for (triangle in 0 until mesh.triangleCount) {
			val corners = IntArray(3) { corner -> mesh.triangles[3 * triangle + corner] }
			val doubled =
				orient2d(
					mesh.positions[2 * corners[0]].toDouble(),
					mesh.positions[2 * corners[0] + 1].toDouble(),
					mesh.positions[2 * corners[1]].toDouble(),
					mesh.positions[2 * corners[1] + 1].toDouble(),
					mesh.positions[2 * corners[2]].toDouble(),
					mesh.positions[2 * corners[2] + 1].toDouble(),
				)

			assertTrue(doubled > 0.0, "triangle $triangle is not positively oriented")
			areaSum += doubled / 2.0

			for (corner in 0 until 3) {
				used[corners[corner]] = true

				assertTrue(directed.add(corners[corner] to corners[(corner + 1) % 3]), "a directed edge of triangle $triangle repeats")
			}
		}

		assertTrue(used.all { vertexUsed -> vertexUsed }, "every vertex is used")
		assertEquals((right - left) * (bottom - top), areaSum, 1e-6 * hypot(right - left, bottom - top), "the triangles cover the rectangle")
	}
}