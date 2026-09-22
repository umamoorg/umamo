package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit-tests the rigger's coordinate frame: positions read from the world axes, and the Origin fields read
 * from the canvas's bottom-left corner with Z up.  Corpus-free - hand-built models with a canvas and an origin
 * exercise the default center origin, both round trips off center, the canvas-less model, and a canvas height
 * change.
 */
class OriginRelativeCoordinatesTest {
	/**
	 * A model with no content, a [width] x [height] canvas, and its world origin at ([originX], [originZ]).
	 *
	 * @param Float width The canvas width.
	 * @param Float height The canvas height.
	 * @param Float originX The world-origin x.
	 * @param Float originZ The world-origin z (up; world space, negated canvas y).
	 * @return PuppetModel The model.
	 */
	private fun model(width: Float, height: Float, originX: Float, originZ: Float): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = width,
			canvasHeight = height,
			worldOriginX = originX,
			worldOriginZ = originZ,
		)

	/** The origin every importer derives: the center of a 1200 x 1200 canvas, stored as (600, -600). */
	private val centered = model(1200f, 1200f, 600f, -600f)

	@Test
	fun theDefaultOriginReadsFromTheCanvasBottomLeft() {
		assertEquals(600f, centered.originFromCanvasLeft())
		assertEquals(600f, centered.originFromCanvasBottom(), "Z counts up from the bottom edge, so the center reads positive")
	}

	@Test
	fun positionsReadFromTheWorldAxes() {
		// The world point the axes cross at reads (0, 0).
		assertEquals(0f, centered.originRelativeX(600f))
		assertEquals(0f, centered.originRelativeZ(-600f))
		// The canvas's top-left corner (world zero) sits left of and above the axes.
		assertEquals(-600f, centered.originRelativeX(0f))
		assertEquals(600f, centered.originRelativeZ(0f))
	}

	@Test
	fun positionsRoundTripOffCenter() {
		val offCenter = model(1000f, 800f, 250f, -600f)

		assertEquals(40f, offCenter.originRelativeX(offCenter.worldXFromOriginRelative(40f)))
		assertEquals(-75f, offCenter.originRelativeZ(offCenter.worldZFromOriginRelative(-75f)))
		assertEquals(310f, offCenter.worldXFromOriginRelative(offCenter.originRelativeX(310f)))
		assertEquals(-120f, offCenter.worldZFromOriginRelative(offCenter.originRelativeZ(-120f)))
	}

	@Test
	fun theOriginRoundTripsThroughTheCanvasReading() {
		val offCenter = model(1000f, 800f, 250f, -600f)

		// Stored 600 below the top of an 800-tall canvas, so 200 above its bottom.
		assertEquals(250f, offCenter.originFromCanvasLeft())
		assertEquals(200f, offCenter.originFromCanvasBottom())
		assertEquals(250f, offCenter.worldOriginXFromCanvasLeft(offCenter.originFromCanvasLeft()))
		assertEquals(-600f, offCenter.worldOriginZFromCanvasBottom(offCenter.originFromCanvasBottom()))
		assertEquals(-500f, offCenter.worldOriginZFromCanvasBottom(300f), "typing 300 puts the origin 300 above the bottom edge")
	}

	@Test
	fun aCanvaslessModelReadsWorldSpace() {
		val canvasless = model(0f, 0f, 0f, 0f)

		assertEquals(35f, canvasless.originRelativeX(35f))
		assertEquals(-12f, canvasless.originRelativeZ(-12f))
		assertEquals(0f, canvasless.originFromCanvasBottom(), "with no canvas the bottom reading is the raw world y")
	}

	@Test
	fun aCanvasHeightChangeMovesOnlyTheOriginZReading() {
		// The canvas grows at its bottom edge and the origin keeps its world point.
		val taller = centered.copy(canvasHeight = 1300f)

		assertEquals(700f, taller.originFromCanvasBottom(), "the bottom edge moved 100 further from the origin")
		assertEquals(600f, taller.originFromCanvasLeft())
		assertEquals(centered.originRelativeX(420f), taller.originRelativeX(420f), "positions still read from the same axes")
		assertEquals(centered.originRelativeZ(-300f), taller.originRelativeZ(-300f))
	}
}