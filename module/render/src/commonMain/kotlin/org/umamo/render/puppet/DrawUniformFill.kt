package org.umamo.render.puppet

import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.FragmentUniforms
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.WarpWorld
import org.umamo.render.glsl.MAX_BLEND_CORNERS
import org.umamo.render.glsl.MAX_CORNERS

/*
 * Filling the reused per-draw uniform structs from a resident drawable.
 *
 * The renderer refills one instance of each struct per draw rather than allocating one per drawable per
 * frame, so these write every field a draw reads and allocate nothing.
 */

/**
 * Fills a drawable's per-pose deform uniforms (active corners + baked parent transform).  A drawable
 * with no corners is unposed, and the struct is left as it was.
 *
 * @param DeformUniforms deform      The struct to fill.
 * @param GpuDrawable    gpuDrawable The resident drawable to fill it from.
 */
internal fun fillDeform(deform: DeformUniforms, gpuDrawable: GpuDrawable) {
	val corners = gpuDrawable.corners ?: return
	val cornerCount = minOf(MAX_CORNERS, corners.size)
	deform.cornerCount = cornerCount
	for (cornerIndex in 0 until cornerCount) {
		deform.cornerCell[cornerIndex] = corners[cornerIndex].linearIndex
		deform.cornerWeight[cornerIndex] = corners[cornerIndex].weight
	}
	// Blend-shape columns: map the pose's active contributions through the static layout.
	// Surplus beyond MAX_BLEND_CORNERS is dropped deterministically (corpus max is 18).
	var blendCount = 0
	val blend = gpuDrawable.blend
	if (blend != null) {
		for (contribution in blend.contributions) {
			if (blendCount >= MAX_BLEND_CORNERS) {
				break
			}
			val column = gpuDrawable.blendLayout.columnOf(contribution.bindingIndex, contribution.keyIndex) ?: continue
			deform.blendCell[blendCount] = column
			deform.blendWeight[blendCount] = contribution.weight
			blendCount++
		}
	}
	deform.blendCount = blendCount
	when (val parentWorld = gpuDrawable.parentWorld) {
		is RotationWorld -> {
			deform.parentType = 1
			val xform = parentWorld.xform
			deform.rotation[0] = xform.c12
			deform.rotation[1] = xform.c13
			deform.rotation[2] = xform.c14
			deform.rotation[3] = xform.c15
			deform.rotation[4] = xform.ox
			deform.rotation[5] = xform.oy
		}

		is WarpWorld -> {
			deform.parentType = 2
			deform.warpColumns = parentWorld.cols
			deform.warpRows = parentWorld.rows
			deform.warpBilinear = parentWorld.bilinear
		}

		else -> deform.parentType = 0
	}
}

/**
 * Fills a drawable's fragment uniforms (texture-or-color, opacity, mask flags, highlight).
 *
 * @param FragmentUniforms fragment    The struct to fill.
 * @param GpuDrawable      gpuDrawable The resident drawable to fill it from.
 * @param Float            opacity     The opacity to draw at.
 * @param Float            highlight   How far to tint toward [tint] (0 = untinted).
 * @param FloatArray       tint        The highlight color, as red, green, and blue.
 * @param Boolean          masked      Whether the draw samples mask coverage.
 */
internal fun fillFragment(
	fragment: FragmentUniforms,
	gpuDrawable: GpuDrawable,
	opacity: Float,
	highlight: Float,
	tint: FloatArray,
	masked: Boolean,
) {
	fragment.reset()
	// The CHOSEN handle, not the atlas one: a drawable rendering from its source artwork has no atlas
	// binding in play, and keying off the wrong field would draw it as a flat color.
	val activeTexture = gpuDrawable.activeTexture()
	if (activeTexture != null) {
		fragment.useTexture = true
		// Identity unless this drawable is sampling its artwork, in which case the affine is what
		// makes its stored (atlas-frame) coordinates address that image instead.
		if (activeTexture === gpuDrawable.layerTexture) {
			gpuDrawable.layerUvAffine?.copyInto(fragment.uvAffine)
		}
	} else {
		fragment.colorRed = gpuDrawable.color[0]
		fragment.colorGreen = gpuDrawable.color[1]
		fragment.colorBlue = gpuDrawable.color[2]
		fragment.colorAlpha = gpuDrawable.color[3]
	}
	fragment.opacity = opacity
	fragment.useMask = masked
	fragment.invertMask = masked && gpuDrawable.invertMask
	fragment.multiplyRed = gpuDrawable.multiplyColor.red
	fragment.multiplyGreen = gpuDrawable.multiplyColor.green
	fragment.multiplyBlue = gpuDrawable.multiplyColor.blue
	fragment.screenRed = gpuDrawable.screenColor.red
	fragment.screenGreen = gpuDrawable.screenColor.green
	fragment.screenBlue = gpuDrawable.screenColor.blue
	fragment.highlight = highlight
	fragment.highlightRed = tint[0]
	fragment.highlightGreen = tint[1]
	fragment.highlightBlue = tint[2]
}

/** Resets every field to its "nothing set" default, for reuse across draws. */
internal fun FragmentUniforms.reset() {
	useTexture = false
	colorRed = 0f
	colorGreen = 0f
	colorBlue = 0f
	colorAlpha = 0f
	opacity = 1f
	useMask = false
	invertMask = false
	multiplyRed = 1f
	multiplyGreen = 1f
	multiplyBlue = 1f
	screenRed = 0f
	screenGreen = 0f
	screenBlue = 0f
	highlight = 0f
	highlightRed = 0f
	highlightGreen = 0f
	highlightBlue = 0f
	drawOrder = 0
	orderOpacity = 1f
	// IDENTITY, not zero.  Every other field here resets to a harmless zero; this one cannot - a zeroed
	// affine maps every texture coordinate onto texel (0, 0), so the whole draw samples one pixel.
	setIdentityUvAffine(uvAffine)
}

/**
 * Writes the identity 2x3 affine into a uv-affine array in place.
 *
 * @param FloatArray affine The six-float affine to overwrite.
 */
private fun setIdentityUvAffine(affine: FloatArray) {
	affine[0] = 1f
	affine[1] = 0f
	affine[2] = 0f
	affine[3] = 0f
	affine[4] = 1f
	affine[5] = 0f
}