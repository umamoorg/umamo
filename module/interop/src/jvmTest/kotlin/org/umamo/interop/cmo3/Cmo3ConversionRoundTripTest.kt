package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.png.PngCodec
import org.umamo.interop.DocumentField
import org.umamo.interop.EntityDiff
import org.umamo.interop.ExportNotice
import org.umamo.interop.cmo3.Cmo3Conversion.AtlasPage
import org.umamo.interop.moc3.Moc3Import
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The MOC3 -> CMO3 self round trip: every corpus .moc3 with a model3 sidecar converts to a
 * fresh CMO3, and re-importing the written file reproduces the source PuppetModel with only the
 * documented residues - WORLD_ORIGIN (the moc stores a real origin; CMO3 import derives the
 * canvas center) and bounded-ULP drift on keyform deltas (fresh absolutes are base + delta and
 * the re-import subtracts, which is not an IEEE identity).
 */
class Cmo3ConversionRoundTripTest {
	private val geometryUlpTolerance = 1e-3f

	@Test
	fun corpusMocModelsConvertAndRoundTrip() {
		val samplesDirectory =
			System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }
				?: run {
					println("moc3.samples not present; skipping MOC3 conversion round trip")
					return
				}
		val mocFiles =
			samplesDirectory
				.walkTopDown()
				.filter { file -> file.isFile && file.extension == "moc3" }
				.filter { file -> File(file.parentFile, "${file.nameWithoutExtension}.model3.json").isFile }
				.sortedBy { file -> file.name }
				.toList()
		assertTrue(mocFiles.isNotEmpty(), "no corpus moc3 with a model3 sidecar under $samplesDirectory")
		val failures = ArrayList<String>()
		for (mocFile in mocFiles) {
			runCatching { roundTripOne(mocFile, failures) }
				.onFailure { failure -> failures.add("${mocFile.name}: threw $failure") }
		}
		assertTrue(failures.isEmpty(), "MOC3 conversion round trip failed:\n" + failures.joinToString("\n"))
	}

	/**
	 * Converts one corpus moc and validates the self round trip.
	 *
	 * @param File mocFile The .moc3 file (sidecars beside it).
	 * @param ArrayList failures The shared violation collector.
	 */
	private fun roundTripOne(mocFile: File, failures: ArrayList<String>) {
		val label = mocFile.name
		val mocDocument = Moc3.decode(mocFile.readBytes())
		val manifest = Moc3.readModel3(File(mocFile.parentFile, "${mocFile.nameWithoutExtension}.model3.json").readText())
		val textureFiles = manifest.fileReferences.textures.map { textureReference -> File(mocFile.parentFile, textureReference) }
		if (textureFiles.any { textureFile -> !textureFile.isFile }) {
			println("$label: atlas pages not in the corpus; skipping")
			return
		}
		val pages =
			textureFiles.map { textureFile ->
				val pngBytes = textureFile.readBytes()
				val decoded = PngCodec.read(pngBytes)
				AtlasPage(pngBytes, decoded.width, decoded.height)
			}
		// displayInfo deliberately null: the same puppet is both the conversion source and the
		// comparison target, so cosmetic names/groups cancel out either way.
		val puppet = Moc3Import.fromMocDocument(mocDocument, displayInfo = null)
		val pageIndexByDrawableId = mocDocument.artMeshes.associate { artMesh -> artMesh.id to artMesh.textureIndex }

		val result =
			Cmo3Conversion.freshCmo3(
				puppet = puppet,
				pages = pages,
				pageIndexByDrawableId = pageIndexByDrawableId,
				modelName = mocFile.nameWithoutExtension,
				nowMillis = 1_700_000_000_000L,
				obfuscateKey = 0x5EEDBEEF,
			)
		// Documented residues may notice: the world origin (moc stores one; CMO3 derives the canvas
		// center) and part statics shadowed by their keyform tracks (import re-derives from the
		// first form, so a disagreeing static cannot be stored independently).
		result.report.notices.filterNot { notice ->
			notice is ExportNotice.UnsupportedChange &&
				(
					(notice.category == "document" && "origin" in notice.detail) ||
						(notice.category == "part" && "shadowed" in notice.detail)
				)
		}.forEach { notice ->
			failures.add("$label: unexpected notice $notice")
		}

		val written = Cmo3.write(result.model)
		// Each converted corpus model doubles as a manual official-editor gate input.
		File("build/converted-${mocFile.nameWithoutExtension}.cmo3").writeBytes(written)

		// Prologue completeness: re-running the writer's own reconciliation on the written bytes
		// must be a no-op - a missing or stale import/version PI here is exactly the malformed-file
		// class the official editor rejects (it resolves element tags through the import list).
		val writtenMainXml =
			org.umamo.format.cmo3.caff.CaffCodec.read(written)
				.firstByTag(org.umamo.format.cmo3.caff.CaffArchive.TAG_MAIN_XML)!!
				.content
		val reparsed = org.umamo.format.cmo3.xml.XmlCodec.parse(writtenMainXml)
		org.umamo.format.cmo3.Cmo3Author.completePrologue(reparsed)
		if (!org.umamo.format.cmo3.xml.XmlCodec.write(reparsed).contentEquals(writtenMainXml)) {
			failures.add("$label: written prologue is incomplete (completePrologue on the output is not a no-op)")
		}
		val reimportedSource =
			Cmo3.read(written).root as? CModelSource
				?: run {
					failures.add("$label: re-read root is not a CModelSource")
					return
				}
		val reimported = Cmo3Import.fromModelSource(reimportedSource)
		val residual = org.umamo.interop.diffPuppetModels(reimported, puppet)
		if (residual.isEmpty) {
			return
		}

		// Allowed residues: WORLD_ORIGIN plus bounded-ULP GEOMETRY/BLEND_SHAPES drift.
		(residual.document - setOf(DocumentField.WORLD_ORIGIN)).forEach { field ->
			failures.add("$label: unexpected document residue $field")
		}
		residual.parameters.forEach { failures.add("$label: parameter residue $it") }
		residual.parameterGroups.forEach { failures.add("$label: group residue $it") }
		for (entityDiff in residual.parts) {
			// The statics-shadowed residue class: a static disagreeing with its track's head cell
			// (noticed above) re-imports as the head value.
			if (entityDiff !is EntityDiff.Changed || entityDiff.fields.any { field -> field.name !in setOf("DRAW_ORDER", "COMPOSITE") }) {
				failures.add("$label: part residue $entityDiff")
			}
		}
		residual.glues.forEach { failures.add("$label: glue residue $it") }
		for (entityDiff in residual.deformers) {
			if (entityDiff !is EntityDiff.Changed || entityDiff.fields.any { field -> field.name !in setOf("GEOMETRY", "BLEND_SHAPES") }) {
				failures.add("$label: deformer residue $entityDiff")
			}
		}
		for (entityDiff in residual.drawables) {
			if (entityDiff !is EntityDiff.Changed || entityDiff.fields.any { field -> field.name !in setOf("GEOMETRY", "BLEND_SHAPES") }) {
				failures.add("$label: drawable residue $entityDiff")
				continue
			}
			val drift = maxGeometryDrift(puppet, reimported, entityDiff.id.raw)
			if (drift > geometryUlpTolerance) {
				failures.add("$label: drawable ${entityDiff.id.raw} geometry drifted by $drift")
			}
		}
	}

	/**
	 * The largest per-component keyform-delta difference between the two models' drawable grids.
	 *
	 * @param PuppetModel source     The conversion source.
	 * @param PuppetModel reimported The re-imported model.
	 * @param String      drawableIdRaw The drawable to compare.
	 * @return Float The maximum absolute component difference (0 when either grid is absent).
	 */
	private fun maxGeometryDrift(
		source: org.umamo.runtime.model.PuppetModel,
		reimported: org.umamo.runtime.model.PuppetModel,
		drawableIdRaw: String,
	): Float {
		val sourceGrid = source.drawables.firstOrNull { it.id.raw == drawableIdRaw }?.geometryGrid ?: return 0f
		val reimportedGrid = reimported.drawables.firstOrNull { it.id.raw == drawableIdRaw }?.geometryGrid ?: return 0f
		val reimportedByCoordinate = reimportedGrid.cells.associate { cell -> cell.coordinate.toList() to cell.form.positionDeltas }
		var maxDifference = 0f
		for (cell in sourceGrid.cells) {
			val reimportedDeltas = reimportedByCoordinate[cell.coordinate.toList()] ?: return Float.MAX_VALUE
			for (component in cell.form.positionDeltas.indices) {
				maxDifference = maxOf(maxDifference, abs(cell.form.positionDeltas[component] - reimportedDeltas[component]))
			}
		}
		return maxDifference
	}
}
