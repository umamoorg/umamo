package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaField
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.mesh.forEachEdge
import org.umamo.geometry.triangulation.ConstraintKind
import org.umamo.geometry.triangulation.RejectedConstraint
import org.umamo.geometry.triangulation.Triangulation
import org.umamo.geometry.triangulation.refine.BoundarySplitCheck
import org.umamo.geometry.triangulation.refine.PiecewiseLinearGuide
import org.umamo.geometry.triangulation.refine.RefinedTriangulation
import org.umamo.geometry.triangulation.refine.RefinementOptions
import org.umamo.geometry.triangulation.refine.RefinementStatistics
import org.umamo.geometry.triangulation.refine.triangulateAndRefine

/*
 * Assembles the mesher's points into one triangulation: the outline rings as BOUNDARY rings (they
 * decide inside from outside), the inner rings - whole, or the stretches a strand left of them - as
 * neutral SEGMENTS, the lattice, the strands' centerline vertices, and the pins as free points.  Simplified outline rings can cross one another where two islands or a narrow neck come
 * close; the triangulator then rejects a boundary edge and leaves a ring open, so both chords
 * involved are replaced by the traced points they skipped and the triangulation runs again - traced
 * rings are disjoint, so this ends.  The finished mesh keeps only the vertices its triangles use.
 *
 * With a minimum angle the triangulation is refined in the same step, within what the vertex budget
 * leaves after the placed points.  An outline chord may be split only where both new chords still
 * clear the art by the minimum margin and, when the split point lies inside the chord, the sliver it
 * cuts off holds no art pixel - so refinement keeps every guarantee below.
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
 * @property RefinementStatistics? refinement          What refinement did to the final triangulation, or null when none ran.
 */
internal class AssembledMesh(
	val mesh: PlanarTriangleMesh?,
	val failure: String?,
	val densifiedChordCount: Int,
	val droppedInnerEdgeCount: Int,
	val sharedPinCount: Int,
	val outlineVertexCount: Int,
	val refinement: RefinementStatistics?,
)

/**
 * Triangulates the mesher's points, untangling crossing outline rings, refines toward the minimum
 * angle, and checks the result.
 *
 * @param List<SampledRing> outline    The outline rings (densified in place when they cross).
 * @param List<InnerPolyline> innerRings The inner rings' points, closed or open.
 * @param DoubleArray       lattice    The lattice points and every other free point but the pins.
 * @param DoubleArray       pins       The pins, Float-rounded.
 * @param AlphaField        field      The field, for the split checks and the final clearance check.
 * @param ArtMeshSettings   settings   The settings: the minimum margin, the minimum angle, and the vertex budget.
 * @param Int               cellSize   The working grid's cell size, which sets refinement's floors.
 * @param PiecewiseLinearGuide? guide  The mesh being replaced, whose bends refinement keeps first, or null.
 * @return AssembledMesh The mesh or the failure, with what was adjusted.
 */
internal fun assembleMesh(
	outline: List<SampledRing>,
	innerRings: List<InnerPolyline>,
	lattice: DoubleArray,
	pins: DoubleArray,
	field: AlphaField,
	settings: ArtMeshSettings,
	cellSize: Int,
	guide: PiecewiseLinearGuide? = null,
): AssembledMesh {
	var densifiedChordCount = 0
	var round = 0
	val splitCheck = boundarySplitCheckOf(field, settings.minimumMargin)

	while (true) {
		val layout = MeshLayout(outline, innerRings, lattice, pins)
		val refined = triangulateAndRefine(layout.points, layout.boundaries, layout.segments, refinementOptionsOf(settings, layout, cellSize, splitCheck, guide))
		val triangulation = refined.triangulation
		val boundaryRejections = triangulation.rejectedConstraints.filter { rejection -> rejection.kind == ConstraintKind.Boundary }

		if (boundaryRejections.isEmpty() && triangulation.classificationConsistent) {
			return finish(layout, refined, field, settings.minimumMargin, densifiedChordCount)
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
			return AssembledMesh(null, "outline rings still cross at full density", densifiedChordCount, 0, 0, layout.outlinePointCount, null)
		}
	}
}

/**
 * How one round refines: toward the minimum angle, adding no more vertices than the budget leaves after
 * the placed points, with floors a quarter and a half of a working cell - finer than the field can
 * tell apart.
 *
 * @param ArtMeshSettings    settings   The settings.
 * @param MeshLayout         layout     The round's points.
 * @param Int                cellSize   The working cell size.
 * @param BoundarySplitCheck splitCheck The field's check on outline splits.
 * @param PiecewiseLinearGuide? guide  The mesh being replaced, or null.
 * @return RefinementOptions The options; a zero target, no guide, or no room adds nothing.
 */
private fun refinementOptionsOf(settings: ArtMeshSettings, layout: MeshLayout, cellSize: Int, splitCheck: BoundarySplitCheck, guide: PiecewiseLinearGuide?): RefinementOptions =
	RefinementOptions(
		minimumAngleDegrees = settings.minimumAngle,
		maximumAddedPoints = maxOf(0, settings.vertexBudget - layout.points.size / 2),
		minimumSeparation = cellSize / 4.0,
		minimumSegmentLength = cellSize / 2.0,
		boundarySplitCheck = splitCheck,
		guide = guide,
		guideTolerance = settings.deformationTolerance,
	)

/**
 * The field's check on an outline chord: it keeps the minimum margin from every art pixel AND cuts no
 * art pixel off - the cap between it and the traced stretch it replaces must hold none, or even-odd
 * would erase a speck swallowed there along with its own ring.  With [boundarySplitCheckOf], what
 * guarantees every art pixel stays enclosed.
 *
 * @param AlphaField field         The art's field.
 * @param Double     minimumMargin The clearance every outline chord keeps.
 * @return ChordCheck The check.
 */
internal fun coversArtCheckOf(field: AlphaField, minimumMargin: Double): ChordCheck =
	ChordCheck { ring, startArc, endArc, between ->
		val startX = ring.roundedX(startArc)
		val startY = ring.roundedY(startArc)
		val endX = ring.roundedX(endArc)
		val endY = ring.roundedY(endArc)

		field.segmentClears(startX, startY, endX, endY, minimumMargin) && !field.containsOpaquePixelCenter(capPolygon(ring, startX, startY, endX, endY, between))
	}

/**
 * The polygon between a chord and the stretch of traced ring it replaces.
 *
 * @param SampledRing ring    The ring.
 * @param Double      startX  The chord's start x.
 * @param Double      startY  The chord's start y.
 * @param Double      endX    The chord's end x.
 * @param Double      endY    The chord's end y.
 * @param List<Int>   between The dense vertices the chord skips, in ring order.
 * @return DoubleArray The polygon, x0, y0, ...: the chord's start, the skipped vertices, its end.
 */
private fun capPolygon(ring: SampledRing, startX: Double, startY: Double, endX: Double, endY: Double, between: List<Int>): DoubleArray {
	val cap = DoubleArray(2 * (between.size + 2))
	cap[0] = startX
	cap[1] = startY

	for ((position, vertex) in between.withIndex()) {
		cap[2 * position + 2] = ring.denseX(vertex)
		cap[2 * position + 3] = ring.denseY(vertex)
	}

	cap[cap.size - 2] = endX
	cap[cap.size - 1] = endY

	return cap
}

/**
 * The field's check on an outline split: both new chords keep the minimum margin from the art, and a
 * split point inside the chord cuts off no art pixel's center.
 *
 * @param AlphaField field         The art's field.
 * @param Double     minimumMargin The clearance every outline chord keeps.
 * @return BoundarySplitCheck The check.
 */
internal fun boundarySplitCheckOf(field: AlphaField, minimumMargin: Double): BoundarySplitCheck =
	BoundarySplitCheck { startX, startY, endX, endY, splitX, splitY, cutsInside ->
		val chordsClear = field.segmentClears(startX, startY, splitX, splitY, minimumMargin) && field.segmentClears(splitX, splitY, endX, endY, minimumMargin)

		chordsClear && (!cutsInside || !field.containsOpaquePixelCenter(doubleArrayOf(startX, startY, splitX, splitY, endX, endY)))
	}

/**
 * The flat point list handed to the triangulator, and which outline ring and position each outline
 * point came from.
 *
 * @param List<SampledRing> outline    The outline rings.
 * @param List<InnerPolyline> innerRings The inner rings' points, closed or open.
 * @param DoubleArray       lattice    The free points but the pins.
 * @param DoubleArray       pins       The pins.
 */
private class MeshLayout(outline: List<SampledRing>, innerRings: List<InnerPolyline>, lattice: DoubleArray, pins: DoubleArray) {
	/** Every point: outline rings, then inner rings, then the lattice, then the pins. */
	val points: DoubleArray

	/** Each outline ring as indices into [points]. */
	val boundaries: List<IntArray>

	/** Each inner polyline as indices into [points], a closed one returning to its first point. */
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
		val innerPointCount = innerRings.sumOf { ring -> ring.pointCount }
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

		for (ring in innerRings) {
			val count = ring.pointCount
			ring.points.copyInto(points, 2 * next)
			val start = next
			// A closed polyline returns to its first point.
			segmentList.add(if (ring.closed) IntArray(count + 1) { position -> start + position % count } else IntArray(count) { position -> start + position })
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
 * Compacts the refined triangulation into a mesh and runs the structural checks.
 *
 * @param MeshLayout           layout              The layout.
 * @param RefinedTriangulation refined             The triangulation, over the layout's points and any refinement added.
 * @param AlphaField           field               The field.
 * @param Double               minimumMargin       The clearance boundary edges must keep.
 * @param Int                  densifiedChordCount Chords densified on the way.
 * @return AssembledMesh The mesh, or the failure.
 */
private fun finish(layout: MeshLayout, refined: RefinedTriangulation, field: AlphaField, minimumMargin: Double, densifiedChordCount: Int): AssembledMesh {
	val result = refined.triangulation
	val points = refined.points
	val refinement = refined.statistics
	val pointCount = points.size / 2
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
			positions[2 * compacted[point]] = points[2 * point].toFloat()
			positions[2 * compacted[point] + 1] = points[2 * point + 1].toFloat()
		}
	}

	val triangles = IntArray(result.triangles.size) { entry -> compacted[result.triangles[entry]] }
	val pinCount = refined.inputPointCount - layout.firstPin
	val pinVertices = IntArray(pinCount)
	val pinCanonicals = HashMap<Int, Int>()
	var sharedPinCount = 0

	for (pin in 0 until pinCount) {
		val canonical = result.canonicalIndex[layout.firstPin + pin]

		if (!used[canonical]) {
			return AssembledMesh(null, "pin $pin is not a vertex of the mesh", densifiedChordCount, droppedInnerEdgeCount, 0, layout.outlinePointCount, refinement)
		}

		pinVertices[pin] = compacted[canonical]
		val firstHolder = pinCanonicals.put(canonical, pin)

		if (firstHolder != null) {
			sharedPinCount++
		}
	}

	val mesh = PlanarTriangleMesh(positions, triangles, pinVertices)
	val clearanceFailure = boundaryClearanceFailure(mesh, field, minimumMargin)

	if (clearanceFailure != null) {
		return AssembledMesh(null, clearanceFailure, densifiedChordCount, droppedInnerEdgeCount, sharedPinCount, layout.outlinePointCount, refinement)
	}

	return AssembledMesh(mesh, null, densifiedChordCount, droppedInnerEdgeCount, sharedPinCount, layout.outlinePointCount, refinement)
}

/**
 * Checks that every boundary edge of the mesh (an edge with a triangle on one side only) keeps the
 * minimum margin from the art.
 *
 * @param PlanarTriangleMesh mesh          The mesh.
 * @param AlphaField         field         The field.
 * @param Double             minimumMargin The clearance required.
 * @return String? A description of the first failing edge, or null when all clear.
 */
private fun boundaryClearanceFailure(mesh: PlanarTriangleMesh, field: AlphaField, minimumMargin: Double): String? {
	val positions = mesh.positions

	mesh.forEachEdge { start, end, isBoundary ->
		if (!isBoundary) {
			return@forEachEdge
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