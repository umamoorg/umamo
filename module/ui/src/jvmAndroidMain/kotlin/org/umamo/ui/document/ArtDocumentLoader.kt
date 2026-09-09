package org.umamo.ui.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.edit.seed.ParameterTemplate
import org.umamo.format.FileKind
import org.umamo.format.FormatCodec
import org.umamo.format.FormatRegistry
import org.umamo.format.art.SourceArt
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.rasterToSourceArt
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
import org.umamo.ui.model.describeImportNotice
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
 * The options an artwork import runs with under [template].  The bridge takes the resolved parameter
 * list and knows nothing about templates, so the template-to-parameters step happens once here for
 * every caller: the shell resolves the preference into it, and the loaders default through it so a
 * caller with no setting to read still seeds the configured default.
 *
 * @param ParameterTemplate template The parameter set to seed.
 * @return SourceArtImportOptions The import options.
 */
fun artworkImportOptions(template: ParameterTemplate = ParameterTemplate.Default): SourceArtImportOptions =
	SourceArtImportOptions(parameters = template.parameters)

/**
 * A layered artwork file, or a flat raster wrapped as one layer, read for the artwork paths: the
 * document open and Add Artwork into an open document.
 *
 * @property SourceArt art  The parsed source art.
 * @property FileKind  kind The format it was read from.
 */
class ReadArtwork(
	val art: SourceArt,
	val kind: FileKind,
)

/**
 * Reads [bytes] as artwork when they are one of the art formats the registry knows (PSD / CLIP / KRA,
 * or PNG / BMP / JPEG / WebP / TIFF as a one-layer document), and null for anything else - a model
 * format, an unrecognised file, or a file that fails to parse, which is logged.
 *
 * @param ByteArray bytes The file contents.
 * @param String    name  The file name (the extension fallback for detection; the log's name).
 * @return ReadArtwork? The art and its format, or null.
 */
fun readArtwork(bytes: ByteArray, name: String): ReadArtwork? {
	val codec = FormatRegistry.detect(bytes, name) ?: return null
	return runCatching { artworkOf(codec, bytes, name)?.let { art -> ReadArtwork(art, codec.kind) } }
		.getOrElse { failure ->
			UmamoLog.error("failed to read artwork $name", failure)
			null
		}
}

/**
 * Reads the artwork file at [path] - a real file-system path recorded on a source at import - off the
 * calling thread, and null when it cannot: a platform uri (Android's SAF handles have no path to
 * read), a missing or unreadable file, or bytes that are not artwork.  Every failure is logged, so the
 * reload that asked can say which file it skipped.
 *
 * @param String path The recorded path.
 * @return ReadArtwork? The art and its format, or null.
 */
suspend fun readArtworkAt(path: String): ReadArtwork? {
	if (path.contains("://")) {
		return null
	}
	val bytes =
		withContext(Dispatchers.IO) {
			runCatching { FileSystem.SYSTEM.read(path.toPath()) { readByteArray() } }
				.getOrElse { failure ->
					UmamoLog.error("failed to read artwork at $path", failure)
					null
				}
		} ?: return null
	val name = path.toPath().name
	return withContext(Dispatchers.Default) { readArtwork(bytes, name) }
}

/**
 * The source art [codec] reads out of [bytes]: a layered reader's document as it is, a flat raster
 * wrapped as one layer, or null for a kind that is not artwork at all.
 *
 * @param FormatCodec codec The detected codec.
 * @param ByteArray   bytes The file contents.
 * @param String      name  The file name (a flat raster's one layer is named after it).
 * @return SourceArt? The art, or null for a non-art kind.
 */
internal fun artworkOf(codec: FormatCodec<*>, bytes: ByteArray, name: String): SourceArt? =
	// detect returns a star-projected FormatCodec<*>; each kind's read result is cast to the model
	// type that kind's codec is known to produce.
	when (codec.kind) {
		FileKind.Psd, FileKind.Clip, FileKind.Kra -> codec.read(bytes) as SourceArt
		FileKind.Png, FileKind.Bmp, FileKind.Jpeg, FileKind.WebP, FileKind.Tiff -> rasterToSourceArt(codec.read(bytes) as RasterImage, name)
		FileKind.Cmo3, FileKind.Moc3, FileKind.Json, FileKind.Uma -> null
	}

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
 * @param SourceArtImportOptions options The seed parameters, threshold, and margin the import runs with.
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