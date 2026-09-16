package org.umamo.ui.workspace.spaces

import org.umamo.edit.withMeshUvs
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins the incremental island derivation: an unchanged island hands back the same mapping and
 * geometry instances frame after frame, a uv-only change rebuilds that island alone and keeps its
 * edges, a topology change rebuilds the edges, a surface or layer change rebuilds everything, and an
 * island that leaves the shown set is forgotten.  The derivation itself is pinned in
 * UvEditorViewStateTest; this is only about when it reruns.
 */
class UvIslandCacheTest {
	private val tileId = AtlasTileId("tile")
	private val triangleUvs = floatArrayOf(0.25f, 0.5f, 0.75f, 0.5f, 0.25f, 0.25f)

	private fun meshedDrawable(rawId: String, uvs: FloatArray = triangleUvs.copyOf(), indices: IntArray = intArrayOf(0, 1, 2)): Drawable =
		Drawable(
			id = DrawableId(rawId),
			name = rawId,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), uvs, indices),
			geometryGrid = null,
			atlasTileId = tileId,
		)

	private fun modelOf(vararg drawables: Drawable, placement: AtlasPlacement? = AtlasPlacement(0, 4f, 4f, 1f, 1f, 0f)): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables.toList(),
			rootChildren = emptyList(),
			rootPartId = null,
			atlas = PuppetAtlas(pages = listOf(AtlasPage(64, 32)), tiles = listOf(AtlasTile(tileId, "tile", 16, 16, placement))),
		)

	private val layer = UvEditorLayer("tile", 16, 16)

	@Test
	fun anUnchangedIslandKeepsItsInstancesAcrossFrames() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val cache = UvIslandCache()
		val first = cache.update(model.drawables, model, null, 64, 32)
		val second = cache.update(model.drawables, model, null, 64, 32)
		assertEquals(2, first.geometries.size)
		for (index in 0 until 2) {
			assertSame(first.geometries[index], second.geometries[index], "island $index is the same geometry instance")
		}
		assertSame(first.uvsById.getValue(DrawableId("a")), second.uvsById.getValue(DrawableId("a")), "the page mapping is the stored array itself")
	}

	@Test
	fun aUvChangeRebuildsThatIslandAloneAndKeepsItsEdges() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val cache = UvIslandCache()
		val before = cache.update(model.drawables, model, null, 64, 32)
		val movedUvs = floatArrayOf(0.5f, 0.5f, 1f, 0.5f, 0.5f, 0.25f)
		val preview = model.withMeshUvs(DrawableId("a"), movedUvs)
		val after = cache.update(preview.drawables, preview, null, 64, 32)
		assertNotSame(before.geometries[0], after.geometries[0], "the moved island is rebuilt")
		assertSame(before.geometries[0].edges, after.geometries[0].edges, "with its edges reused - the indices did not change")
		assertContentEquals(floatArrayOf(32f, 16f, 64f, 16f, 32f, 24f), after.geometries[0].positions, "and its positions re-projected")
		assertSame(before.geometries[1], after.geometries[1], "the untouched island is the same instance")
		assertSame(movedUvs, after.uvsById.getValue(DrawableId("a")), "the page mapping is the preview's array")
	}

	@Test
	fun aTopologyChangeRebuildsTheEdges() {
		val model = modelOf(meshedDrawable("a"))
		val cache = UvIslandCache()
		val before = cache.update(model.drawables, model, null, 64, 32)
		val remeshed = modelOf(meshedDrawable("a", indices = intArrayOf(0, 1, 2)))
		val after = cache.update(remeshed.drawables, remeshed, null, 64, 32)
		assertNotSame(before.geometries[0].edges, after.geometries[0].edges, "a new index array derives new edges")
	}

	@Test
	fun aLayerViewReusesTheRecoveredMappingUntilItsInputsChange() {
		val model = modelOf(meshedDrawable("a"))
		val cache = UvIslandCache()
		val first = cache.update(model.drawables, model, layer, 16, 16)
		val second = cache.update(model.drawables, model, layer, 16, 16)
		assertSame(first.uvsById.getValue(DrawableId("a")), second.uvsById.getValue(DrawableId("a")), "the recovered mapping is reused")
		assertSame(first.geometries[0], second.geometries[0])
		// The placement moved (a committed placement edit): the recovery must rerun.
		val replaced = modelOf(meshedDrawable("a", uvs = model.drawables[0].mesh!!.uvs), placement = AtlasPlacement(0, 8f, 8f, 1f, 1f, 0f))
		val third = cache.update(replaced.drawables, replaced, layer, 16, 16)
		assertNotSame(second.geometries[0], third.geometries[0], "a changed binding rebuilds the island")
		// A different surface size re-projects.
		val fourth = cache.update(replaced.drawables, replaced, layer, 32, 32)
		assertNotSame(third.geometries[0], fourth.geometries[0], "a changed surface size rebuilds the island")
	}

	@Test
	fun anIslandLeavingTheShownSetIsForgotten() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val cache = UvIslandCache()
		val both = cache.update(model.drawables, model, null, 64, 32)
		val onlyB = cache.update(listOf(model.drawables[1]), model, null, 64, 32)
		assertEquals(listOf(DrawableId("b")), onlyB.geometries.map { geometry -> geometry.drawableId })
		assertSame(both.geometries[1], onlyB.geometries[0], "the island that stayed is the same instance")
		val bothAgain = cache.update(model.drawables, model, null, 64, 32)
		assertNotSame(both.geometries[0], bothAgain.geometries[0], "the island that returned was derived afresh")
		assertEquals(2, bothAgain.geometries.size)
	}
}