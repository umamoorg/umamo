package org.umamo.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.IntRect
import org.umamo.settings.Settings
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.resetAlternativeBinding
import org.umamo.ui.action.resetPrimaryBinding
import org.umamo.ui.action.setAlternativeBinding
import org.umamo.ui.action.setPrimaryBinding

/**
 * Which of a command's two binding slots the shortcut editor is editing.
 *
 * @property Primary The binding the rest of the editor shows.
 * @property Alternative The second binding, empty unless a preset binds the command twice or the user gives
 *   it one.
 */
enum class ShortcutSlot {
	Primary,
	Alternative,
}

/**
 * The shortcut editor's open request: the command being edited, the slot the edit is for, and where the cell
 * it was opened from sits, so the editor can open under it.
 *
 * @property String commandId The command being edited.
 * @property ShortcutSlot slot The slot the edit is for.
 * @property IntRect? anchor The cell's bounds in the window, or null when the editor opens centred.
 * @property KeyChord? captured The chord the editor opens holding, or null when it opens empty.
 */
data class ShortcutEditRequest(
	val commandId: String,
	val slot: ShortcutSlot,
	val anchor: IntRect? = null,
	val captured: KeyChord? = null,
)

/**
 * The seam between the shortcut editor in the settings window and the shell's key routing.
 *
 * The editor has to be shell-owned because the shell's root handler sees every key before the settings
 * window does: a routing that read the editor's state from the settings window would find nothing there,
 * and Escape would close Preferences instead of the editor.
 *
 * @property ShortcutEditRequest? request The editor's open request, or null while it is closed.
 */
class ShortcutEditorController {
	/** The editor's open request, or null while it is closed. */
	var request by mutableStateOf<ShortcutEditRequest?>(null)
		private set

	/** An editor waiting for the list to bring its command into view, or null when there is none. */
	var pending by mutableStateOf<ShortcutEditRequest?>(null)
		private set

	/** Each command's slot cells, so an editor opened on a command lands under that command's cell. */
	private val cells = mutableMapOf<String, CellAnchor>()

	/**
	 * The shortcut panel's search field, published while the panel is on screen, so the window's own key
	 * routing can hand it the keyboard.
	 */
	var searchField: FocusRequester? = null

	/** The command that lost its binding to a reassign, marked until it is given another one. */
	var conflicted by mutableStateOf<ShortcutEditRequest?>(null)
		private set

	/**
	 * The cell a command's slot is drawn in, or null when it has never been drawn.
	 *
	 * @param String commandId The command.
	 * @param ShortcutSlot slot The slot.
	 * @return CellAnchor? The cell.
	 */
	private fun cellFor(commandId: String, slot: ShortcutSlot): CellAnchor? = cells["$commandId/${slot.name}"]

	/**
	 * Registers a cell so the editor can be anchored to it.
	 *
	 * @param String commandId The command.
	 * @param ShortcutSlot slot The slot.
	 * @param CellAnchor anchor The cell.
	 */
	fun registerCell(commandId: String, slot: ShortcutSlot, anchor: CellAnchor) {
		cells["$commandId/${slot.name}"] = anchor
	}

	/**
	 * The bounds of a command's slot cell, or null when it has never been drawn.
	 *
	 * @param String commandId The command.
	 * @param ShortcutSlot slot The slot.
	 * @return IntRect? The cell's bounds.
	 */
	fun anchorFor(commandId: String, slot: ShortcutSlot): IntRect? = cellFor(commandId, slot)?.bounds

	/**
	 * Opens the editor on one of a command's slots.
	 *
	 * @param String commandId The command to edit.
	 * @param ShortcutSlot slot The slot to edit.
	 * @param IntRect? anchor The cell the editor should open under, or null to open it centred.
	 * @param KeyChord? captured The chord the editor opens holding.
	 */
	fun open(commandId: String, slot: ShortcutSlot, anchor: IntRect? = null, captured: KeyChord? = null) {
		request = ShortcutEditRequest(commandId, slot, anchor, captured)
	}

	/**
	 * Asks for the editor to open on one of a command's slots, once the list has brought it into view.
	 *
	 * A jump moves the list, and the cell the editor belongs to only has its bounds once the list has
	 * settled - so the editor waits for that rather than opening at a position the list has left behind.
	 *
	 * @param String commandId The command to edit.
	 * @param ShortcutSlot slot The slot to edit.
	 */
	fun openAfterScroll(commandId: String, slot: ShortcutSlot) {
		pending = ShortcutEditRequest(commandId, slot)
	}

	/**
	 * Marks the command that lost a binding to a reassign, so its row stays red until it is given another.
	 *
	 * @param String commandId The command that lost the binding.
	 * @param ShortcutSlot slot The slot that lost it.
	 */
	fun markConflicted(commandId: String, slot: ShortcutSlot) {
		conflicted = ShortcutEditRequest(commandId, slot)
	}

	/**
	 * Clears the mark on a command that has been given another binding.
	 *
	 * @param String commandId The command.
	 */
	fun clearConflict(commandId: String) {
		if (conflicted?.commandId == commandId) {
			conflicted = null
		}
	}

	/** Clears the request waiting for the list to catch up with it. */
	fun clearPending() {
		pending = null
	}

	/** Closes the editor. */
	fun close() {
		request = null
	}
}

/**
 * A cell's last measured bounds, held outside the composition.
 *
 * The measurement lands on every layout pass, and a scroll is a layout pass - so holding it in snapshot
 * state recomposed every visible cell on every frame of a scroll.  A plain holder is read only when a cell
 * is clicked, which is the one moment the value matters.
 */
class CellAnchor {
	/** The cell's bounds in the window, as of the last layout. */
	var bounds: IntRect? = null
}

/**
 * Binds a chord to one of a command's slots.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command to bind.
 * @param ShortcutSlot slot The slot to bind.
 * @param KeyChord chord The chord to bind.
 */
internal fun writeSlot(settings: Settings, commandId: String, slot: ShortcutSlot, chord: KeyChord) {
	when (slot) {
		ShortcutSlot.Primary -> setPrimaryBinding(settings, commandId, chord)
		ShortcutSlot.Alternative -> setAlternativeBinding(settings, commandId, chord)
	}
}

/**
 * Puts one of a command's slots back to its default.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command to reset.
 * @param ShortcutSlot slot The slot to reset.
 */
internal fun resetSlot(settings: Settings, commandId: String, slot: ShortcutSlot) {
	when (slot) {
		ShortcutSlot.Primary -> resetPrimaryBinding(settings, commandId)
		ShortcutSlot.Alternative -> resetAlternativeBinding(settings, commandId)
	}
}

/**
 * Leaves one of a command's slots unbound.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command to unbind.
 * @param ShortcutSlot slot The slot to unbind.
 */
internal fun unbindSlot(settings: Settings, commandId: String, slot: ShortcutSlot) {
	when (slot) {
		ShortcutSlot.Primary -> setPrimaryBinding(settings, commandId, null)
		ShortcutSlot.Alternative -> setAlternativeBinding(settings, commandId, null)
	}
}

/**
 * The shortcut editor's controller, provided by the shell over its whole content so the settings window and
 * the modal ladder read the same one.
 */
val LocalShortcutEditor = staticCompositionLocalOf { ShortcutEditorController() }