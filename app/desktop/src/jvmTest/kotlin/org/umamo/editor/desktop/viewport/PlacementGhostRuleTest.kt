package org.umamo.editor.desktop.viewport

import org.umamo.render.puppet.OverlayColor
import org.umamo.render.puppet.PlacementCropQuad
import org.umamo.render.puppet.PlacementPreview
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.PuppetAtlas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the ghost rule (decision D20): a placement preview draws its ghost crops until the pages the engine
 * applies belong to the ghost's atlas - by identity, or by equality, since equal atlases compose the same
 * pixels - and then draws the rest of the preview without them.
 */
class PlacementGhostRuleTest {
	private val committed = PuppetAtlas(pages = listOf(AtlasPage(16, 16)))
	private val older = PuppetAtlas(pages = listOf(AtlasPage(32, 32)))

	@Test
	fun noPreviewDrawsNothing() {
		assertNull(placementToDraw(null, committed))
	}

	@Test
	fun aPreviewWithoutAGhostIsDrawnAsItIs() {
		val preview = preview(ghostAtlas = null)
		assertSame(preview, placementToDraw(preview, committed))
	}

	@Test
	fun aGhostStandsWhileTheAppliedPagesAreAnotherAtlas() {
		val preview = preview(ghostAtlas = committed)
		assertSame(preview, placementToDraw(preview, older), "the old pages are still applied, so the ghost stands in")
	}

	@Test
	fun aGhostRetiresOnTheFirstFrameItsPagesAreApplied() {
		val preview = preview(ghostAtlas = committed)

		val drawn = placementToDraw(preview, committed)

		assertNotSame(preview, drawn)
		assertTrue(drawn!!.ghostCrops.isEmpty(), "its pages are applied, so the ghost is gone")
		assertNull(drawn.ghostAtlas)
		assertSame(preview.crops, drawn.crops, "the movers' crops stay")
		assertSame(preview.scrimQuads, drawn.scrimQuads, "and so do the scrims")
		assertEquals(preview.scrimColor, drawn.scrimColor)
	}

	@Test
	fun anEqualAtlasRetiresTheGhostToo() {
		val preview = preview(ghostAtlas = committed)
		val equal = committed.copy()

		assertEquals(committed, equal)
		assertTrue(placementToDraw(preview, equal)!!.ghostCrops.isEmpty(), "equal placements compose the same pixels")
	}

	/**
	 * A preview with one scrim, one mover's crop, and one ghost crop.
	 *
	 * @param PuppetAtlas? ghostAtlas The ghost's atlas, or null for no ghost.
	 * @return PlacementPreview The preview.
	 */
	private fun preview(ghostAtlas: PuppetAtlas?): PlacementPreview {
		val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
		val crop = PlacementCropQuad("tile", null, floatArrayOf(4f, 0f, 1f, 0f, 4f, 1f), identity)
		return PlacementPreview(
			OverlayColor(0f, 0f, 0f, 0.5f),
			listOf(floatArrayOf(4f, 0f, 0f, 0f, 4f, 0f)),
			listOf(crop),
			ghostAtlas,
			if (ghostAtlas == null) emptyList() else listOf(crop),
		)
	}
}