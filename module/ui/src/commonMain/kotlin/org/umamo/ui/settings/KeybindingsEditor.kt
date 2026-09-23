package org.umamo.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.KEYMAP_PRESET_IDS
import org.umamo.ui.action.KEYMAP_PRESET_KEY
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.action.keyName
import org.umamo.ui.action.rebindCommand
import org.umamo.ui.action.resetKeymapOverrides
import org.umamo.ui.action.unbindCommand
import org.umamo.ui.kit.LocalKeyCapture
import org.umamo.ui.kit.SCROLLBAR_THICKNESS
import org.umamo.ui.kit.SelectField
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.button.Button
import org.umamo.ui.rememberStringSetting
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_keybindings_clear
import org.umamo.ui.resources.settings_keybindings_conflict
import org.umamo.ui.resources.settings_keybindings_preset
import org.umamo.ui.resources.settings_keybindings_preset_quick
import org.umamo.ui.resources.settings_keybindings_press_shortcut
import org.umamo.ui.resources.settings_keybindings_reassign
import org.umamo.ui.resources.settings_keybindings_reset
import org.umamo.ui.resources.settings_keybindings_unbound
import org.umamo.ui.resources.settings_keymap_preset_blender
import org.umamo.ui.resources.settings_keymap_preset_cubism
import org.umamo.ui.resources.settings_keymap_preset_default
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.workspace.ConfirmRequest

/** A captured chord that collides with another command, held until its reassign prompt is raised in the shell. */
private data class KeybindingConflict(val commandId: String, val chord: KeyChord, val existingCommandId: String)

/** Stable chip width so the chord column lines up; the "press a shortcut" prompt is the widest label. */
private val CHORD_CHIP_MIN_WIDTH = 148.dp

/**
 * The keymap preset row, bound write-through to input.keybinding.preset: the keybindings editor and Quick
 * Setup share it.  A switch keeps the user's per-command overrides, which layer on whichever preset is chosen.
 *
 * @param Function onSelect Called after a preset is picked, for the host's own bookkeeping.
 */
@Composable
internal fun KeymapPresetSettingRow(onSelect: () -> Unit = {}, quick: Boolean = false) {
	var preset by rememberStringSetting(KEYMAP_PRESET_KEY, "default")
	// Preset display names are localized chrome; the ids stay stable (see KEYMAP_PRESET_IDS).
	val presetLabels =
		linkedMapOf(
			"default" to stringResource(Res.string.settings_keymap_preset_default),
			"cubism" to stringResource(Res.string.settings_keymap_preset_cubism),
			"blender" to stringResource(Res.string.settings_keymap_preset_blender),
		)
	SettingRow(label = stringResource(if (quick) Res.string.settings_keybindings_preset_quick else Res.string.settings_keybindings_preset)) {
		SelectField(
			selected = preset,
			options = KEYMAP_PRESET_IDS,
			label = { presetId -> presetLabels[presetId] ?: presetId },
			onSelect = { presetId ->
				preset = presetId
				onSelect()
			},
		)
	}
}

/**
 * The keybindings editor: pick a preset, then rebind individual commands.  The preset dropdown and every
 * rebind write through settings (input.keybinding.preset / .overrides) and the shell re-resolves the live
 * [LocalKeymap] reactively, so a change takes effect across menus, the palette, and dispatch immediately.
 *
 * Only titled commands appear (an untitled command is an internal action with no user-facing label); each
 * row shows the command, its current chord (or "unbound"), and a clear button.  Clicking the chord captures
 * the next key combination; if it is already bound elsewhere a confirm prompt offers to reassign it.  Reset
 * clears all user overrides back to the selected preset.
 *
 * キーバインド編集。プリセット選択と個別コマンドの再割り当て。変更は設定に書き込まれ、キーマップは即時反映される。
 */
@Composable
internal fun KeybindingsEditor() {
	val settings = LocalSettings.current
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	var capturingCommandId by remember { mutableStateOf<String?>(null) }
	var pendingConflict by remember { mutableStateOf<KeybindingConflict?>(null) }

	Box(modifier = Modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			KeymapPresetSettingRow(onSelect = { capturingCommandId = null })
			Spacer(modifier = Modifier.height(SETTING_ROW_SPACING))
			Row(modifier = Modifier.fillMaxWidth()) {
				Button(
					label = stringResource(Res.string.settings_keybindings_reset),
					onClick = {
						resetKeymapOverrides(settings)
						capturingCommandId = null
					},
					primary = false,
				)
			}
			Spacer(modifier = Modifier.height(SETTING_ROW_SPACING))
			Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
				val scrollState = rememberScrollState()
				// The scrollbar is overlaid on this column's trailing edge, so the rows reserve its width
				// or the clear button on every row sits underneath the bar.
				Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = SCROLLBAR_THICKNESS)) {
					for (command in commands.all()) {
						val titleResource = command.title ?: continue
						KeybindingRow(
							title = stringResource(titleResource),
							chord = keymap.chordFor(command.id),
							capturing = capturingCommandId == command.id,
							onStartCapture = { capturingCommandId = command.id },
							onCancelCapture = { capturingCommandId = null },
							onClear = {
								unbindCommand(settings, command.id)
								capturingCommandId = null
							},
							onCaptured = { captured ->
								capturingCommandId = null
								val existingCommandId = keymap.commandFor(captured)
								if (existingCommandId != null && existingCommandId != command.id) {
									pendingConflict = KeybindingConflict(command.id, captured, existingCommandId)
								} else {
									rebindCommand(settings, command.id, captured)
								}
							},
						)
					}
				}
				VerticalScrollbarOverlay(scrollState)
			}
		}
		pendingConflict?.let { conflict ->
			val existingTitle =
				commands[conflict.existingCommandId]?.title?.let { titleResource -> stringResource(titleResource) }
					?: conflict.existingCommandId
			val chordLabel = formatAccelerator(conflict.chord)
			// The prompt goes through the shell's one confirm slot rather than a dialog of its own, so Enter
			// reassigns and Escape cancels it: a dialog drawn here sits outside the shell's key ladder, where
			// Escape falls to the Preferences overlay and closes it behind the prompt.  The strings resolve
			// here, where composition can read them, and the conflict clears once handed over.
			LaunchedEffect(conflict) {
				commands.invoke(
					"document.confirm",
					ConfirmRequest(
						message = Res.string.settings_keybindings_conflict,
						arguments = listOf(chordLabel, existingTitle),
						confirmLabel = Res.string.settings_keybindings_reassign,
					) { rebindCommand(settings, conflict.commandId, conflict.chord) },
				)
				pendingConflict = null
			}
		}
	}
}

/**
 * One command row: the command name, its chord chip, and a clear button.
 *
 * @param String    title          The command's localized display name.
 * @param KeyChord? chord          The currently bound chord, or null when unbound.
 * @param Boolean   capturing      Whether this row is capturing a new chord.
 * @param Function  onStartCapture Begins capture for this row.
 * @param Function  onCancelCapture Ends capture without binding.
 * @param Function  onClear        Unbinds the command.
 * @param Function  onCaptured     Receives a captured chord to bind.
 */
@Composable
private fun KeybindingRow(
	title: String,
	chord: KeyChord?,
	capturing: Boolean,
	onStartCapture: () -> Unit,
	onCancelCapture: () -> Unit,
	onClear: () -> Unit,
	onCaptured: (KeyChord) -> Unit,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(text = title, style = typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f))
		ChordChip(
			chord = chord,
			capturing = capturing,
			onStartCapture = onStartCapture,
			onCancelCapture = onCancelCapture,
			onCaptured = onCaptured,
		)
		Spacer(modifier = Modifier.width(8.dp))
		ClearButton(enabled = chord != null && !capturing, onClick = onClear)
	}
}

/**
 * The clickable chord chip: shows the current accelerator (or "unbound"), and while capturing becomes a
 * focused key sink that turns the next key combination into a chord (bare Escape cancels capture).
 *
 * Two things keep that true inside the shell.  The capture is announced on [LocalKeyCapture], because the shell's
 * root handler previews every key before this chip sees it and would otherwise take Escape to close Preferences.
 * And the chip keeps ONE focus node whether it is capturing or not: a focus node that left the composition when
 * the capture ended would leave focus on nothing, and with nothing focused no key reaches the shell at all - the
 * reassign prompt a taken chord raises would answer to neither Enter nor Escape.
 *
 * @param KeyChord? chord          The currently bound chord, or null.
 * @param Boolean   capturing      Whether this chip is capturing.
 * @param Function  onStartCapture Begins capture (chip click).
 * @param Function  onCancelCapture Ends capture without binding.
 * @param Function  onCaptured     Receives a captured chord.
 */
@Composable
private fun ChordChip(
	chord: KeyChord?,
	capturing: Boolean,
	onStartCapture: () -> Unit,
	onCancelCapture: () -> Unit,
	onCaptured: (KeyChord) -> Unit,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val shapes = LocalUmamoShapes.current
	val focusRequester = remember { FocusRequester() }
	LaunchedEffect(capturing) {
		if (capturing) {
			focusRequester.requestFocus()
		}
	}
	val keyCapture = LocalKeyCapture.current
	DisposableEffect(capturing, keyCapture) {
		if (capturing) {
			keyCapture.begin()
		}
		onDispose {
			if (capturing) {
				keyCapture.end()
			}
		}
	}
	val label =
		when {
			capturing -> stringResource(Res.string.settings_keybindings_press_shortcut)
			chord != null -> formatAccelerator(chord)
			else -> stringResource(Res.string.settings_keybindings_unbound)
		}
	val chipBase =
		Modifier
			.widthIn(min = CHORD_CHIP_MIN_WIDTH)
			.clip(shapes.small)
			// Capturing is an armed accent state, not a selection - accent is deliberate here even though
			// the two tokens share a value in the dark scheme.
			.background(if (capturing) colors.accent else colors.controlBackground)
			.border(BorderStroke(1.dp, if (capturing) colors.accent else colors.controlBorder), shapes.small)
	val chipModifier =
		chipBase
			.focusRequester(focusRequester)
			.onPreviewKeyEvent { event -> capturing && handleCaptureKey(event, onCaptured, onCancelCapture) }
			// The one focus node, present in both states so it outlives the capture.
			.focusable()
			.clickable(enabled = !capturing, onClick = onStartCapture)
	Box(modifier = chipModifier.padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
		Text(text = label, style = typography.labelMedium, color = if (capturing) colors.accentText else colors.text)
	}
}

/**
 * Turns a key event during capture into a chord: ignores key-up and bare modifier presses (waiting for a
 * real key), treats unmodified Escape as cancel, and otherwise emits the chord via [onCaptured].  The key
 * naming is the shared [keyName] table, so a captured chord dispatches identically at runtime.
 *
 * @param KeyEvent event         The key event during capture.
 * @param Function onCaptured     Receives the captured chord.
 * @param Function onCancelCapture Cancels capture (unmodified Escape).
 * @return Boolean true when the event was handled (consumed).
 */
private fun handleCaptureKey(event: KeyEvent, onCaptured: (KeyChord) -> Unit, onCancelCapture: () -> Unit): Boolean {
	if (event.type != KeyEventType.KeyDown) {
		return false
	}
	val pressedKeyName = keyName(event.key) ?: return false
	val noModifiers = !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed && !event.isAltPressed
	if (pressedKeyName == "Escape" && noModifiers) {
		onCancelCapture()
		return true
	}
	onCaptured(
		KeyChord(
			keyName = pressedKeyName,
			primaryModifier = event.isCtrlPressed || event.isMetaPressed,
			shift = event.isShiftPressed,
			alt = event.isAltPressed,
		),
	)
	return true
}

/**
 * A small "✕" clear button matching the kit's icon controls; dimmed and inert when there is nothing to
 * clear (the command is already unbound, or the row is mid-capture).
 *
 * @param Boolean  enabled Whether clearing is possible.
 * @param Function onClick Unbinds the command.
 */
@Composable
private fun ClearButton(enabled: Boolean, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val description = stringResource(Res.string.settings_keybindings_clear)
	// The label doubles as the hover text, matching the kit's icon controls: the row names the command, not
	// what the ✕ beside it does.
	Tooltip(text = description) {
		Box(
			modifier =
				Modifier
					.size(20.dp)
					.then(if (enabled) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
					.semantics { contentDescription = description },
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = "✕",
				style = typography.labelMedium,
				color =
					when {
						!enabled -> colors.textDisabled
						hovered -> colors.text
						else -> colors.textMuted
					},
			)
		}
	}
}