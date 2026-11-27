package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.*

/**
 * The Sources space's localized chrome, resolved where the space composes and handed down whole, so a row,
 * its chip, and its menus name what they show without a lookup of their own.  The document's own names (its
 * files, layers, art, and drawables) are never here: they are the rigger's data, not chrome.  Neither is a
 * text that takes a value: a row's secondary text, its status word, and a proposal's Accept entry are
 * resolved where the value is known.
 *
 * Immutable so Compose compares it by value: a holder built again from the same strings leaves every row
 * that takes it skippable.
 *
 * @param String unboundArt     The row that groups the art bound to no layer.
 * @param String fileActions    The accessible name of a file row's chip.
 * @param String replaceArtwork The entry that repoints a file's record at another file.
 * @param String reloadFile     The entry that reads one file again.
 * @param String layerActions   The accessible name of an unbound layer row's chip.
 * @param String ignoreLayer    The entry that keeps a layer out of the rig.
 * @param String stopIgnoring   The entry that lets a reload mint an ignored layer again.
 * @param String review         The accessible name of a review row's chip.
 * @param String relinkByHand   The review chip's entry that opens the relink list.
 * @param String leave          The review chip's entry that leaves the binding as it is.
 * @param String relink         The accessible name of a tile row's chip.
 * @param String unbind         The relink list's entry that clears a tile's binding.
 * @param String deleteArt      The relink list's entry that removes a tile no drawable samples.
 * @param String noMatches      The relink list's line for a search that matches nothing.
 */
@Immutable
internal data class SourcesLabels(
	val unboundArt: String,
	val fileActions: String,
	val replaceArtwork: String,
	val reloadFile: String,
	val layerActions: String,
	val ignoreLayer: String,
	val stopIgnoring: String,
	val review: String,
	val relinkByHand: String,
	val leave: String,
	val relink: String,
	val unbind: String,
	val deleteArt: String,
	val noMatches: String,
)

/**
 * Resolves the Sources space's chrome in the current locale.
 *
 * @return SourcesLabels The labels, which follow a live locale switch like any other string read.
 */
@Composable
internal fun sourcesLabels(): SourcesLabels =
	SourcesLabels(
		unboundArt = stringResource(Res.string.sources_unbound_art),
		fileActions = stringResource(Res.string.sources_file_menu),
		replaceArtwork = stringResource(Res.string.sources_file_menu_replace),
		reloadFile = stringResource(Res.string.sources_file_menu_reload),
		layerActions = stringResource(Res.string.sources_layer_menu),
		ignoreLayer = stringResource(Res.string.sources_layer_menu_ignore),
		stopIgnoring = stringResource(Res.string.sources_layer_menu_unignore),
		review = stringResource(Res.string.sources_suggestion_title),
		relinkByHand = stringResource(Res.string.sources_suggestion_relink),
		leave = stringResource(Res.string.sources_suggestion_leave),
		relink = stringResource(Res.string.sources_relink_title),
		unbind = stringResource(Res.string.sources_relink_clear),
		deleteArt = stringResource(Res.string.sources_relink_delete),
		noMatches = stringResource(Res.string.sources_relink_no_matches),
	)