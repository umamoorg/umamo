package org.umamo.ui.viewport.uv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId
import org.umamo.settings.Settings
import org.umamo.ui.LocalSettings
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.StubPuppetViewportService
import org.umamo.ui.viewport.ViewportRegionOverlay
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.gizmoAreaTag
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.spaces.uv.UvRadiusSurfaceKey
import org.umamo.ui.workspace.spaces.uv.rememberUvProportionalRadius

/*
 * The UV editor's gizmo overlays, with the Zoom Region overlay above them, mounted the way UvEditorSpace
 * mounts them, twice: two areas side by side over ONE session and ONE render-sync handle, so a case can
 * check that a gesture belongs to the area it started in.  The render sync is the recording stand-in,
 * which never renders, and each area shows the rig's page or its source layer through the rig's frames
 * and cameras.  Density is one, so a dp is a pixel and the rig's screen coordinates are the pointer
 * coordinates the input helpers take (gizmo/GizmoPointerInputHelpers.kt).
 *
 * The shown geometry follows the session's COMMITTED model.  The real host follows the render sync's
 * preview too, so a second area moves with a drag in the first; that costs the host a recomposition per
 * drive by design, and leaving it out here is what lets a case pin what the overlays themselves recompose.
 *
 * Pointer moves stay well inside an area and never cross into the other: a modal overlay drives on Exit
 * as well as Move, and a move within WRAP_MARGIN_PX of an edge would warp the real cursor.
 */

/**
 * What one fixture area shows.
 *
 * @property Boolean layer True for the rig's source layer, false for its atlas page.
 * @property List<DrawableId> shown The drawables drawn over the surface; empty for a surface with none of
 *   the edit's meshes.
 * @property Boolean cameraLost True while the area has no frame camera, as before its first frame.
 */
internal data class UvRigSurface(
	val layer: Boolean = false,
	val shown: List<DrawableId> = listOf(UV_RIG_QUAD, UV_RIG_OTHER),
	val cameraLost: Boolean = false,
)

/** The rig's source layer as an area shows it: the quad alone, since only the quad is bound to it. */
internal val UV_RIG_LAYER_SURFACE = UvRigSurface(layer = true, shown = listOf(UV_RIG_QUAD))

/** What the fixture holds for a case to inspect. */
internal class UvGizmoOverlayFixture(
	/** The session both areas run over. */
	val session: EditorSession,
	/** What an area showing the page hands its Object overlay for a placement gesture, or null for none. */
	val placementSurface: UvPlacementSurface?,
) {
	/** The render-sync handle both areas share. */
	val renderSync = RecordingPuppetRenderSync()

	/** The render service the Zoom Region overlay reaches. */
	val service = StubPuppetViewportService()

	/** The settings the gizmo colors read. */
	val settings: Settings = inMemorySettings()

	/** Every overlap picker an overlay asked for, as the area, the anchor, and the candidates. */
	val overlapRequests = ArrayList<Triple<String, Offset, List<PickCandidate>>>()

	/**
	 * The areas whose overlays are mounted.  Taking an area out unmounts its overlays mid-gesture the way
	 * a mode switch or a closing area does, while the area's box, its tag, and the host's pointer record
	 * stay, so input can go on after it.
	 */
	val mountedAreas = mutableStateOf(setOf(LEFT_AREA, RIGHT_AREA))

	/** The page frame every area showing the page shares. */
	val pageFrame = uvRigPageFrame()

	/** The layer frame every area showing the layer shares. */
	val layerFrame = uvRigLayerFrame()

	/** The camera that centers the quad over the layer, as the page camera does over the page. */
	val layerCamera = uvRigCameraOver(uvRigGeometries(session.model.value, layerFrame, listOf(UV_RIG_QUAD)))

	private val surfaceByArea = mapOf(LEFT_AREA to mutableStateOf(UvRigSurface()), RIGHT_AREA to mutableStateOf(UvRigSurface()))

	/** Each area's radius state as its last composition held it, recorded after the composition applies. */
	val radiusStateByArea = HashMap<String, MutableState<Float?>>()

	/** Each area's placement readout, the host's per-area state. */
	val placementDragStatusByArea = mapOf(LEFT_AREA to mutableStateOf<PlacementDragStatus?>(null), RIGHT_AREA to mutableStateOf<PlacementDragStatus?>(null))

	/** Where the pointer last was in each area, tracked by the host. */
	val areaPointerByArea = mapOf(LEFT_AREA to mutableStateOf(Offset.Zero), RIGHT_AREA to mutableStateOf(Offset.Zero))

	/**
	 * Shows a surface in an area.
	 *
	 * @param String areaId The area.
	 * @param UvRigSurface surface What it shows from now on.
	 */
	fun show(areaId: String, surface: UvRigSurface) {
		surfaceByArea.getValue(areaId).value = surface
	}

	/**
	 * What an area shows.
	 *
	 * @param String areaId The area.
	 * @return UvRigSurface The surface.
	 */
	fun surfaceOf(areaId: String): UvRigSurface = surfaceByArea.getValue(areaId).value

	/**
	 * The proportional radius the area's shown surface holds now, the value the host's badge reads.
	 *
	 * @param String areaId The area.
	 * @return Float? The radius in display texels, or null while unseeded.
	 */
	fun radiusOf(areaId: String): Float? = radiusStateByArea[areaId]?.value

	/**
	 * The frame a surface is shown through.
	 *
	 * @param UvRigSurface surface The surface.
	 * @return UvEditFrame The frame.
	 */
	fun frameOf(surface: UvRigSurface): UvEditFrame = if (surface.layer) layerFrame else pageFrame

	/**
	 * The camera a surface is shown under, while the area has one.
	 *
	 * @param UvRigSurface surface The surface.
	 * @return ViewportCamera The camera.
	 */
	fun cameraOf(surface: UvRigSurface): ViewportCamera = if (surface.layer) layerCamera else UV_RIG_PAGE_CAMERA
}

/**
 * Mounts the UV editor's Object and Edit gizmo overlays in the two areas over [session].
 *
 * @param EditorSession session The session to run the overlays over.
 * @param UvPlacementSurface? placementSurface What an area showing the page hands its Object overlay, as
 *   the host does over a page when the document retains its art; an area showing the layer hands nothing.
 * @return UvGizmoOverlayFixture What the case inspects.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.mountUvGizmoOverlays(session: EditorSession, placementSurface: UvPlacementSurface? = null): UvGizmoOverlayFixture {
	val fixture = UvGizmoOverlayFixture(session, placementSurface)
	setContent {
		CompositionLocalProvider(LocalSettings provides fixture.settings, LocalPuppetRenderSync provides fixture.renderSync) {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					Row {
						for (areaId in listOf(LEFT_AREA, RIGHT_AREA)) {
							key(areaId) {
								val model by session.model.collectAsState()
								val surface = fixture.surfaceOf(areaId)
								val frame = fixture.frameOf(surface)
								val geometries = remember(model, surface) { uvRigGeometries(model, frame, surface.shown) }
								val islandPick =
									remember(model, geometries) {
										val uvsById =
											geometries.associate { geometry ->
												geometry.drawableId to displayToUv(geometry.positions, frame.displayWidth, frame.displayHeight)
											}
										uvIslandPick(geometries = geometries, frontRank = restFrontRank(model), uvsById = uvsById, image = null)
									}
								val camera = if (surface.cameraLost) null else fixture.cameraOf(surface)
								val radiusState =
									rememberUvProportionalRadius(
										areaId,
										UvRadiusSurfaceKey(frame.displayWidth, frame.displayHeight, if (surface.layer) UV_RIG_LAYER_KEY else null),
									)
								SideEffect { fixture.radiusStateByArea[areaId] = radiusState }
								val areaPointer = fixture.areaPointerByArea.getValue(areaId)
								Box(
									modifier =
										Modifier
											.size(UV_RIG_AREA_WIDTH.dp, UV_RIG_AREA_HEIGHT.dp)
											.testTag(gizmoAreaTag(areaId))
											// The host's own pointer record: watch-only, on the Initial pass, so it is
											// current even while an overlay owns the gesture.
											.pointerInput(areaId) {
												awaitPointerEventScope {
													while (true) {
														val event = awaitPointerEvent(PointerEventPass.Initial)
														event.changes.lastOrNull()?.let { change -> areaPointer.value = change.position }
													}
												}
											},
								) {
									if (areaId in fixture.mountedAreas.value) {
										UvObjectGizmoOverlay(
											areaId = areaId,
											session = session,
											geometries = geometries,
											islandPick = islandPick,
											frame = frame,
											camera = camera,
											widthPx = UV_RIG_AREA_WIDTH,
											heightPx = UV_RIG_AREA_HEIGHT,
											placementSurface = if (surface.layer) null else fixture.placementSurface,
											placementDragStatusState = fixture.placementDragStatusByArea.getValue(areaId),
											onOverlapRequest = { anchor, candidates -> fixture.overlapRequests.add(Triple(areaId, anchor, candidates)) },
										)
										UvEditGizmoOverlay(
											areaId = areaId,
											session = session,
											geometries = geometries,
											frame = frame,
											camera = camera,
											widthPx = UV_RIG_AREA_WIDTH,
											heightPx = UV_RIG_AREA_HEIGHT,
											areaPointer = areaPointer,
											proportionalRadiusDisplayState = radiusState,
										)
										// Above the gizmos, as the host mounts it; inert unless Zoom Region is armed here.
										ViewportRegionOverlay(
											areaId = areaId,
											service = fixture.service,
											session = session,
											camera = camera,
											widthPx = UV_RIG_AREA_WIDTH,
											heightPx = UV_RIG_AREA_HEIGHT,
										)
									}
								}
							}
						}
					}
				}
			}
		}
	}
	waitForIdle()
	return fixture
}

/**
 * Waits for a placement gesture latched in an area to land its capture, which builds off the UI thread:
 * the pointer nudges one pixel back and forth at [at] until the first drive publishes the host's readout.
 * A pixel is a quarter of a page texel at the rig's zoom, and a Grab snaps to whole page pixels, so the
 * nudges move nothing.  The gesture's origin is wherever the pointer was when the capture landed.
 *
 * @param UvGizmoOverlayFixture fixture The mounted fixture.
 * @param String areaId The area the gesture latched in.
 * @param Offset at Where the pointer rests while it waits.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitPlacementCapture(fixture: UvGizmoOverlayFixture, areaId: String, at: Offset) {
	val deadline = System.currentTimeMillis() + PLACEMENT_CAPTURE_TIMEOUT_MILLIS
	var nudged = false
	while (fixture.placementDragStatusByArea.getValue(areaId).value == null) {
		check(System.currentTimeMillis() < deadline) { "the placement capture never landed" }
		nudged = !nudged
		moveIn(areaId, listOf(if (nudged) at + Offset(1f, 0f) else at))
	}
}

/** How long a case waits for a placement capture to land before it fails. */
private const val PLACEMENT_CAPTURE_TIMEOUT_MILLIS = 5000L