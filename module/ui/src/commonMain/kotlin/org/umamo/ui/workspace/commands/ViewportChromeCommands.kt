package org.umamo.ui.workspace.commands

import org.umamo.settings.Settings
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.SHOW_SIDEBAR_SETTINGS_KEY
import org.umamo.ui.workspace.SHOW_TOOLBAR_SETTINGS_KEY

/**
 * The viewport chrome toggles.  They write settings rather than session or layout state, so the chrome
 * follows its keys reactively and a toggle takes effect across every open viewport at once.
 *
 * Registered by the settings-backed shell wrapper, not by EditorShell itself: the shell's contract is to
 * stay Settings-free so it remains standalone-runnable and unit-testable, which is the same division the
 * app-registered File commands follow.  A shell mounted without settings simply lacks these two.
 *
 * @param Settings settings The merged settings tree the toggles read and write.
 * @return List<Command> The commands to register.
 */
internal fun viewportChromeCommands(settings: Settings): List<Command> =
	listOf(
		Command("view.toggleToolbar", title = Res.string.cmd_view_toggle_toolbar) {
			settings.setBoolean(SHOW_TOOLBAR_SETTINGS_KEY, !(settings.getBoolean(SHOW_TOOLBAR_SETTINGS_KEY) ?: true))
		},
		Command("view.toggleSidebar", title = Res.string.cmd_view_toggle_sidebar) {
			settings.setBoolean(SHOW_SIDEBAR_SETTINGS_KEY, !(settings.getBoolean(SHOW_SIDEBAR_SETTINGS_KEY) ?: false))
		},
	)