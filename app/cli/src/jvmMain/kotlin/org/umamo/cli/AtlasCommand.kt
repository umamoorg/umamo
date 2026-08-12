package org.umamo.cli

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.art.isEffectivelyVisible
import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.AtlasPackPlacement
import org.umamo.format.atlas.AtlasPackResult
import org.umamo.format.atlas.atlasPackItems
import org.umamo.format.atlas.packAtlas
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import java.io.File

/*
 * The atlas subcommand: pack a source artwork document's layers into texture atlas pages.
 *
 * The pipeline's Phase C packer has no editor surface yet by design - pages are verified here first
 * and wired in afterwards, the way the alpha analysis was.  So this command is the packer's proof:
 * it writes the pages, reports every placement, and checks each packed tile back out of the page it
 * landed on before claiming success.
 */

/** Canvas pixels past which the preview composite is skipped rather than allocated. */
private const val PREVIEW_PIXEL_CAP = 64_000_000

/**
 * Runs `atlas <file> [<directory>] [options]`.
 *
 * Output lands in a NEW subdirectory named after the input, created under the given directory
 * (default: the input file's own directory), matching how `extract` keeps its output from mixing
 * into a folder that already holds other files.
 *
 * @param List arguments The subcommand's arguments.
 * @return Int The exit code; 1 when a packed tile fails to read back out of its page.
 */
internal fun runAtlas(arguments: List<String>): Int {
	val parsed =
		parseArguments(
			arguments,
			knownFlags = setOf("--rotate", "--visible-only", "--no-shrink", "--preview"),
			knownOptions = setOf("--page-size", "--gutter", "--extrude"),
		)
	if (parsed.positionals.isEmpty() || parsed.positionals.size > 2) {
		throw CliUsageException(
			"Usage: atlas <file> [<directory>] [--page-size=N] [--gutter=N] [--extrude=N] " +
				"[--rotate] [--visible-only] [--no-shrink] [--preview]",
		)
	}
	val loaded = loadInput(parsed.positionals[0])
	if (loaded !is LoadedInput.SourceArtInput) {
		throw CliUsageException("Atlas applies to source artwork only (psd, clip, kra, or a flat raster)")
	}

	val gutter = parsed.intOption("--gutter", 2)
	val options =
		AtlasPackOptions(
			maxPageSize = parsed.intOption("--page-size", 4096),
			gutter = gutter,
			extrude = parsed.intOption("--extrude", minOf(2, gutter)),
			allowRotation = "--rotate" in parsed.flags,
			shrinkPages = "--no-shrink" !in parsed.flags,
		)

	val art = loaded.art
	val visibleOnly = "--visible-only" in parsed.flags
	val include: (SourceLayer) -> Boolean = { layer ->
		layer.kind == SourceLayerKind.Raster && (!visibleOnly || art.isEffectivelyVisible(layer))
	}
	val includedLayers = art.layers.filter(include)
	val items = art.atlasPackItems(include)
	if (items.isEmpty()) {
		throw CliUsageException("${loaded.file.name} has no raster layers to pack")
	}
	val nonRasterCount = art.layers.count { layer -> layer.kind != SourceLayerKind.Raster }
	if (nonRasterCount > 0) {
		System.err.println("Note: $nonRasterCount non-raster layer(s) carry no pixels and were not packed.")
	}

	println("Packing ${items.size} layer(s) of ${loaded.file.name} (${art.widthPx}x${art.heightPx} canvas)")
	val result = packAtlas(items, options)

	val outputDirectory = resolveOutputDirectory(loaded.file, parsed.positionals.getOrNull(1))
	outputDirectory.mkdirs()
	writePages(result, outputDirectory)
	writeReport(result, includedLayers, items, options, loaded.file, outputDirectory)
	reportSkips(result)
	if ("--preview" in parsed.flags) {
		writePreview(art, includedLayers, items, result, outputDirectory)
	}

	val mismatches = verifyTiles(items, result)
	if (mismatches.isNotEmpty()) {
		System.err.println("VERIFY FAILED: ${mismatches.size} tile(s) do not match their source pixels:")
		mismatches.take(10).forEach { message -> System.err.println("  $message") }
		return 1
	}
	println("verified ${result.placements.size}/${result.placements.size} tiles byte-exact")
	return 0
}

/**
 * Resolves the output subdirectory, named after the input file.
 *
 * @param File inputFile          The source artwork file.
 * @param String? parentDirectory The caller's directory argument, when given.
 * @return File The directory the pages and report write into.
 */
private fun resolveOutputDirectory(inputFile: File, parentDirectory: String?): File {
	val parent = if (parentDirectory != null) File(parentDirectory) else (inputFile.parentFile ?: File("."))
	val directory = File(parent, inputFile.nameWithoutExtension)
	if (directory.exists()) {
		System.err.println("Note: ${directory.path} already exists; its matching files will be overwritten.")
	}
	return directory
}

/**
 * Encodes and writes every packed page.
 *
 * @param AtlasPackResult result The packing outcome.
 * @param File outputDirectory   The directory to write into.
 */
private fun writePages(result: AtlasPackResult, outputDirectory: File) {
	for ((pageIndex, page) in result.pages.withIndex()) {
		val target = File(outputDirectory, "page_${pageIndex.toString().padStart(2, '0')}.png")
		val bytes = PngCodec.write(page)
		target.writeBytes(bytes)
		val occupancy = (result.pageOccupancy(pageIndex) * 100f).toInt()
		println("Wrote ${target.path} (${page.width}x${page.height}, $occupancy% occupancy, ${bytes.size} bytes)")
	}
}

/**
 * Writes the placement report: the options the pack ran with, one line per placement, then the skips.
 *
 * @param AtlasPackResult result The packing outcome.
 * @param List layers            The layers that were offered to the packer, in the same order as [items].
 * @param List items             The pack items, parallel to [layers].
 * @param AtlasPackOptions options The options the pack ran with.
 * @param File inputFile         The source artwork file.
 * @param File outputDirectory   The directory to write into.
 */
private fun writeReport(
	result: AtlasPackResult,
	layers: List<SourceLayer>,
	items: List<AtlasPackItem>,
	options: AtlasPackOptions,
	inputFile: File,
	outputDirectory: File,
) {
	val layerNameByKey = items.indices.associate { itemIndex -> items[itemIndex].key to layers[itemIndex].name }
	val target = File(outputDirectory, "placements.txt")
	val report = StringBuilder()
	report.appendLine("#atlas placements for ${inputFile.name}")
	report.appendLine(
		"#options pageSize=${options.maxPageSize} gutter=${options.gutter} extrude=${options.extrude} " +
			"rotate=${options.allowRotation} shrink=${options.shrinkPages}",
	)
	for ((pageIndex, page) in result.pages.withIndex()) {
		report.appendLine(
			"#page $pageIndex ${page.width}x${page.height} ${(result.pageOccupancy(pageIndex) * 100f).toInt()}% occupancy",
		)
	}
	report.appendLine("#page\tx\ty\twidth\theight\tturns\ttrimX\ttrimY\tkey\tname")
	for (placement in result.placements) {
		report.appendLine(
			listOf(
				placement.pageIndex,
				placement.pageX,
				placement.pageY,
				placement.pageWidth,
				placement.pageHeight,
				placement.quarterTurns,
				placement.trimLeft,
				placement.trimTop,
				placement.key,
				layerNameByKey[placement.key].orEmpty(),
			).joinToString("\t"),
		)
	}
	for (skip in result.skipped) {
		report.appendLine("#skipped\t${skip.reason}\t${skip.key}\t${layerNameByKey[skip.key].orEmpty()}")
	}
	target.writeText(report.toString())
	println("Wrote ${target.path} (${result.placements.size} placements, ${result.skipped.size} skipped)")
}

/**
 * Prints the skip tally to stderr, grouped by reason.
 *
 * A page that looks right while several layers quietly went missing is this stage's characteristic
 * failure, so the skips are reported every run rather than only living in the written report.
 *
 * @param AtlasPackResult result The packing outcome.
 */
private fun reportSkips(result: AtlasPackResult) {
	if (result.skipped.isEmpty()) {
		return
	}
	for ((reason, skips) in result.skipped.groupBy { skip -> skip.reason }) {
		System.err.println("Note: ${skips.size} layer(s) not packed - $reason")
	}
}

/**
 * Reads every packed tile back out of its page and compares it to the source pixels.
 *
 * This is the command's reason to exist: the atlas is a repackable indirection over the source art,
 * so a page that does not reproduce the art it was built from is worthless however good it looks.
 * The page-coordinate mapping is written out here rather than shared with the packer, so a wrong
 * rotation convention cannot agree with itself.
 *
 * @param List items             The tiles that were packed.
 * @param AtlasPackResult result The packing outcome.
 * @return List One message per mismatching tile, empty when every tile matched.
 */
private fun verifyTiles(items: List<AtlasPackItem>, result: AtlasPackResult): List<String> {
	val itemsByKey = items.associateBy { item -> item.key }
	val mismatches = mutableListOf<String>()
	for (placement in result.placements) {
		val item = itemsByKey.getValue(placement.key)
		val page = result.pages[placement.pageIndex]
		var mismatch: String? = null
		for (tileY in 0 until placement.trimHeight) {
			for (tileX in 0 until placement.trimWidth) {
				val pageX = if (placement.quarterTurns == 0) placement.pageX + tileX else placement.pageX + tileY
				val pageY =
					if (placement.quarterTurns == 0) {
						placement.pageY + tileY
					} else {
						placement.pageY + placement.trimWidth - 1 - tileX
					}
				val sourceOffset = ((placement.trimTop + tileY) * item.width + placement.trimLeft + tileX) * 4
				val pageOffset = (pageY * page.width + pageX) * 4
				for (channelIndex in 0 until 4) {
					if (item.rgba[sourceOffset + channelIndex] != page.rgba[pageOffset + channelIndex]) {
						mismatch = "'${placement.key}' tile pixel ($tileX, $tileY) at page ($pageX, $pageY)"
						break
					}
				}
				if (mismatch != null) {
					break
				}
			}
			if (mismatch != null) {
				break
			}
		}
		mismatch?.let { message -> mismatches.add(message) }
	}
	return mismatches
}

/**
 * Composites the packed layers back onto a canvas-sized image, sampling from the PAGES.
 *
 * The byte check proves the pages carry the right pixels; this is what a human looks at to see that
 * the placements also mean the right thing.  Blend modes and layer opacity are ignored - it is a
 * source-over stack, not a compositor - so a document leaning on them will not match its own
 * application's render, which is expected rather than a packing fault.
 *
 * @param SourceArt art          The source document, for canvas size and layer positions.
 * @param List layers            The layers that were offered to the packer, parallel to [items].
 * @param List items             The pack items, parallel to [layers].
 * @param AtlasPackResult result The packing outcome.
 * @param File outputDirectory   The directory to write into.
 */
private fun writePreview(
	art: SourceArt,
	layers: List<SourceLayer>,
	items: List<AtlasPackItem>,
	result: AtlasPackResult,
	outputDirectory: File,
) {
	if (art.widthPx.toLong() * art.heightPx.toLong() > PREVIEW_PIXEL_CAP) {
		System.err.println("Note: ${art.widthPx}x${art.heightPx} canvas exceeds the preview cap; preview skipped.")
		return
	}
	val placementByKey = result.placements.associateBy { placement -> placement.key }
	val canvas = ByteArray(art.widthPx * art.heightPx * 4)
	// Source-over from the bottom up: order is top-most first, so the highest order draws first.
	for (layerIndex in layers.indices.sortedByDescending { layerIndex -> layers[layerIndex].order }) {
		val placement = placementByKey[items[layerIndex].key] ?: continue
		drawPlacementOntoCanvas(canvas, art.widthPx, art.heightPx, layers[layerIndex], placement, result)
	}
	val target = File(outputDirectory, "preview.png")
	val bytes = PngCodec.write(RasterImage(art.widthPx, art.heightPx, canvas))
	target.writeBytes(bytes)
	println("Wrote ${target.path} (${art.widthPx}x${art.heightPx} recomposite from the packed pages, ${bytes.size} bytes)")
}

/**
 * Draws one placed layer onto the preview canvas, reading its pixels out of the atlas page.
 *
 * @param ByteArray canvas       The canvas buffer, RGBA8888 straight alpha.
 * @param Int canvasWidth        The canvas width in pixels.
 * @param Int canvasHeight       The canvas height in pixels.
 * @param SourceLayer layer      The layer being drawn, for its canvas position.
 * @param AtlasPackPlacement placement Where its pixels sit on a page.
 * @param AtlasPackResult result The packing outcome holding the pages.
 */
private fun drawPlacementOntoCanvas(
	canvas: ByteArray,
	canvasWidth: Int,
	canvasHeight: Int,
	layer: SourceLayer,
	placement: AtlasPackPlacement,
	result: AtlasPackResult,
) {
	val page = result.pages[placement.pageIndex]
	for (tileY in 0 until placement.trimHeight) {
		val canvasY = layer.bounds.top + placement.trimTop + tileY
		if (canvasY < 0 || canvasY >= canvasHeight) {
			continue
		}
		for (tileX in 0 until placement.trimWidth) {
			val canvasX = layer.bounds.left + placement.trimLeft + tileX
			if (canvasX < 0 || canvasX >= canvasWidth) {
				continue
			}
			val pageX = if (placement.quarterTurns == 0) placement.pageX + tileX else placement.pageX + tileY
			val pageY =
				if (placement.quarterTurns == 0) {
					placement.pageY + tileY
				} else {
					placement.pageY + placement.trimWidth - 1 - tileX
				}
			val pageOffset = (pageY * page.width + pageX) * 4
			val canvasOffset = (canvasY * canvasWidth + canvasX) * 4
			val sourceAlpha = (page.rgba[pageOffset + 3].toInt() and 0xFF) / 255f
			if (sourceAlpha <= 0f) {
				continue
			}
			val destinationAlpha = (canvas[canvasOffset + 3].toInt() and 0xFF) / 255f
			val outAlpha = sourceAlpha + destinationAlpha * (1f - sourceAlpha)
			for (channelIndex in 0 until 3) {
				val sourceChannel = (page.rgba[pageOffset + channelIndex].toInt() and 0xFF) / 255f
				val destinationChannel = (canvas[canvasOffset + channelIndex].toInt() and 0xFF) / 255f
				val blended =
					(sourceChannel * sourceAlpha + destinationChannel * destinationAlpha * (1f - sourceAlpha)) / outAlpha
				canvas[canvasOffset + channelIndex] = (blended * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
			}
			canvas[canvasOffset + 3] = (outAlpha * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
		}
	}
}