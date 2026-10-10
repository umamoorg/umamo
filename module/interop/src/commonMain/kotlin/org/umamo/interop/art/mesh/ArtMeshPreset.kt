package org.umamo.interop.art.mesh

/**
 * The art mesher's ready-made settings, by how densely a part should be meshed.  Every preset uses
 * the default alpha threshold and vertex budget; the numbers are starting points, tuned against the
 * test corpus's artwork.
 *
 * @property ArtMeshSettings settings The settings the preset stands for.
 */
public enum class ArtMeshPreset(public val settings: ArtMeshSettings) {
	/** The everyday density: parts that bend a moderate amount. */
	Standard(
		ArtMeshSettings(
			alphaThreshold = DEFAULT_ART_MESH_ALPHA_THRESHOLD,
			outlineSpacing = 32.0,
			interiorSpacing = 48.0,
			outerMargin = 4.0,
			innerMargin = 8.0,
			minimumMargin = 2.0,
			minimumOutlinePoints = 6,
		),
	),

	/** Denser: parts that bend a lot (hair, mouths, cloth). */
	Fine(
		ArtMeshSettings(
			alphaThreshold = DEFAULT_ART_MESH_ALPHA_THRESHOLD,
			outlineSpacing = 20.0,
			interiorSpacing = 28.0,
			outerMargin = 3.0,
			innerMargin = 6.0,
			minimumMargin = 1.5,
			minimumOutlinePoints = 8,
		),
	),

	/** Sparser: parts that barely bend (accessories, rigid props). */
	Coarse(
		ArtMeshSettings(
			alphaThreshold = DEFAULT_ART_MESH_ALPHA_THRESHOLD,
			outlineSpacing = 48.0,
			interiorSpacing = 72.0,
			outerMargin = 6.0,
			innerMargin = 12.0,
			minimumMargin = 3.0,
			minimumOutlinePoints = 4,
		),
	),
}