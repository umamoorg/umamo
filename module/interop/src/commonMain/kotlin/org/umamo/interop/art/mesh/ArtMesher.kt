package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaChannel
import org.umamo.format.art.AlphaField
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaChannel
import org.umamo.format.art.alphaField
import org.umamo.geometry.polyline.outermostRings
import org.umamo.geometry.polyline.ringContainsPoint
import org.umamo.geometry.triangulation.refine.PiecewiseLinearGuide
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * The art mesher: a deformation-ready triangle mesh over a layer's art, built from its signed distance
 * field - the outline and the inner ring are contours of the field, and a lattice fills between them.
 *
 * The OUTLINE is the field's contour a little outside the art - at least the minimum margin plus two
 * working cells.  A traced chord provably clears the art by the minimum margin plus a little over half
 * a cell at that level (the field's sampling and interpolation cost up to 1.42 cells), and thinning the
 * traced ring's near-duplicate vertices costs at most a quarter cell more, so every chord kept from
 * the thinned ring still clears the margin and every art pixel stays enclosed.  It is simplified to
 * the outline spacing with keypoints at sharp turns, and any simplified chord that comes too close to
 * the art, or cuts off a piece of it, is split again from the traced contour.  A HOLE in the art too
 * small for the outline spacing - its ring at the outline's level shorter than one spacing around, as
 * where a gap barely twice that level wide pinches off a pocket - is meshed over instead of cut out,
 * along with any ring inside it: cutting it would crowd the minimum outline points into a speck of
 * transparency, and refinement would grade the mesh around it down to that size.  An INNER RING follows
 * the art's edge a margin inside it, a hexagonal LATTICE fills the interior, PINS become vertices at
 * their exact positions, and the constrained triangulation of all of it, clipped to the outline, is
 * the mesh.  A pin's SURROUNDINGS (the old triangles around a glued vertex) count as art, so a pin off
 * the art is joined to it by the surface it had rather than meshed around on its own.  With a MINIMUM
 * ANGLE the triangulation is refined toward it in what the vertex budget leaves (see assembleMesh), and
 * with a GUIDE - the mesh being replaced, carrying the rig built on it - the old vertices where that rig
 * bends go in first, at their exact points, until the new mesh follows it to the deformation tolerance.
 *
 * Over the VERTEX BUDGET the interior gives way first: its spacing widens while the outline keeps the
 * silhouette as asked, up to the interior thinning limit times the outline spacing; past that the two
 * widen together at the limit, so a dense outline never stands around a nearly empty interior.  Only
 * when the outline can give no more (every ring at its minimum) is the interior left to refinement.  A
 * mesh that fits is never coarsened; one that must be keeps a share of the budget back, so refinement
 * has room to fix what coarsening makes - unless the last round only fits the whole budget, which then
 * stands rather than fail.
 *
 * Filling holes meshes the art's silhouette instead.  The outline keeps only the rings nothing else
 * encloses, so no hole ring (and no ring of art inside a hole) ever reaches the triangulation and the
 * mesh is one solid sheet per piece; the outline itself is traced from the art's own field, so every
 * guarantee above still holds.  The inner ring and lattice come from the field with its holes
 * filled, so they run evenly across the holes instead of only through the art.
 *
 * Everything is in the layer raster's own pixels, y down, rounded to Float before it is triangulated,
 * so the triangles' orientation (orient2d > 0, the birth quad's winding) holds as stored.  The output
 * is a function of the alpha channel, the settings, the pins, and their surroundings alone.
 */

/** Rounds of thinning the interior and widening the outline to meet the vertex budget before giving up. */
private const val BUDGET_ROUNDS: Int = 8

/** How far past its estimate a coarsened spacing goes, so the next round lands under the budget rather than at it. */
private const val SPACING_OVERSHOOT: Double = 1.05

/**
 * The share of the vertex budget held back for refinement once a mesh has to be coarsened to fit.  A
 * coarsened mesh pairs a dense outline with a thinned interior, which makes the very slivers refinement
 * fixes, so it needs real room: on the corpus a third of the budget halves what a fifth leaves below
 * the minimum angle, with fewer vertices in all.
 */
private const val REFINEMENT_RESERVE_SHARE: Double = 0.30

/**
 * Meshes one layer raster; see [generateArtMesh] over an [AlphaChannel], which is all it reads.
 *
 * @param LayerRaster     raster   The layer's pixels.
 * @param ArtMeshSettings settings How to space and place vertices.
 * @param DoubleArray     pins            Points that must become vertices, x0, y0, ..., raster px.
 * @param DoubleArray     pinSurroundings Triangles that count as art, six coordinates each, raster px.
 * @param PiecewiseLinearGuide? guide     The mesh being replaced with the rig's fields, raster px, or null.
 * @return ArtMeshResult The mesh, or why there is none, with what was adjusted.
 */
public fun generateArtMesh(
	raster: LayerRaster,
	settings: ArtMeshSettings,
	pins: DoubleArray = DoubleArray(0),
	pinSurroundings: DoubleArray = DoubleArray(0),
	guide: PiecewiseLinearGuide? = null,
): ArtMeshResult = generateArtMesh(raster.alphaChannel(), settings, pins, pinSurroundings, guide)

/**
 * Meshes one layer's alpha channel.
 *
 * @param AlphaChannel    alpha    The layer's alpha, the only channel the mesh depends on.
 * @param ArtMeshSettings settings How to space and place vertices.
 * @param DoubleArray     pins            Points that must become vertices, x0, y0, ..., raster px (glued vertices kept through a regeneration).
 * @param DoubleArray     pinSurroundings Triangles that count as art, six coordinates each, raster px (the old surface around those glued vertices).
 * @param PiecewiseLinearGuide? guide     The mesh being replaced, raster px, with the fields of the rig built on it (see PiecewiseLinearGuide), or null.
 * @return ArtMeshResult The mesh, or why there is none, with what was adjusted.
 */
public fun generateArtMesh(
	alpha: AlphaChannel,
	settings: ArtMeshSettings,
	pins: DoubleArray = DoubleArray(0),
	pinSurroundings: DoubleArray = DoubleArray(0),
	guide: PiecewiseLinearGuide? = null,
): ArtMeshResult {
	requireFinitePins(pins, pinSurroundings)
	val notices = ArrayList<ArtMeshNotice>()
	val layer = traceLayer(alpha, settings, pins, pinSurroundings, guide, notices) ?: return ArtMeshResult(null, listOf(ArtMeshNotice.NothingOpaque), null)
	val vertexFloor = layer.outline.size * settings.minimumOutlinePoints + layer.pinCount

	if (vertexFloor > settings.vertexBudget) {
		return overBudget(notices, vertexFloor, settings.vertexBudget, null)
	}

	// The strands are found once, after the floor has had its say; each attempt places their rungs at its own spacing.
	val strandPlan = if (settings.strandRungs) planStrands(layer.interiorField, layer.outline, settings, layer.cellSize, layer.outlineLevel) else StrandPlan.NONE
	val schedule = BudgetSchedule(settings, layer.outline, vertexFloor)

	while (true) {
		val points = placePoints(layer, strandPlan, schedule.outlineSpacing, schedule.interiorSpacing)

		if (schedule.fits(points)) {
			if (schedule.round > 0) {
				notices.add(ArtMeshNotice.SpacingCoarsened(schedule.outlineSpacing, schedule.interiorSpacing))
			}

			return assemble(layer, points, notices, schedule.outlineSpacing, schedule.interiorSpacing)
		}

		if (!schedule.coarsen(points)) {
			return overBudget(notices, points.vertexCount, settings.vertexBudget, null)
		}
	}
}

/**
 * Checks that pins and their surroundings are whole, finite coordinates.
 *
 * @param DoubleArray pins            The pins, x0, y0, ...
 * @param DoubleArray pinSurroundings The surroundings, six coordinates per triangle.
 */
private fun requireFinitePins(pins: DoubleArray, pinSurroundings: DoubleArray) {
	require(pins.size % 2 == 0) { "pins must hold x, y pairs, but has ${pins.size} components" }

	for (component in pins.indices) {
		require(pins[component].isFinite()) { "pin component $component is not finite: ${pins[component]}" }
	}

	require(pinSurroundings.size % 6 == 0) { "pin surroundings must hold six coordinates per triangle, but has ${pinSurroundings.size} components" }

	for (component in pinSurroundings.indices) {
		require(pinSurroundings[component].isFinite()) { "pin surroundings component $component is not finite: ${pinSurroundings[component]}" }
	}
}

/**
 * One layer's field and contours, traced once: everything the attempts at each spacing share.
 *
 * @property AlphaField            field         The art's field, which every outline chord is checked against.
 * @property AlphaField            interiorField The field the inner rings, lattice, and strands follow (the silhouette's when filling holes).
 * @property ArtMeshSettings       settings      The settings.
 * @property Int                   cellSize      The working cell size.
 * @property Double                outlineLevel  The outline's level, how far outside the art it runs.
 * @property Double                tolerance     The corner-finding tolerance.
 * @property List<SampledRing>     outline       The outline rings, sampled afresh by every attempt; their arcs are measured once.
 * @property Int                   meshedOverRingCount The traced outline rings left out: holes too small for the outline spacing and any ring inside one.
 * @property List<DoubleArray>     denseInner    The traced inner contours.
 * @property DoubleArray           pins          The pins, Float-rounded.
 * @property PiecewiseLinearGuide? guide         The mesh being replaced, or null.
 */
private class TracedLayer(
	val field: AlphaField,
	val interiorField: AlphaField,
	val settings: ArtMeshSettings,
	val cellSize: Int,
	val outlineLevel: Double,
	val tolerance: Double,
	val outline: List<SampledRing>,
	val meshedOverRingCount: Int,
	val denseInner: List<DoubleArray>,
	val pins: DoubleArray,
	val guide: PiecewiseLinearGuide?,
) {
	/** The number of pins. */
	val pinCount: Int
		get() = pins.size / 2
}

/**
 * Builds a layer's field at the working cell the margins ask for and traces its outline and inner
 * contours, leaving out of the outline the holes too small for the outline spacing.
 *
 * @param AlphaChannel          alpha           The layer's alpha.
 * @param ArtMeshSettings       settings        The settings.
 * @param DoubleArray           pins            The pins, raster px.
 * @param DoubleArray           pinSurroundings The triangles that count as art, raster px.
 * @param PiecewiseLinearGuide? guide           The mesh being replaced, or null.
 * @param MutableList           notices         Receives a notice when the grid had to coarsen.
 * @return TracedLayer? The traced layer, or null when nothing is opaque.
 */
private fun traceLayer(alpha: AlphaChannel, settings: ArtMeshSettings, pins: DoubleArray, pinSurroundings: DoubleArray, guide: PiecewiseLinearGuide?, notices: MutableList<ArtMeshNotice>): TracedLayer? {
	// The working cell follows the margins (contours move by up to about a cell), never the spacing.
	val nearestMargin = if (settings.innerMargin > 0.0) minOf(settings.outerMargin, settings.innerMargin) else settings.outerMargin
	val requestedCellSize = maxOf(1, floor(nearestMargin / 2.0).toInt())
	// Enough padding for the outline level at ANY cell size the grid cap may force: the level is at most
	// outerMargin + minimumMargin + 2 cells, and the contour must stay clear of the border.
	val paddingCells = ceil(settings.outerMargin / requestedCellSize).toInt() + ceil(settings.minimumMargin / requestedCellSize).toInt() + 4
	val field = alpha.alphaField(settings.alphaThreshold, requestedCellSize, paddingCells, pins, seedTriangles = pinSurroundings) ?: return null

	if (field.cellSize > requestedCellSize) {
		notices.add(ArtMeshNotice.WorkingGridCoarsened(requestedCellSize, field.cellSize))
	}

	val cellSize = field.cellSize
	val outlineLevel = maxOf(settings.outerMargin, settings.minimumMargin + 2.0 * cellSize)
	val thinning = cellSize / 4.0
	val tracedOutline = field.isoRings(outlineLevel)
	val outlineRings = if (settings.fillHoles) outermostRings(tracedOutline).map { index -> tracedOutline[index] } else withoutSmallHoles(tracedOutline, settings.outlineSpacing)
	val meshedOverRingCount = if (settings.fillHoles) 0 else tracedOutline.size - outlineRings.size
	val interiorField = if (settings.fillHoles) field.withHolesFilled(outlineLevel) else field
	val denseInner = if (settings.innerMargin > 0.0) interiorField.isoRings(-settings.innerMargin).map { ring -> thinTracedRing(ring, thinning) } else emptyList()
	val roundedPins = DoubleArray(pins.size) { component -> pins[component].toFloat().toDouble() }
	val outline = outlineRings.map { ring -> SampledRing(thinTracedRing(ring, thinning)) }

	return TracedLayer(field, interiorField, settings, cellSize, outlineLevel, maxOf(1.0, cellSize.toDouble()), outline, meshedOverRingCount, denseInner, roundedPins, guide)
}

/**
 * The outline's traced rings without the holes too small for the outline spacing: a hole ring shorter
 * than one spacing around is left out, and with it every ring inside it, so even-odd still reads
 * every other ring the same.  Leaving a hole out only adds transparent pixels to the mesh, so every
 * art pixel stays enclosed; art inside a hole left out (a speck in a pocket) lies inside the ring
 * around the hole, so the mesh covers it too.
 *
 * @param List<DoubleArray> rings   The traced rings at the outline's level, pairwise disjoint.
 * @param Double            spacing The outline spacing.
 * @return List<DoubleArray> The rings kept, in their order.
 */
internal fun withoutSmallHoles(rings: List<DoubleArray>, spacing: Double): List<DoubleArray> {
	val leftOut = BooleanArray(rings.size)

	for ((index, ring) in rings.withIndex()) {
		val measure = RingMeasure.of(ring)

		if (!measure.isHole || measure.perimeter >= spacing) {
			continue
		}

		// Rings at one level never cross, so one vertex tells whether a ring lies inside.
		for ((other, otherRing) in rings.withIndex()) {
			if (other == index || ringContainsPoint(ring, otherRing[0], otherRing[1])) {
				leftOut[other] = true
			}
		}
	}

	return if (leftOut.none { it }) rings else rings.filterIndexed { index, _ -> !leftOut[index] }
}

/**
 * The spacings each attempt tries, coarsened round by round until the placed points fit the budget.
 *
 * Over the budget the interior thins alone, up to the thinning limit times the outline spacing; past
 * that the two widen together at the limit; once the outline can give no more, the interior is left to
 * refinement and only the outline widens.  A mesh that must coarsen keeps a share of the budget back for
 * refinement, but the last round only has to fit the whole budget.
 *
 * @param ArtMeshSettings   settings    The settings.
 * @param List<SampledRing> outline     The outline rings.
 * @param Int               vertexFloor The fewest vertices any attempt can have: each ring's minimum, plus the pins.
 */
private class BudgetSchedule(private val settings: ArtMeshSettings, outline: List<SampledRing>, private val vertexFloor: Int) {
	/** Past this spacing every ring is at its minimum, so widening further changes nothing. */
	private val widestUsefulOutlineSpacing = maxOf(settings.outlineSpacing, outline.maxOfOrNull { ring -> ring.perimeter / settings.minimumOutlinePoints } ?: 0.0)

	/** The limit never asks for an interior denser, against the outline, than the settings themselves. */
	private val thinningLimit = maxOf(settings.interiorThinningLimit, settings.interiorSpacing / settings.outlineSpacing)

	/** The outline spacing the next attempt uses. */
	var outlineSpacing: Double = settings.outlineSpacing
		private set

	/** The interior spacing the next attempt uses; null once the interior is left to refinement. */
	var interiorSpacing: Double? = settings.interiorSpacing
		private set

	/** The rounds of coarsening so far. */
	var round: Int = 0
		private set

	private var target = settings.vertexBudget
	private var previousInteriorCount = Int.MAX_VALUE

	/**
	 * Whether an attempt's points fit: within the target, or on the last round within the whole budget,
	 * so the reserve never turns a mesh that fits into none.
	 *
	 * @param PlacedPoints points The attempt's points.
	 * @return Boolean True when they fit.
	 */
	fun fits(points: PlacedPoints): Boolean = points.vertexCount <= target || (round == BUDGET_ROUNDS && points.vertexCount <= settings.vertexBudget)

	/**
	 * Coarsens the spacings for the next round, after an attempt that did not fit.
	 *
	 * @param PlacedPoints points The attempt's points.
	 * @return Boolean False when no round is left, or the floor fills the whole budget so no widening can help.
	 */
	fun coarsen(points: PlacedPoints): Boolean {
		round++

		if (round > BUDGET_ROUNDS || vertexFloor >= settings.vertexBudget) {
			return false
		}

		// Coarsening is needed, so keep a share of the budget back for refinement, never past the floor.
		target = settings.vertexBudget - minOf(ceil(REFINEMENT_RESERVE_SHARE * settings.vertexBudget).toInt(), settings.vertexBudget - vertexFloor)
		val interiorRoom = target - points.outlineCount
		val interiorCount = points.interiorCount
		val currentInteriorSpacing = interiorSpacing
		// The outline above its floor, which widening shrinks, and the room the target leaves it.
		val reducibleOutline = (points.outlineCount - vertexFloor).toDouble()
		val room = maxOf(1.0, (target - vertexFloor).toDouble())

		// The lattice shrinks with the spacing's square and the inner ring with the spacing, so the square
		// root of the overshoot (plus a little) lands close.
		val thinnedInteriorSpacing = if (currentInteriorSpacing != null && interiorRoom > 0) currentInteriorSpacing * sqrt(interiorCount.toDouble() / interiorRoom) * SPACING_OVERSHOOT else Double.POSITIVE_INFINITY
		val outlineCanWiden = points.outlineCount > vertexFloor && outlineSpacing < widestUsefulOutlineSpacing

		if (currentInteriorSpacing != null && thinnedInteriorSpacing <= thinningLimit * outlineSpacing && interiorCount in 1 until previousInteriorCount) {
			// Thin the interior alone.
			interiorSpacing = thinnedInteriorSpacing
			previousInteriorCount = interiorCount
		} else if (currentInteriorSpacing != null && outlineCanWiden) {
			// At its limit the interior widens with the outline.  The outline above its floor shrinks as
			// 1 / s and the interior, moved to the limit, as 1 / s squared; solving the two for the room
			// left lands close.
			val interiorAtLimit = interiorCount * (currentInteriorSpacing / (thinningLimit * outlineSpacing)).let { ratio -> ratio * ratio }
			val shrink =
				if (interiorAtLimit > 0.0) {
					(-reducibleOutline + sqrt(reducibleOutline * reducibleOutline + 4.0 * interiorAtLimit * room)) / (2.0 * interiorAtLimit)
				} else {
					room / reducibleOutline
				}
			outlineSpacing = minOf(outlineSpacing * maxOf(1.01, 1.02 / shrink), widestUsefulOutlineSpacing)
			interiorSpacing = thinningLimit * outlineSpacing
		} else if (currentInteriorSpacing != null) {
			// The outline and pins fill the budget themselves at their leanest (or the interior stopped
			// thinning with the outline as lean as it gets).
			interiorSpacing = null
		} else {
			// Only the outline above its floor shrinks as it widens, in proportion to the spacing.
			outlineSpacing = minOf(outlineSpacing * reducibleOutline / room * SPACING_OVERSHOOT, widestUsefulOutlineSpacing)
		}

		return true
	}
}

/**
 * A result with no mesh because the vertices would not fit the budget.
 *
 * @param MutableList        notices     The notices so far; the over-budget notice is added.
 * @param Int                vertexCount The vertices the mesh would need.
 * @param Int                budget      The vertex budget.
 * @param ArtMeshStatistics? statistics  The attempt's statistics, when it got as far as a mesh.
 * @return ArtMeshResult The result.
 */
private fun overBudget(notices: MutableList<ArtMeshNotice>, vertexCount: Int, budget: Int, statistics: ArtMeshStatistics?): ArtMeshResult {
	notices.add(ArtMeshNotice.OverBudget(vertexCount, budget))

	return ArtMeshResult(null, notices, statistics)
}

/**
 * Every point one attempt places, before triangulation.
 *
 * @property List<SampledRing>   outline    The outline rings.
 * @property List<InnerPolyline> innerRings The inner rings' points, whole or cut by strands.
 * @property DoubleArray         lattice    The lattice points.
 * @property DoubleArray         pins       The pins.
 * @property StrandPlacement     strands    The strands' rungs and centerline vertices.
 */
private class PlacedPoints(val outline: List<SampledRing>, val innerRings: List<InnerPolyline>, val lattice: DoubleArray, val pins: DoubleArray, val strands: StrandPlacement) {
	/** The outline's, the strands' centerline, and the pins' points: what the interior cannot give way for. */
	val outlineCount: Int
		get() = outline.sumOf { ring -> ring.sampleCount } + strands.centers.size / 2 + pins.size / 2

	/** The inner rings' and the lattice's points. */
	val interiorCount: Int
		get() = innerRings.sumOf { ring -> ring.pointCount } + lattice.size / 2

	/** The vertex count the mesh would have (an upper bound; aliasing and unused points only lower it). */
	val vertexCount: Int
		get() = outlineCount + interiorCount
}

/**
 * Places the outline, inner rings, and lattice at one pair of spacings.
 *
 * @param TracedLayer layer           The traced layer; its outline rings are sampled afresh here.
 * @param StrandPlan  strandPlan      The outline's thin strands.
 * @param Double      outlineSpacing  The outline spacing.
 * @param Double?     interiorSpacing The interior spacing, or null to place no interior points.
 * @return PlacedPoints The points.
 */
private fun placePoints(layer: TracedLayer, strandPlan: StrandPlan, outlineSpacing: Double, interiorSpacing: Double?): PlacedPoints {
	val settings = layer.settings
	val coversArt = coversArtCheckOf(layer.field, settings.minimumMargin)
	// The strands' rungs pair the outline's samples across them, so they are placed first.
	val strands = placeStrands(strandPlan, layer.outline, outlineSpacing, layer.interiorField, settings, layer.cellSize, layer.pins)

	for ((ringIndex, ring) in layer.outline.withIndex()) {
		ring.sampleEvenly(outlineSpacing, layer.tolerance, settings.minimumOutlinePoints, strands.anchorRunsByRing[ringIndex])
		ring.refine(coversArt)
	}

	if (interiorSpacing == null) {
		return PlacedPoints(layer.outline, emptyList(), DoubleArray(0), layer.pins, strands)
	}

	val innerRings = pruneInsideStrands(innerRingPoints(layer.denseInner, layer.interiorField, interiorSpacing, settings.innerMargin, layer.tolerance, layer.pins), strands)
	val lattice = latticePoints(layer.interiorField, interiorSpacing, settings.innerMargin, layer.pins).let { all -> if (strands.quads.isEmpty()) all else withoutStrandPoints(all, strands) }

	return PlacedPoints(layer.outline, innerRings, lattice, layer.pins, strands)
}

/**
 * Points with those inside a strand taken out.
 *
 * @param DoubleArray     points  The points, x0, y0, ...
 * @param StrandPlacement strands Where the strands lie.
 * @return DoubleArray The points outside every strand.
 */
private fun withoutStrandPoints(points: DoubleArray, strands: StrandPlacement): DoubleArray {
	val kept = ArrayList<Double>()

	for (point in 0 until points.size / 2) {
		if (!strands.contains(points[2 * point], points[2 * point + 1])) {
			kept.add(points[2 * point])
			kept.add(points[2 * point + 1])
		}
	}

	return kept.toDoubleArray()
}

/**
 * Assembles the placed points into the final mesh and result.
 *
 * @param TracedLayer  layer           The traced layer.
 * @param PlacedPoints points          The points.
 * @param MutableList  notices         The notices so far.
 * @param Double       outlineSpacing  The outline spacing used.
 * @param Double?      interiorSpacing The interior spacing used, or null when the interior was left to refinement.
 * @return ArtMeshResult The result.
 */
private fun assemble(layer: TracedLayer, points: PlacedPoints, notices: MutableList<ArtMeshNotice>, outlineSpacing: Double, interiorSpacing: Double?): ArtMeshResult {
	val refinedChordCount = points.outline.sumOf { ring -> ring.refinedChordCount }
	val assembled = assembleMesh(points.outline, points.innerRings, points.lattice + points.strands.centers, points.pins, layer.field, layer.settings, layer.cellSize, layer.guide)
	addAssemblyNotices(assembled, notices)
	val statistics = statisticsOf(layer, points, assembled, refinedChordCount, outlineSpacing, interiorSpacing)
	val mesh = assembled.mesh

	if (mesh == null) {
		notices.add(ArtMeshNotice.StructureCheckFailed(assembled.failure ?: "unknown"))

		return ArtMeshResult(null, notices, statistics)
	}

	// Untangling crossing rings adds traced points; the budget still holds the final word.
	if (mesh.vertexCount > layer.settings.vertexBudget) {
		return overBudget(notices, mesh.vertexCount, layer.settings.vertexBudget, statistics)
	}

	return ArtMeshResult(mesh, notices, statistics)
}

/**
 * Notes what assembly adjusted: densified chords, dropped inner edges, shared pins, and what refinement
 * and the guide could not reach.
 *
 * @param AssembledMesh assembled The assembly.
 * @param MutableList   notices   Receives the notices.
 */
private fun addAssemblyNotices(assembled: AssembledMesh, notices: MutableList<ArtMeshNotice>) {
	val refinement = assembled.refinement

	if (assembled.densifiedChordCount > 0) {
		notices.add(ArtMeshNotice.OutlineDensified(assembled.densifiedChordCount))
	}

	if (assembled.droppedInnerEdgeCount > 0) {
		notices.add(ArtMeshNotice.InnerRingEdgesDropped(assembled.droppedInnerEdgeCount))
	}

	if (assembled.sharedPinCount > 0) {
		notices.add(ArtMeshNotice.PinsShareVertex(assembled.sharedPinCount))
	}

	if (refinement != null && refinement.guideStoppedByCap) {
		notices.add(ArtMeshNotice.DeformationToleranceUnmet(refinement.guideRemainingError))
	}

	if (refinement != null && refinement.stoppedByCap && refinement.underTargetTriangleCount > 0) {
		notices.add(ArtMeshNotice.RefinementBudgetReached(refinement.underTargetTriangleCount))
	}
}

/**
 * What one mesh was made of.
 *
 * @param TracedLayer   layer             The traced layer.
 * @param PlacedPoints  points            The points.
 * @param AssembledMesh assembled         The assembly.
 * @param Int           refinedChordCount The outline chords split by refinement against the art.
 * @param Double        outlineSpacing    The outline spacing used.
 * @param Double?       interiorSpacing   The interior spacing used, or null.
 * @return ArtMeshStatistics The statistics.
 */
private fun statisticsOf(layer: TracedLayer, points: PlacedPoints, assembled: AssembledMesh, refinedChordCount: Int, outlineSpacing: Double, interiorSpacing: Double?): ArtMeshStatistics {
	val refinement = assembled.refinement

	return ArtMeshStatistics(
		cellSize = layer.cellSize,
		outlineLevel = layer.outlineLevel,
		outlineSpacing = outlineSpacing,
		interiorSpacing = interiorSpacing,
		outlineRingCount = points.outline.size,
		meshedOverRingCount = layer.meshedOverRingCount,
		outlineVertexCount = assembled.outlineVertexCount,
		refinedChordCount = refinedChordCount,
		innerRingCount = points.innerRings.size,
		innerVertexCount = points.innerRings.sumOf { ring -> ring.pointCount },
		latticeVertexCount = points.lattice.size / 2,
		pinCount = layer.pinCount,
		steinerVertexCount = refinement?.addedPointCount ?: 0,
		boundarySplitCount = refinement?.boundarySplitCount ?: 0,
		underTargetCount = refinement?.underTargetTriangleCount ?: 0,
		unfixableCount = refinement?.unfixableTriangleCount ?: 0,
		guideVertexCount = refinement?.guidePointCount ?: 0,
		strandCount = points.strands.strandCount,
		rungCount = points.strands.rungCount,
		strandCenterCount = points.strands.centers.size / 2,
	)
}