package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACLayerEntry
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.EnvValueSet
import org.umamo.format.cmo3.model.gen.FilterEnv
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.SourceLayerRef

/**
 * A CMO3's layered-art web read as model state: the atlas itself, which tile each drawable samples,
 * where each tile's pixels live in the graph, and the artwork files the editor decomposed to build it.
 *
 * @property PuppetAtlas atlas               The document's pages and tiles, with each tile's placement.
 * @property Map         tileIdByDrawableId  Raw drawable id to the tile it samples; absent when it has none.
 * @property Map         imageResourceByTile The graph resource holding each tile's pixels, for the
 *   document's pixel supplier - metadata ingest reads no bytes, so decoding stays the caller's.
 * @property List        sources             The layered images the editor imported, as the model's
 *   source list: identity, name, and the advisory PSD path, with NO layer inventory - the editor's
 *   decomposed layer tree is walked by a later phase.
 */
public class Cmo3AtlasIngest(
	public val atlas: PuppetAtlas,
	public val tileIdByDrawableId: Map<String, AtlasTileId>,
	public val imageResourceByTile: Map<AtlasTileId, CImageResource>,
	public val sources: List<ArtSource> = emptyList(),
) {
	public companion object {
		/** What a document with no texture manager, or no model images, ingests to. */
		public val EMPTY: Cmo3AtlasIngest = Cmo3AtlasIngest(PuppetAtlas.Empty, emptyMap(), emptyMap())
	}
}

/**
 * Reads a CMO3's layered-art web into the model's atlas.
 *
 * The web is three joined structures and this walks all three: the texture manager's atlas pages carry
 * a `ModelImageEntry` per packed image (where the packer put it), the model-image groups carry the
 * images themselves (the art, and its size), and each art mesh's texture-input extension names the
 * image it samples.  The join key throughout is the model image's guid, which becomes the tile id.
 *
 * Nothing is decoded here: an image's dimensions come off its resource record, so a model with hundreds
 * of layers ingests without touching a pixel.  [Cmo3AtlasIngest.imageResourceByTile] is what lets the
 * caller decode later, on demand.
 *
 * A drawable's placement is deliberately NOT resolved here.  Whether a drawable reads a packed page or
 * its art directly follows from the document's display mode and whether its tile was packed at all -
 * both of which are model state - so the model derives it (`PuppetModel.atlasBindingFor`) rather than
 * this ingest freezing an answer that an edit would then have to keep in step.
 *
 * @param CModelSource modelSource The CMO3's root model source.
 * @return Cmo3AtlasIngest The atlas, the per-drawable tile binding, and the pixel resources.
 */
public fun cmo3AtlasIngest(modelSource: CModelSource): Cmo3AtlasIngest {
	// CMO3: CModelSource field textureManager - the root of the whole layered-art web.
	val textureManager = modelSource.textureManager as? CTextureManager ?: return Cmo3AtlasIngest.EMPTY

	// CMO3: CTextureManager field _textureAtlases -> CTextureAtlas fields modelImages / width / height.
	// This page order is the document's, and becomes AtlasPlacement.pageIndex; it is independent of the
	// renderer's own page numbering, which is derived from drawable encounter order.
	val atlases = Cmo3Import.elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>()
	val pages = atlases.map { atlas -> AtlasPage(atlas.width, atlas.height) }
	// CMO3: CTextureAtlas field cachedAtlasImage - the page's own pixels.  Which resource a drawable's
	// texture points AT is what says whether its stored coordinates are page-space, and it is a fact of
	// the file rather than of the display mode: flipping the mode retargets currentTextureInputData and
	// leaves this alone.  Identity membership is the test, CImageResource being a plain class.
	val atlasPageResources = atlases.mapNotNull { atlas -> atlas.cachedAtlasImage as? CImageResource }.toHashSet()
	val placementByModelImageGuid = HashMap<String, AtlasPlacement>()
	for ((pageIndex, atlas) in atlases.withIndex()) {
		for (entry in Cmo3Import.elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>()) {
			// CMO3: ModelImageEntry field modelImageGuid - the join back to the pooled model image.
			val modelImageGuid = Cmo3Import.uuidOf(entry.modelImageGuid) ?: continue
			// CMO3: ModelImageEntry field materialLocalToAtlasTransform - the packer's work, as a
			// GTransform2 (position, scale, eulerAngle in DEGREES).  Absent scale defaults to 1: a
			// GTransform2 with no scale child is the unscaled case, not a collapse to zero.
			val transform = entry.materialLocalToAtlasTransform as? GTransform2 ?: continue
			val position = transform.position as? GVector2
			val scale = transform.scale as? GVector2
			placementByModelImageGuid[modelImageGuid] =
				AtlasPlacement(
					pageIndex = pageIndex,
					positionX = position?.x ?: 0f,
					positionY = position?.y ?: 0f,
					scaleX = scale?.x ?: 1f,
					scaleY = scale?.y ?: 1f,
					rotationDegrees = transform.eulerAngle,
				)
		}
	}

	// CMO3: CTextureManager field _rawImages -> LayeredImageWrapper field image -> CLayeredImage fields
	// guid / name / psdFile - the artwork files the editor decomposed at import, each keyed by the guid
	// the selector maps below reference.  psdFile is the external-reference <file> shape: its text is
	// the absolute path on the importing machine (docs/format/CMO3.md section 4), advisory here.  The
	// field's legacy name notwithstanding, a flat raster import lands in the same structure, so the
	// format is read off the name's extension rather than assumed.
	val layeredImages =
		Cmo3Import.elementsOf(textureManager._rawImages).mapNotNull { wrapper -> (wrapper as? LayeredImageWrapper)?.image as? CLayeredImage }
	val sources = ArrayList<ArtSource>(layeredImages.size)
	val knownSourceIds = HashSet<String>()
	for (image in layeredImages) {
		val guid = Cmo3Import.uuidOf(image.guid) ?: continue
		if (!knownSourceIds.add(guid)) {
			continue
		}
		val name = image.name?.takeIf { candidate -> candidate.isNotEmpty() } ?: guid
		sources.add(
			ArtSource(
				id = ArtSourceId(guid),
				name = name,
				path = (image.psdFile as? FileRef)?.textPath?.takeIf { path -> path.isNotEmpty() },
				format = name.substringAfterLast('.', missingDelimiterValue = "psd").lowercase(),
			),
		)
	}

	// CMO3: CTextureManager field _modelImageGroups -> CModelImageGroup field _modelImages.
	val modelImages =
		Cmo3Import.elementsOf(textureManager._modelImageGroups)
			.filterIsInstance<CModelImageGroup>()
			.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>() }
	val tiles = ArrayList<AtlasTile>(modelImages.size)
	val imageResourceByTile = HashMap<AtlasTileId, CImageResource>()
	val knownTileIds = HashSet<String>()
	for (modelImage in modelImages) {
		// CMO3: CModelImage field guid - the key every binding and placement references.
		val key = Cmo3Import.uuidOf(modelImage.guid) ?: continue
		// CMO3: CModelImage field _filteredImage - the baked composite raster, carrying its own
		// dimensions, so the inventory needs no decode.  An image with no resource has no art to show
		// and no size to place, so it is not a tile at all.
		val resource = modelImage._filteredImage as? CImageResource ?: continue
		val tileId = AtlasTileId(key)
		knownTileIds.add(key)
		imageResourceByTile[tileId] = resource
		tiles.add(
			AtlasTile(
				id = tileId,
				// CMO3: CModelImage field name - the editor writes the drawable's own name here.
				name = displayNameOf(modelImage.name) ?: key,
				width = resource.width,
				height = resource.height,
				placement = placementByModelImageGuid[key],
				source = soleSourceLayerRefOf(modelImage, knownSourceIds),
			),
		)
	}

	// CMO3: CDrawableSourceSet field _sources -> CArtMeshSource field _extensions.
	val artMeshes =
		Cmo3Import.elementsOf((modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources)
			.filterIsInstance<CArtMeshSource>()
	val tileIdByDrawableId = HashMap<String, AtlasTileId>()
	var anyDrawableSamplesAPage = false
	for (mesh in artMeshes) {
		val drawableId = Cmo3Import.idStrOf(mesh.id) ?: continue
		// CMO3: CTextureInputExtension field _textureInputs -> CTextureInput_ModelImage field
		// _modelImageGuid.  A drawable carrying only an atlas-region input (no model image) has no
		// source art and is simply absent from the map.
		val extension =
			Cmo3Import.elementsOf(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
		val modelImageInput =
			Cmo3Import.elementsOf(extension._textureInputs).filterIsInstance<CTextureInput_ModelImage>().firstOrNull()
				?: continue
		val key = Cmo3Import.uuidOf(modelImageInput._modelImageGuid) ?: continue
		if (key !in knownTileIds) {
			continue
		}
		// CMO3: CArtMeshSource field texture -> GTexture2D field srcImageResource.
		val sampledResource = (mesh.texture as? GTexture2D)?.srcImageResource as? CImageResource
		if (sampledResource != null && sampledResource in atlasPageResources) {
			anyDrawableSamplesAPage = true
		}
		tileIdByDrawableId[drawableId] = AtlasTileId(key)
	}

	return Cmo3AtlasIngest(
		PuppetAtlas(pages, tiles, storedUvsAddressPages = anyDrawableSamplesAPage),
		tileIdByDrawableId,
		imageResourceByTile,
		sources,
	)
}

/**
 * The source-layer binding of a model image, when exactly one artwork layer composites into it -
 * which is every model image across the corpus - else null.
 *
 * The binding is name-keyed and marked unstable: the editor's decomposed layer tree carries no
 * format-minted id, so the only key a CMO3 can offer is the layer's name at import, which holds
 * exactly as long as the artist's layer organisation does.
 *
 * @param CModelImage modelImage     The model image to resolve.
 * @param Set         knownSourceIds The layered-image guids the texture manager lists; a map keyed
 *   by an unlisted image binds to nothing rather than to a source the document does not have.
 * @return SourceLayerRef? The binding, or null.
 */
private fun soleSourceLayerRefOf(modelImage: CModelImage, knownSourceIds: Set<String>): SourceLayerRef? {
	// CMO3: CModelImage field inputFilterEnv -> ModelImageFilterEnv (a FilterEnv) field envValues, a map
	// from filter-value ids to EnvValueSet; the set under mi_input_layerInputData holds the
	// CLayerSelectorMap whose _imageToLayerInput maps each layered image's guid to the list of layer
	// inputs composited from it.  Found by the value's type rather than the id: the id is an Id node
	// whose equality the deserializer does not promise, and only one set holds a selector map.
	val filterEnv = modelImage.inputFilterEnv as? FilterEnv ?: return null
	val selectorMap =
		Cmo3Import.elementsOf(filterEnv.envValues).filterIsInstance<EnvValueSet>()
			.firstNotNullOfOrNull { valueSet -> valueSet.value as? CLayerSelectorMap } ?: return null
	val imageToInputs = selectorMap._imageToLayerInput as? Map<*, *> ?: return null
	var soleImageGuid: String? = null
	var soleInput: CLayerInputData? = null
	var inputCount = 0
	for ((imageGuid, inputs) in imageToInputs) {
		for (input in Cmo3Import.elementsOf(inputs).filterIsInstance<CLayerInputData>()) {
			inputCount++
			soleImageGuid = Cmo3Import.uuidOf(imageGuid)
			soleInput = input
		}
	}
	if (inputCount != 1) {
		return null
	}
	val sourceGuid = soleImageGuid?.takeIf { guid -> guid in knownSourceIds } ?: return null
	// CMO3: CLayerInputData field layer -> ACLayerEntry field name.
	val layerName = (soleInput?.layer as? ACLayerEntry)?.name?.takeIf { name -> name.isNotEmpty() } ?: return null
	return SourceLayerRef(ArtSourceId(sourceGuid), layerKey = "name:$layerName", stableKey = false)
}

/**
 * Resolves a CMO3 name slot, which the serializer holds as `Any?` and fills with either a plain string
 * or an id-bearing node.
 *
 * @param Any? name The raw name field.
 * @return String? The display name, or null when the slot holds neither shape.
 */
private fun displayNameOf(name: Any?): String? =
	when (name) {
		is String -> name.takeIf { candidate -> candidate.isNotEmpty() }
		is Id -> name.idstr.takeIf { candidate -> candidate.isNotEmpty() }
		else -> null
	}