package org.umamo.render

import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableLayerBinding

/**
 * One source layer the editor can show: the artwork a drawable was authored against, before packing.
 *
 * Identity and size only - the pixels come from [LayerTextures.rasterFor] on demand, because a real
 * model carries hundreds of these and decoding them all at document load would stall the open.
 *
 * @property String key The layer's stable document-local identifier(the join key bindings reference).
 * @property String name The layer's display name.
 * @property Int width The layer image's width in pixels.
 * @property Int height The layer image's height in pixels.
 * @property List<String> boundDrawableIds Every drawable that samples this layer, in document order.
 * @property String? sourceLayerName The originating artwork layer's name when exactly one composites
 *   into this image, else null (recorded for a future source binding; unused today).
 */
data class SourceLayerEntry(
	val key: String,
	val name: String,
	val width: Int,
	val height: Int,
	val boundDrawableIds: List<String>,
	val sourceLayerName: String?,
)

/**
 * A document's source-layer store: the inventory of artwork layers plus each drawable's recovered
 * binding to one, with the pixels decoded lazily.
 *
 * Inventory is built eagerly (names and dimensions are cheap metadata), rasters are not: a CMO3
 * document loads synchronously on the UI thread, so decoding every layer up front would stall the
 * open by seconds on a real model.  A decoded layer is retained for the store's life, which is the
 * document's life - a replaced document drops the whole store.
 *
 * Format-agnostic by construction: the byte supplier is injected, so the CMO3 retained graph fills
 * it today and a layered-art reader can fill the same type later.
 *
 * @property List<SourceLayerEntry> layers Every source layer in the document, in document order.
 * @property Map<String, DrawableLayerBinding> bindingsByDrawableId Each drawable's recovered binding,
 *   keyed by the raw drawable id; a drawable with no recoverable layer is absent.
 * @property Function readBytes Yields a layer's PNG bytes by key, or null when it has none.
 */
class LayerTextures(
	val layers: List<SourceLayerEntry>,
	val bindingsByDrawableId: Map<String, DrawableLayerBinding>,
	private val readBytes: (String) -> ByteArray?,
) {
	private val entriesByKey: Map<String, SourceLayerEntry> = layers.associateBy { entry -> entry.key }

	// A null value is a remembered failure (no bytes, or bytes that will not decode), which is why this
	// is not a getOrPut - getOrPut treats a stored null as absent and would retry the decode every time.
	private val decodedByKey = HashMap<String, DecodedImage?>()

	/** True when the document surfaces no source layers at all (a MOC3 origin, or art-less CMO3). */
	val isEmpty: Boolean get() = layers.isEmpty()

	/**
	 * The inventory entry for a layer key.
	 *
	 * @param String layerKey The layer's stable id.
	 * @return SourceLayerEntry? The entry, or null when the key is unknown.
	 */
	fun layerFor(layerKey: String): SourceLayerEntry? = entriesByKey[layerKey]

	/**
	 * The source layer a drawable samples.
	 *
	 * @param String drawableId The raw drawable id.
	 * @return SourceLayerEntry? The layer, or null when the drawable has no recoverable binding.
	 */
	fun layerForDrawable(drawableId: String): SourceLayerEntry? =
		bindingsByDrawableId[drawableId]?.let { binding -> entriesByKey[binding.layerKey] }

	/**
	 * A drawable's binding, resolved through the same texture-source indirection the atlas lookup uses.
	 *
	 * Bindings are keyed by the SOURCE format's drawable ids, so a session-created copy has no key of
	 * its own and finds its art through the drawable it was duplicated from - exactly as it finds its
	 * atlas page.  Looking a drawable up by its own raw id alone would give a duplicate a page view but
	 * no layer view.
	 *
	 * @param Drawable drawable The drawable to resolve.
	 * @return DrawableLayerBinding? The binding, or null when the drawable has no recoverable source art.
	 */
	fun bindingForDrawable(drawable: Drawable): DrawableLayerBinding? =
		bindingsByDrawableId[(drawable.textureSourceId ?: drawable.id).raw]

	/**
	 * The source layer a drawable samples, through the texture-source indirection.
	 *
	 * @param Drawable drawable The drawable to resolve.
	 * @return SourceLayerEntry? The layer, or null when the drawable has no recoverable source art.
	 */
	fun layerForDrawable(drawable: Drawable): SourceLayerEntry? =
		bindingForDrawable(drawable)?.let { binding -> entriesByKey[binding.layerKey] }

	/**
	 * The placement any drawable over a layer shares.
	 *
	 * Every drawable bound to one layer resolves through the same model image, so they carry the same
	 * placement and the same page - which is what lets the editor describe a layer's frame without
	 * first deciding which of its users to ask, and keeps that frame stable as the selection narrows.
	 *
	 * @param String layerKey The layer in question.
	 * @return DrawableLayerBinding? A binding over that layer, or null when nothing is bound to it.
	 */
	fun bindingForLayer(layerKey: String): DrawableLayerBinding? =
		entriesByKey[layerKey]?.boundDrawableIds?.firstNotNullOfOrNull { drawableId -> bindingsByDrawableId[drawableId] }

	/**
	 * Whether a drawable draws over the given layer, through the texture-source indirection.
	 *
	 * @param Drawable drawable The drawable to test.
	 * @param String layerKey The layer in question.
	 * @return Boolean True when the drawable samples that layer.
	 */
	fun drawsOverLayer(drawable: Drawable, layerKey: String): Boolean =
		bindingForDrawable(drawable)?.layerKey == layerKey

	/**
	 * A layer's pixels, decoding them on first request and caching the result (failures included).
	 *
	 * CALLER-CONFINED: the cache is a plain map with no synchronization, so every call must come from
	 * the same thread (today, the UI thread).  A second caller would not merely race the map - it could
	 * hand out a SECOND decoded instance for one layer, and both the renderer's texture cache and the
	 * viewport's freshness test compare decoded images by identity.  Anything off that thread uses
	 * [decodeRaster] instead, which shares nothing.
	 *
	 * @param String layerKey The layer's stable id.
	 * @return DecodedImage? The decoded raster, or null when the layer has no usable pixels.
	 */
	fun rasterFor(layerKey: String): DecodedImage? {
		if (layerKey !in entriesByKey) {
			return null
		}
		if (decodedByKey.containsKey(layerKey)) {
			return decodedByKey[layerKey]
		}
		// The cached twin decodes through the uncached one: the two must produce the same image for the
		// same bytes, because callers compare a cached result against a freshly decoded one by identity.
		val decoded = decodeRaster(layerKey)
		decodedByKey[layerKey] = decoded
		return decoded
	}

	/**
	 * A layer's pixels, decoded fresh and cached nowhere - safe to call from any thread, including
	 * several at once.
	 *
	 * The uncached twin of [rasterFor], for callers that own their own result: the bytes come from an
	 * immutable archive and the decoder is stateless, so nothing here is shared.  Costs a repeat decode
	 * when a layer is already cached, which is the price of not sharing a cache across threads.
	 *
	 * @param String layerKey The layer's stable id.
	 * @return DecodedImage? The decoded raster, or null when the layer has no usable pixels.
	 */
	fun decodeRaster(layerKey: String): DecodedImage? =
		readBytes(layerKey)?.let { bytes ->
			try {
				val image = org.umamo.format.png.PngCodec.read(bytes)
				DecodedImage(image.rgba, image.width, image.height)
			} catch (_: Exception) {
				null
			}
		}

	companion object {
		/** The store a document with no source art surfaces. */
		val EMPTY: LayerTextures = LayerTextures(emptyList(), emptyMap()) { null }
	}
}