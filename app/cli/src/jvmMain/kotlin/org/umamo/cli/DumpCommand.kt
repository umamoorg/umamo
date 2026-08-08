package org.umamo.cli

import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.serialize.ModelDocument
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import org.umamo.runtime.model.PuppetModel

/*
 * The dump subcommand: human-readable (and, for MOC3, dump_model.c-diffable) file contents.
 */

/**
 * Runs `dump <file> [--sections] [--xml] [--puppet]`.
 *
 * @param List arguments The subcommand's arguments.
 * @return Int The exit code.
 */
internal fun runDump(arguments: List<String>): Int {
	val flags = arguments.filter { argument -> argument.startsWith("--") }.toSet()
	val positionals = arguments.filterNot { argument -> argument.startsWith("--") }
	val unknownFlags = flags - setOf("--sections", "--xml", "--puppet")
	if (unknownFlags.isNotEmpty()) {
		throw CliUsageException("unknown dump flag(s): ${unknownFlags.joinToString(", ")}")
	}
	if (positionals.size != 1) {
		throw CliUsageException("usage: dump <file> [--sections] [--xml] [--puppet]")
	}
	val loaded = loadInput(positionals[0])
	when (loaded) {
		is LoadedInput.Moc3Input -> {
			if ("--xml" in flags) {
				throw CliUsageException("--xml applies to cmo3 input only")
			}
			if ("--sections" in flags) {
				dumpMoc3Sections(loaded)
			} else {
				dumpMoc3Document(loaded.document)
			}
		}

		is LoadedInput.Cmo3Input -> {
			if ("--sections" in flags) {
				throw CliUsageException("--sections applies to moc3 input only")
			}
			if ("--xml" in flags) {
				dumpCmo3Xml(loaded.model)
				return 0
			}
			dumpCmo3Overview(loaded.model)
		}
	}
	if ("--puppet" in flags) {
		dumpPuppetSummary(importPuppet(loaded))
	}
	return 0
}

/**
 * The MOC3 semantic summary, mirroring dump_model.c's static line grammar (`P`/`T`/`D` records) so
 * the relive harness's diff scripts can consume it.  Runtime-evaluated columns (posed draw/render
 * order, evaluated opacities, vertex-position hashes) are deliberately absent - this dumps the
 * static rig, not a revived core.
 *
 * @param MocDocument document The decoded moc.
 */
private fun dumpMoc3Document(document: MocDocument) {
	println("# MOC3 version=${document.version.byteValue}")
	document.canvas?.let { canvas ->
		val size = "${formatSixSignificant(canvas.width)},${formatSixSignificant(canvas.height)}"
		val origin = "${formatSixSignificant(canvas.originX)},${formatSixSignificant(canvas.originY)}"
		println("# canvas size=$size origin=$origin ppu=${formatSixSignificant(canvas.pixelsPerUnit)}")
	}
	println(
		"# counts parameters=${document.parameters.size} parts=${document.parts.size} " +
			"drawables=${document.artMeshes.size} deformers=${document.deformers.size} " +
			"glues=${document.glues.size} groups=${document.renderOrderGroups.size} " +
			"bindings=${document.bindings.size} blendShapes=${document.blendShapes.size} " +
			"offscreens=${document.offscreens.size}",
	)
	println("[parameters]")
	document.parameters.forEachIndexed { parameterIndex, parameter ->
		println(
			"P $parameterIndex id=${parameter.id} type=${parameter.type?.number ?: -1} " +
				"min=${formatSixSignificant(parameter.minimumValue)} max=${formatSixSignificant(parameter.maximumValue)} " +
				"def=${formatSixSignificant(parameter.defaultValue)} repeat=${if (parameter.repeats) 1 else 0}",
		)
	}
	println("[parts]")
	document.parts.forEachIndexed { partIndex, part ->
		println("T $partIndex id=${part.id} parent=${part.parentPartIndex}")
	}
	println("[drawables]")
	document.artMeshes.forEachIndexed { meshIndex, artMesh ->
		println(
			"D $meshIndex id=${artMesh.id} cflag=0x${(artMesh.constantFlags and 0xFF).toString(16).padStart(2, '0')} " +
				"tex=${artMesh.textureIndex} part=${artMesh.parentPartIndex} vtx=${artMesh.vertexCount} " +
				"idx=${artMesh.triangleIndices.size} masks=${artMesh.maskDrawableIndices.size}",
		)
	}
}

/**
 * The MOC3 container tier: version, CountInfo, and per-section presence with element counts.
 *
 * @param LoadedInput.Moc3Input loaded The moc3 input (re-read at the container tier).
 */
private fun dumpMoc3Sections(loaded: LoadedInput.Moc3Input) {
	val container = MocCodec.read(loaded.family.mocFile.readBytes())
	println("# MOC3 version=${container.versionByte} sections=${container.sectionCount} bigEndian=${container.isBigEndian}")
	println("# countInfo ${container.countInfo.joinToString(",")}")
	var presentCount = 0
	for (section in Section.entries) {
		if (!container.sections.isPresent(section)) {
			continue
		}
		presentCount++
		println("S ${section.name} count=${container.sections.elementCount(section)}")
	}
	println("# sections present=$presentCount modeled=${Section.entries.size}")
}

/**
 * The CMO3 overview: target version, the CAFF entry table, the embedded image resources, and the
 * parsed main.xml prologue summary.
 *
 * @param Cmo3Model model The read model.
 */
private fun dumpCmo3Overview(model: Cmo3Model) {
	val archive = model.archive
	println("# CMO3 targetVersionNo=${model.targetVersionNo ?: "-"}")
	println(
		"# caff formatVersion=${archive.formatVersion.joinToString(".")} " +
			"archiveVersion=${archive.archiveVersion.joinToString(".")} " +
			"obfuscateKey=${archive.obfuscateKey}",
	)
	println("[entries]")
	archive.entries.forEachIndexed { entryIndex, entry ->
		println(
			"E $entryIndex path=${entry.path} tag=${entry.tag} size=${entry.content.size} " +
				"compression=${entry.compression} obfuscated=${if (entry.obfuscated) 1 else 0}",
		)
	}
	println("[images]")
	model.imageResources().forEachIndexed { imageIndex, resource ->
		val pngSize = model.extractLayerPng(resource)?.size ?: 0
		println("I $imageIndex type=${resource.type} width=${resource.width} height=${resource.height} pngBytes=$pngSize")
	}
	val mainXml = archive.firstByTag(CaffArchive.TAG_MAIN_XML)
	if (mainXml != null) {
		// sharedElements/mainElements are JDOM-typed and JDOM stays :format-internal; the class-version
		// table and import list are the useful prologue summary anyway.
		val parsed = ModelDocument.parse(mainXml.content)
		println("[xml]")
		println("# versions ${parsed.versions.entries.joinToString(" ") { (className, versionNumber) -> "$className=$versionNumber" }}")
		println("# imports ${parsed.imports.joinToString(" ")}")
	}
}

/**
 * Writes the decompressed main.xml to stdout byte-for-byte, so a shell redirect round-trips it.
 * CaffEntry.content is already inflated and deobfuscated by the CAFF read.
 *
 * @param Cmo3Model model The read model.
 */
private fun dumpCmo3Xml(model: Cmo3Model) {
	val mainXml =
		model.archive.firstByTag(CaffArchive.TAG_MAIN_XML)
			?: throw IllegalStateException("archive has no main_xml entry")
	System.out.write(mainXml.content)
	System.out.flush()
}

/**
 * The imported-puppet summary: entity counts and the parameter table - the exact import path the
 * convert command lowers from, so a suspect conversion can be inspected at this tier first.
 *
 * @param PuppetModel puppet The imported puppet.
 */
private fun dumpPuppetSummary(puppet: PuppetModel) {
	println("[puppet]")
	println(
		"# counts parameters=${puppet.parameters.size} parts=${puppet.parts.size} " +
			"deformers=${puppet.deformers.size} drawables=${puppet.drawables.size} glues=${puppet.glues.size}",
	)
	println(
		"# canvas ${formatSixSignificant(puppet.canvasWidth)}x${formatSixSignificant(puppet.canvasHeight)} " +
			"origin=${formatSixSignificant(puppet.worldOriginX)},${formatSixSignificant(puppet.worldOriginY)} " +
			"target=${puppet.runtimeTarget.name}",
	)
	puppet.parameters.forEachIndexed { parameterIndex, parameter ->
		println(
			"P $parameterIndex id=${parameter.id.raw} min=${formatSixSignificant(parameter.min)} " +
				"max=${formatSixSignificant(parameter.max)} def=${formatSixSignificant(parameter.default)}",
		)
	}
}
