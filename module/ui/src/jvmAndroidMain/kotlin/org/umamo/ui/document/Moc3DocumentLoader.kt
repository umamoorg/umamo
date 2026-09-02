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
import org.umamo.format.moc3.json.Model3Json
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.interop.moc3.import.moc3AtlasPages
import org.umamo.render.PuppetTextures
import org.umamo.render.UndecodablePagePolicy
import org.umamo.render.buildPuppetTextures
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.initialLiveParams

/**
 * A `.moc3` imported together with its JSON sidecars and external atlas pages.  None of the file's
 * undigested form is retained, because the MOC3 export bakes every section fresh from the model with
 * no reference container involved (docs/format/MOC3.md § 8) - which is why this class has no
 * counterpart to [Cmo3Document]'s retained graph.  That one IS load-bearing: the CMO3 export
 * reconciles onto it.  The original atlas page PNGs are the one thing kept ([atlasPages], in model3
 * texture order): both exports prefer those exact bytes over re-encoding the decoded RGBA, Export CMO3
 * embedding them in the synthesized graph's image chain and Export MOC3 writing them beside the moc.
 * Each export falls back to a re-encode for any decoded page this list does not reach, so the page
 * numbering the drawables address stays intact either way.
 */
class Moc3Document(
	override val path: String,
	override val puppet: PuppetModel,
	override val textures: PuppetTextures,
	override val liveParams: LiveParams,
	val atlasPages: List<ByteArray>,
	/** The parsed manifest, whose texture names and non-file sections a MOC3 export re-emits. */
	val manifest: Model3Json,
	/**
	 * Every sidecar the manifest referenced and the loader could read, each carrying the manifest
	 * section it came from and its manifest-relative name (`Erica.physics3.json`,
	 * `motion/idle.motion3.json`).
	 *
	 * Retained as TEXT, unparsed: Umamo models none of these, so a MOC3 export re-emits them verbatim
	 * rather than rebuilding them from a model that never held them.  Reading them at import is what
	 * makes that possible at all - a picker-driven export has no access to the source directory.
	 *
	 * The KIND travels with the text because only the manifest knows it.  A rigger is free to name the
	 * physics file anything, and re-deriving the kind from the file name later would drop such a file
	 * into the motion catch-all - written beside the moc, but no longer wired into the manifest.
	 */
	val sidecars: List<Moc3Sidecars.PassThroughSidecar>,
) : PuppetDocument

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
 *  - physics3/pose3/userdata3/exp3/motion3 are read as TEXT and retained for a MOC3 export to
 *    re-emit; each is optional, and a missing one degrades that export rather than the open.
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
	// Shared with the export so import and re-export agree on the family's shape.
	val basename = Moc3Sidecars.basenameFor(name)

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

	val mocDocument =
		runCatching { Moc3.read(mocBytes) }.getOrElse { failure ->
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
	// Validate the moc's page indices against the manifest BEFORE decoding megabytes of PNG.  The
	// texture build rejects an out-of-range index too, so this is not the correctness guard - it is
	// what keeps a stale or foreign manifest from costing a full atlas decode first, and what names
	// the offending mesh and page count instead of the build's generic decode failure.
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
	// A MOC3's pages are sibling files named by the manifest, so one that will not decode means the
	// family on disk is incomplete or mismatched - an import error to surface, not a puppet to render
	// half of.  Hence Fail here where the CMO3 loader passes Skip.
	val pageSet = moc3AtlasPages(mocDocument, pageBytes)
	val textures =
		buildPuppetTextures(
			pageSet.pageBytes,
			pageSet.atlasIndexByDrawableId,
			pageSet.premultipliedAlpha,
			UndecodablePagePolicy.Fail,
		)
			?: run {
				UmamoLog.warn("cannot import $path: an atlas page failed to decode")
				return DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.MissingTexture, name))
			}

	// Pass-through sidecars: read now, re-emitted verbatim by a MOC3 export.  Every one is optional -
	// an unreadable physics file degrades the export's fidelity, never the open.
	val sidecars = readPassThroughSidecars(manifest, readRelative)

	// cdi3: optional display info; a parse failure degrades (cosmetics never block a working model).
	val displayInfo = readDisplayInfo(manifest.fileReferences.displayInfo, basename, readRelative)

	// The import leaves warp/rotation-parented rest meshes in parent-deformer space (all a moc stores);
	// the post-pass evaluates the default pose to rewrite them into the editor's canvas-space convention.
	// Guarded like the byte-level CMO3 loader's whole body: a malformed-but-decodable moc that throws
	// inside the import or the evaluator must surface the ParseFailed alert, not crash the app.
	return runCatching {
		val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(mocDocument, displayInfo))
		DocumentLoad.Loaded(
			Moc3Document(
				path = path,
				puppet = puppet,
				textures = textures,
				liveParams = initialLiveParams(puppet),
				atlasPages = pageBytes,
				manifest = manifest,
				sidecars = sidecars,
			),
		)
	}.getOrElse { failure ->
		UmamoLog.error("failed to import $path", failure)
		DocumentLoad.Failed(DocumentOpenFailure(DocumentOpenError.ParseFailed, name))
	}
}

/**
 * Reads every sidecar the manifest references, as text, tagged with the section it came from.
 *
 * The kind is taken from WHICH manifest field named the file, which is the only place it is stated:
 * the names themselves are the rigger's to choose, so a physics file called `custom.json` is
 * indistinguishable from a motion by its name alone.  Classifying here rather than at export time is
 * what keeps such a file wired into the manifest the export writes.
 *
 * The cdi3 is deliberately NOT among them: it is the one sidecar the export synthesizes from the
 * model (display names are model data), so carrying the imported one through would overwrite the
 * names the rigger has since changed.
 *
 * @param Model3Json manifest     The parsed manifest.
 * @param Function   readRelative Reads a manifest-directory-relative reference, or null when missing.
 * @return List Each readable sidecar, with its kind and relative name.
 */
private fun readPassThroughSidecars(
	manifest: Model3Json,
	readRelative: (String) -> ByteArray?,
): List<Moc3Sidecars.PassThroughSidecar> {
	val references = LinkedHashMap<String, Moc3Sidecars.SidecarKind>()
	manifest.fileReferences.physics?.let { reference ->
		references[reference] = Moc3Sidecars.SidecarKind.Physics
	}
	manifest.fileReferences.pose?.let { reference -> references[reference] = Moc3Sidecars.SidecarKind.Pose }
	manifest.fileReferences.userData?.let { reference ->
		references[reference] = Moc3Sidecars.SidecarKind.UserData
	}
	manifest.fileReferences.expressions?.forEach { expression ->
		references[expression.file] = Moc3Sidecars.SidecarKind.Expression
	}
	manifest.fileReferences.motions?.values?.forEach { motions ->
		motions.forEach { motion -> references[motion.file] = Moc3Sidecars.SidecarKind.Motion }
	}
	val sidecars = ArrayList<Moc3Sidecars.PassThroughSidecar>(references.size)
	for ((reference, kind) in references) {
		val bytes = readRelative(reference)
		if (bytes == null) {
			UmamoLog.warn("sidecar $reference is missing; a MOC3 export will not carry it")
			continue
		}
		sidecars.add(Moc3Sidecars.PassThroughSidecar(kind, reference, bytes.decodeToString()))
	}
	return sidecars
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