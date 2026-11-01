package org.umamo.edit

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeLayout
import org.umamo.runtime.model.latticeLayout
import org.umamo.runtime.model.meshOf

/**
 * The element structure Edit mode selects and transforms over, whatever owns it: how many vertices there are,
 * the faces as rings of vertex indices, and - derived from those rings - the unique edges and each vertex's
 * neighbors.  A drawable's mesh is a triangle topology; a warp's lattice is a quad topology whose faces are its
 * cells, so its edges are the grid lines alone and no cell is ever split by a diagonal.
 *
 * Every face has [cornersPerFace] corners, listed around it.  An edge joins consecutive corners of a face (the
 * last back to the first), so a triangle mesh yields exactly the edges and adjacency its triangles always did, in
 * the same order, and a cell yields its four sides.
 *
 * Immutable; the edges and the adjacency are built on first use, so a query that needs neither pays for neither.
 *
 * @property Int vertexCount How many vertices there are, isolated ones included.
 * @property IntArray faceCorners [cornersPerFace] vertex indices per face, around the face.
 * @property Int cornersPerFace How many corners every face has: 3 for a triangle mesh, 4 for a lattice.
 */
class ElementTopology(
	val vertexCount: Int,
	val faceCorners: IntArray,
	val cornersPerFace: Int,
) {
	init {
		require(cornersPerFace >= 3) { "a face needs at least three corners, got $cornersPerFace" }
		require(faceCorners.size % cornersPerFace == 0) { "${faceCorners.size} corners do not make whole faces of $cornersPerFace" }
	}

	/** How many faces there are. */
	val faceCount: Int get() = faceCorners.size / cornersPerFace

	/** The unique edges as endpoint pairs, low endpoint first, in first-encounter order around the faces. */
	val edgeEndpoints: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) { ringEdgeEndpoints(faceCorners, cornersPerFace) }

	/** The unique edges, in the order of [edgeEndpoints], so an edge's ordinal means the same in both. */
	val edges: List<MeshElement.Edge> by lazy(LazyThreadSafetyMode.PUBLICATION) { edgesOf(edgeEndpoints) }

	/**
	 * Writes face [faceIndex]'s center over [positions] - the mean of its corners - into [out] at [outIndex]: where a
	 * face's dot sits and what a face pick measures to.
	 *
	 * @param FloatArray positions The interleaved (x, y) positions the corners index into.
	 * @param Int faceIndex The face.
	 * @param FloatArray out Where the center goes.
	 * @param Int outIndex The x slot in [out]; y follows it.
	 */
	fun faceCenterInto(positions: FloatArray, faceIndex: Int, out: FloatArray, outIndex: Int) {
		var sumX = 0f
		var sumY = 0f

		for (cornerSlot in 0 until cornersPerFace) {
			val corner = faceCorners[faceIndex * cornersPerFace + cornerSlot]
			sumX += positions[corner * 2]
			sumY += positions[corner * 2 + 1]
		}

		out[outIndex] = sumX / cornersPerFace
		out[outIndex + 1] = sumY / cornersPerFace
	}

	/**
	 * Each vertex's neighbors: the vertices an edge joins it to, deduplicated.  A vertex on no face has none.  What
	 * Select Linked floods, Individual Origins splits into islands, connected-only proportional walks, and Vertex
	 * Slide picks its edges from.
	 */
	val adjacency: List<IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val neighborSets = List(vertexCount) { HashSet<Int>() }

		forEachRingEdge(faceCorners, cornersPerFace) { vertexA, vertexB ->
			if (vertexA in 0 until vertexCount && vertexB in 0 until vertexCount && vertexA != vertexB) {
				neighborSets[vertexA].add(vertexB)
				neighborSets[vertexB].add(vertexA)
			}
		}
		neighborSets.map { neighbors -> neighbors.toIntArray() }
	}

	/**
	 * Whether [faceIndex] names one of the faces.
	 *
	 * @param Int faceIndex The face ordinal.
	 * @return Boolean True when it is in range.
	 */
	fun hasFace(faceIndex: Int): Boolean = faceIndex in 0 until faceCount

	/**
	 * The vertex indices around one face.
	 *
	 * @param Int faceIndex The face ordinal.
	 * @return Set<Int> Its corners.
	 */
	fun verticesOfFace(faceIndex: Int): Set<Int> {
		val corners = LinkedHashSet<Int>(cornersPerFace)

		for (cornerIndex in 0 until cornersPerFace) {
			corners.add(faceCorners[faceIndex * cornersPerFace + cornerIndex])
		}

		return corners
	}

	/**
	 * The canonical edges around one face: each pair of consecutive corners, the last back to the first.
	 *
	 * @param Int faceIndex The face ordinal.
	 * @return List<MeshElement.Edge> Its sides.
	 */
	fun edgesOfFace(faceIndex: Int): List<MeshElement.Edge> {
		val faceStart = faceIndex * cornersPerFace

		return List(cornersPerFace) { cornerIndex ->
			MeshElement.Edge.of(faceCorners[faceStart + cornerIndex], faceCorners[faceStart + (cornerIndex + 1) % cornersPerFace])
		}
	}

	companion object {
		/**
		 * A triangle mesh's topology: three corners per face.
		 *
		 * @param Int vertexCount The vertex count, isolated vertices included.
		 * @param IntArray triangleIndices Three vertex indices per triangle.
		 * @return ElementTopology The topology, holding [triangleIndices] itself.
		 */
		fun ofTriangles(vertexCount: Int, triangleIndices: IntArray): ElementTopology = ElementTopology(vertexCount, triangleIndices, 3)

		/**
		 * Vertices with no faces between them: a topology that connects nothing, for a gesture that moves whole
		 * meshes and so never needs connectivity (Object mode's transforms).
		 *
		 * @param Int vertexCount The vertex count.
		 * @return ElementTopology The face-less topology.
		 */
		fun unconnected(vertexCount: Int): ElementTopology = ElementTopology(vertexCount, IntArray(0), 3)

		/**
		 * A drawable mesh's topology.
		 *
		 * @param DrawableMesh mesh The mesh.
		 * @return ElementTopology Its triangle topology.
		 */
		fun ofMesh(mesh: DrawableMesh): ElementTopology = ofTriangles(mesh.vertexCount, mesh.indices)

		/**
		 * A warp lattice's topology: its control points, its cells as faces, and so its grid lines as edges.
		 *
		 * @param WarpLatticeLayout layout The lattice's layout.
		 * @return ElementTopology The quad topology.
		 */
		fun ofLattice(layout: WarpLatticeLayout): ElementTopology = ElementTopology(layout.pointCount, layout.cellCorners, 4)

		/**
		 * Flat endpoint pairs as boxed edges, in their order, so an edge's ordinal means the same in both.
		 *
		 * @param IntArray edgeEndpoints Two endpoints per edge, low endpoint first.
		 * @return List<MeshElement.Edge> The edges.
		 */
		internal fun edgesOf(edgeEndpoints: IntArray): List<MeshElement.Edge> =
			List(edgeEndpoints.size / 2) { edgeOrdinal -> MeshElement.Edge(edgeEndpoints[edgeOrdinal * 2], edgeEndpoints[edgeOrdinal * 2 + 1]) }

		/**
		 * The unique edges of a set of face rings as endpoint pairs, low endpoint first, in first-encounter order.
		 *
		 * @param IntArray faceCorners The faces' corners, around each face.
		 * @param Int cornersPerFace The corners per face.
		 * @return IntArray Two endpoints per unique edge.
		 */
		internal fun ringEdgeEndpoints(faceCorners: IntArray, cornersPerFace: Int): IntArray {
			val faceCount = faceCorners.size / cornersPerFace
			val seen = HashSet<Long>(faceCount * cornersPerFace)
			val endpoints = IntArray(faceCount * cornersPerFace * 2)
			var written = 0

			forEachRingEdge(faceCorners, cornersPerFace) { from, to ->
				val low = minOf(from, to)
				val high = maxOf(from, to)

				if (seen.add((low.toLong() shl 32) or high.toLong())) {
					endpoints[written] = low
					endpoints[written + 1] = high
					written += 2
				}
			}

			return endpoints.copyOf(written)
		}

		/**
		 * Runs [visit] over every face's sides in face order: each pair of consecutive corners, the last back to the
		 * first.  An edge two faces share is visited once per face.
		 *
		 * @param IntArray faceCorners The faces' corners, around each face.
		 * @param Int cornersPerFace The corners per face.
		 * @param Function visit Called with each side's two corners, in ring order.
		 */
		private inline fun forEachRingEdge(faceCorners: IntArray, cornersPerFace: Int, visit: (Int, Int) -> Unit) {
			val faceCount = faceCorners.size / cornersPerFace

			for (faceIndex in 0 until faceCount) {
				val faceStart = faceIndex * cornersPerFace

				for (cornerIndex in 0 until cornersPerFace) {
					visit(faceCorners[faceStart + cornerIndex], faceCorners[faceStart + (cornerIndex + 1) % cornersPerFace])
				}
			}
		}
	}
}

/**
 * An Edit owner's topology: a drawable's mesh triangles, or a warp's lattice cells.  Null when the model holds no such
 * owner, a drawable carries no mesh, or a deformer is not a warp with a lattice (one keyed at all) - nothing Edit
 * mode could select on.
 *
 * @param ElementOwner owner The drawable or warp.
 * @return ElementTopology? Its topology, or null.
 */
fun PuppetModel.editTopologyOf(owner: ElementOwner): ElementTopology? =
	when (owner) {
		is ElementOwner.Drawable -> {
			meshOf(owner.id)?.let { mesh -> ElementTopology.ofMesh(mesh) }
		}

		is ElementOwner.Deformer -> {
			val warp = deformers.firstOrNull { deformer -> deformer.id == owner.id } as? Deformer.Warp
			warp?.takeIf { candidate -> candidate.geometryGrid != null }?.let { lattice -> ElementTopology.ofLattice(lattice.latticeLayout) }
		}
	}