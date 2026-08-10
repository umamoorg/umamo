package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLayerTextures
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalPuppetTextures
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.model.OverlapPickerPopup
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_uv_mirror_x
import org.umamo.ui.resources.menu_uv_mirror_y
import org.umamo.ui.resources.space_uv
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.viewport.OverlapState
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.UvCursorOverlay
import org.umamo.ui.viewport.UvEditGizmoOverlay
import org.umamo.ui.viewport.UvHudOverlay
import org.umamo.ui.viewport.UvLayerOverlay
import org.umamo.ui.viewport.UvObjectGizmoOverlay
import org.umamo.ui.viewport.UvSceneContent
import org.umamo.ui.viewport.UvSpaceCamera
import org.umamo.ui.viewport.ViewportRegionOverlay
import org.umamo.ui.viewport.overlapStateFrom
import org.umamo.ui.viewport.restFrontRank
import org.umamo.ui.viewport.uvIslandPick
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalAreaCameraHub

/**
 * The UV editor space: the shown atlas page drawn under its UV islands, with the session's selections
 * shared 1:1 (Blender's UV sync selection, always on - Umamo UVs are strictly per-vertex, so the
 * viewport and the UV editor agree by construction).  In Edit mode the composed [UvEditGizmoOverlay]
 * owns the interactions: element picking and box select over the shared mesh selection, and the modal
 * G / S / R operators over the texture coordinates with live GPU preview.  In Object mode
 * [UvObjectGizmoOverlay] draws every visible island on the page and owns island selection - click,
 * box, and the Alt overlap stack, writing the session's object selection, so a selection made here
 * flows out to the viewport and the outliner.  Middle-drag pans and the wheel zooms in both modes,
 * through this space's own navigation loop.
 *
 * FULL VIEWPORT-SERVICE PARITY: the atlas page underlay is rendered by the SAME offscreen GL engine the
 * 2D viewport uses (a per-area "atlas page" render scene), blitted here by [UvPageUnderlay]; the UV
 * camera is owned by that service, and the Compose wireframe / gizmo overlays lock to the frame camera so
 * they stay glued to the (asynchronously produced) raster during pan / zoom.  With no service present
 * (Android until the GLES engine lands) the space shows the grid placeholder, exactly like the 2D
 * viewport - there is no CPU underlay fallback.
 *
 * The working space is the display mapping of UvDisplayMapping.kt: texel units with Y up (v = 0 is the
 * image's TOP row, so the axis flips - see that file's header).
 *
 * @param AreaScope scope The hosting area context (its id keys the render registration and gesture latches).
 */
@Composable
internal fun UvEditorSpace(scope: AreaScope) {
	val model = LocalPuppet.current
	val session = LocalEditorSession.current
	val textures = LocalPuppetTextures.current
	val service = LocalPuppetViewportService.current
	if (model == null || session == null) {
		PlaceholderSpace(stringResource(Res.string.space_uv))
		return
	}
	// STRICT PARITY: the UV editor renders its page through the GL engine, exactly like the 2D viewport.
	// With no service (Android until the GLES engine lands) show the grid placeholder - no underlay, no
	// editing camera - mirroring Viewport2DBody's null-host branch.
	if (service == null) {
		EmptyViewportBackdrop()
		return
	}
	val mode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	val objectSelection by session.selection.collectAsState()

	// The area's texture selection, shared with the header's selector through the hosting AreaScope
	// (header and body are sibling subtrees - spaceState is their one channel).
	val viewState = scope.spaceState(UV_EDITOR_VIEW_STATE_KEY) { UvEditorViewState() }

	// The source-layer view, when the selector asks for one AND this document retains the artwork to
	// serve it: the active drawable's own art with its mapping recovered onto it.  Null falls the space
	// back to its page view, so choosing the mode never blanks the editor and a document with no source
	// art (a MOC3 origin) simply keeps showing pages.
	val layers = LocalLayerTextures.current
	val layerView =
		if (viewState.textureSelection is UvTextureSelection.SourceLayer) {
			resolveUvEditorLayer(model, meshSelection, objectSelection, layers)
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
		PlaceholderSpace(stringResource(Res.string.space_uv))
		return
	}

	// The meshes drawn over the shown surface and their display-space projection: over a layer, every
	// drawable bound to it with its stored coordinates recovered into the layer's frame; over a page,
	// the Edit / Object candidate rules and the page filter (shownUvDrawables).  Remembered so
	// selection churn - which changes styling, never membership - rebuilds nothing here.
	val shownDrawables =
		remember(model, textures, layers, layerView, pageIndex, mode, meshSelection.drawableIds) {
			if (layerView != null && layers != null) {
				shownLayerDrawables(model, layers, layerView.layerKey)
			} else {
				shownUvDrawables(model, mode, meshSelection, textures, pageIndex)
			}
		}
	val geometries =
		remember(shownDrawables, layers, layerView, displayWidth, displayHeight) {
			if (layerView != null && layers != null) {
				layerGizmoGeometries(shownDrawables, layers, layerView.width, layerView.height)
			} else {
				uvGizmoGeometries(shownDrawables, displayWidth, displayHeight)
			}
		}
	val liveGeometries = rememberUpdatedState(geometries)

	// The Object-mode island pick surface: the model's rest-pose front rank plus the CPU pick
	// adapters over the shown islands and the page's decoded pixels (UvIslandPick.kt).
	val frontRank = remember(model) { restFrontRank(model) }
	val islandPick =
		remember(shownDrawables, geometries, frontRank, pageIndex, textures) {
			uvIslandPick(
				shownDrawables = shownDrawables,
				geometries = geometries,
				frontRank = frontRank,
				page = pageIndex?.let { resolvedIndex -> textures?.atlases?.getOrNull(resolvedIndex) },
			)
		}

	// Register this area as a UV scene on the shared GL engine and follow the frame it publishes; the
	// content tracks the resolved texture selection via setUvSceneContent, which is also how the area
	// switches between a page and a layer WITHOUT re-registering (a second register would take a
	// reference-counted hold this area never releases).  The camera is owned by the service (pan / zoom /
	// fit below drive it), and the frame carries the camera it was rendered at for the overlay glue.
	//
	// Resolving the raster here is what triggers its decode, on first sight only - the store caches
	// thereafter, including its failures.
	val sceneContent =
		if (layerView != null) {
			UvSceneContent.SourceLayer(layers?.rasterFor(layerView.layerKey))
		} else {
			UvSceneContent.AtlasPage(pageIndex)
		}
	val imageFlow = remember(scope.areaId) { service.registerUvScene(scope.areaId, sceneContent) }
	// The live service camera feeds the zoom readout: the wheel updates it immediately, where the
	// frame's camera (image?.camera) lags the raster by a few frames.
	val cameraFlow = remember(scope.areaId) { service.cameraFlow(scope.areaId) }
	LaunchedEffect(scope.areaId, sceneContent) { service.setUvSceneContent(scope.areaId, sceneContent) }
	DisposableEffect(scope.areaId) {
		onDispose { service.unregister(scope.areaId) }
	}
	val image by imageFlow.collectAsState()
	val liveCamera by cameraFlow.collectAsState()
	// The UV editor's proportional influence radius, in display (texel) units.  The session's
	// radiusWorld is scaled for the puppet canvas and means nothing on an atlas page, so only the
	// falloff curve and Connected Only are shared; the radius seeds from the page size on first use
	// and survives across gestures (the circle-select remembered-radius pattern).  Owned here, by the
	// overlay stack's host, because two sibling overlays need it: UvEditGizmoOverlay's gesture machinery
	// seeds and resizes it, UvHudOverlay's status badge reads it.
	val proportionalRadiusDisplay = remember(scope.areaId) { mutableStateOf<Float?>(null) }

	// The overlap-picker popup's host state (the 2D viewport's pattern): the Object overlay's Alt
	// pick requests it through overlapStateFrom, the popup mounted in the content stack resolves or
	// dismisses it.  Area-local, like the anchor it carries.
	var overlap by remember(scope.areaId) { mutableStateOf<OverlapState?>(null) }

	// Area-death guard: a gesture latched from this area must not outlive it (corner-join, space
	// switch, workspace tab switch), or the latch strands with no overlay to drive or confirm it.
	// The overlay's own dispose effect resyncs the renderer when a capture was live.  Zoom Region
	// disarms too - an armed flag naming a dead area would never resolve (the 2D viewport's guard).
	DisposableEffect(scope.areaId, session) {
		onDispose {
			if (session.activeUvOperator.value?.areaId == scope.areaId) {
				session.clearUvOperator()
			}
			if (session.activeSelectTool.value?.areaId == scope.areaId) {
				session.clearSelectTool()
			}
			if (session.zoomRegionArmedArea.value == scope.areaId) {
				session.disarmZoomRegion()
			}
		}
	}

	// The view commands' seam: register this area's camera ops for its lifetime, so view.fit / 1:1 /
	// zoom / frame-selected target this UV editor when the pointer last touched it.  The ops drive the
	// SERVICE camera (the same per-area camera the pan / zoom / fit machinery the 2D viewport uses), so the
	// zoom steps honor the same viewport.zoomStep settings fed into the service.
	val areaCameraHub = LocalAreaCameraHub.current
	DisposableEffect(scope.areaId, areaCameraHub, session, service) {
		// The UV camera reads the supplier lazily each Frame Selected, so it always frames the current
		// shown geometries without re-registering on every mesh change.  Object mode narrows to the
		// SELECTED islands - the shown list is every visible island on the page, and framing all of
		// them would just frame the page; an empty selection yields an empty list, keeping Frame
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
						// content instead of re-rasterizing the wireframe.  Only a real UV change re-records it.
						.graphicsLayer()
						.clipToBounds()
						// Navigation lives on the PARENT box, not the drawing canvas.  In Edit mode the gizmo
						// overlay is a child on top; as the parent, this loop sees the Main pass after the overlay,
						// so pan / zoom work in both modes - the 2D viewport's setup.
						.pointerInput(session, scope.areaId) {
							uvEditorNavigation(session = session, service = service, areaId = scope.areaId)
						},
			) {
				// The page underlay: the GL-rendered atlas page clipped to its on-screen tile with the
				// 1.dp frame around it, or the grid placeholder before the first frame (UvPageUnderlay.kt).
				UvPageUnderlay(
					rendered = image,
					pageWidth = displayWidth,
					pageHeight = displayHeight,
					widthPx = widthPx,
					heightPx = heightPx,
				)
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
				if (layerView != null) {
					// The layer view is READ-ONLY: it draws the recovered mapping over the artwork and
					// installs no pointer input, so the interactive overlays below are not mounted at all
					// rather than being gated internally.  Editing here authors the vertex-to-layer
					// mapping, which has to write back through the drawable's atlas placement to reach the
					// stored coordinates - a different edit from the page view's, and one worth landing only
					// after the recovery it depends on can be trusted by eye.
					UvLayerOverlay(
						session = session,
						geometries = geometries,
						camera = image?.camera,
						widthPx = widthPx,
						heightPx = heightPx,
					)
				} else {
					// The mode-exclusive sibling overlays, each self-gated on the session's mode (the
					// viewport pair's convention, so both mount unconditionally): Object mode's island
					// selection surface (every visible island drawn, click / box / Alt-stack picking and
					// Shift+RightClick cursor placement over the session's object selection), then Edit
					// mode's interaction core (element selection, box select, and the modal G / S / R
					// operators with live GPU preview).  Both are locked to the frame camera
					// (image?.camera) for the same pan / zoom glue as the 2D viewport's overlays;
					// unconsumed input falls through to the navigation loop and the context menu.
					UvObjectGizmoOverlay(
						areaId = scope.areaId,
						session = session,
						geometries = geometries,
						islandPick = islandPick,
						pageWidth = displayWidth,
						pageHeight = displayHeight,
						camera = image?.camera,
						widthPx = widthPx,
						heightPx = heightPx,
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
						pageWidth = displayWidth,
						pageHeight = displayHeight,
						camera = image?.camera,
						widthPx = widthPx,
						heightPx = heightPx,
						proportionalRadiusDisplayState = proportionalRadiusDisplay,
					)
				}
				// Zoom Region (Shift+B): mode-agnostic and self-gated on the armed area, so it composes nothing
				// until armed.  Mounted above the gizmo overlays so an armed drag is captured over them; on
				// release it calls the area-generic service.zoomToRegion for this UV atlas-page area.  Takes
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
				// Absent over a layer: the cursor is stored in ATLAS coordinates, so drawing it against a
				// layer's frame would place it somewhere it does not mean.
				if (layerView == null) {
					UvCursorOverlay(
						session = session,
						pageWidth = displayWidth,
						pageHeight = displayHeight,
						camera = image?.camera,
						widthPx = widthPx,
						heightPx = heightPx,
					)
				}
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
				)
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