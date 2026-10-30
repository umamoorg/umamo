package org.umamo.ui.transform

import org.umamo.edit.ElementTopology
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The world box Frame Selected fits and a part's Transform rows measure: the union of whole captured meshes,
 * or of the covered vertices alone, in world space with z up.
 */
class WorldExtentTest {
	private val tolerance = 1e-4f
	private val left = DrawableId("left")
	private val right = DrawableId("right")

	/**
	 * A direct rectangle drawable on the canvas (y down, so world z is its negation).
	 *
	 * @param DrawableId id The drawable's id.
	 * @param Float minX The left edge on the canvas.
	 * @param Float minY The top edge on the canvas.
	 * @param Float maxX The right edge on the canvas.
	 * @param Float maxY The bottom edge on the canvas.
	 * @return Drawable The drawable.
	 */
	private fun rectangle(id: DrawableId, minX: Float, minY: Float, maxX: Float, maxY: Float): Drawable {
		val corners = floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY)

		return Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(corners, FloatArray(corners.size), intArrayOf(0, 1, 2, 0, 2, 3)),
			geometryGrid = null,
		)
	}

	/**
	 * Two rectangles side by side: left spans x 0..10 and canvas y 0..4, right spans x 20..30 and canvas y 2..8.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(rectangle(left, 0f, 0f, 10f, 4f), rectangle(right, 20f, 2f, 30f, 8f)),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** Whole meshes join into one box, and their center and size are the Transform rows' Position and Size. */
	@Test
	fun wholeMeshesJoinIntoOneBox() {
		val extent = assertNotNull(worldExtentOf(captureDrawableWorlds(model(), emptyMap(), listOf(left, right))))
		assertEquals(0f, extent.minX, tolerance)
		assertEquals(30f, extent.maxX, tolerance)
		assertEquals(-8f, extent.minZ, tolerance)
		assertEquals(0f, extent.maxZ, tolerance)
		val bounds = extent.toMeshBounds()
		assertEquals(15f, bounds.centerX, tolerance)
		assertEquals(-4f, bounds.centerY, tolerance)
		assertEquals(30f, bounds.width, tolerance)
		assertEquals(8f, bounds.height, tolerance)
	}

	/** Only the covered vertices count, so one corner of each mesh boxes just those two corners. */
	@Test
	fun onlyCoveredVerticesCount() {
		val geometries = captureDrawableWorlds(model(), emptyMap(), listOf(left, right))
		val covered = geometries.map { geometry -> CoveredVertices(geometry, ElementTopology.ofTriangles(4, intArrayOf(0, 1, 2, 0, 2, 3)), setOf(0)) }
		val extent = assertNotNull(coveredWorldExtent(covered))
		assertEquals(0f, extent.minX, tolerance)
		assertEquals(20f, extent.maxX, tolerance)
		assertEquals(-2f, extent.minZ, tolerance)
		assertEquals(0f, extent.maxZ, tolerance)
	}

	/** Nothing to measure gives no box rather than an inverted one. */
	@Test
	fun nothingToMeasureGivesNoBox() {
		assertNull(worldExtentOf(emptyList()))
		assertNull(coveredWorldExtent(emptyList()))
	}
}