package org.umamo.ui.document

import org.umamo.edit.EditorSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the editor opens into, and what File > New makes.
 *
 * Three of these pin decisions that are invisible until they are wrong.  A new document must be CLEAN,
 * or the quit guard asks about work nobody did and the first Ctrl+N of a session prompts for nothing.
 * It must carry a canvas, because the viewport, the properties panel, and an export all read one and a
 * 0x0 rig has no frame to draw.  And its raster store must be its own: artwork decodes into whatever
 * store the document hands the import, so a shared one would carry this document's art into the next.
 */
class BlankDocumentTest {
	@Test
	fun aNewDocumentIsEmptyInEveryList() {
		val document = newBlankDocument()

		assertNull(document.path, "a document that was never saved has no file")
		assertEquals(UNTITLED_DOCUMENT_NAME, document.displayName)
		val puppet = document.puppet
		assertTrue(puppet.drawables.isEmpty(), "no drawables")
		assertTrue(puppet.parts.isEmpty(), "no parts")
		assertTrue(puppet.deformers.isEmpty(), "no deformers")
		assertTrue(puppet.rootChildren.isEmpty(), "nothing in the organizational tree")
		assertTrue(puppet.atlas.tiles.isEmpty(), "no art on the atlas")
		assertTrue(puppet.sources.isEmpty(), "no artwork files behind it")
		assertTrue(
			puppet.parameters.isEmpty(),
			"and no parameters - the template seeds on the first artwork imported, not on the empty document",
		)
		assertTrue(document.textures.atlases.isEmpty(), "no atlas pages")
	}

	@Test
	fun aNewDocumentHasACanvasWithTheOriginAtItsCenter() {
		val puppet = newBlankDocument().puppet

		assertEquals(BLANK_DOCUMENT_CANVAS_SIZE, puppet.canvasWidth)
		assertEquals(BLANK_DOCUMENT_CANVAS_SIZE, puppet.canvasHeight)
		// World space negates canvas y, so the center sits at (w/2, -h/2) - what every import computes.
		assertEquals(BLANK_DOCUMENT_CANVAS_SIZE / 2f, puppet.worldOriginX)
		assertEquals(-(BLANK_DOCUMENT_CANVAS_SIZE / 2f), puppet.worldOriginZ)
	}

	@Test
	fun aNewDocumentStartsClean() {
		val document = newBlankDocument()

		val session = EditorSession(document.puppet, document.liveParams.values)

		assertFalse(session.dirty.value, "nothing has been edited yet, so quitting must not ask")
	}

	@Test
	fun eachNewDocumentBringsItsOwnRasterStore() {
		val first = newBlankDocument()
		val second = newBlankDocument()

		assertNotSame(first.artRasters, second.artRasters, "two documents never share the store artwork decodes into")
	}
}