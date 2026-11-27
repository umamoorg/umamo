package org.umamo.interop.art.mesh

/**
 * The art mesher's ready-made settings, by how densely a part should be meshed.  Every preset uses
 * the default alpha threshold and vertex budget; the numbers are starting points, tuned against the
 * test corpus's artwork.
 *
 * @property String          key      The stable name settings and the strip store the preset by.
 * @property ArtMeshSettings settings The settings the preset stands for.
 */
public enum class ArtMeshPreset(public val key: String, public val settings: ArtMeshSettings) {
	/** The everyday density: parts that bend a moderate amount. */
	Standard(
		"standard",
		ArtMeshSettings(
			alphaThreshold = DEFAULT_ART_MESH_ALPHA_THRESHOLD,
			outlineSpacing = 32.0,
			interiorSpacing = 48.0,
			outerMargin = 4.0,
			innerMargin = 8.0,
			minimumMargin = 2.0,
			minimumOutlinePoints = 6,
			minimumAngle = 20.0,
			strandWidth = 40.0,
		),
	),

	/** Denser: parts that bend a lot (hair, mouths, cloth). */
	Fine(
		"fine",
		ArtMeshSettings(
			alphaThreshold = DEFAULT_ART_MESH_ALPHA_THRESHOLD,
			outlineSpacing = 20.0,
			interiorSpacing = 28.0,
			outerMargin = 3.0,
			innerMargin = 6.0,
			minimumMargin = 1.5,
			minimumOutlinePoints = 8,
			minimumAngle = 24.0,
			strandWidth = 26.0,
		),
	),

	/** Sparser: parts that barely bend (accessories, rigid props). */
	Coarse(
		"coarse",
		ArtMeshSettings(
			alphaThreshold = DEFAULT_ART_MESH_ALPHA_THRESHOLD,
			outlineSpacing = 48.0,
			interiorSpacing = 72.0,
			outerMargin = 6.0,
			innerMargin = 12.0,
			minimumMargin = 3.0,
			minimumOutlinePoints = 4,
			minimumAngle = 15.0,
			strandWidth = 60.0,
		),
	),
	;

	public companion object {
		/** The preset a first run starts from when nothing else says otherwise. */
		public val Default: ArtMeshPreset = Standard

		/**
		 * The preset stored under a key, or the default for an unknown or missing key.
		 *
		 * @param String? key The stored key.
		 * @return ArtMeshPreset The preset.
		 */
		public fun fromKey(key: String?): ArtMeshPreset = entries.firstOrNull { preset -> preset.key == key } ?: Default
	}
}