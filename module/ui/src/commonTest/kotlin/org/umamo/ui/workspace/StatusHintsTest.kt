package org.umamo.ui.workspace

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.CommandHint
import org.umamo.ui.action.CommandSpaces
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.parseKeyChord
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.status_bind_delete_key
import org.umamo.ui.resources.status_bind_edit_mode
import org.umamo.ui.resources.status_bind_grab
import org.umamo.ui.resources.status_bind_insert_key
import org.umamo.ui.resources.status_bind_pin
import org.umamo.ui.resources.status_bind_scale
import org.umamo.ui.resources.status_bind_visibility
import org.umamo.ui.resources.status_select_mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how the status bar's suggestions are derived from the command declarations: which commands are
 * suggested over which space, in what order, and how commands sharing a label become one entry.
 */
class StatusHintsTest {
	/**
	 * A hinted command that does nothing.
	 *
	 * @param String id The command id.
	 * @param StringResource? hint The status label, or null for a command that is never suggested.
	 * @param CommandSpaces spaces The spaces it belongs to.
	 * @param Boolean available Whether it currently applies.
	 * @param Boolean suggested Whether its hint is worth suggesting right now, on top of being available.
	 * @return Command The command.
	 */
	private fun command(
		id: String,
		hint: StringResource?,
		spaces: CommandSpaces = CommandSpaces.Everywhere,
		available: Boolean = true,
		suggested: Boolean = true,
	): Command =
		Command(
			id,
			title = null,
			availability = CommandAvailability { available },
			spaces = spaces,
			hint = hint?.let { label -> CommandHint(label, suggestedWhen = CommandAvailability { suggested }) },
			handler = {},
		)

	/**
	 * A keymap over chord-spec to command-id pairs.
	 *
	 * @param Pair<String, String> bindings Each chord spec ("KeyG") with the command id it reaches.
	 * @return Keymap The keymap holding exactly those bindings.
	 */
	private fun keymap(vararg bindings: Pair<String, String>): Keymap = Keymap.fromSpecs(bindings.toMap())

	/**
	 * The labels of these suggestions, in order - what most cases below compare.
	 *
	 * @return List<StringResource> The suggestion labels.
	 */
	private fun List<StatusHint>.labels(): List<StringResource> = map { hint -> hint.label }

	/** Only a command carrying a hint is ever suggested. */
	@Test
	fun aCommandWithoutAHintIsNeverSuggested() {
		val commands = listOf(command("test.plain", hint = null), command("test.hinted", hint = Res.string.status_bind_grab))
		val hints = statusHintsFor(commands, keymap("KeyA" to "test.plain", "KeyG" to "test.hinted"), SpaceKind.Viewport2D)

		assertEquals(listOf(Res.string.status_bind_grab), hints.labels())
	}

	/** A scoped command is suggested inside its spaces and nowhere else - the point of deriving from the hovered space. */
	@Test
	fun aScopedCommandIsSuggestedOnlyInItsSpaces() {
		val commands = listOf(command("test.pin", hint = Res.string.status_bind_pin, spaces = CommandSpaces.UvEditor))
		val bindings = keymap("KeyP" to "test.pin")

		assertEquals(listOf(Res.string.status_bind_pin), statusHintsFor(commands, bindings, SpaceKind.UvEditor).labels())
		assertTrue(statusHintsFor(commands, bindings, SpaceKind.Viewport2D).isEmpty())
		assertTrue(statusHintsFor(commands, bindings, null).isEmpty(), "and not before the pointer has touched a surface")
	}

	/** An unavailable command is not suggested, whatever space it belongs to. */
	@Test
	fun anUnavailableCommandIsNotSuggested() {
		val commands = listOf(command("test.grab", hint = Res.string.status_bind_grab, available = false))

		assertTrue(statusHintsFor(commands, keymap("KeyG" to "test.grab"), SpaceKind.Viewport2D).isEmpty())
	}

	/**
	 * An available command whose hint is not worth suggesting right now stays off the bar - how Tab is
	 * bound and runs in Edit mode while the bar suggests it only from Object mode.
	 */
	@Test
	fun anAvailableCommandWhoseHintIsNotSuggestedStaysOffTheBar() {
		val commands = listOf(command("test.editMode", hint = Res.string.status_bind_edit_mode, suggested = false))

		assertTrue(statusHintsFor(commands, keymap("Tab" to "test.editMode"), SpaceKind.Viewport2D).isEmpty())
	}

	/** An unbound command is omitted, so the strip never advertises a key that does nothing. */
	@Test
	fun anUnboundCommandIsNotSuggested() {
		val commands = listOf(command("test.grab", hint = Res.string.status_bind_grab))

		assertTrue(statusHintsFor(commands, keymap(), SpaceKind.Viewport2D).isEmpty())
	}

	/**
	 * What is specific to the hovered space leads, then what belongs everywhere - each tier in
	 * registration order, even when the global command was registered first.
	 */
	@Test
	fun scopedSuggestionsLeadAndEachTierKeepsRegistrationOrder() {
		val commands =
			listOf(
				command("test.editMode", hint = Res.string.status_bind_edit_mode),
				command("test.grab", hint = Res.string.status_bind_grab, spaces = CommandSpaces.WorkSurfaces),
				command("test.visibility", hint = Res.string.status_bind_visibility),
				command("test.scale", hint = Res.string.status_bind_scale, spaces = CommandSpaces.WorkSurfaces),
			)
		val bindings = keymap("Tab" to "test.editMode", "KeyG" to "test.grab", "KeyH" to "test.visibility", "KeyS" to "test.scale")

		assertEquals(
			listOf(Res.string.status_bind_grab, Res.string.status_bind_scale, Res.string.status_bind_edit_mode, Res.string.status_bind_visibility),
			statusHintsFor(commands, bindings, SpaceKind.Viewport2D).labels(),
		)
	}

	/** Commands sharing one label are one entry carrying every chord, in registration order ("1/2/3 Select Mode"). */
	@Test
	fun commandsSharingALabelMergeIntoOneEntry() {
		val commands =
			listOf(
				command("test.vertex", hint = Res.string.status_select_mode),
				command("test.edge", hint = Res.string.status_select_mode),
				command("test.face", hint = Res.string.status_select_mode),
			)
		val hints = statusHintsFor(commands, keymap("Digit1" to "test.vertex", "Digit2" to "test.edge", "Digit3" to "test.face"), SpaceKind.Viewport2D)

		assertEquals(1, hints.size)
		assertEquals(listOf(parseKeyChord("Digit1"), parseKeyChord("Digit2"), parseKeyChord("Digit3")), hints.single().chords)
	}

	/** A group with any member unbound is dropped whole: "1/3 Select Mode" would misread as the full set. */
	@Test
	fun aPartiallyBoundGroupIsDroppedWhole() {
		val commands =
			listOf(
				command("test.vertex", hint = Res.string.status_select_mode),
				command("test.edge", hint = Res.string.status_select_mode),
				command("test.face", hint = Res.string.status_select_mode),
			)

		assertTrue(statusHintsFor(commands, keymap("Digit1" to "test.vertex", "Digit3" to "test.face"), SpaceKind.Viewport2D).isEmpty())
	}

	/** The chord always comes from the keymap handed in, so a rebind is reflected with no other change. */
	@Test
	fun aRebindIsReflected() {
		val commands = listOf(command("test.grab", hint = Res.string.status_bind_grab))

		assertEquals(listOf(parseKeyChord("KeyG")), statusHintsFor(commands, keymap("KeyG" to "test.grab"), SpaceKind.Viewport2D).single().chords)
		assertEquals(listOf(parseKeyChord("KeyM")), statusHintsFor(commands, keymap("KeyM" to "test.grab"), SpaceKind.Viewport2D).single().chords)
	}

	/** The list is cut at the limit, keeping the leading entries - the space-specific ones. */
	@Test
	fun theListIsCutAtTheLimitKeepingTheLeadingEntries() {
		val labels =
			listOf(
				Res.string.status_bind_grab,
				Res.string.status_bind_scale,
				Res.string.status_bind_pin,
				Res.string.status_bind_edit_mode,
				Res.string.status_bind_visibility,
				Res.string.status_select_mode,
				Res.string.status_bind_insert_key,
				Res.string.status_bind_delete_key,
			)
		val commands = labels.mapIndexed { labelIndex, label -> command("test.command$labelIndex", hint = label) }
		val bindings = Keymap.fromSpecs(labels.indices.associate { labelIndex -> "F${labelIndex + 1}" to "test.command$labelIndex" })

		val hints = statusHintsFor(commands, bindings, SpaceKind.Viewport2D)

		assertEquals(STATUS_HINT_LIMIT, hints.size)
		assertEquals(labels.take(STATUS_HINT_LIMIT), hints.labels())
	}
}