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
	/** The notice channel, declared first: the latches below post through it. */
	val notices = SessionNotices()

	/** The transient tool state and the saved tool settings. */
	val latches = ToolLatches(notify = notices::emit)

	/** The area-request buses. */
	val requestBus = SessionRequestBus()
}