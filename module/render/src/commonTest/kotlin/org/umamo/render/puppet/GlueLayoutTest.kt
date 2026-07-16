package org.umamo.render.puppet

import org.umamo.render.glsl.MAX_GLUES
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [planGlueLayout] - the glue addressing the GPU weld reads.
 *
 * This logic had NO coverage of any kind before it was extracted: `GpuDeformValidationTest` excludes glue
 * by design, and `GlueTest` / `GlueCorpusTest` only exercise the CPU weld, which does not use these
 * offsets or attributes at all. `GpuGlueValidationTest` now covers the same ground end-to-end through a
 * GPU, but needs a display; these run anywhere.
 */
class GlueLayoutTest {
	private val paramA = ParameterId("A")

	/** A drawable with [vertexCount] vertices; geometry values are irrelevant to addressing. */
	private fun drawable(id: String, vertexCount: Int): Drawable {
		val positions = FloatArray(vertexCount * 2)
		return Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), IntArray(0)),
			keyforms =
				KeyformGrid(
					listOf(KeyformAxis(paramA, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size)))),
				),
		)
	}

	private fun model(drawables: List<Drawable>, glues: List<Glue>): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { OrgChild.Drawable(it.id) },
			rootPartId = null,
			glues = glues,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		)

	@Test
	fun planGlueLayoutIsEmptyWithoutGlue() {
		val layout = planGlueLayout(model(listOf(drawable("a", 3)), emptyList()))
		assertTrue(layout.glueMeshIds.isEmpty())
		assertEquals(0, layout.globalVertexCount, "no glue means no shared store")
		assertTrue(layout.attributesById.isEmpty())
	}

	@Test
	fun planGlueLayoutAssignsOffsetsInDrawableOrderSkippingUngluedMeshes() {
		// "middle" is not glued, so it takes no region: offsets follow model.drawables order over the
		// GLUED meshes only. Both the pass-1 write and the pass-2 partner read depend on this exactly.
		val drawables = listOf(drawable("a", 4), drawable("middle", 9), drawable("b", 2))
		val glue = Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f)), null)
		val layout = planGlueLayout(model(drawables, listOf(glue)))
		assertEquals(setOf(DrawableId("a"), DrawableId("b")), layout.glueMeshIds)
		assertEquals(0, layout.baseOffsetById[DrawableId("a")], "the first glued mesh starts the store")
		assertEquals(4, layout.baseOffsetById[DrawableId("b")], "the next glued mesh follows a's 4 vertices")
		assertEquals(null, layout.baseOffsetById[DrawableId("middle")], "an unglued mesh gets no region")
		assertEquals(6, layout.globalVertexCount, "4 + 2; the unglued mesh's 9 vertices are not in the store")
	}

	@Test
	fun planGlueLayoutDefaultsEveryVertexToAnUngluedSelfWeld() {
		val drawables = listOf(drawable("a", 3), drawable("b", 3))
		val glue = Glue(DrawableId("a"), DrawableId("b"), emptyList(), null)
		val layout = planGlueLayout(model(drawables, listOf(glue)))
		val attributesB = layout.attributesById.getValue(DrawableId("b"))
		// Self-pointing by GLOBAL index (b starts at 3), so the weld is arithmetically a no-op.
		assertContentEquals(intArrayOf(3, 4, 5), attributesB.partnerIndex, "an unglued vertex points at itself")
		assertContentEquals(intArrayOf(-1, -1, -1), attributesB.glueIndex, "-1 means no glue")
		assertContentEquals(floatArrayOf(0f, 0f, 0f), attributesB.weldWeight, "zero weight is a no-op weld")
	}

	@Test
	fun planGlueLayoutPointsEachPairAtItsPartnerByGlobalIndex() {
		val drawables = listOf(drawable("a", 4), drawable("b", 4))
		val glue = Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(1, 2, 0.25f, 0.75f)), null)
		val layout = planGlueLayout(model(drawables, listOf(glue)))
		val attributesA = layout.attributesById.getValue(DrawableId("a"))
		val attributesB = layout.attributesById.getValue(DrawableId("b"))
		// a's vertex 1 -> b's vertex 2, which is GLOBAL index 4 + 2 = 6.
		assertEquals(6, attributesA.partnerIndex[1], "A's seam vertex points at B's, by global index")
		assertEquals(0, attributesA.glueIndex[1])
		assertEquals(0.25f, attributesA.weldWeight[1], "A carries weightA")
		// b's vertex 2 -> a's vertex 1, global index 0 + 1 = 1.
		assertEquals(1, attributesB.partnerIndex[2], "B's seam vertex points back at A's")
		assertEquals(0, attributesB.glueIndex[2])
		assertEquals(0.75f, attributesB.weldWeight[2], "B carries weightB")
		// Untouched vertices stay no-op welds.
		assertEquals(-1, attributesA.glueIndex[0])
		assertEquals(-1, attributesB.glueIndex[3])
	}

	@Test
	fun planGlueLayoutGivesAnIndexLessAnchorItsOwnRegion() {
		// A zero-triangle anchor draws nothing but is a weld partner, so it MUST still get a region: pass 1
		// deforms it, and pass 2 reads its positions. Dropping it would weld against uninitialised memory.
		val anchor = drawable("anchor", 4).let { Drawable(it.id, it.name, null, BlendMode.Normal, emptyList(), it.mesh, it.keyforms) }
		val drawables = listOf(anchor, drawable("b", 2))
		val glue = Glue(DrawableId("anchor"), DrawableId("b"), listOf(GluePair(0, 0, 0f, 1f)), null)
		val layout = planGlueLayout(model(drawables, listOf(glue)))
		assertEquals(0, layout.baseOffsetById[DrawableId("anchor")])
		assertEquals(4, layout.baseOffsetById[DrawableId("b")], "the anchor's 4 vertices still occupy the store")
		assertEquals(6, layout.globalVertexCount)
		assertEquals(4, layout.attributesById.getValue(DrawableId("anchor")).partnerIndex.size)
	}

	@Test
	fun planGlueLayoutTagsEachGlueWithItsOwnIndex() {
		// The glue index selects the per-pose intensity uniform, so a second glue must not reuse the first's.
		val drawables = listOf(drawable("a", 2), drawable("b", 2), drawable("c", 2))
		val glues =
			listOf(
				Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f)), null),
				Glue(DrawableId("b"), DrawableId("c"), listOf(GluePair(1, 1, 0.5f, 0.5f)), null),
			)
		val layout = planGlueLayout(model(drawables, glues))
		val attributesB = layout.attributesById.getValue(DrawableId("b"))
		assertEquals(0, attributesB.glueIndex[0], "B's vertex 0 belongs to the first glue")
		assertEquals(1, attributesB.glueIndex[1], "B's vertex 1 belongs to the second")
	}

	@Test
	fun planGlueLayoutSkipsAGlueNamingAnAbsentDrawable() {
		val drawables = listOf(drawable("a", 2))
		val glue = Glue(DrawableId("a"), DrawableId("ghost"), listOf(GluePair(0, 0, 0.5f, 0.5f)), null)
		val layout = planGlueLayout(model(drawables, listOf(glue)))
		// "ghost" is named by the glue but carried by no drawable, so it gets no region and no attributes,
		// and the pair is dropped rather than welding against a region that does not exist.
		assertEquals(null, layout.baseOffsetById[DrawableId("ghost")])
		assertEquals(2, layout.globalVertexCount, "only the real mesh occupies the store")
		assertEquals(-1, layout.attributesById.getValue(DrawableId("a")).glueIndex[0], "the half-resolved pair is not applied")
	}

	@Test
	fun planGlueLayoutLeavesGlueBeyondTheShaderArrayUnwelded() {
		// The shader's glueIntensity[] uniform has exactly MAX_GLUES slots; a vertex tagged with glue index
		// >= MAX_GLUES would read it out of bounds. Those pairs must stay -1 (unwelded), which is what makes
		// resolvePose's "renders unwelded" promise true rather than an out-of-bounds read.
		val a = drawable("a", 1)
		val b = drawable("b", 1)
		// MAX_GLUES + 1 glues, all on the same pair. The first MAX_GLUES tag vertex 0; the last must not.
		val glues = List(MAX_GLUES + 1) { Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f)), null) }
		val layout = planGlueLayout(model(listOf(a, b), glues))
		// Each pair overwrites vertex 0's tag, so after the loop it holds the LAST in-bounds glue index.
		assertEquals(MAX_GLUES - 1, layout.attributesById.getValue(DrawableId("a")).glueIndex[0], "the last addressable glue tags the vertex")
		assertTrue(
			layout.attributesById.getValue(DrawableId("a")).glueIndex.all { it < MAX_GLUES },
			"no vertex is ever tagged with a glue index the shader array cannot hold",
		)
	}
}
