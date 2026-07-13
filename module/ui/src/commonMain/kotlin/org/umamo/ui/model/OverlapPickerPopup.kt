package org.umamo.ui.model

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.kit.AtPointPositionProvider
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.ThumbnailSlot
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * One row of the overlap picker: a drawable to select, its display label, and an optional layer-art
 * thumbnail (shown in the row's leading slot when present, or an empty slot when null).
 *
 * 重なり選択ポップアップの 1 行。選択するドロウアブル、表示ラベル、任意のサムネイル。
 *
 * @property DrawableId id        The drawable this row selects.
 * @property String label         The display label (the Outliner shows the same).
 * @property ImageBitmap? thumbnail A layer-art thumbnail for the leading slot, or null.
 */
data class OverlapEntry(val id: DrawableId, val label: String, val thumbnail: ImageBitmap? = null)

/**
 * A command-palette-styled popup for choosing among art meshes stacked under the cursor, when a single
 * click is ambiguous. Anchored at [anchor]; lists [entries] front-to-back with [defaultIndex] (the most
 * "unambiguously clicked" mesh) pre-highlighted. There is exactly one highlight state (Blender-style,
 * no separate hover): moving the mouse over a row and pressing Up/Down both drive it, Enter or a click
 * picks it, Escape or a click outside dismisses. Shared between desktop and Android — only the trigger
 * differs (Alt-click vs pen long-press). The popup never touches the selection state; it reports the
 * chosen drawable through [onPick] and closure through [onDismiss].
 *
 * カーソル下に重なるアートメッシュから選ぶコマンドパレット風ポップアップ。前面順に並べ、中心度が最も高い
 * 候補を既定で強調する。デスクトップと Android で共用。
 *
 * @param IntOffset anchor          The cursor position to anchor the popup at, in window pixels.
 * @param List entries              The candidates, front-to-back.
 * @param Int defaultIndex          The pre-highlighted row (the centrality winner).
 * @param Function onPick           Called with the chosen drawable id.
 * @param Function onDismiss        Called to close without choosing.
 */
@Composable
fun OverlapPickerPopup(
	anchor: IntOffset,
	entries: List<OverlapEntry>,
	defaultIndex: Int,
	onPick: (DrawableId) -> Unit,
	onDismiss: () -> Unit,
) {
	if (entries.isEmpty()) {
		return
	}
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	var highlighted by remember(entries) { mutableStateOf(defaultIndex.coerceIn(0, entries.size - 1)) }
	val focusRequester = remember { FocusRequester() }
	// Grab focus on open so the navigation keys reach onPreviewKeyEvent (the popup has no text field to
	// hold focus, unlike the command palette).
	LaunchedEffect(Unit) {
		focusRequester.requestFocus()
	}
	Popup(
		popupPositionProvider = AtPointPositionProvider(anchor),
		onDismissRequest = onDismiss,
		properties = PopupProperties(focusable = true),
	) {
		Surface(
			modifier =
				Modifier
					.focusRequester(focusRequester)
					.focusable()
					.onPreviewKeyEvent { event ->
						if (event.type != KeyEventType.KeyDown) {
							false
						} else {
							when (event.key) {
								Key.DirectionDown -> {
									highlighted = (highlighted + 1) % entries.size
									true
								}

								Key.DirectionUp -> {
									highlighted = (highlighted - 1 + entries.size) % entries.size
									true
								}

								Key.Enter, Key.NumPadEnter -> {
									onPick(entries[highlighted].id)
									true
								}

								Key.Escape -> {
									onDismiss()
									true
								}

								else -> false
							}
						}
					},
			color = colors.menuBackground,
			shape = LocalUmamoShapes.current.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.width(IntrinsicSize.Max)) {
				entries.forEachIndexed { index, entry ->
					val isHighlighted = index == highlighted
					Row(
						modifier =
							Modifier
								.fillMaxWidth()
								.padding(horizontal = 4.dp, vertical = 4.dp)
								.background(
									if (isHighlighted) colors.selection else Color.Transparent,
									shape = LocalUmamoShapes.current.small,
								)
								.pointerInput(index) {
									awaitPointerEventScope {
										while (true) {
											val event = awaitPointerEvent()
											// Move only, deliberately not Enter: the popup opens under a
											// stationary cursor, and a synthesized hover on open would stomp
											// the pre-highlighted centrality winner. Real movement over a row
											// steals the single highlight the arrow keys also drive.
											if (event.type == PointerEventType.Move) {
												highlighted = index
											}
										}
									}
								}
								.clickable { onPick(entry.id) }
								.padding(horizontal = 12.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						// Leading layer-art thumbnail. A checker backdrop sits behind it so transparent art reads as
						// a clear silhouette; an untextured drawable leaves the slot empty so label-only rows align.
						ThumbnailSlot(thumbnail = entry.thumbnail)
						Spacer(modifier = Modifier.width(8.dp))
						Text(
							text = entry.label,
							style = typography.bodyMedium,
							color = if (isHighlighted) colors.selectionText else colors.text,
							modifier = Modifier.weight(1f),
						)
					}
				}
			}
		}
	}
}
