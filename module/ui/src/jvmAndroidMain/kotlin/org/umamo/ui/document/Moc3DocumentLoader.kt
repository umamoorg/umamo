package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.render.buildPuppetTextures
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.ingest.Moc3Import
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.initialLiveParams

/**
 * Loads a picked `.moc3` plus its sidecars into a [Moc3Document].  A baked model is a file family,
 * not a single file: the `<basename>.model3.json` manifest next to the moc names the atlas PNGs (and
 * the optional cdi3/physics3/... sidecars), all resolved relative to the moc's directory.
 *
 * The whole pipeline runs on Dispatchers.IO: the sibling reads are blocking okio IO, and the decode
 * plus import that interleave them are seconds of CPU on a real model (a single 8192 atlas page is
 * 256MB of RGBA) - none of which may run on the caller's dispatcher, which is the Compose Main
 * thread for every interactive open.
 *
 * Desktop-first: sibling discovery needs a real directory, so an Android SAF `content://` handle
 * (which has no resolvable parent) fails cleanly as MissingManifest until a folder-picker flow exists.
 *
 * @param PlatformFile file     The picked or reconstructed `.moc3` handle.
 * @param ByteArray    mocBytes The moc's already-read contents.
 * @return DocumentLoad The loaded document, or the failure reason.
 */
suspend fun loadMoc3Document(file: PlatformFile, mocBytes: ByteArray): DocumentLoad =
	withContext(Dispatchers.IO) {
		val path = file.absolutePath()
		val directory = runCatching { path.toPath().parent }.getOrNull()
		buildMoc3Document(
			path = path,
			name = file.name,
			mocBytes = mocBytes,
			readRelative = { reference ->
				directory?.let { baseDirectory ->
					runCatching { FileSystem.SYSTEM.read(baseDirectory / reference) { readByteArray() } }.getOrNull()
				}
			},
		)
	}

/**
 * Assembles a [Moc3Document] from a moc's bytes and its sidecars, every sibling read injected via
 * [readRelative] so the discovery/failure rules unit-test without a filesystem.  The rules:
 *
 *  - `<basename>.model3.json` is REQUIRED - absent fails as MissingManifest; present but
 *    unparseable fails as ParseFailed (the file exists, so "not found" would mislead).
 *  - Every texture the manifest lists is REQUIRED, and every mesh's textureIndex must land inside
 *    that list - an empty list, a missing file, an undecodable page, or an out-of-range index
 *    fails as MissingTexture: a puppet without its atlas wiring is broken, not degraded.
 *  - cdi3 (display names) is OPTIONAL - the manifest's DisplayInfo reference first, then the
 *    basename fallback; absent or unparseable degrades to raw format ids.
 *  - physics3/pose3/userdata3 are not read - nothing consumes them yet.
 *  - No failure escapes as an exception: like the byte-level CMO3 loader, anything thrown by the
 *    import/assembly is caught and reported as ParseFailed, never propagated to the caller.
 *
 * @param String    path         The stored path recorded on the document (the recent-files key).
 * @param String    name         The moc file name (`Erica.moc3`; sidecars derive from its basename).
 * @param ByteArray mocBytes     The moc's contents.
 * @param Function  readRelative Reads a manifest-directory-relative reference, or null when missing.
 * @return DocumentLoad The loaded document, or the failure reason.
 */
internal fun buildMoc3Document(
	path: String,
	name: String,
	mocBytes: ByteArray,
	readRelative: (String) -> ByteArray?,
): DocumentLoad {
	// Case-insensitive strip: every route here matches the extension case-insensitively (argv) or
	// not at all (magic-byte detection), so "MODEL.MOC3" must yield basename "MODEL".
	val basename = if (name.endsWith(".moc3", ignoreCase = true)) name.dropLast(".moc3".length) else name

	// model3.json: the manifest that joins the family together. Hard requirement; a manifest that
	// exists but does not parse is a ParseFailed, not a MissingManifest - the alert must not claim
	// a file is absent when it is sitting right there.
	val manifestName = "$basename.model3.json"
	val manifestBytes = readRelative(manifestName)
	if (manifestBytes == null) {
		UmamoLog.warn("cannot import $path: no $manifestName next to it")
		return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.MissingManifest, name))
	}
	val manifest =
		runCatching { Moc3.readModel3(manifestBytes.decodeToString()) }.getOrElse { failure ->
			UmamoLog.error("cannot import $path: $manifestName failed to parse", failure)
			return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ParseFailed, name))
		}
	if (manifest.fileReferences.moc.substringAfterLast('/') != name) {
		// The manifest names a different moc (a renamed file); the picked bytes stay authoritative.
		UmamoLog.warn("$manifestName references ${manifest.fileReferences.moc}, not $name; importing the picked file")
	}

	val (mocModel, mocDocument) =
		runCatching {
			val container = Moc3.read(mocBytes)
			container to Moc3.decode(container)
		}.getOrElse { failure ->
			UmamoLog.error("failed to decode $path", failure)
			return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ParseFailed, name))
		}

	// Atlas pages, in manifest order (a mesh's textureIndex indexes this list). Hard requirement.
	// model3: FileReferences.Textures - the ordered atlas page paths, manifest-relative.
	val textureReferences = manifest.fileReferences.textures
	if (textureReferences.isEmpty()) {
		UmamoLog.warn("cannot import $path: $manifestName lists no textures")
		return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.MissingTexture, name))
	}
	// Validate the moc's page indices against the manifest BEFORE decoding megabytes of PNG: an
	// out-of-range textureIndex (stale or foreign manifest) would otherwise import fine and then
	// crash the renderer's direct atlas indexing at first frame.
	for (artMesh in mocDocument.artMeshes) {
		if (artMesh.textureIndex !in textureReferences.indices) {
			UmamoLog.warn(
				"cannot import $path: ${artMesh.id} references texture page ${artMesh.textureIndex}, " +
					"but $manifestName lists ${textureReferences.size} page(s)",
			)
			return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.MissingTexture, name))
		}
	}
	val pageBytes =
		textureReferences.map { textureReference ->
			readRelative(textureReference) ?: run {
				UmamoLog.warn("cannot import $path: texture $textureReference is missing")
				return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.MissingTexture, name))
			}
		}
	val textures =
		buildPuppetTextures(pageBytes, mocDocument.artMeshes.associate { artMesh -> artMesh.id to artMesh.textureIndex })
			?: run {
				UmamoLog.warn("cannot import $path: an atlas page failed to decode")
				return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.MissingTexture, name))
			}

	// cdi3: optional display info; a parse failure degrades (cosmetics never block a working model).
	val displayInfo = readDisplayInfo(manifest.fileReferences.displayInfo, basename, readRelative)

	// Blend-shape records import into live BlendShapeBindings (Moc3Import maps them), so no
	// warning is needed - only offscreens stay unhandled.
	if (mocDocument.offscreens.isNotEmpty()) {
		UmamoLog.warn("$name carries ${mocDocument.offscreens.size} offscreens; offscreen compositing is not handled yet")
	}

	// The import leaves warp/rotation-parented rest meshes in parent-deformer space (all a moc stores);
	// the post-pass evaluates the default pose to rewrite them into the editor's canvas-space convention.
	// Guarded like the byte-level CMO3 loader's whole body: a malformed-but-decodable moc that throws
	// inside the import or the evaluator must surface the ParseFailed alert, not crash the app.
	return runCatching {
		val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(mocDocument, displayInfo))
		DocumentLoad.Loaded(
			Moc3Document(
				path = path,
				moc = mocModel,
				mocDocument = mocDocument,
				puppet = puppet,
				textures = textures,
				liveParams = initialLiveParams(puppet),
			),
		)
	}.getOrElse { failure ->
		UmamoLog.error("failed to import $path", failure)
		DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ParseFailed, name))
	}
}

/**
 * Reads the optional cdi3 display info: the manifest's DisplayInfo reference first, then the
 * `<basename>.cdi3.json` sibling fallback; null (raw-id degradation) when neither reads or parses.
 *
 * @param String?  manifestReference The manifest's FileReferences.DisplayInfo, or null.
 * @param String   basename          The moc's basename for the sibling fallback.
 * @param Function readRelative      Reads a manifest-directory-relative reference.
 * @return Cdi3Json? The parsed display info, or null.
 */
private fun readDisplayInfo(
	manifestReference: String?,
	basename: String,
	readRelative: (String) -> ByteArray?,
): Cdi3Json? {
	val candidates = listOfNotNull(manifestReference, "$basename.cdi3.json").distinct()
	for (candidate in candidates) {
		val bytes = readRelative(candidate) ?: continue
		val parsed =
			runCatching { Moc3.readCdi3(bytes.decodeToString()) }
				.onFailure { failure -> UmamoLog.warn("ignoring unparseable $candidate: ${failure.message}") }
				.getOrNull()
		if (parsed != null) {
			return parsed
		}
	}
	return null
}
