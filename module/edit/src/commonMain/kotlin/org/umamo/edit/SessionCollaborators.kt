package org.umamo.edit

/**
 * The collaborators an [EditorSession] delegates its tool state, its request buses, and its notices to,
 * built as one unit before the session exists.
 *
 * A class rather than three constructor parameters because a supertype delegate (`by`) may only name a
 * constructor parameter, and a secondary constructor's `this(...)` call cannot share a value between two of
 * its arguments - yet the latches need the notice channel to post through.  Holding the three here lets the
 * public constructor hand the private one a single ready-made bundle.
 */
internal class SessionCollaborators {
	/** The notice channel, declared first: the tool settings below post through it. */
	val notices = SessionNotices()

	/** The transient tool latches. */
	val latches = ToolLatches()

	/** The saved tool settings: the cursors, the pivot mode, the grid, and proportional editing. */
	val settings = ToolSettings(notify = notices::emit)

	/** The area-request buses. */
	val requestBus = SessionRequestBus()
}