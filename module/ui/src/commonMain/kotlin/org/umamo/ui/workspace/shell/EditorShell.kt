package org.umamo.ui.workspace.shell

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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.menu.MenuBar
import org.umamo.ui.kit.menu.TopLevelMenu
import org.umamo.ui.l10n.ProvideAppLocale
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.properties.LocalPropertyTabRegistry
import org.umamo.ui.properties.PropertyTab
import org.umamo.ui.properties.defaultPropertyTabRegistry
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.workspace_new_name
import org.umamo.ui.sidebar.LocalSidebarRegistry
import org.umamo.ui.sidebar.SidebarRegistry
import org.umamo.ui.sidebar.rememberSidebarRegistry
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.LocalSpaceRegistry
import org.umamo.ui.workspace.LocalViewportHost
import org.umamo.ui.workspace.SpaceDescriptor
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.ViewportHost
import org.umamo.ui.workspace.area.AreaDragController
import org.umamo.ui.workspace.area.AreaDragOverlay
import org.umamo.ui.workspace.area.AreaTree
import org.umamo.ui.workspace.area.SPLIT_ARM_DISTANCE
import org.umamo.ui.workspace.commands.ArtworkOperations
import org.umamo.ui.workspace.layout.InterfaceLayout
import org.umamo.ui.workspace.layout.WorkspaceLayoutController
import org.umamo.ui.workspace.layout.defaultLayout
import org.umamo.ui.workspace.statusbar.StatusBar

/*
 * The window's shell.  This is the one package that may import any other under workspace, the spaces
 * included; no production code under workspace imports it back.
 *
 *  - EditorShell.kt: the wiring.  It builds the controllers, registers the commands, reclaims focus, and
 *    lays out the window: the menu and tab row, the area tree, the status bar, then the overlays.
 *  - ShellControllers.kt: everything the shell remembers for its lifetime, and the locals they back.
 *  - ShellCommandRegistration.kt: the shell's own command tables, registered in palette order.
 *  - ShellFocus.kt: keeping the keyboard on the root.
 *  - ModalKeyLadder.kt, over ShellModalState.kt and ShellKeyStroke.kt: the Escape / Enter ladder that
 *    runs before the keymap.
 *  - ShellTextEntry.kt: the press that ends text entry.  ShellCursorClaim.kt: the window-wide cursor.
 *  - ShellCursorOverlays.kt (with the pie menu tables in ViewportPieMenus.kt): the overlays anchored at
 *    the window pointer.  ShellModalOverlays.kt: the modal dialogs, in stacking order.
 *  - WorkspaceTabs.kt: the workspace tab strip.
 *  - EditorShellPersistence.kt: the settings-backed wrapper apps mount, and LogExport.kt, the log export
 *    it registers.
 *  - DefaultSpaces.kt: the table wiring each space kind to its body.
 */

/**
 * The whole editor shell: workspace tabs over a recursive, switchable, splittable area tree, with the
 * command palette overlaid. Self-contained so it runs standalone (defaults seed a two-workspace
 * layout and a base space registry), while every collaborator is injectable so an app can supply the
 * GL [viewportHost], override specific spaces, share a pre-populated [commandRegistry] (e.g. with File
 * commands), drive the [languageTag] from settings, and persist via [onLayoutChange].
 *
 * The shell is wiring over its collaborators: [ShellControllers] holds what it keeps for its lifetime -
 * among them the [WorkspaceLayoutController] that owns the layout and its edits (structural edits route
 * through the single [org.umamo.ui.workspace.layout.AreaCommand] choke point) and the ShellOverlayState
 * that holds the modal chrome.  The command tables live in the org.umamo.ui.workspace.commands package
 * (registered per group by [RegisterShellCommands]), and the root key handling is the modal ladder in
 * ModalKeyLadder.kt.  The palette and keymap dispatch through the action registry - the input spine.
 *
 * @param InterfaceLayout initialLayout The starting layout (defaults to the seeded two-workspace layout).
 * @param ViewportHost? viewportHost What draws a 2D viewport area, or null with no render service to draw with.
 * @param Map spaceOverrides Per-kind space descriptors layered over the base registry.
 * @param List propertyTabOverrides Property tabs layered over the base tab set (a vendor extension seam).
 * @param CommandRegistry commandRegistry The action registry (the app may pre-register commands).
 * @param SidebarRegistry sidebarRegistry The tabs and panels every work-surface sidebar draws from; the app
 *   holds one per window, so it outlives a document swap.
 * @param List appMenu The application menu-bar contents, shown to the left of the workspace tabs; empty
 *   (the default) renders no bar.  The app supplies it because its items close over app-specific state
 *   (the open document, the file picker), while the bar component itself is shared.
 * @param String languageTag The active UI language (BCP-47).
 * @param Keymap keymap The active keymap (defaults to the built-in default preset; the persistent wrapper
 *   injects the settings-resolved keymap so a preset change or a rebind takes effect everywhere at once).
 * @param Function onLayoutChange Called with every new layout, for persistence.
 * @param Function onLayoutDragChange Called with true when a splitter drag begins and false when it
 *   ends; the persistence wrapper holds the debounced write while the drag is live and commits
 *   immediately at its end.  The signal carries no layout and publishes nothing, so it bypasses
 *   [WorkspaceLayoutController] on purpose - a controller pass-through would add API for no state.
 * @param ArtworkOperations? artwork The app's artwork orchestrations (add a file, reload the listed
 *   files, relink a tile, match or replace a file's bindings) over the area the command fires in, or
 *   null (the default) when no open document can take artwork.  The shell registers the commands
 *   itself so the operation strip lands in the hovered work surface.
 * @param Function? exportImage Export Image, handed the 2D viewport area it should frame (or null when the
 *   pointer last touched none), or null (the default) when nothing can be captured.  The shell registers the
 *   command itself because only its routing knows which viewport the rigger means.
 */
@Composable
fun EditorShell(
	initialLayout: InterfaceLayout = defaultLayout(),
	viewportHost: ViewportHost? = null,
	spaceOverrides: Map<SpaceKind, SpaceDescriptor> = emptyMap(),
	propertyTabOverrides: List<PropertyTab> = emptyList(),
	commandRegistry: CommandRegistry = remember { CommandRegistry() },
	sidebarRegistry: SidebarRegistry = rememberSidebarRegistry(),
	appMenu: List<TopLevelMenu> = emptyList(),
	languageTag: String = "en",
	keymap: Keymap = defaultKeymap(),
	onLayoutChange: (InterfaceLayout) -> Unit = {},
	onLayoutDragChange: (Boolean) -> Unit = {},
	artwork: ArtworkOperations? = null,
	exportImage: ((viewportAreaId: String?) -> Unit)? = null,
) {
	val controllers = rememberShellControllers(initialLayout, onLayoutChange)
	val currentOnLayoutDragChange by rememberUpdatedState(onLayoutDragChange)
	val spaceRegistry = remember(spaceOverrides) { defaultSpaceRegistry().withOverrides(spaceOverrides) }
	val propertyTabRegistry =
		remember(propertyTabOverrides) { defaultPropertyTabRegistry().withOverrides(propertyTabOverrides) }
	// The pointer's last position in shell-root pixels (null before any pointer event), observed on the
	// Initial pass below so it tracks through any gesture.  Anchors the shell-level cursor overlays -
	// the pie menu ring and near-cursor notices - which render above the area tree so they escape area
	// bounds and exist exactly once (see ShellCursorOverlays.kt).
	var shellPointerPosition by remember { mutableStateOf<Offset?>(null) }
	// The split arm distance is in dp; convert it once to the px the controller hit-tests in.
	controllers.dragController.splitThresholdPx = with(LocalDensity.current) { SPLIT_ARM_DISTANCE.toPx() }

	// Both outside the locale key below, which rebuilds everything inside it on a language switch: the
	// command groups must not re-register (the palette order would shuffle) and the focus effects must not
	// restart.  Registration comes first, so the palette lists the shell's groups in the order declared there.
	RegisterShellCommands(commandRegistry, controllers, artwork, exportImage)
	ReclaimShellFocus(controllers, languageTag)

	// The open document the key ladder hands its arms, read afresh for each key event.
	val editorSession = LocalEditorSession.current
	val selection = LocalSelection.current
	// The window-wide cursor: hidden under an armed relation pick (the shell draws the eyedropper
	// itself), the editor's I-beam while text entry is live, and otherwise nothing of its own.
	// Claiming it here rather than in each viewport is what makes a mode announce itself the
	// instant it begins, wherever the pointer happens to be sitting.
	val cursorClaim =
		shellCursorClaim(
			relationPickArmed = controllers.relationPick.request != null,
			textEntryActive = controllers.inlineEditController.cancel != null,
		)
	val claimedPointerIcon = remember(cursorClaim) { cursorClaim.pointerIcon() }

	// The keyboard root: the one focusable node the keyboard dispatches from, with the window's pointer
	// observers.  Outside the locale key for the same reason as the effects above - a language switch that
	// disposed the focused node would leave focus null, and every key dead, with nothing to take it back.
	Box(
		modifier =
			Modifier
				.fillMaxSize()
				// Before the focus target it observes: after focusable() it would report only a descendant's focus.
				.onFocusChanged { focusState -> controllers.rootHoldsFocus = focusState.hasFocus }
				.focusRequester(controllers.focusRequester)
				.focusable()
				// Declared once on a node that lives the whole time, never mounted when a mode starts: a
				// hover icon that appears mid-gesture is not consulted until the pointer next MOVES, and
				// text entry begins with a click the hand then rests on - the I-beam would never appear.
				// The unclaimed case resolves to the plain pointer, which is what an unclaimed pointer
				// already resolves to, so with no mode running the descendants still decide.
				.pointerHoverIcon(claimedPointerIcon, overrideDescendants = cursorClaim.overridesDescendants)
				// The window-space pointer tracker for the shell cursor overlays.  The root surface inside
				// fills this box from its origin, so the observer and the overlays agree on positions.
				.pointerInput(Unit) {
					observeWindowPointer { position -> shellPointerPosition = position }
				}
				// The press that ends text entry (ShellTextEntry.kt).
				.releaseTextEntryOnPress(controllers.inlineEditController, controllers.overlays, controllers.focusRequester)
				// Root key handling is the modal ladder (ModalKeyLadder.kt): modal chrome and
				// in-flight gestures pre-empt the keymap in stacking order; whatever the ladder
				// does not consume falls through to the keymap + action registry.
				.onPreviewKeyEvent { event ->
					handleModalKeyLadder(
						stroke = event.toShellKeyStroke(),
						state = controllers.modalState(editorSession, selection, commandRegistry, keymap),
					)
				},
	) {
		ProvideAppLocale(languageTag) {
			// Resolved here, inside the locale key, so it follows a language switch; the workspace commands
			// registered outside the key read it when they run.
			val newWorkspaceBaseName = stringResource(Res.string.workspace_new_name)
			SideEffect { controllers.newWorkspaceBaseName = newWorkspaceBaseName }
			UmamoTheme {
				CompositionLocalProvider(
					LocalCommands provides commandRegistry,
					LocalKeymap provides keymap,
					LocalSpaceRegistry provides spaceRegistry,
					LocalPropertyTabRegistry provides propertyTabRegistry,
					LocalSidebarRegistry provides sidebarRegistry,
					LocalViewportHost provides viewportHost,
					*controllers.compositionLocals(),
				) {
					Surface(modifier = Modifier.fillMaxSize(), color = LocalUmamoColors.current.windowBackground) {
						Column(modifier = Modifier.fillMaxSize()) {
							ShellTabRow(appMenu = appMenu, workspaces = controllers.workspaces)
							ShellAreaHost(
								workspaces = controllers.workspaces,
								dragController = controllers.dragController,
								onSplitterDragChange = { dragActive -> currentOnLayoutDragChange(dragActive) },
								modifier = Modifier.weight(1f).fillMaxWidth(),
							)
							// The bottom status strip is the Column's last child: fixed-height chrome under the
							// weight(1f) area host, so the area tree fills the gap between the tabs and the strip.
							StatusBar(modifier = Modifier.fillMaxWidth())
						}
						// The cursor overlays, above the area tree and below the modals (ShellCursorOverlays.kt).
						ShellCursorOverlayStack(pointerPosition = { shellPointerPosition })
						// The modal overlays, above everything else (ShellModalOverlays.kt).
						ShellModalOverlays(overlays = controllers.overlays, commandRegistry = commandRegistry, hoveredSurfaces = controllers.hoveredSurfaces)
					}
				}
			}
		}
	}
}

/**
 * The window's top row: the application menu bar, when the app supplies one, then the workspace tabs.
 *
 * The menu bar shares the tab strip's row, sitting to its left to save vertical space; the tabs take the
 * remaining width.  With no menu (e.g. on Android this slice) only the tabs show.
 *
 * @param List                      appMenu    The application menu-bar contents; empty renders no bar.
 * @param WorkspaceLayoutController workspaces The layout the tabs show and edit.
 */
@Composable
private fun ShellTabRow(appMenu: List<TopLevelMenu>, workspaces: WorkspaceLayoutController) {
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
			onDuplicate = { sourceId, suggestedName ->
				workspaces.duplicate(
					sourceId,
					suggestedName,
				)
			},
			onDelete = { targetId -> workspaces.delete(targetId) },
			onReorder = { fromIndex, toIndex -> workspaces.reorder(fromIndex, toIndex) },
			onRename = { workspaceId, newName -> workspaces.rename(workspaceId, newName) },
			modifier = Modifier.weight(1f),
		)
	}
}

/**
 * The active workspace's area tree, with the corner-drag join highlight painted over it.
 *
 * @param WorkspaceLayoutController workspaces           The layout whose active workspace this shows and edits.
 * @param AreaDragController        dragController       The corner-drag state, resolved against this host's bounds.
 * @param Function                  onSplitterDragChange Called with true when a divider drag begins and false when it ends.
 * @param Modifier                  modifier             The size the shell gives the host.
 */
@Composable
private fun ShellAreaHost(
	workspaces: WorkspaceLayoutController,
	dragController: AreaDragController,
	onSplitterDragChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier =
			modifier
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
				onSplitterDragChange = onSplitterDragChange,
			)
		}

		// Visual-only join highlight, painted last so it floats above the tree (and the offscreen viewport).
		AreaDragOverlay(controller = dragController, modifier = Modifier.fillMaxSize())
	}
}