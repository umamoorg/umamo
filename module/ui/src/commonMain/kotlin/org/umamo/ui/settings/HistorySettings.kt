package org.umamo.ui.settings

import org.umamo.edit.DEFAULT_HISTORY_LIMIT

/**
 * The settings key, bundled default, and commit clamp for the undo-history cap.  The same split as
 * [org.umamo.ui.viewport.ViewportSettings]: the preferences row and the shell's session wiring both read
 * these constants, so neither duplicates the key literal.
 *
 * The cap is applied by writing [org.umamo.edit.EditorSession.historyLimit]; :edit holds no settings
 * dependency (it is the platform-neutral editing core), so the value is always pushed in from here.
 */
internal object HistorySettings {
	const val HISTORY_LIMIT_KEY = "interface.historyLimit"

	/**
	 * The fallback for a missing or unparseable value, kept in lockstep with defaultSettings.json.  Taken
	 * from :edit's own constant so the number itself lives in exactly one Kotlin place.
	 */
	const val HISTORY_LIMIT_DEFAULT = DEFAULT_HISTORY_LIMIT

	/**
	 * The commit clamp: at least one retained step (the stack always keeps the live state), up to 1000.
	 * The ceiling can be generous because snapshots structurally share their unchanged sub-trees - a step
	 * costs what that edit actually changed, not a copy of the document.
	 */
	val HISTORY_LIMIT_RANGE = 1..1000
}