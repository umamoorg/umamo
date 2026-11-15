package org.umamo.ui.menu

import org.umamo.ui.action.CommandFamily

/**
 * The application menu bar, left to right: one command family per menu.
 *
 * The bar holds no row of its own.  Each menu is its family as the registry lists it - its blocks, its submenus,
 * and every row's label, accelerator, and grayed state - so which commands a menu offers is said by the command
 * tables and nowhere else, and a row a host never registered (Open Log Folder where no file manager can be handed
 * the folder) is simply not there.  Desktop and the keyboardless tablet mount the same list, which is what keeps the
 * two on one interface.
 */
val APP_MENU_BAR: List<CommandFamily> =
	listOf(
		CommandFamily.File,
		CommandFamily.Edit,
		CommandFamily.Select,
		CommandFamily.Workspace,
		CommandFamily.Help,
	)