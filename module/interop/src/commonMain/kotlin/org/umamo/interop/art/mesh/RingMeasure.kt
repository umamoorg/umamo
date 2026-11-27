package org.umamo.interop.art.mesh

import kotlin.math.sqrt

/**
 * A traced ring's length and twice its signed area: what the rules on which rings have room read.
 *
 * @property Double perimeter   The ring's length, back around to its first vertex.
 * @property Double doubledArea Twice its shoelace area in raster coordinates: positive around art, negative around a hole (the field's contour convention).
 */
internal class RingMeasure(val perimeter: Double, val doubledArea: Double) {
	/** Whether the ring goes around a hole in the art. */
	val isHole: Boolean
		get() = doubledArea < 0.0

	companion object {
		/**
		 * Measures a closed ring.
		 *
		 * @param DoubleArray ring The ring, x0, y0, ..., closed implicitly.
		 * @return RingMeasure Its length and doubled signed area.
		 */
		fun of(ring: DoubleArray): RingMeasure {
			val vertexCount = ring.size / 2
			var perimeter = 0.0
			var doubledArea = 0.0

			for (vertex in 0 until vertexCount) {
				val next = (vertex + 1) % vertexCount
				val deltaX = ring[2 * next] - ring[2 * vertex]
				val deltaY = ring[2 * next + 1] - ring[2 * vertex + 1]
				perimeter += sqrt(deltaX * deltaX + deltaY * deltaY)
				doubledArea += ring[2 * vertex] * ring[2 * next + 1] - ring[2 * next] * ring[2 * vertex + 1]
			}

			return RingMeasure(perimeter, doubledArea)
		}
	}
}