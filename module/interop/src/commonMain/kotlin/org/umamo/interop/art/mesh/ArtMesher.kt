package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaField
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaField
import org.umamo.geometry.polyline.outermostRings
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * The art mesher: a deformation-ready triangle mesh over a layer's art, built from its signed distance
 * field (docs/plan/auto-mesh.md, the SDF band mesher).
 *
 * The OUTLINE is the field's contour a little outside the art - at least the minimum margin plus two
 * working cells.  A traced chord provably clears the art by the minimum margin plus a little over half
 * a cell at that level (the field's sampling and interpolation cost up to 1.42 cells), and thinning the
 * traced ring's near-duplicate vertices costs at most a quarter cell more, so every chord kept from
 * the thinned ring still clears the margin and every art pixel stays enclosed.  It is simplified to
 * the outline spacing with keypoints
 * at sharp turns, and any simplified chord that comes too close to the art, or cuts off a piece of it,
 * is split again from the traced contour.  An INNER RING follows the art's edge a margin inside it, a
 * hexagonal LATTICE fills the interior, PINS become vertices at their exact positions, and the
 * constrained triangulation of all of it, clipped to the outline, is the mesh.
 *
 * Filling holes meshes the art's silhouette instead.  The outline keeps only the rings nothing else
 * encloses, so no hole ring (and no ring of art inside a hole) ever reaches the triangulation and the
 * mesh is one solid sheet per piece; the outline itself is traced from the art's own field, so every
 * guarantee above still holds.  The inner ring and lattice come from the field with its holes
 * filled, so they run evenly across the holes instead of only through the art.
 *
 * Everything is in the layer raster's own pixels, y down, rounded to Float before it is triangulated,
 * so the triangles' orientation (orient2d > 0, the birth quad's winding) holds as stored.  The output
 * is a function of the raster, the settings, and the pins alone.
 */

/** Rounds of widening the spacings to meet the vertex budget before giving up. */
private const val BUDGET_ROUNDS: Int = 5

/**
 * Meshes one layer raster.
 *
 * @param LayerRaster     raster   The layer's pixels.
 * @param ArtMeshSettings settings How to space and place vertices.
 * @param DoubleArray     pins     Points that must become vertices, x0, y0, ..., raster px (glued vertices kept through a regeneration).
 * @return ArtMeshResult The mesh, or why there is none, with what was adjusted.
 */
public fun generateArtMesh(raster: LayerRaster, settings: ArtMeshSettings, pins: DoubleArray = DoubleArray(0)): ArtMeshResult {
	require(pins.size % 2 == 0) { "pins must hold x, y pairs, but has ${pins.size} components" }

	for (component in pins.indices) {
		require(pins[component].isFinite()) { "pin component $component is not finite: ${pins[component]}" }
	}

	// The working cell follows the margins (contours move by up to about a cell), never the spacing.
	val nearestMargin = if (settings.innerMargin > 0.0) minOf(settings.outerMargin, settings.innerMargin) else settings.outerMargin
	val requestedCellSize = maxOf(1, floor(nearestMargin / 2.0).toInt())
	// Enough padding for the outline level at ANY cell size the grid cap may force: the level is at most
	// outerMargin + minimumMargin + 2 cells, and the contour must stay clear of the border.
	val paddingCells = ceil(settings.outerMargin / requestedCellSize).toInt() + ceil(settings.minimumMargin / requestedCellSize).toInt() + 4
	val field = raster.alphaField(settings.alphaThreshold, requestedCellSize, paddingCells, pins) ?: return ArtMeshResult(null, listOf(ArtMeshNotice.NothingOpaque), null)
	val notices = ArrayList<ArtMeshNotice>()

	if (field.cellSize > requestedCellSize) {
		notices.add(ArtMeshNotice.WorkingGridCoarsened(requestedCellSize, field.cellSize))
	}

	val cellSize = field.cellSize
	val outlineLevel = maxOf(settings.outerMargin, settings.minimumMargin + 2.0 * cellSize)
	val tolerance = maxOf(1.0, cellSize.toDouble())
	val thinning = cellSize / 4.0
	val tracedOutline = field.isoRings(outlineLevel)
	val outlineRings = if (settings.fillHoles) outermostRings(tracedOutline).map { index -> tracedOutline[index] } else tracedOutline
	val denseOutline = outlineRings.map { ring -> thinTracedRing(ring, thinning) }
	val interiorField = if (settings.fillHoles) field.withHolesFilled(outlineLevel) else field
	val denseInner = if (settings.innerMargin > 0.0) interiorField.isoRings(-settings.innerMargin).map { ring -> thinTracedRing(ring, thinning) } else emptyList()
	val roundedPins = DoubleArray(pins.size) { component -> pins[component].toFloat().toDouble() }
	val pinCount = pins.size / 2
	val vertexFloor = denseOutline.size * settings.minimumOutlinePoints + pinCount

	if (vertexFloor > settings.vertexBudget) {
		notices.add(ArtMeshNotice.OverBudget(vertexFloor, settings.vertexBudget))

		return ArtMeshResult(null, notices, null)
	}

	var outlineSpacing = settings.outlineSpacing
	var interiorSpacing = settings.interiorSpacing
	var round = 0

	while (true) {
		val points = placePoints(field, interiorField, settings, denseOutline, denseInner, roundedPins, outlineSpacing, interiorSpacing, tolerance)

		if (points.vertexCount <= settings.vertexBudget) {
			if (round > 0) {
				notices.add(ArtMeshNotice.SpacingCoarsened(outlineSpacing, interiorSpacing))
			}

			return assemble(points, field, settings, notices, cellSize, outlineLevel, outlineSpacing, interiorSpacing, pinCount)
		}

		round++

		// With the floor filling the whole budget, no widening can help.
		if (round > BUDGET_ROUNDS || vertexFloor >= settings.vertexBudget) {
			notices.add(ArtMeshNotice.OverBudget(points.vertexCount, settings.vertexBudget))

			return ArtMeshResult(null, notices, null)
		}

		// Only the vertices above the floor (every outline ring's minimum) shrink as the spacing widens:
		// the outline with the spacing and the interior with its square, so the square root of the
		// reducible overshoot (plus a little) usually lands under the budget in a round or two.
		val reducible = (points.vertexCount - vertexFloor).toDouble()
		val reducibleBudget = (settings.vertexBudget - vertexFloor).toDouble()
		val widening = sqrt(reducible / reducibleBudget) * 1.05
		outlineSpacing *= widening
		interiorSpacing *= widening
	}
}

/**
 * Every point one attempt places, before triangulation.
 *
 * @property List<SampledRing> outline    The outline rings.
 * @property List<DoubleArray> innerRings The inner rings' points.
 * @property DoubleArray       lattice    The lattice points.
 * @property DoubleArray       pins       The pins.
 */
private class PlacedPoints(val outline: List<SampledRing>, val innerRings: List<DoubleArray>, val lattice: DoubleArray, val pins: DoubleArray) {
	/** The vertex count the mesh would have (an upper bound; aliasing and unused points only lower it). */
	val vertexCount: Int
		get() = outline.sumOf { ring -> ring.sampleCount } + innerRings.sumOf { ring -> ring.size / 2 } + lattice.size / 2 + pins.size / 2
}

/**
 * Places the outline, inner rings, and lattice at one pair of spacings.
 *
 * @param AlphaField        field           The art's field, which every outline chord is checked against.
 * @param AlphaField        interiorField   The field the inner rings and lattice follow (the silhouette's when filling holes).
 * @param ArtMeshSettings   settings        The settings.
 * @param List<DoubleArray> denseOutline    The traced outline contours.
 * @param List<DoubleArray> denseInner      The traced inner contours.
 * @param DoubleArray       pins            The pins, Float-rounded.
 * @param Double            outlineSpacing  The outline spacing.
 * @param Double            interiorSpacing The interior spacing.
 * @param Double            tolerance       The corner-finding tolerance.
 * @return PlacedPoints The points.
 */
private fun placePoints(
	field: AlphaField,
	interiorField: AlphaField,
	settings: ArtMeshSettings,
	denseOutline: List<DoubleArray>,
	denseInner: List<DoubleArray>,
	pins: DoubleArray,
	outlineSpacing: Double,
	interiorSpacing: Double,
	tolerance: Double,
): PlacedPoints {
	val minimumMargin = settings.minimumMargin
	// A chord stands only when it keeps the minimum margin from every art pixel AND cuts no art pixel
	// off - the cap between it and the traced stretch it replaces must hold none, or even-odd would
	// erase a speck swallowed there along with its own ring.
	val coversArt =
		ChordCheck { ring, startArc, endArc, between ->
			val startX = ring.roundedX(startArc)
			val startY = ring.roundedY(startArc)
			val endX = ring.roundedX(endArc)
			val endY = ring.roundedY(endArc)

			if (!field.segmentClears(startX, startY, endX, endY, minimumMargin)) {
				false
			} else {
				val cap = DoubleArray(2 * (between.size + 2))
				cap[0] = startX
				cap[1] = startY

				for ((position, vertex) in between.withIndex()) {
					cap[2 * position + 2] = ring.denseX(vertex)
					cap[2 * position + 3] = ring.denseY(vertex)
				}

				cap[cap.size - 2] = endX
				cap[cap.size - 1] = endY
				!field.containsOpaquePixelCenter(cap)
			}
		}
	val outline =
		denseOutline.map { dense ->
			val ring = SampledRing(dense)
			ring.sampleEvenly(outlineSpacing, tolerance, settings.minimumOutlinePoints)
			ring.refine(coversArt)
			ring
		}
	val innerRings = innerRingPoints(denseInner, interiorField, interiorSpacing, settings.innerMargin, tolerance, pins)
	val lattice = latticePoints(interiorField, interiorSpacing, settings.innerMargin, pins)

	return PlacedPoints(outline, innerRings, lattice, pins)
}

/**
 * Assembles the placed points into the final mesh and result.
 *
 * @param PlacedPoints          points          The points.
 * @param AlphaField            field           The field.
 * @param ArtMeshSettings       settings        The settings.
 * @param MutableList           notices         The notices so far.
 * @param Int                   cellSize        The working cell size.
 * @param Double                outlineLevel    The outline's level.
 * @param Double                outlineSpacing  The outline spacing used.
 * @param Double                interiorSpacing The interior spacing used.
 * @param Int                   pinCount        The number of pins.
 * @return ArtMeshResult The result.
 */
private fun assemble(
	points: PlacedPoints,
	field: AlphaField,
	settings: ArtMeshSettings,
	notices: MutableList<ArtMeshNotice>,
	cellSize: Int,
	outlineLevel: Double,
	outlineSpacing: Double,
	interiorSpacing: Double,
	pinCount: Int,
): ArtMeshResult {
	val refinedChordCount = points.outline.sumOf { ring -> ring.refinedChordCount }
	val assembled = assembleMesh(points.outline, points.innerRings, points.lattice, points.pins, field, settings.minimumMargin)

	if (assembled.densifiedChordCount > 0) {
		notices.add(ArtMeshNotice.OutlineDensified(assembled.densifiedChordCount))
	}

	if (assembled.droppedInnerEdgeCount > 0) {
		notices.add(ArtMeshNotice.InnerRingEdgesDropped(assembled.droppedInnerEdgeCount))
	}

	if (assembled.sharedPinCount > 0) {
		notices.add(ArtMeshNotice.PinsShareVertex(assembled.sharedPinCount))
	}

	val statistics =
		ArtMeshStatistics(
			cellSize = cellSize,
			outlineLevel = outlineLevel,
			outlineSpacing = outlineSpacing,
			interiorSpacing = interiorSpacing,
			outlineRingCount = points.outline.size,
			outlineVertexCount = assembled.outlineVertexCount,
			refinedChordCount = refinedChordCount,
			innerRingCount = points.innerRings.size,
			innerVertexCount = points.innerRings.sumOf { ring -> ring.size / 2 },
			latticeVertexCount = points.lattice.size / 2,
			pinCount = pinCount,
		)
	val mesh = assembled.mesh

	if (mesh == null) {
		notices.add(ArtMeshNotice.StructureCheckFailed(assembled.failure ?: "unknown"))

		return ArtMeshResult(null, notices, statistics)
	}

	// Untangling crossing rings adds traced points; the budget still holds the final word.
	if (mesh.vertexCount > settings.vertexBudget) {
		notices.add(ArtMeshNotice.OverBudget(mesh.vertexCount, settings.vertexBudget))

		return ArtMeshResult(null, notices, statistics)
	}

	return ArtMeshResult(mesh, notices, statistics)
}