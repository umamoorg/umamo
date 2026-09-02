package org.umamo.edit

import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.layerUvAffineOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the multi-tile placement edit the gizmo commits through: every drawable over each moved tile
 * re-maps in one pass (drawables sharing a tile move together), untouched drawables pass their arrays
 * through by reference, one fault anywhere in the map refuses the whole edit, the session commit is
 * ONE undo step, and the Object-mode UV latch admits exactly the selections that have packed art to
 * move.
 */
class AtlasPlacementEditsTest {
	private val tileAId = AtlasTileId("tileA")
	private val tileBId = AtlasTileId("tileB")
	private val tileCId = AtlasTileId("tileC")
	private val unpackedTileId = AtlasTileId("tileUnpacked")
	private val page = AtlasPage(128, 128)

	private fun placement(x: Float, y: Float, pageIndex: Int = 0): AtlasPlacement =
		AtlasPlacement(pageIndex, x, y, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f)

	private fun meshedDrawable(id: String, tileId: AtlasTileId?, uvs: FloatArray): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 1f), uvs, intArrayOf(0, 0, 0)),
			geometryGrid = null,
			atlasTileId = tileId,
		)

	private fun storedUvsAt(artUvs: FloatArray, tileId: AtlasTileId, placed: AtlasPlacement?): FloatArray {
		val binding = DrawableLayerBinding(tileId.raw, placed, page.width, page.height)
		val storedToArt = assertNotNull(layerUvAffineOf(binding, 16, 16))
		return applyUvAffine(artUvs, assertNotNull(invertUvAffine(storedToArt)))
	}

	private fun assertUvsClose(expected: FloatArray, actual: FloatArray, message: String) {
		assertEquals(expected.size, actual.size, message)
		for (componentIndex in expected.indices) {
			assertTrue(
				abs(expected[componentIndex] - actual[componentIndex]) < 1e-5f,
				"$message: component $componentIndex expected ${expected[componentIndex]}, was ${actual[componentIndex]}",
			)
		}
	}

	private val placedA = placement(4f, 4f)
	private val placedB = placement(40f, 40f)
	private val placedC = placement(80f, 80f)
	private val artUvsA1 = floatArrayOf(0.25f, 0.5f, 0.75f, 0.125f)
	private val artUvsA2 = floatArrayOf(0f, 1f, 1f, 0f)
	private val artUvsB = floatArrayOf(0f, 0f, 1f, 1f)
	private val artUvsC = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)

	private fun baseModel(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = listOf(Deformer.Warp(DeformerId("w"), "W", parent = null, partId = null, rows = 2, columns = 2, isQuadTransform = true, geometryGrid = null)),
			drawables =
				listOf(
					meshedDrawable("dA1", tileAId, storedUvsAt(artUvsA1, tileAId, placedA)),
					meshedDrawable("dA2", tileAId, storedUvsAt(artUvsA2, tileAId, placedA)),
					meshedDrawable("dB", tileBId, storedUvsAt(artUvsB, tileBId, placedB)),
					meshedDrawable("dC", tileCId, storedUvsAt(artUvsC, tileCId, placedC)),
					meshedDrawable("dUnpacked", unpackedTileId, artUvsB.copyOf()),
					meshedDrawable("dLoose", null, artUvsB.copyOf()),
				),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 200f,
			worldOriginX = 50f,
			worldOriginY = 100f,
			atlas =
				PuppetAtlas(
					pages = listOf(page),
					tiles =
						listOf(
							AtlasTile(tileAId, "A", 16, 16, placedA),
							AtlasTile(tileBId, "B", 16, 16, placedB),
							AtlasTile(tileCId, "C", 16, 16, placedC),
							AtlasTile(unpackedTileId, "U", 16, 16, null),
						),
				),
		)

	private fun PuppetModel.uvsOf(id: String): FloatArray = drawables.first { drawable -> drawable.id.raw == id }.mesh!!.uvs

	private fun PuppetModel.placementOf(tileId: AtlasTileId): AtlasPlacement? = atlas.tileById.getValue(tileId).placement

	private fun target(id: String): SelectionTarget = SelectionTarget.Drawable(DrawableId(id))

	@Test
	fun aMultiTileMoveRemapsEveryDrawableOverEachMovedTileInOnePass() {
		val base = baseModel()
		val movedA = placement(20f, 8f)
		val movedB = placement(60f, 44f)

		val moved = base.withAtlasPlacements(mapOf(tileAId to movedA, tileBId to movedB))

		assertEquals(movedA, moved.placementOf(tileAId), "tile A moved")
		assertEquals(movedB, moved.placementOf(tileBId), "tile B moved")
		assertEquals(placedC, moved.placementOf(tileCId), "tile C stayed")
		assertUvsClose(storedUvsAt(artUvsA1, tileAId, movedA), moved.uvsOf("dA1"), "the first drawable over A follows the art")
		assertUvsClose(storedUvsAt(artUvsA2, tileAId, movedA), moved.uvsOf("dA2"), "the second drawable over A follows too - a tile moves every drawable sharing it")
		assertUvsClose(storedUvsAt(artUvsB, tileBId, movedB), moved.uvsOf("dB"), "the drawable over B follows the art")
		assertSame(base.uvsOf("dC"), moved.uvsOf("dC"), "an untouched tile's drawable keeps its array by reference")
		assertSame(base.uvsOf("dLoose"), moved.uvsOf("dLoose"), "an unbound drawable is untouched")
		assertEquals(placedA, base.placementOf(tileAId), "the original model is immutable")
	}

	@Test
	fun oneFaultAnywhereInTheMapRefusesTheWholeEdit() {
		val base = baseModel()

		val missingPage = base.withAtlasPlacements(mapOf(tileAId to placement(20f, 8f), tileBId to placement(0f, 0f, pageIndex = 3)))
		assertSame(base, missingPage, "a placement naming a page the atlas lacks refuses the whole map, not just its tile")

		val collapsed = base.withAtlasPlacements(mapOf(tileAId to placement(20f, 8f), tileBId to AtlasPlacement(0, 0f, 0f, 0f, 1f, 0f)))
		assertSame(base, collapsed, "a placement that will not invert refuses the whole map")

		val unknown = base.withAtlasPlacements(mapOf(tileAId to placement(20f, 8f), AtlasTileId("gone") to placement(1f, 1f)))
		assertSame(base, unknown, "an unknown tile refuses the whole map")
	}

	@Test
	fun aNoOpMapReturnsTheSameInstance() {
		val base = baseModel()
		assertSame(base, base.withAtlasPlacements(emptyMap()), "an empty map changes nothing")
		assertSame(base, base.withAtlasPlacements(mapOf(tileAId to placedA, tileBId to placedB)), "restating every placement changes nothing")
	}

	@Test
	fun theSessionCommitsAMultiTileMoveAsOneStep() {
		val session = EditorSession(baseModel())
		val movedA = placement(20f, 8f)
		val movedB = placement(60f, 44f)

		session.setAtlasPlacements(mapOf(tileAId to movedA, tileBId to movedB))
		assertEquals(movedA, session.model.value.placementOf(tileAId))
		assertEquals(movedB, session.model.value.placementOf(tileBId))
		assertTrue(session.dirty.value, "a pack is document content, so it dirties")
		assertEquals("change.document.atlasPlacement", DocumentChange.SetAtlasPlacement(listOf(tileAId, tileBId)).labelKey)

		session.undo()
		assertEquals(placedA, session.model.value.placementOf(tileAId), "one undo restores both tiles")
		assertEquals(placedB, session.model.value.placementOf(tileBId), "one undo restores both tiles")
		assertFalse(session.canUndo.value, "the whole gesture was one step")
		assertFalse(session.dirty.value, "undo restores the saved model instance")

		session.setAtlasPlacements(mapOf(tileAId to placedA))
		assertFalse(session.canUndo.value, "a no-op commit records nothing")
	}

	@Test
	fun placementDragTileIdsCoversPlacedTilesOfSelectedDrawablesOnly() {
		val model = baseModel()
		val selection =
			Selection(
				setOf(target("dA1"), target("dA2"), target("dUnpacked"), target("dLoose"), SelectionTarget.Deformer(DeformerId("w"))),
				target("dA1"),
			)
		assertEquals(setOf(tileAId), model.placementDragTileIds(selection), "two drawables over one tile name it once; unpacked and unbound art contribute nothing")
		assertEquals(emptySet(), model.placementDragTileIds(Selection()), "nothing selected, nothing to move")
		assertEquals(setOf(tileAId, tileBId), model.placementDragTileIds(Selection(setOf(target("dB"), target("dA2")), target("dB"))).toSet())
	}

	@Test
	fun objectModeLatchesAUvOperatorOverPackedArt() {
		val session = EditorSession(baseModel())
		assertEquals(EditorMode.Object, session.mode.value)
		session.setSelection(Selection(setOf(target("dA1")), target("dA1")))

		session.beginUvOperator(MeshOperatorKind.Grab, "uv-area")
		assertEquals(ActiveOperator(MeshOperatorKind.Grab, "uv-area"), session.activeUvOperator.value, "a placed selection latches")
		assertNull(session.notice.value, "no notice on success")

		session.clearUvOperator()
		session.beginUvOperator(MeshOperatorKind.Rotate, "uv-area")
		assertEquals(MeshOperatorKind.Rotate, session.activeUvOperator.value?.kind, "rotate latches too")
		session.clearUvOperator()
		session.beginUvOperator(MeshOperatorKind.Scale, "uv-area")
		assertEquals(MeshOperatorKind.Scale, session.activeUvOperator.value?.kind, "scale latches too")

		session.clearUvOperator()
		session.beginUvOperator(MeshOperatorKind.VertexSlide, "uv-area")
		assertNull(session.activeUvOperator.value, "vertex slide is not a placement operation")
	}

	@Test
	fun objectModeRefusesWithANoticeWhenNothingSelectedIsPacked() {
		val session = EditorSession(baseModel())

		session.beginUvOperator(MeshOperatorKind.Grab, "uv-area")
		assertNull(session.activeUvOperator.value, "an empty selection has nothing to place")
		assertEquals("notice.uv.placement.noPlacedArt", session.notice.value?.messageKey)
		assertEquals(NoticePlacement.NearCursor, session.notice.value?.placement)

		session.setSelection(Selection(setOf(target("dUnpacked"), target("dLoose")), target("dUnpacked")))
		session.beginUvOperator(MeshOperatorKind.Grab, "uv-area")
		assertNull(session.activeUvOperator.value, "unpacked and unbound art has nothing on a page to move")
		assertEquals("notice.uv.placement.noPlacedArt", session.notice.value?.messageKey)
	}

	@Test
	fun objectModeRefusesALayerAddressedDocument() {
		val base = baseModel()
		val session = EditorSession(base.copy(atlas = base.atlas.copy(storedUvsAddressPages = false)))
		session.setSelection(Selection(setOf(target("dA1")), target("dA1")))

		session.beginUvOperator(MeshOperatorKind.Grab, "uv-area")
		assertNull(session.activeUvOperator.value, "coordinates that address the art have no page placement to move")
		assertEquals("notice.uv.placement.layerAddressed", session.notice.value?.messageKey)
	}

	@Test
	fun editModeStillNeedsAMeshSelection() {
		val session = EditorSession(baseModel())
		session.setSelection(Selection(setOf(target("dA1")), target("dA1")))
		session.setMode(EditorMode.Edit)

		session.beginUvOperator(MeshOperatorKind.Grab, "uv-area")
		assertNull(session.activeUvOperator.value, "Edit mode moves texture coordinates and has none selected")
		assertNull(session.notice.value, "an empty Edit selection stays a silent no-op")
	}
}