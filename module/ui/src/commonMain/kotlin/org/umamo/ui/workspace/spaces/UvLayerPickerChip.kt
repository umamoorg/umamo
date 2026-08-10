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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
		Column(modifier = Modifier.widthIn(min = 240.dp, max = 360.dp).padding(4.dp)) {
			SearchField(value = query, onValueChange = { updated -> query = updated }, modifier = Modifier.padding(4.dp))
			if (filtered.isEmpty()) {
				Text(
					text = stringResource(if (entries.isEmpty()) Res.string.uv_layer_picker_empty else Res.string.uv_layer_picker_no_matches),
					modifier = Modifier.padding(8.dp),
				)
			} else {
				// Capped rather than scrolling the whole popup: a real model carries hundreds of layers, so
				// the list virtualizes and the search is how you reach the rest.
				LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
					items(filtered, key = { entry -> entry.key }) { entry ->
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