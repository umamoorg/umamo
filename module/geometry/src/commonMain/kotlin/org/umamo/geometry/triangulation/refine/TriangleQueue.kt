package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.triangulation.HalfEdgeTriangulation

/*
 * Triangles as packed vertex triples, the one identity refinement keeps across mutations: a slot
 * number changes with every split and flip, but three vertices name the same triangle until it is
 * destroyed.  A triple is rotated to start at its lowest vertex (the orientation stays), so each
 * triangle has exactly one key.
 */

/** The bits one vertex takes in a packed triple. */
private const val VERTEX_BITS: Int = 21

/** Masks one vertex back out of a packed triple. */
private const val VERTEX_MASK: Long = (1L shl VERTEX_BITS) - 1L

/** The vertex count a packed triple can name. */
internal const val MAXIMUM_PACKED_VERTICES: Int = 1 shl VERTEX_BITS

/**
 * Packs a positively oriented triangle into one Long, rotated to start at its lowest vertex.
 *
 * @param Int first  The first corner.
 * @param Int second The second corner.
 * @param Int third  The third corner.
 * @return Long The key.
 */
internal fun packTriangle(first: Int, second: Int, third: Int): Long =
	when {
		first < second && first < third -> packRotated(first, second, third)
		second < third -> packRotated(second, third, first)
		else -> packRotated(third, first, second)
	}

/**
 * Packs three vertices in the order given.
 *
 * @param Int first  The first corner.
 * @param Int second The second corner.
 * @param Int third  The third corner.
 * @return Long The key.
 */
private fun packRotated(first: Int, second: Int, third: Int): Long = (first.toLong() shl (2 * VERTEX_BITS)) or (second.toLong() shl VERTEX_BITS) or third.toLong()

/**
 * A packed triangle's first (lowest) corner.
 *
 * @param Long triangle The key.
 * @return Int The corner.
 */
internal fun triangleFirst(triangle: Long): Int = ((triangle ushr (2 * VERTEX_BITS)) and VERTEX_MASK).toInt()

/**
 * A packed triangle's second corner.
 *
 * @param Long triangle The key.
 * @return Int The corner.
 */
internal fun triangleSecond(triangle: Long): Int = ((triangle ushr VERTEX_BITS) and VERTEX_MASK).toInt()

/**
 * A packed triangle's third corner.
 *
 * @param Long triangle The key.
 * @return Int The corner.
 */
internal fun triangleThird(triangle: Long): Int = (triangle and VERTEX_MASK).toInt()

/**
 * A store triangle's key, by its slot.
 *
 * @param Int triangle The triangle slot.
 * @return Long The key.
 */
internal fun HalfEdgeTriangulation.keyOfTriangle(triangle: Int): Long {
	val base = 3 * triangle

	return packTriangle(origin[base], origin[base + 1], origin[base + 2])
}

/**
 * The slot of the store triangle a key names, while it still exists.
 *
 * @param Long key The key.
 * @return Int The triangle slot, or -1 when no triangle has those corners any more.
 */
internal fun HalfEdgeTriangulation.triangleOfKey(key: Long): Int {
	val halfEdge = findHalfEdge(triangleFirst(key), triangleSecond(key))

	if (halfEdge == HalfEdgeTriangulation.NO_HALF_EDGE || origin[previous(halfEdge)] != triangleThird(key)) {
		return -1
	}

	return halfEdge / 3
}

/**
 * A binary min-heap of packed triangles keyed by a quality value, worst first.  Equal values are
 * ordered by the packed triple, so the pop order is a function of what was pushed alone.
 */
internal class TriangleQueue {
	private var values = DoubleArray(INITIAL_CAPACITY)
	private var triangles = LongArray(INITIAL_CAPACITY)

	/** The number of entries held. */
	var size: Int = 0
		private set

	/**
	 * Whether any entries remain.
	 *
	 * @return Boolean True while the queue holds at least one entry.
	 */
	fun isNotEmpty(): Boolean = size > 0

	/**
	 * Adds one triangle.
	 *
	 * @param Double value    Its quality; smaller pops first.
	 * @param Long   triangle Its packed triple.
	 */
	fun push(value: Double, triangle: Long) {
		if (size == values.size) {
			values = values.copyOf(2 * size)
			triangles = triangles.copyOf(2 * size)
		}

		var position = size
		size++

		while (position > 0) {
			val parent = (position - 1) / 2

			if (!precedes(value, triangle, values[parent], triangles[parent])) {
				break
			}

			values[position] = values[parent]
			triangles[position] = triangles[parent]
			position = parent
		}

		values[position] = value
		triangles[position] = triangle
	}

	/**
	 * Removes and returns the worst triangle; the caller guarantees the queue is not empty.
	 *
	 * @return Long The packed triple with the smallest value.
	 */
	fun pop(): Long {
		val worst = triangles[0]
		size--
		val lastValue = values[size]
		val lastTriangle = triangles[size]
		var position = 0

		while (true) {
			var child = 2 * position + 1

			if (child >= size) {
				break
			}

			if (child + 1 < size && precedes(values[child + 1], triangles[child + 1], values[child], triangles[child])) {
				child++
			}

			if (!precedes(values[child], triangles[child], lastValue, lastTriangle)) {
				break
			}

			values[position] = values[child]
			triangles[position] = triangles[child]
			position = child
		}

		values[position] = lastValue
		triangles[position] = lastTriangle

		return worst
	}

	/**
	 * Whether one entry pops before another.
	 *
	 * @param Double value          The first entry's value.
	 * @param Long   triangle       The first entry's triple.
	 * @param Double otherValue     The second entry's value.
	 * @param Long   otherTriangle  The second entry's triple.
	 * @return Boolean True when the first is strictly ahead.
	 */
	private fun precedes(value: Double, triangle: Long, otherValue: Double, otherTriangle: Long): Boolean = value < otherValue || (value == otherValue && triangle < otherTriangle)

	private companion object {
		const val INITIAL_CAPACITY = 64
	}
}