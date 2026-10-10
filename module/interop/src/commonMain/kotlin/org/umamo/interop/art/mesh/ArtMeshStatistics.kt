package org.umamo.interop.art.mesh

/**
 * What went into one generated mesh, for diagnostics and the CLI.
 *
 * @property Int    cellSize            The working grid's cell size, pixels.
 * @property Double outlineLevel        How far outside the art the outline was traced, pixels.
 * @property Double outlineSpacing      The outline spacing used, pixels.
 * @property Double interiorSpacing     The interior spacing used, pixels.
 * @property Int    outlineRingCount    The number of outline rings (one per island, plus one per hole).
 * @property Int    outlineVertexCount  The outline's vertices.
 * @property Int    refinedChordCount   Outline chords split because they came too close to the art or cut it off.
 * @property Int    innerRingCount      The number of inner rings.
 * @property Int    innerVertexCount    The inner rings' vertices.
 * @property Int    latticeVertexCount  The interior lattice's vertices.
 * @property Int    pinCount            The pins given.
 */
public data class ArtMeshStatistics(
	val cellSize: Int,
	val outlineLevel: Double,
	val outlineSpacing: Double,
	val interiorSpacing: Double,
	val outlineRingCount: Int,
	val outlineVertexCount: Int,
	val refinedChordCount: Int,
	val innerRingCount: Int,
	val innerVertexCount: Int,
	val latticeVertexCount: Int,
	val pinCount: Int,
)