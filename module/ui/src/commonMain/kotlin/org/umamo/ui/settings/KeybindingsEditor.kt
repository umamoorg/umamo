package org.umamo.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.KEYMAP_PRESET_IDS
import org.umamo.ui.action.KEYMAP_PRESET_KEY
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.KeymapCategory
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.bindableCommands
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.action.keyChordOf
import org.umamo.ui.action.keyName
import org.umamo.ui.action.rebindCommand
import org.umamo.ui.action.resetKeymapOverrides
import org.umamo.ui.action.unbindCommand
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.button.Button
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.container.SectionCard
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.kit.field.SearchField
import org.umamo.ui.kit.field.SelectField
import org.umamo.ui.kit.textentry.LocalKeyCapture
import org.umamo.ui.rememberStringSetting
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_keybindings_chord_unbound
import org.umamo.ui.resources.settings_keybindings_chord_unlisted
import org.umamo.ui.resources.settings_keybindings_clear
import org.umamo.ui.resources.settings_keybindings_conflict
import org.umamo.ui.resources.settings_keybindings_no_matches
import org.umamo.ui.resources.settings_keybindings_preset
import org.umamo.ui.resources.settings_keybindings_preset_description
import org.umamo.ui.resources.settings_keybindings_preset_quick
import org.umamo.ui.resources.settings_keybindings_press_shortcut
import org.umamo.ui.resources.settings_keybindings_reassign
import org.umamo.ui.resources.settings_keybindings_reset
import org.umamo.ui.resources.settings_keybindings_reset_description
import org.umamo.ui.resources.settings_keybindings_search_hint
import org.umamo.ui.resources.settings_keybindings_search_record
import org.umamo.ui.resources.settings_keybindings_unbound
import org.umamo.ui.resources.settings_keymap_preset_blender
import org.umamo.ui.resources.settings_keymap_preset_cubism
import org.umamo.ui.resources.settings_keymap_preset_default
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.workspace.ConfirmRequest

/** A captured chord that collides with another command, held until its reassign prompt is raised in the shell. */
private data class KeybindingConflict(val commandId: String, val chord: KeyChord, val existingCommandId: String)

/** What on the page is waiting for a key press.  One at a time: arming one disarms the other. */
private sealed interface KeybindingCapture {
	/**
	 * A command's chip, capturing the chord to bind it to.
	 *
	 * @property String commandId The command being rebound.
	 */
	data class Row(val commandId: String) : KeybindingCapture

	/** The search's record button, capturing the chord to look up. */
	data object Search : KeybindingCapture
}

/**
 * The keymap preset row, bound write-through to input.keybinding.preset: the keybindings editor and Quick
 * Setup share it.  A switch keeps the user's per-command overrides, which layer on whichever preset is chosen.
 *
 * @param Function onSelect Called after a preset is picked, for the host's own bookkeeping.
 * @param Boolean quick True to label the row for Quick Setup.
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
	PropertyFieldRow(
		label = stringResource(if (quick) Res.string.settings_keybindings_preset_quick else Res.string.settings_keybindings_preset),
		description = stringResource(Res.string.settings_keybindings_preset_description),
	) {
		SelectField(
			selected = preset,
			options = KEYMAP_PRESET_IDS,
			label = { presetId -> presetLabels[presetId] ?: presetId },
			onSelect = { presetId ->
				preset = presetId
				onSelect()
			},
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/**
 * The keybindings editor: pick a preset, then find and rebind individual commands under their categories.  The
 * preset dropdown and every rebind write through settings (input.keybinding.preset / .overrides) and the shell
 * re-resolves the live [LocalKeymap] reactively, so a change takes effect across menus, the palette, and dispatch
 * immediately.  Only the commands a chord can run appear, under their titles ([bindableCommands]).
 *
 * @param PreferencesFolds folds The window's fold state, which each category card persists into.
 */
@Composable
internal fun KeybindingsEditor(folds: PreferencesFolds) {
	val settings = LocalSettings.current
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	var capture by remember { mutableStateOf<KeybindingCapture?>(null) }
	var search by remember { mutableStateOf<KeybindingSearch>(KeybindingSearch.Text("")) }
	var pendingConflict by remember { mutableStateOf<KeybindingConflict?>(null) }

	SettingsCardPage(
		pinned = {
			KeymapPresetSettingRow(onSelect = { capture = null })
			ResetOverridesRow(
				onReset = {
					resetKeymapOverrides(settings)
					capture = null
				},
			)
			KeybindingSearchField(
				search = search,
				recording = capture == KeybindingCapture.Search,
				onSearchChange = { changed -> search = changed },
				onStartRecording = { capture = KeybindingCapture.Search },
				onCancelRecording = { capture = null },
				onRecorded = { chord ->
					capture = null
					search = KeybindingSearch.Chord(chord)
				},
			)
		},
	) {
		val unboundLabel = stringResource(Res.string.settings_keybindings_unbound)
		val chordSearch = search as? KeybindingSearch.Chord
		// What dispatch resolves the recorded press to, so the page answers "what does this press run" exactly as
		// the shell would, the macOS Control fallback included.
		val firingCommandId = chordSearch?.let { recorded -> keymap.commandForPress(recorded.chord) }
		val searching = search.isActive()
		val byCategory = bindableCommands(commands.all()).groupBy { command -> command.shownKeymapCategory }
		var shownCards = 0

		for (category in KeymapCategory.entries) {
			val rows =
				byCategory[category].orEmpty().mapNotNull { command ->
					val titleResource = command.title ?: return@mapNotNull null
					val chordLabel = keymap.chordFor(command.id)?.let { chord -> formatAccelerator(chord) } ?: unboundLabel

					KeybindingSearchRow(command.id, stringResource(titleResource), chordLabel)
				}
			val title = stringResource(category.heading)
			val visibility = keybindingCardVisibility(title, rows, search, firingCommandId)

			if (!visibility.shown) {
				continue
			}

			shownCards++
			val foldId = keybindingFoldId(category)
			key(category.id) {
				// A search forces its matching cards open; otherwise the rigger's persisted fold wins.
				SectionCard(title = title, expanded = searching || folds.isExpanded(foldId), onToggle = { folds.toggle(foldId) }) {
					for (rowIndex in visibility.visibleRowIndices) {
						val row = rows[rowIndex]
						key(row.commandId) {
							KeybindingRow(
								title = row.title,
								chord = keymap.chordFor(row.commandId),
								capturing = capture == KeybindingCapture.Row(row.commandId),
								onStartCapture = { capture = KeybindingCapture.Row(row.commandId) },
								onCancelCapture = { capture = null },
								onClear = {
									unbindCommand(settings, row.commandId)
									capture = null
								},
								onCaptured = { captured ->
									capture = null
									val existingCommandId = keymap.commandFor(captured)

									if (existingCommandId != null && existingCommandId != row.commandId) {
										pendingConflict = KeybindingConflict(row.commandId, captured, existingCommandId)
									} else {
										rebindCommand(settings, row.commandId, captured)
									}
								},
							)
						}
					}
				}
			}
		}

		if (searching && shownCards == 0) {
			NoMatchesLine(chordSearch = chordSearch, firingCommandId = firingCommandId)
		}
	}

	pendingConflict?.let { conflict ->
		val existingTitle =
			commands[conflict.existingCommandId]?.title?.let { titleResource -> stringResource(titleResource) }
				?: conflict.existingCommandId
		val chordLabel = formatAccelerator(conflict.chord)
		// The prompt goes through the shell's confirm queue rather than a dialog of its own, so Enter
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

/**
 * The row under the preset that clears every per-command override, so each command falls back to the preset's chord.
 *
 * @param Function onReset Clears the overrides.
 */
@Composable
private fun ResetOverridesRow(onReset: () -> Unit) {
	PropertyFieldRow(label = "") {
		Tooltip(text = stringResource(Res.string.settings_keybindings_reset_description), modifier = Modifier.fillMaxWidth()) {
			Button(label = stringResource(Res.string.settings_keybindings_reset), onClick = onReset, modifier = Modifier.fillMaxWidth(), primary = false)
		}
	}
}

/**
 * The page's search: a text field matched against names and shown shortcuts, with a record button that looks a
 * pressed chord up instead.  While the button records, the field empties to its "press a shortcut" hint; the search
 * underneath is untouched until a chord lands, so a cancelled recording leaves the list as it was.
 *
 * @param KeybindingSearch search The current search.
 * @param Boolean recording Whether the record button is capturing.
 * @param Function onSearchChange Receives the typed text as a text search.
 * @param Function onStartRecording Arms the record button.
 * @param Function onCancelRecording Disarms it without a chord.
 * @param Function onRecorded Receives the pressed chord, which ends the recording.
 */
@Composable
private fun KeybindingSearchField(
	search: KeybindingSearch,
	recording: Boolean,
	onSearchChange: (KeybindingSearch) -> Unit,
	onStartRecording: () -> Unit,
	onCancelRecording: () -> Unit,
	onRecorded: (KeyChord) -> Unit,
) {
	val shownText =
		if (recording) {
			""
		} else {
			when (search) {
				is KeybindingSearch.Text -> search.query
				is KeybindingSearch.Chord -> formatAccelerator(search.chord)
			}
		}
	SearchField(
		value = shownText,
		// Editing the field always makes a text search, a recorded chord's label included.
		onValueChange = { typed -> onSearchChange(KeybindingSearch.Text(typed)) },
		modifier = Modifier.fillMaxWidth(),
		width = Dp.Unspecified,
		placeholder = stringResource(if (recording) Res.string.settings_keybindings_press_shortcut else Res.string.settings_keybindings_search_hint),
		trailingAction = {
			RecordChordButton(
				recording = recording,
				onStart = onStartRecording,
				onCancel = onCancelRecording,
				onRecorded = onRecorded,
			)
		},
	)
}

/**
 * The keyboard button inside the search field: click it, press a chord, and the page narrows to the command that
 * chord runs.  Clicking it again, or a bare Escape, cancels.
 *
 * @param Boolean recording Whether it is capturing.
 * @param Function onStart Arms it.
 * @param Function onCancel Disarms it without a chord.
 * @param Function onRecorded Receives the pressed chord.
 */
@Composable
private fun RecordChordButton(recording: Boolean, onStart: () -> Unit, onCancel: () -> Unit, onRecorded: (KeyChord) -> Unit) {
	val capture = rememberChordCaptureModifier(capturing = recording, onCaptured = onRecorded, onCancelCapture = onCancel)
	Box(modifier = capture, contentAlignment = Alignment.Center) {
		IconButton(
			icon = LocalUmamoIcons.keyboard,
			onClick = {
				if (recording) {
					onCancel()
				} else {
					onStart()
				}
			},
			contentDescription = stringResource(Res.string.settings_keybindings_search_record),
			size = DpSize(16.dp, 16.dp),
			glyphSize = 16.dp,
			active = recording,
		)
	}
}

/**
 * The muted line shown in place of the cards when a search leaves none standing.  A recorded chord says what became
 * of it: nothing is bound to it, or it runs a built-in command the page does not list.
 *
 * @param KeybindingSearch.Chord? chordSearch The recorded chord, or null for a text search.
 * @param String? firingCommandId The command the recorded chord runs, or null when it runs none.
 */
@Composable
private fun NoMatchesLine(chordSearch: KeybindingSearch.Chord?, firingCommandId: String?) {
	val message =
		when {
			chordSearch == null -> stringResource(Res.string.settings_keybindings_no_matches)
			firingCommandId == null -> stringResource(Res.string.settings_keybindings_chord_unbound, formatAccelerator(chordSearch.chord))
			else -> stringResource(Res.string.settings_keybindings_chord_unlisted, formatAccelerator(chordSearch.chord))
		}
	Text(text = message, style = LocalUmamoTypography.current.bodyMedium, color = LocalUmamoColors.current.textMuted)
}

/**
 * One command row: the command name in the label column, its chord chip and a clear button in the control column.
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
	PropertyFieldRow(label = title) {
		Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			ChordChip(
				chord = chord,
				capturing = capturing,
				onStartCapture = onStartCapture,
				onCancelCapture = onCancelCapture,
				onCaptured = onCaptured,
				modifier = Modifier.weight(1f),
			)
			Spacer(modifier = Modifier.width(4.dp))
			ClearButton(enabled = chord != null && !capturing, onClick = onClear)
		}
	}
}

/**
 * The capture half of a control that takes the next key press as a chord: the chip and the search's record button
 * share it.  While [capturing] it requests focus and becomes the key sink that turns the next key combination into a
 * chord (a bare Escape cancels).
 *
 * Two things keep that true inside the shell.  The capture is announced on [LocalKeyCapture], because the shell's
 * root handler previews every key before the control sees it and would otherwise take Escape to close Preferences.
 * And the control keeps ONE focus node whether it is capturing or not: a focus node that left the composition when
 * the capture ended would leave focus on nothing, and with nothing focused no key reaches the shell at all - the
 * reassign prompt a taken chord raises would answer to neither Enter nor Escape.
 *
 * @param Boolean capturing Whether the control is capturing.
 * @param Function onCaptured Receives a captured chord.
 * @param Function onCancelCapture Ends capture without a chord.
 * @return Modifier The focus node and key sink, for the control to place ahead of its own click handling.
 */
@Composable
private fun rememberChordCaptureModifier(capturing: Boolean, onCaptured: (KeyChord) -> Unit, onCancelCapture: () -> Unit): Modifier {
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

	return Modifier
		.focusRequester(focusRequester)
		.onPreviewKeyEvent { event -> capturing && handleCaptureKey(event, onCaptured, onCancelCapture) }
		// The one focus node, present in both states so it outlives the capture.
		.focusable()
}

/**
 * The clickable chord chip: shows the current accelerator (or "unbound"), and while capturing takes the next key
 * combination as the command's new chord ([rememberChordCaptureModifier]).
 *
 * @param KeyChord? chord          The currently bound chord, or null.
 * @param Boolean   capturing      Whether this chip is capturing.
 * @param Function  onStartCapture Begins capture (chip click).
 * @param Function  onCancelCapture Ends capture without binding.
 * @param Function  onCaptured     Receives a captured chord.
 * @param Modifier  modifier       Layout modifier for the chip.
 */
@Composable
private fun ChordChip(
	chord: KeyChord?,
	capturing: Boolean,
	onStartCapture: () -> Unit,
	onCancelCapture: () -> Unit,
	onCaptured: (KeyChord) -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val shapes = LocalUmamoShapes.current
	val capture = rememberChordCaptureModifier(capturing = capturing, onCaptured = onCaptured, onCancelCapture = onCancelCapture)
	val label =
		when {
			capturing -> stringResource(Res.string.settings_keybindings_press_shortcut)
			chord != null -> formatAccelerator(chord)
			else -> stringResource(Res.string.settings_keybindings_unbound)
		}
	val chipModifier =
		modifier
			.clip(shapes.small)
			// Capturing is an armed accent state, not a selection - accent is deliberate here even though
			// the two tokens share a value in the dark scheme.
			.background(if (capturing) colors.accent else colors.controlBackground)
			.border(BorderStroke(1.dp, if (capturing) colors.accent else colors.controlBorder), shapes.small)
			.then(capture)
			.clickable(enabled = !capturing, onClick = onStartCapture)
	// The same text style and vertical inset as the field dropdowns, so a chip row stands as tall as the preset row.
	Box(modifier = chipModifier.padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
		Text(
			text = label,
			style = typography.labelMedium,
			color = if (capturing) colors.accentText else colors.text,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/**
 * Turns a key event during capture into a chord: ignores key-up and bare modifier presses (waiting for a
 * real key), treats unmodified Escape as cancel, and otherwise emits the chord via [onCaptured].  The key
 * naming is the shared [keyName] table and the modifiers come from [keyChordOf], the rule dispatch decodes
 * with, so a captured chord dispatches identically at runtime - a literal Control on macOS included.
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
		keyChordOf(
			keyName = pressedKeyName,
			controlPressed = event.isCtrlPressed,
			metaPressed = event.isMetaPressed,
			shiftPressed = event.isShiftPressed,
			altPressed = event.isAltPressed,
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