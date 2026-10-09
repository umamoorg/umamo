package org.umamo.ui.workspace.spaces.uv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.render.puppet.OverlayColor
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.atlasBindingForTile
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.menu.ContextMenuArea
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.model.LocalPuppetTextures
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.model.LocalSourceArtRasters
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_uv_mirror_x
import org.umamo.ui.resources.menu_uv_mirror_y
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.viewport.ApplicationGridMirror
import org.umamo.ui.viewport.AreaOverlaysPublisher
import org.umamo.ui.viewport.LocalAreaOverlays
import org.umamo.ui.viewport.OverlapPickerPopup
import org.umamo.ui.viewport.OverlapState
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.UvSceneContent
import org.umamo.ui.viewport.ViewportRegionOverlay
import org.umamo.ui.viewport.gizmo.editMeshOverlaySizes
import org.umamo.ui.viewport.overlapStateFrom
import org.umamo.ui.viewport.tracksAreaPointer
import org.umamo.ui.viewport.uv.PlacementDragStatus
import org.umamo.ui.viewport.uv.UvCursorOverlay
import org.umamo.ui.viewport.uv.UvEditGizmoOverlay
import org.umamo.ui.viewport.uv.UvHudOverlay
import org.umamo.ui.viewport.uv.UvObjectGizmoOverlay
import org.umamo.ui.viewport.uv.UvPlacementSceneState
import org.umamo.ui.viewport.uv.UvPlacementSurface
import org.umamo.ui.viewport.uv.UvShownScene
import org.umamo.ui.viewport.uv.UvSpaceCamera
import org.umamo.ui.viewport.uv.atlasPageEditFrame
import org.umamo.ui.viewport.uv.publishUvScene
import org.umamo.ui.viewport.uv.restFrontRank
import org.umamo.ui.viewport.uv.sourceLayerEditFrame
import org.umamo.ui.viewport.uv.uvIslandPick
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalAreaCameraHub
import org.umamo.ui.workspace.LocalAreaOverlayHub
import org.umamo.ui.workspace.spaces.PlaceholderSpace

/*
 * The UV editor space: the body (this file), its header controls with the layer picker
 * (UvLayerPickerChip.kt), the raster drawn under the overlays (UvPageUnderlay.kt), and the view state
 * with its page and layer resolution (UvEditorViewState.kt).  The overlays and gestures the body mounts
 * are org.umamo.ui.viewport.uv.
 */

/**
 * The UV editor space: the shown surface - an atlas page, or the source layer the art was authored on -
 * drawn under its UV islands, with the session's selections shared 1:1 (Blender's UV sync selection,
 * always on - Umamo UVs are strictly per-vertex, so the viewport and the UV editor agree by
 * construction).  In Edit mode the composed [UvEditGizmoOverlay] owns the interactions: element picking
 * and box select over the shared mesh selection, and the modal G / S / R operators over the texture
 * coordinates with live GPU preview.  In Object mode the frame shows every visible island on the shown
 * surface, and [UvObjectGizmoOverlay] owns island selection - click, box, and the Alt overlap stack, writing
 * the session's object selection, so a selection made here flows out to the viewport and the outliner.
 * Middle-drag pans and the wheel zooms in both modes, through this space's own navigation loop.
 *
 * FULL VIEWPORT-SERVICE PARITY: the surface is rendered by the SAME offscreen GL engine the 2D viewport
 * uses (a per-area UV render scene, whose content is either an atlas page or a source layer's raster,
 * with the Edit-mode wireframe, or Object mode's islands and placement preview, drawn over it from the
 * scene this space publishes - UvSceneOverlay.kt),
 * blitted here by [UvPageUnderlay]; the UV camera is owned by that service, and the Compose gizmo
 * overlays lock to the frame camera so they stay glued to the (asynchronously produced) raster during
 * pan / zoom.  With no service present (Android until the GLES engine lands) the space shows a bare
 * panel, as the 2D viewport shows a plain backdrop without one - there is no CPU underlay fallback.
 *
 * The working space is the display mapping of UvDisplayMapping.kt: texel units with Y up (v = 0 is the
 * image's TOP row, so the axis flips - see that file's header).
 *
 * @param AreaScope scope The hosting area context (its id keys the render registration and gesture latches).
 */
@Composable
internal fun UvEditorSpace(scope: AreaScope) {
	val committedModel = LocalPuppet.current
	// The document as it looks RIGHT NOW: the uncommitted model while any area drags a modal gesture,
	// else the committed one.  Read here rather than only in the area that owns the gesture, because a
	// second UV editor showing the atlas page has to follow a drag happening over the source artwork
	// live, the way the 2D viewport beside it does, rather than wait for the gesture to confirm.
	//
	// Never session state: the preview carries no undo step and never marks the document dirty, so the
	// surfaces that want the document as SAVED keep reading LocalPuppet.
	val model = LocalPuppetRenderSync.current?.preview?.value ?: committedModel
	val session = LocalEditorSession.current
	val textures = LocalPuppetTextures.current
	val service = LocalPuppetViewportService.current

	// The area's texture selection and overlays, shared with the header's selector and overlays control
	// through the hosting AreaScope (header and body are sibling subtrees - spaceState is their one channel).
	// Taken ahead of the service gate, as the 2D viewport takes its view state: the header's popover shows
	// the area's grid with or without a renderer, and its mirror of the application's grid is kept here.
	val viewState = scope.spaceState(UV_EDITOR_VIEW_STATE_KEY) { UvEditorViewState() }
	ApplicationGridMirror(viewState.overlays)

	// STRICT PARITY: the UV editor renders its surface through the GL engine, exactly like the 2D viewport.
	// With no service (Android until the GLES engine lands) show a bare panel - no underlay, no
	// editing camera - as Viewport2DBody shows a plain backdrop with no host.
	if (model == null || session == null || service == null) {
		PlaceholderSpace("")
		return
	}
	val mode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	val objectSelection by session.selection.collectAsState()

	// The source-layer view, when the selector asks for one AND this document retains the artwork to
	// serve it: the active drawable's own art with its mapping recovered onto it.  Null falls the space
	// back to its page view, so choosing the mode never blanks the editor and a document with no source
	// art (a MOC3 origin) simply keeps showing pages.
	val artRasters = LocalSourceArtRasters.current
	val layerView =
		if (viewState.textureSelection is UvTextureSelection.SourceLayer) {
			resolveUvEditorLayer(model, meshSelection, objectSelection)
		} else {
			null
		}

	// The shown page: a pinned page the textures can satisfy wins page-first, else the page follows
	// the session's active drawable, falling back to the first meshed drawable so the space is never
	// blank; the precedence chain and the untextured 1x1 fallback live in resolveUvEditorPage
	// (UvEditorViewState.kt).
	val resolvedPage = resolveUvEditorPage(model, meshSelection, objectSelection, textures, viewState.textureSelection)

	// The palette's page-switch requests (uv.page.*): the executing area was resolved at dispatch
	// into the payload, so this gate is deterministic.  Mode-agnostic on purpose - reviewing pages is
	// not an Edit-mode operation.  Collected ABOVE the placeholder early-return so a document with
	// textures but nothing meshed (placeholder showing) can still pin a page into view.
	val liveEffectivePageIndex = rememberUpdatedState(resolvedPage?.pageIndex)
	val livePageCount = rememberUpdatedState(textures?.atlases?.size ?: 0)
	LaunchedEffect(session, scope.areaId) {
		session.uvPageRequests.collect { request ->
			if (request.areaId != scope.areaId) {
				return@collect
			}
			viewState.textureSelection =
				uvPageSelectionAfter(request.kind, viewState.textureSelection, liveEffectivePageIndex.value, livePageCount.value)
		}
	}
	// The display space both views share: texels of whatever is shown, with the v-flip (UvDisplayMapping).
	// One of the two resolutions must have produced a surface or there is nothing to show at all.
	val pageIndex = resolvedPage?.pageIndex
	val displayWidth = layerView?.width ?: resolvedPage?.pageWidth
	val displayHeight = layerView?.height ?: resolvedPage?.pageHeight
	if (displayWidth == null || displayHeight == null) {
		return
	}

	// The meshes drawn over the shown surface and their display-space projection: over a layer, every
	// drawable bound to it with its stored coordinates recovered into the layer's frame; over a page,
	// the Edit / Object candidate rules and the page filter (shownUvDrawables).  Remembered so
	// selection churn - which changes styling, never membership - rebuilds nothing here.
	val shownDrawables =
		remember(model, textures, layerView, pageIndex, mode, meshSelection.drawableIds) {
			if (layerView != null) {
				shownLayerDrawables(model, mode, meshSelection, layerView.layerKey)
			} else {
				shownUvDrawables(model, mode, meshSelection, textures, pageIndex)
			}
		}
	// Each shown mapping in the SHOWN surface's own frame - stored coordinates over a page, recovered
	// ones over a layer.  One derivation feeds both the display projection and the pick's alpha gate,
	// so the wireframe and the hit test can never disagree about where a mesh is.  Kept per mesh across
	// derives (UvGizmoGeometryCache), so a drive, which previews new coordinates for the moved meshes
	// alone, rebuilds those alone and hands the wireframe the same arrays for the rest.
	val geometryCache = remember(scope.areaId) { UvGizmoGeometryCache() }
	val shownUvs =
		remember(shownDrawables, model, layerView) {
			geometryCache.surfaceUvs(shownDrawables, model, layerView)
		}
	val geometries =
		remember(shownDrawables, shownUvs, displayWidth, displayHeight) {
			geometryCache.geometries(shownDrawables, shownUvs, displayWidth, displayHeight)
		}
	val liveGeometries = rememberUpdatedState(geometries)

	// The space an edit here is authored in.  Over a page the display texels ARE the stored frame; over
	// a layer the drawable's placement stands between them.  Every drawable over one layer shares that
	// placement, so the frame comes from the LAYER rather than from whichever drawables are currently
	// shown - it must not shift when Edit mode narrows the shown set to the session's meshes.  One
	// conversion therefore covers a whole edit and the shared-pivot transform modes keep meaning
	// something.  A layer whose mapping will not invert falls back to treating its own texels as the
	// frame rather than editing blind.
	val editFrame =
		remember(layerView, model.atlas, displayWidth, displayHeight) {
			val layerBinding =
				if (layerView != null) {
					model.atlasBindingForTile(AtlasTileId(layerView.layerKey))
				} else {
					null
				}
			val layerFrame =
				if (layerView != null && layerBinding != null) {
					sourceLayerEditFrame(layerBinding, layerView.width, layerView.height)
				} else {
					null
				}
			layerFrame ?: atlasPageEditFrame(displayWidth, displayHeight)
		}

	// The Object-mode island pick surface: the model's rest-pose front rank plus the CPU pick adapters
	// over the shown islands and the shown image's decoded pixels (UvIslandPickController.kt).  The
	// image is the atlas page or the source layer's artwork, and the alpha gate follows it - over
	// overlapping islands, the one with opaque art under the click on the surface actually being
	// looked at wins, and a click where no island is opaque falls back to the mesh itself.
	val frontRank = remember(model) { restFrontRank(model) }
	val shownImage =
		if (layerView != null) {
			artRasters?.rasterFor(AtlasTileId(layerView.layerKey))
		} else {
			pageIndex?.let { resolvedIndex -> textures?.atlases?.getOrNull(resolvedIndex) }
		}
	val islandPick =
		remember(geometries, frontRank, shownUvs, shownImage) {
			uvIslandPick(geometries = geometries, frontRank = frontRank, uvsById = shownUvs, image = shownImage)
		}

	// Register this area as a UV scene on the shared GL engine and follow the frame it publishes; the
	// content tracks the resolved texture selection through the scene publish below (setUvSceneContent),
	// which is also how the area switches between a page and a layer WITHOUT re-registering (a second
	// register would take a reference-counted hold this area never releases).  The camera is owned by the
	// service (pan / zoom / fit below drive it), and the frame carries the camera it was rendered at for
	// the overlay glue.  The service keeps this area's view of each page and layer apart, so following the
	// selection onto another one - or picking one - brings back the view it was left with, or fits it the
	// first time.
	//
	// Resolving the raster here is what triggers its decode, on first sight only - the store caches
	// thereafter, including its failures.
	val sceneContent =
		if (layerView != null) {
			UvSceneContent.SourceLayer(layerView.layerKey, shownImage)
		} else {
			UvSceneContent.AtlasPage(pageIndex)
		}
	// The extent a fit of this area takes in beside the surface: every shown mesh, so art moved past the
	// page edge is framed with the page.  Handed over with the content, which is what lets the engine fit a
	// surface it switches to against that surface's own meshes.
	val islandExtent = remember(geometries) { shownIslandExtent(geometries) }
	// Keyed on the service too, like the 2D viewport's registration: a slot remembered across a
	// service swap would keep collecting the disposed engine's flows and never register with the live one.
	val imageFlow = remember(scope.areaId, service) { service.registerUvScene(scope.areaId, sceneContent, islandExtent) }
	// The area's render options (its grid geometry, and whether its frames draw the grid lines and the
	// overlay) follow the overlay state the header's control edits.
	AreaOverlaysPublisher(service, scope.areaId, viewState.overlays)
	// The live service camera feeds the zoom readout: the wheel updates it immediately, where the
	// frame's camera (image?.camera) lags the raster by a few frames.
	val cameraFlow = remember(scope.areaId, service) { service.cameraFlow(scope.areaId) }
	// The area's live circle stroke, written by the Edit overlay's marquee and drawn over this area alone:
	// held here and read only by the publish below, so a stamp recomposes nothing.
	val circleStrokeState = remember(scope.areaId) { mutableStateOf<MeshSelection?>(null) }
	// The area's placement scene (the drag's scrims, crops, and moving islands, and a landing's ghost),
	// written by the Object overlay's placement gesture and read only by the publish below, so a drive
	// recomposes nothing.
	val placementSceneState = remember(scope.areaId) { UvPlacementSceneState() }
	// The scene the engine draws: the surface and its extent, with the Edit-mode wireframe of the shown
	// session meshes, or in Object mode the shown islands and the placement preview, laid on it - derived off
	// the UI thread and published as the area's content whenever it changes (UvSceneOverlay.kt).  The
	// geometry follows the preview model, so every UV area showing a dragged mesh follows the drag.
	val scrim = LocalUmamoColors.current.overlayScrim
	val scrimColor = remember(scrim) { OverlayColor(scrim.red, scrim.green, scrim.blue, scrim.alpha) }
	val shownScene =
		remember(sceneContent, islandExtent, model, geometries, frontRank, scrimColor) {
			UvShownScene(sceneContent, islandExtent, model, geometries, frontRank, scrimColor)
		}
	val liveShownScene = rememberUpdatedState(shownScene)
	val overlaySizes = rememberUpdatedState(editMeshOverlaySizes(LocalDensity.current))
	LaunchedEffect(scope.areaId, service, session) {
		publishUvScene(
			service = service,
			areaId = scope.areaId,
			session = session,
			shownScene = snapshotFlow { liveShownScene.value },
			circleStroke = snapshotFlow { circleStrokeState.value },
			sizes = snapshotFlow { overlaySizes.value },
			placement = snapshotFlow { placementSceneState.snapshot() },
		)
	}
	DisposableEffect(scope.areaId, service) {
		onDispose { service.unregister(scope.areaId) }
	}
	val image by imageFlow.collectAsState()
	val liveCamera by cameraFlow.collectAsState()
	// The UV editor's proportional influence radius, in display (texel) units, kept per surface for the
	// area's life (rememberUvProportionalRadius).  Owned here, by the overlay stack's host, because two
	// sibling overlays need it: UvEditGizmoOverlay's gesture machinery seeds and resizes it, UvHudOverlay's
	// status badge reads it.
	val proportionalRadiusDisplay =
		rememberUvProportionalRadius(scope.areaId, UvRadiusSurfaceKey(displayWidth, displayHeight, layerView?.layerKey))

	// Where the pointer last was in this area, for the pointer-addressed requests the Edit overlay answers
	// (Select Linked): tracked here, by the host, so it stays current while the overlay's own pointer loop is
	// not mounted.  Area-local, like the 2D viewport's.
	val areaPointer = remember(scope.areaId) { mutableStateOf(Offset.Zero) }

	// The overlap-picker popup's host state (the 2D viewport's pattern): the Object overlay's Alt
	// pick requests it through overlapStateFrom, the popup mounted in the content stack resolves or
	// dismisses it.  Area-local, like the anchor it carries.
	var overlap by remember(scope.areaId) { mutableStateOf<OverlapState?>(null) }

	// The placement gesture's page: Object-mode G / S / R over the shown page's placements needs the
	// page's texel size and the source art the pages recompose from.  Null over a source layer (a
	// placement has no page to move on there) and while the document retains no art; the Object
	// overlay then drops a latch with its own notice.
	val placementSurface =
		remember(layerView, pageIndex, displayWidth, displayHeight, artRasters, shownImage) {
			if (layerView == null && pageIndex != null && artRasters != null) {
				UvPlacementSurface(displayWidth, displayHeight, artRasters, shownImage)
			} else {
				null
			}
		}
	// The drag's live readout, host-owned because two sibling overlays meet on it: the Object overlay
	// writes it per pointer frame and UvHudOverlay's badge reads it.
	val placementDragStatus = remember(scope.areaId) { mutableStateOf<PlacementDragStatus?>(null) }

	// Area-death guard: a gesture latched from this area must not outlive it (corner-join, space
	// switch, workspace tab switch), or the latch strands with no overlay to drive or confirm it.
	// The overlay's own dispose effect resyncs the renderer when a capture was live, so the released
	// operator needs nothing more here (the 2D viewport's guard resyncs itself).
	DisposableEffect(scope.areaId, session) {
		onDispose { session.releaseArea(scope.areaId) }
	}

	// The view commands' seam: register this area's camera ops for its lifetime, so view.fit / 1:1 /
	// zoom / frame-selected target this UV editor when the pointer last touched it.  The ops drive the
	// SERVICE camera (the same per-area camera the pan / zoom / fit machinery the 2D viewport uses), so the
	// zoom steps honor the same viewport.zoomStep settings fed into the service.
	val areaCameraHub = LocalAreaCameraHub.current
	DisposableEffect(scope.areaId, areaCameraHub, session, service) {
		// The UV camera reads the supplier lazily each Frame Selected, so it always frames the current
		// shown geometries without re-registering on every mesh change.  Object mode narrows to the
		// SELECTED islands - the shown list is every visible island on the surface, and framing all of
		// them would just frame the surface; an empty selection yields an empty list, keeping Frame
		// Selected a no-op then.
		val ops =
			UvSpaceCamera(service, session, scope.areaId) {
				if (session.mode.value == EditorMode.Edit) {
					liveGeometries.value
				} else {
					val selectedIds =
						session.selection.value.targets
							.mapNotNull { target -> (target as? SelectionTarget.Drawable)?.id }
							.toSet()
					liveGeometries.value.filter { geometry -> geometry.drawableId in selectedIds }
				}
			}
		areaCameraHub?.register(scope.areaId, ops)
		onDispose { areaCameraHub?.unregister(scope.areaId) }
	}

	// Register the area's overlay state for its lifetime, so the shell's view.overlay.* commands flip THIS
	// UV editor's overlays when the pointer last touched it - the same hub the 2D viewport registers into.
	val overlayHub = LocalAreaOverlayHub.current
	DisposableEffect(scope.areaId, overlayHub) {
		overlayHub?.register(scope.areaId, viewState.overlays)
		onDispose { overlayHub?.unregister(scope.areaId) }
	}

	// The UV viewport's own contextual menu: right-click anywhere in the viewport for UV operations.  A
	// context menu is contextual - this holds ONLY UV ops, not area actions.  Nested in the content below,
	// it overrides the AreaLeaf area menu within the viewport (the same precedence the outliner's row menu
	// has over the area menu); the area context stays on the header.  Mirror X / Mirror Y dispatch the
	// existing uv.mirror* commands through the registry (never a hardcoded handler); rebuilt on mode change
	// (mode is observed above), so the rows enable and disable with Edit / Object mode.
	val commands = LocalCommands.current
	val mirrorEnabled = mode == EditorMode.Edit
	val uvContextItems =
		listOf(
			MenuItem.Action(
				label = stringResource(Res.string.menu_uv_mirror_x),
				onSelect = { commands.invoke("uv.mirrorU") },
				enabled = mirrorEnabled,
			),
			MenuItem.Action(
				label = stringResource(Res.string.menu_uv_mirror_y),
				onSelect = { commands.invoke("uv.mirrorV") },
				enabled = mirrorEnabled,
			),
		)

	val uiColors = LocalUmamoColors.current
	BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
		val widthPx = constraints.maxWidth
		val heightPx = constraints.maxHeight
		LaunchedEffect(widthPx, heightPx) {
			service.resize(scope.areaId, widthPx, heightPx)
		}
		ContextMenuArea(items = uvContextItems, modifier = Modifier.fillMaxSize()) {
			Box(
				modifier =
					Modifier
						.fillMaxSize()
						.background(uiColors.panelBackground)
						// Cache boundary: promote the UV editor's overlay drawing to its own layer so a sibling
						// repaint - the 2D viewport's own pan / zoom, a parameter scrub - composites this cached
						// content instead of re-rasterizing the overlays it holds.  Only a real UV change
						// re-records it.
						.graphicsLayer()
						.clipToBounds()
						.tracksAreaPointer(scope.areaId, areaPointer)
						// Navigation lives on the PARENT box, not the drawing canvas.  In Edit mode the gizmo
						// overlay is a child on top; as the parent, this loop sees the Main pass after the overlay,
						// so pan / zoom work in both modes - the 2D viewport's setup.
						.pointerInput(session, scope.areaId) {
							uvEditorNavigation(session = session, service = service, areaId = scope.areaId)
						},
			) {
				// The stack's overlays read the area's overlay visibility through LocalAreaOverlays, as the 2D
				// viewport's do: the cursor marker and the HUD chips gate themselves on it.
				CompositionLocalProvider(LocalAreaOverlays provides viewState.overlays) {
					// The underlay: the GL-rendered frame - the surface with its surround, border, and wireframe -
					// or the backdrop color before the first frame (UvPageUnderlay.kt).
					UvPageUnderlay(rendered = image)
					// The overlap picker for an ambiguous Alt click over stacked islands (the popup is its
					// own window; the anchor stays area-local).
					overlap?.let { state ->
						OverlapPickerPopup(
							anchor = state.anchor,
							entries = state.entries,
							defaultIndex = state.defaultIndex,
							onPick = { pickedId ->
								state.pick(pickedId)
								overlap = null
							},
							onDismiss = { overlap = null },
						)
					}
					// The mode-exclusive sibling overlays, each self-gated on the session's mode (the
					// viewport pair's convention, so both mount unconditionally): Object mode's island
					// selection surface (click / box / Alt-stack picking, the placement gesture, and
					// Shift+RightClick cursor placement over the session's object selection), then Edit
					// mode's interaction core (element selection, box select, and the modal G / S / R
					// operators with live GPU preview).  Both are locked to the frame camera
					// (image?.camera) for the same pan / zoom glue as the 2D viewport's overlays;
					// unconsumed input falls through to the navigation loop and the context menu.
					//
					// The SAME pair serves a page and a source layer.  What differs between the two views is
					// carried entirely by the frame and the pick they are handed - the surface's texel size,
					// the conversion back to the stored coordinates, and which image the alpha gate reads -
					// so neither overlay knows which surface it is drawing over.
					UvObjectGizmoOverlay(
						areaId = scope.areaId,
						session = session,
						geometries = geometries,
						islandPick = islandPick,
						frame = editFrame,
						camera = image?.camera,
						widthPx = widthPx,
						heightPx = heightPx,
						placementSurface = placementSurface,
						placementDragStatusState = placementDragStatus,
						placementSceneState = placementSceneState,
						onOverlapRequest = { position, candidates ->
							// The Object-mode Alt pick over a stack: picking a row replaces the object selection.
							overlap =
								overlapStateFrom(service, position, candidates) { pickedId ->
									session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(pickedId)))
								}
						},
					)
					UvEditGizmoOverlay(
						areaId = scope.areaId,
						session = session,
						geometries = geometries,
						frame = editFrame,
						camera = image?.camera,
						widthPx = widthPx,
						heightPx = heightPx,
						areaPointer = areaPointer,
						proportionalRadiusDisplayState = proportionalRadiusDisplay,
						circleStrokeState = circleStrokeState,
					)
					// Zoom Region (Shift+B): mode-agnostic and self-gated on the armed area, so it composes nothing
					// until armed.  Mounted above the gizmo overlays so an armed drag is captured over them; on
					// release it calls the area-generic service.zoomToRegion for this UV editor area.  Takes
					// the LIVE camera like the 2D viewport's mount - the overlay reads it only as its
					// area-initialized gate, never for projection.
					ViewportRegionOverlay(
						areaId = scope.areaId,
						service = service,
						session = session,
						camera = liveCamera,
						widthPx = widthPx,
						heightPx = heightPx,
					)
					// The UV cursor marker: a control's texture-space marker (not HUD chrome), present in
					// both modes like the viewport's 2D cursor, drawn above the gizmo chrome and below the
					// HUD text.  Locked to the frame camera for the same pan / zoom glue as the wireframes.
					// Present in the layer view too: the cursor is stored in ATLAS coordinates, so it converts
					// through the shown surface's frame to be drawn and converts back when placed - one shared
					// control seen in whichever space the user is working in, rather than two cursors to keep
					// in sync.
					UvCursorOverlay(
						session = session,
						frame = editFrame,
						camera = image?.camera,
						widthPx = widthPx,
						heightPx = heightPx,
					)
					// The HUD layer draws topmost, informational chrome only (draw-only, no pointer input, so
					// nothing below loses a gesture): the modal-op status badge, the active-mesh info chip,
					// and the zoom readout.  The chip uses the SAME mode-dependent resolution as the 2D
					// viewport - deliberately not this space's first-meshed page fallback - so the two
					// surfaces annotate the same mesh and the chip stays absent while nothing is selected.
					// Under a pinned page that mesh may live on ANOTHER page: the chip still names it, on
					// purpose - it annotates the session's active mesh, not this page's contents.
					UvHudOverlay(
						areaId = scope.areaId,
						session = session,
						liveCamera = liveCamera,
						proportionalRadiusDisplay = proportionalRadiusDisplay.value,
						placementDragStatus = placementDragStatus.value,
					)
				}
			}
		}
	}
}

/**
 * The UV editor's navigation pointer loop: middle-mouse drag pans and the wheel zooms toward the cursor
 * (Shift for the coarse step).  Pan and zoom drive the SERVICE camera (same as the 2D viewport) and are
 * skipped while this area owns a modal UV operator or an armed select tool - the overlay's controller owns
 * the pointer then (a wheel scroll resizes the proportional radius, not the zoom) - and while an un-armed
 * box drag is live (viewportGestureActive, area-less by design: pointer capture pins the drag's events to
 * the dragging overlay, and zooming under a live rubber-band would desync the box from the camera used at
 * release) or Zoom Region is armed here (its overlay owns the drag above).  The four-term gate is the 2D
 * viewport loop's.  A gesture latched in ANOTHER area does not block: its events never reach here, so this
 * area keeps panning and zooming during it (Blender parity).
 *
 * This loop stamps nothing about where the pointer is: the hovered surface is stamped by the hosting
 * leaf, for every space alike (see stampsHoveredSurface).
 *
 * @param EditorSession session The session whose latches gate this layer.
 * @param PuppetViewportService service The render service whose per-area camera this drives.
 * @param String areaId The UV editor area this loop serves.
 */
private suspend fun PointerInputScope.uvEditorNavigation(
	session: EditorSession,
	service: PuppetViewportService,
	areaId: String,
) {
	awaitPointerEventScope {
		var panAnchor: Offset? = null
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull() ?: continue
			if (session.activeUvOperator.value?.areaId == areaId ||
				session.activeSelectTool.value?.areaId == areaId ||
				session.viewportGestureActive.value ||
				session.zoomRegionArmedArea.value == areaId
			) {
				continue
			}
			when (event.type) {
				PointerEventType.Scroll -> {
					val shiftHeld = event.keyboardModifiers.isShiftPressed
					// AWT reroutes Shift+wheel into a horizontal scroll (delta arrives in x with y zero), so
					// read the x delta when Shift is held.  Sign convention matches y (wheel up = negative).
					val scrollSteps =
						if (change.scrollDelta.y != 0f) {
							change.scrollDelta.y
						} else if (shiftHeld) {
							change.scrollDelta.x
						} else {
							0f
						}
					if (scrollSteps != 0f) {
						// One step per notch (wheel up = negative = zoom in); Shift selects the coarse step.
						service.zoomAtCursor(
							areaId,
							zoomIn = scrollSteps < 0f,
							coarse = shiftHeld,
							cursorXpx = change.position.x,
							cursorYpx = change.position.y,
						)
						change.consume()
					}
				}

				else -> {
					if (event.buttons.isTertiaryPressed) {
						val anchor = panAnchor
						if (anchor != null) {
							val delta = change.position - anchor
							if (delta != Offset.Zero) {
								service.pan(areaId, delta.x, delta.y)
							}
						}
						panAnchor = change.position
						change.consume()
					} else {
						panAnchor = null
					}
				}
			}
		}
	}
}