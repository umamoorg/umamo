package org.umamo.ui.workspace.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import org.umamo.edit.EditorSession
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.kit.field.LocalScrubCancel
import org.umamo.ui.kit.field.ScrubCancelController
import org.umamo.ui.kit.menu.LocalMenuBarController
import org.umamo.ui.kit.menu.MenuBarController
import org.umamo.ui.kit.textentry.InlineEditController
import org.umamo.ui.kit.textentry.KeyCaptureController
import org.umamo.ui.kit.textentry.LocalInlineEditController
import org.umamo.ui.kit.textentry.LocalKeyCapture
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.model.repack.AtlasRepackSessionOptions
import org.umamo.ui.settings.LocalQuickSetup
import org.umamo.ui.settings.QuickSetupState
import org.umamo.ui.workspace.AppAlertQueues
import org.umamo.ui.workspace.AreaCameraHub
import org.umamo.ui.workspace.HoveredSurfaceTracker
import org.umamo.ui.workspace.KeyableHover
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.LocalAppAlerts
import org.umamo.ui.workspace.LocalAreaCameraHub
import org.umamo.ui.workspace.LocalHoveredSurfaceTracker
import org.umamo.ui.workspace.LocalKeyableHover
import org.umamo.ui.workspace.LocalKeyformSheetViews
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.RelationPickController
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.area.AreaDragController
import org.umamo.ui.workspace.area.LocalAreaDragController
import org.umamo.ui.workspace.area.LocalSplitterDragCancel
import org.umamo.ui.workspace.area.SplitterDragCancelController
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.hostsOperationStrip
import org.umamo.ui.workspace.layout.InterfaceLayout
import org.umamo.ui.workspace.layout.WorkspaceLayoutController
import org.umamo.ui.workspace.layout.firstLeafOrNull
import org.umamo.ui.workspace.operationstrip.LocalOperationStrip
import org.umamo.ui.workspace.operationstrip.LocalOperationStripArea
import org.umamo.ui.workspace.operationstrip.OperationStripState
import org.umamo.ui.workspace.rowdrag.LocalRowDragCancel
import org.umamo.ui.workspace.rowdrag.RowDragCancelController

/**
 * Everything the shell remembers for its whole lifetime: the layout controller, the modal chrome, the
 * root's focus, and the seams its descendants and command tables park state on.
 *
 * Built once per shell by [rememberShellControllers] and never rebuilt, so every field is the same instance
 * for as long as the shell is composed.  That is what lets the command groups that must not re-register on
 * a document swap close over them.  A plain holder: beyond the composition locals it provides and the key
 * ladder state it assembles, it adds no behavior.
 *
 * @param InterfaceLayout initialLayout  The layout the layout controller starts from.
 * @param Function        onLayoutChange Called with every new layout the layout controller publishes.
 * @param QuickSetupState quickSetup     Quick Setup's visibility, which the app holds across the document
 *   swaps that rebuild the shell.
 * @param AppAlertQueues  appAlerts      The alert-family queues, which the app holds for the same reason.
 */
internal class ShellControllers(
	initialLayout: InterfaceLayout,
	onLayoutChange: (InterfaceLayout) -> Unit,
	quickSetup: QuickSetupState,
	appAlerts: AppAlertQueues,
) {
	/** The workspaces and their area trees, and every edit to them. */
	val workspaces = WorkspaceLayoutController(initialLayout, onLayoutChange)

	/** Which modal chrome is up, and the queues of the modal alerts. */
	val overlays = ShellOverlayState(quickSetup, appAlerts)

	/** The shell root's focus: the one node the keyboard dispatches from. */
	val focusRequester = FocusRequester()

	/**
	 * Whether the shell root or anything under it holds focus - false once the focused node has left the
	 * composition with nothing taking its place.  Kept by the root and read by the focus reclaim.
	 */
	var rootHoldsFocus: Boolean = false

	/** The corner-drag join and split gesture over the area tree. */
	val dragController = AreaDragController()

	/**
	 * Shared with the menu bar so the root key handler can dismiss an open menu before the keymap claims the
	 * same key (Escape is bound to area.dragCancel).
	 */
	val menuBarController = MenuBarController()

	/**
	 * Shared with inline editors (workspace rename) so that while one is open the root key handler yields the
	 * keyboard to the field - routing Escape to cancel and suppressing its own shortcuts.
	 */
	val inlineEditController = InlineEditController()

	/** Shared with the controls that bind the next key press, which own every key while they wait. */
	val keyCapture = KeyCaptureController()

	/**
	 * Shared with the row-dragging panels (outliner, parameters): while a row drag is in flight its cancel is
	 * parked here, so the root key handler can route Escape to abort the drag before the clear-selection
	 * branch would swallow it.  One pointer means at most one in-flight drag anywhere, so one slot serves
	 * every panel.
	 */
	val rowDragCancel = RowDragCancelController()

	/**
	 * Shared with every number field: while one is being drag-scrubbed its cancel is parked here, so the root
	 * key handler can route Escape to abort the scrub ahead of the overlay-close and clear-selection branches,
	 * either of which would unmount the field mid-drag.  One pointer means at most one scrub anywhere, so one
	 * slot serves every field.
	 */
	val scrubCancel = ScrubCancelController()

	/**
	 * Shared with the area tree: a divider drag keeps its session inside the dragged SplitContainer, so while
	 * one is in flight that container parks its cancel here for the root Escape precedence to reach - the
	 * corner-drag equivalent of what [dragController] already exposes directly.
	 */
	val splitterDragCancel = SplitterDragCancelController()

	/**
	 * The one in-flight relation pick (a Properties field's eyedropper), resolved by whichever surface the user
	 * clicks next - the viewport pick overlay or an outliner row - and cancelled by Escape.
	 */
	val relationPick = RelationPickController()

	/**
	 * The last-touched editor surface (area id + space kind), stamped by every workspace leaf and read by
	 * command handlers at dispatch time - the ONE answer to "which area does the pointer mean" (see
	 * HoveredSurface.kt for the dispatch-time-only contract).
	 */
	val hoveredSurfaces = HoveredSurfaceTracker()

	/**
	 * The ONE routing seam every command group dispatches through, closing over nothing but the tracker and
	 * the layout controller above (both read live at dispatch), so it cannot go stale across a document swap.
	 */
	val routing =
		CommandRouting(
			{ hoveredSurfaces.lastTouched },
			{ hoveredSurfaces.lastTouchedStripHost },
			{ workspaces.layout.activeWorkspace()?.root?.firstLeafOrNull { leaf -> leaf.space.hostsOperationStrip }?.id },
			{ hoveredSurfaces.lastTouchedViewport },
		)

	/**
	 * The routing's strip-area answer as a panel reaches it (LocalOperationStripArea).  One instance for the
	 * shell's lifetime: the local is static, so a fresh lambda per composition would recompose everything.
	 */
	val operationStripArea: () -> String? = { routing.operationStripArea() }

	/** The keyable property under the pointer, so a keyform insert needs no prior selection. */
	val keyableHover = KeyableHover()

	/** The open keyform sheets, whose armed marquee claims Escape. */
	val keyformSheetViews = KeyformSheetViews()

	/**
	 * The camera-bearing areas' per-area controllers, registered by each 2D viewport and UV space for its
	 * lifetime; the view commands resolve the hovered area here at dispatch time (one hub for both surfaces).
	 */
	val areaCameras = AreaCameraHub()

	/**
	 * The repack's session memory, which lives as long as the window, across documents - which is why it does
	 * not key on the session.
	 */
	val repackOptions = AtlasRepackSessionOptions()

	/** The operation settings strip's disclosure state, which lives as long as the window for the same reason. */
	val operationStrip = OperationStripState()

	/**
	 * The localized base name new and imported workspaces are named from (deduped) - the same string the "+"
	 * button passes to onCreate, so the menu's New Workspace and the tab strip agree.  The shell resolves it
	 * inside its locale key after every language switch, and the workspace commands read it when they run.
	 */
	var newWorkspaceBaseName: String = ""

	/**
	 * The composition locals these controllers back, for the shell to provide over its whole content.
	 *
	 * @return Array The provided values, one per controller a descendant reads.
	 */
	fun compositionLocals(): Array<ProvidedValue<*>> =
		arrayOf(
			LocalAreaDragController provides dragController,
			LocalMenuBarController provides menuBarController,
			LocalInlineEditController provides inlineEditController,
			LocalKeyCapture provides keyCapture,
			LocalRowDragCancel provides rowDragCancel,
			LocalScrubCancel provides scrubCancel,
			LocalSplitterDragCancel provides splitterDragCancel,
			LocalKeyableHover provides keyableHover,
			LocalKeyformSheetViews provides keyformSheetViews,
			LocalRelationPick provides relationPick,
			LocalHoveredSurfaceTracker provides hoveredSurfaces,
			LocalAreaCameraHub provides areaCameras,
			LocalOperationStrip provides operationStrip,
			LocalOperationStripArea provides operationStripArea,
		)

	/**
	 * What the modal key ladder consults for one key event, over these controllers.
	 *
	 * @param EditorSession?   editorSession   The open document's session, or null with no document.
	 * @param SelectionHandle? selection       The object-selection handle, or null with no document.
	 * @param CommandRegistry  commandRegistry The registry pie picks and the fallthrough dispatch into.
	 * @param Keymap           keymap          The active keymap for the fallthrough dispatch.
	 * @return ShellModalState The ladder's state.
	 */
	fun modalState(
		editorSession: EditorSession?,
		selection: SelectionHandle?,
		commandRegistry: CommandRegistry,
		keymap: Keymap,
	): ShellModalState =
		ShellModalState(
			overlays = overlays,
			menuBarController = menuBarController,
			inlineEditController = inlineEditController,
			keyCapture = keyCapture,
			editorSession = editorSession,
			selection = selection,
			dragController = dragController,
			splitterDragCancel = splitterDragCancel,
			rowDragCancel = rowDragCancel,
			scrubCancel = scrubCancel,
			relationPick = relationPick,
			keyformSheets = keyformSheetViews,
			commandRegistry = commandRegistry,
			keymap = keymap,
		)
}

/**
 * The shell's controllers, built on the first composition and kept for the shell's lifetime.
 *
 * @param InterfaceLayout initialLayout  The layout the layout controller starts from; read once.
 * @param Function        onLayoutChange Called with every new layout, for persistence.
 * @return ShellControllers The shell's controllers.
 */
@Composable
internal fun rememberShellControllers(
	initialLayout: InterfaceLayout,
	onLayoutChange: (InterfaceLayout) -> Unit,
): ShellControllers {
	// The layout controller outlives recompositions, so it publishes through a live reference to the
	// persistence hook rather than capturing the first composition's lambda.
	val currentOnLayoutChange by rememberUpdatedState(onLayoutChange)
	// Quick Setup's visibility and the alert-family queues are the app's, held across the document swaps that
	// rebuild this shell; read once, since the app's holders live as long as the app and the command tables
	// close over these overlays.
	val quickSetup = LocalQuickSetup.current
	val appAlerts = LocalAppAlerts.current
	return remember {
		ShellControllers(
			initialLayout = initialLayout,
			onLayoutChange = { newLayout -> currentOnLayoutChange(newLayout) },
			quickSetup = quickSetup ?: QuickSetupState(visible = false),
			appAlerts = appAlerts ?: AppAlertQueues(),
		)
	}
}