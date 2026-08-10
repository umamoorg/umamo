package org.umamo.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where one drawable's upright source art sits on an atlas page: the transform the packer applied to
 * it, as translation / scale / rotation.
 *
 * A TRS rather than a rect because that is what the source formats actually carry and what the
 * corpus actually uses - rotation (haruto, koharu, seesaw, modelE) and non-unit scale (modelC packs
 * 170 of 187 images scaled, modelE all 97) both occur, and a rect-plus-quarter-turn model would
 * silently discard them on import.  A packer that only ever emits axis-aligned unit-scale placements
 * writes the constrained subset (scale 1, rotation 0) and loses nothing.
 *
 * Rotation is DEGREES, counter-clockwise, about the layer's own origin, applied after scale; the
 * frame is page pixels with y running DOWN (v = 0 is the page's top row), matching how the decoder
 * emits rows and how the sampler addresses them.
 *
 * @property Int pageIndex The atlas page the art packs onto, in the source document's page order.
 * @property Float positionX The packing origin's x on the page, in pixels (fractional in real files).
 * @property Float positionY The packing origin's y on the page, in pixels.
 * @property Float scaleX The horizontal scale the packer applied (negative mirrors).
 * @property Float scaleY The vertical scale the packer applied (negative mirrors).
 * @property Float rotationDegrees The rotation the packer applied, counter-clockwise degrees.
 */
data class AtlasPlacement(
	val pageIndex: Int,
	val positionX: Float,
	val positionY: Float,
	val scaleX: Float,
	val scaleY: Float,
	val rotationDegrees: Float,
)

/**
 * One source layer the editor can show: the artwork a drawable was authored against, before packing.
 *
 * Identity and size only - the pixels come from [LayerTextures.rasterFor] on demand, because a real
 * model carries hundreds of these (Erica: 176) and decoding them all at document load would stall
 * the open.
 *
 * @property String key The layer's stable document-local id (the join key bindings reference).
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
 * One drawable's recovered link to its source art: which layer it samples, and how its stored atlas
 * texture coordinates map back into that layer's own pixel frame.
 *
 * A null [placement] means the drawable was never packed - its stored uvs already address the layer
 * image directly, so recovery is the identity.  Applying a placement inverse to those would be a
 * double transform.
 *
 * The page dimensions ride here rather than being looked up from [PuppetTextures] because the two
 * page orders are independently derived (PuppetTextures collects pages in drawable-encounter order
 * and skips undecodable ones), so an index shared between them cannot be assumed to agree.  Recovery
 * is self-contained as a result.
 *
 * @property String layerKey The [SourceLayerEntry] this drawable samples.
 * @property AtlasPlacement? placement The packing transform, or null when the drawable was never packed.
 * @property Int pageWidth The atlas page's width in pixels, as recorded with the placement.
 * @property Int pageHeight The atlas page's height in pixels.
 */
data class DrawableLayerBinding(
	val layerKey: String,
	val placement: AtlasPlacement?,
	val pageWidth: Int,
	val pageHeight: Int,
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
	 * A layer's pixels, decoding them on first request and caching the result (failures included).
	 *
	 * @param String layerKey The layer's stable id.
	 * @return DecodedImage? The decoded raster, or null when the layer has no usable pixels.
	 */
	fun rasterFor(layerKey: String): DecodedImage? {
		if (decodedByKey.containsKey(layerKey)) {
			return decodedByKey[layerKey]
		}
		val decoded =
			readBytes(layerKey)?.let { bytes ->
				try {
					val image = org.umamo.format.png.PngCodec.read(bytes)
					DecodedImage(image.rgba, image.width, image.height)
				} catch (_: Exception) {
					null
				}
			}
		decodedByKey[layerKey] = decoded
		return decoded
	}

	companion object {
		/** The store a document with no source art surfaces. */
		val EMPTY: LayerTextures = LayerTextures(emptyList(), emptyMap()) { null }
	}
}

/**
 * The placement's inverse as a 2x3 affine (m00, m01, m02, m10, m11, m12) mapping page pixels back to
 * layer pixels.
 *
 * The forward transform is `atlas = R(angle) . S . layer + position`, so the inverse is
 * `layer = S^-1 . R(-angle) . (atlas - position)`.  Resolved once per placement so a whole mesh
 * converts without recomputing the trigonometry per vertex.
 *
 * @param AtlasPlacement placement The packing transform to invert.
 * @return FloatArray? The inverse affine, or null when the placement is degenerate (a zero scale).
 */
internal fun inversePlacementAffine(placement: AtlasPlacement): FloatArray? {
	if (placement.scaleX == 0f || placement.scaleY == 0f) {
		return null
	}
	val radians = placement.rotationDegrees.toDouble() * PI / 180.0
	val cosine = cos(radians)
	val sine = sin(radians)
	// R(-angle) then S^-1, i.e. rows scaled by the inverse scale after the transposed rotation.
	val m00 = (cosine / placement.scaleX).toFloat()
	val m01 = (sine / placement.scaleX).toFloat()
	val m10 = (-sine / placement.scaleY).toFloat()
	val m11 = (cosine / placement.scaleY).toFloat()
	return floatArrayOf(
		m00,
		m01,
		-(m00 * placement.positionX + m01 * placement.positionY),
		m10,
		m11,
		-(m10 * placement.positionX + m11 * placement.positionY),
	)
}

/**
 * The atlas-page pixel a layer pixel packs to (the forward transform).
 *
 * @param AtlasPlacement placement The packing transform.
 * @param Float layerX The layer-local x in pixels.
 * @param Float layerY The layer-local y in pixels.
 * @return FloatArray The (x, y) page pixel.
 */
fun atlasPixelOf(placement: AtlasPlacement, layerX: Float, layerY: Float): FloatArray {
	val radians = placement.rotationDegrees.toDouble() * PI / 180.0
	val cosine = cos(radians)
	val sine = sin(radians)
	val scaledX = layerX * placement.scaleX
	val scaledY = layerY * placement.scaleY
	return floatArrayOf(
		(cosine * scaledX - sine * scaledY).toFloat() + placement.positionX,
		(sine * scaledX + cosine * scaledY).toFloat() + placement.positionY,
	)
}

/**
 * The layer pixel an atlas-page pixel came from (the inverse transform).
 *
 * @param AtlasPlacement placement The packing transform.
 * @param Float atlasX The page x in pixels.
 * @param Float atlasY The page y in pixels.
 * @return FloatArray? The (x, y) layer pixel, or null when the placement is degenerate.
 */
fun layerPixelOf(placement: AtlasPlacement, atlasX: Float, atlasY: Float): FloatArray? {
	val inverse = inversePlacementAffine(placement) ?: return null
	return floatArrayOf(
		inverse[0] * atlasX + inverse[1] * atlasY + inverse[2],
		inverse[3] * atlasX + inverse[4] * atlasY + inverse[5],
	)
}

/**
 * Maps a drawable's stored texture coordinates into its source layer's own [0,1] frame - the mapping
 * a layer view draws.
 *
 * An unpacked drawable (a null placement on the binding) is already addressing its layer image, so
 * its uvs pass through untouched.  A packed drawable's uvs address the page, so they scale up to
 * page pixels, invert through the placement, and scale down by the layer's own size.
 *
 * @param FloatArray atlasUvs The drawable's stored texture coordinates, interleaved (u, v).
 * @param DrawableLayerBinding binding The drawable's recovered binding.
 * @param Int layerWidth The source layer's width in pixels.
 * @param Int layerHeight The source layer's height in pixels.
 * @return FloatArray? The layer-frame uvs, or null when the placement is degenerate.
 */
fun layerUvsFromAtlasUvs(
	atlasUvs: FloatArray,
	binding: DrawableLayerBinding,
	layerWidth: Int,
	layerHeight: Int,
): FloatArray? {
	val placement = binding.placement ?: return atlasUvs.copyOf()
	if (layerWidth <= 0 || layerHeight <= 0) {
		return null
	}
	val inverse = inversePlacementAffine(placement) ?: return null
	val layerUvs = FloatArray(atlasUvs.size)
	var componentIndex = 0
	while (componentIndex + 1 < atlasUvs.size) {
		val atlasX = atlasUvs[componentIndex] * binding.pageWidth
		val atlasY = atlasUvs[componentIndex + 1] * binding.pageHeight
		layerUvs[componentIndex] = (inverse[0] * atlasX + inverse[1] * atlasY + inverse[2]) / layerWidth
		layerUvs[componentIndex + 1] = (inverse[3] * atlasX + inverse[4] * atlasY + inverse[5]) / layerHeight
		componentIndex += 2
	}
	return layerUvs
}