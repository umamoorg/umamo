package org.umamo.ui.workspace.commands

import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.CommandRegistry

/*
 * The machinery every command table in this package shares: how a group gets registered, and the
 * availability tiers the document-scoped groups all query.
 *
 * A table is a plain List<Command> built by a function that closes over the state its handlers need.
 * The shell registers each through registerAll inside a DisposableEffect keyed on exactly that state,
 * so a document swap or a renderer change re-registers the groups whose handlers went stale and leaves
 * the rest alone.
 */

/**
 * Registers every command in [commands] and returns the matching cleanup, so a DisposableEffect's
 * onDispose can never fall out of sync with what was registered.
 *
 * The hand-mirrored unregister list this replaced rotted the moment a command was added to one list and
 * not the other, which fails silently: the command simply outlives its handler's state.
 *
 * @param List<Command> commands The commands to register.
 * @return Function The cleanup unregistering exactly those commands.
 * @warning Registration is insertion-ordered and re-registering MOVES a group to the tail, which the
 *   command palette shows verbatim for a blank query.  Harmless today because no group's effect key
 *   flips while a document stays open; adding an independently-flipping key to one of those effects
 *   would make the palette reorder itself at runtime.
 */
internal fun CommandRegistry.registerAll(commands: List<Command>): () -> Unit {
	commands.forEach { command -> register(command) }
	return { commands.forEach { command -> unregister(command.id) } }
}

/**
 * The availability tiers the document-scoped command groups share.
 *
 * One instance per registration pass, so the eight session groups hold the same three
 * [CommandAvailability] objects rather than eight copies apiece.  Each lambda reads live session state
 * at query time, so the palette's filter and the keymap's dispatch guard always see the current
 * context - the tiers are computed fresh per query, never sampled at registration.
 *
 * @param EditorSession? session The open document's session, or null when none is open.
 */
internal class SessionAvailability(session: EditorSession?) {
	/** Applies whenever a document is open - the tier most session commands sit in. */
	val hasDocument = CommandAvailability { session != null }

	/** Applies only in Edit mode, where there is a mesh-element domain to act on. */
	val inEditMode = CommandAvailability { session?.mode?.value == EditorMode.Edit }

	/** Applies only while a Circle-select brush is live, since the radius steps have nothing to resize otherwise. */
	val circleToolLive = CommandAvailability { session?.activeSelectTool?.value is ActiveSelectTool.Circle }
}
