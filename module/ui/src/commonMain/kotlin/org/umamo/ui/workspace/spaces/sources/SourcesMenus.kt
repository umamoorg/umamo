package org.umamo.ui.workspace.spaces.sources

import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.workspace.commands.IgnoreLayerRequest
import org.umamo.ui.workspace.commands.ReloadScope
import org.umamo.ui.workspace.commands.ReplaceRequest

/*
 * The entries of every menu a row and its chip show.  Plain functions over the resolved labels: a menu's
 * entries are new objects on every build either way, so nothing here needs composition, and commonTest
 * pins the entries directly.  A row's chip and its context menu build their entries here alike, so a
 * mouse and a pen reach the same actions.
 */

/** The command Replace Artwork dispatches; the app picks the file. */
private const val REPLACE_ARTWORK_COMMAND = "sources.replaceArtwork"

/** The command Reload This File dispatches, scoped to the row's file. */
private const val RELOAD_ARTWORK_COMMAND = "document.reloadArtwork"

/** The command the ignore toggle dispatches. */
private const val IGNORE_LAYER_COMMAND = "sources.ignoreLayer"

/**
 * A file row's two actions: Replace Artwork… and Reload This File.
 *
 * @param ArtSourceId     sourceId The file.
 * @param SourcesLabels   labels   The space's localized chrome.
 * @param CommandRegistry commands The registry to dispatch through.
 * @return List<MenuItem> The entries.
 */
internal fun sourceFileMenuItems(sourceId: ArtSourceId, labels: SourcesLabels, commands: CommandRegistry): List<MenuItem> =
	listOf(
		MenuItem.Action(labels.replaceArtwork, onSelect = { commands.invoke(REPLACE_ARTWORK_COMMAND, ReplaceRequest(sourceId)) }),
		MenuItem.Action(labels.reloadFile, onSelect = { commands.invoke(RELOAD_ARTWORK_COMMAND, ReloadScope(setOf(sourceId))) }),
	)

/**
 * An unbound layer row's one action: Ignore Layer, so a reload never mints a drawable for it, while the
 * row is plain unbound, and Stop Ignoring once it is ignored.
 *
 * @param SourceLayerRef  ref      The layer.
 * @param Boolean         ignored  Whether the row is ignored now.
 * @param SourcesLabels   labels   The space's localized chrome.
 * @param CommandRegistry commands The registry to dispatch through.
 * @return List<MenuItem> The entry.
 */
internal fun layerMenuItems(ref: SourceLayerRef, ignored: Boolean, labels: SourcesLabels, commands: CommandRegistry): List<MenuItem> =
	listOf(
		MenuItem.Action(
			if (ignored) labels.stopIgnoring else labels.ignoreLayer,
			onSelect = { commands.invoke(IGNORE_LAYER_COMMAND, IgnoreLayerRequest(ref, ignored = !ignored)) },
		),
	)

/**
 * A review row's proposal page: the matcher's proposal to accept when there is one, a relink by hand, or
 * leaving the binding as it is.  Leaving does nothing but close the menu.
 *
 * @param SourcesLabels labels         The space's localized chrome.
 * @param String?       acceptLabel    The Accept entry naming the proposed layer and its confidence, or null
 *   when nothing is proposed, which leaves the entry out.
 * @param Function      onAccept       Accepts the proposal.
 * @param Function      onRelinkByHand Turns the menu to the relink list.
 * @return List<MenuItem> The entries.
 */
internal fun reviewMenuItems(labels: SourcesLabels, acceptLabel: String?, onAccept: () -> Unit, onRelinkByHand: () -> Unit): List<MenuItem> =
	buildList {
		if (acceptLabel != null) {
			add(MenuItem.Action(label = acceptLabel, onSelect = onAccept))
		}

		add(MenuItem.Action(label = labels.relinkByHand, onSelect = onRelinkByHand))
		add(MenuItem.Action(label = labels.leave, onSelect = {}))
	}

/**
 * The searchable relink menu: every listed file's layers under the file's name, with the search box on
 * top and Unbind and Delete Art leading when offered - the tile chip's menu and the review chip's by-hand
 * page.  The current binding reads dimmed and does nothing; a query that matches nothing leaves one dimmed
 * line saying so.
 *
 * @param SourcesLabels     labels        The space's localized chrome.
 * @param List<RelinkGroup> groups        The files and the layers to list under each, as the query left them.
 * @param SourceLayerRef?   current       The binding the picker starts from, shown dimmed, or null.
 * @param String            query         The search text.
 * @param Function          onQueryChange Takes the edited search text.
 * @param Function?         onUnbind      Clears the binding, offered as an Unbind row when non-null; null hides the row.
 * @param Function?         onDelete      Removes the tile from the atlas, offered as a Delete Art row when
 *   non-null (a tile no drawable samples); null hides the row.
 * @param Function          onPick        Takes the chosen layer by its file and key; the menu dismisses itself.
 * @return List<MenuItem> The menu, search box first.
 */
internal fun relinkMenuItems(
	labels: SourcesLabels,
	groups: List<RelinkGroup>,
	current: SourceLayerRef?,
	query: String,
	onQueryChange: (String) -> Unit,
	onUnbind: (() -> Unit)?,
	onDelete: (() -> Unit)?,
	onPick: (sourceId: ArtSourceId, layerKey: String) -> Unit,
): List<MenuItem> =
	buildList {
		add(MenuItem.Search(value = query, onValueChange = onQueryChange, width = SOURCES_RELINK_MENU_WIDTH))

		if (onUnbind != null) {
			add(MenuItem.Action(label = labels.unbind, onSelect = onUnbind))
		}

		if (onDelete != null) {
			add(MenuItem.Action(label = labels.deleteArt, onSelect = onDelete))
		}

		if (onUnbind != null || onDelete != null) {
			add(MenuItem.Separator)
		}

		if (groups.isEmpty()) {
			add(MenuItem.Action(label = labels.noMatches, onSelect = {}, enabled = false))
		}

		for (group in groups) {
			val source = group.source
			add(MenuItem.Heading(source.name))

			for (layer in group.layers) {
				val key = layer.key
				val bound = current?.sourceId == source.id && current.layerKey == key
				add(MenuItem.Action(label = layer.name, enabled = !bound, onSelect = { onPick(source.id, key) }))
			}
		}
	}