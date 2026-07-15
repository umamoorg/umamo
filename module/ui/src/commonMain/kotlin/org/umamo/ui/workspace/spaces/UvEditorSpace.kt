package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshTopology
import org.umamo.edit.SelectionTarget
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalPuppetTextures
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_uv_mirror_x
import org.umamo.ui.resources.menu_uv_mirror_y
import org.umamo.ui.resources.space_uv
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.viewport.GizmoMeshGeometry
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.UvGizmoOverlay
import org.umamo.ui.viewport.UvSpaceCamera
import org.umamo.ui.viewport.ViewportRegionOverlay
import org.umamo.ui.viewport.atlasPageIndexFor
import org.umamo.ui.viewport.buildHighlightSets
import org.umamo.ui.viewport.drawMeshWireframe
import org.umamo.ui.viewport.rememberMeshEditColors
import org.umamo.ui.viewport.uvToDisplay
import org.umamo.ui.viewport.worldToScreen
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.HoveredSurface
import org.umamo.ui.workspace.HoveredSurfaceTracker
import org.umamo.ui.workspace.LocalAreaCameraHub
import org.umamo.ui.workspace.LocalHoveredSurfaceTracker
import org.umamo.ui.workspace.SpaceKind

/**
 * The UV editor space: the active drawable's atlas page drawn under its UV wireframe, with the
 * session's mesh selection shared 1:1 (Blender's UV sync selection, always on - Umamo UVs are strictly
 * per-vertex, so the viewport and the UV editor agree by construction).  In Edit mode the composed
 * [UvGizmoOverlay] owns the interactions: element picking and box select over the shared selection,
 * and the modal G / S / R operators over the texture coordinates with live GPU preview.  Object mode
 * shows the selected drawable's mapping as a read-only preview (pan / zoom only), matching the app's
 * object/edit split.  Middle-drag pans and the wheel zooms in both modes, through this space's own
 * navigation loop.
 *
 * FULL VIEWPORT-SERVICE PARITY: the atlas page underlay is rendered by the SAME offscreen GL engine the
 * 2D viewport uses (a per-area "atlas page" render scene), blitted here as the frame's [Image]; the UV
 * camera is owned by that service, and the Compose wireframe / gizmo overlays lock to the frame camera so
 * they stay glued to the (asynchronously produced) raster during pan / zoom.  With no service present
 * (Android until the GLES engine lands) the space shows the grid placeholder, exactly like the 2D
 * viewport - there is no CPU underlay fallback.
 *
 * The working space is the display mapping of UvDisplayMapping.kt: texel units with Y up (v = 0 is the
 * image's TOP row, so the axis flips - see that file's header).
 *
 * UV エディタ空間。アクティブな描画メッシュのアトラスページを 2D ビューポートと同じ GL エンジンで描画し、
 * その上に UV ワイヤーフレームを重ねる。カメラはサービスが保持する。
 *
 * @param AreaScope scope The hosting area context (its id keys the render registration and gesture latches).
 */
@Composable
internal fun UvEditorSpace(scope: AreaScope) {
	val model = LocalPuppet.current
	val session = LocalEditorSession.current
	val textures = LocalPuppetTextures.current
	val service = LocalPuppetViewportService.current
	val hoveredTracker = LocalHoveredSurfaceTracker.current
	val gizmoColors = rememberMeshEditColors()
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

	// The shown page follows the session's active drawable (Edit-mode active mesh first, then the
	// object selection), falling back to the first meshed drawable so the space is never blank.
	val activeDrawableId =
		meshSelection.activeDrawableId
			?: (objectSelection.active as? SelectionTarget.Drawable)?.id
			?: model.drawables.firstOrNull { drawable -> drawable.mesh != null }?.id
	val activeDrawable = model.drawables.firstOrNull { drawable -> drawable.id == activeDrawableId }
	if (activeDrawable?.mesh == null) {
		PlaceholderSpace(stringResource(Res.string.space_uv))
		return
	}
	val pageIndex = textures?.let { puppetTextures -> atlasPageIndexFor(activeDrawable, puppetTextures) }
	val page = pageIndex?.let { resolvedIndex -> textures.atlases.getOrNull(resolvedIndex) }
	// Untextured fallback: a 1x1 "page" turns the display mapping into the flipped unit square, so the
	// wireframe still shows (over the grid) for a drawable with no atlas entry.  Must equal the service's
	// pageContentBounds dimensions so the Compose wireframe and the GL page frame at the same camera align.
	val pageWidth = page?.width ?: 1
	val pageHeight = page?.height ?: 1

	// The meshes drawn over the page: in Edit mode every session mesh sampling this page (the active
	// one is emphasized via the highlight sets), in Object mode the selected drawable alone, read-only.
	// Meshes without an editable UV array (empty or malformed) are excluded everywhere.
	val shownDrawables =
		remember(model, textures, pageIndex, mode, meshSelection.drawableIds, objectSelection, activeDrawable) {
			val candidates =
				if (mode == EditorMode.Edit) {
					meshSelection.drawableIds.mapNotNull { drawableId -> model.drawables.firstOrNull { drawable -> drawable.id == drawableId } }
				} else {
					// Object mode is a read-only preview of the SELECTED drawables only.  With nothing
					// selected the pane shows just the atlas page (the first-meshed fallback still drives
					// which page, but draws no wireframe): rendering the fallback drawable's vertices while
					// nothing is selected reads as editable and clutters the view.
					objectSelection.targets
						.filterIsInstance<SelectionTarget.Drawable>()
						.mapNotNull { target -> model.drawables.firstOrNull { drawable -> drawable.id == target.id } }
				}
			candidates.filter { drawable ->
				val mesh = drawable.mesh
				mesh != null &&
					mesh.uvs.isNotEmpty() &&
					mesh.uvs.size == mesh.positions.size &&
					(textures == null || atlasPageIndexFor(drawable, textures) == pageIndex)
			}
		}
	val geometries =
		remember(shownDrawables, pageWidth, pageHeight) {
			shownDrawables.mapNotNull { drawable ->
				val mesh = drawable.mesh ?: return@mapNotNull null
				GizmoMeshGeometry(drawable.id, mesh.indices, MeshTopology.uniqueEdges(mesh.indices), uvToDisplay(mesh.uvs, pageWidth, pageHeight))
			}
		}
	val liveGeometries = rememberUpdatedState(geometries)

	// Register this area as an atlas-page scene on the shared GL engine and follow the frame it publishes;
	// the page tracks the active drawable via setAtlasPageIndex.  The camera is owned by the service (pan /
	// zoom / fit below drive it), and the frame carries the camera it was rendered at for the overlay glue.
	val imageFlow = remember(scope.areaId) { service.registerAtlasPage(scope.areaId, pageIndex) }
	LaunchedEffect(scope.areaId, pageIndex) { service.setAtlasPageIndex(scope.areaId, pageIndex) }
	DisposableEffect(scope.areaId) {
		onDispose { service.unregister(scope.areaId) }
	}
	val image by imageFlow.collectAsState()

	// Area-death guard: a gesture latched from this area must not outlive it (corner-join, space
	// switch, workspace tab switch), or the latch strands with no overlay to drive or confirm it.
	// The overlay's own dispose effect resyncs the renderer when a capture was live.
	DisposableEffect(scope.areaId, session) {
		onDispose {
			if (session.activeUvOperator.value?.areaId == scope.areaId) {
				session.clearUvOperator()
			}
			if (session.activeSelectTool.value?.areaId == scope.areaId) {
				session.clearSelectTool()
			}
		}
	}

	// The view commands' seam: register this area's camera ops for its lifetime, so view.fit / 1:1 /
	// zoom / frame-selected target this UV editor when the pointer last touched it.  The ops drive the
	// SERVICE camera (the same per-area camera the pan / zoom / fit machinery the 2D viewport uses), so the
	// zoom steps honor the same viewport.zoomStep settings fed into the service.
	val areaCameraHub = LocalAreaCameraHub.current
	DisposableEffect(scope.areaId, areaCameraHub, session, service) {
		// The UV camera reads liveGeometries.value lazily each Frame Selected, so it always frames the
		// current shown geometries without re-registering on every mesh change.
		val ops = UvSpaceCamera(service, session, scope.areaId) { liveGeometries.value }
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
		// The atlas page's on-screen rectangle: the full UV tile (display space [0, 0]-[pageWidth, pageHeight])
		// projected through the frame's camera, so it tracks pan / zoom glued to the rendered texture.  The grid
		// + texture raster is clipped to it and the 1.dp frame drawn around it.
		val rendered = image
		val textureRect =
			rendered?.let { frame ->
				val cornerLowerLeft = worldToScreen(0f, 0f, frame.camera, IntSize(widthPx, heightPx))
				val cornerUpperRight = worldToScreen(pageWidth.toFloat(), pageHeight.toFloat(), frame.camera, IntSize(widthPx, heightPx))
				Rect(
					left = minOf(cornerLowerLeft.x, cornerUpperRight.x),
					top = minOf(cornerLowerLeft.y, cornerUpperRight.y),
					right = maxOf(cornerLowerLeft.x, cornerUpperRight.x),
					bottom = maxOf(cornerLowerLeft.y, cornerUpperRight.y),
				)
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
						// so pan / zoom and area stamping work in both modes - the 2D viewport's setup.
						.pointerInput(session, scope.areaId) {
							uvEditorNavigation(
								session = session,
								service = service,
								areaId = scope.areaId,
								hoveredTracker = hoveredTracker,
							)
						},
			) {
				if (rendered == null) {
					// Pre-first-frame placeholder: the themed grid backdrop until the GL frame lands and the
					// framed texture takes over.
					EmptyViewportBackdrop()
				} else {
					// The atlas page rendered by the GL engine - upright, correctly sampled, sharing the puppet's
					// texture.  Its grid + texture raster is clipped to the page rect so the grid does not spill
					// past the texture onto the panel elevation.
					Image(
						bitmap = rendered.bitmap,
						contentDescription = null,
						modifier =
							Modifier.fillMaxSize().drawWithContent {
								val rect = textureRect
								if (rect != null) {
									clipRect(rect.left, rect.top, rect.right, rect.bottom) { this@drawWithContent.drawContent() }
								} else {
									drawContent()
								}
							},
						contentScale = ContentScale.FillBounds,
					)
					// The 1.dp frame around the texture (the page-elevation border of the diagram).
					Canvas(modifier = Modifier.fillMaxSize()) {
						val rect = textureRect ?: return@Canvas
						drawRect(
							color = uiColors.panelBorder,
							topLeft = Offset(rect.left, rect.top),
							size = Size(rect.width, rect.height),
							style = Stroke(width = 1.dp.toPx()),
						)
					}
				}
				// Object mode draws the mapping here, without selection emphasis - a read-only preview posed from
				// the FRAME camera so it lags with the GL page during pan / zoom.  Unclipped, so UVs outside the
				// page tile stay visible.  Edit mode's wireframes (selection highlights, previews) belong to the
				// overlay below.
				Canvas(modifier = Modifier.fillMaxSize()) {
					val drawCamera = image?.camera ?: return@Canvas
					if (mode != EditorMode.Edit) {
						for (geometry in geometries) {
							// The object-mode preview is the Blender object-overlay style: edges + a faint face fill,
							// no vertex or face dots (objectOverlay ignores selectMode's handle rules).
							drawMeshWireframe(
								positions = geometry.positions,
								indices = geometry.indices,
								edges = geometry.edges,
								highlight = buildHighlightSets(emptySet(), null, meshSelection.selectMode, geometry.indices),
								selectMode = meshSelection.selectMode,
								colors = gizmoColors,
								camera = drawCamera,
								size = IntSize(widthPx, heightPx),
								objectOverlay = true,
							)
						}
					}
				}
				// The Edit-mode interaction core: element selection, box select, and the modal G / S / R
				// operators with live GPU preview.  Composed only in Edit mode, so the Object-mode preview
				// stays read-only and input falls through to the navigation loop untouched.  Locked to the frame
				// camera (image?.camera) for the same pan/zoom glue as the 2D viewport's overlays.
				if (mode == EditorMode.Edit) {
					UvGizmoOverlay(
						areaId = scope.areaId,
						session = session,
						geometries = geometries,
						pageWidth = pageWidth,
						pageHeight = pageHeight,
						camera = image?.camera,
						widthPx = widthPx,
						heightPx = heightPx,
					)
				}
				// Zoom Region (Shift+B): mode-agnostic and self-gated on the armed area, so it composes nothing
				// until armed.  Mounted last so an armed drag is captured above the gizmo overlay; on release it
				// calls the area-generic service.zoomToRegion for this UV atlas-page area.
				ViewportRegionOverlay(
					areaId = scope.areaId,
					service = service,
					session = session,
					camera = image?.camera,
					widthPx = widthPx,
					heightPx = heightPx,
				)
			}
		}
	}
}

/**
 * The UV editor's navigation pointer loop: middle-mouse drag pans, the wheel zooms toward the cursor
 * (Shift for the coarse step), and any non-Exit pointer activity stamps the shell's hovered-surface
 * tracker so keyboard commands (G / S / R and view commands) target this area at dispatch time.  Pan and
 * zoom drive the SERVICE camera (same as the 2D viewport) and are skipped while this area owns a modal UV
 * operator or an armed select tool - the overlay's controller owns the pointer then (a wheel scroll
 * resizes the proportional radius, not the zoom).  A gesture latched in ANOTHER area does not block: its
 * events never reach here, so this area keeps panning and zooming during it (Blender parity).
 *
 * Unlike the 2D viewport loop this does NOT stamp service.activeAreaId: keyboard UV view commands route
 * through the hovered-surface tracker, and leaving activeAreaId to the 2D viewports keeps the dispatch-time
 * object / select latch contract (which resolves activeViewportArea) intact.
 *
 * @param EditorSession session The session whose latches gate this layer.
 * @param PuppetViewportService service The render service whose per-area camera this drives.
 * @param String areaId The UV editor area this loop serves.
 * @param HoveredSurfaceTracker? hoveredTracker The shell's last-touched-surface tracker, or null.
 */
private suspend fun PointerInputScope.uvEditorNavigation(
	session: EditorSession,
	service: PuppetViewportService,
	areaId: String,
	hoveredTracker: HoveredSurfaceTracker?,
) {
	awaitPointerEventScope {
		var panAnchor: Offset? = null
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull() ?: continue
			if (event.type != PointerEventType.Exit) {
				hoveredTracker?.lastTouched = HoveredSurface(areaId, SpaceKind.UvEditor)
			}
			if (session.activeUvOperator.value?.areaId == areaId ||
				session.activeSelectTool.value?.areaId == areaId
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
