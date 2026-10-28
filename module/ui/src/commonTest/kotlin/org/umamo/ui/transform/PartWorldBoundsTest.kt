package org.umamo.ui.transform

import org.umamo.edit.transform.MeshBounds
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A part's world bounds: the box around every drawable it holds, nested parts included, as the viewport draws
 * them, and none when nothing it holds is on screen.
 */
class PartWorldBoundsTest {
	private val tolerance = 1e-4f

	/**
	 * A rectangle drawable on the canvas, optionally hung from a deformer.
	 *
	 * @param String id The drawable's id.
	 * @param Float left The left edge on the canvas.
	 * @param Float top The top edge on the canvas.
	 * @param Float right The right edge on the canvas.
	 * @param Float bottom The bottom edge on the canvas.
	 * @param String? parentDeformer The parent deformer's id, or null for a direct drawable.
	 * @return Drawable The drawable.
	 */
	private fun rectangle(id: String, left: Float, top: Float, right: Float, bottom: Float, parentDeformer: String? = null): Drawable {
		val corners = floatArrayOf(left, top, right, top, right, bottom, left, bottom)

		return Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = parentDeformer?.let(::DeformerId),
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(corners, FloatArray(corners.size), intArrayOf(0, 1, 2, 0, 2, 3)),
			geometryGrid = null,
		)
	}

	/**
	 * The rig: part outer holds drawable a and part inner, which holds drawable b; part empty holds nothing;
	 * part stranded holds only drawable c, hung from a deformer the model does not carry.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts =
				listOf(
					Part(PartId("outer"), "outer", children = listOf(OrgChild.Drawable(DrawableId("a")), OrgChild.Part(PartId("inner")))),
					Part(PartId("inner"), "inner", children = listOf(OrgChild.Drawable(DrawableId("b")))),
					Part(PartId("empty"), "empty", children = emptyList()),
					Part(PartId("stranded"), "stranded", children = listOf(OrgChild.Drawable(DrawableId("c")))),
				),
			deformers = emptyList(),
			drawables =
				listOf(
					rectangle("a", 0f, 0f, 10f, 10f),
					rectangle("b", 100f, 50f, 120f, 60f),
					rectangle("c", 0f, 0f, 10f, 10f, parentDeformer = "absent"),
				),
			rootChildren = listOf(OrgChild.Part(PartId("outer")), OrgChild.Part(PartId("empty")), OrgChild.Part(PartId("stranded"))),
			rootPartId = null,
		)

	/**
	 * Asserts [bounds] has the given center and extents.
	 *
	 * @param Float centerX The expected center x.
	 * @param Float centerZ The expected center z (up).
	 * @param Float width The expected x extent.
	 * @param Float height The expected z extent.
	 * @param MeshBounds? bounds The bounds to check.
	 */
	private fun assertBounds(centerX: Float, centerZ: Float, width: Float, height: Float, bounds: MeshBounds?) {
		val found = assertNotNull(bounds)
		assertEquals(centerX, found.centerX, tolerance)
		assertEquals(centerZ, found.centerY, tolerance)
		assertEquals(width, found.width, tolerance)
		assertEquals(height, found.height, tolerance)
	}

	/** A part spans its own drawable and its nested part's, in world space with z up. */
	@Test
	fun aPartSpansEverythingItHolds() {
		assertBounds(60f, -30f, 120f, 60f, partWorldBounds(model(), emptyMap(), PartId("outer")))
	}

	/** A nested part spans only what it holds itself. */
	@Test
	fun aNestedPartSpansOnlyItsOwn() {
		assertBounds(110f, -55f, 20f, 10f, partWorldBounds(model(), emptyMap(), PartId("inner")))
	}

	/** An empty part, a part with nothing on screen, and a part the model does not hold have no bounds. */
	@Test
	fun nothingOnScreenHasNoBounds() {
		assertNull(partWorldBounds(model(), emptyMap(), PartId("empty")))
		assertNull(partWorldBounds(model(), emptyMap(), PartId("stranded")))
		assertNull(partWorldBounds(model(), emptyMap(), PartId("absent")))
	}
}