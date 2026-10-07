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
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [planGlueLayout] - the glue addressing the GPU weld reads - and the two questions an edit asks of
 * it: whether a plan still fits the edited model ([glueLayoutFits]) and whether one drawable's entry moved
 * between two plans ([glueEntryChanged]).
 *
 * `GpuDeformValidationTest` excludes glue by design, and `GlueTest` / `GlueCorpusTest` exercise only the
 * CPU weld, which does not use these offsets or attributes; `GpuGlueValidationTest` covers the same ground
 * end to end through a GPU, but needs a display.  These run anywhere.
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
			mesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), IntArray(0)),
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(paramA, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size)))),
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
			worldOriginZ = 0f,
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
		val glue = Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f)))
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
		val glue = Glue(DrawableId("a"), DrawableId("b"), emptyList())
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
		val glue = Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(1, 2, 0.25f, 0.75f)))
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
		val anchor = drawable("anchor", 4).let { Drawable(it.id, it.name, null, BlendMode.Normal, emptyList(), it.mesh, it.geometryGrid) }
		val drawables = listOf(anchor, drawable("b", 2))
		val glue = Glue(DrawableId("anchor"), DrawableId("b"), listOf(GluePair(0, 0, 0f, 1f)))
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
				Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f))),
				Glue(DrawableId("b"), DrawableId("c"), listOf(GluePair(1, 1, 0.5f, 0.5f))),
			)
		val layout = planGlueLayout(model(drawables, glues))
		val attributesB = layout.attributesById.getValue(DrawableId("b"))
		assertEquals(0, attributesB.glueIndex[0], "B's vertex 0 belongs to the first glue")
		assertEquals(1, attributesB.glueIndex[1], "B's vertex 1 belongs to the second")
	}

	@Test
	fun planGlueLayoutSkipsAGlueNamingAnAbsentDrawable() {
		val drawables = listOf(drawable("a", 2))
		val glue = Glue(DrawableId("a"), DrawableId("ghost"), listOf(GluePair(0, 0, 0.5f, 0.5f)))
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
		val glues = List(MAX_GLUES + 1) { Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.5f, 0.5f))) }
		val layout = planGlueLayout(model(listOf(a, b), glues))
		// Each pair overwrites vertex 0's tag, so after the loop it holds the LAST in-bounds glue index.
		assertEquals(MAX_GLUES - 1, layout.attributesById.getValue(DrawableId("a")).glueIndex[0], "the last addressable glue tags the vertex")
		assertTrue(
			layout.attributesById.getValue(DrawableId("a")).glueIndex.all { it < MAX_GLUES },
			"no vertex is ever tagged with a glue index the shader array cannot hold",
		)
	}

	/** Planning the same model twice moves no entry, even over a new glue list holding the same glues. */
	@Test
	fun aReplanOfTheSameModelChangesNoEntry() {
		val source = pairModel(4, 4)
		val first = planGlueLayout(source)
		val second = planGlueLayout(source.copy(glues = source.glues.toList()))
		assertFalse(glueEntryChanged(first, second, DrawableId("a")), "a")
		assertFalse(glueEntryChanged(first, second, DrawableId("b")), "b")
	}

	/** A glue mesh that grows moves its own entry, and the region of every glue mesh after it. */
	@Test
	fun aGrownMeshMovesItsOwnEntryAndEveryLaterOne() {
		val before = planGlueLayout(pairModel(4, 4))
		val laterGrown = planGlueLayout(pairModel(4, 5))
		assertFalse(glueEntryChanged(before, laterGrown, DrawableId("a")), "a mesh before the grown one keeps its entry")
		assertTrue(glueEntryChanged(before, laterGrown, DrawableId("b")), "the grown mesh's entry moves")
		val earlierGrown = planGlueLayout(pairModel(5, 4))
		assertTrue(glueEntryChanged(before, earlierGrown, DrawableId("a")), "the grown mesh's entry moves")
		assertTrue(glueEntryChanged(before, earlierGrown, DrawableId("b")), "and so does the region after it")
	}

	/** A mesh leaving the glue changes its entry; a mesh that never was glued does not. */
	@Test
	fun aMeshLeavingTheGlueChangesItsEntry() {
		val source = pairModel(4, 4).let { model -> model.copy(drawables = model.drawables + drawable("loose", 3)) }
		val before = planGlueLayout(source)
		val after = planGlueLayout(source.copy(glues = emptyList()))
		assertTrue(glueEntryChanged(before, after, DrawableId("b")), "b left the glue")
		assertFalse(glueEntryChanged(before, after, DrawableId("loose")), "an unglued mesh has no entry either side")
	}

	/** Removing an earlier glue retags a later one: only that entry's glue index moves. */
	@Test
	fun aRetaggedGlueChangesOnlyTheGlueIndex() {
		val drawables = listOf(drawable("c", 4), drawable("d", 4), drawable("a", 4), drawable("b", 4))
		val earlier = Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(1, 0, 0.5f, 0.5f)))
		val later = Glue(DrawableId("c"), DrawableId("d"), listOf(GluePair(1, 0, 0.5f, 0.5f)))
		val before = planGlueLayout(model(drawables, listOf(earlier, later)))
		val after = planGlueLayout(model(drawables, listOf(later)))
		val id = DrawableId("d")
		assertTrue(glueEntryChanged(before, after, id), "d's entry moved")
		assertEquals(before.baseOffsetById[id], after.baseOffsetById[id], "its region did not")
		assertContentEquals(before.attributesById.getValue(id).partnerIndex, after.attributesById.getValue(id).partnerIndex, "nor its partners")
		assertContentEquals(before.attributesById.getValue(id).weldWeight, after.attributesById.getValue(id).weldWeight, "nor its weights")
		assertEquals(listOf(1, 0), listOf(before.attributesById.getValue(id).glueIndex[0], after.attributesById.getValue(id).glueIndex[0]), "only its glue index")
	}

	/** Edits that move no weld leave a plan fitting the model. */
	@Test
	fun glueLayoutFitsEditsThatMoveNoWeld() {
		val source = pairModel(4, 4)
		val layout = planGlueLayout(source)
		assertTrue(glueLayoutFits(layout, source), "the same model")
		assertTrue(glueLayoutFits(layout, source.copy(glues = source.glues.map { glue -> glue.copy(intensity = 0.5f) })), "an intensity edit")
		val moved = source.drawables.map { drawable -> drawable.copy(mesh = drawable.mesh!!.let { mesh -> DrawableMesh.withLocalEqualToCanvas(FloatArray(mesh.positions.size) { 1f }, mesh.uvs, mesh.indices) }) }
		assertTrue(glueLayoutFits(layout, source.copy(drawables = moved)), "meshes moved at the same size")
		assertTrue(glueLayoutFits(layout, source.copy(drawables = source.drawables + drawable("loose", 3))), "an unglued drawable added")
	}

	/** Edits that move a weld leave a plan that no longer fits. */
	@Test
	fun glueLayoutFitsNoEditThatMovesAWeld() {
		val source = pairModel(4, 4)
		val layout = planGlueLayout(source)
		val glue = source.glues.single()
		assertFalse(glueLayoutFits(layout, source.copy(glues = listOf(glue.copy(pairs = glue.pairs.toList())))), "a re-paired glue")
		assertFalse(glueLayoutFits(layout, source.copy(glues = emptyList())), "a removed glue")
		val second = Glue(DrawableId("b"), DrawableId("a"), listOf(GluePair(2, 3, 0.5f, 0.5f)))
		val twoGlues = source.copy(glues = listOf(glue, second))
		assertFalse(glueLayoutFits(planGlueLayout(twoGlues), twoGlues.copy(glues = listOf(second, glue))), "reordered glues")
		assertFalse(glueLayoutFits(layout, pairModel(4, 5).copy(glues = source.glues)), "a glue mesh that grew, over the same glues")
	}

	/**
	 * Two glue meshes a and b of the given sizes, glued by one pair.
	 *
	 * @param Int aVertexCount The vertex count of a.
	 * @param Int bVertexCount The vertex count of b.
	 * @return PuppetModel The model.
	 */
	private fun pairModel(aVertexCount: Int, bVertexCount: Int): PuppetModel =
		model(
			listOf(drawable("a", aVertexCount), drawable("b", bVertexCount)),
			listOf(Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(1, 0, 0.5f, 0.5f)))),
		)
}