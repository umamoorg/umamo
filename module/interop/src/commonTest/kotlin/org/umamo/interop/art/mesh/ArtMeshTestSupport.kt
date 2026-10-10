package org.umamo.interop.art.mesh

import org.umamo.format.art.LayerRaster
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.predicate.orient2d
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A raster with a given alpha where a predicate says art and 0 elsewhere.
 *
 * @param Int                   width  The raster width.
 * @param Int                   height The raster height.
 * @param Int                   alpha  The art's alpha byte.
 * @param (Int, Int) -> Boolean isArt  Whether pixel (column, row) is art.
 * @return LayerRaster The raster.
 */
internal fun rasterOf(width: Int, height: Int, alpha: Int = 255, isArt: (Int, Int) -> Boolean): LayerRaster {
	val rgba = ByteArray(width * height * 4)

	for (row in 0 until height) {
		for (column in 0 until width) {
			if (isArt(column, row)) {
				val offset = (row * width + column) * 4
				rgba[offset] = 0x80.toByte()
				rgba[offset + 1] = 0x40.toByte()
				rgba[offset + 2] = 0x20.toByte()
				rgba[offset + 3] = alpha.toByte()
			}
		}
	}

	return LayerRaster(width, height, rgba)
}

/**
 * Whether a pixel's center lies within a disc.
 *
 * @param Int    column  The pixel's column.
 * @param Int    row     The pixel's row.
 * @param Double centerX The disc's center x.
 * @param Double centerY The disc's center y.
 * @param Double radius  The disc's radius.
 * @return Boolean True inside.
 */
internal fun inDisc(column: Int, row: Int, centerX: Double, centerY: Double, radius: Double): Boolean {
	val deltaX = column + 0.5 - centerX
	val deltaY = row + 0.5 - centerY

	return deltaX * deltaX + deltaY * deltaY <= radius * radius
}

/**
 * Checks a generated mesh against everything the mesher promises, with oracles that share no code
 * with it: every triangle positively oriented on its Float coordinates, every directed edge used
 * once, every vertex used and distinct, pins at their exact Float positions, every art pixel's
 * center inside some triangle (scan conversion by orient2d), and every boundary edge at least the
 * minimum margin from every art pixel square (brute force).
 *
 * @param LayerRaster     raster   The meshed raster.
 * @param ArtMeshSettings settings The settings used.
 * @param ArtMeshResult   result   The result.
 * @param DoubleArray     pins     The pins given.
 * @return PlanarTriangleMesh The mesh, for further checks.
 */
internal fun assertValidArtMesh(raster: LayerRaster, settings: ArtMeshSettings, result: ArtMeshResult, pins: DoubleArray = DoubleArray(0)): PlanarTriangleMesh {
	val mesh = assertNotNull(result.mesh, "no mesh: ${result.notices}")
	assertTrue(mesh.vertexCount <= settings.vertexBudget, "${mesh.vertexCount} vertices exceed the budget ${settings.vertexBudget}")
	val directed = HashSet<Long>()
	val used = BooleanArray(mesh.vertexCount)

	for (triangle in 0 until mesh.triangleCount) {
		val first = mesh.triangles[3 * triangle]
		val second = mesh.triangles[3 * triangle + 1]
		val third = mesh.triangles[3 * triangle + 2]
		assertTrue(orientOf(mesh, first, second, third) > 0.0, "triangle $triangle is not positively oriented")

		for ((start, end) in listOf(first to second, second to third, third to first)) {
			assertTrue(directed.add(edgeKey(start, end)), "directed edge $start -> $end appears twice")
			used[start] = true
		}
	}

	assertTrue(used.all { it }, "every vertex is used by a triangle")
	val positions = HashSet<Long>()

	for (vertex in 0 until mesh.vertexCount) {
		val key = (mesh.positions[2 * vertex].toRawBits().toLong() shl 32) or (mesh.positions[2 * vertex + 1].toRawBits().toLong() and 0xFFFFFFFFL)
		assertTrue(positions.add(key), "vertex $vertex duplicates another's position")
	}

	assertEquals(pins.size / 2, mesh.pinVertices.size)

	for (pin in 0 until pins.size / 2) {
		val vertex = mesh.pinVertices[pin]
		assertEquals(pins[2 * pin].toFloat(), mesh.positions[2 * vertex], "pin $pin x")
		assertEquals(pins[2 * pin + 1].toFloat(), mesh.positions[2 * vertex + 1], "pin $pin y")
	}

	assertArtCovered(raster, mesh, settings.alphaThreshold)
	assertBoundaryClearsArt(raster, mesh, settings.alphaThreshold, settings.minimumMargin)

	return mesh
}

/**
 * The art pixels of a raster, as column, row pairs.
 *
 * @param LayerRaster raster    The raster.
 * @param Int         threshold The minimum alpha counted as art.
 * @return List<IntArray> The art pixels.
 */
internal fun artPixels(raster: LayerRaster, threshold: Int): List<IntArray> {
	val pixels = ArrayList<IntArray>()

	for (row in 0 until raster.height) {
		for (column in 0 until raster.width) {
			if ((raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF) >= threshold) {
				pixels.add(intArrayOf(column, row))
			}
		}
	}

	return pixels
}

/**
 * Whether a point lies in some triangle of a mesh (edges included).
 *
 * @param PlanarTriangleMesh mesh The mesh.
 * @param Double             x    The point's x.
 * @param Double             y    The point's y.
 * @return Boolean True when covered.
 */
internal fun meshCovers(mesh: PlanarTriangleMesh, x: Double, y: Double): Boolean {
	for (triangle in 0 until mesh.triangleCount) {
		if (triangleContains(mesh, triangle, x, y)) {
			return true
		}
	}

	return false
}

/**
 * Asserts every art pixel's center lies in some triangle, by scan-converting each triangle's
 * bounding box - linear in the mesh's area, so it holds up on full-size corpus layers.
 *
 * @param LayerRaster        raster    The raster.
 * @param PlanarTriangleMesh mesh      The mesh.
 * @param Int                threshold The minimum alpha counted as art.
 */
internal fun assertArtCovered(raster: LayerRaster, mesh: PlanarTriangleMesh, threshold: Int) {
	val covered = BooleanArray(raster.width * raster.height)

	for (triangle in 0 until mesh.triangleCount) {
		var minimumX = Double.POSITIVE_INFINITY
		var minimumY = Double.POSITIVE_INFINITY
		var maximumX = Double.NEGATIVE_INFINITY
		var maximumY = Double.NEGATIVE_INFINITY

		for (corner in 0 until 3) {
			val vertex = mesh.triangles[3 * triangle + corner]
			minimumX = minOf(minimumX, mesh.positions[2 * vertex].toDouble())
			minimumY = minOf(minimumY, mesh.positions[2 * vertex + 1].toDouble())
			maximumX = maxOf(maximumX, mesh.positions[2 * vertex].toDouble())
			maximumY = maxOf(maximumY, mesh.positions[2 * vertex + 1].toDouble())
		}

		for (row in maxOf(0, floor(minimumY - 0.5).toInt())..minOf(raster.height - 1, ceil(maximumY - 0.5).toInt())) {
			for (column in maxOf(0, floor(minimumX - 0.5).toInt())..minOf(raster.width - 1, ceil(maximumX - 0.5).toInt())) {
				if (!covered[row * raster.width + column] && triangleContains(mesh, triangle, column + 0.5, row + 0.5)) {
					covered[row * raster.width + column] = true
				}
			}
		}
	}

	for (row in 0 until raster.height) {
		for (column in 0 until raster.width) {
			if ((raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF) >= threshold) {
				assertTrue(covered[row * raster.width + column], "art pixel ($column, $row) is not covered")
			}
		}
	}
}

/**
 * Asserts every boundary edge of a mesh (a triangle on one side only) keeps at least a margin from
 * every art pixel square, scanning only the pixels in each edge's padded bounding box.
 *
 * @param LayerRaster        raster        The raster.
 * @param PlanarTriangleMesh mesh          The mesh.
 * @param Int                threshold     The minimum alpha counted as art.
 * @param Double             minimumMargin The margin required.
 */
internal fun assertBoundaryClearsArt(raster: LayerRaster, mesh: PlanarTriangleMesh, threshold: Int, minimumMargin: Double) {
	val directed = HashSet<Long>()

	for (entry in mesh.triangles.indices) {
		directed.add(edgeKey(mesh.triangles[entry], mesh.triangles[if (entry % 3 == 2) entry - 2 else entry + 1]))
	}

	for (key in directed) {
		val start = (key ushr 32).toInt()
		val end = key.toInt()

		if (edgeKey(end, start) in directed) {
			continue
		}

		val reach = minimumMargin + 1.0
		val firstColumn = maxOf(0, floor(minOf(mesh.positions[2 * start], mesh.positions[2 * end]) - reach).toInt())
		val lastColumn = minOf(raster.width - 1, ceil(maxOf(mesh.positions[2 * start], mesh.positions[2 * end]) + reach).toInt())
		val firstRow = maxOf(0, floor(minOf(mesh.positions[2 * start + 1], mesh.positions[2 * end + 1]) - reach).toInt())
		val lastRow = minOf(raster.height - 1, ceil(maxOf(mesh.positions[2 * start + 1], mesh.positions[2 * end + 1]) + reach).toInt())

		for (row in firstRow..lastRow) {
			for (column in firstColumn..lastColumn) {
				if ((raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF) < threshold) {
					continue
				}

				val distance = distanceSegmentToSquare(mesh, start, end, column.toDouble(), row.toDouble())

				if (distance < minimumMargin - 1e-9) {
					fail("boundary edge $start -> $end is $distance from art pixel ($column, $row)")
				}
			}
		}
	}
}

/**
 * Whether a triangle contains a point, edges included.
 *
 * @param PlanarTriangleMesh mesh     The mesh.
 * @param Int                triangle The triangle.
 * @param Double             x        The point's x.
 * @param Double             y        The point's y.
 * @return Boolean True when contained.
 */
private fun triangleContains(mesh: PlanarTriangleMesh, triangle: Int, x: Double, y: Double): Boolean {
	val first = mesh.triangles[3 * triangle]
	val second = mesh.triangles[3 * triangle + 1]
	val third = mesh.triangles[3 * triangle + 2]

	return orientToPoint(mesh, first, second, x, y) >= 0.0 && orientToPoint(mesh, second, third, x, y) >= 0.0 && orientToPoint(mesh, third, first, x, y) >= 0.0
}

/**
 * orient2d of two mesh vertices and a point.
 *
 * @param PlanarTriangleMesh mesh  The mesh.
 * @param Int                start The first vertex.
 * @param Int                end   The second vertex.
 * @param Double             x     The point's x.
 * @param Double             y     The point's y.
 * @return Double The predicate's value.
 */
private fun orientToPoint(mesh: PlanarTriangleMesh, start: Int, end: Int, x: Double, y: Double): Double =
	orient2d(
		mesh.positions[2 * start].toDouble(),
		mesh.positions[2 * start + 1].toDouble(),
		mesh.positions[2 * end].toDouble(),
		mesh.positions[2 * end + 1].toDouble(),
		x,
		y,
	)

/**
 * orient2d of three mesh vertices.
 *
 * @param PlanarTriangleMesh mesh   The mesh.
 * @param Int                first  The first vertex.
 * @param Int                second The second vertex.
 * @param Int                third  The third vertex.
 * @return Double The predicate's value.
 */
private fun orientOf(mesh: PlanarTriangleMesh, first: Int, second: Int, third: Int): Double = orientToPoint(mesh, first, second, mesh.positions[2 * third].toDouble(), mesh.positions[2 * third + 1].toDouble())

/**
 * The distance from a mesh edge to a unit pixel square, built differently from the mesher's own
 * check: zero when an end lies in the square or the edge meets one of its sides, else the least
 * distance between the edge and the square's four sides.
 *
 * @param PlanarTriangleMesh mesh  The mesh.
 * @param Int                start The edge's start vertex.
 * @param Int                end   The edge's end vertex.
 * @param Double             left  The square's left edge.
 * @param Double             top   The square's top edge.
 * @return Double The distance.
 */
private fun distanceSegmentToSquare(mesh: PlanarTriangleMesh, start: Int, end: Int, left: Double, top: Double): Double {
	val startX = mesh.positions[2 * start].toDouble()
	val startY = mesh.positions[2 * start + 1].toDouble()
	val endX = mesh.positions[2 * end].toDouble()
	val endY = mesh.positions[2 * end + 1].toDouble()
	val inside = { x: Double, y: Double -> x >= left && x <= left + 1.0 && y >= top && y <= top + 1.0 }

	if (inside(startX, startY) || inside(endX, endY)) {
		return 0.0
	}

	val corners = doubleArrayOf(left, top, left + 1.0, top, left + 1.0, top + 1.0, left, top + 1.0)
	var nearest = Double.POSITIVE_INFINITY

	for (side in 0 until 4) {
		val next = (side + 1) % 4
		val sideStartX = corners[2 * side]
		val sideStartY = corners[2 * side + 1]
		val sideEndX = corners[2 * next]
		val sideEndY = corners[2 * next + 1]

		if (segmentsMeet(startX, startY, endX, endY, sideStartX, sideStartY, sideEndX, sideEndY)) {
			return 0.0
		}

		nearest =
			minOf(
				nearest,
				pointToSegment(startX, startY, sideStartX, sideStartY, sideEndX, sideEndY),
				pointToSegment(endX, endY, sideStartX, sideStartY, sideEndX, sideEndY),
				pointToSegment(sideStartX, sideStartY, startX, startY, endX, endY),
				pointToSegment(sideEndX, sideEndY, startX, startY, endX, endY),
			)
	}

	return nearest
}

/**
 * Whether two segments share a point, by cross-product signs.
 *
 * @param Double firstStartX  The first segment's start x.
 * @param Double firstStartY  The first segment's start y.
 * @param Double firstEndX    The first segment's end x.
 * @param Double firstEndY    The first segment's end y.
 * @param Double secondStartX The second segment's start x.
 * @param Double secondStartY The second segment's start y.
 * @param Double secondEndX   The second segment's end x.
 * @param Double secondEndY   The second segment's end y.
 * @return Boolean True when they cross or touch.
 */
private fun segmentsMeet(
	firstStartX: Double,
	firstStartY: Double,
	firstEndX: Double,
	firstEndY: Double,
	secondStartX: Double,
	secondStartY: Double,
	secondEndX: Double,
	secondEndY: Double,
): Boolean {
	val side = { ax: Double, ay: Double, bx: Double, by: Double, x: Double, y: Double -> (bx - ax) * (y - ay) - (by - ay) * (x - ax) }
	val secondStartSide = side(firstStartX, firstStartY, firstEndX, firstEndY, secondStartX, secondStartY)
	val secondEndSide = side(firstStartX, firstStartY, firstEndX, firstEndY, secondEndX, secondEndY)
	val firstStartSide = side(secondStartX, secondStartY, secondEndX, secondEndY, firstStartX, firstStartY)
	val firstEndSide = side(secondStartX, secondStartY, secondEndX, secondEndY, firstEndX, firstEndY)

	return secondStartSide * secondEndSide <= 0.0 &&
		firstStartSide * firstEndSide <= 0.0 &&
		minOf(firstStartX, firstEndX) <= maxOf(secondStartX, secondEndX) &&
		minOf(secondStartX, secondEndX) <= maxOf(firstStartX, firstEndX) &&
		minOf(firstStartY, firstEndY) <= maxOf(secondStartY, secondEndY) &&
		minOf(secondStartY, secondEndY) <= maxOf(firstStartY, firstEndY)
}

/**
 * The distance from a point to a segment.
 *
 * @param Double pointX The point's x.
 * @param Double pointY The point's y.
 * @param Double startX The segment's start x.
 * @param Double startY The segment's start y.
 * @param Double endX   The segment's end x.
 * @param Double endY   The segment's end y.
 * @return Double The distance.
 */
private fun pointToSegment(pointX: Double, pointY: Double, startX: Double, startY: Double, endX: Double, endY: Double): Double {
	val alongX = endX - startX
	val alongY = endY - startY
	val lengthSquared = alongX * alongX + alongY * alongY
	val fraction = if (lengthSquared > 0.0) (((pointX - startX) * alongX + (pointY - startY) * alongY) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
	val offsetX = startX + fraction * alongX - pointX
	val offsetY = startY + fraction * alongY - pointY

	return sqrt(offsetX * offsetX + offsetY * offsetY)
}

/**
 * Packs a directed edge into one Long.
 *
 * @param Int start The start vertex.
 * @param Int end   The end vertex.
 * @return Long The key.
 */
internal fun edgeKey(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)