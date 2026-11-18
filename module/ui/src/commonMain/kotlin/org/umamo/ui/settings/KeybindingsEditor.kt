package org.umamo.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.CommandTable
import org.umamo.ui.action.KEYMAP_PRESET_IDS
import org.umamo.ui.action.KEYMAP_PRESET_KEY
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.acceleratorParts
import org.umamo.ui.action.defaultSlotsFor
import org.umamo.ui.action.primaryModifierLabel
import org.umamo.ui.action.rankShortcutMatches
import org.umamo.ui.action.resetKeymapOverrides
import org.umamo.ui.kit.KeyChordView
import org.umamo.ui.kit.SCROLLBAR_THICKNESS
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.button.Button
import org.umamo.ui.kit.button.DisclosureChevron
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.field.SearchField
import org.umamo.ui.kit.field.SelectField
import org.umamo.ui.kit.highlightedText
import org.umamo.ui.kit.specialKeyIcon
import org.umamo.ui.rememberStringSetting
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_keybindings_preset
import org.umamo.ui.resources.settings_keybindings_preset_quick
import org.umamo.ui.resources.settings_keybindings_reset
import org.umamo.ui.resources.settings_keybindings_reset_slot
import org.umamo.ui.resources.settings_keybindings_search
import org.umamo.ui.resources.settings_keybindings_slot_alternative
import org.umamo.ui.resources.settings_keybindings_slot_primary
import org.umamo.ui.resources.settings_keybindings_unbind_slot
import org.umamo.ui.resources.settings_keybindings_unbound
import org.umamo.ui.resources.settings_keymap_preset_blender
import org.umamo.ui.resources.settings_keymap_preset_cubism
import org.umamo.ui.resources.settings_keymap_preset_default
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon

/** The width of the preset dropdown, so the reset button beside it has room. */
private val PRESET_FIELD_WIDTH = 260.dp

/** The height of the search box, which is drawn as a pill. */
private val SEARCH_HEIGHT = 36.dp

/** The room between the list and the nav beside it. */
private val LIST_PADDING = 16.dp

/** How long a jumped-to group is banded for. */
private const val FLASH_MILLIS = 700L

/**
 * The keymap preset row, bound write-through to input.keybinding.preset: the keybindings editor and Quick
 * Setup share it.  A switch keeps the user's per-command overrides, which layer on whichever preset is chosen.
 *
 * @param Function onSelect Called after a preset is picked, for the host's own bookkeeping.
 * @param Boolean quick True to label the row for Quick Setup.
 * @param Function? onReset Called when the row's reset button is clicked, for a row that offers one.
 */
@Composable
internal fun KeymapPresetSettingRow(
	onSelect: () -> Unit = {},
	quick: Boolean = false,
	onReset: (() -> Unit)? = null,
) {
	var preset by rememberStringSetting(KEYMAP_PRESET_KEY, "default")
	// Preset display names are localized chrome; the ids stay stable (see KEYMAP_PRESET_IDS).
	val presetLabels =
		linkedMapOf(
			"default" to stringResource(Res.string.settings_keymap_preset_default),
			"cubism" to stringResource(Res.string.settings_keymap_preset_cubism),
			"blender" to stringResource(Res.string.settings_keymap_preset_blender),
		)
	SettingRow(label = stringResource(if (quick) Res.string.settings_keybindings_preset_quick else Res.string.settings_keybindings_preset)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			SelectField(
				selected = preset,
				options = KEYMAP_PRESET_IDS,
				label = { presetId -> presetLabels[presetId] ?: presetId },
				modifier = Modifier.width(PRESET_FIELD_WIDTH),
				onSelect = { presetId ->
					preset = presetId
					onSelect()
				},
			)

			if (onReset != null) {
				Spacer(modifier = Modifier.width(8.dp))
				Button(
					label = stringResource(Res.string.settings_keybindings_reset),
					onClick = onReset,
					primary = false,
				)
			}
		}
	}
}

/**
 * The keybindings editor: a preset, a search, a nav of the groups, and the commands of the chosen one.
 *
 * The commands are grouped by the table that registers them - the domain each belongs to - and each table
 * holds the menu families its commands are shown in as nested groups.  The nav down the left picks the
 * table; the list on the right holds that table's groups, and every group folds away.
 *
 * A row carries the command's glyph, its name, and the two slots: the primary binding the rest of the editor
 * shows, and the alternative, which is empty until a preset binds the command twice or the user gives it one.
 * Clicking a slot opens the editor for it under the cell; the two small buttons beside it reset the slot to
 * its default and unbind it.
 */
@Composable
internal fun KeybindingsEditor() {
	val settings = LocalSettings.current
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val shortcutEditor = LocalShortcutEditor.current
	var query by remember { mutableStateOf("") }
	val searchFocus = remember { FocusRequester() }
	// The find shortcut asks for the field; the request itself waits for the next frame, so it is not made
	// from inside the key event's own dispatch.
	var focusTheSearch by remember { mutableStateOf(false) }
	var collapsed by remember { mutableStateOf(emptySet<String>()) }
	var selectedGroup by remember { mutableStateOf<String?>(null) }
	// The group a jump landed on, banded for a moment so the eye finds it in the list.
	var flashedGroup by remember { mutableStateOf<String?>(null) }
	val scope = rememberCoroutineScope()

	LaunchedEffect(focusTheSearch) {
		if (focusTheSearch) {
			focusTheSearch = false
			searchFocus.requestFocus()
		}
	}

	// The window's own key routing focuses the field, so it has to be told where the field is.
	DisposableEffect(shortcutEditor, searchFocus) {
		shortcutEditor.searchField = searchFocus

		onDispose { shortcutEditor.searchField = null }
	}

	LaunchedEffect(flashedGroup) {
		if (flashedGroup != null) {
			delay(FLASH_MILLIS)
			flashedGroup = null
		}
	}

	// Reading the revision subscribes the list to the registry, so a command that registers later reaches it.
	val revision = commands.revision
	val titled =
		remember(revision) {
			commands.all().mapNotNull { command -> command.title?.let { title -> command to title } }
		}

	// The labels need stringResource (a composable), so they are resolved here and the filtering below is
	// plain Kotlin.  The list is a few hundred short labels at most, so resolving every one each
	// recomposition is cheap.
	val entries =
		titled.map { (command, title) ->
			val slots = keymap.slotsFor(command.id)

			ShortcutEntry(
				command = command,
				label = stringResource(title),
				family = command.family(),
				groupLabel = command.table?.let { table -> stringResource(table.label) } ?: "",
				shortcutText =
					buildList {
						slots.primary?.let { chord -> add(acceleratorParts(chord).joinToString(separator = "+")) }
						slots.alternative?.let { chord -> add(acceleratorParts(chord).joinToString(separator = "+")) }
					}.joinToString(separator = " "),
			)
		}

	val filtered =
		if (query.isBlank()) {
			entries
		} else {
			rankShortcutMatches(
				entries,
				query,
				labelOf = { entry -> entry.label },
				idOf = { entry -> entry.command.id },
				groupOf = { entry -> entry.groupLabel },
				shortcutOf = { entry -> entry.shortcutText },
			)
		}

	val tree = buildTree(filtered)
	// The list holds every group, and the nav is a table of contents over it: picking a group scrolls to
	// that group's heading rather than hiding the rest.
	val lines = flatten(tree, if (query.isBlank()) collapsed else emptySet())
	val listState = rememberLazyListState()
	val navTargets =
		tree.map { branch ->
			NavTarget(
				key = branch.key,
				label = branch.label,
				line = lines.indexOfFirst { line -> line.branch?.key == branch.key },
			)
		}
	// The group each line belongs to, so the nav can find the current one without rebuilding the list.
	val groupOfLine = lines.map { line -> line.group }

	// A jump brings the command into view and then opens the editor on it: the cell only has its bounds
	// once the list has settled, so the editor waits for the list to reach it.
	LaunchedEffect(shortcutEditor.pending) {
		val pending = shortcutEditor.pending ?: return@LaunchedEffect
		val index = lines.indexOfFirst { line -> line.entry?.command?.id == pending.commandId }

		if (index >= 0) {
			listState.requestScrollToItem(index)
		}

		// One frame for the list to lay the row out where it now sits.
		withFrameNanos { }

		shortcutEditor.open(
			pending.commandId,
			pending.slot,
			shortcutEditor.anchorFor(pending.commandId, pending.slot),
		)
		shortcutEditor.clearPending()
	}

	// The chords more than one command claims - the same chord on two rows - and the commands whose two
	// slots are both of them.
	val claims = HashMap<KeyChord, MutableList<String>>()

	for (command in titled.map { entry -> entry.first }) {
		val slots = keymap.slotsFor(command.id)

		for (chord in listOfNotNull(slots.primary, slots.alternative)) {
			val holders = claims.getOrPut(chord) { mutableListOf() }

			if (command.id !in holders) {
				holders += command.id
			}
		}
	}

	val conflicting =
		titled
			.mapNotNull { entry ->
				val command = entry.first
				val slots = keymap.slotsFor(command.id)
				val primaryClash = slots.primary?.let { chord -> (claims[chord]?.size ?: 0) > 1 } == true
				val alternativeClash = slots.alternative?.let { chord -> (claims[chord]?.size ?: 0) > 1 } == true

				if (primaryClash && alternativeClash) command.id else null
			}
			.toSet()

	Column(
		modifier =
			Modifier
				.fillMaxSize()
				// The platform's find shortcut hands the search field the keyboard, the way every other
				// panel's does.
				.onPreviewKeyEvent { event ->
					val find =
						event.type == KeyEventType.KeyDown &&
							event.key == Key.F &&
							(event.isCtrlPressed || event.isMetaPressed)

					if (find) {
						focusTheSearch = true
					}

					find
				},
	) {
		KeymapPresetSettingRow(onReset = { resetKeymapOverrides(settings) })
		Spacer(modifier = Modifier.height(SETTING_ROW_SPACING))

		SearchField(
			value = query,
			onValueChange = { newQuery -> query = newQuery },
			// The requester rides on the field itself, so the shortcut below has something to focus.
			modifier = Modifier.fillMaxWidth().heightIn(min = SEARCH_HEIGHT).focusRequester(searchFocus),
			width = null,
			placeholder = stringResource(Res.string.settings_keybindings_search, primaryModifierLabel()),
			leading = {
				val tint = LocalUmamoColors.current.textMuted

				Canvas(modifier = Modifier.size(14.dp)) {
					drawIcon(LocalUmamoIcons.search, tint)
				}
			},
			shape = RoundedCornerShape(SEARCH_HEIGHT / 2),
		)

		Spacer(modifier = Modifier.height(SETTING_ROW_SPACING))

		Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
			GroupNav(
				targets = navTargets,
				groupOfLine = groupOfLine,
				listState = listState,
				onSelect = { target ->
					flashedGroup = target.key
					scope.launch { listState.animateScrollToItem(target.line) }
				},
			)
			Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(LocalUmamoColors.current.divider))
			Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
				BindingColumnHeader(modifier = Modifier.padding(start = LIST_PADDING))
				LazyColumn(
					state = listState,
					modifier = Modifier.fillMaxSize().padding(start = LIST_PADDING, end = SCROLLBAR_THICKNESS + LIST_PADDING),
				) {
					itemsIndexed(
						lines,
						key = { _, line -> line.entry?.command?.id ?: line.branch?.key ?: "" },
					) { _, line ->
						val branch = line.branch
						val flash = flashedGroup == line.group.key

						if (branch != null) {
							GroupHeading(
								label = branch.label,
								depth = line.depth,
								expanded = branch.key !in collapsed,
								flash = flash,
								onToggle = { collapsed = collapsed.toggle(branch.key) },
							)
						} else {
							val entry = line.entry ?: return@itemsIndexed

							ShortcutRow(
								entry = entry,
								depth = line.depth,
								query = query,
								flash = flash,
								conflict = conflicting.contains(entry.command.id) || shortcutEditor.conflicted?.commandId == entry.command.id,
								onEdit = { commandId, slot, anchor -> shortcutEditor.open(commandId, slot, anchor) },
							)
						}
					}

					// The room at the end that lets the last group's heading reach the top of the list: without
					// it the scroll stops short and the last groups can never be reached or shown as current.
					item {
						Spacer(
							modifier =
								Modifier.height(
									with(LocalDensity.current) { listState.layoutInfo.viewportSize.height.toDp() },
								),
						)
					}
				}
				VerticalScrollbarOverlay(listState)
			}
		}
	}
}