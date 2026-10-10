package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaField
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.triangulation.ConstraintKind
import org.umamo.geometry.triangulation.RejectedConstraint
import org.umamo.geometry.triangulation.Triangulation
import org.umamo.geometry.triangulation.triangulate

/*
 * Assembles the mesher's points into one triangulation: the outline rings as BOUNDARY rings (they
 * decide inside from outside), the inner rings as neutral SEGMENTS, the lattice and the pins as free
 * points.  Simplified outline rings can cross one another where two islands or a narrow neck come
 * close; the triangulator then rejects a boundary edge and leaves a ring open, so both chords
 * involved are replaced by the traced points they skipped and the triangulation runs again - traced
 * rings are disjoint, so this ends.  The finished mesh keeps only the vertices its triangles use.
 *
 * The final checks are structural, not per pixel: no boundary edge rejected, inside and outside
 * consistent, every pin a vertex, and every boundary edge of the mesh clear of the art by the minimum
 * margin.  Together with the outline's chord checks they prove every opaque pixel is covered.
 */

/** Repair rounds that densify single chords before every outline ring is densified whole. */
private const val CHORD_REPAIR_ROUNDS: Int = 6

/**
 * What [assembleMesh] produced.
 *
 * @property PlanarTriangleMesh? mesh                  The mesh, or null when a structural check failed.
 * @property String?             failure               What failed, when the mesh is null.
 * @property Int                 densifiedChordCount   Outline chords replaced by their traced points to untangle crossing rings.
 * @property Int                 droppedInnerEdgeCount Inner-ring edges left out because they crossed the outline.
 * @property Int                 sharedPinCount        Pins that landed on the same vertex as another pin.
 * @property Int                 outlineVertexCount    The outline's points after any densifying.
 */
internal class AssembledMesh(
	val mesh: PlanarTriangleMesh?,
	val failure: String?,
	val densifiedChordCount: Int,
	val droppedInnerEdgeCount: Int,
	val sharedPinCount: Int,
	val outlineVertexCount: Int,
)

/**
 * Triangulates the mesher's points, untangling crossing outline rings, and checks the result.
 *
 * @param List<SampledRing>  outline       The outline rings (densified in place when they cross).
 * @param List<DoubleArray>  innerRings    The inner rings' points.
 * @param DoubleArray        lattice       The lattice points.
 * @param DoubleArray        pins          The pins, Float-rounded.
 * @param AlphaField         field         The field, for the final clearance check.
 * @param Double             minimumMargin The clearance every boundary edge must keep from the art.
 * @return AssembledMesh The mesh or the failure, with what was adjusted.
 */
internal fun assembleMesh(outline: List<SampledRing>, innerRings: List<DoubleArray>, lattice: DoubleArray, pins: DoubleArray, field: AlphaField, minimumMargin: Double): AssembledMesh {
	var densifiedChordCount = 0
	var round = 0

	while (true) {
		val layout = MeshLayout(outline, innerRings, lattice, pins)
		val triangulation = triangulate(layout.points, layout.boundaries, layout.segments)
		val boundaryRejections = triangulation.rejectedConstraints.filter { rejection -> rejection.kind == ConstraintKind.Boundary }

		if (boundaryRejections.isEmpty() && triangulation.classificationConsistent) {
			return finish(layout, triangulation, field, minimumMargin, densifiedChordCount)
		}

		round++

		if (round <= CHORD_REPAIR_ROUNDS) {
			densifiedChordCount += densifyCrossings(outline, layout, triangulation, boundaryRejections)
		} else if (round == CHORD_REPAIR_ROUNDS + 1) {
			// Traced rings never cross, so the outline at full density always triangulates.
			for (ring in outline) {
				densifiedChordCount += ring.sampleCount
				ring.densifyAll()
			}
		} else {
			return AssembledMesh(null, "outline rings still cross at full density", densifiedChordCount, 0, 0, layout.outlinePointCount)
		}
	}
}

/**
 * The flat point list handed to the triangulator, and which outline ring and position each outline
 * point came from.
 *
 * @param List<SampledRing> outline    The outline rings.
 * @param List<DoubleArray> innerRings The inner rings' points.
 * @param DoubleArray       lattice    The lattice points.
 * @param DoubleArray       pins       The pins.
 */
private class MeshLayout(outline: List<SampledRing>, innerRings: List<DoubleArray>, lattice: DoubleArray, pins: DoubleArray) {
	/** Every point: outline rings, then inner rings, then the lattice, then the pins. */
	val points: DoubleArray

	/** Each outline ring as indices into [points]. */
	val boundaries: List<IntArray>

	/** Each inner ring as a closed polyline of indices into [points]. */
	val segments: List<IntArray>

	/** The outline ring each point belongs to, or -1. */
	val ringOf: IntArray

	/** The point's position within its outline ring. */
	val positionOf: IntArray

	/** The index of the first pin in [points]. */
	val firstPin: Int

	/** The number of outline points. */
	val outlinePointCount: Int

	init {
		val outlinePoints = outline.map { ring -> ring.points() }
		outlinePointCount = outlinePoints.sumOf { ring -> ring.size / 2 }
		val innerPointCount = innerRings.sumOf { ring -> ring.size / 2 }
		val totalCount = outlinePointCount + innerPointCount + lattice.size / 2 + pins.size / 2
		points = DoubleArray(2 * totalCount)
		ringOf = IntArray(totalCount) { -1 }
		positionOf = IntArray(totalCount)
		val boundaryList = ArrayList<IntArray>()
		val segmentList = ArrayList<IntArray>()
		var next = 0

		for ((ringIndex, ringPoints) in outlinePoints.withIndex()) {
			val count = ringPoints.size / 2
			ringPoints.copyInto(points, 2 * next)

			for (position in 0 until count) {
				ringOf[next + position] = ringIndex
				positionOf[next + position] = position
			}

			val start = next
			boundaryList.add(IntArray(count) { start + it })
			next += count
		}

		for (ringPoints in innerRings) {
			val count = ringPoints.size / 2
			ringPoints.copyInto(points, 2 * next)
			val start = next
			// Closed: the polyline returns to its first point.
			segmentList.add(IntArray(count + 1) { position -> start + position % count })
			next += count
		}

		lattice.copyInto(points, 2 * next)
		next += lattice.size / 2
		firstPin = next
		pins.copyInto(points, 2 * next)
		boundaries = boundaryList
		segments = segmentList
	}
}

/**
 * Densifies the outline chords behind each rejected boundary edge: the rejected chord itself, and the
 * chord it crossed (or that chord's whole ring, when the crossing cannot be traced to one chord).
 *
 * @param List<SampledRing>        outline    The outline rings.
 * @param MeshLayout               layout     The layout the triangulation ran on.
 * @param Triangulation            result     The triangulation.
 * @param List<RejectedConstraint> rejections The rejected boundary edges.
 * @return Int The number of chords densified.
 */
private fun densifyCrossings(outline: List<SampledRing>, layout: MeshLayout, result: Triangulation, rejections: List<RejectedConstraint>): Int {
	val chordsByRing = HashMap<Int, MutableSet<Int>>()
	val wholeRings = HashSet<Int>()

	for (rejection in rejections) {
		chordsByRing.getOrPut(rejection.polylineIndex) { HashSet() }.add(rejection.edgeIndex)
		val crossedChord = chordOf(layout, rejection.crossedStartPoint, rejection.crossedEndPoint, outline)

		if (crossedChord != null) {
			chordsByRing.getOrPut(crossedChord.first) { HashSet() }.add(crossedChord.second)
		} else if (rejection.crossedStartPoint >= 0 && layout.ringOf[rejection.crossedStartPoint] >= 0) {
			wholeRings.add(layout.ringOf[rejection.crossedStartPoint])
		}
	}

	var densified = 0

	for (ringIndex in wholeRings.sorted()) {
		densified += outline[ringIndex].sampleCount
		outline[ringIndex].densifyAll()
	}

	for (ringIndex in chordsByRing.keys.sorted()) {
		if (ringIndex in wholeRings) {
			continue
		}

		val ring = outline[ringIndex]
		val wrapChord = ring.sampleCount - 1
		// Densify from the highest chord down so the lower indices stay valid; the wrapping chord last,
		// since its points may land before the first sample and shift every index.
		val chords = chordsByRing.getValue(ringIndex).sortedDescending()

		for (chord in chords) {
			if (chord != wrapChord) {
				ring.densifyChord(chord)
				densified++
			}
		}

		if (wrapChord in chords) {
			ring.densifyChord(ring.sampleCount - 1)
			densified++
		}
	}

	return densified
}

/**
 * The outline ring and chord a crossed edge belongs to, when its two ends are consecutive points of
 * one ring.
 *
 * @param MeshLayout        layout  The layout.
 * @param Int               start   One end (a canonical point index).
 * @param Int               end     The other end.
 * @param List<SampledRing> outline The outline rings.
 * @return Pair<Int, Int>? The ring and chord, or null.
 */
private fun chordOf(layout: MeshLayout, start: Int, end: Int, outline: List<SampledRing>): Pair<Int, Int>? {
	if (start < 0 || end < 0) {
		return null
	}

	val ring = layout.ringOf[start]

	if (ring < 0 || ring != layout.ringOf[end]) {
		return null
	}

	val count = outline[ring].sampleCount
	val startPosition = layout.positionOf[start]
	val endPosition = layout.positionOf[end]

	return when {
		(startPosition + 1) % count == endPosition -> ring to startPosition
		(endPosition + 1) % count == startPosition -> ring to endPosition
		else -> null
	}
}

/**
 * Compacts the triangulation into a mesh and runs the structural checks.
 *
 * @param MeshLayout    layout              The layout.
 * @param Triangulation result              The triangulation.
 * @param AlphaField    field               The field.
 * @param Double        minimumMargin       The clearance boundary edges must keep.
 * @param Int           densifiedChordCount Chords densified on the way.
 * @return AssembledMesh The mesh, or the failure.
 */
private fun finish(layout: MeshLayout, result: Triangulation, field: AlphaField, minimumMargin: Double, densifiedChordCount: Int): AssembledMesh {
	val pointCount = layout.points.size / 2
	val droppedInnerEdgeCount = result.rejectedConstraints.count { rejection -> rejection.kind == ConstraintKind.Segment }
	val used = BooleanArray(pointCount)

	for (vertex in result.triangles) {
		used[vertex] = true
	}

	val compacted = IntArray(pointCount) { -1 }
	var vertexCount = 0

	for (point in 0 until pointCount) {
		if (used[point]) {
			compacted[point] = vertexCount
			vertexCount++
		}
	}

	val positions = FloatArray(2 * vertexCount)

	for (point in 0 until pointCount) {
		if (used[point]) {
			positions[2 * compacted[point]] = layout.points[2 * point].toFloat()
			positions[2 * compacted[point] + 1] = layout.points[2 * point + 1].toFloat()
		}
	}

	val triangles = IntArray(result.triangles.size) { entry -> compacted[result.triangles[entry]] }
	val pinCount = pointCount - layout.firstPin
	val pinVertices = IntArray(pinCount)
	val pinCanonicals = HashMap<Int, Int>()
	var sharedPinCount = 0

	for (pin in 0 until pinCount) {
		val canonical = result.canonicalIndex[layout.firstPin + pin]

		if (!used[canonical]) {
			return AssembledMesh(null, "pin $pin is not a vertex of the mesh", densifiedChordCount, droppedInnerEdgeCount, 0, layout.outlinePointCount)
		}

		pinVertices[pin] = compacted[canonical]
		val firstHolder = pinCanonicals.put(canonical, pin)

		if (firstHolder != null) {
			sharedPinCount++
		}
	}

	val clearanceFailure = boundaryClearanceFailure(positions, triangles, field, minimumMargin)

	if (clearanceFailure != null) {
		return AssembledMesh(null, clearanceFailure, densifiedChordCount, droppedInnerEdgeCount, sharedPinCount, layout.outlinePointCount)
	}

	return AssembledMesh(PlanarTriangleMesh(positions, triangles, pinVertices), null, densifiedChordCount, droppedInnerEdgeCount, sharedPinCount, layout.outlinePointCount)
}

/**
 * Checks that every boundary edge of the mesh (an edge with a triangle on one side only) keeps the
 * minimum margin from the art.
 *
 * @param FloatArray positions     The mesh's vertices.
 * @param IntArray   triangles     The mesh's triangles.
 * @param AlphaField field         The field.
 * @param Double     minimumMargin The clearance required.
 * @return String? A description of the first failing edge, or null when all clear.
 */
private fun boundaryClearanceFailure(positions: FloatArray, triangles: IntArray, field: AlphaField, minimumMargin: Double): String? {
	val directed = HashSet<Long>()

	for (entry in triangles.indices) {
		val next = if (entry % 3 == 2) entry - 2 else entry + 1
		directed.add(edgeKey(triangles[entry], triangles[next]))
	}

	for (entry in triangles.indices) {
		val start = triangles[entry]
		val end = triangles[if (entry % 3 == 2) entry - 2 else entry + 1]

		if (edgeKey(end, start) in directed) {
			continue
		}

		val clears =
			field.segmentClears(
				positions[2 * start].toDouble(),
				positions[2 * start + 1].toDouble(),
				positions[2 * end].toDouble(),
				positions[2 * end + 1].toDouble(),
				minimumMargin,
			)

		if (!clears) {
			return "boundary edge $start -> $end comes within $minimumMargin px of the art"
		}
	}

	return null
}

/**
 * Packs a directed edge into one Long.
 *
 * @param Int start The start vertex.
 * @param Int end   The end vertex.
 * @return Long The key.
 */
private fun edgeKey(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)