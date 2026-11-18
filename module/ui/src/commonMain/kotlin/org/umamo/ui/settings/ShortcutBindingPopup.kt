package org.umamo.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.settings.Settings
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.acceleratorModifierParts
import org.umamo.ui.action.acceleratorParts
import org.umamo.ui.action.keyChordOf
import org.umamo.ui.action.keyName
import org.umamo.ui.action.loadKeymap
import org.umamo.ui.action.pressHoldsLiteralControl
import org.umamo.ui.action.pressHoldsPrimary
import org.umamo.ui.kit.KeyChordView
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.Button
import org.umamo.ui.kit.button.CloseButton
import org.umamo.ui.kit.specialKeyIcon
import org.umamo.ui.kit.textentry.LocalKeyCapture
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_close
import org.umamo.ui.resources.settings_keybindings_conflict
import org.umamo.ui.resources.settings_keybindings_conflict_used
import org.umamo.ui.resources.settings_keybindings_reassign
import org.umamo.ui.resources.settings_keybindings_save
import org.umamo.ui.resources.settings_keybindings_shared
import org.umamo.ui.resources.settings_keybindings_type_hint
import org.umamo.ui.settings.ShortcutSlot.Alternative
import org.umamo.ui.settings.ShortcutSlot.Primary
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.canOverlap

/** The width of the popup card. */
private val POPUP_WIDTH = 230.dp

/** The height of the key view's inner box, where the caps are shown. */
private val KEY_VIEW_HEIGHT = 104.dp

/** The height of the popup's own button. */
private val BUTTON_HEIGHT = 38.dp

/** The gap between the popup's rows. */
private val POPUP_ROW_GAP = 8.dp

/**
 * The editor for one of a command's binding slots: the key view, the live capture, and the way out - saving
 * a new binding, or closing without changing anything.
 *
 * A combination another command already holds is reported below the view: in red when the two would compete,
 * and in plain words when they cannot - a chord the UV editor and the 2D viewport both answer to is not a
 * conflict, because the two never apply at once.
 *
 * @param ShortcutEditRequest request The command and slot being edited, and the cell to open under.
 * @param Function onDismiss Closes the popup.
 */
@Composable
internal fun ShortcutBindingPopup(
	request: ShortcutEditRequest,
	onDismiss: () -> Unit,
) {
	val settings = LocalSettings.current
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val shortcutEditor = LocalShortcutEditor.current
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val shapes = LocalUmamoShapes.current

	val command = commands[request.commandId]
	val title = command?.title?.let { titleResource -> stringResource(titleResource) } ?: request.commandId
	val current = keymap.slotsFor(request.commandId)
	val currentChord = if (request.slot == Primary) current.primary else current.alternative

	// What the user has pressed so far.  It survives trying one combination after another: the last one
	// caught is the one Save commits.  An editor opened on a chord already carries it.
	var captured by remember(request) { mutableStateOf<KeyChord?>(request.captured) }

	// The caps the view shows while the user is pressing: the modifiers alone until a key joins them.
	var liveParts by remember(request) { mutableStateOf<List<String>?>(null) }

	// The chord the view shows: what has been caught, or the slot's own binding until something is caught.
	val shown = captured ?: currentChord
	val shownParts = liveParts ?: shown?.let { chord -> acceleratorParts(chord) }

	// The command another command already holds the shown chord on, and whether the two can ever compete.
	val holder =
		shown?.let { chord ->
			keymap.commandFor(chord)?.takeIf { holderId -> holderId != request.commandId }
		}
	val holderTitle =
		holder?.let { holderId -> commands[holderId]?.title?.let { titleResource -> stringResource(titleResource) } ?: holderId }
	val competes =
		holder != null &&
			command != null &&
			commands[holder]?.spaces?.canOverlap(command.spaces) != false

	Box(modifier = Modifier.fillMaxSize()) {
		Surface(
			modifier =
				Modifier
					.offset { anchorOffset(request.anchor) }
					.width(POPUP_WIDTH),
			color = colors.panelBackground,
			shape = shapes.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.padding(12.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = title,
						style = typography.titleMedium,
						color = colors.text,
						modifier = Modifier.weight(1f),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					CloseButton(onClick = onDismiss, contentDescription = stringResource(Res.string.settings_close))
				}

				Spacer(modifier = Modifier.height(POPUP_ROW_GAP))

				KeyView(
					parts = shownParts,
					activeIndex = if (liveParts != null) (shownParts?.size ?: 0) - 1 else -1,
					onCaptured = { chord ->
						captured = chord
						liveParts = acceleratorParts(chord)
					},
					onModifier = { parts -> liveParts = parts },
					onCancelCapture = { onDismiss() },
					keyIcon = if (liveParts == null) shown?.let { chord -> specialKeyIcon(chord.keyName) } else null,
				)

				if (holder != null && holderTitle != null) {
					Spacer(modifier = Modifier.height(POPUP_ROW_GAP))
					Text(
						text =
							if (competes) {
								stringResource(Res.string.settings_keybindings_conflict_used, formatParts(shown), holderTitle)
							} else {
								stringResource(Res.string.settings_keybindings_shared, formatParts(shown), holderTitle)
							},
						style = typography.bodySmall,
						color = if (competes) colors.signalBad else colors.textMuted,
					)
				}

				Spacer(modifier = Modifier.height(POPUP_ROW_GAP))

				Button(
					label = stringResource(Res.string.settings_keybindings_save),
					onClick = {
						val chord = captured

						if (chord == null) {
							onDismiss()
						} else if (holder != null && competes) {
							// The chord belongs to another command the two would fight over: the shell's
							// confirm queue asks whether to take it.
							raiseConflict(commands, settings, request, chord, holder, holderTitle, shortcutEditor, onDismiss)
						} else {
							writeSlot(settings, request.commandId, request.slot, chord)
							// The command has a binding again, so it is no longer the one that lost one.
							shortcutEditor.clearConflict(request.commandId)
							onDismiss()
						}
					},
					primary = true,
					enabled = captured != null,
					height = BUTTON_HEIGHT,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}
}

/**
 * Where the popup sits: under the cell it was opened from, reaching to the LEFT of it, so a cell near the
 * right edge of the panel does not push the popup off the window.
 *
 * @param IntRect? anchor The cell's bounds, or null.
 * @return IntOffset The offset within the overlay.
 */
private fun anchorOffset(anchor: IntRect?): IntOffset =
	if (anchor == null) {
		IntOffset(0, 0)
	} else {
		IntOffset(maxOf(0, anchor.right - POPUP_WIDTH.value.toInt()), anchor.bottom)
	}

/**
 * The key view: the caps of the binding, and the live capture that fills it.
 *
 * @param List<String>? parts The caps to show, or null when nothing is bound and nothing is pressed.
 * @param Int activeIndex The cap just pressed, or -1 for none.
 * @param Function onCaptured Receives each chord the user presses.
 * @param Function onModifier Receives the modifier caps as they are pressed.
 * @param Function onCancelCapture Called when the user cancels with Escape.
 * @param UmamoIcon? keyIcon The glyph the key's own cap carries.
 */
@Composable
private fun KeyView(
	parts: List<String>?,
	activeIndex: Int,
	onCaptured: (KeyChord) -> Unit,
	onModifier: (List<String>) -> Unit,
	onCancelCapture: () -> Unit,
	keyIcon: UmamoIcon? = null,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val shapes = LocalUmamoShapes.current
	val focusRequester = remember { FocusRequester() }
	val keyCapture = LocalKeyCapture.current

	LaunchedEffect(Unit) {
		focusRequester.requestFocus()
	}

	DisposableEffect(Unit) {
		keyCapture.begin()

		onDispose {
			keyCapture.end()
		}
	}

	Box(
		modifier =
			Modifier
				.fillMaxWidth()
				.heightIn(min = KEY_VIEW_HEIGHT)
				.clip(shapes.small)
				.background(colors.controlBackground)
				.border(BorderStroke(1.dp, colors.controlBorder), shapes.small)
				.focusRequester(focusRequester)
				.onPreviewKeyEvent { event -> handleCaptureKey(event, onCaptured, onModifier, onCancelCapture) }
				.focusable()
				.padding(8.dp),
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			if (parts != null) {
				KeyChordView(
					parts = parts,
					activeIndex = activeIndex,
					large = true,
					keyIcon = keyIcon,
				)
			}

			Spacer(modifier = Modifier.height(POPUP_ROW_GAP))
			Text(
				text = stringResource(Res.string.settings_keybindings_type_hint),
				style = typography.bodyMedium,
				color = colors.textMuted,
			)
		}
	}
}

/**
 * Turns a key event into a chord and reports it: key-up is ignored, a modifier shows its cap on its own, and
 * Escape cancels.  Every key press reports a chord, so the user can try one combination after another and
 * keep the last one.
 *
 * @param KeyEvent event The key event during capture.
 * @param Function onCaptured Receives the captured chord.
 * @param Function onModifier Receives the modifier caps as they are pressed.
 * @param Function onCancelCapture Called on Escape.
 * @return Boolean true when the event was handled (consumed).
 */
private fun handleCaptureKey(
	event: KeyEvent,
	onCaptured: (KeyChord) -> Unit,
	onModifier: (List<String>) -> Unit,
	onCancelCapture: () -> Unit,
): Boolean {
	if (event.type != KeyEventType.KeyDown) {
		return false
	}

	val noModifiers = !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed && !event.isAltPressed

	if (event.key == Key.Escape && noModifiers) {
		onCancelCapture()

		return true
	}

	val held =
		KeyChord(
			keyName = "",
			primaryModifier = pressHoldsPrimary(event.isCtrlPressed, event.isMetaPressed),
			shift = event.isShiftPressed,
			alt = event.isAltPressed,
			controlKey = pressHoldsLiteralControl(event.isCtrlPressed),
		)

	// A modifier on its own shows its cap at once, so the view says what is held before any key joins it.
	if (event.key.isModifierKey()) {
		onModifier(acceleratorModifierParts(held))

		return true
	}

	val pressedKeyName = keyName(event.key) ?: return false
	val chord =
		keyChordOf(
			keyName = pressedKeyName,
			controlPressed = event.isCtrlPressed,
			metaPressed = event.isMetaPressed,
			shiftPressed = event.isShiftPressed,
			altPressed = event.isAltPressed,
		)
	onCaptured(chord)

	return true
}

/**
 * Whether the key is one of the modifiers, which show a cap on their own rather than a chord.
 *
 * @return Boolean True when the key is a modifier.
 */
private fun Key.isModifierKey(): Boolean =
	this == Key.ShiftLeft ||
		this == Key.ShiftRight ||
		this == Key.CtrlLeft ||
		this == Key.CtrlRight ||
		this == Key.MetaLeft ||
		this == Key.MetaRight ||
		this == Key.AltLeft ||
		this == Key.AltRight

/**
 * The chord's parts as one readable string, for the warning under the key view.
 *
 * @param KeyChord? chord The chord to name.
 * @return String The chord's caps, joined by a plus.
 */
private fun formatParts(chord: KeyChord?): String =
	chord?.let { bound -> acceleratorParts(bound).joinToString(separator = " + ") } ?: ""

/**
 * Asks the shell whether the chord may be taken from the command that holds it.
 *
 * Taking it leaves the other command with nothing, so the editor is handed to that command when the user
 * confirms: the list jumps to it and its slot opens, so the binding that was taken can be given something
 * else instead of quietly disappearing.
 *
 * @param CommandRegistry commands The registry the confirm is raised through.
 * @param Settings settings The settings store to write to.
 * @param ShortcutEditRequest request The slot being edited.
 * @param KeyChord chord The chord taken.
 * @param String holderCommandId The command that holds it.
 * @param String? holderTitle The command's title, already resolved.
 * @param ShortcutEditorController shortcutEditor The editor, handed to the command that lost the chord.
 * @param Function onDismiss Closes the popup once the chord is taken.
 */
private fun raiseConflict(
	commands: CommandRegistry,
	settings: Settings,
	request: ShortcutEditRequest,
	chord: KeyChord,
	holderCommandId: String,
	holderTitle: String?,
	shortcutEditor: ShortcutEditorController,
	onDismiss: () -> Unit,
) {
	commands.invoke(
		"document.confirm",
		ConfirmRequest(
			message = Res.string.settings_keybindings_conflict,
			arguments = listOf(formatParts(chord), holderTitle ?: holderCommandId),
			confirmLabel = Res.string.settings_keybindings_reassign,
		) {
			// Which slot held the chord, read BEFORE the write takes it away.
			val loserSlot = slotHolding(settings, holderCommandId, chord)
			writeSlot(settings, request.commandId, request.slot, chord)
			// The editor closes first and then is asked for on the command that lost the chord: closing it
			// after would close the request the ask had just made.  It waits for the list to bring that
			// command into view, so it opens under the right cell.
			onDismiss()
			// That command's row stays red until it is given another binding.
			shortcutEditor.markConflicted(holderCommandId, loserSlot)
			shortcutEditor.openAfterScroll(holderCommandId, loserSlot)
		},
	)
}

/**
 * Which of a command's slots holds the chord.
 *
 * @param Settings settings The settings store to read the keymap from.
 * @param String commandId The command to look at.
 * @param KeyChord chord The chord to find.
 * @return ShortcutSlot The slot holding it, or the primary when it holds neither.
 */
private fun slotHolding(settings: Settings, commandId: String, chord: KeyChord): ShortcutSlot {
	val slots = loadKeymap(settings).slotsFor(commandId)

	return if (slots.alternative == chord) ShortcutSlot.Alternative else ShortcutSlot.Primary
}