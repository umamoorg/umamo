package org.umamo.cli

import org.umamo.format.FormatRegistry
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.format.moc3.json.Model3Json
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.PuppetModel
import java.io.File
import java.util.Locale

/*
 * Input routing shared by every subcommand: read a file, detect its format through the same
 * FormatRegistry the app uses, and (for moc3) discover the sibling family files through the
 * model3.json manifest.
 */

/** A loaded input file, branched by detected format. */
internal sealed interface LoadedInput {
	/** A CMO3 editor document. */
	class Cmo3Input(val model: Cmo3Model, val bytes: ByteArray) : LoadedInput

	/** A MOC3 runtime document plus whatever family files sat beside it. */
	class Moc3Input(val document: MocDocument, val family: Moc3Family) : LoadedInput
}

/**
 * The MOC3 family files discovered around a moc: the manifest (when present), the display-info
 * sidecar, the texture files the manifest references, and the pass-through sidecar texts.
 *
 * @property File       mocFile      The .moc3 file itself.
 * @property Model3Json? manifest    The parsed model3.json, or null when none was found.
 * @property Cdi3Json?  displayInfo  The parsed cdi3.json, or null when absent.
 * @property List       textureFiles The manifest's texture files, resolved against its directory.
 * @property List       sidecars     Physics/pose/userdata texts, carried through verbatim.
 */
internal class Moc3Family(
	val mocFile: File,
	val manifest: Model3Json?,
	val displayInfo: Cdi3Json?,
	val textureFiles: List<File>,
	val sidecars: List<Moc3Sidecars.PassThroughSidecar>,
)

/**
 * Reads and format-detects one input file.  A .model3.json path is accepted as a moc3 input, with
 * the MOC3 resolved through the manifest's own file reference.
 *
 * @param String path The input file path.
 * @return LoadedInput The loaded, format-branched input.
 */
internal fun loadInput(path: String): LoadedInput {
	val inputFile = File(path)
	if (!inputFile.isFile) {
		throw CliUsageException("No such file: $path")
	}
	if (inputFile.name.lowercase(Locale.ROOT).endsWith(".model3.json")) {
		val manifest = Moc3.readModel3(inputFile.readText())
		val mocFile = File(inputFile.parentFile, manifest.fileReferences.moc)
		if (!mocFile.isFile) {
			throw CliUsageException("Manifest references a missing MOC3: ${mocFile.path}")
		}
		return loadMoc3(mocFile, manifestFile = inputFile, manifest = manifest)
	}
	val bytes = inputFile.readBytes()
	return when (FormatRegistry.detect(bytes, inputFile.name)) {
		Cmo3 -> LoadedInput.Cmo3Input(Cmo3.read(bytes), bytes)
		Moc3 -> loadMoc3(inputFile, manifestFile = null, manifest = null)
		else -> throw CliUsageException("Unsupported input format: $path (expected cmo3, moc3, or model3.json)")
	}
}

/**
 * Loads a moc3 and discovers its family.  Without an explicit manifest the sibling
 * `<basename>.model3.json` is tried first, then any manifest in the directory whose moc reference
 * names this file.
 *
 * @param File mocFile             The .moc3 file.
 * @param File? manifestFile       The manifest file, when the caller already has it.
 * @param Model3Json? manifest     The parsed manifest, when the caller already has it.
 * @return LoadedInput The moc3 input with its family.
 */
private fun loadMoc3(mocFile: File, manifestFile: File?, manifest: Model3Json?): LoadedInput.Moc3Input {
	val document = Moc3.read(mocFile.readBytes())
	val resolvedManifestFile =
		manifestFile
			?: File(mocFile.parentFile, Moc3Sidecars.basenameFor(mocFile.name) + ".model3.json").takeIf { candidate -> candidate.isFile }
			?: mocFile.parentFile
				?.listFiles { _, name -> name.lowercase(Locale.ROOT).endsWith(".model3.json") }
				?.firstOrNull { candidate ->
					runCatching { Moc3.readModel3(candidate.readText()).fileReferences.moc == mocFile.name }.getOrDefault(false)
				}
	val resolvedManifest = manifest ?: resolvedManifestFile?.let { file -> Moc3.readModel3(file.readText()) }
	if (resolvedManifest == null) {
		return LoadedInput.Moc3Input(document, Moc3Family(mocFile, null, null, emptyList(), emptyList()))
	}
	val manifestDirectory = resolvedManifestFile!!.parentFile
	val references = resolvedManifest.fileReferences
	// Manifest reference first, then the sibling-by-basename fallback the app's document loader
	// uses - a model3 without a DisplayInfo entry otherwise converts nameless, and the export then
	// writes parameter ids where Cubism expects display names.
	val displayInfo =
		listOfNotNull(references.displayInfo, "${mocFile.nameWithoutExtension}.cdi3.json")
			.distinct()
			.firstNotNullOfOrNull { reference ->
				File(manifestDirectory, reference)
					.takeIf { file -> file.isFile }
					?.let { file -> runCatching { Moc3.readCdi3(file.readText()) }.getOrNull() }
			}
	val textureFiles = references.textures.map { reference -> File(manifestDirectory, reference) }
	val sidecars =
		buildList {
			fun passThrough(kind: Moc3Sidecars.SidecarKind, reference: String?) {
				val file = reference?.let { relative -> File(manifestDirectory, relative) } ?: return
				if (file.isFile) {
					add(Moc3Sidecars.PassThroughSidecar(kind, file.name, file.readText()))
				}
			}
			passThrough(Moc3Sidecars.SidecarKind.Physics, references.physics)
			passThrough(Moc3Sidecars.SidecarKind.Pose, references.pose)
			passThrough(Moc3Sidecars.SidecarKind.UserData, references.userData)
		}
	return LoadedInput.Moc3Input(document, Moc3Family(mocFile, resolvedManifest, displayInfo, textureFiles, sidecars))
}

/**
 * Imports a loaded input to the runtime PuppetModel - the exact import path the editor uses.
 *
 * A MOC3-origin puppet gets restMeshesToCanvasSpace (its rest meshes arrive in parent-deformer
 * space); a CMO3-origin one authors directly in canvas pixels and must NOT be rebased.
 *
 * @param LoadedInput loaded The loaded input.
 * @return PuppetModel The imported puppet, in canvas space.
 */
internal fun importPuppet(loaded: LoadedInput): PuppetModel =
	when (loaded) {
		is LoadedInput.Cmo3Input -> Cmo3Import.fromModelSource(loaded.model.root as CModelSource)
		is LoadedInput.Moc3Input -> restMeshesToCanvasSpace(Moc3Import.fromMocDocument(loaded.document, loaded.family.displayInfo))
	}

/**
 * Formats a float like C's printf %.6g (what dump_model.c prints), so dump output stays diffable
 * against the relive harness.  Java's %g keeps trailing zeros where C strips them, hence the trim.
 *
 * @param Float value The value to format.
 * @return String The %.6g-formatted text with C's trailing-zero trimming.
 */
internal fun formatSixSignificant(value: Float): String {
	val raw = String.format(Locale.ROOT, "%.6g", value.toDouble())
	val exponentIndex = raw.indexOfFirst { character -> character == 'e' || character == 'E' }
	val mantissa = if (exponentIndex >= 0) raw.substring(0, exponentIndex) else raw
	val exponent = if (exponentIndex >= 0) raw.substring(exponentIndex) else ""
	val trimmed = if (mantissa.contains('.')) mantissa.trimEnd('0').trimEnd('.') else mantissa
	return trimmed + exponent
}
