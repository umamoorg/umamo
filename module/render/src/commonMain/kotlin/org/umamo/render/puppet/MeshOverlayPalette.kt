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
 * The nine colors the mesh overlay draws with: each domain's idle, selected, and active color.  The
 * active face color colors the active face's DOT only; a fill never uses it, the active face fills as
 * selected.  The viewport.meshEdit settings also carry an off-key color per domain, which nothing draws,
 * so it has no place here.
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
) {
	companion object {
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
			)
	}
}