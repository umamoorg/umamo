package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.composeAffine
import org.umamo.runtime.model.inversePlacementAffine
import org.umamo.runtime.model.placementAffine
import java.util.IdentityHashMap

/**
 * The full atlas-web reconcile: page set, entry membership, drawable texture
 * references, and page images, brought to the EDITED model's packing as one all-or-nothing pass.
 *
 * The membership half is the load-bearing one: a ModelImageEntry physically lives inside its page's
 * CTextureAtlas element, and the import reads a tile's page purely from which atlas holds its entry.
 * Rewriting the entry's transforms without also moving the entry between atlases would leave a file
 * whose placements point at one page while the membership still names another - a jumbled reimport,
 * the failure mode this class exists to close.
 *
 * Everything here follows the model's page order: model page i is `_textureAtlases[i]`, so the page
 * set reconciles positionally - retained atlases are reused in place (their page resources mutated,
 * never replaced, because page-resource IDENTITY is what storedUvsAddressPages recovery reads),
 * atlases past the retained count are minted with the fresh conversion's own builders, and retained
 * atlases past the edited count are deleted after everything living has moved out of them.
 *
 * A pack-in - a bound tile that never had an entry gaining a placement - mints the entry and each
 * bound drawable's region input, so a whole-model repack of a document with never-packed bound art
 * (Erica carries three such drawables) exports consistently too.
 *
 * All or nothing: validation runs first and any gap declines the WHOLE reconcile - the honest
 * notices then flow from the property lowering unchanged.  Nothing here may half-write, because the
 * reconcile has no rollback.
 */
internal class Cmo3AtlasWebLowering(
	private val target: Cmo3Model,
	private val modelSource: CModelSource,
	private val baseline: PuppetModel,
	private val edited: PuppetModel,
) {
	/**
	 * What the reconcile did.
	 *
	 * @property Boolean pagesRecomposed True when the full web reconcile ran; the stale-page notices
	 *                                   key on it.
	 * @property Boolean pruneNeeded     True when graph structure was dropped, so the export must run
	 *                                   the shared-pool prune.
	 */
	class Result(
		val pagesRecomposed: Boolean,
		val pruneNeeded: Boolean,
	) {
		companion object {
			/** The no-op outcome: nothing touched, notices stay. */
			val Declined: Result = Result(pagesRecomposed = false, pruneNeeded = false)
		}
	}

	/** One drawable source whose region input must be rewritten for its tile's new placement. */
	private class RetargetCandidate(
		val source: CArtMeshSource,
		val region: CTextureInput_TextureAtlasRegion,
		val input: CAffine,
		val tileId: String,
	)

	/** One drawable source over a packed-IN tile, which gains a fresh region input. */
	private class PackInCandidate(
		val source: CArtMeshSource,
		val extension: CTextureInputExtension,
		val tileId: String,
	)

	/**
	 * Reconciles the atlas web onto [recomposedPages], or declines whole.
	 *
	 * @param List recomposedPages The pages composed for the edited model's packing, in its page order.
	 * @return Result Whether the reconcile ran, and whether it dropped structure.
	 */
	fun reconcile(recomposedPages: List<Cmo3Conversion.AtlasPage>): Result {
		if (recomposedPages.isEmpty()) {
			return Result.Declined
		}
		// CMO3: CModelSource field textureManager -> CTextureManager field _textureAtlases.
		val textureManager = modelSource.textureManager as? CTextureManager ?: return Result.Declined
		val atlasList = mutableGraphListOf(textureManager._textureAtlases) ?: return Result.Declined
		if (atlasList.any { member -> member !is CTextureAtlas }) {
			return Result.Declined
		}
		val newPageCount = edited.atlas.pages.size
		// The pages are the EDITED model's inventory, in its own page order: same count, positionally
		// indexed.  The patch stays authoritative on dimensions, matching the reuse arm's existing
		// contract.
		if (recomposedPages.size != newPageCount) {
			return Result.Declined
		}

		// --- Gather the web. ---
		val web = indexAtlasWeb(textureManager)
		val siteByTileId = web.siteByTileId
		val strayEntryAtlasIndices = web.strayEntryAtlasIndices
		val canvasAffineByTileId = web.canvasAffineByTileId
		val modelImageByTileId = web.modelImageByTileId

		// --- Classify every edited tile; ANY gap declines whole. ---
		val baselinePlacementByTileId = baseline.atlas.tiles.associateBy({ tile -> tile.id.raw }, { tile -> tile.placement })
		val editedTileIds = HashSet<String>()
		val changedPlacementByTileId = HashMap<String, Pair<AtlasPlacement?, AtlasPlacement>>()
		val boundTileIds = edited.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId?.raw }
		val moves = ArrayList<Pair<AtlasEntrySite, AtlasPlacement>>()
		val packOuts = ArrayList<AtlasEntrySite>()
		val packInPlacementByTileId = LinkedHashMap<String, AtlasPlacement>()
		for (tile in edited.atlas.tiles) {
			val tileId = tile.id.raw
			editedTileIds.add(tileId)
			val newPlacement = tile.placement
			val site = siteByTileId[tileId]
			if (newPlacement == null) {
				if (site != null) {
					// A pack-out removes the entry; a drawable still sampling the page through it
					// would be stranded, so a BOUND pack-out declines (the repack never produces one).
					if (tileId in boundTileIds) {
						return Result.Declined
					}
					packOuts.add(site)
				}
				continue
			}
			if (site == null) {
				// Pack-in: a never-packed tile gaining a placement mints its entry and its bound
				// drawables' region inputs below - possible only when the tile's model image and
				// canvas placement resolve.
				if (canvasAffineByTileId[tileId] == null || modelImageByTileId[tileId] == null) {
					return Result.Declined
				}
				if (newPlacement.pageIndex !in 0 until newPageCount || inversePlacementAffine(newPlacement) == null) {
					return Result.Declined
				}
				packInPlacementByTileId[tileId] = newPlacement
				continue
			}
			if (newPlacement.pageIndex !in 0 until newPageCount) {
				return Result.Declined
			}
			if (canvasAffineByTileId[tileId] == null || inversePlacementAffine(newPlacement) == null) {
				return Result.Declined
			}
			val oldPlacement = baselinePlacementByTileId[tileId]
			if (oldPlacement != newPlacement) {
				changedPlacementByTileId[tileId] = oldPlacement to newPlacement
			}
			if (newPlacement.pageIndex != site.atlasIndex) {
				moves.add(site to newPlacement)
			}
		}
		// Entries the edited model does not track (stray, or a tile outside the edited set) stay
		// where they sit - which must survive the page-set reconcile.
		for (atlasIndex in strayEntryAtlasIndices) {
			if (atlasIndex >= newPageCount) {
				return Result.Declined
			}
		}
		for ((tileId, site) in siteByTileId) {
			if (tileId !in editedTileIds && site.atlasIndex >= newPageCount) {
				return Result.Declined
			}
		}

		// --- Gather the drawables to retarget: every source over a placement-changed tile. ---
		// CMO3: CDrawableSourceSet field _sources -> CArtMeshSource field _extensions ->
		// CTextureInputExtension field _textureInputs.
		val artMeshes =
			Cmo3Import.elementsOf((modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources)
				.filterIsInstance<CArtMeshSource>()
		val editedDrawableIds = edited.drawables.mapTo(HashSet()) { drawable -> drawable.id.raw }
		val candidates = ArrayList<RetargetCandidate>()
		val packInCandidates = ArrayList<PackInCandidate>()
		val existingTextureByAtlasIndex = HashMap<Int, GTexture2D>()
		for (mesh in artMeshes) {
			val drawableId = Cmo3Import.idStrOf(mesh.id) ?: continue
			val texture = mesh.texture as? GTexture2D
			if (texture != null) {
				// The page's ONE shared texture, found by resource identity - the same rule the
				// import's page-packed classification reads.
				val sampled = texture.srcImageResource
				for ((atlasIndex, member) in atlasList.withIndex()) {
					if ((member as CTextureAtlas).cachedAtlasImage === sampled) {
						existingTextureByAtlasIndex.putIfAbsent(atlasIndex, texture)
					}
				}
			}
			if (drawableId !in editedDrawableIds) {
				// Pending deletion: the structural pass removes the source; nothing to retarget.
				continue
			}
			val extension =
				Cmo3Import.elementsOf(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
			val tileId =
				Cmo3Import.uuidOf(
					Cmo3Import.elementsOf(extension._textureInputs)
						.filterIsInstance<CTextureInput_ModelImage>()
						.firstOrNull()
						?._modelImageGuid,
				) ?: continue
			val region =
				Cmo3Import.elementsOf(extension._textureInputs)
					.filterIsInstance<CTextureInput_TextureAtlasRegion>()
					.firstOrNull()
			if (tileId in packInPlacementByTileId) {
				if (region != null) {
					// A region input over a tile with no entry is an inconsistent web; decline whole
					// rather than guess which half to trust.
					return Result.Declined
				}
				packInCandidates.add(PackInCandidate(mesh, extension, tileId))
				continue
			}
			if (tileId !in changedPlacementByTileId) {
				continue
			}
			if (region == null) {
				// A bound drawable sampling its art directly while its tile's packing moved: the
				// re-derived page-frame coordinates have no region input to ride, so decline whole.
				return Result.Declined
			}
			val input = region.inputImageLocalToCanvasTransform as? CAffine ?: return Result.Declined
			candidates.add(RetargetCandidate(mesh, region, input, tileId))
		}
		// Every EDITED drawable over a packed-in tile must have been found file-side, or its
		// re-derived page-frame coordinates would export with nothing retargeting its sampling.
		for (tileId in packInPlacementByTileId.keys) {
			val editedBound = edited.drawables.count { drawable -> drawable.atlasTileId?.raw == tileId }
			val found = packInCandidates.count { candidate -> candidate.tileId == tileId }
			if (editedBound != found) {
				return Result.Declined
			}
		}

		// --- Mutation.  Validation is complete; anything impossible past here is a caller bug. ---
		// 1. Snapshots, so every rewrite reads pre-mutation values regardless of sharing.
		val oldEntryHalfByTileId = HashMap<String, FloatArray>()
		for (tileId in changedPlacementByTileId.keys) {
			val entryHalf = siteByTileId.getValue(tileId).entry.atlasLocalToCanvasTransform as? CAffine ?: continue
			oldEntryHalfByTileId[tileId] = entryHalf.toAffineArray()
		}
		val oldInputByRegion = IdentityHashMap<CTextureInput_TextureAtlasRegion, FloatArray>()
		for (candidate in candidates) {
			oldInputByRegion.getOrPut(candidate.region) { candidate.input.toAffineArray() }
		}

		// 2. Reuse retained pages in model order: bytes always, the dimension web on a resize.  The
		// resource is mutated IN PLACE, never replaced - its identity is the storedUvsAddressPages
		// recovery test.
		val retainedCount = minOf(atlasList.size, newPageCount)
		for (pageIndex in 0 until retainedCount) {
			val atlas = atlasList[pageIndex] as CTextureAtlas
			val page = recomposedPages[pageIndex]
			// CMO3: CTextureAtlas field cachedAtlasImage - the page's CImageResource.
			val resource = atlas.cachedAtlasImage as? CImageResource ?: return Result.Declined
			if (resource.imageFileBuf == null) {
				return Result.Declined
			}
			target.replaceLayerPng(resource, page.pngBytes)
			if (resource.width != page.width || resource.height != page.height) {
				// CMO3: CImageResource fields width / height - corpus-verified to equal the decoded PNG's.
				resource.width = page.width
				resource.height = page.height
				// CMO3: CTextureAtlas fields width / height - the page extent the reader trusts.
				atlas.width = page.width
				atlas.height = page.height
				// CMO3: CTextureAtlas field cachedImageManager - rebuilt so the cache raster's
				// 64-aligned padding transform describes the NEW dimensions.
				atlas.cachedImageManager = Cmo3ImageChainBuilder.paddedCacheManager(resource, page.width, page.height)
			}
		}

		// 3. Mint pages past the retained count, with the fresh conversion's own builders.
		val mintedTextureByAtlasIndex = HashMap<Int, GTexture2D>()
		for (pageIndex in atlasList.size until newPageCount) {
			val page = recomposedPages[pageIndex]
			val path = target.nextImageFileBufPath()
			val resource = Cmo3ImageChainBuilder.pageImageResource(path, page.width, page.height, page.pngBytes.size)
			target.addLayerPng(resource, page.pngBytes)
			val atlas = Cmo3ImageChainBuilder.pageAtlas(pageIndex, page.width, page.height, resource)
			atlasList.add(atlas)
			mintedTextureByAtlasIndex[pageIndex] = Cmo3ImageChainBuilder.pageTexture(atlas.name, resource)
		}

		// 4. Re-home every cross-page entry: list membership plus the owning-page back-reference.
		for ((site, newPlacement) in moves) {
			val destination = atlasList[newPlacement.pageIndex] as CTextureAtlas
			mutableGraphListOf(site.atlas.modelImages)?.remove(site.entry)
			mutableGraphListOf(destination.modelImages)?.add(site.entry)
			// CMO3: ModelImageEntry field atlas - the owning-page back-reference.
			site.entry.atlas = destination
		}

		// 5. Pack-outs: the entry leaves its page (import reads placement=null from entry absence);
		// the tile's CModelImage web stays - the art inventory is untouched.
		for (site in packOuts) {
			mutableGraphListOf(site.atlas.modelImages)?.remove(site.entry)
		}

		// 6. Pack-ins: mint the entry the tile never had.  The transforms are written with the same
		// shared math the property lowering uses, which then rewrites them identically for the
		// placement diff - the mint exists so an entry is THERE to rewrite.
		for ((tileId, newPlacement) in packInPlacementByTileId) {
			val destination = atlasList[newPlacement.pageIndex] as CTextureAtlas
			val modelImage = modelImageByTileId.getValue(tileId)
			val canvasAffine = canvasAffineByTileId.getValue(tileId)
			val entryHalf =
				checkNotNull(atlasLocalToCanvasFor(canvasAffine, newPlacement)) {
					"pack-in tile '$tileId' lost its composition after validation"
				}
			val entry =
				Cmo3ImageChainBuilder.packedEntry(
					atlas = destination,
					modelImageGuid = modelImage.guid,
					atlasLocalToCanvas = affineOf(entryHalf),
					packing = Cmo3ImageChainBuilder.writePacking(GTransform2(), newPlacement),
				)
			mutableGraphListOf(destination.modelImages)?.add(entry)
		}

		// 7. Delete retained atlases past the edited count, highest first.  Validation moved or
		// removed every tracked entry; a leftover here is a validation bug, not document state.
		var deletedAny = false
		for (pageIndex in atlasList.size - 1 downTo newPageCount) {
			val atlas = atlasList[pageIndex] as CTextureAtlas
			check(Cmo3Import.elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>().isEmpty()) {
				"atlas $pageIndex still holds entries after the membership reconcile"
			}
			(atlas.cachedAtlasImage as? CImageResource)?.let { resource -> target.removeLayerPng(resource) }
			atlasList.removeAt(pageIndex)
			deletedAny = true
		}

		// 8. Retarget every candidate from the snapshots.
		fun pageTextureFor(pageIndex: Int): GTexture2D {
			mintedTextureByAtlasIndex[pageIndex]?.let { minted -> return minted }
			return existingTextureByAtlasIndex.getOrPut(pageIndex) {
				// No drawable ever sampled this retained page; give it the shared texture the fresh
				// path would have built.
				val atlas = atlasList[pageIndex] as CTextureAtlas
				Cmo3ImageChainBuilder.pageTexture(atlas.name, atlas.cachedAtlasImage as CImageResource)
			}
		}
		for (candidate in candidates) {
			val (oldPlacement, newPlacement) = changedPlacementByTileId.getValue(candidate.tileId)
			val site = siteByTileId.getValue(candidate.tileId)
			if (newPlacement.pageIndex != site.atlasIndex) {
				val destination = atlasList[newPlacement.pageIndex] as CTextureAtlas
				// CMO3: CArtMeshSource field texture - the page's shared GTexture2D.
				candidate.source.texture = pageTextureFor(newPlacement.pageIndex)
				// CMO3: CTextureInput_TextureAtlasRegion field textureAtlasGuid - repointed at the
				// destination atlas's own guid object (the official files' shared-ref shape).
				candidate.region.textureAtlasGuid = destination.guid
			}
			val canvasAffine = canvasAffineByTileId.getValue(candidate.tileId)
			val snapshotInput = oldInputByRegion.getValue(candidate.region)
			val entryOldHalf = oldEntryHalfByTileId[candidate.tileId]
			// CMO3: CTextureInput_TextureAtlasRegion field inputImageLocalToCanvasTransform - the
			// PAGE-pixels-to-canvas placement the editor inverts for its mesh-over-patch views.  The
			// corpus keeps it equal to the entry's atlasLocalToCanvasTransform, so when the old input
			// matched the entry's old half it is written through the same shared math the entry pair
			// takes (bit-identical); a drawable carrying its own placement (a shared tile sampled
			// from two spots) keeps its own via the exact re-fit oldInput
			// composed-with P_old composed-with P_new-inverse.
			val newInput =
				if (entryOldHalf != null && snapshotInput.contentEquals(entryOldHalf)) {
					atlasLocalToCanvasFor(canvasAffine, newPlacement)
				} else if (oldPlacement != null) {
					inversePlacementAffine(newPlacement)?.let { inverseNew ->
						composeAffine(composeAffine(snapshotInput, placementAffine(oldPlacement)), inverseNew)
					}
				} else {
					atlasLocalToCanvasFor(canvasAffine, newPlacement)
				}
			checkNotNull(newInput) { "tile '${candidate.tileId}' lost its input re-fit after validation" }
			candidate.input.setFromAffineArray(newInput)
		}

		// 9. Pack-in drawables: a fresh region input into the EXISTING extension (the shape
		// freshTextureInputExtension writes for a created drawable), the page's shared texture, and
		// the live input pointer when the document displays from the atlas.  The reconcile runs
		// before the drawable lowering, so storedUvsFor then sees the region and writes the repack's
		// page-frame coordinates verbatim.
		for (candidate in packInCandidates) {
			val newPlacement = packInPlacementByTileId.getValue(candidate.tileId)
			val destination = atlasList[newPlacement.pageIndex] as CTextureAtlas
			val inputAffine =
				checkNotNull(atlasLocalToCanvasFor(canvasAffineByTileId.getValue(candidate.tileId), newPlacement)) {
					"pack-in tile '${candidate.tileId}' lost its input re-fit after validation"
				}
			// CMO3: CArtMeshSource field texture - the page's shared GTexture2D.
			candidate.source.texture = pageTextureFor(newPlacement.pageIndex)
			val region =
				CTextureInput_TextureAtlasRegion().apply {
					// CMO3: CTextureInput_TextureAtlasRegion fields textureAtlasGuid +
					// inputImageLocalToCanvasTransform (ACTextureInput super carries the owner backref).
					optionalTransformOnCanvas = CAffine()
					_owner = candidate.extension
					textureAtlasGuid = destination.guid
					inputImageLocalToCanvasTransform = affineOf(inputAffine)
				}
			mutableGraphListOf(candidate.extension._textureInputs)?.add(region)
			if (!edited.rendersFromSourceLayers) {
				// CMO3: CTextureInputExtension field currentTextureInputData - which input the
				// document's display mode samples.
				candidate.extension.currentTextureInputData = region
			}
		}

		return Result(pagesRecomposed = true, pruneNeeded = deletedAny || packOuts.isNotEmpty())
	}
}