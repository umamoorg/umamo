package org.umamo.ui.viewport.uv

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.puppet.IslandEdgeRole
import org.umamo.render.puppet.IslandFillRole
import org.umamo.render.puppet.IslandStyle
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OverlayColor
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.UvSceneContent
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the UV Object scene's derive over the placed rig: every shown island back to front by the rest front
 * rank in the roles the selection, the collisions, and the pins give it; a moving island at its preview
 * positions; a selection change that keeps every array by identity; and over a page the placement preview -
 * the scrims over the movers' old trims, the crops at their new placements, and the ghost while its atlas is
 * committed.
 */
class UvObjectOverlayProducerTest {
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)
	private val scrim = OverlayColor(0f, 0f, 0f, 0.5f)
	private val quadFront = mapOf(UV_RIG_QUAD to 2f, UV_RIG_OTHER to 1f)

	@Test
	fun theIslandsDrawBackToFrontInTheirRoles() {
		val model = uvRigPlacedModel()

		val islands = assertNotNull(produce(model, selecting(UV_RIG_QUAD)).islands)

		assertEquals(MeshOverlayKind.Islands, islands.overlay.kind)
		assertEquals(listOf(UV_RIG_OTHER, UV_RIG_QUAD), islands.overlay.meshes.map { mesh -> mesh.drawableId }, "the nearer island draws last")
		assertEquals(IslandStyle(IslandFillRole.Idle, IslandEdgeRole.Idle), islands.overlay.meshes[0].islandStyle)
		assertEquals(IslandStyle(IslandFillRole.Selected, IslandEdgeRole.Active), islands.overlay.meshes[1].islandStyle, "the active island fills selected and outlines active")
		val ties = assertNotNull(produce(model, selecting(UV_RIG_QUAD), frontRank = emptyMap()).islands)
		assertEquals(listOf(UV_RIG_QUAD, UV_RIG_OTHER), ties.overlay.meshes.map { mesh -> mesh.drawableId }, "equal ranks keep the shown order")
	}

	@Test
	fun theWarningOutranksThePinWhichOutranksTheSelection() {
		val tile = AtlasTileId("tile")
		val selected = setOf(DrawableId("a"))

		assertEquals(IslandEdgeRole.Warning, islandStyleOf(DrawableId("a"), selected, DrawableId("a"), tile, setOf(tile), setOf(tile)).edge, "a collision beats a pin")
		assertEquals(IslandEdgeRole.Pinned, islandStyleOf(DrawableId("a"), selected, DrawableId("a"), tile, emptySet(), setOf(tile)).edge, "a pin beats the active outline")
		assertEquals(IslandEdgeRole.Active, islandStyleOf(DrawableId("a"), selected, DrawableId("a"), tile, emptySet(), emptySet()).edge)
		assertEquals(IslandEdgeRole.Selected, islandStyleOf(DrawableId("a"), selected, null, tile, emptySet(), emptySet()).edge)
		assertEquals(IslandStyle(IslandFillRole.Idle, IslandEdgeRole.Idle), islandStyleOf(DrawableId("b"), selected, DrawableId("a"), null, setOf(tile), setOf(tile)), "an island with no tile is never warned or pinned")
		assertEquals(IslandFillRole.Selected, islandStyleOf(DrawableId("a"), emptySet(), DrawableId("a"), tile, setOf(tile), emptySet()).fill, "the active island fills selected whatever its outline")
	}

	@Test
	fun aMovingIslandTakesItsPreviewPositions() {
		val model = uvRigPlacedModel()
		val drag = uvRigPlacementDrag(model)
		val geometries = uvRigGeometries(model, uvRigPageFrame())

		val islands = assertNotNull(produce(model, selecting(UV_RIG_QUAD), UvPlacementScene(drag, null), geometries = geometries).islands)

		assertSame(drag.previewPositionsById.getValue(UV_RIG_QUAD), islands.positionsById[UV_RIG_QUAD], "the mover draws where the drive puts it")
		assertSame(geometries.first { geometry -> geometry.drawableId == UV_RIG_OTHER }.positions, islands.positionsById[UV_RIG_OTHER], "a bystander where it is")
	}

	@Test
	fun aCollidingTileOutlinesInTheWarningRole() {
		val model = uvRigPlacedModel()
		// Forty page pixels right puts the quad's tile over the triangle's.
		val drag = uvRigPlacementDrag(model, deltaTexels = 40f)
		assertTrue(UV_RIG_QUAD_TILE in drag.result.overlappingTileIds, "the fixture's move does collide")

		val islands = assertNotNull(produce(model, selecting(UV_RIG_QUAD), UvPlacementScene(drag, null)).islands)

		assertTrue(islands.overlay.meshes.filter { mesh -> mesh.drawableId == UV_RIG_QUAD }.all { mesh -> mesh.islandStyle?.edge == IslandEdgeRole.Warning })
	}

	@Test
	fun aSelectionChangeKeepsEveryArray() {
		val model = uvRigPlacedModel()
		val geometries = uvRigGeometries(model, uvRigPageFrame())
		val producer = UvObjectOverlayProducer()
		val first = assertNotNull(producer.produce(selecting(UV_RIG_QUAD), sceneOf(model, geometries), UvPlacementScene(null, null), model.atlas, sizes).islands)
		assertSame(first, producer.produce(selecting(UV_RIG_QUAD), sceneOf(model, geometries), UvPlacementScene(null, null), model.atlas, sizes).islands, "the same state is the same value")

		val second = assertNotNull(producer.produce(selecting(UV_RIG_OTHER), sceneOf(model, geometries), UvPlacementScene(null, null), model.atlas, sizes).islands)

		assertNotSame(first, second, "a new selection is a new value")
		for (mesh in second.overlay.meshes) {
			val before = first.overlay.meshes.first { previous -> previous.drawableId == mesh.drawableId }
			assertSame(before.edgeEndpoints, mesh.edgeEndpoints, "${mesh.drawableId} keeps its edges")
			assertSame(first.positionsById[mesh.drawableId], second.positionsById[mesh.drawableId], "and its positions")
			assertSame(first.triangleIndicesById[mesh.drawableId], second.triangleIndicesById[mesh.drawableId], "and its indices")
		}
	}

	@Test
	fun thePreviewScrimsTheOldTrimAndCropsTheNew() {
		val model = uvRigPlacedModel()
		val drag = uvRigPlacementDrag(model)
		val mover = drag.gesture.movers.single()

		val preview = assertNotNull(produce(model, selecting(UV_RIG_QUAD), UvPlacementScene(drag, null)).placement)

		assertEquals(scrim, preview.scrimColor)
		assertContentEquals(trimQuadToDisplay(mover.placement, mover.trim, UV_RIG_PAGE_SIDE), preview.scrimQuads.single(), "the scrim covers the old trim")
		val crop = preview.crops.single()
		assertEquals(UV_RIG_QUAD_TILE.raw, crop.tileKey)
		assertSame(mover.crop, crop.crop, "the mover's own crop")
		assertContentEquals(trimQuadToDisplay(uvRigTilePlacement(110f), mover.trim, UV_RIG_PAGE_SIDE), crop.quadToWorld, "at the drive's placement")
		assertContentEquals(trimSampleAffine(mover.trim, UV_RIG_TILE_SIDE, UV_RIG_TILE_SIDE), crop.sampleAffine, "sampling the trim in the whole tile")
		assertNull(preview.ghostAtlas)
		assertTrue(preview.ghostCrops.isEmpty())
	}

	@Test
	fun theGhostShowsOnlyWhileItsAtlasIsCommitted() {
		val model = uvRigPlacedModel()
		val drag = uvRigPlacementDrag(model)
		val mover = drag.gesture.movers.single()
		val ghost = PlacementGhost(model.atlas, UV_RIG_PAGE_SIDE, listOf(GhostCrop(mover.tileId, mover.crop!!, mover.trim, uvRigTilePlacement(110f))))

		val standing = assertNotNull(produce(model, selecting(UV_RIG_QUAD), UvPlacementScene(null, ghost)).placement)
		assertSame(model.atlas, standing.ghostAtlas, "the ghost names its atlas for the engine")
		assertContentEquals(trimQuadToDisplay(uvRigTilePlacement(110f), mover.trim, UV_RIG_PAGE_SIDE), standing.ghostCrops.single().quadToWorld)
		assertTrue(standing.scrimQuads.isEmpty() && standing.crops.isEmpty(), "and nothing of a drag")

		assertNull(produce(model, selecting(UV_RIG_QUAD), UvPlacementScene(null, ghost), committedAtlas = model.atlas.copy()).placement, "another committed atlas takes it down")
	}

	@Test
	fun overALayerThereIsNoPreview() {
		val model = uvRigPlacedModel()
		val drag = uvRigPlacementDrag(model)
		val layer = UvSceneContent.SourceLayer(UV_RIG_LAYER_KEY, null)

		val scene = UvObjectOverlayProducer().produce(selecting(UV_RIG_QUAD), sceneOf(model, uvRigGeometries(model, uvRigPageFrame()), layer), UvPlacementScene(drag, null), model.atlas, sizes)

		assertNull(scene.placement, "a placement moves on a page only")
		assertNotNull(scene.islands, "the islands still draw")
	}

	@Test
	fun anUnchangedDragKeepsThePreview() {
		val model = uvRigPlacedModel()
		val drag = uvRigPlacementDrag(model)
		val geometries = uvRigGeometries(model, uvRigPageFrame())
		val producer = UvObjectOverlayProducer()
		val first = producer.produce(selecting(UV_RIG_QUAD), sceneOf(model, geometries), UvPlacementScene(drag, null), model.atlas, sizes).placement

		val again = producer.produce(selecting(UV_RIG_OTHER), sceneOf(model, geometries), UvPlacementScene(drag, null), model.atlas, sizes).placement

		assertSame(first, again, "a selection change leaves the preview as it was")
		assertNotSame(first, producer.produce(selecting(UV_RIG_QUAD), sceneOf(model, geometries), UvPlacementScene(uvRigPlacementDrag(model), null), model.atlas, sizes).placement, "a new drive is a new preview")
	}

	/**
	 * One fresh derive over the rig's page.
	 *
	 * @param PuppetModel model The model.
	 * @param Selection selection The object selection.
	 * @param UvPlacementScene placement The placement scene.
	 * @param PuppetAtlas committedAtlas The committed atlas.
	 * @param Map<DrawableId, Float> frontRank The front rank.
	 * @param List<GizmoMeshGeometry> geometries The shown geometry.
	 * @return UvObjectScene The scene.
	 */
	private fun produce(
		model: PuppetModel,
		selection: Selection,
		placement: UvPlacementScene = UvPlacementScene(null, null),
		committedAtlas: PuppetAtlas = model.atlas,
		frontRank: Map<DrawableId, Float> = quadFront,
		geometries: List<GizmoMeshGeometry> = uvRigGeometries(model, uvRigPageFrame()),
	): UvObjectScene =
		UvObjectOverlayProducer().produce(selection, sceneOf(model, geometries, frontRank = frontRank), placement, committedAtlas, sizes)

	/**
	 * A scene over the rig's page (or [content]) showing the given geometry.
	 *
	 * @param PuppetModel model The model.
	 * @param List<GizmoMeshGeometry> geometries The shown geometry.
	 * @param UvSceneContent content The surface.
	 * @param Map<DrawableId, Float> frontRank The front rank.
	 * @return UvShownScene The scene.
	 */
	private fun sceneOf(
		model: PuppetModel,
		geometries: List<GizmoMeshGeometry>,
		content: UvSceneContent = UvSceneContent.AtlasPage(0),
		frontRank: Map<DrawableId, Float> = quadFront,
	): UvShownScene = UvShownScene(content, null, model, geometries, frontRank, scrim)

	/**
	 * A selection of one drawable, active.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @return Selection The selection.
	 */
	private fun selecting(drawableId: DrawableId): Selection = Selection(setOf(SelectionTarget.Drawable(drawableId)), SelectionTarget.Drawable(drawableId))
}