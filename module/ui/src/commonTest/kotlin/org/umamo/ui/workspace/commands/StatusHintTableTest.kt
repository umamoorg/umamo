package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.status_bind_edit_mode
import org.umamo.ui.resources.status_bind_grab
import org.umamo.ui.resources.status_bind_rotate
import org.umamo.ui.resources.status_bind_scale
import org.umamo.ui.resources.status_bind_visibility
import org.umamo.ui.resources.status_select_mode
import org.umamo.ui.workspace.STATUS_HINT_LIMIT
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.everyStatusHintFor
import org.umamo.ui.workspace.statusHintsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins which commands the status bar may suggest, and in which document states.
 *
 * The status bar recomputes its suggestions when the registered table (and so the document), the editor
 * mode, the hovered space, or the keymap changes - and on nothing else, because a command's availability
 * is a live query the composition cannot observe.  So a hinted command's availability, and its hint's
 * suggestedWhen, may depend on the document and the mode ALONE.  One that also read the selection, an
 * armed tool, or the atlas would leave its suggestion standing (or missing) until the pointer next
 * crossed into another space.
 *
 * What a predicate reads is nothing a test can derive, so the hinted table is pinned whole instead.  A
 * command that gains a hint must change this map, which is the moment to check that both of its
 * predicates answer from the document and the mode and nothing more.
 */
class StatusHintTableTest {
	/**
	 * When a hinted command is suggested, across the three states the status bar recomputes on.
	 *
	 * @property Boolean noDocument Whether it is suggested with no document open.
	 * @property Boolean objectMode Whether it is suggested in Object mode.
	 * @property Boolean editMode Whether it is suggested in Edit mode.
	 */
	private data class SuggestedIn(val noDocument: Boolean, val objectMode: Boolean, val editMode: Boolean)

	/**
	 * Whether each hinted command is suggested over [session], by id: available, and its hint worth
	 * suggesting.  The space is left out on purpose - CommandSpacesTableTest pins that dimension.
	 *
	 * @param EditorSession? session The session the tables close over, or null for no document.
	 * @return Map<String, Boolean> Every hinted command's id with whether it is suggested.
	 */
	private fun suggestedById(session: EditorSession?): Map<String, Boolean> =
		everyCommandTable(session)
			.mapNotNull { command ->
				command.hint?.let { hint -> command.id to (command.availability.isAvailable() && hint.suggestedWhen.isAvailable()) }
			}.toMap()

	/** The hinted commands, each with the states it is suggested in; every other command carries no hint. */
	@Test
	fun theHintedCommandsAreExactlyThese() {
		val inObjectModeOnly = SuggestedIn(noDocument = false, objectMode = true, editMode = false)
		val inEditModeOnly = SuggestedIn(noDocument = false, objectMode = false, editMode = true)
		val withADocument = SuggestedIn(noDocument = false, objectMode = true, editMode = true)
		val expected =
			mapOf(
				"mode.toggleEdit" to inObjectModeOnly,
				"object.toggleVisibility" to inObjectModeOnly,
				"mesh.grab" to withADocument,
				"mesh.scale" to withADocument,
				"mesh.rotate" to withADocument,
				"mesh.selectMode.vertex" to inEditModeOnly,
				"mesh.selectMode.edge" to inEditModeOnly,
				"mesh.selectMode.face" to inEditModeOnly,
				"uv.pinPlacement" to inObjectModeOnly,
				"keyform.insert" to withADocument,
				"keyform.delete" to withADocument,
			)
		val noDocument = suggestedById(null)
		val objectMode = suggestedById(commandFixtureSession(EditorMode.Object))
		val editMode = suggestedById(commandFixtureSession(EditorMode.Edit))
		val actual =
			noDocument.keys.associateWith { id ->
				SuggestedIn(noDocument = noDocument.getValue(id), objectMode = objectMode.getValue(id), editMode = editMode.getValue(id))
			}

		assertEquals(expected, actual)
	}

	/**
	 * Over a viewport under the default keymap, each mode reads as its own short list: the way into Edit
	 * mode and the visibility toggle from Object mode, the element domains from Edit mode.
	 */
	@Test
	fun eachModeSuggestsItsOwnListOverAViewport() {
		val transforms = listOf(Res.string.status_bind_grab, Res.string.status_bind_scale, Res.string.status_bind_rotate)
		val objectHints = statusHintsFor(everyCommandTable(commandFixtureSession(EditorMode.Object)), defaultKeymap(), SpaceKind.Viewport2D)
		val editHints = statusHintsFor(everyCommandTable(commandFixtureSession(EditorMode.Edit)), defaultKeymap(), SpaceKind.Viewport2D)

		assertEquals(transforms + Res.string.status_bind_edit_mode + Res.string.status_bind_visibility, objectHints.map { hint -> hint.label })
		assertEquals(transforms + Res.string.status_select_mode, editHints.map { hint -> hint.label })
	}

	/**
	 * Under the default keymap no space, in either mode, has more to suggest than the strip shows.  The
	 * cut drops the trailing suggestions without a trace, so a hint that pushes a list past the limit
	 * fails here by name instead of quietly taking another off the bar.
	 */
	@Test
	fun noSuggestionIsCutUnderTheDefaultKeymap() {
		for (mode in EditorMode.entries) {
			val commands = everyCommandTable(commandFixtureSession(mode))
			for (kind in SpaceKind.entries) {
				val uncut = everyStatusHintFor(commands, defaultKeymap(), kind)
				assertTrue(uncut.size <= STATUS_HINT_LIMIT, "$kind in $mode mode has ${uncut.size} suggestions, and the strip shows $STATUS_HINT_LIMIT")
			}
		}
	}
}