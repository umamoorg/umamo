package org.umamo.ui.model

import androidx.compose.ui.graphics.ImageBitmap
import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable
import org.umamo.ui.graphics.RgbaAlphaType
import org.umamo.ui.graphics.rgbaToImageBitmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Longest edge (in texels) of a single art-mesh crop. The drawable's atlas crop is nearest-downsampled to
 * fit within this box so the cache stays small even for large atlas pages; the consumer then scales the
 * result to its display slot. 96px is comfortably above the slots that show it (the overlap picker's
 * ~44dp, the Outliner hover's ~120dp) at any reasonable display density, so the preview stays crisp.
 */
private const val CROP_MAX_DIMENSION = 96

/**
 * Longest edge (in pixels) of a combined part-folder preview. A touch larger than a single crop because it
 * packs several layers; the composite is built once per part and cached.
 */
private const val PART_COMPOSITE_MAX_DIMENSION = 128

/**
 * Crops small art-mesh previews from the loaded model's atlas pages, the shared source for the viewport's
 * overlap picker and the Outliner's hover preview (per-drawable [thumbnailFor] and combined
 * [partThumbnailFor]). Pure CPU over the immutable decoded atlas bytes ([PuppetTextures.atlases]) and the
 * drawables' mesh data - no GL, no render-thread hand-off - so it is safe to call straight from the Compose
 * UI thread. Results are memoized; [updateModel] refreshes the model-derived inputs after every committed
 * edit and evicts exactly the entries the edit staled (atlas pixels never change; a future re-import that
 * swaps atlases builds a fresh instance).
 *
 * Rasterises to a Compose [ImageBitmap] through the rgbaToImageBitmap platform seam (Skiko on desktop,
 * android.graphics.Bitmap on Android), so one implementation serves both apps. The per-drawable crop
 * covers every drawable carrying mesh UVs (not just the currently visible ones), so a hidden layer
 * previews too.
 *
 * モデルのアトラスからアートメッシュのサムネイルを切り出す。重なり選択とアウトライナーのホバーで共用。
 *
 * @property PuppetModel puppet The rig whose drawables are previewed.
 * @property PuppetTextures textures The decoded atlas page(s) + per-drawable page index.
 */
class DrawableThumbnailer(
	private val puppet: PuppetModel,
	private val textures: PuppetTextures,
) : DrawableThumbnailProvider {
	/** A cropped art region as raw straight-alpha RGBA (top row first), reused by both the per-drawable and part previews. */
	private class RawCrop(val rgba: ByteArray, val width: Int, val height: Int)

	/** A drawable's rest-pose model-space axis-aligned bounds, the rectangle its crop is placed into when compositing. */
	private class ModelBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
		val width: Float get() = maxX - minX
		val height: Float get() = maxY - minY
	}

	// Drawable id -> drawable, over the CURRENT model - refreshed by [updateModel] so a session-created
	// drawable (a duplicate's fresh ".001" id) resolves for crops and bounds.
	private var drawableById = puppet.drawables.associateBy { it.id }

	// Per-drawable UVs (full-atlas [0,1]), over every drawable carrying mesh UVs - the hover preview should
	// work on hidden layers too, so this is not restricted to the visible/pickable set.  Refreshed by
	// [updateModel] alongside [drawableById].
	private var uvsByDrawable: Map<DrawableId, FloatArray> = uvsFor(puppet.drawables)

	// Drawable membership + paint order and the part hierarchy a part composite is built from, over the
	// CURRENT model - [updateModel] refreshes them with the rest of the model-derived inputs.  UI-thread
	// only (the same thread the previews are requested on).
	private var currentDrawables = puppet.drawables
	private var currentPartChildren: Map<PartId, List<PartId>> =
		puppet.parts.associate { part -> part.id to part.children.filterIsInstance<OrgChild.Part>().map { it.id } }

	// Drawable -> owning part, derived from the org tree (membership lives only there now).
	private var currentDrawableOwner: Map<DrawableId, PartId?> = puppet.partByDrawable()

	// Memoized derivations. The per-drawable caches are keyed by immutable geometry / UV data; [updateModel]
	// evicts an entry only when its drawable's mesh data was replaced or the drawable was removed.  The part
	// composites depend on membership / order and are cleared wholesale.
	private val cropCache = mutableMapOf<DrawableId, RawCrop?>()
	private val boundsCache = mutableMapOf<DrawableId, ModelBounds?>()
	private val thumbnailCache = mutableMapOf<DrawableId, ImageBitmap?>()
	private val partThumbnailCache = mutableMapOf<PartId, ImageBitmap?>()

	/**
	 * Refreshes every model-derived input after an edit and evicts exactly the caches the edit staled:
	 * the part composites always (membership / order may have changed), and the geometry-keyed
	 * per-drawable entries only where the mesh data was actually copy-on-write replaced (a remesh swaps
	 * uvs, a vertex move swaps positions) or the drawable was removed (a later redo may recreate the id
	 * with different geometry).  A session-added drawable - a duplicate's fresh ".001" id - needs no
	 * eviction; its entries fill lazily on first request now that the lookups see it.
	 *
	 * @param PuppetModel model The current model.
	 */
	fun updateModel(model: PuppetModel) {
		val previousById = drawableById
		val newById = model.drawables.associateBy { it.id }
		for (drawable in model.drawables) {
			val previous = previousById[drawable.id] ?: continue
			if (drawable.mesh?.uvs !== previous.mesh?.uvs) {
				cropCache.remove(drawable.id)
				thumbnailCache.remove(drawable.id)
			}
			if (drawable.mesh?.positions !== previous.mesh?.positions) {
				boundsCache.remove(drawable.id)
			}
		}
		for (removedId in previousById.keys) {
			if (removedId !in newById) {
				cropCache.remove(removedId)
				thumbnailCache.remove(removedId)
				boundsCache.remove(removedId)
			}
		}
		drawableById = newById
		uvsByDrawable = uvsFor(model.drawables)
		currentDrawables = model.drawables
		currentPartChildren = model.parts.associate { part -> part.id to part.children.filterIsInstance<OrgChild.Part>().map { it.id } }
		currentDrawableOwner = model.partByDrawable()
		partThumbnailCache.clear()
	}

	/**
	 * The per-drawable full-atlas UVs over every drawable carrying mesh UVs.
	 *
	 * @param List<Drawable> drawables The model's drawables.
	 * @return Map<DrawableId, FloatArray> Drawable id to its mesh UVs.
	 */
	private fun uvsFor(drawables: List<Drawable>): Map<DrawableId, FloatArray> =
		drawables
			.mapNotNull { drawable -> drawable.mesh?.uvs?.takeIf { it.isNotEmpty() }?.let { drawable.id to it } }
			.toMap()

	/**
	 * The cropped art preview for a drawable, or null when it is untextured, mesh-less, or unknown. Memoized.
	 *
	 * @param DrawableId id The drawable to preview.
	 * @return ImageBitmap? The cropped preview, or null when none is available.
	 */
	override fun thumbnailFor(id: DrawableId): ImageBitmap? = thumbnailCache.getOrPut(id) { croppedFor(id)?.let(::rawToImage) }

	/**
	 * A combined preview of every art mesh under [id] (its own drawables and its sub-parts'), placed by each
	 * drawable's rest-pose model bounds and composited back-to-front, or null when the part holds no textured
	 * drawable. Reuses the cached per-drawable crops; only the bounding-box placement and the alpha-over blit
	 * are new work, so it stays cheap. Memoized.
	 *
	 * @param PartId id The part to preview.
	 * @return ImageBitmap? The composited preview, or null when the part has no previewable art.
	 */
	override fun partThumbnailFor(id: PartId): ImageBitmap? = partThumbnailCache.getOrPut(id) { buildPartThumbnail(id) }

	/** The cached raw crop for a drawable, computed once. */
	private fun croppedFor(id: DrawableId): RawCrop? = cropCache.getOrPut(id) { buildCrop(id) }

	/**
	 * Crops the drawable's atlas page to its mesh UV bounding box and nearest-downsamples it to fit
	 * [CROP_MAX_DIMENSION], preserving the straight (un-premultiplied) alpha so the silhouette survives.
	 *
	 * @param DrawableId id The drawable to crop.
	 * @return RawCrop? The cropped RGBA region, or null when the drawable is untextured or mesh-less.
	 */
	private fun buildCrop(id: DrawableId): RawCrop? {
		// The atlas mapping is keyed by the source format's ids, so a session-created duplicate resolves
		// its page through textureSourceId (its source, or the original for a copy of a copy) - mirroring
		// the GPU upload's resolution in GlPuppetRenderer.
		val drawable = drawableById[id] ?: return null
		val atlasIndex = textures.atlasIndexByDrawableId[(drawable.textureSourceId ?: drawable.id).raw] ?: return null
		val image = textures.atlases.getOrNull(atlasIndex) ?: return null
		val uvs = uvsByDrawable[id] ?: return null
		if (uvs.isEmpty()) {
			return null
		}
		var minU = Float.POSITIVE_INFINITY
		var maxU = Float.NEGATIVE_INFINITY
		var minV = Float.POSITIVE_INFINITY
		var maxV = Float.NEGATIVE_INFINITY
		var pairIndex = 0
		while (pairIndex < uvs.size - 1) {
			val texU = uvs[pairIndex]
			val texV = uvs[pairIndex + 1]
			if (texU < minU) {
				minU = texU
			}
			if (texU > maxU) {
				maxU = texU
			}
			if (texV < minV) {
				minV = texV
			}
			if (texV > maxV) {
				maxV = texV
			}
			pairIndex += 2
		}
		// UV bounding box → texel rectangle, clamped to the atlas and at least one texel on each side.
		val cropLeft = floor(minU * image.width).toInt().coerceIn(0, image.width - 1)
		val cropTop = floor(minV * image.height).toInt().coerceIn(0, image.height - 1)
		val cropRight = ceil(maxU * image.width).toInt().coerceIn(cropLeft + 1, image.width)
		val cropBottom = ceil(maxV * image.height).toInt().coerceIn(cropTop + 1, image.height)
		val cropWidth = cropRight - cropLeft
		val cropHeight = cropBottom - cropTop
		// Fit the longest edge into the crop box (never upscale a region already smaller than the box).
		val scale = (CROP_MAX_DIMENSION.toFloat() / maxOf(cropWidth, cropHeight)).coerceAtMost(1f)
		val thumbWidth = (cropWidth * scale).toInt().coerceAtLeast(1)
		val thumbHeight = (cropHeight * scale).toInt().coerceAtLeast(1)
		val rowBytes = thumbWidth * 4
		val pixels = ByteArray(rowBytes * thumbHeight)
		var destRow = 0
		while (destRow < thumbHeight) {
			// Nearest-neighbor: map the destination texel back to a source texel within the crop rectangle.
			val sourceRow = cropTop + (destRow * cropHeight) / thumbHeight
			var destColumn = 0
			while (destColumn < thumbWidth) {
				val sourceColumn = cropLeft + (destColumn * cropWidth) / thumbWidth
				val sourceOffset = (sourceRow * image.width + sourceColumn) * 4
				val destOffset = destRow * rowBytes + destColumn * 4
				pixels[destOffset] = image.rgba[sourceOffset]
				pixels[destOffset + 1] = image.rgba[sourceOffset + 1]
				pixels[destOffset + 2] = image.rgba[sourceOffset + 2]
				pixels[destOffset + 3] = image.rgba[sourceOffset + 3]
				destColumn += 1
			}
			destRow += 1
		}
		return RawCrop(pixels, thumbWidth, thumbHeight)
	}

	/** The cached rest-pose model bounds for a drawable, from its mesh positions. */
	private fun modelBoundsOf(id: DrawableId): ModelBounds? = boundsCache.getOrPut(id) { computeBounds(id) }

	/**
	 * Computes a drawable's rest-pose model-space bounding box from its mesh positions (interleaved x, y).
	 *
	 * @param DrawableId id The drawable to bound.
	 * @return ModelBounds? The bounds, or null when the drawable is mesh-less or degenerate.
	 */
	private fun computeBounds(id: DrawableId): ModelBounds? {
		val positions = drawableById[id]?.mesh?.positions ?: return null
		if (positions.size < 2) {
			return null
		}
		var minX = Float.POSITIVE_INFINITY
		var maxX = Float.NEGATIVE_INFINITY
		var minY = Float.POSITIVE_INFINITY
		var maxY = Float.NEGATIVE_INFINITY
		var pairIndex = 0
		while (pairIndex < positions.size - 1) {
			val positionX = positions[pairIndex]
			val positionY = positions[pairIndex + 1]
			if (positionX < minX) {
				minX = positionX
			}
			if (positionX > maxX) {
				maxX = positionX
			}
			if (positionY < minY) {
				minY = positionY
			}
			if (positionY > maxY) {
				maxY = positionY
			}
			pairIndex += 2
		}
		if (minX > maxX || minY > maxY) {
			return null
		}
		return ModelBounds(minX, minY, maxX, maxY)
	}

	/** Every part id in a part's subtree (the part itself plus all descendant parts). */
	private fun partSubtree(rootPartId: PartId): Set<PartId> {
		val collected = LinkedHashSet<PartId>()
		val pending = ArrayDeque<PartId>()
		pending.addLast(rootPartId)
		while (pending.isNotEmpty()) {
			val partId = pending.removeLast()
			if (!collected.add(partId)) {
				continue
			}
			currentPartChildren[partId]?.forEach { childPartId -> pending.addLast(childPartId) }
		}
		return collected
	}

	/**
	 * Builds the combined part preview for [partThumbnailFor]. Gathers the part subtree's drawables in the
	 * model's back-to-front order (the [PuppetModel.drawables] base order), unions their model bounds, and
	 * blits each cached crop into its mapped (Y-flipped) rectangle with straight-alpha over-compositing.
	 *
	 * @param PartId partId The part to preview.
	 * @return ImageBitmap? The composite, or null when no member drawable has a crop.
	 */
	private fun buildPartThumbnail(partId: PartId): ImageBitmap? {
		val subtree = partSubtree(partId)
		var unionMinX = Float.POSITIVE_INFINITY
		var unionMaxX = Float.NEGATIVE_INFINITY
		var unionMinY = Float.POSITIVE_INFINITY
		var unionMaxY = Float.NEGATIVE_INFINITY
		// Members in PuppetModel.drawables order, which is the parts-tree back-to-front paint order, so a
		// later entry paints in front. Keep only those with both a crop and bounds (so untextured / mesh-less
		// layers neither inflate the union nor leave a gap).
		val members = ArrayList<Pair<RawCrop, ModelBounds>>()
		for (drawable in currentDrawables) {
			val owningPartId = currentDrawableOwner[drawable.id] ?: continue
			if (owningPartId !in subtree) {
				continue
			}
			val crop = croppedFor(drawable.id) ?: continue
			val bounds = modelBoundsOf(drawable.id) ?: continue
			members += crop to bounds
			if (bounds.minX < unionMinX) {
				unionMinX = bounds.minX
			}
			if (bounds.maxX > unionMaxX) {
				unionMaxX = bounds.maxX
			}
			if (bounds.minY < unionMinY) {
				unionMinY = bounds.minY
			}
			if (bounds.maxY > unionMaxY) {
				unionMaxY = bounds.maxY
			}
		}
		if (members.isEmpty()) {
			return null
		}
		val unionWidth = unionMaxX - unionMinX
		val unionHeight = unionMaxY - unionMinY
		if (unionWidth <= 0f || unionHeight <= 0f) {
			return null
		}
		val scale = PART_COMPOSITE_MAX_DIMENSION.toFloat() / max(unionWidth, unionHeight)
		val compositeWidth = (unionWidth * scale).roundToInt().coerceIn(1, PART_COMPOSITE_MAX_DIMENSION)
		val compositeHeight = (unionHeight * scale).roundToInt().coerceIn(1, PART_COMPOSITE_MAX_DIMENSION)
		val pixels = ByteArray(compositeWidth * compositeHeight * 4)
		for ((crop, bounds) in members) {
			// Map model bounds straight into composite pixels: Cubism's rest-pose mesh Y runs top-down, the
			// same direction as the atlas crop's rows (which display upright as the single-drawable preview),
			// so no Y flip - a smaller model Y is nearer the top. Flipping here would invert each layer's
			// placement (frills jump to the top, stacked eye parts jumble) while leaving symmetric parts fine.
			val left = (bounds.minX - unionMinX) / unionWidth * compositeWidth
			val right = (bounds.maxX - unionMinX) / unionWidth * compositeWidth
			val top = (bounds.minY - unionMinY) / unionHeight * compositeHeight
			val bottom = (bounds.maxY - unionMinY) / unionHeight * compositeHeight
			blitOver(pixels, compositeWidth, compositeHeight, crop, left, top, right, bottom)
		}
		return rawToImage(RawCrop(pixels, compositeWidth, compositeHeight))
	}

	/**
	 * Alpha-over-composites a [crop] into the destination buffer over the floating-point rectangle
	 * [leftEdge, rightEdge) x [topEdge, bottomEdge), nearest-sampling the source and clamping to the buffer.
	 * Straight-alpha over: outA = sa + da(1-sa); outRgb = (srcRgb·sa + dstRgb·da(1-sa)) / outA.
	 *
	 * @param ByteArray destination The composite buffer (straight RGBA, top row first).
	 * @param Int destinationWidth The buffer width in pixels.
	 * @param Int destinationHeight The buffer height in pixels.
	 * @param RawCrop crop The source crop to place.
	 * @param Float leftEdge The destination rectangle's left edge, in pixels.
	 * @param Float topEdge The destination rectangle's top edge, in pixels.
	 * @param Float rightEdge The destination rectangle's right edge, in pixels.
	 * @param Float bottomEdge The destination rectangle's bottom edge, in pixels.
	 */
	private fun blitOver(
		destination: ByteArray,
		destinationWidth: Int,
		destinationHeight: Int,
		crop: RawCrop,
		leftEdge: Float,
		topEdge: Float,
		rightEdge: Float,
		bottomEdge: Float,
	) {
		val spanWidth = rightEdge - leftEdge
		val spanHeight = bottomEdge - topEdge
		if (spanWidth <= 0f || spanHeight <= 0f) {
			return
		}
		val firstColumn = floor(leftEdge).toInt().coerceIn(0, destinationWidth)
		val lastColumn = ceil(rightEdge).toInt().coerceIn(0, destinationWidth)
		val firstRow = floor(topEdge).toInt().coerceIn(0, destinationHeight)
		val lastRow = ceil(bottomEdge).toInt().coerceIn(0, destinationHeight)
		var destRow = firstRow
		while (destRow < lastRow) {
			val normalizedY = (destRow + 0.5f - topEdge) / spanHeight
			val sourceRow = (normalizedY * crop.height).toInt().coerceIn(0, crop.height - 1)
			var destColumn = firstColumn
			while (destColumn < lastColumn) {
				val normalizedX = (destColumn + 0.5f - leftEdge) / spanWidth
				val sourceColumn = (normalizedX * crop.width).toInt().coerceIn(0, crop.width - 1)
				val sourceOffset = (sourceRow * crop.width + sourceColumn) * 4
				val sourceAlpha = crop.rgba[sourceOffset + 3].toInt() and 0xFF
				if (sourceAlpha != 0) {
					overComposite(destination, (destRow * destinationWidth + destColumn) * 4, crop.rgba, sourceOffset, sourceAlpha)
				}
				destColumn += 1
			}
			destRow += 1
		}
	}

	/**
	 * Over-composites one source pixel onto one destination pixel, both straight-alpha RGBA.
	 *
	 * @param ByteArray destination The destination buffer.
	 * @param Int destinationOffset The destination pixel's byte offset.
	 * @param ByteArray source The source buffer.
	 * @param Int sourceOffset The source pixel's byte offset.
	 * @param Int sourceAlpha The source pixel's alpha byte (0..255), already known non-zero.
	 */
	private fun overComposite(destination: ByteArray, destinationOffset: Int, source: ByteArray, sourceOffset: Int, sourceAlpha: Int) {
		val sourceAlphaFraction = sourceAlpha / 255f
		val destinationAlphaFraction = (destination[destinationOffset + 3].toInt() and 0xFF) / 255f
		val outAlphaFraction = sourceAlphaFraction + destinationAlphaFraction * (1f - sourceAlphaFraction)
		if (outAlphaFraction <= 0f) {
			return
		}
		var channelIndex = 0
		while (channelIndex < 3) {
			val sourceChannel = (source[sourceOffset + channelIndex].toInt() and 0xFF) / 255f
			val destinationChannel = (destination[destinationOffset + channelIndex].toInt() and 0xFF) / 255f
			val outChannel = (sourceChannel * sourceAlphaFraction + destinationChannel * destinationAlphaFraction * (1f - sourceAlphaFraction)) / outAlphaFraction
			destination[destinationOffset + channelIndex] = (outChannel * 255f).roundToInt().coerceIn(0, 255).toByte()
			channelIndex += 1
		}
		destination[destinationOffset + 3] = (outAlphaFraction * 255f).roundToInt().coerceIn(0, 255).toByte()
	}

	/**
	 * Wraps a raw straight-alpha RGBA crop as a Compose bitmap. The bytes are UNPREMUL (straight) so a
	 * transparent texel's leftover matte RGB does not ghost through additively.
	 *
	 * @param RawCrop crop The raw crop to wrap.
	 * @return ImageBitmap The Compose bitmap.
	 */
	private fun rawToImage(crop: RawCrop): ImageBitmap = rgbaToImageBitmap(crop.rgba, crop.width, crop.height, RgbaAlphaType.Straight)
}
