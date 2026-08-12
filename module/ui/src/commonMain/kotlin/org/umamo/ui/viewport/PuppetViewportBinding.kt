package org.umamo.ui.viewport

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.GridConfig
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.render.GridColors
import org.umamo.render.LayerRasterSet
import org.umamo.render.LayerTextures
import org.umamo.render.PuppetTextures
import org.umamo.render.buildLayerRasterSet
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.storage.UmamoLog
import org.umamo.ui.LocalSettings
import org.umamo.ui.model.DrawableThumbnailProvider
import org.umamo.ui.model.OverlapPickerPopup
import org.umamo.ui.model.PuppetRenderSync
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.umamoPointerIcon
import org.umamo.ui.workspace.ViewportHost

/**
 * The viewport host plus its render service and preview seams, returned together so the app can inject
 * the host into the editor shell and provide the service / thumbnails / render-sync into their locals.
 * The per-area camera controllers are no longer carried here - each viewport / UV leaf registers its own
 * into the shared AreaCameraHub, and the view commands resolve the hovered area through that hub.
 *
 * @property ViewportHost host The Viewport2D host injected into the editor shell.
 * @property DrawableThumbnailProvider thumbnails Art-mesh previews for the Outliner hover (LocalDrawableThumbnails).
 * @property PuppetRenderSync renderSync Streams transient preview models to the renderer (LocalPuppetRenderSync).
 * @property PuppetViewportService service The render service itself (LocalPuppetViewportService), so the UV
 *           editor can register an atlas-page area and drive its camera through the same engine.
 */
class PuppetViewportBinding(
	val host: ViewportHost,
	val thumbnails: DrawableThumbnailProvider,
	val renderSync: PuppetRenderSync,
	val service: PuppetViewportService,
)

/**
 * Builds a [PuppetViewportBinding] backed by a [PuppetViewportService]: the puppet renders on the
 * engine's own thread and shows as a lightweight Compose `Image` with a per-area pan/zoom camera, so the
 * viewport is ordinary Compose content (the zoom readout - and future gizmos - layer over it correctly).
 * The service is created through [serviceFactory], owned for the life of this composition (one document),
 * and disposed when it leaves; viewport areas register / resize / navigate against it by id.
 *
 * @param PuppetModel puppet The rig to render (the document's model at open; the service builds from it).
 * @param PuppetTextures textures The atlas page(s).
 * @param LiveParams liveParams The shared parameter hand-off.
 * @param EditorSession session The per-document session (its selection drives picking + tint, its model
 *   drives the visibility re-render).
 * @param PuppetViewportServiceFactory serviceFactory Creates (and starts) the platform render service.
 * @return PuppetViewportBinding The host + camera controller the shell and app wire up.
 */
@Composable
fun rememberPuppetViewportHost(
	puppet: PuppetModel,
	textures: PuppetTextures,
	layers: LayerTextures,
	liveParams: LiveParams,
	session: EditorSession,
	serviceFactory: PuppetViewportServiceFactory,
): PuppetViewportBinding {
	val service =
		remember(puppet, textures, liveParams) {
			serviceFactory(puppet, textures, liveParams)
		}
	DisposableEffect(service) {
		onDispose { service.dispose() }
	}
	// Bridge the session's selection to the render thread: push the selected drawable ids (the only kind the
	// viewport tints) plus the active (last-selected) one, so it re-renders the tint with the active drawable
	// distinguished from the rest of a multi-selection. The session is the source of truth, so this also
	// follows an undo/redo that changes the selection. Mirrors how settings/liveparams reach the render thread.
	//
	// Edit mode suppresses the tint entirely (an empty set, no active): the object selection still holds the
	// drawable being edited, but the highlight is object-mode chrome that would fight the mesh gizmo overlay.
	// Gated on the mode exactly like the pose override below, so entering Edit clears the tint and leaving
	// restores it with no stash - session.selection is never touched.
	LaunchedEffect(service, session) {
		combine(session.selection, session.mode, session.previewSelection) { selection, mode, previewSelection ->
			when {
				mode == EditorMode.Edit -> emptySet<DrawableId>() to null
				// An in-flight object circle stroke paints a transient preview; while it is live the tint shows
				// exactly what the stroke has painted (previewSelection) with no active drawable yet, so drawables
				// light up plain under the brush before the stroke commits.
				previewSelection != null -> previewSelection to null
				// Otherwise the committed object selection's drawables tint as usual, with its active
				// (last-selected) drawable tinted apart. A Part/Deformer active target yields null, so every
				// selected drawable tints plain - correct, since only drawables are tinted.
				else -> {
					val selectedDrawableIds =
						selection.targets.filterIsInstance<SelectionTarget.Drawable>().map { it.id }.toSet()
					val activeDrawableId = (selection.active as? SelectionTarget.Drawable)?.id
					selectedDrawableIds to activeDrawableId
				}
			}
		}
			.distinctUntilChanged()
			.collect { (drawableIds, activeDrawableId) ->
				service.setSelection(drawableIds)
				service.setActiveSelection(activeDrawableId)
			}
	}
	// Bridge the session's model to the render thread on every committed edit or undo/redo: push the whole
	// model (so a layer reorder re-sorts the draw order) and the resolved Parts-panel visibility cascade (so
	// an eyeball toggle hides / re-shows art). Edit mode renders the same model as Object mode — the gizmo
	// overlay projects itself onto the real deformed geometry, so there is no edit-view transform here.
	LaunchedEffect(service, session) {
		session.model.collect { model ->
			service.setModel(model)
			service.setShownDrawables(model.visibleDrawableIds())
		}
	}
	// Prepare the artwork the puppet displays from, off both the UI and the render threads, and hand it
	// over whole.  Rebuilt when the mode flips or the drawable set changes (a duplicate needs its own
	// mapping), reusing already-decoded images so an edit does not re-decode the document.  collectLatest
	// abandons an in-flight preparation if the mode flips again mid-decode.
	//
	// Until a set lands the service holds EMPTY and the puppet displays from its atlas, which is what
	// makes the switch atomic rather than a puppet resolving piece by piece.
	LaunchedEffect(service, layers) {
		var prepared = LayerRasterSet.EMPTY
		session.model
			.map { model -> model.rendersFromSourceLayers to model.drawables.map { drawable -> drawable.id } }
			.distinctUntilChanged()
			.collectLatest { (fromSourceLayers, _) ->
				if (!fromSourceLayers || layers.isEmpty) {
					prepared = LayerRasterSet.EMPTY
					service.setSourceLayerRasters(LayerRasterSet.EMPTY)
				} else {
					val model = session.model.value
					val reuse = prepared
					val next = withContext(Dispatchers.Default) { buildLayerRasterSet(model, layers, reuse) }
					prepared = next
					service.setSourceLayerRasters(next)
					// A partly-recoverable document displays from a mix, which must not read as a clean
					// switch.  The count goes to the log because the notice carries no arguments; the
					// message's job is only to stop the mix from being silent.
					if (next.unresolvedDrawableCount > 0) {
						UmamoLog.warn(
							"source-artwork display: ${next.unresolvedDrawableCount} drawable(s) have no usable artwork and stay on the atlas",
						)
						session.emitNotice("notice.display.partialSourceArtwork")
					}
				}
			}
	}
	// Mirror the session's pose into the render-thread hand-off so undo / redo (and any committed scrub)
	// re-poses the viewport. Mid-drag previews already take the faster direct path (the Parameters panel
	// writes this same volatile through LiveParamsAdapter.preview), so this fires only at gesture
	// boundaries and on undo, not per frame — and a commit re-publishes the identical map, a no-op.
	//
	// Edit mode overrides the DISPLAYED pose to neutral (an empty map — the renderer falls back to every
	// parameter's default): Edit mode edits the neutral state of the base mesh, so the whole puppet snaps
	// to rest for its duration. Display-only by construction — session.pose is never touched, so leaving
	// Edit mode restores the Object-mode pose with no stash. The parameter panel is locked while in Edit
	// mode, so no preview write can bypass this override.
	LaunchedEffect(service, session) {
		combine(session.pose, session.mode) { pose, mode ->
			if (mode == EditorMode.Edit) emptyMap() else pose
		}.collect { effectivePose -> liveParams.values = effectivePose }
	}
	// Pending unkeyed channel edits mirror the same way, and are suppressed in Edit mode for the same
	// reason the pose is: Edit mode shows the neutral base, so an in-flight channel edit must not tint it.
	// Publishing them into the same volatile hand-off is what makes a typed-but-unkeyed value show in the
	// viewport at all - it is session state, so it can never reach the renderer inside the model.
	LaunchedEffect(service, session) {
		combine(session.pendingChannelEdits, session.mode) { pending, mode ->
			if (mode == EditorMode.Edit) emptyMap() else pending
		}.collect { effectiveOverrides -> liveParams.channelOverrides = effectiveOverrides }
	}
	// Feed the zoom-increment settings into the service and keep them live as settings change; the keys,
	// defaults, and highlight parser are shared with the preferences window via ViewportSettings.
	val settings = LocalSettings.current
	LaunchedEffect(service, settings, session) {
		fun applyViewportSettings() {
			service.zoomStepPercent =
				(settings.getDouble(ViewportSettings.ZOOM_STEP_KEY) ?: ViewportSettings.ZOOM_STEP_DEFAULT).toFloat()
			service.zoomStepCoarsePercent =
				(
					settings.getDouble(ViewportSettings.ZOOM_STEP_COARSE_KEY)
						?: ViewportSettings.ZOOM_STEP_COARSE_DEFAULT
				).toFloat()
			service.supersampleEnabled =
				settings.getBoolean(ViewportSettings.SUPERSAMPLE_KEY) ?: ViewportSettings.SUPERSAMPLE_DEFAULT
			service.supersampleWhileResizing =
				settings.getBoolean(ViewportSettings.SUPERSAMPLE_WHILE_RESIZING_KEY)
					?: ViewportSettings.SUPERSAMPLE_WHILE_RESIZING_DEFAULT
			val (red, green, blue) = parseSelectionHighlightColor(settings.getString(ViewportSettings.SELECTION_HIGHLIGHT_KEY))
			service.setSelectionHighlightColor(red, green, blue)
			val (activeRed, activeGreen, activeBlue) =
				parseSelectionHighlightColor(settings.getString(ViewportSettings.ACTIVE_SELECTION_HIGHLIGHT_KEY))
			service.setActiveSelectionHighlightColor(activeRed, activeGreen, activeBlue)
			// Resolve the global-default grid geometry into the session, the single source of truth the
			// snap commands and the renderer both read.  A stored per-file value takes precedence here once
			// the UMA format lands; formats that do not store grid info (CMO3) keep this default.
			val gridScale =
				(settings.getDouble(ViewportSettings.GRID_SCALE_KEY) ?: ViewportSettings.GRID_SCALE_DEFAULT).toFloat()
			val gridSubdivisions =
				settings.getInt(ViewportSettings.GRID_SUBDIVISIONS_KEY) ?: ViewportSettings.GRID_SUBDIVISIONS_DEFAULT
			session.setGridConfig(GridConfig(gridScale, gridSubdivisions))
		}
		applyViewportSettings()
		settings.changes.collect { key ->
			if (key.startsWith("viewport.")) {
				applyViewportSettings()
			}
		}
	}
	// Feed the per-document grid geometry (the session's single source of truth, resolved from settings /
	// per-file) into the render service so the drawn backdrop grid matches the snap increment.
	LaunchedEffect(service, session) {
		session.gridConfig.collect { config -> service.gridConfig = config }
	}
	// Feed the themed grid-backdrop colors into the service and keep them live: LocalUmamoColors already
	// resolves the active scheme (including "system"), so a theme switch recomposes with new colors and this
	// effect re-pushes them, re-tinting the viewport backdrop. Compose Color components are 0..1 sRGB, exactly
	// what the grid shader mixes.
	val gridPalette = LocalUmamoColors.current
	LaunchedEffect(service, gridPalette) {
		service.gridColors =
			GridColors(
				backgroundRed = gridPalette.viewportGridBackground.red,
				backgroundGreen = gridPalette.viewportGridBackground.green,
				backgroundBlue = gridPalette.viewportGridBackground.blue,
				majorRed = gridPalette.viewportGridLineMajor.red,
				majorGreen = gridPalette.viewportGridLineMajor.green,
				majorBlue = gridPalette.viewportGridLineMajor.blue,
				minorRed = gridPalette.viewportGridLineMinor.red,
				minorGreen = gridPalette.viewportGridLineMinor.green,
				minorBlue = gridPalette.viewportGridLineMinor.blue,
			)
	}
	return remember(service, session) {
		val host =
			object : ViewportHost {
				@Composable
				override fun Viewport2D(areaId: String, modifier: Modifier) {
					val imageFlow = remember(areaId) { service.register(areaId) }
					val cameraFlow = remember(areaId) { service.cameraFlow(areaId) }
					DisposableEffect(areaId) {
						onDispose { service.unregister(areaId) }
					}
					// The area-death cleanup: a leaf can leave composition MID-GESTURE (a corner-join, a
					// space switch via the header dropdown, a workspace tab switch), which cancels the
					// overlay's latch effect WITHOUT running its teardown branch - stranding the renderer
					// on the un-committed preview and orphaning the latch.  Cancel anything this area
					// initiated and resync the raster to the committed model.
					DisposableEffect(areaId, session) {
						onDispose {
							if (session.activeMeshOperator.value?.areaId == areaId ||
								session.activeObjectOperator.value?.areaId == areaId
							) {
								session.clearMeshOperator()
								session.clearObjectOperator()
								service.setModel(session.model.value)
							}
							if (session.activeSelectTool.value?.areaId == areaId) {
								session.clearSelectTool()
							}
							if (session.zoomRegionArmedArea.value == areaId) {
								session.disarmZoomRegion()
							}
						}
					}
					val image by imageFlow.collectAsState()
					val camera by cameraFlow.collectAsState()
					// Keyed on the cursor, not remembered bare: this composable recomposes per rendered frame
					// (image), so the lookup is worth caching - but the cursor set is documented as gaining a
					// per-theme override later, and a keyless remember would then serve stale art forever.
					val panCursor = LocalUmamoCursors.nsewScroll
					val grabCursor = remember(panCursor) { umamoPointerIcon(panCursor) }
					var panning by remember(areaId) { mutableStateOf(false) }
					var overlap by remember(areaId) { mutableStateOf<OverlapState?>(null) }
					BoxWithConstraints(modifier = modifier.fillMaxSize()) {
						val widthPx = constraints.maxWidth
						val heightPx = constraints.maxHeight
						LaunchedEffect(widthPx, heightPx) {
							service.resize(areaId, widthPx, heightPx)
						}
						Box(
							modifier =
								Modifier
									.fillMaxSize()
									.pointerHoverIcon(if (panning) grabCursor else PointerIcon.Default)
									.pointerInput(areaId) {
										viewportNavigation(service, areaId, session) { panning = it }
									},
						) {
							image?.let { rendered ->
								Image(
									bitmap = rendered.bitmap,
									contentDescription = null,
									modifier = Modifier.fillMaxSize(),
									contentScale = ContentScale.FillBounds,
								)
							}
							overlap?.let { state ->
								OverlapPickerPopup(
									anchor = state.anchor,
									entries = state.entries,
									defaultIndex = state.defaultIndex,
									onPick = { id ->
										state.pick(id)
										overlap = null
									},
									onDismiss = { overlap = null },
								)
							}
							// Edit-mode vertex gizmos draw over the puppet image; the overlay self-gates on Edit
							// mode with an active drawable, so it is inert (and passes input through) otherwise.
							// Project AND pose with the DISPLAYED frame, not the live state: the raster is produced
							// asynchronously and lands a few frames behind. Locking the overlay to the frame's
							// camera keeps the mesh glued to the art during pan/zoom; locking its geometry to the
							// frame's model keeps the wireframe glued to the art during a vertex edit (both lag the
							// gesture together as one unit instead of racing ahead). camera and model come from the
							// same image in one composition, so they are always the same frame. In a static view
							// at rest the frame equals the live state, so there is no lag.
							ViewportEditGizmoOverlay(
								areaId = areaId,
								service = service,
								session = session,
								camera = image?.camera,
								frameModel = image?.model,
								widthPx = widthPx,
								heightPx = heightPx,
								onOverlapRequest = { position, candidates ->
									// Edit mode's Alt+Q over a stack: picking a row switches the edited mesh
									// (never the object selection - that follows inside switchEditDrawable).
									overlap =
										overlapStateFrom(service, position, candidates) { id ->
											session.switchEditDrawable(id)
										}
								},
							)
							// The Object-mode gizmo: the mode-exclusive sibling of the Edit gizmo (each self-gates to
							// its mode). It owns the whole primary-button surface in Object mode - the click pick
							// (including the Alt overlap popup), the un-armed box drag, and the armed box / circle
							// tools and object G / S / R. Same frame-camera projection as the Edit gizmo so the
							// affordance stays glued to the art. It draws no posed mesh (only the rubber-band,
							// affordances, and pivot HUD), so it needs the frame camera but no frame model.
							ViewportObjectGizmoOverlay(
								areaId = areaId,
								service = service,
								session = session,
								camera = image?.camera,
								widthPx = widthPx,
								heightPx = heightPx,
								onOverlapRequest = { position, candidates ->
									overlap =
										overlapStateFrom(service, position, candidates) { id ->
											session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(id)))
										}
								},
							)
							// The Zoom Region overlay sits on top of the gizmo so it captures the drag in Edit mode
							// too; it self-gates on the session's armed-area flag, so it is inert (and passes input
							// through) in every other area and whenever Zoom Region is not armed.
							ViewportRegionOverlay(
								areaId = areaId,
								service = service,
								session = session,
								camera = camera,
								widthPx = widthPx,
								heightPx = heightPx,
							)
							// The relation-pick overlay sits above the gizmos so an armed eyedropper claims the
							// click before Object mode's pick would change the selection. It self-gates on the
							// shell's pick slot, so it is inert (and passes input through) whenever none is armed.
							ViewportPickOverlay(
								areaId = areaId,
								service = service,
							)
							// The 2D cursor marker: a control's world-anchored marker (not HUD chrome), so it
							// draws in its own layer above the gizmo chrome and below the HUD text.  Locked to
							// the frame camera like every world-anchored overlay drawing.
							Cursor2dOverlay(
								session = session,
								camera = image?.camera,
								widthPx = widthPx,
								heightPx = heightPx,
							)
							// The HUD layer draws topmost (the modal-op status badge, the active-mesh info chip,
							// and the zoom readout). It installs no pointer input, so it never steals a gesture
							// from the overlays below. The zoom readout takes the LIVE camera - the wheel
							// updates it immediately, where the frame camera lags the raster. The near-cursor
							// notices and the radial pie menus render at the SHELL level
							// (ShellCursorOverlays.kt): one instance above the whole area tree, escaping this
							// viewport's clipped bounds.
							ViewportHudOverlay(
								areaId = areaId,
								session = session,
								liveCamera = camera,
							)
						}
					}
				}
			}
		// The render-sync seam the UV editor's modal previews stream through: preview pushes go straight
		// to the render thread (transient, like the Edit overlay's own setModel calls), and resync
		// restores the session's committed model after a cancel / teardown.
		val renderSync =
			object : PuppetRenderSync {
				override fun previewModel(model: PuppetModel) {
					service.setModel(model)
				}

				override fun resync() {
					service.setModel(session.model.value)
				}
			}
		PuppetViewportBinding(
			host,
			service.thumbnails(),
			renderSync,
			service,
		)
	}
}

/**
 * The viewport pointer loop: middle-mouse drag pans (grab), the wheel zooms toward the cursor (Shift
 * for the coarse step), and any pointer activity marks this area active so keyboard view commands
 * target it. Picking is NOT handled here - the mode's gizmo overlay above owns the primary button
 * (the Object overlay's click pick / un-armed box, the Edit overlay's element picking). The right
 * button stays reserved for the context menu; touch pinch is the Android host's concern.
 *
 * @param PuppetViewportService service The render service to drive.
 * @param String areaId The area this viewport hosts.
 * @param EditorSession session The session whose armed tools gate this layer.
 * @param Function setPanning Reports whether a middle-mouse pan is in progress (drives the grab cursor).
 * @note This loop stamps NOTHING about where the pointer is.  Every workspace leaf carries that (see
 *   stampsHoveredSurface), and commands resolve their area from it at dispatch; a viewport-only second
 *   answer here would disagree with that one the moment the pointer moved to another space, so there is
 *   deliberately none.
 */
private suspend fun PointerInputScope.viewportNavigation(
	service: PuppetViewportService,
	areaId: String,
	session: EditorSession,
	setPanning: (Boolean) -> Unit,
) {
	awaitPointerEventScope {
		var panAnchor: Offset? = null
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull() ?: continue
			// THIS AREA'S modal operator (G / S / R), armed Box / Circle select tool, or armed Zoom Region
			// - or an in-flight un-armed box drag (area-less: pointer capture pins its events to the
			// dragging overlay) - owns the pointer: the overlay above consumes the event, but this
			// navigation layer does not check isConsumed, so it must skip pan / zoom explicitly -
			// otherwise a Circle MMB-erase would also pan, its wheel-resize would also zoom, and an
			// object G / S / R grab would pan.  A gesture latched in ANOTHER area does not block: its
			// events never reach here, so this area keeps panning and zooming during it (Blender
			// parity).
			if (session.activeMeshOperator.value?.areaId == areaId ||
				session.activeObjectOperator.value?.areaId == areaId ||
				session.activeSelectTool.value?.areaId == areaId ||
				session.viewportGestureActive.value ||
				session.zoomRegionArmedArea.value == areaId
			) {
				continue
			}
			when (event.type) {
				PointerEventType.Scroll -> {
					val shiftHeld = event.keyboardModifiers.isShiftPressed
					// AWT reroutes Shift+wheel into a horizontal scroll (the delta arrives in x with y
					// zero), so the coarse step's own modifier would hide the wheel motion - read the x
					// delta when Shift is held.  Sign convention matches y (wheel up = negative).
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
						if (panAnchor == null) {
							setPanning(true)
						}
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
						if (panAnchor != null) {
							setPanning(false)
						}
						panAnchor = null
					}
				}
			}
		}
	}
}