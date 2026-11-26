package org.umamo.render.puppet

/**
 * One overlay color as STRAIGHT (non-premultiplied) sRGB components in 0..1.  Plain floats so this
 * module stays UI-free, the GridColors reason; the overlay fragment shader premultiplies, since the
 * frame is premultiplied.
 *
 * @property Float red The red component.
 * @property Float green The green component.
 * @property Float blue The blue component.
 * @property Float alpha The alpha.
 */
data class OverlayColor(
	val red: Float,
	val green: Float,
	val blue: Float,
	val alpha: Float,
) {
	companion object {
		/**
		 * The color behind a packed AARRGGBB value, the settings' hex form.
		 *
		 * @param Long argb The packed value.
		 * @return OverlayColor The color.
		 */
		fun fromArgb(argb: Long): OverlayColor =
			OverlayColor(
				red = ((argb shr 16) and 0xFF).toFloat() / 255f,
				green = ((argb shr 8) and 0xFF).toFloat() / 255f,
				blue = (argb and 0xFF).toFloat() / 255f,
				alpha = ((argb shr 24) and 0xFF).toFloat() / 255f,
			)
	}
}

/**
 * The colors the mesh overlay draws with: each domain's idle, selected, and active color, the two
 * placement colors a UV area's islands outline in, the three a warp's lattice draws in by its role, and the
 * one a pending lattice draws in whatever its role.  The active face color colors the active face's DOT
 * only; a fill never uses it, the active face fills as selected.
 *
 * @property OverlayColor vertexIdle An unselected vertex dot.
 * @property OverlayColor vertexSelected A selected vertex dot.
 * @property OverlayColor vertexActive The active vertex dot.
 * @property OverlayColor edgeIdle An unselected edge.
 * @property OverlayColor edgeSelected A selected edge.
 * @property OverlayColor edgeActive The active edge.
 * @property OverlayColor faceIdle An unselected face fill, and an unselected face dot with alpha forced to 1.
 * @property OverlayColor faceSelected A selected face fill, and a selected face dot with alpha forced to 1.
 * @property OverlayColor faceActive The active face dot, with alpha forced to 1.
 * @property OverlayColor warning The outline of an island whose tile is in a placement collision.
 * @property OverlayColor pinnedPlacement The outline of an island whose tile is pinned.
 * @property OverlayColor latticeIdle A warp lattice shown only because its area shows every deformer: its outer edges
 *   and its corner dots, translucent by default so a rig full of them stays readable.
 * @property OverlayColor latticeSelected A selected warp's lattice.
 * @property OverlayColor latticeActive The active warp's lattice.
 * @property OverlayColor latticePending A warp lattice shaped in Pose mode and not yet keyed, whatever its role: its
 *   grid lines and dots in the lattice layer, and its unselected grid lines in the Edit cage.
 * @property OverlayColor latticeHover The lattice the pointer is over, whatever its role or mark, drawn at this
 *   color's own alpha whatever the area's deformer opacity: what a click there takes.
 * @property Float backFacingOpacity What the Edit cage's back-facing primitives' alphas are multiplied by, 0 to 1, in
 *   an area that dims them ([org.umamo.render.FrameOverlays.dimBackFacing]).
 */
data class MeshOverlayPalette(
	val vertexIdle: OverlayColor,
	val vertexSelected: OverlayColor,
	val vertexActive: OverlayColor,
	val edgeIdle: OverlayColor,
	val edgeSelected: OverlayColor,
	val edgeActive: OverlayColor,
	val faceIdle: OverlayColor,
	val faceSelected: OverlayColor,
	val faceActive: OverlayColor,
	val warning: OverlayColor,
	val pinnedPlacement: OverlayColor,
	val latticeIdle: OverlayColor,
	val latticeSelected: OverlayColor,
	val latticeActive: OverlayColor,
	val latticePending: OverlayColor,
	val latticeHover: OverlayColor,
	val backFacingOpacity: Float = DEFAULT_BACK_FACING_OPACITY,
) {
	companion object {
		/** The back-facing opacity the settings default to. */
		const val DEFAULT_BACK_FACING_OPACITY: Float = 0.3f

		/** The settings defaults, so a renderer draws something sensible before the UI pushes a palette. */
		val Classic: MeshOverlayPalette =
			MeshOverlayPalette(
				vertexIdle = OverlayColor.fromArgb(0xFFFF00ECL),
				vertexSelected = OverlayColor.fromArgb(0xFFFF7A00L),
				vertexActive = OverlayColor.fromArgb(0xFF7DE400L),
				edgeIdle = OverlayColor.fromArgb(0x99000000L),
				edgeSelected = OverlayColor.fromArgb(0xFFFF7A00L),
				edgeActive = OverlayColor.fromArgb(0xFF7DE400L),
				faceIdle = OverlayColor.fromArgb(0x22000000L),
				faceSelected = OverlayColor.fromArgb(0x66FF7A00L),
				faceActive = OverlayColor.fromArgb(0xFF7DE400L),
				warning = OverlayColor.fromArgb(0xFFFF5A5AL),
				pinnedPlacement = OverlayColor.fromArgb(0xFFB266FFL),
				latticeIdle = OverlayColor.fromArgb(0x80FFFFFFL),
				latticeSelected = OverlayColor.fromArgb(0xFF338CFFL),
				latticeActive = OverlayColor.fromArgb(0xFF7DE400L),
				latticePending = OverlayColor.fromArgb(0xFFDF8431L),
				latticeHover = OverlayColor.fromArgb(0xFFFF00ECL),
			)
	}
}