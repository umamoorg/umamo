package org.umamo.format.atlas

import org.umamo.format.art.DEFAULT_ALPHA_THRESHOLD
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.raster.RasterImage

/*
 * The atlas packer: source-layer rasters in, packed pages plus a placement report out.
 *
 * Because the 2D source art hands us each piece's opaque silhouette before packing, the atlas can
 * be generated rather than authored by hand and reconciled.  The packing REPORT is deliberately
 * not a placement transform: it records integer page rectangles, the trim each tile came from, and
 * the quarter turn applied, which is what a packer knows.  Lowering that onto a placement transform
 * belongs with whoever owns the placement model.
 *
 * Its own package rather than sitting beside analyzeAlpha in `art`, because the packer reads layers
 * and writes flat images: `art` deliberately does not depend on `raster` (see RasterImage's
 * analyzeAlpha extension), and this is the node that legitimately spans both.
 */

/**
 * Extra area a tile's placement must keep clear, in raster-local pixels.
 *
 * The pixels alone do not bound what samples a tile: an art mesh rings OUTSIDE the opaque region
 * (and routinely outside the raster itself), so a pack spaced by opaque bounds alone puts one
 * tile's mesh footprint over its neighbour's art.  A reserve carries that reach into the pack: the
 * tile's reservation becomes the union of its opaque trim and this rect, so anything inside it
 * lands on the tile's own transparent margin rather than on a neighbour.
 *
 * Coordinates are raster-local like a trim's, edges exclusive on the right and bottom, and MAY be
 * negative or exceed the raster - a mesh's reach is not confined to its art.
 *
 * @property Int left   The reserved rect's left edge.
 * @property Int top    The reserved rect's top edge.
 * @property Int right  The reserved rect's right edge, exclusive.
 * @property Int bottom The reserved rect's bottom edge, exclusive.
 */
public data class AtlasPackReserve(
	val left: Int,
	val top: Int,
	val right: Int,
	val bottom: Int,
) {
	init {
		require(right >= left && bottom >= top) { "reserve must not be inverted: $left..$right x $top..$bottom" }
	}
}

/**
 * One tile to pack: a stable key plus the straight-alpha pixels to place.
 *
 * Pixels are taken by reference and never mutated, matching how the flat-raster adapter hands a
 * buffer through; the caller must not mutate [rgba] while a pack is running.
 *
 * A plain class, not a data class: it wraps a pixel buffer whose generated structural equality would
 * deep-compare on every call, the same reason [LayerRaster] and [RasterImage] are plain classes.
 *
 * @property String            key     The tile's stable identity, unique within one pack.
 * @property Int               width   The raster width in pixels.
 * @property Int               height  The raster height in pixels.
 * @property ByteArray         rgba    RGBA8888 pixels, straight alpha, row-major from the top row.
 * @property AtlasPackReserve? reserve Extra area to keep clear around this tile (a mesh's reach),
 *                                     unioned with the opaque trim; null reserves the trim alone.
 */
public class AtlasPackItem(
	public val key: String,
	public val width: Int,
	public val height: Int,
	public val rgba: ByteArray,
	public val reserve: AtlasPackReserve? = null,
) {
	init {
		require(width >= 0 && height >= 0) { "tile '$key' has negative dimensions: $width x $height" }
		require(rgba.size.toLong() == width.toLong() * height.toLong() * 4L) {
			"tile '$key' rgba size ${rgba.size} does not match $width x $height x 4"
		}
	}
}

/**
 * How to pack.
 *
 * @property Int     maxPageSize         The square page side pages are packed against, in pixels.
 * @property Int     gutter              Transparent spacing reserved around every tile and at the page border.
 * @property Int     extrude             How many pixels of each tile's edge colour are replicated into the gutter.
 * @property Boolean allowRotation       Whether a tile may be quarter-turned to pack tighter.
 * @property Boolean powerOfTwoPages     Whether final page dimensions round up to a power of two.
 * @property Boolean squarePages         Whether final pages are square, as every corpus atlas is.
 * @property Boolean shrinkPages         Whether pages size themselves to the document instead of staying at [maxPageSize].
 * @property Int     alphaThreshold      Minimum alpha byte (1..255) for a pixel to count as opaque when trimming.
 * @property Int     minimumOpaquePixels Tiles with fewer opaque pixels than this are reported rather than packed.
 */
public data class AtlasPackOptions(
	val maxPageSize: Int = 4096,
	val gutter: Int = 2,
	val extrude: Int = 2,
	val allowRotation: Boolean = false,
	val powerOfTwoPages: Boolean = true,
	val squarePages: Boolean = true,
	val shrinkPages: Boolean = true,
	val alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
	val minimumOpaquePixels: Int = 1,
)

/**
 * Where one tile landed, in whole page pixels.
 *
 * [pageX] / [pageY] locate the tile itself, not its gutter footprint.  The trim fields say which part
 * of the source raster was packed: a tile is trimmed to its opaque bounds, so the source pixel
 * (trimLeft + tileX, trimTop + tileY) is what sits at the placed position.  A [quarterTurns] of 1
 * means the tile was rotated one counter-clockwise quarter turn in the page's y-down frame, which
 * swaps its on-page width and height.
 *
 * @property String key         The packed tile's key.
 * @property Int    pageIndex   The page it landed on.
 * @property Int    pageX       The tile's left edge on the page.
 * @property Int    pageY       The tile's top edge on the page.
 * @property Int    trimLeft    The opaque bounds' left edge within the source raster.
 * @property Int    trimTop     The opaque bounds' top edge within the source raster.
 * @property Int    trimWidth   The opaque bounds' width, before any rotation.
 * @property Int    trimHeight  The opaque bounds' height, before any rotation.
 * @property Int    quarterTurns 0 for upright, 1 for one counter-clockwise quarter turn.
 */
public data class AtlasPackPlacement(
	val key: String,
	val pageIndex: Int,
	val pageX: Int,
	val pageY: Int,
	val trimLeft: Int,
	val trimTop: Int,
	val trimWidth: Int,
	val trimHeight: Int,
	val quarterTurns: Int,
) {
	/** The tile's width on the page, after any quarter turn. */
	public val pageWidth: Int
		get() = if (quarterTurns == 0) trimWidth else trimHeight

	/** The tile's height on the page, after any quarter turn. */
	public val pageHeight: Int
		get() = if (quarterTurns == 0) trimHeight else trimWidth
}

/** Why a tile was not packed. */
public enum class AtlasPackSkipReason {
	/** Nothing in the raster met the alpha threshold - an empty layer, or a transparent placeholder. */
	NoOpaquePixels,

	/** The tile has opaque pixels but fewer than the configured minimum. */
	BelowMinimumCoverage,

	/** The trimmed tile plus its gutter does not fit a page even on its own. */
	LargerThanPage,
}

/**
 * One tile that was not packed, and why.
 *
 * @property String              key    The tile's key.
 * @property AtlasPackSkipReason reason Why it was left out.
 */
public data class AtlasPackSkip(
	val key: String,
	val reason: AtlasPackSkipReason,
)

/**
 * The packing outcome: the composed pages plus a full account of every tile that went in.
 *
 * [placements] and [skipped] partition the input keys exactly - every tile handed to [packAtlas]
 * appears in one of them.  That is the contract that keeps a plausible-looking page from quietly
 * having lost three layers, which is this stage's characteristic failure.
 *
 * A plain class, not a data class: [pages] holds pixel buffers.
 *
 * @property List pages      The composed atlas pages, in page-index order.
 * @property List placements Where each packed tile landed, in the caller's input order.
 * @property List skipped    The tiles left out, in the caller's input order.
 */
public class AtlasPackResult(
	public val pages: List<RasterImage>,
	public val placements: List<AtlasPackPlacement>,
	public val skipped: List<AtlasPackSkip>,
) {
	/**
	 * The fraction of a page's area covered by packed tile pixels, ignoring gutters and extrusion.
	 *
	 * @param Int pageIndex The page to measure.
	 * @return Float The covered fraction, in 0.0..1.0.
	 */
	public fun pageOccupancy(pageIndex: Int): Float {
		val page = pages[pageIndex]
		var covered = 0L
		for (placement in placements) {
			if (placement.pageIndex == pageIndex) {
				covered += placement.trimWidth.toLong() * placement.trimHeight.toLong()
			}
		}
		return covered.toFloat() / (page.width.toFloat() * page.height.toFloat())
	}
}

/**
 * Packs tiles into atlas pages and composes the page pixels.
 *
 * Each tile is trimmed to its opaque bounds (lossless at the default threshold - no antialiased edge
 * pixel is cut), reserved with a gutter on every side, placed by the MaxRects packer, blitted, and
 * extruded into its gutter.  Pages are packed against [AtlasPackOptions.maxPageSize] and then cropped
 * to what was used, so a document that needs a 512 page gets one rather than a mostly-empty 4096.
 *
 * The result is DETERMINISTIC: tiles are ordered by descending max side, then descending area, then
 * key, so the caller's input order cannot change the packing.  Repacking an unchanged set therefore
 * reproduces it exactly, which is what makes a repack a safe operation rather than a churn source.
 *
 * @param List             items   The tiles to pack; keys must be unique.
 * @param AtlasPackOptions options The packing policy.
 * @return AtlasPackResult The composed pages, the placements, and the tiles left out.
 */
public fun packAtlas(
	items: List<AtlasPackItem>,
	options: AtlasPackOptions = AtlasPackOptions(),
): AtlasPackResult {
	require(options.maxPageSize > 0) { "maxPageSize must be positive: ${options.maxPageSize}" }
	require(options.gutter >= 0) { "gutter must be non-negative: ${options.gutter}" }
	require(options.extrude in 0..options.gutter) {
		"extrude must be in 0..gutter (${options.gutter}) so no tile's extrusion reaches another: ${options.extrude}"
	}
	require(options.alphaThreshold in 1..255) { "alphaThreshold must be in 1..255: ${options.alphaThreshold}" }
	require(options.minimumOpaquePixels >= 0) {
		"minimumOpaquePixels must be non-negative: ${options.minimumOpaquePixels}"
	}
	require(items.distinctBy { item -> item.key }.size == items.size) {
		"atlas pack item keys must be unique; duplicates make the packing order ambiguous"
	}
	require(!options.allowRotation || items.none { item -> item.reserve != null }) {
		"reserves under rotation are not supported yet: a quarter turn moves the trim's offset inside its reservation"
	}

	// Trim first: a tile's footprint is its opaque bounds - widened to any caller reserve, since a
	// mesh's reach must land on the tile's own margin - plus the gutter, and a tile with nothing
	// opaque never reaches the packer at all.
	val trims = arrayOfNulls<LayerBounds>(items.size)
	val reserves = arrayOfNulls<LayerBounds>(items.size)
	val skipped = ArrayList<AtlasPackSkip>()
	val requests = ArrayList<RectPackRequest>(items.size)
	for ((itemIndex, item) in items.withIndex()) {
		val analysis = analyzeAlpha(item.width, item.height, item.rgba, options.alphaThreshold)
		if (analysis == null) {
			skipped.add(AtlasPackSkip(item.key, AtlasPackSkipReason.NoOpaquePixels))
			continue
		}
		if (analysis.opaquePixelCount < options.minimumOpaquePixels) {
			skipped.add(AtlasPackSkip(item.key, AtlasPackSkipReason.BelowMinimumCoverage))
			continue
		}
		val trim = analysis.opaqueBounds
		val reserveLeft = minOf(trim.left, item.reserve?.left ?: trim.left)
		val reserveTop = minOf(trim.top, item.reserve?.top ?: trim.top)
		val reserveRight = maxOf(trim.left + trim.width, item.reserve?.right ?: (trim.left + trim.width))
		val reserveBottom = maxOf(trim.top + trim.height, item.reserve?.bottom ?: (trim.top + trim.height))
		trims[itemIndex] = trim
		reserves[itemIndex] = LayerBounds(reserveLeft, reserveTop, reserveRight - reserveLeft, reserveBottom - reserveTop)
		requests.add(
			RectPackRequest(
				itemIndex = itemIndex,
				width = reserveRight - reserveLeft + 2 * options.gutter,
				height = reserveBottom - reserveTop + 2 * options.gutter,
			),
		)
	}

	requests.sortWith(
		compareByDescending<RectPackRequest> { request -> maxOf(request.width, request.height) }
			.thenByDescending { request -> request.width.toLong() * request.height.toLong() }
			.thenBy { request -> items[request.itemIndex].key },
	)

	val packingSide = choosePackingSide(requests, options)
	val layout = packRects(requests, packingSide, options.allowRotation)
	for (itemIndex in layout.oversizedItemIndices) {
		skipped.add(AtlasPackSkip(items[itemIndex].key, AtlasPackSkipReason.LargerThanPage))
	}

	val pageWidths = IntArray(layout.usedWidths.size)
	val pageHeights = IntArray(layout.usedHeights.size)
	for (pageIndex in layout.usedWidths.indices) {
		val sizes = finalPageSize(layout.usedWidths[pageIndex], layout.usedHeights[pageIndex], packingSide, options)
		pageWidths[pageIndex] = sizes.first
		pageHeights[pageIndex] = sizes.second
	}

	val placementByItemIndex = arrayOfNulls<AtlasPackPlacement>(items.size)
	for (slot in layout.slots) {
		val item = items[slot.itemIndex]
		val trim = checkNotNull(trims[slot.itemIndex]) { "packed tile '${item.key}' has no trim" }
		val reserve = checkNotNull(reserves[slot.itemIndex]) { "packed tile '${item.key}' has no reserve" }
		// The placement records the TRIM's page position, exactly as before reserves existed: the
		// reservation only spaces neighbours further apart, so everything downstream of the placement
		// (the lowering, the composition, the derivation) is untouched by it.
		placementByItemIndex[slot.itemIndex] =
			AtlasPackPlacement(
				key = item.key,
				pageIndex = slot.pageIndex,
				pageX = slot.x + options.gutter + (trim.left - reserve.left),
				pageY = slot.y + options.gutter + (trim.top - reserve.top),
				trimLeft = trim.left,
				trimTop = trim.top,
				trimWidth = trim.width,
				trimHeight = trim.height,
				quarterTurns = if (slot.rotated) 1 else 0,
			)
	}
	val placements = placementByItemIndex.filterNotNull()

	val inputOrderByKey = items.withIndex().associate { (itemIndex, item) -> item.key to itemIndex }
	return AtlasPackResult(
		pages = composeAtlasPages(pageWidths, pageHeights, items, placements, options.extrude),
		placements = placements,
		skipped = skipped.sortedBy { skip -> inputOrderByKey.getValue(skip.key) },
	)
}

/**
 * Builds pack items from this document's layers, one per layer, keyed by its stable layer id.
 *
 * Layer kind, visibility, and sliver policy are deliberately the caller's: Phase B settled that the
 * analysis never drops a real art layer and the consumer decides what to ingest, and the same rule
 * applies here.  Pass [include] to apply a policy; the default packs every layer.
 *
 * A duplicate layer id (possible from the PSD name-and-order fallback, where no lyid is written) is
 * disambiguated with the layer's draw order rather than rejected, since the collision is an artifact
 * of a weak source key and not a caller mistake.
 *
 * @param Function include Which layers to pack; defaults to all of them.
 * @return List One pack item per included layer, in document draw order.
 */
public fun SourceArt.atlasPackItems(include: (SourceLayer) -> Boolean = { true }): List<AtlasPackItem> {
	val usedKeys = HashSet<String>()
	return layers.filter(include).map { layer ->
		var key = layer.id.raw
		if (!usedKeys.add(key)) {
			key = "${layer.id.raw}#${layer.order}"
			usedKeys.add(key)
		}
		AtlasPackItem(key, layer.raster.width, layer.raster.height, layer.raster.rgba)
	}
}

/**
 * Chooses the page side to pack against: the smallest one that still needs no extra page.
 *
 * Packing straight against [AtlasPackOptions.maxPageSize] and cropping afterwards is not enough,
 * because best-short-side-fit spreads a small document along the top of a huge page - the crop then
 * keeps a wide, shallow extent that squares up almost empty.  Packing against a page the document
 * roughly fills instead makes it stack, and the page count is the thing worth protecting: a smaller
 * page that costs an extra texture bind is a worse trade than a slightly emptier one.
 *
 * Only the rectangle geometry runs here - no pixels are touched - so the handful of trial packs is
 * cheap next to a single page composition.
 *
 * @param List             requests The footprints to be packed.
 * @param AtlasPackOptions options  The packing policy.
 * @return Int The page side to pack against.
 */
private fun choosePackingSide(requests: List<RectPackRequest>, options: AtlasPackOptions): Int {
	if (!options.shrinkPages || requests.isEmpty()) {
		return options.maxPageSize
	}
	val baseline = packRects(requests, options.maxPageSize, options.allowRotation)
	val fitting = requests.filter { request -> request.width <= options.maxPageSize && request.height <= options.maxPageSize }
	if (fitting.isEmpty()) {
		return options.maxPageSize
	}
	var side = roundUpToPowerOfTwo(fitting.maxOf { request -> maxOf(request.width, request.height) })
	while (side < options.maxPageSize) {
		val trial = packRects(requests, side, options.allowRotation)
		if (trial.usedWidths.size <= baseline.usedWidths.size &&
			trial.oversizedItemIndices.size == baseline.oversizedItemIndices.size
		) {
			return side
		}
		side = side shl 1
	}
	return options.maxPageSize
}

/**
 * Resolves one page's final dimensions from the extent the packer actually used.
 *
 * @param Int usedWidth    The rightmost footprint edge on the page, gutter included.
 * @param Int usedHeight   The lowest footprint edge on the page, gutter included.
 * @param Int packingSide  The page side the pack ran against.
 * @param AtlasPackOptions options The packing policy.
 * @return Pair The page width and height in pixels.
 */
private fun finalPageSize(usedWidth: Int, usedHeight: Int, packingSide: Int, options: AtlasPackOptions): Pair<Int, Int> {
	if (!options.shrinkPages) {
		return packingSide to packingSide
	}
	var width = usedWidth
	var height = usedHeight
	if (options.squarePages) {
		width = maxOf(width, height)
		height = width
	}
	if (options.powerOfTwoPages) {
		width = roundUpToPowerOfTwo(width)
		height = roundUpToPowerOfTwo(height)
	}
	return minOf(width, packingSide) to minOf(height, packingSide)
}

/**
 * Rounds a positive extent up to the next power of two.
 *
 * @param Int extent The extent in pixels; at least 1.
 * @return Int The smallest power of two that is at least [extent].
 */
private fun roundUpToPowerOfTwo(extent: Int): Int {
	var size = 1
	while (size < extent) {
		size = size shl 1
	}
	return size
}