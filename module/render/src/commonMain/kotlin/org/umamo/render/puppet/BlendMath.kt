package org.umamo.render.puppet

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The layer-composite reference math - the single spec the composite fragment shader
 * (glsl/CompositeShaders.kt) and the analytic GL tests both follow.  Sources are the PUBLIC
 * standard formulas only: W3C Compositing and Blending Level 1 for the separable and
 * non-separable (Hue/Color) blend functions, Porter-Duff for Over/Atop/Out, and the X Render
 * extension's conjoint/disjoint over variants.  Cubism's own `_TSL`/`_R2` math is NOT derived
 * from the official SDK - the standard-formula reading has been checked against the official
 * editor's rendering of Model A's authored combinations (NORMAL / MULTIPLY_R2 composites; HSL_COLOR /
 * ADD_R2 (Glow) / HARDLIGHT / MULTIPLY_R2 drawable blends; OVER / ATOP / OUT / DISJOINT alphas) and
 * matches visually.  Combinations the corpus does not yet exercise with real art (the remaining
 * `_TSL` variants, Conjoint) are unconfirmed against the editor.
 *
 * Everything operates on PREMULTIPLIED RGBA (the renderer's framebuffer convention).  The three
 * legacy modes under Over alpha replicate the fixed-function equations of GlFrameEncoder.applyBlend
 * EXACTLY (that path is pinned by a shader==fixed-function GL test); every other combination takes
 * the generic unpremultiply -> blend -> Porter-Duff path.
 */

/**
 * The composite shader's colorMode int for a blend mode - the MOC3 packed encoding's color half
 * (editor-dropdown order, legacy first; MUST agree with BlendModeMapping.colorBlendOfPacked).
 *
 * @param BlendMode blendMode The color blend mode.
 * @return Int The shader's colorMode uniform value (0-17).
 */
public fun packedColorModeOf(blendMode: BlendMode): Int =
	when (blendMode) {
		BlendMode.Normal -> 0
		BlendMode.Additive -> 1
		BlendMode.Multiply -> 2
		BlendMode.AdditiveModern -> 3
		BlendMode.AdditiveGlow -> 4
		BlendMode.Darken -> 5
		BlendMode.MultiplyModern -> 6
		BlendMode.ColorBurn -> 7
		BlendMode.LinearBurn -> 8
		BlendMode.Lighten -> 9
		BlendMode.Screen -> 10
		BlendMode.ColorDodge -> 11
		BlendMode.Overlay -> 12
		BlendMode.SoftLight -> 13
		BlendMode.HardLight -> 14
		BlendMode.LinearLight -> 15
		BlendMode.Hue -> 16
		BlendMode.Color -> 17
	}

/**
 * The composite shader's alphaMode int for an alpha blend mode - the MOC3 packed encoding's alpha
 * half (dropdown order; MUST agree with BlendModeMapping.alphaBlendOfPacked).
 *
 * @param AlphaBlendMode alphaBlendMode The alpha blend mode.
 * @return Int The shader's alphaMode uniform value (0-4).
 */
public fun packedAlphaModeOf(alphaBlendMode: AlphaBlendMode): Int =
	when (alphaBlendMode) {
		AlphaBlendMode.Over -> 0
		AlphaBlendMode.Atop -> 1
		AlphaBlendMode.Out -> 2
		AlphaBlendMode.Conjoint -> 3
		AlphaBlendMode.Disjoint -> 4
	}

/**
 * Composites one premultiplied source pixel over one premultiplied destination pixel.
 *
 * @param FloatArray     srcPremul      The layer pixel, premultiplied RGBA (after opacity, colors,
 *                                      and mask have been applied to it).
 * @param FloatArray     dstPremul      The destination pixel, premultiplied RGBA.
 * @param BlendMode      blendMode      The color blend mode.
 * @param AlphaBlendMode alphaBlendMode The alpha blend mode.
 * @return FloatArray The composited pixel, premultiplied RGBA, channels clamped to [0,1].
 */
public fun compositeReference(
	srcPremul: FloatArray,
	dstPremul: FloatArray,
	blendMode: BlendMode,
	alphaBlendMode: AlphaBlendMode,
): FloatArray {
	val sourceAlpha = srcPremul[3]
	val destinationAlpha = dstPremul[3]

	// The three legacy modes under Over replicate fixed-function blending bit-for-bit (the pre-5.3
	// semantics the old runtime renders); everything else goes through the generic path.
	if (alphaBlendMode == AlphaBlendMode.Over && blendMode.isLegacy) {
		val out =
			when (blendMode) {
				// (ONE, ONE_MINUS_SRC_ALPHA) on both color and alpha - premultiplied source-over.
				BlendMode.Normal ->
					floatArrayOf(
						srcPremul[0] + dstPremul[0] * (1f - sourceAlpha),
						srcPremul[1] + dstPremul[1] * (1f - sourceAlpha),
						srcPremul[2] + dstPremul[2] * (1f - sourceAlpha),
						sourceAlpha + destinationAlpha * (1f - sourceAlpha),
					)
				// (ONE, ONE) color, (ZERO, ONE) alpha.
				BlendMode.Additive ->
					floatArrayOf(
						srcPremul[0] + dstPremul[0],
						srcPremul[1] + dstPremul[1],
						srcPremul[2] + dstPremul[2],
						destinationAlpha,
					)
				// (DST_COLOR, ONE_MINUS_SRC_ALPHA) color, (ZERO, ONE) alpha.
				else ->
					floatArrayOf(
						srcPremul[0] * dstPremul[0] + dstPremul[0] * (1f - sourceAlpha),
						srcPremul[1] * dstPremul[1] + dstPremul[1] * (1f - sourceAlpha),
						srcPremul[2] * dstPremul[2] + dstPremul[2] * (1f - sourceAlpha),
						destinationAlpha,
					)
			}
		return FloatArray(4) { channelIndex -> out[channelIndex].coerceIn(0f, 1f) }
	}

	// Generic path: unpremultiply, blend, weight by backdrop alpha (W3C: the blended color applies
	// only where the backdrop exists), then the alpha mode's Porter-Duff factors on premultiplied
	// terms: co = as*Fa*Cm + ab*Fb*Cb, ao = as*Fa + ab*Fb.
	val sourceColor = unpremultiply(srcPremul)
	val destinationColor = unpremultiply(dstPremul)
	val blended = blendColor(blendMode, destinationColor, sourceColor)
	val mixed =
		FloatArray(3) { channelIndex ->
			(1f - destinationAlpha) * sourceColor[channelIndex] + destinationAlpha * blended[channelIndex]
		}
	val sourceFactor: Float
	val destinationFactor: Float
	when (alphaBlendMode) {
		AlphaBlendMode.Over -> {
			sourceFactor = 1f
			destinationFactor = 1f - sourceAlpha
		}
		AlphaBlendMode.Atop -> {
			sourceFactor = destinationAlpha
			destinationFactor = 1f - sourceAlpha
		}
		AlphaBlendMode.Out -> {
			sourceFactor = 1f - destinationAlpha
			destinationFactor = 0f
		}
		// X Render conjoint over: maximal overlap - the destination shows only where it exceeds
		// the source (ao = max(as, ab)).
		AlphaBlendMode.Conjoint -> {
			sourceFactor = 1f
			destinationFactor = if (destinationAlpha <= 0f || sourceAlpha >= destinationAlpha) 0f else 1f - sourceAlpha / destinationAlpha
		}
		// X Render disjoint over: minimal overlap - the coverages pack side by side
		// (ao = min(1, as + ab)).
		AlphaBlendMode.Disjoint -> {
			sourceFactor = 1f
			destinationFactor = if (destinationAlpha <= 0f) 0f else min(1f, (1f - sourceAlpha) / destinationAlpha)
		}
	}
	val outAlpha = sourceAlpha * sourceFactor + destinationAlpha * destinationFactor
	return floatArrayOf(
		(sourceAlpha * sourceFactor * mixed[0] + destinationAlpha * destinationFactor * destinationColor[0]).coerceIn(0f, 1f),
		(sourceAlpha * sourceFactor * mixed[1] + destinationAlpha * destinationFactor * destinationColor[1]).coerceIn(0f, 1f),
		(sourceAlpha * sourceFactor * mixed[2] + destinationAlpha * destinationFactor * destinationColor[2]).coerceIn(0f, 1f),
		outAlpha.coerceIn(0f, 1f),
	)
}

/** The straight-alpha color of a premultiplied pixel (0 where the alpha is 0). */
private fun unpremultiply(premul: FloatArray): FloatArray {
	val alpha = premul[3]
	if (alpha <= 0f) {
		return floatArrayOf(0f, 0f, 0f)
	}
	return floatArrayOf(premul[0] / alpha, premul[1] / alpha, premul[2] / alpha)
}

/**
 * The color blend function B(Cb, Cs) for [blendMode], on straight-alpha colors.
 *
 * @param BlendMode  blendMode The color mode.
 * @param FloatArray backdrop  Cb, straight RGB.
 * @param FloatArray source    Cs, straight RGB.
 * @return FloatArray The blended color, straight RGB.
 */
public fun blendColor(blendMode: BlendMode, backdrop: FloatArray, source: FloatArray): FloatArray =
	when (blendMode) {
		BlendMode.Normal -> source.copyOf()
		// The additive family reads as linear dodge (add) in the generic path; the legacy mode's
		// fixed-function semantics apply only under Over (see compositeReference).  Add (Glow) has
		// been checked against the official editor via Model A and matches this shared formula;
		// Add's modern (_TSL) variant shares it too but is unconfirmed against the editor.
		BlendMode.Additive, BlendMode.AdditiveModern, BlendMode.AdditiveGlow ->
			perChannel(backdrop, source) { backdropChannel, sourceChannel -> min(1f, backdropChannel + sourceChannel) }
		BlendMode.Multiply, BlendMode.MultiplyModern ->
			perChannel(backdrop, source) { backdropChannel, sourceChannel -> backdropChannel * sourceChannel }
		BlendMode.Darken -> perChannel(backdrop, source) { backdropChannel, sourceChannel -> min(backdropChannel, sourceChannel) }
		BlendMode.Lighten -> perChannel(backdrop, source) { backdropChannel, sourceChannel -> max(backdropChannel, sourceChannel) }
		BlendMode.Screen ->
			perChannel(backdrop, source) { backdropChannel, sourceChannel ->
				backdropChannel + sourceChannel - backdropChannel * sourceChannel
			}
		BlendMode.ColorBurn ->
			perChannel(backdrop, source) { backdropChannel, sourceChannel ->
				when {
					backdropChannel >= 1f -> 1f
					sourceChannel <= 0f -> 0f
					else -> 1f - min(1f, (1f - backdropChannel) / sourceChannel)
				}
			}
		BlendMode.LinearBurn ->
			perChannel(backdrop, source) { backdropChannel, sourceChannel -> max(0f, backdropChannel + sourceChannel - 1f) }
		BlendMode.ColorDodge ->
			perChannel(backdrop, source) { backdropChannel, sourceChannel ->
				when {
					backdropChannel <= 0f -> 0f
					sourceChannel >= 1f -> 1f
					else -> min(1f, backdropChannel / (1f - sourceChannel))
				}
			}
		BlendMode.Overlay -> perChannel(backdrop, source) { backdropChannel, sourceChannel -> hardLight(sourceChannel, backdropChannel) }
		BlendMode.HardLight -> perChannel(backdrop, source) { backdropChannel, sourceChannel -> hardLight(backdropChannel, sourceChannel) }
		BlendMode.SoftLight ->
			perChannel(backdrop, source) { backdropChannel, sourceChannel ->
				if (sourceChannel <= 0.5f) {
					backdropChannel - (1f - 2f * sourceChannel) * backdropChannel * (1f - backdropChannel)
				} else {
					backdropChannel + (2f * sourceChannel - 1f) * (softLightD(backdropChannel) - backdropChannel)
				}
			}
		BlendMode.LinearLight ->
			perChannel(backdrop, source) { backdropChannel, sourceChannel ->
				(backdropChannel + 2f * sourceChannel - 1f).coerceIn(0f, 1f)
			}
		BlendMode.Hue -> setLuminosity(setSaturation(source, saturation(backdrop)), luminosity(backdrop))
		BlendMode.Color -> setLuminosity(source, luminosity(backdrop))
	}

private inline fun perChannel(backdrop: FloatArray, source: FloatArray, operation: (Float, Float) -> Float): FloatArray =
	FloatArray(3) { channelIndex -> operation(backdrop[channelIndex], source[channelIndex]) }

/** W3C hard-light: multiply below the midpoint of [selector], screen above it. */
private fun hardLight(base: Float, selector: Float): Float =
	if (selector <= 0.5f) {
		2f * selector * base
	} else {
		val screened = base + (2f * selector - 1f) - base * (2f * selector - 1f)
		screened
	}

/** W3C soft-light's D(x) helper. */
private fun softLightD(value: Float): Float =
	if (value <= 0.25f) {
		((16f * value - 12f) * value + 4f) * value
	} else {
		sqrt(value)
	}

/** W3C Lum(C). */
private fun luminosity(color: FloatArray): Float = 0.3f * color[0] + 0.59f * color[1] + 0.11f * color[2]

/** W3C Sat(C). */
private fun saturation(color: FloatArray): Float =
	max(color[0], max(color[1], color[2])) - min(color[0], min(color[1], color[2]))

/** W3C ClipColor(C): pulls out-of-range channels back toward the luminosity. */
private fun clipColor(color: FloatArray): FloatArray {
	val lum = luminosity(color)
	val minChannel = min(color[0], min(color[1], color[2]))
	val maxChannel = max(color[0], max(color[1], color[2]))
	var result = color
	if (minChannel < 0f) {
		val below = result
		result = FloatArray(3) { channelIndex -> lum + (below[channelIndex] - lum) * lum / (lum - minChannel) }
	}
	if (maxChannel > 1f) {
		val above = result
		result = FloatArray(3) { channelIndex -> lum + (above[channelIndex] - lum) * (1f - lum) / (maxChannel - lum) }
	}
	return result
}

/** W3C SetLum(C, l). */
private fun setLuminosity(color: FloatArray, lum: Float): FloatArray {
	val delta = lum - luminosity(color)
	return clipColor(floatArrayOf(color[0] + delta, color[1] + delta, color[2] + delta))
}

/** W3C SetSat(C, s): rescales the mid channel between min and max, on sorted channels. */
private fun setSaturation(color: FloatArray, sat: Float): FloatArray {
	val result = FloatArray(3)
	val indices = intArrayOf(0, 1, 2).sortedBy { color[it] }
	val minIndex = indices[0]
	val midIndex = indices[1]
	val maxIndex = indices[2]
	if (color[maxIndex] > color[minIndex]) {
		result[midIndex] = (color[midIndex] - color[minIndex]) * sat / (color[maxIndex] - color[minIndex])
		result[maxIndex] = sat
	}
	return result
}
