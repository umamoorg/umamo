package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.resources.*

/**
 * The headed groups the keybindings page lists commands under, in the order the page shows them.  A command's
 * category is its table's ([CommandTable.keymapCategory]) unless the command names its own
 * ([Command.keymapCategory]), since a table follows the state its handler closes over and a category follows what a
 * rigger looks for.
 *
 * The [id] is what the page's fold state persists, so it never changes once shipped; a renamed category keeps its id.
 *
 * @property String id The stable id the page's fold state is keyed by.
 * @property StringResource heading The category's card heading.
 */
enum class KeymapCategory(val id: String, val heading: StringResource) {
	File("file", Res.string.settings_keybindings_category_file),
	Artwork("artwork", Res.string.settings_keybindings_category_artwork),
	Edit("edit", Res.string.settings_keybindings_category_edit),
	Modes("modes", Res.string.settings_keybindings_category_modes),
	Select("select", Res.string.settings_keybindings_category_select),
	Transform("transform", Res.string.settings_keybindings_category_transform),
	Snap("snap", Res.string.settings_keybindings_category_snap),
	Mesh("mesh", Res.string.settings_keybindings_category_mesh),
	Deformers("deformers", Res.string.settings_keybindings_category_deformers),
	Keyforms("keyforms", Res.string.settings_keybindings_category_keyforms),
	Relations("relations", Res.string.settings_keybindings_category_relations),
	Object("object", Res.string.settings_keybindings_category_object),
	Uv("uv", Res.string.settings_keybindings_category_uv),
	View("view", Res.string.settings_keybindings_category_view),
	Overlays("overlays", Res.string.settings_keybindings_category_overlays),
	Workspace("workspace", Res.string.settings_keybindings_category_workspace),
	Help("help", Res.string.settings_keybindings_category_help),

	/** Where a command built outside every table lands, so the page never drops a bindable command. */
	Other("other", Res.string.settings_keybindings_category_other),
}