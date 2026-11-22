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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import org.umamo.settings.Settings
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.KEYMAP_PRESET_IDS
import org.umamo.ui.action.KEYMAP_PRESET_KEY
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.KeymapCategory
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.addCommandBind
import org.umamo.ui.action.bindableCommands
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.action.keyChordOf
import org.umamo.ui.action.keyName
import org.umamo.ui.action.removeCommandBind
import org.umamo.ui.action.replaceCommandBind
import org.umamo.ui.action.resetKeymapOverrides
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

/**
 * A captured chord that collides with another command, held until its reassign prompt is raised in the shell.
 *
 * @property String commandId The command the chord is for.
 * @property KeyChord? replacing The command's chord it takes the place of, or null when it is one more.
 * @property KeyChord chord The captured chord.
 * @property String existingCommandId The command that holds the chord now.
 */
private data class KeybindingConflict(val commandId: String, val replacing: KeyChord?, val chord: KeyChord, val existingCommandId: String)

/** What on the page is waiting for a key press.  One at a time: arming one disarms the other. */
private sealed interface KeybindingCapture {
	/**
	 * A command's chip, capturing the chord to bind it to.
	 *
	 * @property String commandId The command being rebound.
	 */
	data class Row(val commandId: String) : KeybindingCapture

	/** The search's recording, capturing the chord to look up. */
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
	val captureFocus = remember { FocusRequester() }
	// Arming takes the keyboard in the same call.  Left to an effect, a press in the frame between would be
	// captured as the chord while its typed character still went to a focused search field.
	val arm: (KeybindingCapture) -> Unit = { armed ->
		capture = armed
		captureFocus.requestFocus()
	}
	val captureSink =
		rememberChordCaptureSink(
			focusRequester = captureFocus,
			isCapturing = { capture != null },
			onCaptured = { captured ->
				when (val armed = capture) {
					is KeybindingCapture.Row -> {
						// The chip shows the command's first chord, so that is the one a capture replaces.
						val replacing = keymap.chordFor(armed.commandId)
						val existingCommandId = keymap.commandFor(captured)

						if (existingCommandId != null && existingCommandId != armed.commandId) {
							pendingConflict = KeybindingConflict(armed.commandId, replacing, captured, existingCommandId)
						} else {
							bindCapturedChord(settings, armed.commandId, replacing, captured)
						}
					}

					KeybindingCapture.Search -> {
						search = KeybindingSearch.Chord(captured)
					}

					null -> {}
				}

				capture = null
			},
			onCancelCapture = { capture = null },
		)

	Box(modifier = Modifier.fillMaxSize().then(captureSink)) {
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
					onSearchChange = { changed ->
						search = changed
						capture = null
					},
					onStartRecording = { arm(KeybindingCapture.Search) },
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
						val chordLabels = keymap.chordsFor(command.id).map { chord -> formatAccelerator(chord) }.ifEmpty { listOf(unboundLabel) }

						KeybindingSearchRow(command.id, stringResource(titleResource), chordLabels)
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
					SectionCard(
						title = title,
						expanded = searching || folds.isExpanded(foldId),
						onToggle = {
							folds.toggle(foldId)
							// A fold can hide the armed row, which would then take the next press unseen.
							capture = null
						},
					) {
						for (rowIndex in visibility.visibleRowIndices) {
							val row = rows[rowIndex]
							key(row.commandId) {
								val chord = keymap.chordFor(row.commandId)
								KeybindingRow(
									title = row.title,
									chord = chord,
									capturing = capture == KeybindingCapture.Row(row.commandId),
									onStartCapture = { arm(KeybindingCapture.Row(row.commandId)) },
									onClear = {
										chord?.let { shown -> removeCommandBind(settings, row.commandId, shown) }
										capture = null
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
				) { bindCapturedChord(settings, conflict.commandId, conflict.replacing, conflict.chord) },
			)
			pendingConflict = null
		}
	}
}

/**
 * The row under the preset that clears every per-command override, so each command falls back to the preset's chords.
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
 * Binds a captured chord to its command: in the place of [replacing], or after the command's other chords when there
 * is nothing to replace.  Whatever held the chord loses it, so a conflict is confirmed before this runs.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command the chord is for.
 * @param KeyChord? replacing The command's chord to replace, or null to add one.
 * @param KeyChord chord The captured chord.
 */
private fun bindCapturedChord(settings: Settings, commandId: String, replacing: KeyChord?, chord: KeyChord) {
	if (replacing == null) {
		addCommandBind(settings, commandId, chord)
	} else {
		replaceCommandBind(settings, commandId, replacing, chord)
	}
}

/**
 * The page's search: a text field matched against names and shown shortcuts, with a keyboard button that looks a
 * pressed chord up instead.  While it records, an armed chip face covers the whole field, so the field cannot be
 * mistaken for the place to type the shortcut; the search underneath is untouched until a chord lands, so a
 * cancelled recording leaves the list as it was.
 *
 * @param KeybindingSearch search The current search.
 * @param Boolean recording Whether the page is recording a chord to look up.
 * @param Function onSearchChange Receives the typed text as a text search.
 * @param Function onStartRecording Arms the recording.
 */
@Composable
private fun KeybindingSearchField(
	search: KeybindingSearch,
	recording: Boolean,
	onSearchChange: (KeybindingSearch) -> Unit,
	onStartRecording: () -> Unit,
) {
	val shownText =
		when (search) {
			is KeybindingSearch.Text -> search.query
			is KeybindingSearch.Chord -> formatAccelerator(search.chord)
		}
	Box(modifier = Modifier.fillMaxWidth()) {
		SearchField(
			value = shownText,
			// Editing the field always makes a text search, a recorded chord's label included.
			onValueChange = { typed -> onSearchChange(KeybindingSearch.Text(typed)) },
			modifier = Modifier.fillMaxWidth(),
			width = Dp.Unspecified,
			placeholder = stringResource(Res.string.settings_keybindings_search_hint),
			trailingAction = {
				IconButton(
					icon = LocalUmamoIcons.keyboard,
					onClick = onStartRecording,
					contentDescription = stringResource(Res.string.settings_keybindings_search_record),
					size = DpSize(16.dp, 16.dp),
					glyphSize = 16.dp,
				)
			},
		)

		if (recording) {
			ChordChipFace(label = stringResource(Res.string.settings_keybindings_press_shortcut), armed = true, modifier = Modifier.matchParentSize())
		}
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
 * @param KeyChord? chord          The command's first chord, or null when unbound.
 * @param Boolean   capturing      Whether this row is capturing a new chord.
 * @param Function  onStartCapture Begins capture for this row.
 * @param Function  onClear        Removes the shown chord.
 */
@Composable
private fun KeybindingRow(
	title: String,
	chord: KeyChord?,
	capturing: Boolean,
	onStartCapture: () -> Unit,
	onClear: () -> Unit,
) {
	PropertyFieldRow(label = title) {
		Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			ChordChip(chord = chord, capturing = capturing, onStartCapture = onStartCapture, modifier = Modifier.weight(1f))
			Spacer(modifier = Modifier.width(4.dp))
			ClearButton(enabled = chord != null && !capturing, onClick = onClear)
		}
	}
}

/**
 * The page's one key sink: whichever control is armed, a chip or the search's recording, the next key combination
 * lands here as its chord (a bare Escape cancels).
 *
 * It is the page's only focus node for captures, mounted for as long as the page is.  A control that held the
 * keyboard itself would take focus along when its row left the list, and with focus on nothing no key reaches the
 * shell at all: the reassign prompt a taken chord raises would answer to neither Enter nor Escape.
 *
 * The capture is announced on [LocalKeyCapture], because the shell's root handler previews every key before the sink
 * sees it and would otherwise take Escape to close Preferences.  A capture ends when the sink loses the keyboard, so
 * a click into the search field leaves no capture listening behind it.
 *
 * @param FocusRequester focusRequester The sink's focus, requested by whatever arms a capture.
 * @param Function isCapturing Whether a capture is armed, asked as each key arrives.
 * @param Function onCaptured Receives a captured chord.
 * @param Function onCancelCapture Ends the capture without a chord.
 * @return Modifier The focus node and key sink, for the page's outermost node.
 */
@Composable
private fun rememberChordCaptureSink(
	focusRequester: FocusRequester,
	isCapturing: () -> Boolean,
	onCaptured: (KeyChord) -> Unit,
	onCancelCapture: () -> Unit,
): Modifier {
	val capturing = isCapturing()
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

	var heldFocus by remember { mutableStateOf(false) }

	return Modifier
		// Before the focus target it observes, so isFocused is the sink itself and a descendant taking focus (the
		// search field) reads as the sink losing it.  Only a loss cancels: taking the keyboard from that
		// descendant reports the sink unfocused once on the way to focused, with the capture already armed.
		.onFocusChanged { focusState ->
			if (heldFocus && !focusState.isFocused) {
				onCancelCapture()
			}

			heldFocus = focusState.isFocused
		}
		.focusRequester(focusRequester)
		.onPreviewKeyEvent { event -> isCapturing() && handleCaptureKey(event, onCaptured, onCancelCapture) }
		.focusable()
}

/**
 * The clickable chord chip: shows the current accelerator (or "unbound"), and while capturing reads as armed until
 * the page's key sink ([rememberChordCaptureSink]) takes the next key combination as the command's new chord.
 *
 * @param KeyChord? chord          The currently bound chord, or null.
 * @param Boolean   capturing      Whether this chip is capturing.
 * @param Function  onStartCapture Begins capture (chip click).
 * @param Modifier  modifier       Layout modifier for the chip.
 */
@Composable
private fun ChordChip(
	chord: KeyChord?,
	capturing: Boolean,
	onStartCapture: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val label =
		when {
			capturing -> stringResource(Res.string.settings_keybindings_press_shortcut)
			chord != null -> formatAccelerator(chord)
			else -> stringResource(Res.string.settings_keybindings_unbound)
		}
	ChordChipFace(label = label, armed = capturing, modifier = modifier, onClick = if (capturing) null else onStartCapture)
}

/**
 * The face a chord chip and the search's recording cover share: a bordered label that turns to the accent while
 * armed.  It takes every press that lands on it, clickable or not, so nothing beneath an armed face is reached.
 *
 * @param String    label    The text the face shows.
 * @param Boolean   armed    Whether it is waiting for a key press.
 * @param Modifier  modifier Layout modifier for the face.
 * @param Function? onClick  What a click does, or null for a face that only swallows the press.
 */
@Composable
private fun ChordChipFace(label: String, armed: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val shapes = LocalUmamoShapes.current
	val faceModifier =
		modifier
			.clip(shapes.small)
			// Armed is an accent state, not a selection - accent is deliberate here even though the two tokens
			// share a value in the dark scheme.
			.background(if (armed) colors.accent else colors.controlBackground)
			.border(BorderStroke(1.dp, if (armed) colors.accent else colors.controlBorder), shapes.small)
			// The page's key sink holds the keyboard; a face that took focus on its click would end the capture
			// it had just armed.
			.focusProperties { canFocus = false }
			.clickable(enabled = onClick != null, onClick = onClick ?: {})
	// The same text style and vertical inset as the field dropdowns, so a chip row stands as tall as the preset row.
	Box(modifier = faceModifier.padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
		Text(
			text = label,
			style = typography.labelMedium,
			color = if (armed) colors.accentText else colors.text,
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
 * @param Function onClick Removes the shown chord.
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