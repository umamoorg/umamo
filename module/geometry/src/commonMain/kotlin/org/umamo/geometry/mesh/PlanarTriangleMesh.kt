package org.umamo.geometry.mesh

/**
 * A triangle mesh in the plane with optional pinned vertices - the neutral shape the auto-mesher
 * produces and an editing transfer consumes, free of any puppet or format type.
 *
 * Positions are Float because that is what a drawable mesh stores; a producer that triangulated its
 * Float-rounded coordinates hands over triangles whose orientation holds exactly as stored.
 *
 * A plain class: it holds arrays.
 *
 * @property FloatArray positions   The vertices, x0, y0, x1, y1, ...
 * @property IntArray   triangles   Three vertex indices per triangle.
 * @property IntArray   pinVertices For each pin the producer was given, the vertex it became.
 */
public class PlanarTriangleMesh(
	public val positions: FloatArray,
	public val triangles: IntArray,
	public val pinVertices: IntArray,
) {
	init {
		require(positions.size % 2 == 0) { "positions must hold x, y pairs, but has ${positions.size} components" }
		require(triangles.size % 3 == 0) { "triangles must hold index triples, but has ${triangles.size} entries" }
		val vertexCount = positions.size / 2

		for (entry in triangles.indices) {
			require(triangles[entry] in 0 until vertexCount) { "triangle entry $entry is ${triangles[entry]}, outside 0 until $vertexCount" }
		}

		for (pin in pinVertices.indices) {
			require(pinVertices[pin] in 0 until vertexCount) { "pin $pin maps to ${pinVertices[pin]}, outside 0 until $vertexCount" }
		}
	}

	/** The number of vertices. */
	public val vertexCount: Int
		get() = positions.size / 2

	/** The number of triangles. */
	public val triangleCount: Int
		get() = triangles.size / 3
}