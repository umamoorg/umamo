package org.umamo.reimport

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.art.analyzeAlpha
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.ArtworkReload
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.ReplacedTile
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.isUntouchedBirthQuad
import org.umamo.runtime.model.reloadTileId
import org.umamo.runtime.model.storedToArtAffineForTile

/*
 * The reload planner: the re-read file's matched and added halves turned into the model delta a
 * reload commits.  Pure over the model and the art - the pixels of the OLD tiles come in through a
 * callback, the pixels of the new ones go out beside the delta - so the same plan runs against the
 * live model and against the operation strip's base when a row is adjusted.
 *
 * What it never does: touch a tile whose layer the file lost (the reconcile flags it for review),
 * change a drawable's positions, or delete anything.  A drawable's mesh changes only in the two
 * ways Alexia decided: an untouched birth quad is re-born over the new art, and an edited mesh keeps
 * its vertices with its texture coordinates carried so each vertex samples the canvas pixel it did
 * before - Cubism's own re-import behaviour, where the art moves under a mesh that stays.
 */

/**
 * A planned reload: the delta, the new tiles' pixels, the reconcile it followed, and its notes.
 *
 * @property ArtworkReload   reload       The model delta.
 * @property Map             rasterByTile Each new tile's pixels, by its new id.
 * @property ReconcileReport report       The classification the plan followed (review items included).
 * @property List            notices      What the reload could not carry as drawn, in document order.
 */
class ReloadPlan(
	val reload: ArtworkReload,
	val rasterByTile: Map<AtlasTileId, LayerRaster>,
	val report: ReconcileReport,
	val notices: List<SourceArtImportNotice>,
)

/** One tile's replacement and everything that came with it. */
private class TileReplacement(
	val replaced: ReplacedTile,
	val raster: LayerRaster,
	val meshes: Map<DrawableId, DrawableMesh>,
	val outgrown: List<DrawableId>,
)

/** Half a pixel: how far a mesh may fall short of the opaque art before it counts as outgrown. */
private const val COVERAGE_TOLERANCE = 0.5f

/**
 * Plans reloads and relinks.  See the file comment for the rules.
 */
object ArtworkReloadPlanner {
	/**
	 * Plans re-reading one listed file into [model].
	 *
	 * A bound tile is replaced when its layer's pixels, size, or canvas bounds differ from what the
	 * document holds; an unchanged layer costs nothing.  Raster layers no tile is bound to are minted
	 * through the bridge under the same source.  The source record takes the inventory as just read.
	 * Null when the file is not one the model lists or when nothing at all changed - not even the
	 * inventory - so a reload with nothing to do pushes no step.
	 *
	 * @param PuppetModel            model       The model the plan applies to.
	 * @param ArtSourceId            sourceId    The listed file being re-read.
	 * @param SourceArt              art         The file as just read.
	 * @param SourceArtImportOptions options     The threshold and margin every re-born quad and added layer use.
	 * @param Function               oldRasterOf The document's pixels for a tile, or null when it has none.
	 * @param String?                contentHash The whole-file content hash of the bytes [art] was read from,
	 *   recorded on the refreshed source; null keeps the record's.  A hash that changed while nothing in the
	 *   layers did does not by itself make a plan - the watcher acknowledges such a save instead.
	 * @return ReloadPlan? The plan, or null when there is nothing to commit.
	 */
	fun plan(
		model: PuppetModel,
		sourceId: ArtSourceId,
		art: SourceArt,
		options: SourceArtImportOptions,
		oldRasterOf: (AtlasTileId) -> LayerRaster?,
		contentHash: String? = null,
	): ReloadPlan? {
		val source = model.sources.firstOrNull { candidate -> candidate.id == sourceId } ?: return null
		val layersByKey = rasterLayersByKey(art)
		val boundTiles = model.atlas.tiles.filter { tile -> tile.source?.sourceId == sourceId }
		val report = KeyReconciler.reconcile(boundTiles.mapNotNull { tile -> tile.source }, art)
		val oldInventoryByKey = source.layers.associateBy { layer -> layer.key }
		val taken = model.atlas.tiles.mapTo(HashSet()) { tile -> tile.id }
		val replaced = ArrayList<ReplacedTile>()
		val meshes = LinkedHashMap<DrawableId, DrawableMesh>()
		val outgrown = ArrayList<DrawableId>()
		val rasters = LinkedHashMap<AtlasTileId, LayerRaster>()
		val notices = ArrayList<SourceArtImportNotice>()
		for (tile in boundTiles) {
			val ref = tile.source ?: continue
			val layer = layersByKey[ref.layerKey] ?: continue
			val replacement =
				replaceTile(model, tile, layer, ref, oldInventoryByKey[ref.layerKey], options, oldRasterOf, taken, notices, force = false)
					?: continue
			replaced.add(replacement.replaced)
			rasters[replacement.replaced.tile.id] = replacement.raster
			meshes.putAll(replacement.meshes)
			outgrown.addAll(replacement.outgrown)
		}
		val addedKeys = report.results.filterIsInstance<ReconcileResult.Added>().mapTo(HashSet()) { result -> result.layerKey }
		val added =
			if (addedKeys.isEmpty()) {
				null
			} else {
				val subset = LayerSubsetArt(art, art.layers.filter { layer -> layer.id.raw in addedKeys })
				SourceArtImport.additionsFor(subset, ArtSourceDescriptor(source.name, source.path, source.format), options, model, underSource = sourceId)
			}
		if (added != null) {
			rasters.putAll(added.rasterByTile)
			notices.addAll(added.notices)
		}
		val refreshed = source.copy(layers = SourceArtImport.inventoryOf(art), contentHash = contentHash ?: source.contentHash)
		if (replaced.isEmpty() && added == null && refreshed.copy(contentHash = source.contentHash) == source) {
			return null
		}
		return ReloadPlan(ArtworkReload(refreshed, replaced, meshes, added?.additions, outgrown), rasters, report, notices)
	}

	/**
	 * Plans rebinding one tile to [ref]'s layer with that layer's art pulled in: the tile is replaced
	 * whenever the binding changes or the layer's art differs from what the tile holds, and the layer's
	 * file takes the inventory as just read.  Null when the tile or the file is unknown, the layer is not
	 * a raster layer with art, or the tile already holds exactly this binding and this art - the caller
	 * then has nothing to pull and rewrites the binding alone if it differs.
	 *
	 * @param PuppetModel            model       The model the plan applies to.
	 * @param AtlasTileId            tileId      The tile being rebound.
	 * @param SourceLayerRef         ref         The binding it takes.
	 * @param SourceArt              art         The file [ref] names, as just read.
	 * @param SourceArtImportOptions options     The threshold and margin a re-born quad uses.
	 * @param Function               oldRasterOf The document's pixels for a tile, or null when it has none.
	 * @return ReloadPlan? The plan, or null when there is nothing to pull.
	 */
	fun planRelink(
		model: PuppetModel,
		tileId: AtlasTileId,
		ref: SourceLayerRef,
		art: SourceArt,
		options: SourceArtImportOptions,
		oldRasterOf: (AtlasTileId) -> LayerRaster?,
	): ReloadPlan? {
		val tile = model.atlas.tileById[tileId] ?: return null
		val source = model.sources.firstOrNull { candidate -> candidate.id == ref.sourceId } ?: return null
		val layer = rasterLayersByKey(art)[ref.layerKey] ?: return null
		val oldRef = tile.source
		val oldInventory =
			oldRef?.let { previous ->
				model.sources.firstOrNull { candidate -> candidate.id == previous.sourceId }?.layers?.firstOrNull { row -> row.key == previous.layerKey }
			}
		val taken = model.atlas.tiles.mapTo(HashSet()) { candidate -> candidate.id }
		val notices = ArrayList<SourceArtImportNotice>()
		val replacement =
			replaceTile(model, tile, layer, ref, oldInventory, options, oldRasterOf, taken, notices, force = oldRef != ref) ?: return null
		val refreshed = source.copy(layers = SourceArtImport.inventoryOf(art))
		return ReloadPlan(
			ArtworkReload(refreshed, listOf(replacement.replaced), replacement.meshes, additions = null, outgrown = replacement.outgrown),
			mapOf(replacement.replaced.tile.id to replacement.raster),
			ReconcileReport(listOf(ReconcileResult.Matched(ref, ref.layerKey))),
			notices,
		)
	}

	/**
	 * The raster layers of [art] by key - the only layers a tile can be bound to.
	 *
	 * @param SourceArt art The file as read.
	 * @return Map The raster layers, keyed by the reader's key.
	 */
	private fun rasterLayersByKey(art: SourceArt): Map<String, SourceLayer> =
		art.layers.filter { layer -> layer.kind == SourceLayerKind.Raster }.associateBy { layer -> layer.id.raw }

	/**
	 * Replaces one tile with [layer]'s art when the art differs from what the tile holds (or when
	 * [force] says the tile must change regardless, a rebinding), deciding each drawable's mesh over it.
	 *
	 * @param PuppetModel            model        The model the tile lives in.
	 * @param AtlasTile              tile         The tile being replaced.
	 * @param SourceLayer            layer        The layer whose art it takes.
	 * @param SourceLayerRef         ref          The binding the replacement carries.
	 * @param ArtSourceLayer?        oldInventory The layer's inventory row at the last read, for the old
	 *   art frame's canvas origin; null when the document never recorded one.
	 * @param SourceArtImportOptions options      The threshold and margin a re-born quad uses.
	 * @param Function               oldRasterOf  The document's pixels for the tile.
	 * @param MutableSet             taken        Every tile id in use; the new id is added to it.
	 * @param MutableList            notices      Appended with anything the replacement could not carry.
	 * @param Boolean                force        Replace even when the art is unchanged.
	 * @return TileReplacement? The replacement, or null when there is nothing to change.
	 */
	private fun replaceTile(
		model: PuppetModel,
		tile: AtlasTile,
		layer: SourceLayer,
		ref: SourceLayerRef,
		oldInventory: ArtSourceLayer?,
		options: SourceArtImportOptions,
		oldRasterOf: (AtlasTileId) -> LayerRaster?,
		taken: MutableSet<AtlasTileId>,
		notices: MutableList<SourceArtImportNotice>,
		force: Boolean,
	): TileReplacement? {
		val raster = layer.raster
		val old = oldRasterOf(tile.id)
		val boundsUnchanged =
			oldInventory != null &&
				oldInventory.left == layer.bounds.left &&
				oldInventory.top == layer.bounds.top &&
				oldInventory.width == layer.bounds.width &&
				oldInventory.height == layer.bounds.height
		val pixelsUnchanged =
			old != null && old.width == raster.width && old.height == raster.height && old.rgba.contentEquals(raster.rgba)
		if (!force && boundsUnchanged && pixelsUnchanged) {
			return null
		}
		// A layer erased to nothing has no art to give: the tile keeps what it has, and the note says why.
		val analysis = layer.analyzeAlpha(alphaThreshold = options.alphaThreshold)
		if (analysis == null) {
			notices.add(SourceArtImportNotice.EmptyLayer(layer.name))
			return null
		}
		val newId = reloadTileId(tile.id, taken)
		taken.add(newId)
		val newTile =
			AtlasTile(
				id = newId,
				name = layer.name,
				width = raster.width,
				height = raster.height,
				placement = null,
				source = ref,
				pinned = tile.pinned,
				replaces = tile.id,
			)
		// The old art frame's canvas origin: the inventory's record of where the layer sat, else the
		// layer's own bounds now (a document that never recorded it can only assume the art stayed put).
		val oldLeft = (oldInventory?.left ?: layer.bounds.left).toFloat()
		val oldTop = (oldInventory?.top ?: layer.bounds.top).toFloat()
		val newLeft = layer.bounds.left.toFloat()
		val newTop = layer.bounds.top.toFloat()
		val opaqueOnCanvas =
			LayerBounds(
				left = layer.bounds.left + analysis.opaqueBounds.left,
				top = layer.bounds.top + analysis.opaqueBounds.top,
				width = analysis.opaqueBounds.width,
				height = analysis.opaqueBounds.height,
			)
		val storedToArt = model.atlas.storedToArtAffineForTile(tile.id)
		val meshes = LinkedHashMap<DrawableId, DrawableMesh>()
		val outgrown = ArrayList<DrawableId>()
		for (drawable in model.drawables) {
			if (drawable.atlasTileId != tile.id) {
				continue
			}
			val mesh = drawable.mesh ?: continue
			if (storedToArt == null) {
				// The tile's mapping cannot be read (a placement naming a page the atlas lacks); the mesh
				// is left as it is rather than guessed at.
				continue
			}
			val artUvs = applyUvAffine(mesh.uvs, storedToArt)
			if (mesh.isUntouchedBirthQuad(artUvs, tile.width, tile.height, oldLeft, oldTop)) {
				val reborn = SourceArtImport.birthMeshFor(layer, options.alphaThreshold, options.birthMeshMargin) ?: continue
				meshes[drawable.id] = reborn
				continue
			}
			// Canvas-attached: the vertex stays where it is and keeps sampling the canvas pixel under it,
			// so the coordinate moves from the old art frame to the new one through the bounds delta.
			val carried = FloatArray(artUvs.size)
			for (vertex in 0 until artUvs.size / 2) {
				carried[vertex * 2] = (artUvs[vertex * 2] * tile.width + oldLeft - newLeft) / raster.width
				carried[vertex * 2 + 1] = (artUvs[vertex * 2 + 1] * tile.height + oldTop - newTop) / raster.height
			}
			meshes[drawable.id] = DrawableMesh(mesh.positions, carried, mesh.indices)
			if (!covers(mesh.positions, opaqueOnCanvas)) {
				outgrown.add(drawable.id)
			}
		}
		return TileReplacement(ReplacedTile(tile.id, newTile), raster, meshes, outgrown)
	}

	/**
	 * Whether a mesh's canvas extent contains the opaque art's canvas rectangle, within half a pixel.
	 *
	 * @param FloatArray  positions The mesh's canvas positions, x then y per vertex.
	 * @param LayerBounds opaque    The opaque art's rectangle on the canvas.
	 * @return Boolean True when the art lies inside the mesh's reach.
	 */
	private fun covers(positions: FloatArray, opaque: LayerBounds): Boolean {
		if (positions.size < 2) {
			return false
		}
		var minX = Float.POSITIVE_INFINITY
		var minY = Float.POSITIVE_INFINITY
		var maxX = Float.NEGATIVE_INFINITY
		var maxY = Float.NEGATIVE_INFINITY
		for (vertex in 0 until positions.size / 2) {
			val x = positions[vertex * 2]
			val y = positions[vertex * 2 + 1]
			minX = minOf(minX, x)
			minY = minOf(minY, y)
			maxX = maxOf(maxX, x)
			maxY = maxOf(maxY, y)
		}
		return minX <= opaque.left + COVERAGE_TOLERANCE &&
			minY <= opaque.top + COVERAGE_TOLERANCE &&
			maxX >= opaque.left + opaque.width - COVERAGE_TOLERANCE &&
			maxY >= opaque.top + opaque.height - COVERAGE_TOLERANCE
	}
}