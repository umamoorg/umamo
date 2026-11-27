package org.umamo.interop.art.mesh

/**
 * What went into one generated mesh, for diagnostics and the CLI.
 *
 * @property Int    cellSize            The working grid's cell size, pixels.
 * @property Double outlineLevel        How far outside the art the outline was traced, pixels.
 * @property Double outlineSpacing      The outline spacing used, pixels.
 * @property Double? interiorSpacing    The interior spacing used, pixels, or null when the vertex budget left the interior to refinement.
 * @property Int    outlineRingCount    The number of outline rings (one per island, plus one per hole cut out).
 * @property Int    meshedOverRingCount The traced outline rings left out: holes too small for the outline spacing, meshed over, and any ring inside one (0 when holes are filled).
 * @property Int    outlineVertexCount  The outline's vertices.
 * @property Int    refinedChordCount   Outline chords split because they came too close to the art or cut it off.
 * @property Int    innerRingCount      The number of inner rings.
 * @property Int    innerVertexCount    The inner rings' vertices.
 * @property Int    latticeVertexCount  The interior lattice's vertices.
 * @property Int    pinCount            The pins given.
 * @property Int    steinerVertexCount  Vertices refinement added toward the minimum angle: the mesh's last vertices.
 * @property Int    boundarySplitCount  Of those, the ones that split an outline chord.
 * @property Int    underTargetCount    Triangles left below the minimum angle (0 when there is none).
 * @property Int    unfixableCount      Of those, the ones refinement could not help: an angle the outline itself makes, or one past a floor.
 * @property Int    guideVertexCount    Of the added vertices, the old ones kept to hold the rig being replaced.
 * @property Int    strandCount         Thin strands meshed as rung strips.
 * @property Int    rungCount           Their rungs: pairs of outline vertices facing each other across a strand.
 * @property Int    strandCenterCount   Their centerline vertices, one per rung where the strand is wide enough.
 */
public data class ArtMeshStatistics(
	val cellSize: Int,
	val outlineLevel: Double,
	val outlineSpacing: Double,
	val interiorSpacing: Double?,
	val outlineRingCount: Int,
	val meshedOverRingCount: Int,
	val outlineVertexCount: Int,
	val refinedChordCount: Int,
	val innerRingCount: Int,
	val innerVertexCount: Int,
	val latticeVertexCount: Int,
	val pinCount: Int,
	val steinerVertexCount: Int,
	val boundarySplitCount: Int,
	val underTargetCount: Int,
	val unfixableCount: Int,
	val guideVertexCount: Int,
	val strandCount: Int = 0,
	val rungCount: Int = 0,
	val strandCenterCount: Int = 0,
)