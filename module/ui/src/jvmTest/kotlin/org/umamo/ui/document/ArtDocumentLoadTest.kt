package org.umamo.ui.document

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.interop.ExportNotice
import org.umamo.interop.art.HumanoidParameters
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.deriveAtlasTextures
import org.umamo.runtime.model.ParameterNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end open of a real layered PSD through [loadDocument] into [buildArtDocument]: the bridge,
 * the pack at open, and the document's page set, then the CMO3 export the shell would run on it.
 *
 * Two things only a real file proves.  The pages the pack composed at open must equal the pages
 * [deriveAtlasTextures] composes from the packed model - the derivation is what undo and the resolver
 * rebuild from, so a difference there is a viewport that changes when nothing was edited.  And the
 * fresh-graph export must re-import to the same rig, which is the promise that an artwork-origin
 * document is shippable from the moment it opens.
 *
 * Reads `psd.sample` (defaulted to the local corpus by the build) and self-skips without it.
 */
class ArtDocumentLoadTest {
	private val sample: File? = System.getProperty("psd.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aCorpusPsdOpensPackedAndExportsToARigThatReimports() {
		val file = sample
		if (file == null) {
			println("psd.sample not present; skipping the artwork document gate")
			return
		}
		val load = loadDocument(file.readBytes(), file.name, file.path)
		val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
		val puppet = document.puppet
		for (notice in document.importNotices) {
			println("import note: $notice")
		}

		assertTrue(puppet.drawables.isNotEmpty(), "the PSD has layers to rig")
		assertEquals(HumanoidParameters.list, puppet.parameters, "the default template seeds the humanoid set")
		assertTrue(puppet.rendersFromSourceLayers, "a fresh import shows the layers as drawn")
		assertTrue(puppet.atlas.pages.isNotEmpty(), "the pack at open produced pages")
		assertEquals(puppet.atlas.pages.size, document.textures.atlases.size, "one composed page per model page")

		// Every bound tile is either on a page or named in the notes - never silently unpacked.
		val notedUnpacked =
			document.importNotices.mapNotNull { notice ->
				when (notice) {
					is SourceArtImportNotice.LayerLargerThanPage -> notice.layerName
					is SourceArtImportNotice.LayerNotPacked -> notice.layerName
					else -> null
				}
			}.toSet()
		for (drawable in puppet.drawables) {
			val tile = assertNotNull(puppet.atlas.tileById[assertNotNull(drawable.atlasTileId)])
			val placement = tile.placement
			if (placement == null) {
				assertTrue(tile.name in notedUnpacked, "unpacked tile '${tile.name}' is named in the import notes")
			} else {
				assertTrue(placement.pageIndex in puppet.atlas.pages.indices, "tile '${tile.name}' sits on a page the model has")
				assertEquals(placement.pageIndex, document.textures.atlasIndexByDrawableId[drawable.id.raw], "the renderer's page map agrees")
			}
			assertNotNull(document.artRasters.rasterFor(tile.id), "tile '${tile.name}' has its pixels")
		}

		// The pack's pages ARE the derivation's pages, byte for byte.
		val derived = assertNotNull(deriveAtlasTextures(puppet, document.artRasters, premultipliedAlpha = false), "the packed model derives")
		assertEquals(document.textures.atlases.size, derived.atlases.size, "derived page count")
		for ((pageIndex, page) in document.textures.atlases.withIndex()) {
			val derivedPage = derived.atlases[pageIndex]
			assertEquals(page.width, derivedPage.width, "page $pageIndex width")
			assertEquals(page.height, derivedPage.height, "page $pageIndex height")
			assertTrue(page.rgba.contentEquals(derivedPage.rgba), "page $pageIndex composed at open differs from its derivation")
		}

		// The fresh-graph export re-imports to the same rig.
		val prepared =
			prepareCmo3Export(
				document = document,
				edited = puppet,
				effectiveTextures = document.textures,
				modelName = "gate",
				nowMillis = 0L,
				obfuscateKey = 0,
			)
		assertTrue(prepared.report.notices.any { notice -> notice is ExportNotice.MissingSourceArt }, "the export says the source art is not written yet")
		val reread = Cmo3.read(Cmo3.write(prepared.model))
		val rereadRoot = reread.root as CModelSource

		// Every exported art mesh carries a keyform grid with its default cell.  The official editor
		// refuses a mesh without one (setKeyformGridSource rejects null, getDefaultKeyForm throws "no
		// KeyForms"), and every drawable born from artwork is unkeyed - so this is the line between a
		// file that opens in Cubism and one that fills its log and crashes.
		val artMeshes = graphElements((rereadRoot.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()
		assertEquals(puppet.drawables.size, artMeshes.size, "one art mesh per drawable in the written graph")
		for (artMesh in artMeshes) {
			val gridSource = assertNotNull(artMesh.keyformGridSource as? KeyformGridSource, "art mesh '${artMesh.localName}' has a keyform grid")
			assertTrue(graphElements(gridSource.keyformsOnGrid).isNotEmpty(), "art mesh '${artMesh.localName}' grid has its default cell")
			assertTrue(graphElements(artMesh.keyforms).isNotEmpty(), "art mesh '${artMesh.localName}' has a default form")
		}

		val reimported = Cmo3Import.fromModelSource(rereadRoot)
		// Compared by id, not by list position: a CMO3's storage order is not the panel order (the org
		// tree is), and the ids are what the export writes and the re-import reads back.
		val rereadById = reimported.drawables.associateBy { drawable -> drawable.id }
		assertEquals(puppet.drawables.map { drawable -> drawable.id }.toSet(), rereadById.keys, "the same drawables come back")
		assertEquals(
			puppet.parts.associate { part -> part.id to part.name },
			reimported.parts.associate { part -> part.id to part.name },
			"the same parts come back, named the same",
		)
		assertEquals(puppet.rootChildren, reimported.rootChildren, "the org tree's root keeps its order")
		assertEquals(puppet.parameters.map { parameter -> parameter.id }, reimported.parameters.map { parameter -> parameter.id }, "the seeded parameters")
		// Every parameter sits in the written group hierarchy: the editor logs a recovery for each one it
		// finds outside it, and the re-import builds this tree from that hierarchy alone.
		assertEquals(
			puppet.parameters.map { parameter -> ParameterNode.Param(parameter.id) },
			reimported.parameterTree,
			"every seeded parameter is in the root parameter group, in order",
		)
		assertEquals(puppet.atlas.pages, reimported.atlas.pages, "the page inventory")
		for (drawable in puppet.drawables) {
			val expected = assertNotNull(drawable.mesh)
			val reread = assertNotNull(rereadById[drawable.id], "${drawable.name} re-imports")
			assertEquals(drawable.name, reread.name, "${drawable.id.raw} keeps its name")
			val actual = assertNotNull(reread.mesh, "${drawable.name} keeps its mesh")
			assertEquals(expected.vertexCount, actual.vertexCount, "${drawable.name} vertex count")
			for (componentIndex in expected.uvs.indices) {
				assertEquals(expected.uvs[componentIndex], actual.uvs[componentIndex], 1e-4f, "${drawable.name} uv component $componentIndex")
				assertEquals(expected.positions[componentIndex], actual.positions[componentIndex], 1e-2f, "${drawable.name} position component $componentIndex")
			}
		}
		println("artwork gate: ${puppet.drawables.size} drawables, ${puppet.parts.size} parts, ${puppet.atlas.pages.size} page(s), ${document.importNotices.size} note(s)")
	}

	/**
	 * A CMO3 collection field as its elements, whichever container shape the serializer used.
	 *
	 * @param Any? collection The raw field.
	 * @return List The elements.
	 */
	private fun graphElements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}
}