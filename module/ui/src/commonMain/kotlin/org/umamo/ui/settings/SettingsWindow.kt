package org.umamo.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.button.CloseButton
import org.umamo.ui.kit.container.NestedSection
import org.umamo.ui.kit.container.SectionCard
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_category_colors
import org.umamo.ui.resources.settings_category_import
import org.umamo.ui.resources.settings_category_interface
import org.umamo.ui.resources.settings_category_keybindings
import org.umamo.ui.resources.settings_category_pen
import org.umamo.ui.resources.settings_category_viewport
import org.umamo.ui.resources.settings_close
import org.umamo.ui.resources.settings_title
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/** Fixed width of the left category rail; wide enough for the longest localized category label. */
private val CATEGORY_RAIL_WIDTH = 168.dp

/** The inset around a page that is not built from cards (Pen). */
private val PLAIN_PAGE_PADDING = 20.dp

/** The inset around a page of cards, and the gap between two cards, as the Properties panel spaces them. */
private val CARD_PAGE_PADDING = 12.dp
private val CARD_SPACING = 6.dp

/** The gap between the rows pinned above a card page's scroll, the same as between the rows inside a card. */
private val PINNED_ROW_SPACING = 4.dp

/**
 * The preferences categories shown in the left rail, in display order.  Each carries its localized rail
 * label; the right pane switches on the selection in [SettingsWindow] (an exhaustive `when`, so adding a
 * category is a compile-time prompt to give it content).
 *
 * @property StringResource label The rail label resource.
 */
internal enum class SettingsCategory(val label: StringResource) {
	Interface(Res.string.settings_category_interface),
	Colors(Res.string.settings_category_colors),
	Viewport(Res.string.settings_category_viewport),
	Import(Res.string.settings_category_import),
	Keybindings(Res.string.settings_category_keybindings),
	Pen(Res.string.settings_category_pen),
}

/**
 * The preferences window: a focus-stealing in-window overlay styled like the kit's other modals (the
 * command palette, the confirm dialog), so it reads as the same surface family rather than a host-OS window.
 * Sections auto-save every change, so the only chrome is dismissal; the header close button serves a
 * keyboardless tablet, where there is no Escape key and the scrim may not read as interactive.  The shell owns
 * the visible state and routes Escape here.
 *
 * @param Function onDismiss Closes the window (Escape is routed here by the shell; also the scrim / close button).
 */
@Composable
fun SettingsWindow(onDismiss: () -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	var category by remember { mutableStateOf(SettingsCategory.Interface) }
	val folds = rememberPreferencesFolds()
	Box(
		// indication = null: the kit's convention everywhere interactive.  Without it the full-size scrim
		// renders the default hover/press indication - a second dimming layer that toggles as the pointer
		// enters/leaves the window or presses a dropdown, stacking on the intended scrim.
		modifier =
			Modifier
				.fillMaxSize()
				.background(colors.overlayScrim)
				.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			// The card swallows clicks (enabled = false) so a press inside it is not read as a scrim dismissal,
			// exactly as the confirm dialog and command palette cards do.
			modifier =
				Modifier
					.fillMaxWidth(0.82f)
					.fillMaxHeight(0.82f)
					.widthIn(max = 760.dp)
					.heightIn(max = 560.dp)
					.clickable(enabled = false, onClick = {}),
			color = colors.panelBackground,
			shape = LocalUmamoShapes.current.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				// Header: title on the left, close button on the right.
				Row(
					modifier = Modifier.fillMaxWidth().background(colors.headerBackground).padding(horizontal = 16.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(text = stringResource(Res.string.settings_title), style = typography.titleSmall, modifier = Modifier.weight(1f))
					CloseButton(onClick = onDismiss, contentDescription = stringResource(Res.string.settings_close))
				}
				Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
				Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
					CategoryRail(selected = category, onSelect = { category = it }, modifier = Modifier.width(CATEGORY_RAIL_WIDTH).fillMaxHeight())
					Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
					// The Properties body's ground, so the cards sit one step above it as they do there.
					Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.headerBackground)) {
						when (category) {
							SettingsCategory.Interface -> SettingsCardPage { InterfaceSection(folds) }
							SettingsCategory.Colors -> SettingsCardPage { ColorsSection(folds) }
							SettingsCategory.Viewport -> SettingsCardPage { ViewportSection(folds) }
							SettingsCategory.Import -> SettingsCardPage { ImportSection(folds) }
							SettingsCategory.Keybindings -> KeybindingsSection(folds)
							SettingsCategory.Pen -> Box(modifier = Modifier.padding(PLAIN_PAGE_PADDING)) { PenSection() }
						}
					}
				}
			}
		}
	}
}

/**
 * The left rail of category rows.  The selected row is filled with the accent and its label uses the
 * on-accent text color; the rest hover-highlight, matching the kit's row idiom (the command palette
 * list, menu rows).
 *
 * @param SettingsCategory selected The currently selected category.
 * @param Function         onSelect Called with a category when its row is clicked.
 * @param Modifier         modifier Layout modifier for the rail column.
 */
@Composable
private fun CategoryRail(selected: SettingsCategory, onSelect: (SettingsCategory) -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	Column(modifier = modifier.padding(vertical = 6.dp)) {
		for (entry in SettingsCategory.entries) {
			val isSelected = entry == selected
			val interaction = remember { MutableInteractionSource() }
			val hovered by interaction.collectIsHoveredAsState()
			Row(
				modifier =
					Modifier
						.fillMaxWidth()
						.background(
							when {
								isSelected -> colors.selection
								hovered -> colors.rowHover
								else -> Color.Transparent
							},
						)
						.clickable(interactionSource = interaction, indication = null) { onSelect(entry) }
						.padding(horizontal = 16.dp, vertical = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = stringResource(entry.label),
					style = typography.labelMedium,
					color = if (isSelected) colors.selectionText else colors.text,
				)
			}
		}
	}
}

/**
 * A scrolling page of setting cards, spaced and inset as the Properties panel's, under an optional pinned block that
 * stays put while the cards scroll.
 *
 * @param Function? pinned Rows held above the scroll (a page's search and its page-wide controls), or null for none.
 * @param Function content The page's cards, top to bottom.
 */
@Composable
internal fun SettingsCardPage(pinned: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
	val colors = LocalUmamoColors.current
	Column(modifier = Modifier.fillMaxSize()) {
		if (pinned != null) {
			Column(
				modifier = Modifier.fillMaxWidth().padding(CARD_PAGE_PADDING),
				verticalArrangement = Arrangement.spacedBy(PINNED_ROW_SPACING),
				content = pinned,
			)
			Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
		}

		val scrollState = rememberScrollState()
		Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
			Column(
				modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(CARD_PAGE_PADDING),
				verticalArrangement = Arrangement.spacedBy(CARD_SPACING),
				content = content,
			)
			VerticalScrollbarOverlay(scrollState)
		}
	}
}

/**
 * One collapsible card of settings rows, its fold state persisted through [folds].
 *
 * @param PreferencesFolds folds The window's fold state.
 * @param PreferencesSection section The card, for its title and its persisted id.
 * @param Function content The card's rows, top to bottom.
 */
@Composable
internal fun SettingsCard(folds: PreferencesFolds, section: PreferencesSection, content: @Composable ColumnScope.() -> Unit) {
	SectionCard(
		title = stringResource(section.title),
		expanded = folds.isExpanded(section),
		onToggle = { folds.toggle(section) },
		content = content,
	)
}

/**
 * One collapsible sub-panel inside a [SettingsCard], its fold state persisted through [folds].
 *
 * @param PreferencesFolds folds The window's fold state.
 * @param PreferencesSection section The sub-panel, for its title and its persisted id.
 * @param Function content The sub-panel's rows, top to bottom.
 */
@Composable
internal fun SettingsSubsection(folds: PreferencesFolds, section: PreferencesSection, content: @Composable ColumnScope.() -> Unit) {
	NestedSection(
		title = stringResource(section.title),
		expanded = folds.isExpanded(section),
		onToggle = { folds.toggle(section) },
		content = content,
	)
}