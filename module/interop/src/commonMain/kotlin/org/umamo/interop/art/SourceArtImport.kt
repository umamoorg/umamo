package org.umamo.interop.art

import org.umamo.format.art.ChannelMask
import org.umamo.format.art.DEFAULT_ALPHA_THRESHOLD
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.art.analyzeAlpha
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.deriveRenderRoot

/*
 * The SourceArt -> PuppetModel bridge: the first point where a layered file (or a flat raster)
 * becomes an editable rig.  One drawable per raster layer over a quad birth mesh, one part per
 * folder, one atlas tile per layer bound to its source layer by the reader's key.
 *
 * The model comes out UNPACKED - every tile unplaced, every drawable's coordinates addressing its own
 * art - so this stays a pure conversion that the pack at open then moves onto pages through the same
 * repack primitive the Repack Atlas command uses.  Packing here would only duplicate that path.
 */

/**
 * What the importer records about the file the art came from.
 *
 * @property String  name   The file's display name (its file name).
 * @property String? path   The advisory external path, or null when the platform has none (a SAF uri).
 * @property String  format The source format's file extension ("psd", "clip", "kra", "png", ...).
 */
class ArtSourceDescriptor(
	val name: String,
	val path: String?,
	val format: String,
)

/**
 * How an import shapes the model it builds.
 *
 * @property ParameterTemplate parameterTemplate The parameter set to seed.
 * @property Int               alphaThreshold    Minimum alpha byte (1..255) for a pixel to count as art
 *   when a layer is trimmed for its birth mesh; the pack at open trims under the same threshold.
 * @property Int               birthMeshMargin   How far, in source pixels, the birth quad extends past
 *   the layer's opaque bounds on every side.
 */
class SourceArtImportOptions(
	val parameterTemplate: ParameterTemplate = ParameterTemplate.Default,
	val alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
	val birthMeshMargin: Int = SourceArtImport.DEFAULT_BIRTH_MESH_MARGIN,
) {
	init {
		require(alphaThreshold in 1..255) { "alphaThreshold must be in 1..255: $alphaThreshold" }
		require(birthMeshMargin >= 0) { "birthMeshMargin must be non-negative: $birthMeshMargin" }
	}
}

/**
 * What an import produced.
 *
 * @property PuppetModel puppet       The unpacked model.
 * @property Map         rasterByTile Each tile's pixels, by tile - the document's pixel supplier takes
 *   them from here, since the model itself carries none.
 * @property List        notices      Everything the import could not carry as drawn, in document order.
 */
class SourceArtImportResult(
	val puppet: PuppetModel,
	val rasterByTile: Map<AtlasTileId, LayerRaster>,
	val notices: List<SourceArtImportNotice>,
)

/** One raster layer that became a drawable, kept for the passes that run after the first. */
private class ImportedLayer(
	val layer: SourceLayer,
	val drawableId: DrawableId,
)

/**
 * One folder of the source tree while the org tree is being built: its own attributes plus the
 * children gathered under it, each tagged with the order it sorts by.
 */
private class FolderNode(
	val path: String,
	val name: String,
	val visible: Boolean,
	val opacity: Float,
	val clipped: Boolean,
	val blend: LayerBlend,
	val passThrough: Boolean,
) {
	val children = ArrayList<FolderChild>()

	/** The top-most order under this folder, so it sorts among its siblings where its first layer is. */
	var firstOrder: Int = Int.MAX_VALUE
}

/** One entry of a folder's children: a drawable, or a nested folder, with the order it sorts by. */
private sealed interface FolderChild {
	val order: Int

	class Layer(override val order: Int, val drawableId: DrawableId) : FolderChild

	class Folder(val node: FolderNode) : FolderChild {
		override val order: Int get() = node.firstOrder
	}
}

/**
 * Turns parsed source art into a puppet model - see the file comment for the shape it produces.
 */
object SourceArtImport {
	/** How far the birth quad extends past a layer's opaque bounds, in source pixels. */
	const val DEFAULT_BIRTH_MESH_MARGIN: Int = 2

	/** The id of the one source a first import records. */
	const val FIRST_SOURCE_ID: String = "art-0"

	/**
	 * Builds an unpacked model from [art].
	 *
	 * Layers are visited top-most first (the source's own draw order), and every id the model mints -
	 * `ArtMesh<n>`, `Part<n>`, the tile ids - is sequential in that order, so the same file imports to
	 * the same ids every time and a CMO3 export reads the way the official editor's own import would.
	 *
	 * @param SourceArt              art     The parsed source art.
	 * @param ArtSourceDescriptor    source  What to record about the file it came from.
	 * @param SourceArtImportOptions options The template, threshold, and margin.
	 * @return SourceArtImportResult The model, its tiles' pixels, and the import notices.
	 */
	fun fromSourceArt(
		art: SourceArt,
		source: ArtSourceDescriptor,
		options: SourceArtImportOptions = SourceArtImportOptions(),
	): SourceArtImportResult {
		val sourceId = ArtSourceId(FIRST_SOURCE_ID)
		val layersTopFirst = art.layers.sortedBy { layer -> layer.order }
		val notices = ArrayList<SourceArtImportNotice>()

		// Pass 1: one drawable and one tile per raster layer with art in it.
		val imported = ArrayList<ImportedLayer>()
		val drawables = ArrayList<Drawable>()
		val tiles = ArrayList<AtlasTile>()
		val rasterByTile = LinkedHashMap<AtlasTileId, LayerRaster>()
		val usedTileKeys = HashSet<String>()
		for (layer in layersTopFirst) {
			if (layer.kind != SourceLayerKind.Raster) {
				notices.add(SourceArtImportNotice.NonRasterLayer(layer.name, layer.kind))
				continue
			}
			val analysis = layer.analyzeAlpha(alphaThreshold = options.alphaThreshold)
			if (analysis == null) {
				notices.add(SourceArtImportNotice.EmptyLayer(layer.name))
				continue
			}
			// The reader's key is the tile's identity too, disambiguated by draw order when a weak source
			// key (the PSD name-and-order fallback) collides - the same rule the packer's adapter applies.
			var tileKey = "$FIRST_SOURCE_ID/${layer.id.raw}"
			if (!usedTileKeys.add(tileKey)) {
				tileKey = "$tileKey#${layer.order}"
				usedTileKeys.add(tileKey)
			}
			val tileId = AtlasTileId(tileKey)
			tiles.add(
				AtlasTile(
					id = tileId,
					name = layer.name,
					width = layer.raster.width,
					height = layer.raster.height,
					source = SourceLayerRef(sourceId, layer.id.raw, stableKey = layer.idIsStable),
				),
			)
			rasterByTile[tileId] = layer.raster

			val blendMapping = mapLayerBlend(layer.blend)
			when (blendMapping) {
				is LayerBlendMapping.Exact -> Unit
				is LayerBlendMapping.Approximate -> notices.add(SourceArtImportNotice.BlendApproximated(layer.name, layer.blend, blendMapping.blendMode))
				LayerBlendMapping.Unsupported -> notices.add(SourceArtImportNotice.BlendUnsupported(layer.name, layer.blend))
			}
			if (layer.channelMask != ChannelMask.ALL) {
				notices.add(SourceArtImportNotice.ChannelMaskDropped(layer.name))
			}

			val drawableId = DrawableId("ArtMesh${drawables.size + 1}")
			drawables.add(
				Drawable(
					id = drawableId,
					name = layer.name,
					parentDeformerId = null,
					blendMode = blendMapping.blendMode,
					maskedBy = emptyList(),
					mesh = birthQuad(layer, analysis.opaqueBounds.left, analysis.opaqueBounds.top, analysis.opaqueBounds.width, analysis.opaqueBounds.height, options.birthMeshMargin),
					geometryGrid = null,
					opacity = layer.opacity,
					isVisible = layer.visible,
					atlasTileId = tileId,
				),
			)
			imported.add(ImportedLayer(layer, drawableId))
		}

		// Pass 2: a clipping layer clips to the nearest non-clipped layer BELOW it in its own folder -
		// Photoshop's "clip to layer below", which CLIP and Krita share.  Below is a greater order.
		val drawableIndexById = drawables.withIndex().associate { (index, drawable) -> drawable.id to index }
		for ((position, entry) in imported.withIndex()) {
			if (!entry.layer.clipped) {
				continue
			}
			val base =
				imported.subList(position + 1, imported.size).firstOrNull { candidate ->
					candidate.layer.groupPath == entry.layer.groupPath && !candidate.layer.clipped
				}
			if (base == null) {
				notices.add(SourceArtImportNotice.ClipBaseMissing(entry.layer.name))
				continue
			}
			val index = drawableIndexById.getValue(entry.drawableId)
			drawables[index] = drawables[index].copy(maskedBy = listOf(base.drawableId))
		}

		// Pass 3: the org tree.  Every folder becomes a part, nested by path; each folder sorts among
		// its siblings where its top-most layer sits, so the panel reads in the file's own order.
		val folderByPath = LinkedHashMap<String, FolderNode>()
		for (group in art.groups) {
			folderByPath[group.path] = folderNodeOf(group)
		}
		for (entry in imported) {
			ensureFolders(entry.layer.groupPath, folderByPath)
		}
		val rootChildren = ArrayList<FolderChild>()
		for (folder in folderByPath.values) {
			val parentPath = folder.path.substringBeforeLast('/', missingDelimiterValue = "")
			val siblings = if (parentPath.isEmpty()) rootChildren else folderByPath.getValue(parentPath).children
			siblings.add(FolderChild.Folder(folder))
		}
		for (entry in imported) {
			val parentPath = entry.layer.groupPath
			val siblings = if (parentPath.isEmpty()) rootChildren else folderByPath.getValue(parentPath).children
			siblings.add(FolderChild.Layer(entry.layer.order, entry.drawableId))
			var ancestorPath = parentPath
			while (ancestorPath.isNotEmpty()) {
				val ancestor = folderByPath.getValue(ancestorPath)
				ancestor.firstOrder = minOf(ancestor.firstOrder, entry.layer.order)
				ancestorPath = ancestorPath.substringBeforeLast('/', missingDelimiterValue = "")
			}
		}
		val parts = ArrayList<Part>()
		val orgRoot = orgChildrenOf(rootChildren, parts, notices)

		// The layer inventory records EVERY layer, skipped ones included, so a re-import can tell a
		// layer that was there and unusable from one that is new.
		val inventory =
			layersTopFirst.map { layer ->
				ArtSourceLayer(
					key = layer.id.raw,
					name = layer.name,
					groupPath = layer.groupPath,
					left = layer.bounds.left,
					top = layer.bounds.top,
					width = layer.bounds.width,
					height = layer.bounds.height,
					visible = layer.visible,
				)
			}

		// Rest positions are canvas pixels with y down, the convention every import shares, and the
		// world origin is the canvas center, negated into world space like every vertex.
		val canvasWidth = art.widthPx.toFloat()
		val canvasHeight = art.heightPx.toFloat()
		// The tree is materialized flat (one leaf per parameter at the root) rather than left empty: the
		// CMO3 export places parameters in the editor's group hierarchy from the tree alone, and the
		// official editor logs a recovery for every parameter it finds outside it.  Same shape the
		// editor's own parameter-create materializes.
		val parameters = options.parameterTemplate.parameters
		val model =
			PuppetModel(
				parameters = parameters,
				parameterTree = parameters.map { parameter -> ParameterNode.Param(parameter.id) },
				parts = parts,
				deformers = emptyList(),
				drawables = drawables,
				rootChildren = orgRoot,
				rootPartId = null,
				canvasWidth = canvasWidth,
				canvasHeight = canvasHeight,
				worldOriginX = canvasWidth / 2f,
				worldOriginY = -(canvasHeight / 2f),
				// The official editor's own fresh-import state: the rigger sees the layers as drawn, and
				// the pack that follows is what makes the document shippable.
				rendersFromSourceLayers = true,
				atlas = PuppetAtlas(pages = emptyList(), tiles = tiles, storedUvsAddressPages = true),
				sources = listOf(ArtSource(sourceId, source.name, source.path, source.format, inventory)),
			)
		return SourceArtImportResult(model.copy(renderRoot = model.deriveRenderRoot()), rasterByTile, notices)
	}

	/**
	 * The quad a layer is born with: the layer's opaque bounds grown by [margin] on every side, as
	 * canvas-pixel positions and art-frame coordinates that are two views of the same rectangle.
	 *
	 * The quad may overhang the layer's raster (the margin is not clamped): the mesh's reach is what
	 * the pack reserves around the tile, and a coordinate past the raster's edge samples the gutter
	 * the pack keeps clear there.
	 *
	 * @param SourceLayer layer      The layer.
	 * @param Int         trimLeft   The opaque bounds' left edge, raster-local.
	 * @param Int         trimTop    The opaque bounds' top edge, raster-local.
	 * @param Int         trimWidth  The opaque bounds' width.
	 * @param Int         trimHeight The opaque bounds' height.
	 * @param Int         margin     How far the quad extends past the bounds.
	 * @return DrawableMesh The two-triangle quad.
	 */
	private fun birthQuad(layer: SourceLayer, trimLeft: Int, trimTop: Int, trimWidth: Int, trimHeight: Int, margin: Int): DrawableMesh {
		val left = (trimLeft - margin).toFloat()
		val top = (trimTop - margin).toFloat()
		val right = (trimLeft + trimWidth + margin).toFloat()
		val bottom = (trimTop + trimHeight + margin).toFloat()
		val canvasLeft = layer.bounds.left.toFloat()
		val canvasTop = layer.bounds.top.toFloat()
		val tileWidth = layer.raster.width.toFloat()
		val tileHeight = layer.raster.height.toFloat()
		return DrawableMesh(
			positions =
				floatArrayOf(
					canvasLeft + left,
					canvasTop + top,
					canvasLeft + right,
					canvasTop + top,
					canvasLeft + right,
					canvasTop + bottom,
					canvasLeft + left,
					canvasTop + bottom,
				),
			uvs =
				floatArrayOf(
					left / tileWidth,
					top / tileHeight,
					right / tileWidth,
					top / tileHeight,
					right / tileWidth,
					bottom / tileHeight,
					left / tileWidth,
					bottom / tileHeight,
				),
			indices = intArrayOf(0, 1, 2, 0, 2, 3),
		)
	}

	/**
	 * A folder node carrying a source group's own attributes.
	 *
	 * @param SourceGroup group The parsed folder.
	 * @return FolderNode The node, with no children yet.
	 */
	private fun folderNodeOf(group: SourceGroup): FolderNode =
		FolderNode(
			path = group.path,
			name = group.name,
			visible = group.visible,
			opacity = group.opacity,
			clipped = group.clipped,
			blend = group.blend,
			passThrough = group.passThrough,
		)

	/**
	 * Makes sure every folder along [groupPath] has a node, synthesizing the ones the reader did not
	 * describe (a reader that flattens without folder metadata still names each layer's path).
	 *
	 * @param String groupPath    The slash-joined path to ensure.
	 * @param MutableMap folderByPath The nodes so far, added to.
	 */
	private fun ensureFolders(groupPath: String, folderByPath: MutableMap<String, FolderNode>) {
		if (groupPath.isEmpty()) {
			return
		}
		var path = ""
		for (segment in groupPath.split('/')) {
			path = if (path.isEmpty()) segment else "$path/$segment"
			if (path !in folderByPath) {
				folderByPath[path] = FolderNode(path, segment, visible = true, opacity = 1f, clipped = false, blend = LayerBlend.Normal, passThrough = true)
			}
		}
	}

	/**
	 * Lowers one level of gathered children onto org children, minting a part per folder in pre-order
	 * so part ids follow the panel order.
	 *
	 * A folder composites like the art when it must: opacity under 1 or a non-Normal blend makes the
	 * part Isolated with that composite, since pass-through would draw its layers as if the folder were
	 * not there.  A folder that isolates for no visible reason stays pass-through, the model's default.
	 *
	 * @param List    children The gathered children of one folder (or the root).
	 * @param MutableList parts Every part minted so far, appended to.
	 * @param MutableList notices The import notices, appended to.
	 * @return List<OrgChild> The org children, top-most first.
	 */
	private fun orgChildrenOf(children: List<FolderChild>, parts: MutableList<Part>, notices: MutableList<SourceArtImportNotice>): List<OrgChild> =
		children.sortedBy { child -> child.order }.map { child ->
			when (child) {
				is FolderChild.Layer -> OrgChild.Drawable(child.drawableId)
				is FolderChild.Folder -> {
					val node = child.node
					val partId = PartId("Part${parts.size + 1}")
					// Reserve the slot before descending so a parent's id precedes its children's.
					val slot = parts.size
					parts.add(Part(partId, node.name, emptyList()))
					val nestedChildren = orgChildrenOf(node.children, parts, notices)
					val blendMapping = mapLayerBlend(node.blend)
					if (blendMapping == LayerBlendMapping.Unsupported) {
						notices.add(SourceArtImportNotice.FolderBlendUnsupported(node.path, node.blend))
					}
					if (node.clipped) {
						notices.add(SourceArtImportNotice.FolderClipDropped(node.path))
					}
					val composites = node.opacity < 1f || blendMapping.blendMode != BlendMode.Normal
					parts[slot] =
						Part(
							id = partId,
							name = node.name,
							children = nestedChildren,
							isVisible = node.visible,
							groupMode = if (composites) PartGroupMode.Isolated else PartGroupMode.PassThrough,
							composite = if (composites) PartComposite(blendMode = blendMapping.blendMode, opacity = node.opacity) else PartComposite(),
						)
					OrgChild.Part(partId)
				}
			}
		}
}