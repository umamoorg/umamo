package org.umamo.ui.settings

/**
 * The settings keys for the preferences window's own state.  The same split as [HistorySettings]: the window and
 * its test read these constants, so neither duplicates the key literal.
 */
internal object PreferencesSettings {
	/**
	 * The ids of the folded cards and sub-panels ([PreferencesSection.id], and the keybindings page's
	 * [keybindingFoldId] cards), as a JSON array of strings.  Only the
	 * folded ones are listed, so a card the list does not name is open: a new card starts open, and an id a later
	 * build drops is inert.  The bundled value is the empty array.
	 */
	const val COLLAPSED_SECTIONS_KEY = "interface.preferences.collapsedSections"
}