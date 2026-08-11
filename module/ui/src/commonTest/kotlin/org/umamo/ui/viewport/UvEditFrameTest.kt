package org.umamo.ui.viewport

import org.umamo.render.AtlasPlacement
import org.umamo.render.DrawableLayerBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the UV editor's frame conversions - the two ends of an edit, reading a stored coordinate to
 * show it and writing an authored one back - plus the commit discipline that keeps a vertex the user
 * never moved bit-identical to what the document stores.
 *
 * The page-view frame is the load-bearing case for "changed nothing": it must be exactly the texel
 * mapping the editor used before frames existed, or every page-view edit shifts.
 */
class UvEditFrameTest {
	private fun packedBinding(
		positionX: Float = 128f,
		positionY: Float = 256f,
		scaleX: Float = 1f,
		scaleY: Float = 1f,
		rotationDegrees: Float = 0f,
	): DrawableLayerBinding =
		DrawableLayerBinding(
			layerKey = "layer",
			placement = AtlasPlacement(0, positionX, positionY, scaleX, scaleY, rotationDegrees),
			pageWidth = 2048,
			pageHeight = 1024,
		)

	/** A page frame is the plain texel mapping - no conversion, and it says so. */
	@Test
	fun pageFrameIsTheStoredFrame() {
		val frame = atlasPageEditFrame(pageWidth = 64, pageHeight = 32)
		assertTrue(frame.isStoredFrame, "the page display IS the stored frame")
		val (u, v) = frame.storedUvAt(16f, 16f)
		assertEquals(displayToUvU(16f, 64), u, 0f, "u matches the bare display mapping exactly")
		assertEquals(displayToUvV(16f, 32), v, 0f, "v matches the bare display mapping exactly")
		val (displayX, displayY) = frame.displayAt(0.25f, 0.5f)
		assertEquals(uvToDisplayX(0.25f, 64), displayX, 0f, "and back out exactly")
		assertEquals(uvToDisplayY(0.5f, 32), displayY, 0f, "and back out exactly")
		assertEquals(null, frame.asUvFrame(), "a page frame carries no normalized conversion")
	}

	/** A layer frame round-trips a display point through the stored coordinates and back. */
	@Test
	fun layerFrameRoundTripsThroughTheStoredCoordinates() {
		val frame = sourceLayerEditFrame(packedBinding(rotationDegrees = 37.5f, scaleX = 0.86f, scaleY = 0.86f), 576, 646)
		assertNotNull(frame, "a well-formed placement yields a frame")
		assertFalse(frame.isStoredFrame, "a placed layer is not the stored frame")
		for ((displayX, displayY) in listOf(0f to 0f, 137f to 251f, 575f to 645f)) {
			val (u, v) = frame.storedUvAt(displayX, displayY)
			val (backX, backY) = frame.displayAt(u, v)
			assertEquals(displayX, backX, 1e-2f, "display x survives the round trip")
			assertEquals(displayY, backY, 1e-2f, "display y survives the round trip")
		}
	}

	/** An unpacked drawable's art is its stored frame, so it takes the page view's own no-conversion path. */
	@Test
	fun unpackedLayerFrameIsTheStoredFrame() {
		val unpacked = DrawableLayerBinding("layer", placement = null, pageWidth = 0, pageHeight = 0)
		val frame = sourceLayerEditFrame(unpacked, 64, 32)
		assertNotNull(frame, "an unpacked binding still yields a frame")
		assertTrue(frame.isStoredFrame, "with no placement the art IS the stored frame")
		assertEquals(64, frame.displayWidth, "sized by the layer")
		assertEquals(null, frame.asUvFrame(), "and carries no normalized conversion")
	}

	/** A placement that cannot be inverted yields no frame rather than a wrong one. */
	@Test
	fun degeneratePlacementYieldsNoFrame() {
		assertEquals(null, sourceLayerEditFrame(packedBinding(scaleX = 0f), 64, 32), "a collapsed axis has no frame")
		assertEquals(null, sourceLayerEditFrame(packedBinding(), layerWidth = 0, layerHeight = 32), "a zero-width layer has no frame")
	}

	/**
	 * THE regression this session exists to prevent: a commit must leave every vertex the operation did
	 * not move bit-identical.  Converting whole arrays instead marks untouched meshes as edited, which
	 * warns the user on CMO3 export about mappings they never authored.
	 */
	@Test
	fun commitLeavesUnmovedVerticesBitIdentical() {
		val storedUvs = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
		val frames =
			listOf(
				"page" to atlasPageEditFrame(2048, 1024),
				"layer" to sourceLayerEditFrame(packedBinding(rotationDegrees = 37.5f, scaleX = 0.86f, scaleY = 0.86f), 576, 646)!!,
			)
		for ((label, frame) in frames) {
			// Display positions for every vertex, as a real gesture supplies - but only vertex 1 moved.
			val displayPositions = FloatArray(storedUvs.size) { componentIndex -> 7f * (componentIndex + 1) }
			val committed = storedUvsWithMoved(storedUvs, listOf(1), displayPositions, frame)
			assertEquals(storedUvs[0].toRawBits(), committed[0].toRawBits(), "$label: vertex 0 u is untouched")
			assertEquals(storedUvs[1].toRawBits(), committed[1].toRawBits(), "$label: vertex 0 v is untouched")
			assertEquals(storedUvs[4].toRawBits(), committed[4].toRawBits(), "$label: vertex 2 u is untouched")
			assertEquals(storedUvs[5].toRawBits(), committed[5].toRawBits(), "$label: vertex 2 v is untouched")
			val (movedU, movedV) = frame.storedUvAt(displayPositions[2], displayPositions[3])
			assertEquals(movedU, committed[2], 0f, "$label: the moved vertex takes its authored u")
			assertEquals(movedV, committed[3], 0f, "$label: the moved vertex takes its authored v")
		}
	}

	/** A moved index past either array's end is skipped rather than throwing. */
	@Test
	fun commitIgnoresOutOfRangeMovedIndices() {
		val storedUvs = floatArrayOf(0.1f, 0.2f)
		val committed = storedUvsWithMoved(storedUvs, listOf(0, 9), floatArrayOf(4f, 8f), atlasPageEditFrame(64, 32))
		assertEquals(2, committed.size, "the result matches the stored array")
		val (movedU, movedV) = atlasPageEditFrame(64, 32).storedUvAt(4f, 8f)
		assertEquals(movedU, committed[0], 0f, "the in-range vertex is written")
		assertEquals(movedV, committed[1], 0f, "the in-range vertex is written")
	}
}