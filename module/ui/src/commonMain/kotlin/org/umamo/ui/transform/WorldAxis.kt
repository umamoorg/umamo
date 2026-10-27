package org.umamo.ui.transform

/** A world axis a position is typed on, measured as the viewport draws it: x right, z up. */
internal enum class WorldAxis {
	/** World x, across. */
	X,

	/** World z, up. */
	Z,
}

/**
 * The world point ([worldX], [worldZ]) with this axis's coordinate set to [worldValue] and the other passed
 * through untouched, so a value typed on one axis never nudges the other.
 *
 * @param Float worldValue The new coordinate on this axis.
 * @param Float worldX The point's current world x.
 * @param Float worldZ The point's current world z.
 * @return Pair<Float, Float> The point's new world x and z.
 */
internal fun WorldAxis.moved(worldValue: Float, worldX: Float, worldZ: Float): Pair<Float, Float> =
	when (this) {
		WorldAxis.X -> worldValue to worldZ
		WorldAxis.Z -> worldX to worldValue
	}