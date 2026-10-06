package org.umamo.ui.workspace.spaces.uv

import org.umamo.edit.withMeshUvs
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.uv.UV_RIG_OTHER
import org.umamo.ui.viewport.uv.UV_RIG_QUAD
import org.umamo.ui.viewport.uv.UV_RIG_QUAD_TILE
import org.umamo.ui.viewport.uv.UV_RIG_TILE_SIDE
import org.umamo.ui.viewport.uv.uvRigModel
import org.umamo.ui.viewport.uv.uvRigPlacedModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins what a UV area's geometry cache keeps from one derive to the next over the UV rig: everything, for a
 * model whose meshes kept their arrays; the moved mesh's positions alone, for a drive that previewed new
 * coordinates for one mesh; the edges whenever the indices stayed; new positions everywhere for a surface
 * that changed size; the layer view's recovered coordinates by the stored array they came from; and what it
 * builds is exactly what the one-shot build makes.
 */
class UvGizmoGeometryCacheTest {
	private val pageSide = 256

	@Test
	fun anUnchangedModelKeepsEveryGeometry() {
		val cache = UvGizmoGeometryCache()
		val model = uvRigModel()
		val first = pageGeometries(cache, model)

		val again = pageGeometries(cache, model)

		assertEquals(2, again.size)
		for (geometryIndex in again.indices) {
			assertSame(first[geometryIndex], again[geometryIndex], "${again[geometryIndex].drawableId} is kept whole")
		}
	}

	@Test
	fun aMovedMeshRemapsAloneAndKeepsItsEdges() {
		val cache = UvGizmoGeometryCache()
		val model = uvRigModel()
		val before = pageGeometries(cache, model)
		val quadUvs = shownOf(model).first { drawable -> drawable.id == UV_RIG_QUAD }.mesh!!.uvs
		val movedUvs = FloatArray(quadUvs.size) { componentIndex -> quadUvs[componentIndex] + 1f / pageSide }
		val moved = model.withMeshUvs(UV_RIG_QUAD, movedUvs)

		val after = pageGeometries(cache, moved)

		val quadBefore = before.first { geometry -> geometry.drawableId == UV_RIG_QUAD }
		val quadAfter = after.first { geometry -> geometry.drawableId == UV_RIG_QUAD }
		assertNotSame(quadBefore.positions, quadAfter.positions, "the moved mesh is mapped again")
		assertContentEquals(FloatArray(quadBefore.positions.size) { componentIndex -> quadBefore.positions[componentIndex] + if (componentIndex % 2 == 0) 1f else -1f }, quadAfter.positions, "to where it moved")
		assertSame(quadBefore.edges, quadAfter.edges, "over the edges it had")
		assertSame(before.first { geometry -> geometry.drawableId == UV_RIG_OTHER }, after.first { geometry -> geometry.drawableId == UV_RIG_OTHER }, "the other mesh is kept whole")
	}

	@Test
	fun aResizedSurfaceRemapsEveryMesh() {
		val cache = UvGizmoGeometryCache()
		val model = uvRigModel()
		val shown = shownOf(model)
		val uvsById = cache.surfaceUvs(shown, model, null)
		val atPageSize = cache.geometries(shown, uvsById, pageSide, pageSide)

		val atDouble = cache.geometries(shown, uvsById, pageSide * 2, pageSide * 2)

		for (geometryIndex in atDouble.indices) {
			assertNotSame(atPageSize[geometryIndex].positions, atDouble[geometryIndex].positions, "${atDouble[geometryIndex].drawableId} maps again at the new size")
			assertSame(atPageSize[geometryIndex].edges, atDouble[geometryIndex].edges, "and keeps its edges")
		}
	}

	@Test
	fun theLayerRecoveryIsKeptByItsStoredArray() {
		val cache = UvGizmoGeometryCache()
		val model = uvRigPlacedModel()
		val layerView = UvEditorLayer(UV_RIG_QUAD_TILE.raw, UV_RIG_TILE_SIDE, UV_RIG_TILE_SIDE)
		val quadOnly = shownOf(model).filter { drawable -> drawable.id == UV_RIG_QUAD }
		val first = cache.surfaceUvs(quadOnly, model, layerView).getValue(UV_RIG_QUAD)

		assertSame(first, cache.surfaceUvs(quadOnly, model, layerView).getValue(UV_RIG_QUAD), "the same stored array recovers to the same array")
		val storedUvs = quadOnly.single().mesh!!.uvs
		val moved = model.withMeshUvs(UV_RIG_QUAD, FloatArray(storedUvs.size) { componentIndex -> storedUvs[componentIndex] + 1f / pageSide })
		assertNotSame(first, cache.surfaceUvs(shownOf(moved).filter { drawable -> drawable.id == UV_RIG_QUAD }, moved, layerView).getValue(UV_RIG_QUAD), "a new one recovers again")
	}

	@Test
	fun itBuildsWhatTheOneShotBuildMakes() {
		val model = uvRigModel()
		val shown = shownOf(model)
		val cached = pageGeometries(UvGizmoGeometryCache(), model)
		val oneShot = uvGizmoGeometries(shown, shownSurfaceUvs(shown, model, null), pageSide, pageSide)

		assertEquals(oneShot.map { geometry -> geometry.drawableId }, cached.map { geometry -> geometry.drawableId })
		for (geometryIndex in oneShot.indices) {
			assertContentEquals(oneShot[geometryIndex].positions, cached[geometryIndex].positions)
			assertEquals(oneShot[geometryIndex].edges, cached[geometryIndex].edges)
			assertSame(oneShot[geometryIndex].indices, cached[geometryIndex].indices)
		}
	}

	@Test
	fun aMeshNoLongerShownIsDropped() {
		val cache = UvGizmoGeometryCache()
		val model = uvRigModel()
		val shown = shownOf(model)
		val both = cache.geometries(shown, cache.surfaceUvs(shown, model, null), pageSide, pageSide)
		val quadOnly = shown.filter { drawable -> drawable.id == UV_RIG_QUAD }
		cache.geometries(quadOnly, cache.surfaceUvs(quadOnly, model, null), pageSide, pageSide)

		val shownAgain = cache.geometries(shown, cache.surfaceUvs(shown, model, null), pageSide, pageSide)

		assertSame(both[0], shownAgain[0], "the quad stayed shown, so it is kept")
		assertNotSame(both[1], shownAgain[1], "the triangle was let go, so it is built afresh")
	}

	/**
	 * The rig's meshed drawables, in model order.
	 *
	 * @param PuppetModel model The model.
	 * @return List<Drawable> The drawables.
	 */
	private fun shownOf(model: PuppetModel): List<Drawable> = model.drawables.filter { drawable -> drawable.mesh != null }

	/**
	 * The page view's geometry through [cache].
	 *
	 * @param UvGizmoGeometryCache cache The cache.
	 * @param PuppetModel model The model.
	 * @return List<GizmoMeshGeometry> The geometry.
	 */
	private fun pageGeometries(cache: UvGizmoGeometryCache, model: PuppetModel): List<GizmoMeshGeometry> {
		val shown = shownOf(model)
		return cache.geometries(shown, cache.surfaceUvs(shown, model, null), pageSide, pageSide)
	}
}