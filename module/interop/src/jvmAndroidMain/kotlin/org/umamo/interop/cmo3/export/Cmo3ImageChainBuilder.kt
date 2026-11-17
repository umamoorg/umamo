package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.cmo3.mutableGraphListOf

/**
 * Populates a fresh graph's texture manager: one atlas and one shared texture per page, the real-layer
 * web for every artwork file, and the crop web for the drawables that have no art of their own.
 *
 * This object is only the orchestrator.  The pieces sit beside it: [Cmo3PageWeb] (the page half and the
 * packing entries), [Cmo3ModelImageWeb] (a model image and its filter web), [Cmo3SourceLayerWeb] (real
 * layers), and [Cmo3PageCropWeb] (the stand-in for a document with no source art, whose file comment
 * says why it exists), all threading one [Cmo3FreshChainNames].
 */
internal object Cmo3ImageChainBuilder {
	/**
	 * One drawable's geometry on its page: id, interleaved atlas-frame uvs and canvas positions, plus
	 * the triangle indices that say which page pixels inside the uv bounding box are actually this
	 * drawable's (the crop web masks its crop to them).
	 */
	internal class DrawableRegion(
		val drawableIdStr: String,
		val uvs: FloatArray,
		val positions: FloatArray,
		val indices: IntArray,
	)

	/**
	 * The populated chain: the PNG entries to embed and the texture bindings.
	 *
	 * @property List pngEntries           The PNG entries to embed.
	 * @property Map  bindingByDrawableId  The texture web per drawable that got one.
	 * @property List pageFallbackBindings The per-page bindings for a drawable with no web of its own.
	 * @property Int  cropDrawableCount    How many drawables took the crop path - the stand-in sliced
	 *   out of a page - which is what the missing-source-art notice reports; zero when every
	 *   drawable's art came from a real layer.
	 * @property List rasterTextures       The textures unpacked drawables sample, one per never-packed
	 *   tile, each over its model image's raster.
	 */
	internal class BuiltImageChain(
		val pngEntries: List<Cmo3FreshFile.PngEntry>,
		val bindingByDrawableId: Map<String, Cmo3DrawableTextureBinding>,
		val pageFallbackBindings: List<Cmo3DrawableTextureBinding>,
		val cropDrawableCount: Int = 0,
		val rasterTextures: List<GTexture2D> = emptyList(),
	)

	/**
	 * Populates [root]'s texture manager with one page chain per atlas page, the real-layer web for
	 * every artwork file in [sourceImages], and the crop web for the drawables [regionsByPage] lists -
	 * the MOC3-origin stand-in that slices each drawable's patch out of its page.  A page with no crop
	 * regions mints no stand-in document, so an artwork-origin file carries none.
	 *
	 * @param CModelSource root          The fresh skeleton root (its textureManager must exist).
	 * @param List         pages         The atlas pages, in model3 texture order.
	 * @param List         regionsByPage Each page's crop-path drawable regions (geometry for the patch
	 *                                   crop and the placement fit), indexed like [pages].
	 * @param Long         nowMillis     The import timestamp the wrappers record (caller-supplied
	 *                                   so tests stay deterministic).
	 * @param Boolean      fromSourceLayers The document's display mode: true writes the texture
	 *                                   manager's combined-layer mode, false the packed-atlas one.
	 * @param List         sourceImages  The artwork files whose tiles have real art, with their layers.
	 * @return BuiltImageChain The PNG entries plus the texture bindings.
	 */
	internal fun populate(
		root: CModelSource,
		pages: List<Cmo3Conversion.AtlasPage>,
		regionsByPage: List<List<DrawableRegion>>,
		nowMillis: Long,
		fromSourceLayers: Boolean = false,
		sourceImages: List<Cmo3SourceLayerWeb.SourceImageInput> = emptyList(),
	): BuiltImageChain {
		val textureManager = root.textureManager as? CTextureManager ?: error("skeleton has no texture manager")
		val names = Cmo3FreshChainNames()
		val rawImages =
			mutableGraphListOf(textureManager._rawImages) ?: error("skeleton texture manager has no raw-image list")
		val textureAtlases =
			mutableGraphListOf(textureManager._textureAtlases) ?: error("skeleton texture manager has no texture-atlas list")
		val modelImageGroups =
			mutableGraphListOf(textureManager._modelImageGroups) ?: error("skeleton texture manager has no model-image-group list")
		val pngEntries = ArrayList<Cmo3FreshFile.PngEntry>()
		val bindingByDrawableId = HashMap<String, Cmo3DrawableTextureBinding>()
		val pageFallbackBindings = ArrayList<Cmo3DrawableTextureBinding>(pages.size)
		val atlases = ArrayList<CTextureAtlas>(pages.size)
		val textures = ArrayList<GTexture2D>(pages.size)
		var cropGroup: Cmo3PageCropWeb.CropGroup? = null
		var cropDrawableCount = 0

		for ((pageIndex, page) in pages.withIndex()) {
			val pagePath = names.nextImageFileBufPath()
			pngEntries.add(Cmo3FreshFile.PngEntry(pagePath, page.pngBytes))
			val pageResource = Cmo3PageWeb.pageImageResource(pagePath, page.width, page.height, page.pngBytes.size)
			val atlas = Cmo3PageWeb.pageAtlas(pageIndex, page.width, page.height, pageResource)
			val texture = Cmo3PageWeb.pageTexture(atlas.name, pageResource)
			atlases.add(atlas)
			textures.add(texture)
			val regions = regionsByPage.getOrNull(pageIndex).orEmpty()

			if (regions.isNotEmpty()) {
				val group =
					cropGroup ?: run {
						val linkedRawImageGuids = CArrayList<Any?>()
						val modelImages = CArrayList<Any?>()
						val minted =
							CModelImageGroup().apply {
								memo = ""
								groupName = "Textures"
								_linkedRawImageGuids = linkedRawImageGuids
								_modelImages = modelImages
							}
						Cmo3PageCropWeb.CropGroup(minted, linkedRawImageGuids, modelImages).also { cropGroup = it }
					}
				cropDrawableCount += regions.size
				rawImages.add(Cmo3PageCropWeb.cropWeb(page, pageIndex, regions, atlas, texture, group, names, pngEntries, bindingByDrawableId, nowMillis))
			}

			pageFallbackBindings.add(Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, null, CAffine()))
			textureAtlases.add(atlas)
		}

		cropGroup?.let { group -> modelImageGroups.add(group.group) }
		val rasterTextures = ArrayList<GTexture2D>()

		for (image in sourceImages) {
			val written = Cmo3SourceLayerWeb.write(image, atlases, textures, names, pngEntries, nowMillis)
			rawImages.add(written.wrapper)
			modelImageGroups.add(written.group)
			bindingByDrawableId.putAll(written.bindingByDrawableId)
			rasterTextures.addAll(written.rasterTextures)
		}

		// CMO3: CTextureManager field isTextureInputModelImageMode - the document's own display mode.
		// The synthesized web carries BOTH inputs per packed drawable (a model image and an atlas region),
		// and an unpacked one's model image is current in either mode, so either mode is representable;
		// the diff-driven lowering retargets currentTextureInputData to match.  This path is not covered
		// by that lowering, so it reads the model directly rather than hardcoding a mode the document may
		// not be in.
		textureManager.isTextureInputModelImageMode = fromSourceLayers

		return BuiltImageChain(pngEntries, bindingByDrawableId, pageFallbackBindings, cropDrawableCount, rasterTextures)
	}
}