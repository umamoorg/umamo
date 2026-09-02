package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACLayerEntry
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas

/**
 * A CMO3's layered-art web read as model state: the atlas itself, which tile each drawable samples,
 * and where each tile's pixels live in the graph.
 *
 * @property PuppetAtlas atlas               The document's pages and tiles, with each tile's placement.
 * @property Map         tileIdByDrawableId  Raw drawable id to the tile it samples; absent when it has none.
 * @property Map         imageResourceByTile The graph resource holding each tile's pixels, for the
 *   document's pixel supplier - metadata ingest reads no bytes, so decoding stays the caller's.
 */
public class Cmo3AtlasIngest(
	public val atlas: PuppetAtlas,
	public val tileIdByDrawableId: Map<String, AtlasTileId>,
	public val imageResourceByTile: Map<AtlasTileId, CImageResource>,
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
				sourceLayerName = soleSourceLayerNameOf(modelImage),
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
	)
}

/**
 * The originating artwork layer's name when a model image composites exactly one.
 *
 * More than one input means no single layer is the source (the composite is), so this reports none
 * rather than picking arbitrarily.  Recorded for a future source binding; nothing reads it yet.
 *
 * @param CModelImage modelImage The pooled model image.
 * @return String? The sole input layer's name, or null when there is not exactly one named input.
 */
private fun soleSourceLayerNameOf(modelImage: CModelImage): String? {
	// CMO3: CModelImage field inputFilterEnv -> CLayerSelectorMap field _imageToLayerInput, a map from
	// the layered image to the list of layer inputs composited from it.
	val selectorMap = modelImage.inputFilterEnv as? CLayerSelectorMap ?: return null
	val inputs =
		Cmo3Import.elementsOf(selectorMap._imageToLayerInput).flatMap { perImage ->
			Cmo3Import.elementsOf(perImage).filterIsInstance<CLayerInputData>()
		}
	val soleInput = inputs.singleOrNull() ?: return null
	// CMO3: CLayerInputData field layer -> ACLayerEntry field name.
	return (soleInput.layer as? ACLayerEntry)?.name?.takeIf { name -> name.isNotEmpty() }
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