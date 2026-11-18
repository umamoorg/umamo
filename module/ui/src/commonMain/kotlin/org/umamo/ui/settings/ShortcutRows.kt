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

/** The glyph slot every row keeps, so a command with a glyph does not step out of the column the rest share. */
private val ROW_ICON_SIZE = 16.dp

/** The gap between a row's glyph slot and its label. */
private val ROW_ICON_LABEL_GAP = 8.dp

/** The width of the group nav down the left of the list. */
private val NAV_WIDTH = 150.dp

/** The width of a binding cell's cap box, so the two columns line up under their headings. */
private val CAP_COLUMN_WIDTH = 140.dp

/** The width of the two small buttons a binding cell carries. */
private val CAP_BUTTON_WIDTH = 18.dp

/** The gap between a group's heading and its rows. */
private val GROUP_GAP = 10.dp

/** The indent of a nested group under its parent. */
private val GROUP_INDENT = 14.dp

/** How fast the band fades in. */
private const val FLASH_IN_MILLIS = 80

/** How fast the band fades back out - slower than it arrives, so the end reads as a fade. */
private const val FLASH_OUT_MILLIS = 280

/**
 * The band a row carries while the pointer is on it, so the name and the bindings it holds read as one row
 * rather than as text at opposite ends of a wide panel.
 *
 * @param Boolean hovered Whether the pointer is on the row.
 * @return Modifier The modifier with the band.
 */
@Composable
internal fun Modifier.hoverBand(hovered: Boolean): Modifier =
	background(if (hovered) LocalUmamoColors.current.rowHover else Color.Transparent)

/**
 * Bands a row for a moment, so a jump that scrolled the list somewhere unexpected still shows where it
 * landed.
 *
 * @param Boolean flash Whether the band is on.
 * @return Modifier The modifier with the band.
 */
@Composable
internal fun Modifier.flashBand(flash: Boolean): Modifier {
	val colors = LocalUmamoColors.current
	val band by animateColorAsState(
		targetValue = if (flash) colors.searchMatchBackground else Color.Transparent,
		animationSpec = tween(durationMillis = if (flash) FLASH_IN_MILLIS else FLASH_OUT_MILLIS),
		label = "flashBand",
	)

	return background(band)
}

/**
 * The nav down the left of the list: the groups, one row apiece, each a jump to that group's heading.
 *
 * @param List<NavTarget> targets The groups and the line each one's heading sits on.
 * @param List<ShortcutBranch> groupOfLine The group each line of the list belongs to.
 * @param LazyListState listState The list's scroll state, read for the group the top of it is in.
 * @param Function onSelect Called with the group whose row was clicked.
 */
@Composable
internal fun GroupNav(
	targets: List<NavTarget>,
	groupOfLine: List<ShortcutBranch>,
	listState: LazyListState,
	onSelect: (NavTarget) -> Unit,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val scrollState = rememberScrollState()

	// The group the top of the list is in, so the nav follows the scroll.  Read here and not in the panel:
	// the panel rebuilds its whole list when it recomposes, and a scroll recomposes it on every frame.
	//
	// The derived state recomputes the group on every frame - it is an array lookup - but it only NOTIFIES
	// when the group actually changes, so the nav redraws on a group boundary rather than on every pixel.
	val activeKey by
		remember(groupOfLine, listState) {
			derivedStateOf { groupOfLine.getOrNull(listState.firstVisibleItemIndex)?.key }
		}

	Column(
		modifier = Modifier.width(NAV_WIDTH).fillMaxHeight().verticalScroll(scrollState).padding(vertical = 4.dp),
	) {
		for (target in targets) {
			val selected = target.key == activeKey
			val interaction = remember { MutableInteractionSource() }
			val hovered by interaction.collectIsHoveredAsState()

			Row(
				modifier =
					Modifier
						.fillMaxWidth()
						.background(
							when {
								selected -> colors.selection
								hovered -> colors.rowHover
								else -> Color.Transparent
							},
						)
						.clickable(interactionSource = interaction, indication = null) { onSelect(target) }
						.padding(start = 16.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = target.label,
					style = typography.bodyMedium,
					color = if (selected) colors.selectionText else colors.text,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

/**
 * The headings over the two binding columns, laid out exactly as the rows are, so each heading sits over the
 * column it names.
 *
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun BindingColumnHeader(modifier: Modifier = Modifier) {
	val typography = LocalUmamoTypography.current
	val colors = LocalUmamoColors.current

	Row(
		modifier = modifier.fillMaxWidth().padding(start = GROUP_INDENT, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(modifier = Modifier.width(ROW_ICON_SIZE))
		Spacer(modifier = Modifier.width(ROW_ICON_LABEL_GAP))
		Spacer(modifier = Modifier.weight(1f))
		Text(
			text = stringResource(Res.string.settings_keybindings_slot_primary),
			style = typography.labelSmall,
			color = colors.textMuted,
			modifier = Modifier.width(CAP_COLUMN_WIDTH).padding(start = 4.dp),
		)
		Spacer(modifier = Modifier.width(CAP_BUTTON_WIDTH * 2))
		Text(
			text = stringResource(Res.string.settings_keybindings_slot_alternative),
			style = typography.labelSmall,
			color = colors.textMuted,
			modifier = Modifier.width(CAP_COLUMN_WIDTH).padding(start = 4.dp),
		)
		Spacer(modifier = Modifier.width(CAP_BUTTON_WIDTH * 2))
	}
}

/**
 * A group's heading: the fold control and the name, large enough to read as a heading.
 *
 * @param String label The group's name.
 * @param Int depth How deep the group sits.
 * @param Boolean expanded Whether the group is open.
 * @param Boolean flash Whether the group is the one a jump just landed on.
 * @param Function onToggle Called when the heading is clicked.
 */
@Composable
internal fun GroupHeading(
	label: String,
	depth: Int,
	expanded: Boolean,
	flash: Boolean,
	onToggle: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()

	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.hoverBand(hovered)
				.flashBand(flash)
				.clickable(interactionSource = interaction, indication = null) { onToggle() }
				.padding(start = GROUP_INDENT * depth, top = GROUP_GAP, bottom = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		DisclosureChevron(expanded = expanded, tint = colors.textMuted)
		Spacer(modifier = Modifier.width(4.dp))
		Text(text = label, style = typography.titleMedium, color = colors.text)
	}
}

/**
 * One command's row: its glyph, its name, and its two binding slots.
 *
 * @param ShortcutEntry entry The row's command.
 * @param Int depth How deep the row sits.
 * @param String query The search query, whose word is banded in the name.
 * @param Boolean flash Whether the group is the one a jump just landed on.
 * @param Boolean conflict Whether both of the command's bindings are claimed by another command too.
 * @param Function onEdit Called with the command, the slot, and the cell's bounds when a slot is clicked.
 */
@Composable
internal fun ShortcutRow(
	entry: ShortcutEntry,
	depth: Int,
	query: String,
	flash: Boolean,
	conflict: Boolean,
	onEdit: (String, ShortcutSlot, IntRect?) -> Unit,
) {
	val keymap = LocalKeymap.current
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val slots = keymap.slotsFor(entry.command.id)

	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()

	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.hoverable(interactionSource = interaction)
				.hoverBand(hovered)
				.flashBand(flash)
				.padding(start = GROUP_INDENT * depth, top = 2.dp, bottom = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Canvas(modifier = Modifier.size(ROW_ICON_SIZE)) {
			entry.command.icon?.let { icon -> drawIcon(icon, colors.textMuted) }
		}
		Spacer(modifier = Modifier.width(ROW_ICON_LABEL_GAP))
		Text(
			text = highlightedText(entry.label, query),
			style = typography.bodySmall,
			color = if (conflict) colors.signalBad else colors.text,
			modifier = Modifier.weight(1f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		BindingCell(
			chord = slots.primary,
			commandId = entry.command.id,
			slot = ShortcutSlot.Primary,
			onEdit = onEdit,
		)
		BindingCell(
			chord = slots.alternative,
			commandId = entry.command.id,
			slot = ShortcutSlot.Alternative,
			onEdit = onEdit,
		)
	}
}

/**
 * One binding slot: the key caps it holds, and the two small buttons that reset and unbind it.
 *
 * @param KeyChord? chord The chord the slot holds, or null.
 * @param String commandId The command the slot belongs to.
 * @param ShortcutSlot slot The slot the cell stands for.
 * @param Function onEdit Called with the command, the slot, and the cell's bounds when the caps are clicked.
 */
@Composable
internal fun BindingCell(
	chord: KeyChord?,
	commandId: String,
	slot: ShortcutSlot,
	onEdit: (String, ShortcutSlot, IntRect?) -> Unit,
) {
	val settings = LocalSettings.current
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val icons = LocalUmamoIcons
	val defaults = remember(settings, commandId) { defaultSlotsFor(settings, commandId) }
	val defaultChord = if (slot == ShortcutSlot.Primary) defaults.primary else defaults.alternative
	val anchor = remember { CellAnchor() }
	val shortcutEditor = LocalShortcutEditor.current

	DisposableEffect(commandId, slot, shortcutEditor) {
		shortcutEditor.registerCell(commandId, slot, anchor)

		onDispose { }
	}

	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(
			modifier =
				Modifier
					.width(CAP_COLUMN_WIDTH)
					.onGloballyPositioned { coordinates ->
						val bounds = coordinates.boundsInRoot()
						anchor.bounds = IntRect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
					}
					.clickable { onEdit(commandId, slot, anchor.bounds) }
					.padding(horizontal = 4.dp, vertical = 2.dp),
			contentAlignment = Alignment.CenterStart,
		) {
			if (chord != null) {
				KeyChordView(
					parts = acceleratorParts(chord),
					separator = null,
					keyIcon = specialKeyIcon(chord.keyName),
				)
			} else {
				Text(
					text = stringResource(Res.string.settings_keybindings_unbound),
					style = typography.labelSmall,
					color = colors.textMuted,
				)
			}
		}
		val resetEnabled = chord != defaultChord
		val unbindEnabled = chord != null

		if (resetEnabled || unbindEnabled) {
			IconButton(
				icon = icons.reset,
				onClick = { resetSlot(settings, commandId, slot) },
				contentDescription = stringResource(Res.string.settings_keybindings_reset_slot),
				size = DpSize(18.dp, 18.dp),
				glyphSize = 14.dp,
				enabled = resetEnabled,
				showTooltip = false,
			)
			IconButton(
				icon = icons.close,
				onClick = { unbindSlot(settings, commandId, slot) },
				contentDescription = stringResource(Res.string.settings_keybindings_unbind_slot),
				size = DpSize(18.dp, 18.dp),
				glyphSize = 14.dp,
				enabled = unbindEnabled,
				showTooltip = false,
			)
		} else {
			Spacer(modifier = Modifier.width(CAP_BUTTON_WIDTH * 2))
		}
	}
}