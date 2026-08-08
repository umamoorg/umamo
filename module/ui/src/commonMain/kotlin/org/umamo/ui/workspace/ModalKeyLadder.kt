package org.umamo.ui.workspace

import androidx.compose.ui.input.key.Key
import org.umamo.edit.EditorMode
import org.umamo.edit.SelectionOps
import org.umamo.edit.TransformAxisConstraint
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.keyName
import org.umamo.ui.viewport.pieMenuEntriesFor

/*
 * The shell's modal key ladder: the ordered Escape/Enter (and modal-only key) precedence that runs in
 * the root's onPreviewKeyEvent BEFORE the keymap.  Modal chrome and in-flight gestures must pre-empt
 * bound commands (Escape closes the topmost overlay, not area.dragCancel), and modal operators
 * swallow keys the keymap would otherwise claim - so this is the documented exception to "all input
 * dispatches through the action registry": the ladder decides WHO owns the key, and everything it
 * does not consume falls through to the keymap dispatch at the bottom.  Order is the contract; add
 * new modal states at the precedence their UI stacking implies, and pin the new arm in
 * ModalKeyLadderTest - which asserts this order arm by arm, and for the combinations where two
 * modals are open at once.
 */

/**
 * Routes one key press through the shell's modal precedence, falling through to the keymap dispatch
 * when no modal state owns it.
 *
 * @param ShellKeyStroke stroke The decoded key press.
 * @param ShellModalState state Everything the precedence consults.
 * @return Boolean True when the event was consumed.
 */
internal fun handleModalKeyLadder(stroke: ShellKeyStroke, state: ShellModalState): Boolean =
	with(state) {
		val closeOpenMenu = menuBarController.closeOpenMenu
		val cancelInlineEdit = inlineEditController.cancel
		val isEscapeDown = stroke.isDown && stroke.key == Key.Escape
		val isEnterDown = stroke.isDown && (stroke.key == Key.Enter || stroke.key == Key.NumPadEnter)
		when {
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
			// The export-report alert is modal the same way: Escape or Enter dismisses it (the export
			// itself already happened; this only acknowledges the notices).
			overlays.exportReport != null -> {
				if (isEscapeDown || isEnterDown) {
					overlays.exportReport = null
				}
				true
			}
			// An open menu owns the keyboard like the overlays below it: Escape closes it, every other key
			// is inert.  It has to claim the rest rather than pass them on - the bar's dropdowns open
			// non-focusable so its labels keep receiving hover for the sweep-to-switch, which leaves the
			// host window focused and this ladder seeing every key first, ahead of the bar's own handler
			// (which claims only Escape).  Passing them on fired shortcuts behind the open menu.
			closeOpenMenu != null -> {
				if (isEscapeDown) {
					closeOpenMenu()
					true
				} else {
					false
				}
			}
			// The overlays that hold their own focus - preferences, the two Help dialogs, the palette -
			// are one family: Escape closes the topmost (instead of falling through to area.dragCancel)
			// and every other key yields to that overlay's own content, exactly as an open inline editor
			// does.  Preferences yields to its dropdown popups, which are focusable and so claim their own
			// Escape before a key ever reaches here; the Help dialogs to the credits scroll and the about
			// links; the palette to its search field.
			overlays.selfFocusedOverlayOpen -> {
				if (isEscapeDown) {
					overlays.closeTopmostSelfFocused()
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
			// An in-flight modal operator (G/S/R over mesh vertices, whole drawables, or texture
			// coordinates) owns Escape: cancel it before any selection clear, so cancelling a grab does
			// not also wipe the selection.  The overlay observes its latch going null and discards its
			// preview.  One arm for all three families - they are mutually exclusive, and clearing
			// dispatches to the family that is actually running.
			isEscapeDown && editorSession?.activeOperator != null -> {
				editorSession.clearActiveOperator()
				true
			}
			// Enter confirms an in-flight modal gesture (mirroring a primary click); the driving overlay
			// holds the working positions and commits on this shared signal, whichever family latched.
			isEnterDown && editorSession?.activeOperator != null -> {
				editorSession.requestMeshConfirm()
				true
			}
			// An open pie menu owns the keyboard: Escape closes it, 1..N instantly picks the
			// matching entry (the ordinal each chip draws - the shared entry order), and
			// everything else is swallowed so shortcuts cannot fire under the ring.
			editorSession?.activePieMenu?.value != null && stroke.isDown -> {
				val openPie = editorSession.activePieMenu.value
				if (stroke.key == Key.Escape) {
					editorSession.closePieMenu()
				} else if (openPie != null) {
					val digit =
						when (stroke.key) {
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
			stroke.isDown && stroke.key == Key.X && editorSession?.activeOperator != null -> {
				editorSession.toggleAxisConstraint(TransformAxisConstraint.AxisX)
				true
			}
			stroke.isDown && stroke.key == Key.Z && editorSession?.activeOperator != null -> {
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
			// An armed keyform-sheet marquee owns Escape, before the clear-selection branch below.  It hides
			// the OS cursor while armed, so a mode with no way out leaves the pointer invisible over the
			// sheet until the user happens to press and release somewhere.
			isEscapeDown && keyformSheets.armedBoxSelect() != null -> {
				keyformSheets.armedBoxSelect()?.disarmBoxSelect?.invoke()
				true
			}
			// An armed relation pick (a Properties field's eyedropper) owns Escape: cancel the pick before the
			// clear-selection branch below, so abandoning a pick never also wipes the user's selection.  Like
			// the zoom region this is mode-agnostic, and it resolves from the outliner as well as a viewport.
			isEscapeDown && relationPick.request != null -> {
				relationPick.cancel()
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
			// An in-flight divider (splitter) drag owns Escape, before the clear-selection arm below.
			// It needs its own arm because it is NOT the area corner drag the three arms below gate on:
			// its session lives in the dragged SplitContainer, never touches AreaDragController, and so
			// left isDragging false - Escape fell through and cleared the object selection while the user
			// was only resizing a panel.  Routed through the registry like the row-drag cancel.
			isEscapeDown && splitterDragCancel.cancel != null -> {
				commandRegistry.invoke("area.dragCancel")
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
			// off an in-flight area CORNER drag so that still yields to area.dragCancel - a divider
			// (splitter) drag is a separate mechanism this gate does not see, and cannot cancel.
			// Blender-parity: with nothing in flight this is a harmless no-op that consumes Escape and
			// never clears selection.
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
			else -> handleShellKey(stroke, keymap, commandRegistry)
		}
	}

/**
 * Translates a key press into a [KeyChord] and, if the keymap binds it, invokes the bound command
 * through the registry - the live keybinding-lookup path.  The position→name mapping is the shared
 * [keyName] table, the same one the rebindings editor captures with, so any chord a user can bind there
 * also dispatches here.
 *
 * @param ShellKeyStroke stroke The decoded key press.
 * @param Keymap keymap The active keymap.
 * @param CommandRegistry registry The command registry to dispatch into.
 * @return Boolean true if a bound command ran (event consumed); false otherwise.
 */
private fun handleShellKey(stroke: ShellKeyStroke, keymap: Keymap, registry: CommandRegistry): Boolean {
	if (!stroke.isDown) {
		return false
	}
	val pressedKeyName = keyName(stroke.key) ?: return false
	val chord =
		KeyChord(
			keyName = pressedKeyName,
			primaryModifier = stroke.primaryModifier,
			shift = stroke.shift,
			alt = stroke.alt,
		)
	val commandId = keymap.commandFor(chord) ?: return false
	return registry.invoke(commandId)
}