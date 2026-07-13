package org.umamo.ui.workspace

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionOps
import org.umamo.edit.TransformAxisConstraint
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.keyName
import org.umamo.ui.kit.InlineEditController
import org.umamo.ui.kit.MenuBarController
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.viewport.pieMenuEntriesFor

/*
 * The shell's modal key ladder: the ordered Escape/Enter (and modal-only key) precedence that runs in
 * the root's onPreviewKeyEvent BEFORE the keymap.  Modal chrome and in-flight gestures must pre-empt
 * bound commands (Escape closes the topmost overlay, not area.dragCancel), and modal operators
 * swallow keys the keymap would otherwise claim - so this is the documented exception to "all input
 * dispatches through the action registry": the ladder decides WHO owns the key, and everything it
 * does not consume falls through to the keymap dispatch at the bottom.  Order is the contract; add
 * new modal states at the precedence their UI stacking implies.
 *
 * シェルのモーダルキー階梯。キーマップより先に走る Escape/Enter の優先順位で、最前面のモーダルが
 * キーを所有する。消費しなかったキーは末尾でキーマップに落ちる。順序が仕様。
 */

/**
 * Routes one root key event through the shell's modal precedence, falling through to the keymap
 * dispatch when no modal state owns it.
 *
 * @param KeyEvent event The raw key event from onPreviewKeyEvent.
 * @param ShellOverlayState overlays The shell's overlay flags (confirm, alert, palette, settings, Help).
 * @param MenuBarController menuBarController The menu-bar seam (an open menu claims Escape).
 * @param InlineEditController inlineEditController The inline-editor seam (an open field claims the keyboard).
 * @param EditorSession? editorSession The open document's session, or null.
 * @param SelectionHandle? selection The object-selection handle, or null with no document.
 * @param AreaDragController dragController The area drag state (an in-flight drag defers Escape to area.dragCancel).
 * @param RowDragCancelController rowDragCancel The panel row-drag seam (an in-flight row drag claims Escape).
 * @param CommandRegistry commandRegistry The registry modal picks and the fallthrough dispatch into.
 * @param Keymap keymap The active keymap for the fallthrough dispatch.
 * @return Boolean True when the event was consumed.
 */
internal fun handleModalKeyLadder(
	event: KeyEvent,
	overlays: ShellOverlayState,
	menuBarController: MenuBarController,
	inlineEditController: InlineEditController,
	editorSession: EditorSession?,
	selection: SelectionHandle?,
	dragController: AreaDragController,
	rowDragCancel: RowDragCancelController,
	commandRegistry: CommandRegistry,
	keymap: Keymap,
): Boolean {
	val closeOpenMenu = menuBarController.closeOpenMenu
	val cancelInlineEdit = inlineEditController.cancel
	val isEscapeDown = event.type == KeyEventType.KeyDown && event.key == Key.Escape
	val isEnterDown =
		event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
	return when {
		// A confirm dialog is the topmost modal: it owns the keyboard entirely.  Escape cancels
		// it (like its Cancel button); every other key is swallowed so no shortcut fires behind
		// it - notably Space, which would otherwise open the palette over the dialog now that the
		// dialog reclaims root focus.
		overlays.pendingConfirm != null -> {
			if (isEscapeDown) {
				overlays.pendingConfirm = null
				true
			} else {
				true
			}
		}
		// The file-open alert is modal like the confirm dialog: Escape or Enter dismisses it
		// (like its OK button); every other key is swallowed so no shortcut fires behind it.
		overlays.openFailure != null -> {
			if (isEscapeDown || isEnterDown) {
				overlays.openFailure = null
			}
			true
		}
		closeOpenMenu != null && isEscapeDown -> {
			closeOpenMenu()
			true
		}
		// The preferences window is a modal overlay while open: Escape closes it, and every
		// other key yields to its own content (the dropdown popups handle their own keys).
		// An open dropdown is a focusable popup, so its Escape closes the dropdown before a
		// key event ever reaches here - this branch only closes the window itself.
		overlays.settingsVisible -> {
			if (isEscapeDown) {
				overlays.settingsVisible = false
				true
			} else {
				false
			}
		}
		// The Help dialogs are modal overlays of the same family: Escape closes, other keys
		// fall through to their own content (the credits sheet's scroll, the about links).
		overlays.aboutVisible -> {
			if (isEscapeDown) {
				overlays.aboutVisible = false
				true
			} else {
				false
			}
		}
		overlays.creditsVisible -> {
			if (isEscapeDown) {
				overlays.creditsVisible = false
				true
			} else {
				false
			}
		}
		// The palette is a modal overlay while open: Escape closes it (instead of falling
		// through to area.dragCancel) and every other key yields to the palette's own content
		// (its search field), exactly as an open inline editor does.
		overlays.paletteVisible -> {
			if (isEscapeDown) {
				overlays.paletteVisible = false
				true
			} else {
				false
			}
		}
		// An inline editor (workspace rename) owns the keyboard while open: Escape cancels it,
		// and every other key falls through to the field - otherwise the shell's shortcuts
		// (Space → palette, letters → file commands) would fire while the user types a name.
		cancelInlineEdit != null -> {
			if (isEscapeDown) {
				cancelInlineEdit()
				true
			} else {
				false
			}
		}
		// An in-flight modal mesh operator (G/S/R) owns Escape: cancel it before any selection
		// clear, so cancelling a grab does not also wipe the vertex selection. The overlay
		// observes the operator going null and discards its preview.
		isEscapeDown && editorSession?.activeMeshOperator?.value != null -> {
			editorSession.clearMeshOperator()
			true
		}
		// Enter confirms an in-flight modal mesh gesture (mirroring a primary click); the
		// overlay holds the working positions and commits on this signal.
		isEnterDown && editorSession?.activeMeshOperator?.value != null -> {
			editorSession.requestMeshConfirm()
			true
		}
		// An in-flight modal OBJECT operator (G/S/R over drawables) owns Escape / Enter the same
		// way, mutually exclusive with the mesh operator above: Escape cancels (the overlay re-syncs
		// the renderer as the operator clears), Enter confirms via the shared confirm signal.
		isEscapeDown && editorSession?.activeObjectOperator?.value != null -> {
			editorSession.clearObjectOperator()
			true
		}
		isEnterDown && editorSession?.activeObjectOperator?.value != null -> {
			editorSession.requestMeshConfirm()
			true
		}
		// An in-flight modal UV operator (G/S/R over texture coordinates) owns Escape / Enter the same
		// way - the three operator latches are mutually exclusive, so exactly one of these families of
		// branches can be live at a time.
		isEscapeDown && editorSession?.activeUvOperator?.value != null -> {
			editorSession.clearUvOperator()
			true
		}
		isEnterDown && editorSession?.activeUvOperator?.value != null -> {
			editorSession.requestMeshConfirm()
			true
		}
		// An open pie menu owns the keyboard: Escape closes it, 1..N instantly picks the
		// matching entry (the ordinal each chip draws - the shared entry order), and
		// everything else is swallowed so shortcuts cannot fire under the ring.
		editorSession?.activePieMenu?.value != null && event.type == KeyEventType.KeyDown -> {
			val openPie = editorSession.activePieMenu.value
			if (event.key == Key.Escape) {
				editorSession.closePieMenu()
			} else if (openPie != null) {
				val digit =
					when (event.key) {
						Key.One, Key.NumPad1 -> 1
						Key.Two, Key.NumPad2 -> 2
						Key.Three, Key.NumPad3 -> 3
						Key.Four, Key.NumPad4 -> 4
						Key.Five, Key.NumPad5 -> 5
						Key.Six, Key.NumPad6 -> 6
						Key.Seven, Key.NumPad7 -> 7
						Key.Eight, Key.NumPad8 -> 8
						else -> 0
					}
				val entry = if (digit > 0) pieMenuEntriesFor(openPie).getOrNull(digit - 1) else null
				if (entry != null && entry.enabled) {
					// Invoke-then-close, matching the pointer pick's order.
					commandRegistry.invoke(entry.commandId, entry.argument)
					editorSession.closePieMenu()
				}
			}
			true
		}
		// X / Z during a modal Grab / Scale toggle the axis lock (Blender's axis constraint).
		// This has to live in the ladder: the modal operator swallows pointer input in the
		// overlay and every other key here, so the keymap never sees these presses.  The
		// session no-ops for Rotate (one 2D rotation axis - nothing to lock).
		event.type == KeyEventType.KeyDown &&
			event.key == Key.X &&
			(
				editorSession?.activeMeshOperator?.value != null ||
					editorSession?.activeObjectOperator?.value != null ||
					editorSession?.activeUvOperator?.value != null
			) -> {
			editorSession.toggleAxisConstraint(TransformAxisConstraint.AxisX)
			true
		}
		event.type == KeyEventType.KeyDown &&
			event.key == Key.Z &&
			(
				editorSession?.activeMeshOperator?.value != null ||
					editorSession?.activeObjectOperator?.value != null ||
					editorSession?.activeUvOperator?.value != null
			) -> {
			editorSession.toggleAxisConstraint(TransformAxisConstraint.AxisZ)
			true
		}
		// An armed Box / Circle select tool owns Escape and Enter: both leave the tool, before the
		// selection-clear branch below. Resolve any in-flight gesture on the fast cancel signal FIRST
		// (a circle stroke keeps its paint, a box rubber-band is discarded), then disarm: clearSelectTool
		// alone routes the cleanup through a recomposition-gated effect that can lose the race to a mouse
		// release still in flight, letting a cancelled armed box commit through the idle Release path.
		(isEscapeDown || isEnterDown) && editorSession?.activeSelectTool?.value != null -> {
			editorSession.requestMeshGestureCancel()
			editorSession.clearSelectTool()
			true
		}
		// An armed Zoom Region gesture owns Escape: disarm it (mode-agnostic, so it precedes the
		// Object-only clear below).
		isEscapeDown && editorSession?.zoomRegionArmedArea?.value != null -> {
			editorSession.disarmZoomRegion()
			true
		}
		// An in-flight panel row drag (outliner / parameters) owns Escape: cancel it (through the
		// registry, mirroring area.dragCancel) before the clear-selection branch below - the press
		// that started the drag already selected the row, so that branch would otherwise swallow
		// Escape and deselect the dragged rows while the drag kept going.
		isEscapeDown && rowDragCancel.cancel != null -> {
			commandRegistry.invoke("row.dragCancel")
			true
		}
		// An in-flight non-armed viewport box drag owns Escape in any mode (the Object overlay
		// publishes the flag): abandon the rubber-band WITHOUT falling through to the Object-mode
		// clear-selection branch below, so cancelling a drag never also wipes the selection.
		isEscapeDown && editorSession?.viewportGestureActive?.value == true && !dragController.isDragging -> {
			editorSession.requestMeshGestureCancel()
			true
		}
		// In Edit mode, Escape abandons an in-flight non-armed box drag (armed tools and the
		// zoom region are handled above); the overlay clears its rubber-band on the signal. Gated
		// off an area drag so a splitter drag still yields to area.dragCancel. Blender-parity: with
		// nothing in flight this is a harmless no-op that consumes Escape and never clears selection.
		isEscapeDown && editorSession?.mode?.value == EditorMode.Edit && !dragController.isDragging -> {
			editorSession.requestMeshGestureCancel()
			true
		}
		// With no modal open and no in-flight corner drag, Escape clears a non-empty OBJECT
		// selection.  Gated to Object mode (a null session counts as Object): in Edit mode the
		// object selection holds the drawable being edited, so clearing it here would strand the
		// Edit session on a drawable nothing points at.  Blender's Edit mode leaves the selection
		// untouched on Escape, so Edit falls through to area.dragCancel (a no-op with no drag).
		// While a drag is active it yields to area.dragCancel (handled via handleShellKey below).
		isEscapeDown &&
			editorSession?.mode?.value != EditorMode.Edit &&
			!dragController.isDragging &&
			selection?.selection?.isEmpty == false -> {
			selection.set(SelectionOps.clear())
			true
		}
		else -> handleShellKey(event, keymap, commandRegistry)
	}
}

/**
 * Translates a Compose key event into a [KeyChord] and, if the keymap binds it, invokes the bound
 * command through the registry - the live keybinding-lookup path.  The position→name mapping is the shared
 * [keyName] table, the same one the rebindings editor captures with, so any chord a user can bind there
 * also dispatches here.
 *
 * Compose のキーイベントを KeyChord に変換し、キーマップが束縛していればレジストリ経由で実行する。
 * キー名は再割り当て UI と共有の [keyName] 表を使う。
 *
 * @param KeyEvent event The raw key event.
 * @param Keymap keymap The active keymap.
 * @param CommandRegistry registry The command registry to dispatch into.
 * @return Boolean true if a bound command ran (event consumed); false otherwise.
 */
private fun handleShellKey(event: KeyEvent, keymap: Keymap, registry: CommandRegistry): Boolean {
	if (event.type != KeyEventType.KeyDown) {
		return false
	}
	val pressedKeyName = keyName(event.key) ?: return false
	val chord =
		KeyChord(
			keyName = pressedKeyName,
			primaryModifier = event.isCtrlPressed || event.isMetaPressed,
			shift = event.isShiftPressed,
			alt = event.isAltPressed,
		)
	val commandId = keymap.commandFor(chord) ?: return false
	return registry.invoke(commandId)
}
