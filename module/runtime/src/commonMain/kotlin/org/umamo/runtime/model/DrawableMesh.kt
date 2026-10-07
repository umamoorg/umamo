package org.umamo.runtime.model

/**
 * An art mesh's static geometry: two sets of interleaved positions (x then y per vertex), the [uvs], and
 * the triangle [indices] (three per polygon).
 *
 * [positions] is the canvas-space editable mesh, in canvas pixels: what Cubism stores as
 * `CArtMeshSource.positions` and `GEditableMesh2.point`, what the canvas-facing code reads (thumbnails, the
 * birth-quad check, the atlas fit), and nothing the evaluation reads.  [localPositions] is the same mesh in
 * the space its keyforms live in - the parent deformer's space (a warp's lattice, a rotation's frame), or the
 * canvas for a drawable with no deformer - and it is the base every keyform delta is measured from:
 * `p = localPositions + Σ wᵢ·Δᵢ`.  Keeping the base in the keyforms' own space keeps each delta a difference
 * of two values of one magnitude, which float32 holds to the space's own precision; a canvas-space base
 * against a warp's 0..1 keyforms would keep only 1/4096 of a unit.
 *
 * For a deformer-less drawable the two describe the same space and are usually one shared array
 * ([withLocalEqualToCanvas]); a CMO3 can still store an editable mesh apart from the rest shape, so they may
 * differ even there.
 *
 * A plain `class`, not a `data class`: the fields are arrays, whose identity-based `equals` would make
 * a generated structural `equals` quietly wrong (the same trap [Keyform] sidesteps by hand). A mesh is
 * referenced, not value-compared, so reference identity is the honest default here.
 *
 * @property FloatArray positions      The canvas-space editable mesh.
 * @property FloatArray localPositions The keyform-space base the deltas are measured from, [positions]' length.
 * @property FloatArray uvs            The texture coordinates.
 * @property IntArray   indices        The triangle indices.
 */
class DrawableMesh(
	val positions: FloatArray,
	val localPositions: FloatArray,
	val uvs: FloatArray,
	val indices: IntArray,
) {
	init {
		require(localPositions.size == positions.size) { "localPositions holds ${localPositions.size} components against ${positions.size} positions" }
	}

	/** Vertex count - two floats (x, y) per vertex. */
	val vertexCount: Int get() = positions.size / 2

	/** Triangle (polygon) count - three indices per triangle. */
	val triangleCount: Int get() = indices.size / 3

	/**
	 * This mesh with [newUvs] in place of its texture coordinates, both position arrays shared.
	 *
	 * @param FloatArray newUvs The new texture coordinates.
	 * @return DrawableMesh The mesh.
	 */
	fun withUvs(newUvs: FloatArray): DrawableMesh = DrawableMesh(positions, localPositions, newUvs, indices)

	companion object {
		/**
		 * A mesh whose keyform-space base is its canvas mesh, one shared array: a drawable with no deformer, whose
		 * keyforms live on the canvas.
		 *
		 * @param FloatArray positions The canvas positions, also the base.
		 * @param FloatArray uvs       The texture coordinates.
		 * @param IntArray   indices   The triangle indices.
		 * @return DrawableMesh The mesh.
		 */
		fun withLocalEqualToCanvas(positions: FloatArray, uvs: FloatArray, indices: IntArray): DrawableMesh = DrawableMesh(positions, positions, uvs, indices)
	}
}

/** The two triangles a birth quad is always wound with: corners in the order left-top, right-top, right-bottom, left-bottom. */
private val BIRTH_QUAD_INDICES = intArrayOf(0, 1, 2, 0, 2, 3)

/**
 * Whether this mesh is still the quad an artwork import gave the drawable at birth, untouched since:
 * four corners wound as the import winds them, an axis-aligned rectangle, and texture coordinates that
 * are the same rectangle in the tile's art - each corner samples exactly the art pixel under it.  A
 * moved vertex breaks the alignment or the position-to-texel agreement, a subdivision the count, so no
 * edit history is needed to tell an untouched quad from an authored mesh.
 *
 * Read on a reload to decide whether the drawable gets a fresh quad over the new art (an untouched
 * quad has nothing to preserve) or keeps its mesh.
 *
 * @param FloatArray artUvs      The mesh's texture coordinates in the ART frame (0..1 across the tile).
 * @param Int        tileWidth   The tile's width in pixels.
 * @param Int        tileHeight  The tile's height in pixels.
 * @param Float      layerLeft   The layer's canvas x at the last read - the art frame's origin.
 * @param Float      layerTop    The layer's canvas y at the last read.
 * @param Float      tolerance   How far, in pixels, a corner may sit from its texel before the quad
 *   counts as edited; half a pixel absorbs float round trips.
 * @return Boolean True for an untouched birth quad.
 */
fun DrawableMesh.isUntouchedBirthQuad(
	artUvs: FloatArray,
	tileWidth: Int,
	tileHeight: Int,
	layerLeft: Float,
	layerTop: Float,
	tolerance: Float = 0.1f,
): Boolean {
	if (vertexCount != 4 || artUvs.size != 8 || !indices.contentEquals(BIRTH_QUAD_INDICES)) {
		return false
	}
	val left = positions[0]
	val top = positions[1]
	val right = positions[2]
	val bottom = positions[5]
	val axisAligned =
		near(positions[3], top, tolerance) &&
			near(positions[4], right, tolerance) &&
			near(positions[6], left, tolerance) &&
			near(positions[7], bottom, tolerance)
	if (!axisAligned || right <= left || bottom <= top) {
		return false
	}
	for (corner in 0 until 4) {
		val texelX = artUvs[corner * 2] * tileWidth + layerLeft
		val texelY = artUvs[corner * 2 + 1] * tileHeight + layerTop
		if (!near(texelX, positions[corner * 2], tolerance) || !near(texelY, positions[corner * 2 + 1], tolerance)) {
			return false
		}
	}
	return true
}

/**
 * Whether two coordinates agree within [tolerance].
 *
 * @param Float first     One coordinate.
 * @param Float second    The other.
 * @param Float tolerance The largest difference that still counts as agreement.
 * @return Boolean True when they agree.
 */
private fun near(first: Float, second: Float, tolerance: Float): Boolean = kotlin.math.abs(first - second) <= tolerance