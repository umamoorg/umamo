package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CSize
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.custom.FilterInstance
import org.umamo.format.cmo3.model.gen.Anisotropy
import org.umamo.format.cmo3.model.gen.CBlend_Normal
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.CachedImageType
import org.umamo.format.cmo3.model.gen.EnvConnection
import org.umamo.format.cmo3.model.gen.EnvValueConnector
import org.umamo.format.cmo3.model.gen.EnvValueSet
import org.umamo.format.cmo3.model.gen.FilterMode
import org.umamo.format.cmo3.model.gen.FilterOutputValueConnector
import org.umamo.format.cmo3.model.gen.FilterValue
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayerSet
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.MagFilter
import org.umamo.format.cmo3.model.gen.MinFilter
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.gen.ModelImageFilterEnv
import org.umamo.format.cmo3.model.gen.ModelImageFilterSet
import org.umamo.format.cmo3.model.gen.WrapMode
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.cmo3.type.CHashMap
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage

/*
 * The image chain a fresh CMO3 needs for its atlas pages, mirroring the official ATLAS-MODE
 * shape (EricaTamamo, isTextureInputModelImageMode=false): one CTextureAtlas + ONE SHARED
 * GTexture2D per page that every packed drawable on the page samples with atlas-frame UVs, plus
 * a PER-DRAWABLE model-image web - each drawable's texture patch cropped out of the page as its
 * own CLayer + CModelImage + ModelImageEntry, the entry carrying the patch's packing origin
 * (materialLocalToAtlasTransform) and the drawable's fitted atlas-to-canvas placement
 * (atlasLocalToCanvasTransform).  The per-drawable web is what the editor's texture-atlas and
 * mesh-edit views derive mesh-over-texture placement from - a single whole-page image cannot
 * place hundreds of drawables, which drew every mesh at its assembled canvas position instead.
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
 * happens to equal its canvas).  A layer's placement is NOT in boundsOnImageDoc, which is all
 * zero on every corpus layer (883 of 883, every file and era); it lives on the owning
 * CModelImage's _materialLocalToCanvasTransform.
 *
 * KNOWN GAP: the packing transform is written as position-only (scale 1, angle 0) while the
 * fitted page-to-canvas affine may flip, scale, or shear - true for ~890 of LimeBirb's 972
 * drawables.  For those the crop is an axis-aligned uv bbox described as if packed upright, so
 * the atlas view mismaps them; only pure-translation packings (Erica) are currently faithful.
 *
 * Remaining deliberate simplifications, validated by the official-editor gate: icon thumbnails
 * are transparent placeholders (the editor regenerates thumbnails on edit), and the cached
 * images are the raw resources themselves at identity (SCALE_1, nothing prerendered).
 */
internal object Cmo3ImageChainBuilder {
	/** One retained atlas page: the original PNG bytes plus its pixel dimensions. */
	internal class AtlasPage(val pngBytes: ByteArray, val width: Int, val height: Int)

	/** One drawable's geometry on its page: id plus interleaved atlas-frame uvs and base positions. */
	internal class DrawableRegion(val drawableIdStr: String, val uvs: FloatArray, val positions: FloatArray)

	/** The populated chain: the PNG entries to embed and the texture bindings. */
	internal class BuiltImageChain(
		val pngEntries: List<Cmo3FreshFile.PngEntry>,
		val bindingByDrawableId: Map<String, Cmo3DrawableTextureBinding>,
		val pageFallbackBindings: List<Cmo3DrawableTextureBinding>,
	)

	/** CMO3: FilterInstance filterDefGuid for "CLayerSelector" - fixed uuid in every corpus file. */
	private const val LAYER_SELECTOR_DEF_UUID = "5e9fe1ea-0ec3-4d68-a5fa-018fc7abe301"

	/** CMO3: FilterInstance filterDefGuid for "CLayerFilter" - fixed uuid in every corpus file. */
	private const val LAYER_FILTER_DEF_UUID = "4083cd1f-40ba-4eda-8400-379019d55ed8"

	/** The per-document singletons of the filter web, shared by every page's ModelImageFilterSet. */
	private class FilterCommons {
		fun valueId(idStr: String): Id = Id("FilterValueId").apply { idstr = idStr }

		val inputLayerData: Id = valueId("mi_input_layerInputData")
		val currentImageGuid: Id = valueId("mi_currentImageGuid")
		val outputImage: Id = valueId("mi_output_image")
		val outputTransform: Id = valueId("mi_output_transform")
		val selectorInputLayerData: Id = valueId("ilf_inputLayerData")
		val selectorCurrentImageGuid: Id = valueId("ilf_currentImageGuid")
		val selectorOutputLayerData: Id = valueId("ilf_outputLayerData")
		val filterInputLayer: Id = valueId("ilf_inputLayer")

		fun value(displayName: String, id: Id): FilterValue =
			FilterValue().apply {
				name = displayName
				this.id = id
			}

		// CMO3: the FilterValue definitions - names and value-id wiring transcribed from the
		// corpus (MultiplyScreenColors.cmo3, identical across files).
		val selectLayer: FilterValue = value("Select Layer", selectorOutputLayerData)
		val importLayer: FilterValue = value("Import Layer", inputLayerData)
		val importLayerSelection: FilterValue = value("Import Layer selection", selectorInputLayerData)
		val currentGuid: FilterValue = value("Current GUID", currentImageGuid)
		val selectedSourceGuid: FilterValue = value("GUID of Selected Source Image", selectorCurrentImageGuid)
		val outputImageValue: FilterValue = value("Output image", outputImage)
		val outputImageResource: FilterValue = value("Output Image (Resource Format)", valueId("ilf_outputImageRes"))
		val layerToCanvasEnv: FilterValue = value("LayerToCanvas変換", outputTransform)
		val layerToCanvasFilter: FilterValue = value("LayerToCanvas変換", valueId("ilf_outputTransform"))

		val selectorDefGuid: Guid =
			Guid("StaticFilterDefGuid").apply {
				uuid = LAYER_SELECTOR_DEF_UUID
				note = "(no debug info)"
			}
		val layerFilterDefGuid: Guid =
			Guid("StaticFilterDefGuid").apply {
				uuid = LAYER_FILTER_DEF_UUID
				note = "(no debug info)"
			}
	}

	/**
	 * Builds one page's ModelImageFilterSet: a CLayerSelector instance feeding a CLayerFilter,
	 * with the external input/output connections the editor's model-image pipeline expects.
	 *
	 * @param FilterCommons commons The per-document shared definitions.
	 * @return ModelImageFilterSet The fresh filter set.
	 */
	private fun buildFilterSet(commons: FilterCommons): ModelImageFilterSet {
		val filterSet = ModelImageFilterSet()
		val selectorId = Id("FilterInstanceId").apply { idstr = "filter0" }
		val layerFilterId = Id("FilterInstanceId").apply { idstr = "filter1" }
		val selector =
			FilterInstance().apply {
				filterName = "CLayerSelector"
				filterDefGuid = commons.selectorDefGuid
				filterId = selectorId
				ownerFilterSet = filterSet
			}
		val selectorOutput =
			FilterOutputValueConnector().apply {
				instance = selector
				id = commons.selectorOutputLayerData
				valueDef = commons.selectLayer
			}
		selector.inputConnectors =
			CHashMap<Any?, Any?>().apply {
				put(commons.selectorInputLayerData, EnvValueConnector().apply { envValueId = commons.inputLayerData })
				put(commons.selectorCurrentImageGuid, EnvValueConnector().apply { envValueId = commons.currentImageGuid })
			}
		selector.outputConnectors = CHashMap<Any?, Any?>().apply { put(commons.selectorOutputLayerData, selectorOutput) }
		val layerFilter =
			FilterInstance().apply {
				filterName = "CLayerFilter"
				filterDefGuid = commons.layerFilterDefGuid
				filterId = layerFilterId
				inputConnectors = CHashMap<Any?, Any?>().apply { put(commons.filterInputLayer, selectorOutput) }
				outputConnectors = CHashMap<Any?, Any?>()
				ownerFilterSet = filterSet
			}
		filterSet.filterMap =
			LinkedHashMap<Any?, Any?>().apply {
				put(selectorId, selector)
				put(layerFilterId, layerFilter)
			}
		filterSet._externalInputs =
			LinkedHashMap<Any?, Any?>().apply {
				put(
					commons.inputLayerData,
					EnvConnection().apply {
						_envValueDef = commons.importLayer
						filter = selector
						filterValueDef = commons.importLayerSelection
					},
				)
				put(
					commons.currentImageGuid,
					EnvConnection().apply {
						_envValueDef = commons.currentGuid
						filter = selector
						filterValueDef = commons.selectedSourceGuid
					},
				)
			}
		filterSet._externalOutputs =
			LinkedHashMap<Any?, Any?>().apply {
				put(
					commons.outputImage,
					EnvConnection().apply {
						_envValueDef = commons.outputImageValue
						filter = layerFilter
						filterValueDef = commons.outputImageResource
					},
				)
				put(
					commons.outputTransform,
					EnvConnection().apply {
						_envValueDef = commons.layerToCanvasEnv
						filter = layerFilter
						filterValueDef = commons.layerToCanvasFilter
					},
				)
			}
		return filterSet
	}

	/**
	 * The cached-image manager the editor expects on every model image and texture atlas: the raw
	 * page cached as itself at SCALE_1 (nothing prerendered).
	 *
	 * @param CImageResource pageResource The page image resource.
	 * @param Int            width        The page width in pixels.
	 * @param Int            height       The page height in pixels.
	 * @return CCachedImageManager The fresh manager.
	 */
	private fun identityCacheManager(pageResource: CImageResource, width: Int, height: Int): CCachedImageManager =
		CCachedImageManager().apply {
			// CMO3: CCachedImageManager fields defaultCacheType / rawImage / cachedImages /
			// requiredMipmapLevel (corpus flat imports cache the raw resource itself).
			defaultCacheType = CachedImageType.SCALE_1
			rawImage = pageResource
			cachedImages =
				ArrayList<Any?>(
					mutableListOf(
						CCachedImage().apply {
							_cachedImageResource = pageResource
							isSharedImage = true
							rawImageSize =
								CSize().apply {
									this.width = width
									this.height = height
								}
							reductionRatio = 1
							mipmapLevel = 64
							hasMargin = false
							isCleaned = false
							transformRawImageToCachedImage = CAffine()
						},
					),
				)
			requiredMipmapLevel = 64
		}

	/**
	 * A transparent square icon plus its PNG entry.
	 *
	 * @param Int    size The square pixel size.
	 * @param String path The unique archive path for the PNG entry.
	 * @param MutableList entries The PNG entry collector.
	 * @return CImageIcon The fresh icon.
	 */
	private fun placeholderIcon(size: Int, path: String, entries: MutableList<Cmo3FreshFile.PngEntry>): CImageIcon {
		entries.add(Cmo3FreshFile.PngEntry(path, Cmo3SkeletonBuilder.blankPng(size)))
		return CImageIcon().apply {
			image =
				CWritableImage().apply {
					width = size
					height = size
					type = "INT_ARGB"
					image = FileRef().apply { archivePath = path }
				}
		}
	}

	/**
	 * A drawable's pixel-aligned texture-patch rect on its page, from its atlas-frame uv bounds.
	 *
	 * Mesh margins may reach slightly outside [0,1]; the rect is clamped to the page and forced to
	 * at least one pixel.
	 *
	 * @param FloatArray uvs        Interleaved atlas-frame uvs.
	 * @param Int        pageWidth  The page's pixel width.
	 * @param Int        pageHeight The page's pixel height.
	 * @return IntArray? [x0, y0, x1, y1] (exclusive max), or null when there are no uvs.
	 */
	private fun patchRectOf(uvs: FloatArray, pageWidth: Int, pageHeight: Int): IntArray? {
		if (uvs.size < 2) {
			return null
		}
		var minU = Float.POSITIVE_INFINITY
		var minV = Float.POSITIVE_INFINITY
		var maxU = Float.NEGATIVE_INFINITY
		var maxV = Float.NEGATIVE_INFINITY
		var componentIndex = 0
		while (componentIndex + 1 < uvs.size) {
			minU = minOf(minU, uvs[componentIndex])
			maxU = maxOf(maxU, uvs[componentIndex])
			minV = minOf(minV, uvs[componentIndex + 1])
			maxV = maxOf(maxV, uvs[componentIndex + 1])
			componentIndex += 2
		}
		if (!minU.isFinite() || !minV.isFinite() || !maxU.isFinite() || !maxV.isFinite()) {
			return null
		}
		val x0 = kotlin.math.floor(minU * pageWidth).toInt().coerceIn(0, pageWidth - 1)
		val y0 = kotlin.math.floor(minV * pageHeight).toInt().coerceIn(0, pageHeight - 1)
		val x1 = kotlin.math.ceil(maxU * pageWidth).toInt().coerceIn(x0 + 1, pageWidth)
		val y1 = kotlin.math.ceil(maxV * pageHeight).toInt().coerceIn(y0 + 1, pageHeight)
		return intArrayOf(x0, y0, x1, y1)
	}

	/**
	 * An independent copy of an affine (the writer must not hoist one shared instance across the
	 * entry, the region input, and the model image - the editor writes separate elements).
	 *
	 * @param CAffine source The affine to copy.
	 * @return CAffine The copy.
	 */
	private fun copyAffine(source: CAffine): CAffine =
		CAffine().apply {
			m00 = source.m00
			m01 = source.m01
			m02 = source.m02
			m10 = source.m10
			m11 = source.m11
			m12 = source.m12
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
		copyAffine(pageFit).apply {
			m02 = pageFit.m00 * x0 + pageFit.m01 * y0 + pageFit.m02
			m12 = pageFit.m10 * x0 + pageFit.m11 * y0 + pageFit.m12
		}

	/**
	 * Encodes the patch rect of a decoded page as its own PNG.
	 *
	 * @param RasterImage decodedPage The decoded page pixels.
	 * @param Int         x0          Patch origin x.
	 * @param Int         y0          Patch origin y.
	 * @param Int         cropWidth   Patch width.
	 * @param Int         cropHeight  Patch height.
	 * @return ByteArray The encoded PNG bytes.
	 */
	private fun cropPng(decodedPage: RasterImage, x0: Int, y0: Int, cropWidth: Int, cropHeight: Int): ByteArray {
		val cropRgba = ByteArray(cropWidth * cropHeight * 4)
		for (rowIndex in 0 until cropHeight) {
			val sourceOffset = ((y0 + rowIndex) * decodedPage.width + x0) * 4
			decodedPage.rgba.copyInto(cropRgba, rowIndex * cropWidth * 4, sourceOffset, sourceOffset + cropWidth * 4)
		}
		return PngCodec.write(RasterImage(cropWidth, cropHeight, cropRgba))
	}

	/**
	 * Populates [root]'s texture manager with one page chain per atlas page plus the per-drawable
	 * model-image webs.
	 *
	 * @param CModelSource root          The fresh skeleton root (its textureManager must exist).
	 * @param List         pages         The atlas pages, in model3 texture order.
	 * @param List         regionsByPage Each page's drawable regions (geometry for the patch crop
	 *                                   and the placement fit), indexed like [pages].
	 * @param Long         nowMillis     The import timestamp the wrappers record (caller-supplied
	 *                                   so tests stay deterministic).
	 * @return BuiltImageChain The PNG entries plus the texture bindings.
	 */
	internal fun populate(
		root: CModelSource,
		pages: List<AtlasPage>,
		regionsByPage: List<List<DrawableRegion>>,
		nowMillis: Long,
	): BuiltImageChain {
		val textureManager = root.textureManager as? CTextureManager ?: error("skeleton has no texture manager")
		val sharedBlend = CBlend_Normal()
		val sharedOptions = CHashMap<String, Any?>()
		val filterCommons = FilterCommons()
		val groupLinkedRawImageGuids = CArrayList<Any?>()
		val groupModelImages = CArrayList<Any?>()
		val group =
			CModelImageGroup().apply {
				memo = ""
				groupName = "Textures"
				_linkedRawImageGuids = groupLinkedRawImageGuids
				_modelImages = groupModelImages
			}
		val rawImages =
			mutableGraphListOf(textureManager._rawImages) ?: error("skeleton texture manager has no raw-image list")
		val textureAtlases =
			mutableGraphListOf(textureManager._textureAtlases) ?: error("skeleton texture manager has no texture-atlas list")
		val modelImageGroups =
			mutableGraphListOf(textureManager._modelImageGroups) ?: error("skeleton texture manager has no model-image-group list")
		val pngEntries = ArrayList<Cmo3FreshFile.PngEntry>()
		val bindingByDrawableId = HashMap<String, Cmo3DrawableTextureBinding>()
		val pageFallbackBindings = ArrayList<Cmo3DrawableTextureBinding>(pages.size)
		// The skeleton's three model icons take image.png / image_0.png / image_1.png, so page
		// icon entries continue the editor's image_N naming from suffix 2.
		var iconSuffix = 2
		// CMO3: the imageFileBuf de-dupe naming convention (imageFileBuf, imageFileBuf_0, ...);
		// pages claim the first indices and patch crops continue the sequence.
		var imageFileBufIndex = 0
		fun nextImageFileBufPath(): String {
			val path = if (imageFileBufIndex == 0) "imageFileBuf.png" else "imageFileBuf_${imageFileBufIndex - 1}.png"
			imageFileBufIndex += 1
			return path
		}
		for ((pageIndex, page) in pages.withIndex()) {
			val pagePath = nextImageFileBufPath()
			val pageName = "Texture_$pageIndex.png"
			pngEntries.add(Cmo3FreshFile.PngEntry(pagePath, page.pngBytes))
			val pageResource =
				CImageResource().apply {
					// CMO3: CImageResource attrs width/height/type + the imageFileBuf file child.
					width = page.width
					height = page.height
					type = "INT_ARGB"
					imageFileBuf = FileRef().apply { archivePath = pagePath }
					imageFileBuf_size = page.pngBytes.size
				}
			val layeredImage = CLayeredImage()
			val patchLayers = CArrayList<Any?>()
			val rootLayerGroup =
				CLayerGroup().apply {
					name = "root"
					memo = ""
					isVisible = true
					blend = sharedBlend
					guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
					opacity255 = 255
					_optionOfIOption = sharedOptions
					_layeredImage = layeredImage
					_children = patchLayers
				}
			val layerEntryList = CArrayList<Any?>(mutableListOf<Any?>(rootLayerGroup))
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
				guid = Cmo3SkeletonBuilder.freshGuid("CLayeredImageGuid")
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
			val atlasEntries = CArrayList<Any?>()
			val atlas = CTextureAtlas()
			atlas.apply {
				name = "TextureAtlas${pageIndex + 1}"
				width = page.width
				height = page.height
				cachedAtlasImage = pageResource
				guid = Cmo3SkeletonBuilder.freshGuid("CTextureAtlasGuid")
				modelImages = atlasEntries
				cachedImageManager = identityCacheManager(pageResource, page.width, page.height)
			}
			val texture =
				GTexture2D().apply {
					// CMO3: GTexture2D - the page's ONE shared texture; every drawable on the page
					// references this instance (the writer hoists it), like the editor's own atlas.
					name = atlas.name
					// CMO3: GTexture fields wrapMode / filterMode / anisotropy - the editor's fixed
					// sampling setup on every corpus texture.
					wrapMode = WrapMode.CLAMP_TO_BORDER
					guid = Cmo3SkeletonBuilder.freshGuid("GTextureGuid")
					anisotropy = Anisotropy.ON
					srcImageResource = pageResource
					transformImageResource01toLogical01 = CAffine()
					mipmapLevel = 64
					// CMO3: GTexture2D field isPremultiplied - true on EVERY corpus texture (178 of
					// 178, every era), including the many whose embedded PNG bytes are straight
					// alpha.  The flag records the editor's texture-render/upload convention, not
					// the byte storage (see the Premultiplied section), so a MOC3 sidecar page -
					// straight alpha like the corpus ones - writes true as well.
					isPremultiplied = true
				}
			texture.filterMode =
				FilterMode().apply {
					minFilter = MinFilter.LINEAR_MIPMAP_LINEAR
					magFilter = MagFilter.LINEAR
					owner = texture
				}
			// Per-drawable patch webs, deduped by patch rect + fitted placement (a mirror duplicate
			// with a different placement keeps its own web - a ModelImageEntry carries only one).
			val regions = regionsByPage.getOrNull(pageIndex).orEmpty()
			val decodedPage = if (regions.isNotEmpty()) PngCodec.read(page.pngBytes) else null
			val imageGuidByWebKey = HashMap<String, Guid>()
			for (region in regions) {
				val pageFit = fitAtlasPageToCanvasTransform(region.uvs, region.positions, page.width, page.height)
				val patch = patchRectOf(region.uvs, page.width, page.height)
				if (patch == null || decodedPage == null) {
					bindingByDrawableId[region.drawableIdStr] =
						Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, null, pageFit)
					continue
				}
				val patchX0 = patch[0]
				val patchY0 = patch[1]
				val cropWidth = patch[2] - patch[0]
				val cropHeight = patch[3] - patch[1]
				val fitBits =
					floatArrayOf(pageFit.m00, pageFit.m01, pageFit.m02, pageFit.m10, pageFit.m11, pageFit.m12)
						.joinToString(":") { component -> component.toRawBits().toString() }
				val webKey = "${patch.joinToString(":")}|$fitBits"
				val imageGuid =
					imageGuidByWebKey.getOrPut(webKey) {
						val cropBytes = cropPng(decodedPage, patchX0, patchY0, cropWidth, cropHeight)
						val cropPath = nextImageFileBufPath()
						pngEntries.add(Cmo3FreshFile.PngEntry(cropPath, cropBytes))
						val cropResource =
							CImageResource().apply {
								// CMO3: CImageResource - the drawable's patch cropped out of the page.
								width = cropWidth
								height = cropHeight
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
								blend = sharedBlend
								guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
								opacity255 = 255
								_optionOfIOption = sharedOptions
								_layeredImage = layeredImage
								imageResource = cropResource
								// CMO3: CLayer field boundsOnImageDoc - ALL ZERO on every corpus layer
								// (883 of 883, every file and era).  The layer's placement lives on
								// its CModelImage's _materialLocalToCanvasTransform, not here.
								boundsOnImageDoc = CRect()
								layerIdentifier =
									CLayerIdentifier().apply {
										layerName = region.drawableIdStr
										layerIdValue_testImpl = -1
									}
								// CMO3: CLayer fields icon16 / icon64 - thumbnails on every corpus layer.
								icon16 = placeholderIcon(16, "image_${iconSuffix++}.png", pngEntries)
								icon64 = placeholderIcon(64, "image_${iconSuffix++}.png", pngEntries)
								layerInfo = LinkedHashMap<String, Any?>()
								this.group = rootLayerGroup
							}
						patchLayers.add(patchLayer)
						layerEntryList.add(patchLayer)
						val patchImage =
							CModelImage().apply {
								guid = Cmo3SkeletonBuilder.freshGuid("CModelImageGuid")
								name = region.drawableIdStr
								// CMO3: CModelImage fields inputFilter / inputFilterEnv - the layer-filter
								// web selecting this patch's layer; the deserializer dereferences both.
								inputFilter = buildFilterSet(filterCommons)
								inputFilterEnv =
									ModelImageFilterEnv().apply {
										envValues =
											CHashMap<Any?, Any?>().apply {
												put(
													filterCommons.currentImageGuid,
													EnvValueSet().apply {
														id = filterCommons.currentImageGuid
														value = layeredImage.guid
														updateTimeMs = nowMillis
													},
												)
												put(
													filterCommons.inputLayerData,
													EnvValueSet().apply {
														id = filterCommons.inputLayerData
														value =
															CLayerSelectorMap().apply {
																_imageToLayerInput =
																	LinkedHashMap<Any?, Any?>().apply {
																		put(
																			layeredImage.guid,
																			ArrayList<Any?>(
																				mutableListOf(
																					CLayerInputData().apply {
																						layer = patchLayer
																						affine = CAffine()
																					},
																				),
																			),
																		)
																	}
															}
														updateTimeMs = nowMillis
													},
												)
											}
									}
								_filteredImage = cropResource
								// CMO3: CModelImage fields icon16 / cachedImageManager - present on every
								// corpus model image; the icon is a placeholder the editor regenerates.
								icon16 = placeholderIcon(16, "image_${iconSuffix++}.png", pngEntries)
								// CMO3: CModelImage field _materialLocalToCanvasTransform - the patch's
								// canvas placement (official layers carry their canvas origin here).
								_materialLocalToCanvasTransform = materialLocalToCanvas(pageFit, patchX0, patchY0)
								_group = group
								linkedRawImageGuids = CArrayList<Any?>(mutableListOf(layeredImage.guid))
								cachedImageManager = identityCacheManager(cropResource, cropWidth, cropHeight)
								memo = ""
							}
						groupModelImages.add(patchImage)
						atlasEntries.add(
							ModelImageEntry().apply {
								// CMO3: ModelImageEntry - the patch's packing origin on the page plus the
								// drawable's fitted atlas-to-canvas placement; the editor's atlas and
								// mesh-edit views derive mesh-over-texture placement from these.
								this.atlas = atlas
								modelImageGuid = patchImage.guid
								// CMO3: ModelImageEntry field autoLayoutLock - an AutoLayoutLock enum
								// (v="NONE"); the editor's field is enum-typed and class-casts a boolean.
								autoLayoutLock = org.umamo.format.cmo3.model.gen.AutoLayoutLock.NONE
								atlasLocalToCanvasTransform = copyAffine(pageFit)
								materialLocalToAtlasTransform =
									GTransform2().apply {
										position =
											GVector2().apply {
												x = patchX0.toFloat()
												y = patchY0.toFloat()
											}
										scale =
											GVector2().apply {
												x = 1f
												y = 1f
											}
									}
							},
						)
						patchImage.guid as Guid
					}
				bindingByDrawableId[region.drawableIdStr] =
					Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, imageGuid, copyAffine(pageFit))
			}
			pageFallbackBindings.add(Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, null, CAffine()))
			groupLinkedRawImageGuids.add(layeredImage.guid)
			rawImages.add(wrapper)
			textureAtlases.add(atlas)
		}
		modelImageGroups.add(group)
		// CMO3: CTextureManager field isTextureInputModelImageMode - false = atlas display mode,
		// matching the atlas-region inputs the drawables carry.
		textureManager.isTextureInputModelImageMode = false
		return BuiltImageChain(pngEntries, bindingByDrawableId, pageFallbackBindings)
	}
}
