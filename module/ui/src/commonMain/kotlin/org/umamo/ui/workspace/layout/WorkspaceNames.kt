package org.umamo.ui.workspace.layout

/**
 * The localized chrome a workspace's name is made from, for the code that names one outside a composition: the
 * workspace commands run on a key press or a menu pick, where no resource can be resolved.  The shell resolves these
 * inside its locale key after every language switch and the commands read them when they run.
 *
 * @property String newBaseName The base name a new or imported workspace is named from (deduped).
 * @property String copySuffix What follows a workspace's title in the name of its copy.
 * @property Map<String, String> builtinTitles The built-in workspaces' titles by id; a built-in has no stored name
 *   until it is renamed, and shows its title in the meantime.
 */
internal class WorkspaceNames(
	val newBaseName: String,
	val copySuffix: String,
	val builtinTitles: Map<String, String>,
) {
	/**
	 * The title [workspace] goes by on its tab: its own name, else its built-in title, else its id.
	 *
	 * @param Workspace workspace The workspace.
	 * @return String The title.
	 */
	fun titleOf(workspace: Workspace): String = workspace.name ?: builtinTitles[workspace.id] ?: workspace.id

	/**
	 * The name a copy of [workspace] is given before it is deduped.
	 *
	 * @param Workspace workspace The workspace being copied.
	 * @return String The copy's suggested name.
	 */
	fun copyNameOf(workspace: Workspace): String = "${titleOf(workspace)} $copySuffix"

	companion object {
		/** What stands in until the shell's first composition has resolved the strings. */
		val Unresolved = WorkspaceNames(newBaseName = "", copySuffix = "", builtinTitles = emptyMap())
	}
}