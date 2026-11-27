package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.chip.DropdownChip
import org.umamo.ui.kit.chip.DropdownChipStyle
import org.umamo.ui.kit.menu.Menu
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.artwork.percentOf
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons

/*
 * The chip a row ends with.  Each is a Compact dropdown chip over a kit Menu, like every other menu in the
 * application, and each builds its entries only while it is open: a closed chip costs its row a glyph.
 * The entries themselves come from SourcesMenus.kt.
 */

/**
 * A row's actions chip: the entries the row's context menu offers, drawn as the kit [Menu] so the chip
 * and the context menu are one menu in two places.  A file row's chip replaces or reloads the file; an
 * unbound layer row's chip ignores the layer, or stops ignoring it.
 *
 * @param String   contentDescription The chip's accessible name.
 * @param Function items              Builds the menu's entries, called while the chip is open.
 */
@Composable
internal fun SourcesActionsChip(contentDescription: String, items: () -> List<MenuItem>) {
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	DropdownChip(
		expanded = open,
		onExpandRequest = { open = true },
		contentDescription = contentDescription,
		icon = icons.dots,
		style = DropdownChipStyle.Compact,
	) {
		Menu(items = items(), onDismissRequest = { open = false }, positionProvider = BelowAnchorPositionProvider)
	}
}

/**
 * A review row's chip: accept the matcher's proposal, relink by hand through the same list a tile row's
 * chip shows, or leave the binding as it is.  Accepting relinks every tile bound to the lost key as one
 * step, so the tiles move together and the art is pulled exactly as a manual relink pulls it.
 *
 * The open chip reads the open document only while it shows, so the row is handed no model.
 *
 * @param SourcesNode    node     The review row, carrying its suggestion when there is one.
 * @param SourceLayerRef ref      The lost binding the row stands for.
 * @param SourcesLabels  labels   The space's localized chrome.
 * @param Function       onRelink Rebinds the tiles as one step (null unbinds), with the tiles the proposal retires.
 */
@Composable
internal fun ReviewChip(
	node: SourcesNode,
	ref: SourceLayerRef,
	labels: SourcesLabels,
	onRelink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit,
) {
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	var byHand by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	// The tiles under review are the row's own list: every tile bound to the lost key, by (file, key),
	// whatever a search leaves listed under the row.
	val boundTiles = node.tileIds
	val suggestion = node.suggestion
	val relinkAll: (SourceLayerRef?, List<AtlasTileId>) -> Unit = { target, retire ->
		if (boundTiles.isNotEmpty()) {
			onRelink(boundTiles, target, retire)
		}
	}
	DropdownChip(
		expanded = open,
		onExpandRequest = {
			open = true
			byHand = false
		},
		contentDescription = labels.review,
		icon = icons.linked,
		style = DropdownChipStyle.Compact,
		iconTint = colors.signalCaution,
	) {
		val puppet = LocalPuppet.current
		val tiles = puppet?.atlas?.tiles.orEmpty()

		if (byHand) {
			val sources = puppet?.sources.orEmpty()
			val groups = remember(sources, query) { relinkGroups(sources, query) }
			Menu(
				items =
					relinkMenuItems(
						labels = labels,
						groups = groups,
						current = ref,
						query = query,
						onQueryChange = { updated -> query = updated },
						onUnbind = null,
						onDelete = null,
						onPick = { sourceId, layerKey -> relinkAll(relinkTargetRef(tiles, sourceId, layerKey), emptyList()) },
					),
				onDismissRequest = {
					open = false
					byHand = false
				},
				positionProvider = BelowAnchorPositionProvider,
			)
		} else {
			val acceptLabel =
				suggestion?.let { proposal ->
					val wording = if (proposal.retires.isEmpty()) Res.string.sources_suggestion_accept else Res.string.sources_suggestion_accept_merge
					stringResource(wording, proposal.candidateName, percentOf(proposal.score))
				}
			// The proposal page and the list are one open chip: picking Relink Manually flips the chip to
			// the list.  A menu row runs its action before the root dismiss, so the dismiss sees the flag
			// and keeps the chip open.
			Menu(
				items =
					reviewMenuItems(
						labels = labels,
						acceptLabel = acceptLabel,
						onAccept = {
							if (suggestion != null) {
								relinkAll(relinkTargetRef(tiles, ref.sourceId, suggestion.candidateKey), suggestion.retires)
							}
						},
						onRelinkByHand = { byHand = true },
					),
				onDismissRequest = {
					if (!byHand) {
						open = false
					}
				},
				positionProvider = BelowAnchorPositionProvider,
			)
		}
	}
}

/**
 * A tile row's relink chip: a searchable menu of every listed file's layers, grouped under the file,
 * plus Unbind while the tile is bound and Delete Art while nothing samples it.  Picking closes the menu
 * and rebinds as one undo step.  The list reads the open document only while it shows, so the row is
 * handed no model.
 *
 * @param AtlasTileId     tileId    The tile the chip rebinds.
 * @param SourceLayerRef? current   The layer the tile is bound to now, or null for a tile bound to none.
 * @param Boolean         canDelete Whether the tile may leave the atlas: no drawable samples it.
 * @param SourcesLabels   labels    The space's localized chrome.
 * @param Function        onRelink  Rebinds tiles to one layer (null unbinds); a chip relink retires nothing.
 * @param Function        onDelete  Removes the tile from the atlas.
 */
@Composable
internal fun RelinkChip(
	tileId: AtlasTileId,
	current: SourceLayerRef?,
	canDelete: Boolean,
	labels: SourcesLabels,
	onRelink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit,
	onDelete: () -> Unit,
) {
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	DropdownChip(
		expanded = open,
		onExpandRequest = { open = true },
		contentDescription = labels.relink,
		icon = if (current != null) icons.linked else icons.unlinked,
		// The row is 22.dp; the Header face would overflow it.
		style = DropdownChipStyle.Compact,
	) {
		val puppet = LocalPuppet.current
		val sources = puppet?.sources.orEmpty()
		val tiles = puppet?.atlas?.tiles.orEmpty()
		val groups = remember(sources, query) { relinkGroups(sources, query) }
		val unbind: (() -> Unit)? = if (current != null) ({ onRelink(listOf(tileId), null, emptyList()) }) else null
		Menu(
			items =
				relinkMenuItems(
					labels = labels,
					groups = groups,
					current = current,
					query = query,
					onQueryChange = { updated -> query = updated },
					onUnbind = unbind,
					onDelete = onDelete.takeIf { canDelete },
					onPick = { sourceId, layerKey -> onRelink(listOf(tileId), relinkTargetRef(tiles, sourceId, layerKey), emptyList()) },
				),
			onDismissRequest = { open = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}