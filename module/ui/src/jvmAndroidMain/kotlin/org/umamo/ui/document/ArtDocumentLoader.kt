package org.umamo.ui.document

import org.umamo.format.FileKind
import org.umamo.format.art.SourceArt
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.model.AtlasRepackRefusalReason
import org.umamo.ui.model.packModelAtOpen
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.initialLiveParams

/**
 * A rig born from an artwork file - a layered PSD / CLIP / KRA, or a flat raster wrapped as one layer.
 *
 * Nothing format-specific is retained: the reader's layer tree became the model's source inventory,
 * the layers' pixels became the raster store, and the pack that ran at open became the model's atlas
 * and these pages.  Export has no retained graph to reconcile onto, so a CMO3 export synthesizes a
 * fresh one the way a MOC3-origin document's does.
 *
 * @property List importNotices Everything the import could not carry as drawn - already logged, kept
 *   so the shell can say once that there are notes to read.
 */
class ArtDocument(
	override val path: String,
	override val puppet: PuppetModel,
	override val textures: PuppetTextures,
	override val artRasters: SourceArtRasters,
	override val liveParams: LiveParams,
	val importNotices: List<SourceArtImportNotice>,
) : PuppetDocument

/**
 * Assembles an [ArtDocument] from parsed source art: the bridge builds the unpacked model, the pack
 * at open moves it onto pages, and the rasters the reader decoded become the document's pixel store.
 *
 * Every raster is wrapped into ONE decoded image up front, so the store's cached and uncached reads
 * hand out the same instance for a tile - the identity the renderer's texture cache and the
 * viewport's freshness test compare by.
 *
 * Throws nothing of its own - the byte-level [loadDocument] wraps this call, so a reader result that
 * trips the bridge or the pack surfaces as ParseFailed there.
 *
 * @param SourceArt              art     The parsed source art.
 * @param FileKind               kind    The format it was read from, recorded on the model's source list.
 * @param String                 name    The file name (the failure display name, the source's name).
 * @param String                 path    The stored path or URI string recorded on the document.
 * @param SourceArtImportOptions options The template, threshold, and margin the import runs with.
 * @return DocumentLoad The loaded document, or NoArtLayers when nothing in the file can be rigged.
 */
internal fun buildArtDocument(
	art: SourceArt,
	kind: FileKind,
	name: String,
	path: String,
	options: SourceArtImportOptions,
): DocumentLoad {
	val imported = SourceArtImport.fromSourceArt(art, ArtSourceDescriptor(name, path.takeIf { stored -> stored.isNotEmpty() }, kind.extension), options)
	if (imported.puppet.drawables.isEmpty()) {
		for (notice in imported.notices) {
			UmamoLog.warn("import: ${describeImportNotice(notice)}")
		}
		UmamoLog.error("failed to import $path: no layer has pixels to rig")
		return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.NoArtLayers, name))
	}
	val decodedByTile = imported.rasterByTile.mapValues { (_, raster) -> DecodedImage(raster.rgba, raster.width, raster.height) }
	val decodeRaster: (org.umamo.runtime.model.AtlasTileId) -> DecodedImage? = { tileId -> decodedByTile[tileId] }
	val packed = packModelAtOpen(imported.puppet, decodeRaster)
	val notices =
		imported.notices +
			packed.refusals.map { refusal ->
				when (refusal.reason) {
					AtlasRepackRefusalReason.LargerThanPage -> SourceArtImportNotice.LayerLargerThanPage(refusal.tileName)
					else -> SourceArtImportNotice.LayerNotPacked(refusal.tileName, refusal.reason.name)
				}
			}
	for (notice in notices) {
		UmamoLog.warn("import: ${describeImportNotice(notice)}")
	}
	val puppet = packed.model
	UmamoLog.info(
		"import: $name -> ${puppet.drawables.size} drawable(s), ${puppet.parts.size} part(s), ${puppet.atlas.pages.size} page(s)," +
			" ${puppet.parameters.size} parameter(s); ${notices.size} note(s)",
	)
	return DocumentLoad.Loaded(ArtDocument(path, puppet, packed.textures, SourceArtRasters(decodeRaster), initialLiveParams(puppet), notices))
}

/**
 * One import notice as a log line: plain English naming the layer and what happened to it, the same
 * shape the export notices log in.
 *
 * @param SourceArtImportNotice notice The notice.
 * @return String The log text.
 */
internal fun describeImportNotice(notice: SourceArtImportNotice): String =
	when (notice) {
		is SourceArtImportNotice.NonRasterLayer -> "layer '${notice.layerName}' is a ${notice.kind.name.lowercase()} layer with no pixels; skipped"
		is SourceArtImportNotice.EmptyLayer -> "layer '${notice.layerName}' has no opaque pixels; skipped"
		is SourceArtImportNotice.BlendUnsupported -> "layer '${notice.layerName}' blends with ${notice.blend.name}, which has no equivalent; imported as Normal"
		is SourceArtImportNotice.BlendApproximated -> "layer '${notice.layerName}' blends with ${notice.blend.name}; imported as the nearest mode, ${notice.mappedTo.name}"
		is SourceArtImportNotice.ClipBaseMissing -> "layer '${notice.layerName}' clips to the layer below but has none in its folder; imported unclipped"
		is SourceArtImportNotice.ChannelMaskDropped -> "layer '${notice.layerName}' writes only some color channels; imported writing all of them"
		is SourceArtImportNotice.FolderBlendUnsupported -> "folder '${notice.groupPath}' blends with ${notice.blend.name}, which has no equivalent; its part composites as Normal"
		is SourceArtImportNotice.FolderClipDropped -> "folder '${notice.groupPath}' clips to the layer below; its part imports unclipped"
		is SourceArtImportNotice.LayerLargerThanPage -> "layer '${notice.layerName}' is larger than the largest atlas page; left unpacked"
		is SourceArtImportNotice.LayerNotPacked -> "layer '${notice.layerName}' could not be packed (${notice.reason}); left unpacked"
	}