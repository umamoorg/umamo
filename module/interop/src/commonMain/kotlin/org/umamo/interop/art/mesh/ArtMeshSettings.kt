package org.umamo.interop.art.mesh

/** The art mesher's default alpha threshold: fainter pixels (stray eraser residue) are not meshed. */
public const val DEFAULT_ART_MESH_ALPHA_THRESHOLD: Int = 16

/** The art mesher's default vertex cap per mesh: what every renderer target can hold. */
public const val DEFAULT_ART_MESH_VERTEX_BUDGET: Int = 1024

/** The largest vertex budget a mesh is ever fitted to: a delta texture this tall draws on any desktop GPU and most tablets. */
public const val MAXIMUM_ART_MESH_VERTEX_BUDGET: Int = 4096

/** The art mesher's default deformation tolerance: how far, in art pixels, a regenerated mesh may stray from the rig it replaces. */
public const val DEFAULT_ART_MESH_DEFORMATION_TOLERANCE: Double = 0.5

/** The smallest deformation tolerance the art mesher takes, in art pixels. */
public const val MINIMUM_ART_MESH_DEFORMATION_TOLERANCE: Double = 0.1

/** The largest minimum angle the art mesher refines toward: past about 33 degrees refinement floods a mesh with vertices. */
public const val MAXIMUM_ART_MESH_MINIMUM_ANGLE: Double = 33.0

/** The art mesher's default interior thinning limit: an interior thinned for the budget stays within four outline spacings. */
public const val DEFAULT_ART_MESH_INTERIOR_THINNING_LIMIT: Double = 4.0

/** The smallest interior thinning limit: the interior never runs sparser than the outline's spacing allows below it. */
public const val MINIMUM_ART_MESH_INTERIOR_THINNING_LIMIT: Double = 1.0

/**
 * How the art mesher spaces and places its vertices.  Every distance is in art pixels (the layer
 * raster's own pixels), the same for every document.
 *
 * Pixels at or above [alphaThreshold] are guaranteed covered: the outline keeps at least
 * [minimumMargin] from every such pixel, and runs [outerMargin] outside the art where the shape
 * allows.  A second, inner ring runs [innerMargin] inside the art's edge (none when zero), and a
 * hexagonal lattice fills the rest.  With [fillHoles] the mesh covers the art's silhouette instead:
 * holes in the art (a lace pattern, a spider web) are meshed over rather than cut out, so the part
 * deforms as one sheet, and the inner ring and lattice run across them.  With a [minimumAngle], vertices
 * are then added beside every triangle whose narrowest corner falls short of it, never moving one
 * already placed, until no such triangle is left that a vertex can fix or the vertex budget runs out;
 * corners the outline itself makes (a hair tip's) are left as they are.  When the mesh replaces
 * one a rig is built on, the old vertices where that rig bends are kept until the new mesh follows it to
 * within [deformationTolerance].  Over the [vertexBudget] the interior thins first, to at most
 * [interiorThinningLimit] times the outline spacing, and past that the outline widens with it.  With
 * [strandRungs], art narrower than [strandWidth] (a hair lock, a strap, a line of ink) is meshed as a
 * strip of rungs, its outline paired straight across it; the presets put that width where an inner
 * ring stops fitting, twice the inner margin plus half the interior spacing.
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
 * @property Double  minimumAngle         The narrowest corner, in degrees, a triangle may have: added vertices break up any thinner one; 0 adds none.
 * @property Double  deformationTolerance How far, in art pixels, the new mesh may stray from a rig it replaces before old vertices are kept to hold it.
 * @property Double  interiorThinningLimit Over the budget, the most the interior spacing grows to, in outline spacings, before the outline widens with it (never below the two spacings' own ratio; at least 1).
 * @property Boolean strandRungs          Whether thin strands are meshed as rung strips rather than from their outline alone.
 * @property Double  strandWidth          The art width, in art pixels, below which a stretch of art is a strand.  Always given: a value derived from the margins would go stale under copy.
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
	val minimumAngle: Double = 0.0,
	val deformationTolerance: Double = DEFAULT_ART_MESH_DEFORMATION_TOLERANCE,
	val interiorThinningLimit: Double = DEFAULT_ART_MESH_INTERIOR_THINNING_LIMIT,
	val strandRungs: Boolean = true,
	val strandWidth: Double,
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
		require(minimumAngle in 0.0..MAXIMUM_ART_MESH_MINIMUM_ANGLE) { "minimumAngle must be in 0..$MAXIMUM_ART_MESH_MINIMUM_ANGLE degrees: $minimumAngle" }
		require(deformationTolerance >= MINIMUM_ART_MESH_DEFORMATION_TOLERANCE && deformationTolerance.isFinite()) { "deformationTolerance must be at least $MINIMUM_ART_MESH_DEFORMATION_TOLERANCE and finite: $deformationTolerance" }
		require(interiorThinningLimit >= MINIMUM_ART_MESH_INTERIOR_THINNING_LIMIT && interiorThinningLimit.isFinite()) { "interiorThinningLimit must be at least $MINIMUM_ART_MESH_INTERIOR_THINNING_LIMIT and finite: $interiorThinningLimit" }
		require(strandWidth > 0.0 && strandWidth.isFinite()) { "strandWidth must be positive and finite: $strandWidth" }
	}
}