package org.umamo.cli

import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.art.isEffectivelyVisible
import org.umamo.format.png.PngCodec
import org.umamo.geometry.mesh.MeshQuality
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.mesh.measureQuality
import org.umamo.interop.art.mesh.ArtMeshNotice
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.ArtMeshResult
import org.umamo.interop.art.mesh.ArtMeshSettings
import org.umamo.interop.art.mesh.generateArtMesh
import java.io.File
import java.util.Locale

/*
 * The mesh subcommand: run the art mesher over a source artwork document's layers and show what it
 * made - per-layer counts, triangle quality, and timing on stdout and in mesh.txt, and with --preview
 * a wireframe picture of each mesh over its art.  The mesher's eyes, before any editor surface exists.
 */

/** The options that override one setting each, by name. */
private val SETTING_OPTIONS =
	setOf(
		"--alpha-threshold",
		"--outline-spacing",
		"--interior-spacing",
		"--outer-margin",
		"--inner-margin",
		"--minimum-margin",
		"--minimum-outline-points",
		"--vertex-budget",
	)

/**
 * Runs `mesh <file> [<directory>] [options]`.
 *
 * Output lands in a NEW subdirectory named after the input with a `-mesh` suffix, created under the
 * given directory (default: the input file's own directory).
 *
 * @param List arguments The subcommand's arguments.
 * @return Int The exit code; 1 when the mesher withheld a mesh after a failed structural check.
 */
internal fun runMesh(arguments: List<String>): Int {
	val parsed = parseArguments(arguments, knownFlags = setOf("--visible-only", "--preview"), knownOptions = setOf("--layer", "--preset", "--scale") + SETTING_OPTIONS)

	if (parsed.positionals.isEmpty() || parsed.positionals.size > 2) {
		throw CliUsageException("Usage: mesh <file> [<directory>] [--layer=NAME] [--preset=standard|fine|coarse] [--visible-only] [--preview] [--scale=N] [setting overrides]")
	}

	val loaded = loadInput(parsed.positionals[0])

	if (loaded !is LoadedInput.SourceArtInput) {
		throw CliUsageException("Mesh applies to source artwork only (psd, clip, kra, or a flat raster)")
	}

	val settings = settingsFrom(parsed)
	val scale = parsed.intOption("--scale", 1)

	if (scale < 1) {
		throw CliUsageException("--scale must be at least 1, got $scale")
	}

	val art = loaded.art
	val visibleOnly = "--visible-only" in parsed.flags
	val layerName = parsed.options["--layer"]
	val layers =
		art.layers
			.filter { layer -> layer.kind == SourceLayerKind.Raster && (!visibleOnly || art.isEffectivelyVisible(layer)) }
			.filter { layer -> layerName == null || layer.name == layerName }
			.sortedBy { layer -> layer.order }

	if (layers.isEmpty()) {
		throw CliUsageException(if (layerName != null) "${loaded.file.name} has no raster layer named '$layerName'" else "${loaded.file.name} has no raster layers")
	}

	val outputDirectory = resolveOutputDirectory(loaded.file, parsed.positionals.getOrNull(1), "-mesh")
	outputDirectory.mkdirs()
	println("Meshing ${layers.size} layer(s) of ${loaded.file.name} with $settings")
	val report = StringBuilder()
	report.append("# mesh ${loaded.file.name}\n")
	report.append("# settings $settings\n")
	report.append(listOf("# order", "name", "raster", "vertices", "triangles", "outline", "refined", "inner", "lattice", "minAngle", "under10", "milliseconds", "notices").joinToString("\t")).append('\n')
	var meshedCount = 0
	var withheldCount = 0
	var vertexTotal = 0
	var largestVertexCount = 0
	var smallestAngle = Double.POSITIVE_INFINITY
	var millisecondsTotal = 0L

	for (layer in layers) {
		val startNanos = System.nanoTime()
		val result = generateArtMesh(layer.raster, settings)
		val milliseconds = (System.nanoTime() - startNanos) / 1_000_000
		millisecondsTotal += milliseconds
		val quality = result.mesh?.measureQuality()
		val row = reportRow(layer, result, quality, milliseconds)
		report.append(row.joinToString("\t")).append('\n')
		println("L ${row.joinToString(" ")}")
		val mesh = result.mesh

		if (mesh == null) {
			if (result.notices.any { notice -> notice is ArtMeshNotice.StructureCheckFailed }) {
				withheldCount++
			}

			continue
		}

		meshedCount++
		vertexTotal += mesh.vertexCount
		largestVertexCount = maxOf(largestVertexCount, mesh.vertexCount)
		smallestAngle = minOf(smallestAngle, quality?.minimumAngleDegrees ?: smallestAngle)

		if ("--preview" in parsed.flags) {
			writePreview(layer, mesh, scale, outputDirectory)
		}
	}

	val reportFile = File(outputDirectory, "mesh.txt")
	reportFile.writeText(report.toString())
	println("Wrote ${reportFile.path}")
	val angleText = if (smallestAngle.isFinite()) format(smallestAngle) else "-"
	println("Meshed $meshedCount/${layers.size} layer(s): $vertexTotal vertices, largest $largestVertexCount, smallest angle $angleText degrees, $millisecondsTotal ms")

	if (withheldCount > 0) {
		System.err.println("FAILED: $withheldCount mesh(es) withheld after a structural check failed")

		return 1
	}

	return 0
}

/**
 * Builds the settings: a preset, then any per-setting overrides.
 *
 * @param CliArguments parsed The command line.
 * @return ArtMeshSettings The settings.
 */
private fun settingsFrom(parsed: CliArguments): ArtMeshSettings {
	val presetName = parsed.options["--preset"] ?: "standard"
	val preset =
		ArtMeshPreset.entries.firstOrNull { entry -> entry.name.equals(presetName, ignoreCase = true) }
			?: throw CliUsageException("--preset must be one of ${ArtMeshPreset.entries.joinToString { it.name.lowercase() }}, got '$presetName'")
	val base = preset.settings

	return try {
		ArtMeshSettings(
			alphaThreshold = parsed.intOption("--alpha-threshold", base.alphaThreshold),
			outlineSpacing = parsed.doubleOption("--outline-spacing", base.outlineSpacing),
			interiorSpacing = parsed.doubleOption("--interior-spacing", base.interiorSpacing),
			outerMargin = parsed.doubleOption("--outer-margin", base.outerMargin),
			innerMargin = parsed.doubleOption("--inner-margin", base.innerMargin),
			minimumMargin = parsed.doubleOption("--minimum-margin", base.minimumMargin),
			minimumOutlinePoints = parsed.intOption("--minimum-outline-points", base.minimumOutlinePoints),
			vertexBudget = parsed.intOption("--vertex-budget", base.vertexBudget),
		)
	} catch (invalid: IllegalArgumentException) {
		throw CliUsageException(invalid.message ?: "invalid mesh settings")
	}
}

/**
 * One layer's report columns.
 *
 * @param SourceLayer   layer        The layer.
 * @param ArtMeshResult result       The mesher's result.
 * @param MeshQuality?  quality      The mesh's triangle quality, when there is a mesh.
 * @param Long          milliseconds The time taken.
 * @return List<String> The columns, matching the report header.
 */
private fun reportRow(layer: SourceLayer, result: ArtMeshResult, quality: MeshQuality?, milliseconds: Long): List<String> {
	val mesh = result.mesh
	val statistics = result.statistics
	val notices = if (result.notices.isEmpty()) "-" else result.notices.joinToString(", ")

	return listOf(
		layer.order.toString(),
		layer.name.ifEmpty { "-" },
		"${layer.raster.width}x${layer.raster.height}",
		mesh?.vertexCount?.toString() ?: "-",
		mesh?.triangleCount?.toString() ?: "-",
		if (statistics != null) "${statistics.outlineRingCount}/${statistics.outlineVertexCount}" else "-",
		statistics?.refinedChordCount?.toString() ?: "-",
		if (statistics != null) "${statistics.innerRingCount}/${statistics.innerVertexCount}" else "-",
		statistics?.latticeVertexCount?.toString() ?: "-",
		if (quality != null) format(quality.minimumAngleDegrees) else "-",
		quality?.smallestAngleHistogram?.get(0)?.toString() ?: "-",
		milliseconds.toString(),
		notices,
	)
}

/**
 * Writes one layer's wireframe preview.
 *
 * @param SourceLayer        layer           The layer.
 * @param PlanarTriangleMesh mesh            Its mesh.
 * @param Int                scale           The magnification.
 * @param File               outputDirectory The directory to write into.
 */
private fun writePreview(layer: SourceLayer, mesh: PlanarTriangleMesh, scale: Int, outputDirectory: File) {
	val picture = renderWireframe(layer.raster, mesh, scale)

	if (picture == null) {
		System.err.println("Note: '${layer.name}' preview would exceed the preview cap; skipped.")

		return
	}

	val safeName = layer.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifEmpty { "layer" }
	val target = File(outputDirectory, "${layer.order.toString().padStart(3, '0')}-$safeName.png")
	target.writeBytes(PngCodec.write(picture))
}

/**
 * Formats a number to two decimals, locale-independent.
 *
 * @param Double value The value.
 * @return String The text.
 */
private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)