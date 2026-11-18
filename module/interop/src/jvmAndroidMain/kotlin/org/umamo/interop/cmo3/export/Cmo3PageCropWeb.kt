package org.umamo.interop.cmo3.export

import org.umamo.format.art.analyzeAlpha
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayerSet
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.cropped
import org.umamo.interop.cmo3.mutableGraphListOf

/*
 * WHY THIS FILE EXISTS, AND WHY IT CUTS UP THE ATLAS
 *
 * A CMO3 is built around SOURCE ART: the editor decomposes an imported PSD/CLIP/KRA into a
 * CLayeredImage tree of per-layer images, and everything else (model images, atlas entries,
 * placements) hangs off that.  A .moc3 carries none of it - only the packed atlas pages and UVs.
 * So a MOC3-origin export has to FABRICATE a source document, and the only raw material available
 * is the packed page.  That is the whole reason this file slices each drawable's uv bounding
 * box back out of the atlas and presents the crop as if it were an imported layer.  None of that
 * cutting is inherent to the format; it is reconstruction of information the bake threw away.
 *
 * An artwork-origin document needs none of it: its tiles hold the real rasters, so those write as
 * real layers of their file's layered image through Cmo3SourceLayerWeb, and the crop path here
 * serves only the drawables that have no such art.  The page half (one atlas and one shared texture
 * per page) and the model-image web around a layer (modelImageOver) are shared by both.
 *
 * The cutting is NOT what makes a file render.  A third-party converter's CMO3 renders in the
 * official editor with NO source document at all (zero CLayeredImage / CLayer / CModelImage /
 * ModelImageEntry, drawables carrying only a CTextureInput_TextureAtlasRegion) - and it renders
 * despite geometry that is offset by a constant from Cubism's own golden file for the same model.
 * The web here exists for the editor's texture-atlas and mesh-edit VIEWS, which derive
 * mesh-over-texture placement from the model images; a single whole-page image cannot place
 * hundreds of drawables and drew every mesh at its assembled canvas position instead.  Do not
 * "simplify" by deleting the web: that shape was tested and the editor errors with an empty atlas.
 *
 * The output renders in the official editor and switches between layered-art and texture-atlas
 * display modes.  The one known failure is scale: modelF (~850 MB, several hundred layers) OOMs
 * the editor on the switch BACK to atlas mode.  Atlas mode recomposites the page out of the
 * materials, so peak memory tracks the total area of the crops cut below, not the page size - a uv
 * bounding box overlaps its neighbours, so that total can run to a multiple of the page.  Measure
 * it before concluding the editor is simply out of room.
 *
 * WHAT IT BUILDS, mirroring the official ATLAS-MODE shape (EricaTamamo,
 * isTextureInputModelImageMode=false): one CTextureAtlas + ONE SHARED GTexture2D per page that
 * every packed drawable on the page samples with atlas-frame UVs, plus a PER-DRAWABLE model-image
 * web - each drawable's texture patch cropped out of the page as its own CLayer + CModelImage +
 * ModelImageEntry, the entry carrying the patch's packing origin (materialLocalToAtlasTransform)
 * and the drawable's fitted atlas-to-canvas placement (atlasLocalToCanvasTransform).
 *
 * Every CModelImage carries the editor's layer-filter web (a CLayerSelector feeding a
 * CLayerFilter, connected through per-document shared FilterValue definitions) plus a filtered
 * image, a cached-image manager, and icon thumbnails - the official reader's custom deserializers
 * dereference these, so a null is a hard failure, not a cosmetic gap.  The filter definition
 * guids are editor-static constants (identical uuids in every corpus file).
 *
 * The synthetic source doc is the ATLAS PAGE's own frame, mirroring how an official document's
 * CLayeredImage carries the source PSD's size rather than the canvas
 * (ModelWithOffscreenPartClipping: a 500x500 doc inside a 1000x2000 canvas - Erica's doc merely
 * happens to equal its canvas).  Each layer's boundsOnImageDoc carries the placement translation
 * as its origin (equal to the owning CModelImage's _materialLocalToCanvasTransform translation on
 * all 892 corpus layers) and the imageResource dims as its size (also 892 of 892); official docs
 * sit origin-aligned on the canvas, so their doc rects and canvas placements coincide, and our
 * patch layers keep that equality by writing the canvas placement.
 *
 * WHERE THE PACKING'S SCALE AND ROTATION LIVE: not on the ModelImageEntry.  Each crop is an
 * axis-aligned rect of page pixels lifted at scale 1, so position-only IS the honest
 * materialLocalToAtlasTransform for it; whatever the packer did to the art - rotate, mirror, scale
 * - is carried by the page fit, which both atlasLocalToCanvasTransform and the model image's
 * _materialLocalToCanvasTransform compose (see fitAtlasPageToCanvasTransform, and note the
 * mean-centering there is what lets a rotated packing survive the fit at all).
 *
 * A patch rect starts as a uv BOUNDING BOX, so unless the mesh is itself a page-aligned rectangle
 * the rect also spans page pixels the packer nested into the leftover corners - a neighbour's
 * artwork.  The crop is therefore masked to the mesh's own triangles (coverageMaskOf) rather than
 * copied verbatim, then TRIMMED to the masked pixels' opaque bounds, with the packing origin,
 * placement, and layer bounds all derived from the trimmed rect together.  Un-masked, the editor
 * takes the foreign pixels for this drawable's artwork - they follow the patch when the atlas is
 * rearranged, and a repack stamps them back into the page.
 *
 * Remaining deliberate simplification, validated by the official-editor gate: the cached images
 * are the raw resources themselves (SCALE_1, nothing prerendered; their
 * transformRawImageToCachedImage records the 64-aligned padding fraction like every corpus
 * cache entry).  The icons are real: each layer's, model image's, and drawable's is its art fitted
 * into the square the way the editor writes it (Cmo3Icons).
 */
internal object Cmo3PageCropWeb {
	/**
	 * What makes two drawables share one CModelImage: the same crop rect and the same mesh.  The
	 * mesh is part of the identity because it masks the crop - two drawables over one rect with
	 * different topology no longer produce the same pixels.  The fitted PLACEMENT is deliberately
	 * NOT part of the key, and that is only safe because Cmo3TwinColocation has already given every
	 * different-placement twin its slot representative's canvas mesh: official twins are always
	 * CO-LOCATED (160 of 160 uv-identical corpus groups share one canvas mesh), the shared image
	 * carries a single canvas placement, and duplicating a material over one slot instead makes
	 * the editor's atlas view - a recomposite of the materials - stack the shared art's alpha
	 * (2a - a*a per extra copy), rendering the atlas more opaque than source-image mode.
	 */
	private class PatchWebKey(
		private val patchRect: IntArray,
		private val uvs: FloatArray,
		private val indices: IntArray,
	) {
		override fun equals(other: Any?): Boolean =
			other is PatchWebKey &&
				patchRect.contentEquals(other.patchRect) &&
				uvs.contentEquals(other.uvs) &&
				indices.contentEquals(other.indices)

		override fun hashCode(): Int {
			var result = patchRect.contentHashCode()
			result = 31 * result + uvs.contentHashCode()
			result = 31 * result + indices.contentHashCode()

			return result
		}
	}

	/**
	 * The material-local point whose image under [pageFit] is the given canvas point.
	 *
	 * This back-solves the declared packing origin from the snapped integer placement so that
	 * atlasLocalToCanvasTransform composed with materialLocalToAtlasTransform reproduces
	 * _materialLocalToCanvasTransform exactly - the official files' structure (their packing
	 * origins are fractional, carrying the complement of the fit's fractional translation).
	 *
	 * @param CAffine pageFit The page-to-canvas fit.
	 * @param Float   canvasX The snapped placement x.
	 * @param Float   canvasY The snapped placement y.
	 * @return FloatArray The (x, y) material-local origin, or null when the fit is degenerate.
	 */
	private fun solvePageFitFor(pageFit: CAffine, canvasX: Float, canvasY: Float): FloatArray? {
		val determinant = pageFit.m00.toDouble() * pageFit.m11 - pageFit.m01.toDouble() * pageFit.m10

		if (determinant == 0.0) {
			return null
		}

		val deltaX = canvasX.toDouble() - pageFit.m02
		val deltaY = canvasY.toDouble() - pageFit.m12

		return floatArrayOf(
			((pageFit.m11 * deltaX - pageFit.m01 * deltaY) / determinant).toFloat(),
			((pageFit.m00 * deltaY - pageFit.m10 * deltaX) / determinant).toFloat(),
		)
	}

	/**
	 * The model image's material-local-to-canvas placement: the page fit composed with the patch
	 * origin, so patch pixel (0,0) maps to the canvas point the page fit sends (x0, y0) to.
	 *
	 * CMO3: CModelImage field _materialLocalToCanvasTransform (official layers carry their canvas
	 * origin here - translate(144, 222) in ModelWithOffscreenPartClipping).
	 *
	 * @param CAffine pageFit The atlas-page-to-canvas fit.
	 * @param Int     x0      The patch origin x on the page.
	 * @param Int     y0      The patch origin y on the page.
	 * @return CAffine The material-local placement.
	 */
	private fun materialLocalToCanvas(pageFit: CAffine, x0: Int, y0: Int): CAffine =
		pageFit.copyAffine().apply {
			m02 = pageFit.m00 * x0 + pageFit.m01 * y0 + pageFit.m02
			m12 = pageFit.m10 * x0 + pageFit.m11 * y0 + pageFit.m12
		}

	/**
	 * How far kept coverage grows past the mesh's own triangles, in page pixels.
	 *
	 * Cutting exactly on the outermost edge would fringe the silhouette: the drawable's UVs reach
	 * that edge, so a bilinear tap there blends against the transparent pixel immediately outside.
	 * Two pixels covers a bilinear tap plus a mip level while staying well inside the gutter a
	 * packer leaves between neighbouring patches.
	 */
	private const val COVERAGE_BLEED_MARGIN = 2

	/**
	 * Which pixels of a patch rect the drawable's own mesh covers, grown by [COVERAGE_BLEED_MARGIN].
	 *
	 * A patch rect is the mesh's axis-aligned uv bounding box, so for any mesh that is not itself a
	 * page-aligned rectangle the rect also spans page pixels belonging to whatever the packer nested
	 * into the leftover corners.  Copying the rect verbatim hands those foreign pixels to this
	 * drawable's CModelImage, which the editor treats as the drawable's own artwork - it travels
	 * with the patch when the atlas is rearranged and gets stamped back into the page on a repack.
	 * Masking to the triangles keeps the crop rect (so every placement transform is unchanged) while
	 * dropping pixels the mesh never samples.
	 *
	 * Rasterization is CONSERVATIVE - a pixel counts as covered when its square overlaps a triangle
	 * at all, not when its center happens to land inside one.  Cubism meshes carry long sliver
	 * triangles along a silhouette, and center sampling drops the ones thinner than a pixel.
	 *
	 * @param FloatArray uvs        Interleaved atlas-frame uvs.
	 * @param IntArray   indices    Triangle indices, three per triangle.
	 * @param Int        pageWidth  The page's pixel width.
	 * @param Int        pageHeight The page's pixel height.
	 * @param Int        patchX0    The patch rect's origin x on the page.
	 * @param Int        patchY0    The patch rect's origin y on the page.
	 * @param Int        cropWidth  The patch rect's width.
	 * @param Int        cropHeight The patch rect's height.
	 * @return BooleanArray One flag per crop pixel in row-major order, or null when the mesh carries
	 *         no triangles to mask with (the whole rect is then kept).
	 */
	private fun coverageMaskOf(
		uvs: FloatArray,
		indices: IntArray,
		pageWidth: Int,
		pageHeight: Int,
		patchX0: Int,
		patchY0: Int,
		cropWidth: Int,
		cropHeight: Int,
	): BooleanArray? {
		if (indices.size < 3) {
			return null
		}

		val vertexCount = uvs.size / 2
		val covered = BooleanArray(cropWidth * cropHeight)
		var triangleStart = 0

		while (triangleStart + 2 < indices.size) {
			val indexA = indices[triangleStart]
			val indexB = indices[triangleStart + 1]
			val indexC = indices[triangleStart + 2]
			triangleStart += 3

			if (indexA !in 0 until vertexCount || indexB !in 0 until vertexCount || indexC !in 0 until vertexCount) {
				continue
			}

			val cornerAx = uvs[2 * indexA].toDouble() * pageWidth - patchX0
			val cornerAy = uvs[2 * indexA + 1].toDouble() * pageHeight - patchY0
			val cornerBx = uvs[2 * indexB].toDouble() * pageWidth - patchX0
			val cornerBy = uvs[2 * indexB + 1].toDouble() * pageHeight - patchY0
			val cornerCx = uvs[2 * indexC].toDouble() * pageWidth - patchX0
			val cornerCy = uvs[2 * indexC + 1].toDouble() * pageHeight - patchY0
			val doubledArea = (cornerBx - cornerAx) * (cornerCy - cornerAy) - (cornerCx - cornerAx) * (cornerBy - cornerAy)

			if (!doubledArea.isFinite() || doubledArea == 0.0) {
				continue
			}

			// Normalize the winding so "inside" is uniformly a non-negative edge value.
			val winding = if (doubledArea > 0.0) 1.0 else -1.0
			// One extra pixel each way: the conservative test below reaches half a pixel past the
			// triangle, and a bounding box rounded inward would clip that reach off.
			val firstColumn = kotlin.math.floor(minOf(cornerAx, cornerBx, cornerCx)).toInt().coerceIn(0, cropWidth - 1) - 1
			val lastColumn = kotlin.math.ceil(maxOf(cornerAx, cornerBx, cornerCx)).toInt().coerceIn(0, cropWidth - 1) + 1
			val firstRow = kotlin.math.floor(minOf(cornerAy, cornerBy, cornerCy)).toInt().coerceIn(0, cropHeight - 1) - 1
			val lastRow = kotlin.math.ceil(maxOf(cornerAy, cornerBy, cornerCy)).toInt().coerceIn(0, cropHeight - 1) + 1

			for (rowIndex in maxOf(0, firstRow)..minOf(cropHeight - 1, lastRow)) {
				val pointY = rowIndex + 0.5

				for (columnIndex in maxOf(0, firstColumn)..minOf(cropWidth - 1, lastColumn)) {
					val flatIndex = rowIndex * cropWidth + columnIndex

					if (covered[flatIndex]) {
						continue
					}

					val pointX = columnIndex + 0.5

					if (overlapsEdge(cornerAx, cornerAy, cornerBx, cornerBy, pointX, pointY, winding) &&
						overlapsEdge(cornerBx, cornerBy, cornerCx, cornerCy, pointX, pointY, winding) &&
						overlapsEdge(cornerCx, cornerCy, cornerAx, cornerAy, pointX, pointY, winding)
					) {
						covered[flatIndex] = true
					}
				}
			}
		}

		return dilate(covered, cropWidth, cropHeight, COVERAGE_BLEED_MARGIN)
	}

	/**
	 * Whether a pixel's unit square reaches the inside half-plane of one triangle edge.
	 *
	 * The edge value at the pixel center varies by at most half the sum of the edge normal's
	 * components across the square, so adding that slack turns a center test into a square-overlap
	 * test - which is what keeps sub-pixel slivers from vanishing.
	 *
	 * @param Double fromX   Edge start x, in crop-local pixels.
	 * @param Double fromY   Edge start y.
	 * @param Double toX     Edge end x.
	 * @param Double toY     Edge end y.
	 * @param Double pointX  The pixel center's x.
	 * @param Double pointY  The pixel center's y.
	 * @param Double winding +1 when the triangle winds counter-clockwise, -1 when clockwise.
	 * @return Boolean True when the pixel square is not fully outside this edge.
	 */
	private fun overlapsEdge(
		fromX: Double,
		fromY: Double,
		toX: Double,
		toY: Double,
		pointX: Double,
		pointY: Double,
		winding: Double,
	): Boolean {
		val edgeX = toX - fromX
		val edgeY = toY - fromY
		val edgeValue = winding * (edgeX * (pointY - fromY) - edgeY * (pointX - fromX))

		return edgeValue + 0.5 * (kotlin.math.abs(edgeX) + kotlin.math.abs(edgeY)) >= 0.0
	}

	/**
	 * Grows a coverage mask by [margin] pixels, as two linear-time passes over a sliding window.
	 *
	 * @param BooleanArray covered The raw per-pixel coverage, row-major.
	 * @param Int          width   The mask width.
	 * @param Int          height  The mask height.
	 * @param Int          margin  How many pixels to grow by.
	 * @return BooleanArray The grown mask ([covered] itself when the margin is zero).
	 */
	private fun dilate(covered: BooleanArray, width: Int, height: Int, margin: Int): BooleanArray {
		if (margin <= 0) {
			return covered
		}

		val grownAcross = BooleanArray(covered.size)

		for (rowIndex in 0 until height) {
			val rowStart = rowIndex * width
			var windowCount = 0

			for (columnIndex in 0..minOf(margin, width - 1)) {
				if (covered[rowStart + columnIndex]) {
					windowCount += 1
				}
			}

			for (columnIndex in 0 until width) {
				grownAcross[rowStart + columnIndex] = windowCount > 0
				val leavingColumn = columnIndex - margin
				val enteringColumn = columnIndex + margin + 1

				if (leavingColumn >= 0 && covered[rowStart + leavingColumn]) {
					windowCount -= 1
				}

				if (enteringColumn < width && covered[rowStart + enteringColumn]) {
					windowCount += 1
				}
			}
		}

		val grown = BooleanArray(covered.size)

		for (columnIndex in 0 until width) {
			var windowCount = 0

			for (rowIndex in 0..minOf(margin, height - 1)) {
				if (grownAcross[rowIndex * width + columnIndex]) {
					windowCount += 1
				}
			}

			for (rowIndex in 0 until height) {
				grown[rowIndex * width + columnIndex] = windowCount > 0
				val leavingRow = rowIndex - margin
				val enteringRow = rowIndex + margin + 1

				if (leavingRow >= 0 && grownAcross[leavingRow * width + columnIndex]) {
					windowCount -= 1
				}

				if (enteringRow < height && grownAcross[enteringRow * width + columnIndex]) {
					windowCount += 1
				}
			}
		}

		return grown
	}

	/**
	 * Copies the patch rect out of a decoded page, clearing pixels outside [coverage].
	 *
	 * @param RasterImage  decodedPage The decoded page pixels.
	 * @param Int          x0          Patch origin x.
	 * @param Int          y0          Patch origin y.
	 * @param Int          cropWidth   Patch width.
	 * @param Int          cropHeight  Patch height.
	 * @param BooleanArray coverage    The mesh coverage mask, or null to keep the whole rect.
	 * @return RasterImage The masked crop pixels.
	 */
	private fun maskedCropOf(
		decodedPage: RasterImage,
		x0: Int,
		y0: Int,
		cropWidth: Int,
		cropHeight: Int,
		coverage: BooleanArray?,
	): RasterImage {
		val cropRgba = ByteArray(cropWidth * cropHeight * 4)

		for (rowIndex in 0 until cropHeight) {
			val sourceOffset = ((y0 + rowIndex) * decodedPage.width + x0) * 4
			val targetOffset = rowIndex * cropWidth * 4
			decodedPage.rgba.copyInto(cropRgba, targetOffset, sourceOffset, sourceOffset + cropWidth * 4)

			if (coverage == null) {
				continue
			}

			for (columnIndex in 0 until cropWidth) {
				if (coverage[rowIndex * cropWidth + columnIndex]) {
					continue
				}

				val pixelOffset = targetOffset + columnIndex * 4
				cropRgba.fill(0, pixelOffset, pixelOffset + 4)
			}
		}

		return RasterImage(cropWidth, cropHeight, cropRgba)
	}

	/**
	 * The crop web's one model-image group and the two lists the pages append to, minted only once a
	 * drawable actually takes the crop path.
	 *
	 * @property CModelImageGroup group               The group.
	 * @property CArrayList       linkedRawImageGuids The stand-in documents' guids, one per cropped page.
	 * @property CArrayList       modelImages         The crop model images.
	 */
	internal class CropGroup(
		val group: CModelImageGroup,
		val linkedRawImageGuids: CArrayList<Any?>,
		val modelImages: CArrayList<Any?>,
	)

	/**
	 * The crop web for one page: a stand-in layered image whose frame is the page itself, one layer
	 * and model image per distinct (patch, mesh) crop, an entry per crop on the page's atlas, and a
	 * binding per drawable.  The MOC3-origin path: the only raw material a bake leaves is the page,
	 * so each drawable's patch is sliced back out of it (see the file comment).
	 *
	 * @param Cmo3PageImage       page       The page.
	 * @param Int                 pageIndex  The page's index.
	 * @param List                regions    The crop-path drawables on the page, at least one.
	 * @param CTextureAtlas       atlas      The page's atlas element.
	 * @param GTexture2D          texture    The page's shared texture.
	 * @param CropGroup           group      The crop web's model-image group.
	 * @param Cmo3FreshChainNames names      The document's shared definitions and naming counters.
	 * @param MutableList         pngEntries The PNG entry collector.
	 * @param MutableMap          bindingByDrawableId The binding collector.
	 * @param Long                nowMillis  The import timestamp the wrapper records.
	 * @return LayeredImageWrapper The stand-in document's wrapper, for the texture manager's raw-image list.
	 */
	internal fun cropWeb(
		page: Cmo3PageImage,
		pageIndex: Int,
		regions: List<Cmo3ImageChainBuilder.DrawableRegion>,
		atlas: CTextureAtlas,
		texture: GTexture2D,
		group: CropGroup,
		names: Cmo3FreshChainNames,
		pngEntries: MutableList<Cmo3FreshFile.PngEntry>,
		bindingByDrawableId: MutableMap<String, Cmo3DrawableTextureBinding>,
		nowMillis: Long,
	): LayeredImageWrapper {
		val pageName = "Texture_$pageIndex.png"
		val layeredImage = CLayeredImage()
		val patchLayers = CArrayList<Any?>()
		val rootLayerGroup =
			CLayerGroup().apply {
				name = "root"
				memo = ""
				isVisible = true
				blend = names.sharedBlend
				guid = freshGuid("CLayerGuid")
				opacity255 = 255
				_optionOfIOption = names.sharedOptions
				_layeredImage = layeredImage
				_children = patchLayers
			}
		val layerEntryList = CArrayList<Any?>(mutableListOf<Any?>(rootLayerGroup))
		val document = CropDocument(layeredImage, rootLayerGroup, patchLayers, layerEntryList)
		layeredImage.apply {
			name = pageName
			memo = ""
			// CMO3: CLayeredImage width/height - the SOURCE document's own frame, unrelated to
			// the canvas (ModelWithOffscreenPartClipping: a 500x500 doc in a 1000x2000 canvas).
			// Our synthetic source document IS the atlas page, so the page dims are its frame.
			width = page.width
			height = page.height
			// A rendered page has no source file on anyone's disk; the bare name is the honest
			// breadcrumb (the editor stores the importing machine's absolute path here).
			psdFile = FileRef().apply { textPath = pageName }
			description = ""
			guid = freshGuid("CLayeredImageGuid")
			psdFileLastModified = nowMillis
			_rootLayer = rootLayerGroup
			layerSet =
				LayerSet().apply {
					_layeredImage = layeredImage
					_layerEntryList = layerEntryList
				}
		}
		val wrapper =
			LayeredImageWrapper().apply {
				image = layeredImage
				importedTimeMSec = nowMillis
				lastModifiedTimeMSec = nowMillis
			}
		val atlasEntries = checkNotNull(mutableGraphListOf(atlas.modelImages)) { "pageAtlas builds a list" }
		// Patch webs, shared across drawables sampling the same crop with the same mesh (mirror
		// twins get ONE material like official files; each twin's placement rides its own
		// region input, and the conversion's twin co-location makes those placements one).
		val decodedPage = page.decodedPixels()
		val patchWebByKey = HashMap<PatchWebKey, PatchWeb>()

		/**
		 * An icon entry of its own for one drawable, over PNG bytes twins share.
		 *
		 * @param ByteArray png  The encoded icon.
		 * @param Int       size The square's edge.
		 * @return CImageIcon The icon.
		 */
		fun iconEntry(png: ByteArray, size: Int): CImageIcon {
			val path = names.nextIconPath()
			pngEntries.add(Cmo3FreshFile.PngEntry(path, png))

			return Cmo3Icons.iconReferencing(size, path)
		}

		for (region in regions) {
			val pageFit = fitAtlasPageToCanvasTransform(region.uvs, region.positions, page.width, page.height)
			val patch = patchRectOf(region.uvs, page.width, page.height)

			if (patch == null) {
				bindingByDrawableId[region.drawableIdStr] =
					Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, null, pageFit)

				continue
			}

			val webKey = PatchWebKey(patch, region.uvs, region.indices)
			val patchWeb =
				patchWebByKey.getOrPut(webKey) {
					mintPatchWeb(region, patch, pageFit, page, decodedPage, document, atlas, atlasEntries, group, names, pngEntries, nowMillis)
				}
			bindingByDrawableId[region.drawableIdStr] =
				Cmo3DrawableTextureBinding(
					texture,
					atlas.guid as Guid,
					patchWeb.imageGuid,
					pageFit.copyAffine(),
					icon32 = iconEntry(patchWeb.icon32Png, 32),
					icon16 = iconEntry(patchWeb.icon16Png, 16),
				)
		}

		group.linkedRawImageGuids.add(layeredImage.guid)

		return wrapper
	}

	/**
	 * Mints one patch web: the drawable's crop, masked to its mesh and trimmed to its opaque bounds, as a
	 * layer of the page's stand-in document, with its model image and its entry on the page's atlas.
	 *
	 * @param Cmo3ImageChainBuilder.DrawableRegion region The drawable whose patch this is.
	 * @param IntArray patch The patch rect on the page, [x0, y0, x1, y1] with an exclusive max.
	 * @param CAffine pageFit The drawable's fitted page-to-canvas placement.
	 * @param Cmo3PageImage page The page.
	 * @param RasterImage decodedPage The page's decoded pixels.
	 * @param CropDocument document The page's stand-in document.
	 * @param CTextureAtlas atlas The page's atlas element.
	 * @param MutableList atlasEntries The atlas's entry list.
	 * @param CropGroup group The crop web's model-image group.
	 * @param Cmo3FreshChainNames names The document's shared definitions and naming counters.
	 * @param MutableList pngEntries The PNG entry collector.
	 * @param Long nowMillis The import timestamp the model image records.
	 * @return PatchWeb The minted web.
	 */
	private fun mintPatchWeb(
		region: Cmo3ImageChainBuilder.DrawableRegion,
		patch: IntArray,
		pageFit: CAffine,
		page: Cmo3PageImage,
		decodedPage: RasterImage,
		document: CropDocument,
		atlas: CTextureAtlas,
		atlasEntries: MutableList<Any?>,
		group: CropGroup,
		names: Cmo3FreshChainNames,
		pngEntries: MutableList<Cmo3FreshFile.PngEntry>,
		nowMillis: Long,
	): PatchWeb {
		val patchX0 = patch[0]
		val patchY0 = patch[1]
		val cropWidth = patch[2] - patch[0]
		val cropHeight = patch[3] - patch[1]
		val coverage =
			coverageMaskOf(region.uvs, region.indices, page.width, page.height, patchX0, patchY0, cropWidth, cropHeight)
		val maskedCrop = maskedCropOf(decodedPage, patchX0, patchY0, cropWidth, cropHeight, coverage)
		// Trim the masked crop to its opaque pixel bounds: an official layer rect
		// records the ART's own bounds, not the mesh's reach (the auto-mesh margin
		// the uv bbox includes), and trimming lands within ~1px median of the
		// editor's own rects on the EricaTamamo differential.  A fully transparent
		// crop (a mesh over empty page pixels) keeps the untrimmed rect.
		val opaqueBounds =
			analyzeAlpha(cropWidth, cropHeight, maskedCrop.rgba, contourEpsilon = 0f)?.opaqueBounds
		val trimmedCrop = if (opaqueBounds == null) maskedCrop else maskedCrop.cropped(opaqueBounds)
		val trimmedX0 = patchX0 + (opaqueBounds?.left ?: 0)
		val trimmedY0 = patchY0 + (opaqueBounds?.top ?: 0)
		// Anchor the web on an INTEGER canvas placement, official-style: every
		// official _materialLocalToCanvasTransform translation is a whole number
		// (176 of 176 on EricaTamamo) while the packing origin and the page fit
		// carry complementary FRACTIONS whose composition reproduces it exactly.
		// So snap the placement to the nearest canvas pixel and back-solve the
		// declared packing origin through the fit - the whole chain then composes
		// without a rounding step, and boundsOnImageDoc equals the placement
		// bit-for-bit instead of disagreeing by the rounding residue (the editor's
		// source-image view shows that residue as a subtle per-layer misplacement).
		val patchPlacement = materialLocalToCanvas(pageFit, trimmedX0, trimmedY0)
		patchPlacement.m02 = kotlin.math.round(patchPlacement.m02)
		patchPlacement.m12 = kotlin.math.round(patchPlacement.m12)
		val packingOrigin = solvePageFitFor(pageFit, patchPlacement.m02, patchPlacement.m12)
		val cropBytes = PngCodec.write(trimmedCrop)
		val cropPath = names.nextImageFileBufPath()
		pngEntries.add(Cmo3FreshFile.PngEntry(cropPath, cropBytes))
		val cropResource =
			CImageResource().apply {
				// CMO3: CImageResource - the drawable's patch cropped out of the page.
				width = trimmedCrop.width
				height = trimmedCrop.height
				type = "INT_ARGB"
				imageFileBuf = FileRef().apply { archivePath = cropPath }
				imageFileBuf_size = cropBytes.size
			}
		val patchLayer =
			CLayer().apply {
				// CMO3: CLayer - the patch as its own layer on the canvas-frame doc,
				// layerId null (no PSD layer identity exists for a rendered atlas).
				name = region.drawableIdStr
				memo = ""
				isVisible = true
				blend = names.sharedBlend
				guid = freshGuid("CLayerGuid")
				opacity255 = 255
				_optionOfIOption = names.sharedOptions
				_layeredImage = document.layeredImage
				imageResource = cropResource
				// CMO3: CLayer field boundsOnImageDoc - the layer's pixel rect on the
				// layered-image doc: origin = the placement translation (equals the
				// CModelImage's _materialLocalToCanvasTransform translation on all 892
				// corpus layers), size = the imageResource dims (also 892 of 892).
				boundsOnImageDoc =
					CRect().apply {
						// The placement translation is already snapped integral, so
						// this equals the transform without a second rounding.
						x = patchPlacement.m02.toInt()
						y = patchPlacement.m12.toInt()
						width = trimmedCrop.width
						height = trimmedCrop.height
					}
				layerIdentifier =
					CLayerIdentifier().apply {
						layerName = region.drawableIdStr
						layerIdValue_testImpl = -1
					}
				// CMO3: CLayer fields icon16 / icon64 - thumbnails on every corpus layer,
				// the crop fitted into each square.
				icon16 = Cmo3Icons.iconOf(trimmedCrop, 16, names.nextIconPath(), pngEntries)
				icon64 = Cmo3Icons.iconOf(trimmedCrop, 64, names.nextIconPath(), pngEntries)
				layerInfo = LinkedHashMap<String, Any?>()
				this.group = document.rootLayerGroup
			}
		document.patchLayers.add(patchLayer)
		document.layerEntryList.add(patchLayer)
		val patchImage =
			Cmo3ModelImageWeb.modelImageOver(region.drawableIdStr, document.layeredImage, patchLayer, cropResource, trimmedCrop, patchPlacement.copyAffine(), group.group, names, pngEntries, nowMillis)
		group.modelImages.add(patchImage)
		atlasEntries.add(
			// The declared packing origin is the fit-inverse of the snapped placement
			// (fractional, like every official entry), NOT the raw crop rect origin, so
			// the web composes to the integer placement exactly.
			Cmo3PageWeb.packedEntry(
				atlas = atlas,
				modelImageGuid = patchImage.guid,
				atlasLocalToCanvas = pageFit.copyAffine(),
				packing =
					Cmo3PageWeb.writePacking(
						GTransform2(),
						positionX = packingOrigin?.get(0) ?: trimmedX0.toFloat(),
						positionY = packingOrigin?.get(1) ?: trimmedY0.toFloat(),
						scaleX = 1f,
						scaleY = 1f,
						rotationDegrees = 0f,
					),
			),
		)

		// The drawable icons show the crop, which IS the mesh's uv box; twins share the
		// bytes and each takes an entry of its own, like the editor's one icon per drawable.
		return PatchWeb(patchImage.guid as Guid, Cmo3Icons.iconPngOf(trimmedCrop, 32), Cmo3Icons.iconPngOf(trimmedCrop, 16))
	}

	/**
	 * The stand-in document one page's crops land in: the layered image, its root group, and the two
	 * lists every patch layer joins.
	 *
	 * @property CLayeredImage layeredImage   The stand-in document.
	 * @property CLayerGroup   rootLayerGroup The document's root group, every patch layer's parent.
	 * @property CArrayList    patchLayers    The root group's children.
	 * @property CArrayList    layerEntryList The document's flat layer-entry list.
	 */
	private class CropDocument(
		val layeredImage: CLayeredImage,
		val rootLayerGroup: CLayerGroup,
		val patchLayers: CArrayList<Any?>,
		val layerEntryList: CArrayList<Any?>,
	)

	/**
	 * One minted patch web, shared by every drawable sampling the same crop with the same mesh.
	 *
	 * @property Guid      imageGuid The crop's model image guid.
	 * @property ByteArray icon32Png The 32px drawable icon's bytes.
	 * @property ByteArray icon16Png The 16px drawable icon's bytes.
	 */
	private class PatchWeb(val imageGuid: Guid, val icon32Png: ByteArray, val icon16Png: ByteArray)
}