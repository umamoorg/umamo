package org.umamo.interop.art.mesh

/** The art mesher's default alpha threshold: fainter pixels (stray eraser residue) are not meshed. */
public const val DEFAULT_ART_MESH_ALPHA_THRESHOLD: Int = 16

/** The art mesher's default vertex cap per mesh: what every renderer target can hold. */
public const val DEFAULT_ART_MESH_VERTEX_BUDGET: Int = 1024

/**
 * How the art mesher spaces and places its vertices.  Every distance is in art pixels (the layer
 * raster's own pixels), the same for every document.
 *
 * Pixels at or above [alphaThreshold] are guaranteed covered: the outline keeps at least
 * [minimumMargin] from every such pixel, and runs [outerMargin] outside the art where the shape
 * allows.  A second, inner ring runs [innerMargin] inside the art's edge (none when zero), and a
 * hexagonal lattice fills the rest.  With [fillHoles] the mesh covers the art's silhouette instead:
 * holes in the art (a lace pattern, a spider web) are meshed over rather than cut out, so the part
 * deforms as one sheet, and the inner ring and lattice run across them.
 *
 * @property Int     alphaThreshold       The minimum alpha byte counted as art (1..255).
 * @property Double  outlineSpacing       The target distance between outline vertices.
 * @property Double  interiorSpacing      The target distance between inner-ring and lattice vertices.
 * @property Double  outerMargin          How far outside the art the outline runs.
 * @property Double  innerMargin          How far inside the art's edge the inner ring runs; 0 for none.
 * @property Double  minimumMargin        The least distance the outline may come to any art pixel (at least 0.5).
 * @property Int     minimumOutlinePoints The fewest vertices one outline ring may have (at least 3).
 * @property Int     vertexBudget         The most vertices one mesh may have.
 * @property Boolean fillHoles            Whether to mesh over the art's holes instead of cutting them out.
 */
public data class ArtMeshSettings(
	val alphaThreshold: Int,
	val outlineSpacing: Double,
	val interiorSpacing: Double,
	val outerMargin: Double,
	val innerMargin: Double,
	val minimumMargin: Double,
	val minimumOutlinePoints: Int,
	val vertexBudget: Int = DEFAULT_ART_MESH_VERTEX_BUDGET,
	val fillHoles: Boolean = false,
) {
	init {
		require(alphaThreshold in 1..255) { "alphaThreshold must be in 1..255: $alphaThreshold" }
		require(outlineSpacing > 0.0 && outlineSpacing.isFinite()) { "outlineSpacing must be positive and finite: $outlineSpacing" }
		require(interiorSpacing > 0.0 && interiorSpacing.isFinite()) { "interiorSpacing must be positive and finite: $interiorSpacing" }
		require(outerMargin >= 0.0 && outerMargin.isFinite()) { "outerMargin must be non-negative and finite: $outerMargin" }
		require(innerMargin >= 0.0 && innerMargin.isFinite()) { "innerMargin must be non-negative and finite: $innerMargin" }
		// A zero margin would make the clearance check vacuous: "at least zero from every pixel".
		require(minimumMargin >= 0.5 && minimumMargin.isFinite()) { "minimumMargin must be at least 0.5 and finite: $minimumMargin" }
		require(minimumOutlinePoints >= 3) { "minimumOutlinePoints must be at least 3: $minimumOutlinePoints" }
		require(vertexBudget >= minimumOutlinePoints) { "vertexBudget must hold at least one outline ring: $vertexBudget" }
	}
}