package org.umamo.ui.document

import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The export dialog's session memory: what it opens with, what sticks across documents, and what
 * deliberately does not.
 *
 * The two-lifetime rule under test: toggles are sticky for the whole session while the scale is
 * sticky only per document, re-seeding from the model on a document change - a confirmed bake
 * scale is a property of one rig, not of the rigger.
 */
class ExportOptionsSessionTest {
	/**
	 * A rig with only the fields the scale seed reads.
	 *
	 * @param Float  canvasWidth   The canvas width the heuristic falls back to.
	 * @param Float? pixelsPerUnit The recorded bake scale, or null for a CMO3-origin document.
	 * @return PuppetModel The rig.
	 */
	private fun puppetOf(canvasWidth: Float, pixelsPerUnit: Float? = null): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = canvasWidth,
			canvasHeight = canvasWidth,
			pixelsPerUnit = pixelsPerUnit,
		)

	@Test
	fun theFirstOpenGetsTheEditorMatchingDefaults() {
		val opened = Moc3ExportSessionOptions().dialogOptionsFor("/models/a.cmo3", puppetOf(canvasWidth = 100f))

		// The dialog defaults drop hidden objects and guides, like the official bake - deliberately
		// NOT Moc3ExportOptions.Default, which is the options-less API's carry-everything contract.
		assertFalse(opened.exportHiddenParts)
		assertFalse(opened.exportHiddenDrawables)
		assertFalse(opened.exportGuideImageParts)
		assertTrue(opened.includePhysics)
		assertTrue(opened.includeUserData)
		assertTrue(opened.includeDisplayInfo)
		assertEquals(100f, opened.pixelsPerUnitOverride, "the scale seeds from the canvas width heuristic")
	}

	@Test
	fun aRecordedScaleSeedsFromTheModelInsteadOfTheHeuristic() {
		val opened = Moc3ExportSessionOptions().dialogOptionsFor("/models/a.moc3", puppetOf(100f, pixelsPerUnit = 250f))

		assertEquals(250f, opened.pixelsPerUnitOverride, "a MOC3-origin document keeps its own bake scale")
	}

	@Test
	fun confirmedTogglesStickAcrossDocumentsButTheScaleDoesNot() {
		val session = Moc3ExportSessionOptions()
		session.recordConfirmed(
			"/models/a.cmo3",
			Moc3ExportOptions(exportHiddenParts = true, includeDisplayInfo = false, pixelsPerUnitOverride = 512f),
		)

		val reopened = session.dialogOptionsFor("/models/a.cmo3", puppetOf(100f))
		assertTrue(reopened.exportHiddenParts, "a confirmed toggle sticks")
		assertFalse(reopened.includeDisplayInfo)
		assertEquals(512f, reopened.pixelsPerUnitOverride, "the same document keeps the confirmed scale")

		val otherDocument = session.dialogOptionsFor("/models/b.cmo3", puppetOf(300f))
		assertTrue(otherDocument.exportHiddenParts, "toggles stick across documents")
		assertFalse(otherDocument.includeDisplayInfo)
		assertEquals(300f, otherDocument.pixelsPerUnitOverride, "the scale re-seeds on a document change")
	}
}