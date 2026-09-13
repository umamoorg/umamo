package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.drawableIdsByAtlasTile
import org.umamo.ui.action.rankCommandMatches
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons

/** The layer menu's width, which its search box fixes so the rows stay put as the search narrows them. */
private val LAYER_PICKER_WIDTH = 320.dp

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
 * A kit [Menu] with a search box, so it looks like every other menu and closes on a pick.
 */
@Composable
internal fun UvLayerPickerChip() {
	val puppet = LocalPuppet.current
	val session = LocalEditorSession.current
	var query by remember { mutableStateOf("") }
	val entries = puppet?.atlas?.tiles.orEmpty()
	// The tile -> drawables inverse, remembered with the model: a row says how many drawables share its
	// art and selects one of them, and rebuilding that per row would walk the whole drawable list each time.
	val drawableIdsByTile = remember(puppet) { puppet?.drawableIdsByAtlasTile().orEmpty() }
	// Remembered on both inputs: ranking scores and sorts the document's whole layer inventory, which
	// runs to hundreds of rows, and this chip recomposes for header state that has nothing to do with
	// either of them.
	val filtered =
		remember(entries, query) {
			if (query.isBlank()) {
				entries
			} else {
				rankCommandMatches(entries, query, labelOf = { entry -> entry.name }, idOf = { entry -> entry.id.raw })
			}
		}
	var open by remember { mutableStateOf(false) }
	val emptyLabel = stringResource(if (entries.isEmpty()) Res.string.uv_layer_picker_empty else Res.string.uv_layer_picker_no_matches)
	// One row per ranked layer; a layer no drawable uses is listed dimmed, since it selects nothing.
	// The menu's search box fixes the width, so the rows stay put as the search narrows them.
	val rows =
		filtered.map { entry ->
			val users = drawableIdsByTile[entry.id].orEmpty()
			MenuItem.Action(
				label = layerRowLabel(entry, users.size),
				enabled = users.isNotEmpty(),
				onSelect = {
					// The first drawable using this art: with duplicates any of them shows the same
					// layer, and picking deterministically beats picking arbitrarily.
					users.firstOrNull()?.let { drawableId ->
						session?.setSelection(SelectionOps.replace(SelectionTarget.Drawable(drawableId)))
					}
				},
			)
		}
	val items =
		buildList {
			add(MenuItem.Search(value = query, onValueChange = { updated -> query = updated }, width = LAYER_PICKER_WIDTH))
			if (rows.isEmpty()) {
				add(MenuItem.Action(label = emptyLabel, onSelect = {}, enabled = false))
			}
			addAll(rows)
		}
	DropdownChip(
		expanded = open,
		onExpandRequest = { open = true },
		contentDescription = stringResource(Res.string.uv_layer_picker_title),
		icon = LocalUmamoIcons.search,
	) {
		Menu(items = items, onDismissRequest = { open = false }, positionProvider = BelowAnchorPositionProvider)
	}
}

/**
 * One layer row's text: its name, its pixel size, and how many drawables sample it.
 *
 * @param AtlasTile entry     The tile to describe.
 * @param Int       userCount How many drawables sample it.
 * @return String The row label.
 */
@Composable
private fun layerRowLabel(entry: AtlasTile, userCount: Int): String =
	stringResource(
		Res.string.uv_layer_picker_row,
		entry.name,
		entry.width,
		entry.height,
		userCount,
	)