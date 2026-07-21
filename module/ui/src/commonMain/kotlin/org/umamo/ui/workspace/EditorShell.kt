package org.umamo.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.CommandPalette
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.document.DocumentOpenError
import org.umamo.ui.help.AboutDialog
import org.umamo.ui.help.CreditsDialog
import org.umamo.ui.kit.ConfirmDialog
import org.umamo.ui.kit.InlineEditController
import org.umamo.ui.kit.LocalInlineEditController
import org.umamo.ui.kit.LocalMenuBarController
import org.umamo.ui.kit.MenuBar
import org.umamo.ui.kit.MenuBarController
import org.umamo.ui.kit.MessageDialog
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.TopLevelMenu
import org.umamo.ui.l10n.ProvideAppLocale
import org.umamo.ui.model.LocalEditorMode
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.properties.LocalPropertyTabRegistry
import org.umamo.ui.properties.PropertyTab
import org.umamo.ui.properties.defaultPropertyTabRegistry
import org.umamo.ui.resources.*
import org.umamo.ui.settings.SettingsWindow
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.UmamoTheme

/**
 * The whole editor shell: workspace tabs over a recursive, switchable, splittable area tree, with the
 * command palette overlaid. Self-contained so it runs standalone (defaults seed a two-workspace
 * layout and a base space registry), while every collaborator is injectable so an app can supply the
 * GL [viewportHost], override specific spaces, share a pre-populated [commandRegistry] (e.g. with File
 * commands), drive the [languageTag] from settings, and persist via [onLayoutChange].
 *
 * The shell is a thin composition over its extracted collaborators: [WorkspaceLayoutController] owns
 * the layout state and its edits (structural edits route through the single [AreaCommand] choke
 * point), [ShellOverlayState] holds the modal chrome flags, the command tables live in
 * ShellCommands.kt (registered per group through registerAll), and the root key handling is the
 * modal ladder in ModalKeyLadder.kt.  The palette and keymap dispatch through the action registry -
 * the input spine.
 *
 * エディタシェル全体。ワークスペースタブ＋再帰的で切替・分割可能なエリアツリー＋コマンドパレット。
 * 単体でも動作し、各協調要素は注入可能。状態と入力処理は分離した協調クラスに委譲する。
 *
 * @param InterfaceLayout initialLayout The starting layout (defaults to the seeded two-workspace layout).
 * @param ViewportHost? viewportHost The platform GL viewport injector, or null for placeholders.
 * @param Map spaceOverrides Per-kind space descriptors layered over the base registry.
 * @param List propertyTabOverrides Property tabs layered over the base tab set (a vendor extension seam).
 * @param CommandRegistry commandRegistry The action registry (the app may pre-register commands).
 * @param List appMenu The application menu-bar contents, shown to the left of the workspace tabs; empty
 *   (the default) renders no bar.  The app supplies it because its items close over app-specific state
 *   (the open document, the file picker), while the bar component itself is shared.
 * @param String languageTag The active UI language (BCP-47).
 * @param Keymap keymap The active keymap (defaults to the built-in default preset; the persistent wrapper
 *   injects the settings-resolved keymap so a preset change or a rebind takes effect everywhere at once).
 * @param Function onLayoutChange Called with every new layout, for persistence.
 */
@Composable
fun EditorShell(
	initialLayout: InterfaceLayout = defaultLayout(),
	viewportHost: ViewportHost? = null,
	spaceOverrides: Map<SpaceKind, SpaceDescriptor> = emptyMap(),
	propertyTabOverrides: List<PropertyTab> = emptyList(),
	commandRegistry: CommandRegistry = remember { CommandRegistry() },
	appMenu: List<TopLevelMenu> = emptyList(),
	languageTag: String = "en",
	keymap: Keymap = defaultKeymap(),
	onLayoutChange: (InterfaceLayout) -> Unit = {},
) {
	// The layout controller outlives recompositions, so it publishes through a live reference to the
	// persistence hook rather than capturing the first composition's lambda.
	val currentOnLayoutChange by rememberUpdatedState(onLayoutChange)
	val workspaces = remember { WorkspaceLayoutController(initialLayout) { newLayout -> currentOnLayoutChange(newLayout) } }
	val overlays = remember { ShellOverlayState() }
	// The localized base name new and imported workspaces are named from (deduped) - the same string the "+"
	// button passes to onCreate, so the menu's New Workspace and the tab strip agree.
	val newWorkspaceBaseName = stringResource(Res.string.workspace_new_name)
	val spaceRegistry = remember(spaceOverrides) { defaultSpaceRegistry().withOverrides(spaceOverrides) }
	val propertyTabRegistry = remember(propertyTabOverrides) { defaultPropertyTabRegistry().withOverrides(propertyTabOverrides) }
	val focusRequester = remember { FocusRequester() }
	val dragController = remember { AreaDragController() }
	// Shared with the menu bar so this shell's root key handler can dismiss an open menu before the keymap
	// claims the same key (Escape is bound to area.dragCancel).  See the onPreviewKeyEvent below.
	val menuBarController = remember { MenuBarController() }
	// Shared with inline editors (workspace rename) so that while one is open this shell's root key handler
	// yields the keyboard to the field - routing Escape to cancel and suppressing its own shortcuts.
	val inlineEditController = remember { InlineEditController() }
	// Shared with the row-dragging panels (outliner, parameters): while a row drag is in flight its cancel
	// is parked here, so the root key handler can route Escape to abort the drag before the clear-selection
	// branch would swallow it.  One pointer means at most one in-flight drag anywhere, so one slot serves
	// every panel.
	val rowDragCancel = remember { RowDragCancelController() }
	// The last-touched editor surface (area id + space kind), stamped by the viewport and UV-editor
	// pointer loops and read by command handlers at dispatch time - the space-aware generalization of
	// the camera controller's activeAreaId (see HoveredSurface.kt for the dispatch-time-only contract).
	val hoveredSurfaces = remember { HoveredSurfaceTracker() }
	// The camera-bearing areas' per-area controllers, registered by each 2D viewport and UV space for
	// its lifetime; the view commands resolve the hovered area here at dispatch time (one hub for both
	// surfaces).
	val areaCameras = remember { AreaCameraHub() }
	// The pointer's last position in shell-root pixels (null before any pointer event), observed on the
	// Initial pass below so it tracks through any gesture.  Anchors the shell-level cursor overlays -
	// the pie menu ring and near-cursor notices - which render above the area tree so they escape area
	// bounds and exist exactly once (see ShellCursorOverlays.kt).
	var shellPointerPosition by remember { mutableStateOf<Offset?>(null) }
	// The split arm distance is in dp; convert it once to the px the controller hit-tests in.
	dragController.splitThresholdPx = with(LocalDensity.current) { SPLIT_ARM_DISTANCE.toPx() }

	// The shell's command tables (ShellCommands.kt), registered per group: each group's effect keys on
	// the state its handlers close over, and registerAll returns the matching cleanup so registration
	// and unregistration can never drift apart.
	DisposableEffect(commandRegistry, dragController) {
		val cleanup = commandRegistry.registerAll(shellChromeCommands(overlays, dragController, rowDragCancel, workspaces))
		onDispose { cleanup() }
	}
	DisposableEffect(commandRegistry) {
		val cleanup = commandRegistry.registerAll(shellWorkspaceCommands(workspaces, overlays, newWorkspaceBaseName))
		onDispose { cleanup() }
	}
	// Viewport navigation commands dispatch to the hovered surface at invocation time: the hovered
	// area's camera controller through the hub (2D viewport or UV editor), a no-op when none is
	// registered. Re-registered when the render service changes (a new document / renderer), which also
	// flips the availability gate.
	val service = LocalPuppetViewportService.current
	DisposableEffect(commandRegistry, service) {
		val hoveredSurface: () -> HoveredSurface? = { hoveredSurfaces.lastTouched }
		val cleanup = commandRegistry.registerAll(shellViewportCommands(areaCameras, hoveredSurface, service != null))
		onDispose { cleanup() }
	}
	val selection = LocalSelection.current
	val editorMode = LocalEditorMode.current
	DisposableEffect(commandRegistry, selection, editorMode) {
		val cleanup = commandRegistry.registerAll(shellModeCommands(selection, editorMode))
		onDispose { cleanup() }
	}
	// Re-registered on a document swap so the handlers close over the current session.  The latching
	// commands resolve the pointer's viewport at dispatch time from the render service's active area
	// (the armZoomRegion precedent), so the effect also keys on it.
	val editorSession = LocalEditorSession.current
	DisposableEffect(commandRegistry, editorSession, selection, service) {
		val activeViewportArea: () -> String? = { service?.activeAreaId }
		val hoveredSurface: () -> HoveredSurface? = { hoveredSurfaces.lastTouched }
		val cleanup = commandRegistry.registerAll(shellSessionCommands(editorSession, selection, activeViewportArea, hoveredSurface))
		onDispose { cleanup() }
	}

	// THE focus-reclaim effect.  Compose leaves focus null whenever the focused node leaves composition
	// (a join/split disposing the focused leaf, a closing overlay/popup/menu/inline editor taking its
	// field along), and a null focus silently kills every keyboard shortcut until the next click - so the
	// root must reclaim focus after each such transition.  One effect keyed on every reclaim trigger:
	//  - structuralEditCount: area-tree edits and popup-invoked workspace CRUD;
	//  - selfFocusedOverlayOpen / inline edit: reclaim when the palette, preferences, Help dialogs, or an
	//    inline rename CLOSE (while one is open it owns focus, so the effect waits);
	//  - modalAlertOpen: the confirm dialog and the file-open alert do NOT own focus - root focus is
	//    (re)claimed on open too, so their Escape/Enter route through the modal ladder while open;
	//  - menu-bar close: an open menu's popup holds focus and its teardown takes it along.
	// The two-frame wait lets a closing popup's teardown finish stealing focus first - an immediate
	// request would be nulled right back out (the menu bar demonstrably needs this; it is harmless for
	// the other triggers).
	val overlaySelfFocused = overlays.selfFocusedOverlayOpen || inlineEditController.cancel != null
	val menuBarOpen = menuBarController.closeOpenMenu != null
	LaunchedEffect(workspaces.structuralEditCount, overlaySelfFocused, overlays.modalAlertOpen, menuBarOpen) {
		if (overlaySelfFocused || menuBarOpen) {
			return@LaunchedEffect
		}
		withFrameNanos {}
		withFrameNanos {}
		focusRequester.requestFocus()
	}

	// An OS-level focus round-trip (alt-tab away and back) restores focus to the WINDOW but to no Compose
	// node - whatever was focused before the blur stays unfocused, so onPreviewKeyEvent never fires and
	// every shortcut is dead until something focusable is clicked.  Reclaim root focus on window-focus
	// regain.  Skipped while an overlay that owns its own focus is up (the reclaim effect above covers
	// their close); the guard reads the live state inside the collector, never captures.
	val windowInfo = LocalWindowInfo.current
	LaunchedEffect(windowInfo) {
		snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
			val overlayOwnsFocus =
				inlineEditController.cancel != null ||
					overlays.selfFocusedOverlayOpen ||
					overlays.modalAlertOpen
			if (windowFocused && !overlayOwnsFocus) {
				focusRequester.requestFocus()
			}
		}
	}

	ProvideAppLocale(languageTag) {
		UmamoTheme {
			CompositionLocalProvider(
				LocalCommands provides commandRegistry,
				LocalKeymap provides keymap,
				LocalSpaceRegistry provides spaceRegistry,
				LocalPropertyTabRegistry provides propertyTabRegistry,
				LocalViewportHost provides viewportHost,
				LocalAreaDragController provides dragController,
				LocalMenuBarController provides menuBarController,
				LocalInlineEditController provides inlineEditController,
				LocalRowDragCancel provides rowDragCancel,
				LocalHoveredSurfaceTracker provides hoveredSurfaces,
				LocalAreaCameraHub provides areaCameras,
			) {
				Surface(
					modifier =
						Modifier
							.fillMaxSize()
							.focusRequester(focusRequester)
							.focusable()
							// The window-space pointer tracker for the shell cursor overlays.  On the root
							// surface, whose content Box shares this coordinate space, so the observer and
							// the overlays agree on positions.
							.pointerInput(Unit) {
								observeWindowPointer { position -> shellPointerPosition = position }
							}
							// Root key handling is the modal ladder (ModalKeyLadder.kt): modal chrome and
							// in-flight gestures pre-empt the keymap in stacking order; whatever the ladder
							// does not consume falls through to the keymap + action registry.
							.onPreviewKeyEvent { event ->
								handleModalKeyLadder(
									event = event,
									overlays = overlays,
									menuBarController = menuBarController,
									inlineEditController = inlineEditController,
									editorSession = editorSession,
									selection = selection,
									dragController = dragController,
									rowDragCancel = rowDragCancel,
									commandRegistry = commandRegistry,
									keymap = keymap,
								)
							},
					color = LocalUmamoColors.current.windowBackground,
				) {
					Column(modifier = Modifier.fillMaxSize()) {
						// The menu bar shares the tab strip's row, sitting to its left to save vertical space; the
						// tabs take the remaining width.  With no menu (e.g. on Android this slice) only the tabs show.
						Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							if (appMenu.isNotEmpty()) {
								MenuBar(menus = appMenu)
							}
							Box(
								modifier =
									Modifier
										.padding(horizontal = 4.dp)
										.height(14.dp).width(1.dp)
										.background(LocalUmamoColors.current.guideLine),
							)
							WorkspaceTabs(
								workspaces = workspaces.layout.workspaces,
								activeId = workspaces.layout.activeWorkspaceId,
								onSelect = { workspaceId -> workspaces.setActiveWorkspace(workspaceId) },
								onCreate = { suggestedName -> workspaces.create(suggestedName) },
								onDuplicate = { sourceId, suggestedName -> workspaces.duplicate(sourceId, suggestedName) },
								onDelete = { targetId -> workspaces.delete(targetId) },
								onReorder = { fromIndex, toIndex -> workspaces.reorder(fromIndex, toIndex) },
								onRename = { workspaceId, newName -> workspaces.rename(workspaceId, newName) },
								modifier = Modifier.weight(1f),
							)
						}
						Box(
							modifier =
								Modifier
									.weight(1f)
									.fillMaxWidth()
									// The one shared space: leaf rects and the drag overlay are both resolved against this Box.
									.onGloballyPositioned { coordinates -> dragController.contentCoords = coordinates },
						) {
							val active = workspaces.layout.activeWorkspace()
							// The corner-drag controller needs the live root to test sibling-ness on release.
							dragController.currentRoot = active?.root
							if (active != null) {
								AreaTree(
									node = active.root,
									onNodeChange = { newRoot -> workspaces.updateActiveRoot(newRoot) },
									onCommand = { command -> workspaces.applyAreaCommand(command) },
									modifier = Modifier.padding(4.dp),
								)
							}
							// Visual-only join highlight, painted last so it floats above the tree (and the offscreen viewport).
							AreaDragOverlay(controller = dragController, modifier = Modifier.fillMaxSize())
						}
						// The bottom status strip is the Column's last child: fixed-height chrome under the
						// weight(1f) content Box, so the area tree fills the gap between the tabs and the strip.
						StatusBar(modifier = Modifier.fillMaxWidth())
					}
					// The shell-level cursor overlays: near-cursor notices, then the pie menu (which owns the
					// pointer while open).  Siblings of the Column so they float above the whole area tree -
					// escaping viewport bounds - and BELOW the modal dialogs that follow.
					ShellNearCursorNotice(pointerPosition = shellPointerPosition)
					ShellPieMenuHost(pointerPosition = shellPointerPosition)
					// Modal overlays are siblings of the Column (Surface stacks its content in a Box), so their
					// full-window scrims cover the menu bar and tab strip too: a click anywhere outside the
					// overlay's card dismisses it, and the chrome behind is not interactable while it is open
					// (so the palette can no longer be left open under a menu-bar-launched window).  Painted
					// bottom-to-top: palette, then settings, then the confirm dialog (the topmost modal).
					if (overlays.paletteVisible) {
						// title == null marks a command as not-a-palette-entry (internal toggles like
						// palette.toggle / area.dragCancel, and argument-only import commands), and an
						// unavailable command is hidden, so the palette only offers titled operations that
						// apply in the current context (mode, armed tool, open document).
						CommandPalette(
							commands = commandRegistry.all().filter { command -> command.title != null && command.availability.isAvailable() },
							onDismiss = { overlays.paletteVisible = false },
							onInvoke = { command ->
								overlays.paletteVisible = false
								commandRegistry.invoke(command.id)
							},
						)
					}
					// The preferences overlay; auto-saves every change, so closing it is the only action it needs.
					if (overlays.settingsVisible) {
						SettingsWindow(onDismiss = { overlays.settingsVisible = false })
					}
					// The Help dialogs, in the same modal family (below the confirm dialog in paint order).
					if (overlays.aboutVisible) {
						AboutDialog(onDismiss = { overlays.aboutVisible = false })
					}
					if (overlays.creditsVisible) {
						CreditsDialog(onDismiss = { overlays.creditsVisible = false })
					}
					// The file-open failure alert, in the same modal family (below the confirm dialog in paint order).
					overlays.openFailure?.let { failure ->
						MessageDialog(
							message = stringResource(openFailureMessage(failure.error), failure.displayName),
							onDismiss = { overlays.openFailure = null },
						)
					}
					// A destructive command raised a confirm: a modal scrim over the whole shell, painted last
					// so it floats above the tabs, the area tree, the palette, and the settings window.
					overlays.pendingConfirm?.let { request ->
						ConfirmDialog(
							message = stringResource(request.message),
							onConfirm = {
								request.onConfirm()
								overlays.pendingConfirm = null
							},
							onCancel = { overlays.pendingConfirm = null },
						)
					}
				}
			}
		}
	}
}

/**
 * Resolves a document-open failure to its localized alert message resource.  Every message takes the
 * file's display name as its one format argument.
 *
 * @param DocumentOpenError error The failure reason reported by the document loader.
 * @return StringResource The message resource for the file-open alert dialog.
 */
private fun openFailureMessage(error: DocumentOpenError): StringResource =
	when (error) {
		DocumentOpenError.ReadFailed -> Res.string.open_failed_read
		DocumentOpenError.Unrecognized -> Res.string.open_failed_unrecognized
		DocumentOpenError.NotOpenable -> Res.string.open_failed_not_openable
		DocumentOpenError.ParseFailed -> Res.string.open_failed_parse
		DocumentOpenError.MissingManifest -> Res.string.open_failed_missing_manifest
		DocumentOpenError.MissingTexture -> Res.string.open_failed_missing_texture
	}
