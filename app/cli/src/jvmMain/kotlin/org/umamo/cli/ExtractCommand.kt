package org.umamo.cli

import java.io.File
import java.util.Locale

/*
 * The extract subcommand: unpack a cmo3's CAFF archive into loose files - the plaintext main.xml
 * plus every embedded layer PNG.
 */

/**
 * Runs `extract <file> [<directory>]`.
 *
 * The entries land in a NEW subdirectory named after the model, created under the given directory
 * (default: the input file's own directory) - `extract Model.cmo3 test/work/` writes into
 * `test/work/Model/`.  The subdirectory is deliberate accident insurance: extraction never mixes
 * its output into a folder that already holds other files.
 *
 * @param List arguments The subcommand's arguments.
 * @return Int The exit code.
 */
internal fun runExtract(arguments: List<String>): Int {
	val positionals = arguments.filterNot { argument -> argument.startsWith("--") }
	if (arguments.size != positionals.size || positionals.isEmpty() || positionals.size > 2) {
		throw CliUsageException("Usage: extract <file> [<directory>]")
	}
	val loaded = loadInput(positionals[0])
	if (loaded !is LoadedInput.Cmo3Input) {
		throw CliUsageException("Extract applies to cmo3 input only")
	}
	val inputFile = File(positionals[0])
	val modelName =
		inputFile.name.let { fileName ->
			if (fileName.lowercase(Locale.ROOT).endsWith(".cmo3")) fileName.dropLast(".cmo3".length) else fileName
		}
	val parentDirectory = if (positionals.size == 2) File(positionals[1]) else (inputFile.parentFile ?: File("."))
	val extractionDirectory = File(parentDirectory, modelName)
	if (extractionDirectory.exists()) {
		System.err.println("Note: ${extractionDirectory.path} already exists; its matching files will be overwritten.")
	}
	extractionDirectory.mkdirs()

	val archive = loaded.model.archive
	val writtenNames = HashSet<String>()
	archive.entries.forEachIndexed { entryIndex, entry ->
		// Entry paths are archive data: guard against traversal and collisions rather than trusting them.
		val safeName = entry.path.ifBlank { "entry_$entryIndex" }
		val uniqueName = if (writtenNames.add(safeName)) safeName else "entry_${entryIndex}_$safeName"
		val target = File(extractionDirectory, uniqueName)
		if (!target.canonicalFile.toPath().startsWith(extractionDirectory.canonicalFile.toPath())) {
			throw IllegalStateException("Archive entry escapes the extraction directory: ${entry.path}")
		}
		target.parentFile?.mkdirs()
		// CaffEntry.content is already inflated and deobfuscated, so main.xml lands as plaintext.
		target.writeBytes(entry.content)
		println("Extracted ${target.path} (${entry.content.size} bytes)")
	}
	if (archive.preview.present) {
		val previewTarget = File(extractionDirectory, "preview.png")
		if (writtenNames.contains("preview.png")) {
			System.err.println("Note: An archive entry already claimed preview.png; the CAFF preview image was skipped.")
		} else {
			previewTarget.writeBytes(archive.preview.png!!)
			println("Extracted ${previewTarget.path} (${archive.preview.png!!.size} bytes, CAFF preview)")
		}
	}
	println("Extracted ${archive.entries.size} entries into ${extractionDirectory.path}")
	return 0
}
