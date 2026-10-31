package org.umamo.ui.workspace.commands

import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.editsElements
import org.umamo.edit.editsKeys
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
 * @warning Registration is insertion-ordered and re-registering MOVES a group to the tail.  Two things
 *   read that order verbatim: the command palette, for a blank query, and the status bar, which lists
 *   each tier's hints in it and cuts the list at STATUS_HINT_LIMIT - so the order also decides WHICH
 *   hint falls off.  Harmless today because no group's effect key flips while a document stays open;
 *   adding an independently-flipping key to one of those effects would make the palette reorder itself,
 *   and the status bar swap its last suggestion, at runtime.
 */
internal fun CommandRegistry.registerAll(commands: List<Command>): () -> Unit {
	commands.forEach { command -> register(command) }

	return { commands.forEach { command -> unregister(command.id) } }
}

/**
 * The availability tiers the document-scoped command groups share.
 *
 * One instance per registration pass, so the session groups hold the same [CommandAvailability] objects
 * rather than copies apiece.  Each lambda reads live session state at query time, so the palette's filter
 * and the keymap's dispatch guard always see the current context - the tiers are computed fresh per query,
 * never sampled at registration.
 *
 * @param EditorSession? session The open document's session, or null when none is open.
 */
internal class SessionAvailability(session: EditorSession?) {
	/** Applies whenever a document is open - the tier most session commands sit in. */
	val hasDocument = CommandAvailability { session != null }

	/** Applies only in Edit mode, where the rest geometry - topology, UVs - is the domain. */
	val inEditMode = CommandAvailability { session?.mode?.value == EditorMode.Edit }

	/**
	 * Applies in the modes that edit elements (Edit and Pose), where the session's vertices or lattice points are the
	 * domain.
	 */
	val inElementMode = CommandAvailability { session?.mode?.value?.editsElements == true }

	/** Applies only in Object mode, where whole objects - and the art placements under drawables - are the domain. */
	val inObjectMode = CommandAvailability { session?.mode?.value == EditorMode.Object }

	/** Applies outside Edit mode, which edits the rest state alone: every command that inserts, removes, or moves a key. */
	val canEditKeys = CommandAvailability { session?.mode?.value?.editsKeys == true }

	/** Applies only while a Circle-select brush is live, since the radius steps have nothing to resize otherwise. */
	val circleToolLive = CommandAvailability { session?.activeSelectTool?.value is ActiveSelectTool.Circle }

	/**
	 * Applies when the document's atlas is repackable: it has tiles to pack, and its stored
	 * coordinates address the pages (a fully layer-addressed document has no page mapping to
	 * re-derive, and flipping one to page addressing is a bigger change than a repack).  A MOC3-origin
	 * document has no tiles at all, so this also keeps the command off the packed endpoint.
	 */
	val canRepackAtlas =
		CommandAvailability {
			val atlas = session?.model?.value?.atlas
			atlas != null && atlas.tiles.isNotEmpty() && atlas.storedUvsAddressPages
		}

	/**
	 * Applies when the document has art tiles to mesh from, outside Pose mode.  A MOC3-origin document has none
	 * (its drawables sample packed pages, where cutting one drawable's art out would pick up its neighbours'),
	 * so the command stays off there.  A generated mesh is a rest edit of drawables, which Pose mode - acting
	 * on deformers at a pose - never makes.
	 */
	val canGenerateMeshFromArt =
		CommandAvailability {
			session != null && session.mode.value != EditorMode.Pose && session.model.value.atlas.tiles.isNotEmpty()
		}

	/** Applies while an operation may still be adjusted - the strip's F9 has something to open. */
	val hasAdjustableOperation = CommandAvailability { session?.adjustableOperation?.value != null }
}