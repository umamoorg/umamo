package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CBlend_Normal
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayerSet
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.cmo3.type.CHashMap

/*
 * The image chain a fresh CMO3 needs for its atlas pages: each MOC3 page becomes a flat-PNG
 * "import" exactly like the editor's own flat-image imports (docs/format/CMO3.md §4 - a
 * CLayeredImage whose root group wraps ONE CLayer with a null layerId), plus one CTextureAtlas
 * whose single ModelImageEntry maps the whole page at identity, and one SHARED GTexture2D every
 * drawable on the page references.  The chain is what makes UVs stay in the atlas frame
 * (hasAtlasRegion) and what gives the official editor a source-art panel to show.
 *
 * Deliberate simplifications on the editor's shape, validated by the official-editor gate:
 * CModelImage.inputFilter / inputFilterEnv stay null (the filter web that composes a model image
 * from PSD layers has nothing to compose here - the page IS the image), cachedImageManager stays
 * null (an editor-side cache), and no per-layer icon thumbnails are embedded.
 */
internal object Cmo3ImageChainBuilder {
	/** One retained atlas page: the original PNG bytes plus its pixel dimensions. */
	internal class AtlasPage(val pngBytes: ByteArray, val width: Int, val height: Int)

	/** The populated chain: the PNG entries to embed and the per-page drawable bindings. */
	internal class BuiltImageChain(
		val pngEntries: List<Cmo3FreshFile.PngEntry>,
		val pageBindings: List<Cmo3DrawableTextureBinding>,
	)

	/**
	 * Populates [root]'s texture manager with one page chain per atlas page.
	 *
	 * @param CModelSource root      The fresh skeleton root (its textureManager must exist).
	 * @param List         pages     The atlas pages, in model3 texture order.
	 * @param Long         nowMillis The import timestamp the wrappers record (caller-supplied so
	 *                               tests stay deterministic).
	 * @return BuiltImageChain The PNG entries plus per-page bindings, indexed like [pages].
	 */
	internal fun populate(root: CModelSource, pages: List<AtlasPage>, nowMillis: Long): BuiltImageChain {
		val textureManager = root.textureManager as? CTextureManager ?: error("skeleton has no texture manager")
		val sharedBlend = CBlend_Normal()
		val sharedOptions = CHashMap<String, Any?>()
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
		val pngEntries = ArrayList<Cmo3FreshFile.PngEntry>(pages.size)
		val pageBindings = ArrayList<Cmo3DrawableTextureBinding>(pages.size)
		for ((pageIndex, page) in pages.withIndex()) {
			// CMO3: the imageFileBuf de-dupe naming convention (imageFileBuf, imageFileBuf_0, ...).
			val pagePath = if (pageIndex == 0) "imageFileBuf.png" else "imageFileBuf_${pageIndex - 1}.png"
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
			val pageLayer =
				CLayer().apply {
					// CMO3: the flat-image import shape - a single CLayer, layerId null (no PSD
					// layer identity exists for a rendered atlas page).
					name = pageName
					memo = ""
					isVisible = true
					blend = sharedBlend
					guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
					opacity255 = 255
					_optionOfIOption = sharedOptions
					_layeredImage = layeredImage
					imageResource = pageResource
					boundsOnImageDoc =
						CRect().apply {
							width = page.width
							height = page.height
						}
					layerIdentifier =
						CLayerIdentifier().apply {
							layerName = pageName
							layerIdValue_testImpl = -1
						}
					layerInfo = LinkedHashMap<String, Any?>()
				}
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
					_children = CArrayList<Any?>(mutableListOf(pageLayer))
				}
			pageLayer.group = rootLayerGroup
			layeredImage.apply {
				name = pageName
				memo = ""
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
						_layerEntryList = CArrayList<Any?>(mutableListOf(rootLayerGroup, pageLayer))
					}
			}
			val wrapper =
				LayeredImageWrapper().apply {
					image = layeredImage
					importedTimeMSec = nowMillis
					lastModifiedTimeMSec = nowMillis
				}
			val modelImage =
				CModelImage().apply {
					guid = Cmo3SkeletonBuilder.freshGuid("CModelImageGuid")
					name = pageName
					_filteredImage = pageResource
					_materialLocalToCanvasTransform = CAffine()
					_group = group
					linkedRawImageGuids = CArrayList<Any?>(mutableListOf(layeredImage.guid))
					memo = ""
				}
			val atlas = CTextureAtlas()
			atlas.apply {
				name = "TextureAtlas${pageIndex + 1}"
				width = page.width
				height = page.height
				cachedAtlasImage = pageResource
				guid = Cmo3SkeletonBuilder.freshGuid("CTextureAtlasGuid")
				modelImages =
					CArrayList<Any?>(
						mutableListOf(
							ModelImageEntry().apply {
								// CMO3: ModelImageEntry - the whole page mapped at identity (the page
								// already IS the packed atlas; there is nothing to place).
								this.atlas = atlas
								modelImageGuid = modelImage.guid
								atlasLocalToCanvasTransform = CAffine()
								materialLocalToAtlasTransform =
									GTransform2().apply {
										position = GVector2()
										scale =
											GVector2().apply {
												x = 1f
												y = 1f
											}
									}
							},
						),
					)
			}
			val texture =
				GTexture2D().apply {
					// CMO3: GTexture2D - the page's ONE shared texture; every drawable on the page
					// references this instance (the writer hoists it), like the editor's own atlas.
					name = atlas.name
					guid = Cmo3SkeletonBuilder.freshGuid("GTextureGuid")
					srcImageResource = pageResource
					transformImageResource01toLogical01 = CAffine()
					mipmapLevel = 64
					// MOC3 sidecar pages are straight-alpha PNGs, and the flag must describe the
					// actual bytes (the editor's own rendered atlas is premultiplied and says true).
					isPremultiplied = false
				}
			groupLinkedRawImageGuids.add(layeredImage.guid)
			groupModelImages.add(modelImage)
			rawImages.add(wrapper)
			textureAtlases.add(atlas)
			pageBindings.add(
				Cmo3DrawableTextureBinding(
					texture = texture,
					textureAtlasGuid = atlas.guid as org.umamo.format.cmo3.model.identity.Guid,
					modelImageGuid = modelImage.guid as org.umamo.format.cmo3.model.identity.Guid,
					inputImageLocalToCanvasTransform = CAffine(),
				),
			)
		}
		modelImageGroups.add(group)
		// CMO3: CTextureManager field isTextureInputModelImageMode - false = atlas display mode,
		// matching the atlas-region inputs the drawables carry.
		textureManager.isTextureInputModelImageMode = false
		return BuiltImageChain(pngEntries, pageBindings)
	}
}
