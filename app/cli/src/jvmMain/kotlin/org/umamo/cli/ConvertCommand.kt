package org.umamo.cli

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.moc3.Moc3
import org.umamo.format.png.PngCodec
import org.umamo.interop.ExportReport
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.cmo3PuppetTextures
import org.umamo.render.encodeAtlasPng
import org.umamo.render.withTexturePagesFrom
import java.io.File
import java.util.Locale
import kotlin.random.Random

/*
 * The convert subcommand: cmo3/moc3 resaves and cross-format conversions, replacing the old
 * Cmo3ResaveDumpTest -Dcmo3.resave workflow.
 */

/**
 * Runs `convert <in> <out>`, picking the direction from the input's detected format and the output
 * path's extension.
 *
 * @param List arguments The subcommand's arguments.
 * @return Int The exit code.
 */
internal fun runConvert(arguments: List<String>): Int {
	if (arguments.size != 2) {
		throw CliUsageException("Usage: convert <in> <out>")
	}
	val loaded = loadInput(arguments[0])
	val outputFile = File(arguments[1])
	val outputExtension = outputFile.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
	outputFile.parentFile?.mkdirs()
	when {
		loaded is LoadedInput.Cmo3Input && outputExtension == "cmo3" -> {
			// An unedited graph re-emits its main.xml byte-identically; only the CAFF wrapper differs.
			Cmo3.write(loaded.model, outputFile)
			println("Resaved cmo3 -> ${outputFile.path} (${outputFile.length()} bytes)")
		}

		loaded is LoadedInput.Moc3Input && outputExtension == "moc3" -> {
			outputFile.writeBytes(Moc3.write(loaded.document))
			println("Rebaked moc3 -> ${outputFile.path} (${outputFile.length()} bytes)")
			println("Note: a rebake is a fresh synthesis, NOT byte-identical to the source by design")
		}

		loaded is LoadedInput.Cmo3Input && outputExtension == "moc3" -> {
			convertCmo3ToMoc3Family(loaded, outputFile)
		}

		loaded is LoadedInput.Moc3Input && outputExtension == "cmo3" -> {
			convertMoc3ToCmo3(loaded, outputFile)
		}

		else -> throw CliUsageException("Unsupported conversion: ${arguments[0]} -> ${outputFile.name}")
	}
	return 0
}

/**
 * Lowers a CMO3 document to the full MOC3 family (moc3 + model3.json + cdi3.json + atlas textures)
 * next to [outputFile].
 *
 * The two mandatory pre-steps mirror the app's own export path: the puppet's drawables arrive on
 * the -1 texture-page sentinel (CMO3 binds textures through the layered-art web, not a page index),
 * so withTexturePagesFrom must land real pages before the lowering can name them.
 *
 * @param LoadedInput.Cmo3Input loaded The cmo3 input.
 * @param File outputFile             The target .moc3 path; the family lands beside it.
 */
private fun convertCmo3ToMoc3Family(loaded: LoadedInput.Cmo3Input, outputFile: File) {
	val basename = Moc3Sidecars.basenameFor(outputFile.name)
	val importedPuppet = importPuppet(loaded)
	val modelSource = (loaded.model.root as org.umamo.format.cmo3.model.custom.CModelSource)
	val textures =
		cmo3PuppetTextures(modelSource) { imageResource -> loaded.model.extractLayerPng(imageResource) }
	val puppet = withTexturePagesFrom(importedPuppet, textures)
	val pages =
		textures.atlases.mapIndexed { pageIndex, atlas ->
			Moc3Sidecars.AtlasPage(
				fileName = String.format(Locale.ROOT, "%s.%d/texture_%02d.png", basename, atlas.width, pageIndex),
				bytes = encodeAtlasPng(atlas),
			)
		}
	val bundle =
		Moc3Sidecars.bundle(
			puppet = puppet,
			basename = basename,
			pages = pages,
			canvasToParentSpace = canvasToParentSpaceFor(puppet),
		)

	// Overwrites without confirmation.
	val outputDirectory = outputFile.parentFile ?: File(".")
	for (bundleFile in bundle.files) {
		val target = File(outputDirectory, bundleFile.name)
		target.parentFile?.mkdirs()
		target.writeBytes(bundleFile.bytes)
		println("Wrote ${target.path} (${bundleFile.bytes.size} bytes)")
	}
	reportNotices(bundle.report)
}

/**
 * Synthesizes a fresh CMO3 from a MOC3-origin document.  The atlas pages come from the manifest's
 * texture files; without a manifest the conversion proceeds textureless with a warning.  A
 * MissingSourceArt notice is always reported - the pages are atlas copies, not the original
 * layered source art.
 *
 * @param LoadedInput.Moc3Input loaded The moc3 input with its discovered family.
 * @param File outputFile             The target .cmo3 path.
 */
private fun convertMoc3ToCmo3(loaded: LoadedInput.Moc3Input, outputFile: File) {
	val puppet = importPuppet(loaded)
	val presentTextureFiles = loaded.family.textureFiles.filter { textureFile -> textureFile.isFile }
	if (loaded.family.manifest == null) {
		System.err.println("Warning: no model3.json manifest found beside ${loaded.family.mocFile.name}; converting textureless")
	} else if (presentTextureFiles.size < loaded.family.textureFiles.size) {
		System.err.println("Warning: ${loaded.family.textureFiles.size - presentTextureFiles.size} manifest texture(s) missing on disk")
	}
	val pages =
		presentTextureFiles.map { textureFile ->
			val pngBytes = textureFile.readBytes()
			val decoded = PngCodec.read(pngBytes)
			Cmo3Conversion.AtlasPage(pngBytes = pngBytes, width = decoded.width, height = decoded.height)
		}
	val result =
		Cmo3Conversion.freshCmo3(
			puppet = puppet,
			pages = pages,
			pageIndexByDrawableId = loaded.document.artMeshes.associate { artMesh -> artMesh.id to artMesh.textureIndex },
			modelName = outputFile.name.removeSuffix(".cmo3"),
			nowMillis = System.currentTimeMillis(),
			obfuscateKey = Random.nextInt(),
		)

	// Overwrites without confirmation.
	Cmo3.write(result.model, outputFile)
	println("Wrote ${outputFile.path} (${outputFile.length()} bytes)")
	reportNotices(result.report)
}

/**
 * Prints an export report's notices to stderr - advisory, never fatal.
 *
 * @param ExportReport report The lowering's report.
 */
private fun reportNotices(report: ExportReport) {
	if (report.isEmpty) {
		return
	}
	System.err.println("Export notices (${report.notices.size}):")
	for (notice in report.notices) {
		System.err.println("  $notice")
	}
}
