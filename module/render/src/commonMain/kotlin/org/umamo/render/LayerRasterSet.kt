package org.umamo.render

import org.umamo.runtime.model.PuppetModel

/**
 * One drawable's source-artwork draw: which image it samples, and the affine carrying its stored
 * texture coordinates into that image's frame.
 *
 * @property String layerKey The artwork this drawable samples, keyed into [LayerRasterSet.rastersByLayerKey].
 * @property FloatArray uvAffine The 2x3 row-major stored-to-artwork affine (see `layerUvAffineOf`).
 */
class DrawableLayerDraw(val layerKey: String, val uvAffine: FloatArray)

/**
 * A decoded snapshot of the source artwork a puppet displays from, plus each drawable's mapping into
 * it - everything the renderer needs to draw from artwork instead of the packed atlas, and nothing it
 * would have to decode itself.
 *
 * Decoded ahead of time and handed over whole, deliberately.  Decoding is slow enough to stall a
 * frame, and the store it comes from is not safe to read from two threads, so the hand-off is one
 * immutable value published once rather than a live lookup the render thread walks.  That is also
 * what makes the mode switch atomic: until a complete set arrives, the puppet keeps displaying from
 * the atlas rather than resolving in piecemeal.
 *
 * A drawable absent from [drawsByDrawableId] renders from its atlas page as usual, so a document
 * whose artwork is only partly recoverable still draws whole.
 *
 * @property Map<String, DecodedImage> rastersByLayerKey Each distinct artwork image, by layer key.
 *   Keyed by LAYER, not by drawable: duplicated art is shared, so the texture count is the number of
 *   distinct images rather than the number of drawables.
 * @property Map<String, DrawableLayerDraw> drawsByDrawableId Each drawable's draw, by raw drawable id.
 * @property Int unresolvedDrawableCount How many meshed drawables found no artwork and so keep
 *   displaying from the atlas.  Reported rather than hidden: a puppet drawn mostly from its atlas
 *   should not look like one drawn from its artwork.
 */
class LayerRasterSet(
	val rastersByLayerKey: Map<String, DecodedImage>,
	val drawsByDrawableId: Map<String, DrawableLayerDraw>,
	val unresolvedDrawableCount: Int = 0,
) {
	/** True when there is nothing to display from, so the puppet stays on the atlas. */
	val isEmpty: Boolean get() = drawsByDrawableId.isEmpty()

	companion object {
		/** The empty set: display from the atlas. */
		val EMPTY: LayerRasterSet = LayerRasterSet(emptyMap(), emptyMap())
	}
}

/**
 * Decodes the source artwork a puppet would display from, and works out each drawable's mapping into
 * it.
 *
 * Slow by nature - it decodes every distinct image the puppet needs - so it belongs on a background
 * thread, and it is written to be safe there: it reads only immutable model and store data and uses
 * the uncached [LayerTextures.decodeRaster], sharing nothing with the store's own cache.
 *
 * A drawable is left out (and counted in [LayerRasterSet.unresolvedDrawableCount]) when the document
 * retains no art for it, when its placement will not invert, or when its image will not decode.  Those
 * keep displaying from the atlas, so the puppet stays whole rather than developing holes - which does
 * mean a puppet can be displaying from a mix of both, and the count is what makes that visible.
 *
 * Pass the previous set when rebuilding (a drawable added or removed changes the mapping but not the
 * artwork): its images are reused, so only genuinely new ones decode.
 *
 * @param PuppetModel model The puppet whose drawables need artwork.
 * @param LayerTextures layers The document's source-art store.
 * @param LayerRasterSet previous An earlier set whose decoded images may be reused.
 * @return LayerRasterSet The decoded artwork plus each drawable's mapping into it.
 */
fun buildLayerRasterSet(
	model: PuppetModel,
	layers: LayerTextures,
	previous: LayerRasterSet = LayerRasterSet.EMPTY,
): LayerRasterSet {
	if (layers.isEmpty) {
		return LayerRasterSet.EMPTY
	}
	val rastersByLayerKey = HashMap<String, DecodedImage>()
	val undecodable = HashSet<String>()
	val drawsByDrawableId = HashMap<String, DrawableLayerDraw>()
	var unresolved = 0
	for (drawable in model.drawables) {
		if (drawable.mesh == null) {
			continue
		}
		val binding = layers.bindingForDrawable(drawable)
		val entry = binding?.let { layers.layerFor(it.layerKey) }
		val affine = if (binding != null && entry != null) layerUvAffineOf(binding, entry.width, entry.height) else null
		if (binding == null || entry == null || affine == null) {
			unresolved++
			continue
		}
		if (binding.layerKey !in rastersByLayerKey && binding.layerKey !in undecodable) {
			val decoded = previous.rastersByLayerKey[binding.layerKey] ?: layers.decodeRaster(binding.layerKey)
			if (decoded == null) {
				undecodable.add(binding.layerKey)
			} else {
				rastersByLayerKey[binding.layerKey] = decoded
			}
		}
		if (binding.layerKey in undecodable) {
			unresolved++
			continue
		}
		drawsByDrawableId[drawable.id.raw] = DrawableLayerDraw(binding.layerKey, affine)
	}
	return LayerRasterSet(rastersByLayerKey, drawsByDrawableId, unresolved)
}