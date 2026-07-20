package org.umamo.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import org.umamo.ui.kit.FIELD_ROW_LABEL_WIDTH
import org.umamo.ui.kit.FIELD_ROW_SPACING
import org.umamo.ui.kit.FieldRow
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.CloseButton
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_category_colors
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

/**
 * The preferences categories shown in the left rail, in display order.  Each carries its localized rail
 * label; the right pane switches on the selection in [SettingsWindow] (an exhaustive `when`, so adding a
 * category is a compile-time prompt to give it content).
 *
 * 設定のカテゴリ（左レール）。各自のローカライズ済みラベルを持つ。右ペインは選択に応じて切り替わる。
 *
 * @property StringResource label The rail label resource.
 */
internal enum class SettingsCategory(val label: StringResource) {
	Interface(Res.string.settings_category_interface),
	Colors(Res.string.settings_category_colors),
	Viewport(Res.string.settings_category_viewport),
	Keybindings(Res.string.settings_category_keybindings),
	Pen(Res.string.settings_category_pen),
}

/**
 * The preferences window: a focus-stealing in-window overlay (a scrim over the shell with a centered
 * card), styled like the kit's other modals (the command palette, the confirm dialog) so it reads as
 * the same surface family rather than a host-OS window.  A left category rail selects which section the
 * right pane shows; sections auto-save every change (no OK/Apply), so the only chrome is dismissal.
 *
 * Dismissal is by Escape, a scrim click, or the header close button - the last matters on a keyboardless
 * tablet, where there is no Escape key and the scrim may not read as interactive.  The shell owns the
 * visible state and routes Escape here (mirroring the command palette); this composable only renders.
 *
 * 設定ウィンドウ。シェルを覆うスクリム＋中央カードのモーダル。左レールでセクションを選び、変更は即時保存。
 * Esc・スクリムのクリック・閉じるボタンで閉じる（キーボード無しのタブレット向けに閉じるボタンを用意）。
 *
 * @param Function onDismiss Closes the window (Escape is routed here by the shell; also the scrim / close button).
 */
@Composable
fun SettingsWindow(onDismiss: () -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	var category by remember { mutableStateOf(SettingsCategory.Interface) }
	Box(
		// indication = null: the kit's convention everywhere interactive. Without it the full-size scrim
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
					Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
						when (category) {
							SettingsCategory.Interface -> InterfaceSection()
							SettingsCategory.Colors -> ColorsSection()
							SettingsCategory.Viewport -> ViewportSection()
							SettingsCategory.Keybindings -> KeybindingsSection()
							SettingsCategory.Pen -> PenSection()
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
 * 左のカテゴリ一覧。選択行はアクセント塗り、他はホバーで強調。
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

/** Spacer between stacked setting rows within a section (the shared kit spacing). */
internal val SETTING_ROW_SPACING = FIELD_ROW_SPACING

/** Fixed width of a setting row's left label column, so the controls in a section align. */
internal val SETTING_LABEL_WIDTH = FIELD_ROW_LABEL_WIDTH

/**
 * One labelled settings row: a thin settings-side wrapper over the shared kit [FieldRow], pinning the
 * settings label-column width so every section aligns.  Kept as its own name so the many settings call
 * sites stay a one-liner.
 *
 * 設定 1 行。共有の kit FieldRow を設定用のラベル幅で包む薄いラッパー。
 *
 * @param String   label   The already-localized row label.
 * @param Function control The control composable (a SelectField, etc.).
 */
@Composable
internal fun SettingRow(label: String, control: @Composable () -> Unit) {
	FieldRow(label = label, labelWidth = SETTING_LABEL_WIDTH, control = control)
}
