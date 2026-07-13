package org.umamo.format.moc3.model

/**
 * A deformer (warp or rotation). Deformers carry no IDs in moc3 - they are referenced by index
 * (their position in [org.umamo.format.moc3.MocDocument.deformers]); [parentDeformerIndex] is -1
 * for a root deformer.
 */
public sealed class Deformer {
	public abstract val parentDeformerIndex: Int
	public abstract val keyformBindingIndex: Int
}

/** One keyform of a [WarpDeformer]: the deformed control-point grid plus opacity/color. */
public data class WarpKeyform(
	/** Control points, `(columns+1)·(rows+1)` points as interleaved `x,y`. */
	val controlPoints: FloatArray,
	val opacity: Float,
	val multiplyColor: Rgb?,
	val screenColor: Rgb?,
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is WarpKeyform &&
					controlPoints.contentEquals(other.controlPoints) &&
					opacity == other.opacity &&
					multiplyColor == other.multiplyColor &&
					screenColor == other.screenColor
			)

	override fun hashCode(): Int {
		var hash = controlPoints.contentHashCode()
		hash = 31 * hash + opacity.hashCode()
		hash = 31 * hash + (multiplyColor?.hashCode() ?: 0)
		hash = 31 * hash + (screenColor?.hashCode() ?: 0)
		return hash
	}
}

/** A warp (grid) deformer: a `(columns+1)·(rows+1)` control-point lattice keyed over parameters. */
public data class WarpDeformer(
	override val parentDeformerIndex: Int,
	override val keyformBindingIndex: Int,
	val rows: Int,
	val columns: Int,
	/** Interpolation mode (0 = triangle-split, non-zero = bilinear); 0 on moc < 3. */
	val mode: Int,
	val keyforms: List<WarpKeyform>,
) : Deformer()

/** One keyform of a [RotationDeformer]: the local affine plus opacity/color. */
public data class RotationKeyform(
	val originX: Float,
	val originY: Float,
	/** Angle delta in degrees (added to [RotationDeformer.baseAngle]). */
	val angle: Float,
	val scale: Float,
	val reflectX: Boolean,
	val reflectY: Boolean,
	val opacity: Float,
	val multiplyColor: Rgb?,
	val screenColor: Rgb?,
)

/** A rotation deformer: an origin/angle/scale affine keyed over parameters, plus a static base angle. */
public data class RotationDeformer(
	override val parentDeformerIndex: Int,
	override val keyformBindingIndex: Int,
	/** Static base angle in degrees, added to each keyform's [RotationKeyform.angle]. */
	val baseAngle: Float,
	val keyforms: List<RotationKeyform>,
) : Deformer()
