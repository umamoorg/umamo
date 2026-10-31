package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.*

/**
 * The outliner's localized chrome, resolved where the space composes and handed down whole, so a row
 * and its menu name what they show without a lookup of their own.  The document's own names (its parts,
 * drawables, and deformers) are never here: they are the rigger's data, not chrome.
 *
 * Immutable so Compose compares it by value: a holder built again from the same strings leaves every row
 * that takes it skippable.
 *
 * @param String root            The synthetic puppet root row.
 * @param String armature        The synthetic deformer-hierarchy row.
 * @param String expand          The accessible name of a closed branch's chevron.
 * @param String collapse        The accessible name of an open branch's chevron.
 * @param String selectHierarchy The menu entry that selects a row's whole subtree.
 * @param String visibility      The menu entry that flips a row's visibility, and the eye's accessible name.
 * @param String selectable      The menu entry that flips a row's selectability, and the pointer's accessible name.
 * @param String rename          The menu entry that opens a row's inline rename.
 * @param String delete          The menu entry that deletes a row; a part keeps its contents.
 * @param String deleteHierarchy The menu entry that deletes a part with its whole subtree.
 */
@Immutable
internal data class OutlinerLabels(
	val root: String,
	val armature: String,
	val expand: String,
	val collapse: String,
	val selectHierarchy: String,
	val visibility: String,
	val selectable: String,
	val rename: String,
	val delete: String,
	val deleteHierarchy: String,
	val createWarp: String,
)

/**
 * Resolves the outliner's chrome in the current locale.
 *
 * @return OutlinerLabels The labels, which follow a live locale switch like any other string read.
 */
@Composable
internal fun outlinerLabels(): OutlinerLabels =
	OutlinerLabels(
		root = stringResource(Res.string.outliner_root),
		armature = stringResource(Res.string.outliner_armature),
		expand = stringResource(Res.string.common_expand),
		collapse = stringResource(Res.string.common_collapse),
		selectHierarchy = stringResource(Res.string.outliner_menu_select_hierarchy),
		visibility = stringResource(Res.string.outliner_menu_visibility),
		selectable = stringResource(Res.string.outliner_menu_selectable),
		rename = stringResource(Res.string.outliner_menu_rename),
		delete = stringResource(Res.string.outliner_menu_delete),
		deleteHierarchy = stringResource(Res.string.outliner_menu_delete_hierarchy),
		createWarp = stringResource(Res.string.cmd_deformer_create_warp),
	)