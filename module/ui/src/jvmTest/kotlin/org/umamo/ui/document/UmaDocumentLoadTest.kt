package org.umamo.ui.document

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.interop.uma.UmaDocumentBridge
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.workspace.spaces.sources.SourcePresence
import org.umamo.ui.workspace.spaces.sources.SourcesStatus
import org.umamo.ui.workspace.spaces.sources.buildSourcesTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How a `.uma` opens through the same byte-level loader every other document uses: as a UmaDocument, with
 * the file kept whole for the next save; read-only when the file holds a required entry this version
 * cannot interpret; and with a typed reason when it cannot open at all.  Synthetic files throughout - a
 * document the codec wrote from an empty rig, then rewritten by hand where a case needs a manifest this
 * writer would never produce.
 */
class UmaDocumentLoadTest {
	@Test
	fun aUmaOpensAsAUmaDocumentKeptWholeForTheNextSave() {
		val bytes = emptyDocumentBytes()

		val load = loadDocument(bytes, "rig.uma", "/rigs/rig.uma")

		val document = assertIs<UmaDocument>(assertIs<DocumentLoad.Loaded>(load).document)
		assertEquals("/rigs/rig.uma", document.path)
		assertFalse(document.isReadOnly)
		assertTrue(document.puppet.drawables.isEmpty(), "the empty rig came back empty")
		assertEquals(newBlankDocument().puppet.canvasWidth, document.puppet.canvasWidth, "with its canvas")
		assertTrue(document.storedPages == null, "a file with no render pages derives them")
		assertEquals(bytes.size, Uma.write(document.uma).size, "the file is kept whole: rewriting it costs nothing new")
	}

	@Test
	fun aRequiredEntryThisVersionCannotReadOpensReadOnly() {
		val bytes = umaBytesWithUnknownRequiredEntry()

		val document = assertIs<UmaDocument>(assertIs<DocumentLoad.Loaded>(loadDocument(bytes, "rig.uma", "/rigs/rig.uma")).document)

		assertTrue(document.isReadOnly, "an unknown required entry blocks saving")
		assertEquals(listOf("future/thing.json"), document.readOnlyReasons.map { reason -> reason.path })
		assertFalse(DocumentFile(document).canSave, "and the save target says so")
	}

	@Test
	fun aNewerContainerReportsANewerFormat() {
		val bytes =
			rewritten(emptyDocumentBytes()) { manifest ->
				val patched = manifest.replaceFirst(Regex("\"containerVersion\":\\s*1"), "\"containerVersion\": 99")
				check(patched != manifest) { "the container version was not found: $manifest" }
				patched
			}

		val failure = assertIs<DocumentLoad.Failed>(loadDocument(bytes, "rig.uma", "/rigs/rig.uma")).failure

		assertEquals(DocumentOpenError.NewerFormat, failure.error)
		assertEquals("rig.uma", failure.displayName)
	}

	/**
	 * A puppet entry this version cannot read is a file from a newer Umamo too: there is no rig to show, and
	 * the message must not call a healthy file damaged.
	 */
	@Test
	fun aNewerPuppetEntryReportsANewerFormat() {
		val bytes =
			rewritten(emptyDocumentBytes()) { manifest ->
				// The puppet's record is the first entry: raise its version and the floor a reader must meet.
				val patched = manifest.replaceFirst(Regex("\"version\":\\s*1"), "\"version\": 2").replaceFirst(Regex("\"minVersion\":\\s*1"), "\"minVersion\": 2")
				check(patched != manifest) { "the puppet record was not found: $manifest" }
				patched
			}

		val failure = assertIs<DocumentLoad.Failed>(loadDocument(bytes, "rig.uma", "/rigs/rig.uma")).failure

		assertEquals(DocumentOpenError.NewerFormat, failure.error)
	}

	/**
	 * A sources entry too new for this version is carried, so the document lists no file while its tiles still
	 * name one.  The bindings survive the open as they were, and the Sources space shows every such tile for
	 * review rather than losing it (UMA §3.6).
	 */
	@Test
	fun aTooNewSourcesEntryLeavesEveryBindingShown() {
		val sourceId = ArtSourceId("art-0")
		val tileId = AtlasTileId("art-0/lyid:1")
		val blank = newBlankDocument().puppet
		val puppet =
			blank.copy(
				atlas = PuppetAtlas(pages = emptyList(), tiles = listOf(AtlasTile(tileId, "Eye", 4, 4, source = SourceLayerRef(sourceId, "lyid:1", true)))),
				sources = listOf(ArtSource(sourceId, "eye.psd", null, "psd", listOf(ArtSourceLayer("lyid:1", "Eye", "", 0, 0, 4, 4, true)))),
			)
		val tilePng = PngCodec.write(RasterImage(4, 4, ByteArray(4 * 4 * 4)))
		val written = Uma.write(UmaDocumentBridge.documentOf(UmaModel.create(umamoWriterInfo()), puppet, UmaPixelSource({ tilePng }, UmaRenderPagePixels.Derived, null)))
		val bytes =
			rewritten(written) { manifest ->
				// The sources record: raise its version and the floor a reader must meet above this version's.
				val sourcesRecord = Regex("(\"kind\":\\s*\"sources\",\\s*\"version\":\\s*)1(,\\s*\"minVersion\":\\s*)1")
				val patched = sourcesRecord.replace(manifest) { match -> "${match.groupValues[1]}2${match.groupValues[2]}2" }
				check(patched != manifest) { "the sources record was not found: $manifest" }
				patched
			}

		val document = assertIs<UmaDocument>(assertIs<DocumentLoad.Loaded>(loadDocument(bytes, "rig.uma", "/rigs/rig.uma")).document)

		assertFalse(document.isReadOnly, "an optional entry too new to read leaves the document editable")
		assertTrue(document.puppet.sources.isEmpty(), "the carried entry lists nothing this version can read")
		assertEquals(SourceLayerRef(sourceId, "lyid:1", true), document.puppet.atlas.tiles.single().source, "the tile keeps its binding")
		val tree = buildSourcesTree(document.puppet, { SourcePresence.Unknown }, "Unbound art")
		val shown = tree.single().children.single()
		assertEquals("tile:${tileId.raw}", shown.id, "the tile is listed")
		assertEquals(SourcesStatus.SourceNotListed, shown.status, "for review")
	}

	@Test
	fun aDamagedUmaReportsAParseFailure() {
		val bytes = emptyDocumentBytes()
		val truncated = bytes.copyOf(bytes.size - 40)

		val failure = assertIs<DocumentLoad.Failed>(loadDocument(truncated, "rig.uma", "/rigs/rig.uma")).failure

		assertEquals(DocumentOpenError.ParseFailed, failure.error)
	}
}