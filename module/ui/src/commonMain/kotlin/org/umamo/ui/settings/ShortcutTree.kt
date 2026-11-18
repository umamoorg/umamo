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

/**
 * One row's command and the text a query is matched against.
 *
 * @property Command command The command the row stands for.
 * @property String label The command's localized name.
 * @property CommandFamily? family The menu family the command is shown in, or null when no menu shows it.
 * @property String groupLabel The name of the domain the command belongs to.
 * @property String shortcutText The command's bound accelerators, as display text.
 */
internal data class ShortcutEntry(
	val command: Command,
	val label: String,
	val family: CommandFamily?,
	val groupLabel: String,
	val shortcutText: String,
)

/**
 * A group of commands under a heading: a command table, or the menu family inside one.
 *
 * @property String key The group's identity, for the collapsed set.
 * @property String label The heading over the group.
 * @property List<ShortcutEntry> entries The commands the group holds directly.
 * @property List<ShortcutBranch> children The groups nested inside it.
 */
internal class ShortcutBranch(
	val key: String,
	val label: String,
	val entries: List<ShortcutEntry>,
	val children: List<ShortcutBranch>,
)

/**
 * One entry of the nav: the group, its name, and the line its heading sits on.
 *
 * @property String key The group's identity.
 * @property String label The group's name.
 * @property Int line The line the group's heading sits on in the list.
 */
internal data class NavTarget(
	val key: String,
	val label: String,
	val line: Int,
)

/**
 * One line of the scrolling list: a group's heading, or a command's row.
 *
 * @property ShortcutBranch? branch The group the line heads, or null for a command row.
 * @property ShortcutEntry? entry The command the line stands for, or null for a heading.
 * @property ShortcutBranch group The top-level group the line belongs to.
 * @property Int depth How deep the line sits.
 */
internal data class ShortcutLine(
	val branch: ShortcutBranch?,
	val entry: ShortcutEntry?,
	val group: ShortcutBranch,
	val depth: Int,
)

/**
 * Adds a key to the collapsed set, or takes it out.
 *
 * @param String key The key to toggle.
 * @return Set The new collapsed keys.
 */
internal fun Set<String>.toggle(key: String): Set<String> = if (key in this) this - key else this + key

/**
 * The menu family a command is shown in, or null when no menu shows it.
 *
 * @return CommandFamily? The family, or null.
 */
internal fun Command.family(): CommandFamily? =
	when (val group = group) {
		is CommandFamily -> group
		is CommandSection -> group.parent
		null -> null
	}

/**
 * The tree of groups the list draws: the command tables, each holding the menu families inside it.
 *
 * @param List<ShortcutEntry> entries The rows to place.
 * @return List<ShortcutBranch> The tree.
 */
@Composable
internal fun buildTree(entries: List<ShortcutEntry>): List<ShortcutBranch> =
	entries
		.groupBy { entry -> entry.command.table }
		.entries
		.sortedBy { group -> group.key?.ordinal ?: Int.MAX_VALUE }
		.map { (table, tableEntries) ->
			val byFamily = tableEntries.groupBy { entry -> entry.family }
			val tableName = table?.name ?: "-"

			ShortcutBranch(
				key = "table:$tableName",
				label = table?.let { owner -> stringResource(owner.label) } ?: "",
				entries = byFamily[null] ?: emptyList(),
				children =
					byFamily.entries
						.filter { it.key != null }
						.sortedBy { it.key!!.ordinal }
						.map { (family, familyEntries) ->
							ShortcutBranch(
								key = "family:$tableName:${family!!.name}",
								label = stringResource(family.heading),
								entries = familyEntries,
								children = emptyList(),
							)
						},
			)
		}

/**
 * The list the scroll shows: every group's heading, then the commands under it.  A collapsed group keeps its
 * heading and loses the rest, so folding one away takes its commands with it.
 *
 * @param List<ShortcutBranch> tree The groups.
 * @param Set<String> collapsed The collapsed group keys.
 * @return List<ShortcutLine> The lines.
 */
internal fun flatten(tree: List<ShortcutBranch>, collapsed: Set<String>): List<ShortcutLine> {
	val lines = mutableListOf<ShortcutLine>()

	for (branch in tree) {
		lines += ShortcutLine(branch = branch, entry = null, group = branch, depth = 0)

		if (branch.key in collapsed) {
			continue
		}

		for (entry in branch.entries) {
			lines += ShortcutLine(branch = null, entry = entry, group = branch, depth = 1)
		}

		for (child in branch.children) {
			lines += ShortcutLine(branch = child, entry = null, group = branch, depth = 1)

			if (child.key in collapsed) {
				continue
			}

			for (entry in child.entries) {
				lines += ShortcutLine(branch = null, entry = entry, group = branch, depth = 2)
			}
		}
	}

	return lines
}