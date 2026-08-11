package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.render.SourceLayerEntry
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.action.rankCommandMatches
import org.umamo.ui.kit.PopupChip
import org.umamo.ui.kit.SearchField
import org.umamo.ui.kit.Text
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLayerTextures
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons

/** The layer panel's width; fixed so the list stays put as the search narrows it (see the panel below). */
private val LAYER_PICKER_WIDTH = 320.dp

/** How tall the layer list grows before it scrolls. */
private val LAYER_PICKER_MAX_LIST_HEIGHT = 320.dp

/**
 * Find a drawable by its artwork: a searchable list of the document's source layers whose rows select
 * the drawable that samples them.
 *
 * This is navigation, not a second selection.  The layer view follows the active drawable, so pointing
 * it at a particular piece of art means selecting the object that uses that art - which is exactly what
 * a row does, writing the ONE session selection.  Naming a layer here would otherwise be a second
 * pointer to keep in sync with the first.
 *
 * Ranked with the command palette's own matcher, so "eye" finds the eye layers by the same rules that
 * find the eye commands.  Rows name the layer, its pixel size, and how many drawables share it -
 * duplicated art is common, and a row selecting one of several is worth seeing before it happens.
 */
@Composable
internal fun UvLayerPickerChip() {
	val layers = LocalLayerTextures.current
	val session = LocalEditorSession.current
	var query by remember { mutableStateOf("") }
	val entries = layers?.layers.orEmpty()
	val filtered =
		if (query.isBlank()) {
			entries
		} else {
			rankCommandMatches(entries, query, labelOf = { entry -> entry.name }, idOf = { entry -> entry.key })
		}
	PopupChip(
		contentDescription = stringResource(Res.string.uv_layer_picker_title),
		icon = LocalUmamoIcons.search,
	) {
		// A FIXED width, unlike the hugging panels the menu chips open: a search panel that re-hugs its
		// widest surviving row would resize under the user's hands on every keystroke.  The command
		// palette pins its width for the same reason.
		Column(modifier = Modifier.width(LAYER_PICKER_WIDTH)) {
			SearchField(
				value = query,
				onValueChange = { updated -> query = updated },
				modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
				width = LAYER_PICKER_WIDTH - 16.dp,
			)
			if (filtered.isEmpty()) {
				Text(
					text = stringResource(if (entries.isEmpty()) Res.string.uv_layer_picker_empty else Res.string.uv_layer_picker_no_matches),
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
				)
			} else {
				// A plain scrolling column, NOT a lazy list.  The panel this opens in measures its content
				// with IntrinsicSize.Max, and a lazy list is a SubcomposeLayout - it cannot answer an
				// intrinsic query at all, and throws rather than degrading.  Every row therefore composes
				// up front, which the menu here does too; the rows are single text lines and the search is
				// what keeps the set small.  Do not reintroduce a lazy list without moving this panel off
				// the intrinsic measurement first.
				Column(
					modifier = Modifier.fillMaxWidth().heightIn(max = LAYER_PICKER_MAX_LIST_HEIGHT).verticalScroll(rememberScrollState()),
				) {
					for (entry in filtered) {
						key(entry.key) {
							LayerPickerRow(
								label = layerRowLabel(entry),
								enabled = entry.boundDrawableIds.isNotEmpty(),
								onClick = {
									// The first drawable using this art: with duplicates any of them shows the same
									// layer, and picking deterministically beats picking arbitrarily.
									entry.boundDrawableIds.firstOrNull()?.let { drawableId ->
										session?.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId(drawableId))))
									}
								},
							)
						}
					}
				}
			}
		}
	}
}

/**
 * One row of the layer list: the label, hover-highlighted, selecting on click.
 *
 * @param String label The row text.
 * @param Boolean enabled Whether the row selects anything (a layer no drawable uses does not).
 * @param Function onClick Invoked when the row is chosen.
 */
@Composable
private fun LayerPickerRow(label: String, enabled: Boolean, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Text(
		text = label,
		color = if (enabled) colors.text else colors.textDisabled,
		modifier =
			Modifier
				.fillMaxWidth()
				.hoverable(interaction, enabled = enabled)
				.background(if (hovered && enabled) colors.rowHover else Color.Transparent)
				.clickable(enabled = enabled, onClick = onClick)
				.padding(horizontal = 8.dp, vertical = 6.dp),
	)
}

/**
 * One layer row's text: its name, its pixel size, and how many drawables sample it.
 *
 * @param SourceLayerEntry entry The layer to describe.
 * @return String The row label.
 */
@Composable
private fun layerRowLabel(entry: SourceLayerEntry): String =
	stringResource(
		Res.string.uv_layer_picker_row,
		entry.name,
		entry.width,
		entry.height,
		entry.boundDrawableIds.size,
	)