package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.*

/**
 * The panel's localized chrome, resolved where the panel composes and handed down whole, so a row names
 * what it shows without a lookup of its own.
 *
 * Immutable so Compose compares it by value: a holder built again from the same strings leaves every
 * control that takes it skippable.
 *
 * @param String reset                  The accessible label of a row's reset glyph.
 * @param String rangeToggle            The accessible label of the chevron that opens a range editor.
 * @param String link                   The accessible label of the glyph that links a slider with the one below.
 * @param String unlink                 The accessible label of the glyph that splits a pad into two sliders.
 * @param String reorderHandle          The accessible label of a row's drag handle.
 * @param String newGroup               The menu entry that creates a group.
 * @param String rename                 The menu entry that opens a name for editing.
 * @param String deleteGroup            The menu entry that deletes a group.
 * @param String addKeyFormParameter    The menu entry that creates a key-form parameter.
 * @param String addBlendShapeParameter The menu entry that creates a blend-shape parameter.
 * @param String deleteParameter        The menu entry that deletes a parameter.
 * @param String defaultGroupName       The name a created group starts with.
 * @param String defaultParameterName   The name a created parameter starts with.
 * @param String repeat                 The range editor's checkbox that makes an axis wrap past its limits.
 * @param String note                   The caption of the range editor's note field.
 * @param String notePlaceholder        The hint the note field shows while it is empty.
 */
@Immutable
internal data class ParameterLabels(
	val reset: String,
	val rangeToggle: String,
	val link: String,
	val unlink: String,
	val reorderHandle: String,
	val newGroup: String,
	val rename: String,
	val deleteGroup: String,
	val addKeyFormParameter: String,
	val addBlendShapeParameter: String,
	val deleteParameter: String,
	val defaultGroupName: String,
	val defaultParameterName: String,
	val repeat: String,
	val note: String,
	val notePlaceholder: String,
)

/**
 * Resolves the panel's chrome in the current locale.
 *
 * @return ParameterLabels The labels, which follow a live locale switch like any other string read.
 */
@Composable
internal fun parameterLabels(): ParameterLabels =
	ParameterLabels(
		reset = stringResource(Res.string.parameter_reset),
		rangeToggle = stringResource(Res.string.parameter_range_section),
		link = stringResource(Res.string.parameter_link),
		unlink = stringResource(Res.string.parameter_unlink),
		reorderHandle = stringResource(Res.string.parameter_reorder_handle),
		newGroup = stringResource(Res.string.parameter_new_group),
		rename = stringResource(Res.string.parameter_menu_rename),
		deleteGroup = stringResource(Res.string.parameter_menu_delete_group),
		addKeyFormParameter = stringResource(Res.string.parameter_menu_add_keyform),
		addBlendShapeParameter = stringResource(Res.string.parameter_menu_add_blendshape),
		deleteParameter = stringResource(Res.string.parameter_menu_delete),
		defaultGroupName = stringResource(Res.string.parameter_group_default_name),
		defaultParameterName = stringResource(Res.string.parameter_default_name),
		repeat = stringResource(Res.string.parameter_range_repeat),
		note = stringResource(Res.string.parameter_range_note),
		notePlaceholder = stringResource(Res.string.parameter_range_note_placeholder),
	)