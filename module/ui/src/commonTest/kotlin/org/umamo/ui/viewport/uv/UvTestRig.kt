package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.PuppetRenderSync
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.worldToScreen
import kotlin.test.assertEquals

/*
 * The rig the UV gizmo tests share.  Two drawables with stored UVs over one 256 x 256 atlas page:
 *   - quad: a square of two triangles (0-1-2, 0-2-3) whose mapping covers page display texels x 100..120,
 *     y 100..120 (display y is up): vertex 0 at (100, 100), 1 at (120, 100), 2 at (120, 120), 3 at (100, 120).
 *   - other: a triangle beside it at (140, 100), (160, 100), (140, 120).
 * Every stored coordinate is a multiple of 1/256, so a display round trip is exact and an untouched vertex
 * can be compared bit for bit.
 *
 * The page camera centers the quad in a 400 x 300 area at four pixels per texel: display (x, y) lands on
 * screen (200 + 4 (x - 110), 150 - 4 (y - 110)), so the quad's corners sit at (160,190) (240,190) (240,110)
 * (160,110) and the triangle's at (320,190) (400,190) (320,110).
 *
 * The source layer is a 64 x 64 artwork placed at page pixel (96, 96), unscaled, which only the quad is
 * bound to.  Its frame is a real conversion (not the stored frame), and its camera centers the quad the
 * same way, so a screen point means the same vertex on either surface.
 *
 * The placed model (uvRigPlacedModel) adds the atlas an Object-mode placement gesture moves: each
 * drawable over its own fully opaque 20 x 20 tile, placed unscaled where its mapping already samples - the
 * quad's at page pixel (100, 136), the triangle's at (140, 136) (page pixels are y down, so display y 100..120
 * is page rows 136..156).
 */

/** The square the rig's tests edit. */
internal val UV_RIG_QUAD = DrawableId("quad")

/** The triangle beside the square. */
internal val UV_RIG_OTHER = DrawableId("other")

/** The rig's area width in pixels. */
internal const val UV_RIG_AREA_WIDTH = 400

/** The rig's area height in pixels. */
internal const val UV_RIG_AREA_HEIGHT = 300

/** The rig's area size. */
internal val UV_RIG_AREA_SIZE = IntSize(UV_RIG_AREA_WIDTH, UV_RIG_AREA_HEIGHT)

/** The atlas page's side in texels. */
internal const val UV_RIG_PAGE_SIDE = 256

/** The source layer's key. */
internal const val UV_RIG_LAYER_KEY = "quadArt"

/** The source layer's side in pixels. */
internal const val UV_RIG_LAYER_SIDE = 64

/** The quad's tile in the placed model. */
internal val UV_RIG_QUAD_TILE = AtlasTileId("quadTile")

/** The triangle's tile in the placed model. */
internal val UV_RIG_OTHER_TILE = AtlasTileId("otherTile")

/** Each placed tile's side in pixels. */
internal const val UV_RIG_TILE_SIDE = 20

/** The quad's page display corners, interleaved (x, y). */
private val QUAD_PAGE_DISPLAY = floatArrayOf(100f, 100f, 120f, 100f, 120f, 120f, 100f, 120f)

/** The triangle's page display corners, interleaved (x, y). */
private val OTHER_PAGE_DISPLAY = floatArrayOf(140f, 100f, 160f, 100f, 140f, 120f)

/** The page camera, centered on the quad at four pixels per texel. */
internal val UV_RIG_PAGE_CAMERA = ViewportCamera(centerX = 110f, centerY = 110f, zoom = 4f)

/** The quad's stored texture coordinates, as the rig's model holds them before any edit. */
internal val UV_RIG_QUAD_UVS: List<Float> = displayToUv(QUAD_PAGE_DISPLAY, UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE).toList()

/** Where the pointer rests as a case's gesture latches, the quad's center on screen: the gesture measures from here. */
internal val UV_RIG_GESTURE_START = Offset(200f, 150f)

/** Forty pixels right of UV_RIG_GESTURE_START: ten display texels at either rig camera's zoom. */
internal val UV_RIG_TEN_TEXELS_RIGHT = Offset(240f, 150f)

/**
 * One rig drawable with no deformer channels.
 *
 * @param DrawableId id The drawable.
 * @param FloatArray pageDisplay Its mapping's page display corners, interleaved (x, y).
 * @param IntArray indices The triangle indices.
 * @return Drawable The drawable.
 */
private fun uvRigDrawable(id: DrawableId, pageDisplay: FloatArray, indices: IntArray): Drawable =
	Drawable(
		id = id,
		name = id.raw,
		parentDeformerId = null,
		blendMode = BlendMode.Normal,
		maskedBy = emptyList(),
		mesh = DrawableMesh(pageDisplay.copyOf(), displayToUv(pageDisplay, UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE), indices),
		geometryGrid = null,
	)

/**
 * The rig's model.
 *
 * @return PuppetModel The quad and the triangle beside it.
 */
internal fun uvRigModel(): PuppetModel =
	PuppetModel(
		parameters = emptyList(),
		parts = emptyList(),
		deformers = emptyList(),
		drawables =
			listOf(
				uvRigDrawable(UV_RIG_QUAD, QUAD_PAGE_DISPLAY, intArrayOf(0, 1, 2, 0, 2, 3)),
				uvRigDrawable(UV_RIG_OTHER, OTHER_PAGE_DISPLAY, intArrayOf(0, 1, 2)),
			),
		rootChildren = listOf(OrgChild.Drawable(UV_RIG_QUAD), OrgChild.Drawable(UV_RIG_OTHER)),
		rootPartId = null,
	)

/**
 * The rig's model over a placed atlas: one page, and each drawable over its own tile where its mapping
 * already samples.
 *
 * @return PuppetModel The quad and the triangle, each bound to its placed tile.
 */
internal fun uvRigPlacedModel(): PuppetModel {
	val model = uvRigModel()
	val tileByDrawable = mapOf(UV_RIG_QUAD to UV_RIG_QUAD_TILE, UV_RIG_OTHER to UV_RIG_OTHER_TILE)
	return model.copy(
		drawables = model.drawables.map { drawable -> drawable.copy(atlasTileId = tileByDrawable.getValue(drawable.id)) },
		atlas =
			PuppetAtlas(
				pages = listOf(AtlasPage(UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE)),
				tiles =
					listOf(
						AtlasTile(UV_RIG_QUAD_TILE, UV_RIG_QUAD_TILE.raw, UV_RIG_TILE_SIDE, UV_RIG_TILE_SIDE, uvRigTilePlacement(100f)),
						AtlasTile(UV_RIG_OTHER_TILE, UV_RIG_OTHER_TILE.raw, UV_RIG_TILE_SIDE, UV_RIG_TILE_SIDE, uvRigTilePlacement(140f)),
					),
			),
	)
}

/**
 * An unscaled, unrotated placement on the rig's page, on the row both tiles share.
 *
 * @param Float positionX The tile's left edge in page pixels.
 * @return AtlasPlacement The placement.
 */
internal fun uvRigTilePlacement(positionX: Float): AtlasPlacement =
	AtlasPlacement(pageIndex = 0, positionX = positionX, positionY = 136f, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f)

/**
 * The source-art store of the placed model: every tile fully opaque, the same instance per tile on every
 * call, as the store's contract asks.
 *
 * @return SourceArtRasters The store.
 */
internal fun uvRigArtRasters(): SourceArtRasters {
	val rgba = ByteArray(UV_RIG_TILE_SIDE * UV_RIG_TILE_SIDE * 4) { byteIndex -> if (byteIndex % 4 == 3) 0xFF.toByte() else 0x80.toByte() }
	val rasterByTile = listOf(UV_RIG_QUAD_TILE, UV_RIG_OTHER_TILE).associateWith { DecodedImage(rgba, UV_RIG_TILE_SIDE, UV_RIG_TILE_SIDE) }
	return SourceArtRasters { tileId -> rasterByTile[tileId] }
}

/**
 * What a UV editor showing the placed model's page hands its Object overlay.  No page pixels, so the movers
 * test only each other.
 *
 * @return UvPlacementSurface The surface.
 */
internal fun uvRigPlacementSurface(): UvPlacementSurface = UvPlacementSurface(UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE, uvRigArtRasters(), pageImage = null)

/**
 * The page placement of one tile in the session's committed model.
 *
 * @param EditorSession session The session.
 * @param AtlasTileId tileId The tile.
 * @return AtlasPlacement? Its placement.
 */
internal fun uvRigPlacementOf(session: EditorSession, tileId: AtlasTileId): AtlasPlacement? = session.model.value.atlas.tileById.getValue(tileId).placement

/**
 * The frame of a UV editor showing the rig's atlas page.
 *
 * @return UvEditFrame The page frame.
 */
internal fun uvRigPageFrame(): UvEditFrame = atlasPageEditFrame(UV_RIG_PAGE_SIDE, UV_RIG_PAGE_SIDE)

/**
 * The frame of a UV editor showing the rig's source layer.
 *
 * @return UvEditFrame The layer frame.
 */
internal fun uvRigLayerFrame(): UvEditFrame {
	val binding =
		DrawableLayerBinding(
			layerKey = UV_RIG_LAYER_KEY,
			placement = AtlasPlacement(pageIndex = 0, positionX = 96f, positionY = 96f, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f),
			pageWidth = UV_RIG_PAGE_SIDE,
			pageHeight = UV_RIG_PAGE_SIDE,
		)
	val frame = sourceLayerEditFrame(binding, UV_RIG_LAYER_SIDE, UV_RIG_LAYER_SIDE)
	check(frame != null && !frame.isStoredFrame) { "the rig's layer frame must be a real conversion" }
	return frame
}

/**
 * The shown meshes' display geometry over a surface, from the session's stored coordinates.
 *
 * @param PuppetModel model The model whose stored coordinates to show.
 * @param UvEditFrame frame The shown surface's frame.
 * @param List<DrawableId> shown The drawables over the surface, in order.
 * @return List<GizmoMeshGeometry> One geometry per shown drawable.
 */
internal fun uvRigGeometries(model: PuppetModel, frame: UvEditFrame, shown: List<DrawableId> = listOf(UV_RIG_QUAD, UV_RIG_OTHER)): List<GizmoMeshGeometry> =
	shown.map { drawableId ->
		val mesh = model.drawables.first { drawable -> drawable.id == drawableId }.mesh!!
		val display = FloatArray(mesh.uvs.size)
		for (vertexIndex in 0 until mesh.uvs.size / 2) {
			val (displayX, displayY) = frame.displayAt(mesh.uvs[vertexIndex * 2], mesh.uvs[vertexIndex * 2 + 1])
			display[vertexIndex * 2] = displayX
			display[vertexIndex * 2 + 1] = displayY
		}
		GizmoMeshGeometry(drawableId, mesh.indices, MeshTopology.uniqueEdges(mesh.indices), display)
	}

/**
 * A camera that centers the quad's display bounds at four pixels per texel, so its corners land where
 * the page camera puts them.
 *
 * @param List<GizmoMeshGeometry> geometries The shown geometry, the quad among it.
 * @return ViewportCamera The camera.
 */
internal fun uvRigCameraOver(geometries: List<GizmoMeshGeometry>): ViewportCamera {
	val quad = geometries.first { geometry -> geometry.drawableId == UV_RIG_QUAD }.positions
	val xs = quad.filterIndexed { componentIndex, _ -> componentIndex % 2 == 0 }
	val ys = quad.filterIndexed { componentIndex, _ -> componentIndex % 2 == 1 }
	return ViewportCamera(centerX = (xs.min() + xs.max()) / 2f, centerY = (ys.min() + ys.max()) / 2f, zoom = 4f)
}

/**
 * Where a display point lands on the rig's screen.
 *
 * @param Float displayX The display x in texels.
 * @param Float displayY The display y in texels.
 * @param ViewportCamera camera The area camera.
 * @return Offset The area-local pixel.
 */
internal fun uvRigScreenOf(displayX: Float, displayY: Float, camera: ViewportCamera = UV_RIG_PAGE_CAMERA): Offset =
	worldToScreen(displayX, displayY, camera, UV_RIG_AREA_SIZE)

/**
 * An Edit-mode session over the rig with the given drawables in the edit and the given elements of the
 * first one selected; the last element given is the active one.
 *
 * @param List<DrawableId> editing The drawables the Edit session spans, the first active.
 * @param List<MeshElement> elements The elements to select on the first drawable, in order.
 * @param PuppetModel model The model to edit.
 * @return EditorSession The session, asserted to be in Edit mode.
 */
internal fun uvEditSession(
	editing: List<DrawableId> = listOf(UV_RIG_QUAD),
	elements: List<MeshElement> = emptyList(),
	model: PuppetModel = uvRigModel(),
): EditorSession {
	val session = EditorSession(model)
	val targets = editing.map { drawableId -> SelectionTarget.Drawable(drawableId) }
	session.setSelection(Selection(targets.toSet(), targets.first()))
	session.setMode(EditorMode.Edit)
	assertEquals(EditorMode.Edit, session.mode.value, "the rig must really be in Edit mode")
	var selection = session.meshSelection.value
	for (element in elements) {
		selection = MeshSelectionOps.add(selection, editing.first(), element)
	}
	if (elements.isNotEmpty()) {
		session.setMeshSelection(selection)
	}
	return session
}

/**
 * An Object-mode session over the rig with the given drawables selected, the first active; none for an
 * empty selection.
 *
 * @param List<DrawableId> selected The drawables to select.
 * @param PuppetModel model The model to select over.
 * @return EditorSession The session.
 */
internal fun uvObjectSession(selected: List<DrawableId> = listOf(UV_RIG_QUAD), model: PuppetModel = uvRigModel()): EditorSession {
	val session = EditorSession(model)
	val targets = selected.map { drawableId -> SelectionTarget.Drawable(drawableId) }
	session.setSelection(Selection(targets.toSet(), targets.firstOrNull()))
	return session
}

/**
 * The stored coordinates of one drawable in the session's committed model.
 *
 * @param EditorSession session The session.
 * @param DrawableId drawableId The drawable.
 * @return List<Float> Its stored coordinates, as a list for a readable comparison.
 */
internal fun uvRigUvsOf(session: EditorSession, drawableId: DrawableId): List<Float> =
	session.model.value.drawables.first { drawable -> drawable.id == drawableId }.mesh!!.uvs.toList()

/**
 * A render-sync stand-in that records what the UV overlays push and never renders.  It keeps the real
 * handle's order: a resync clears the published preview before anything else.
 */
internal class RecordingPuppetRenderSync : PuppetRenderSync {
	private val previewState = mutableStateOf<PuppetModel?>(null)

	/** Every preview model pushed, in order. */
	val previewed = ArrayList<PuppetModel>()

	/** How many times the renderer was sent back to the committed model. */
	var resyncs = 0
		private set

	override val preview: State<PuppetModel?> get() = previewState

	/**
	 * Records a preview push.
	 *
	 * @param PuppetModel model The preview model.
	 */
	override fun previewModel(model: PuppetModel) {
		previewState.value = model
		previewed.add(model)
	}

	/** Records a resync. */
	override fun resync() {
		previewState.value = null
		resyncs++
	}
}