package org.umamo.ui.workspace

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandSpaces
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.Keymap

/**
 * The most suggestions the status bar shows at once.  The strip is one line shared with the transient
 * notice, the selected item, and the model stats, so the list is cut rather than left to crowd them out.
 */
internal const val STATUS_HINT_LIMIT = 6

/**
 * One status bar suggestion: the chords that reach it, and the short label it reads under.
 *
 * @property List<KeyChord> chords The canonical chord of each command suggested under [label], in
 *   registration order - one for a lone command, several for commands sharing a label ("1/2/3").
 * @property StringResource label The short localized label.
 */
internal data class StatusHint(val chords: List<KeyChord>, val label: StringResource)

/**
 * Resolves the shortcuts worth suggesting for the space under the pointer, from the same declarations
 * the command palette filters on.
 *
 * A command is suggested when it carries a [Command.hint], currently applies, and belongs to
 * [hoveredKind].  Commands scoped to particular spaces come first - they are what is specific to where
 * the pointer is - followed by the ones that belong everywhere, each in registration order.
 *
 * Commands sharing one label are suggested as one entry carrying every member's chord.  Such a group is
 * shown only when EVERY member is bound: a partial run ("1/3 Select Mode") would misread as the full set.
 * For a lone command that is simply the rule that an unbound command is never advertised.  Chords come
 * from the live keymap, so a rebind shows here at once.
 *
 * @param List<Command> commands The registered commands, in registration order.
 * @param Keymap keymap The active keymap.
 * @param SpaceKind? hoveredKind The space the pointer last touched, or null before it touched any.
 * @return List<StatusHint> The suggestions to show, at most [STATUS_HINT_LIMIT].
 */
internal fun statusHintsFor(commands: List<Command>, keymap: Keymap, hoveredKind: SpaceKind?): List<StatusHint> {
	val suggested =
		commands.filter { command ->
			command.hint != null && command.spaces.appliesIn(hoveredKind) && command.availability.isAvailable()
		}
	// A stable sort, so each tier keeps registration order.
	val tiered = suggested.sortedBy { command -> if (command.spaces is CommandSpaces.Everywhere) 1 else 0 }
	// Grouped on the label, which a LinkedHashMap keeps in first-occurrence order: a group sits where its
	// first member does.
	val membersByLabel = LinkedHashMap<StringResource, MutableList<Command>>()
	for (command in tiered) {
		val label = command.hint ?: continue
		membersByLabel.getOrPut(label) { mutableListOf() }.add(command)
	}
	val hints = mutableListOf<StatusHint>()
	for ((label, members) in membersByLabel) {
		val chords = members.mapNotNull { member -> keymap.chordFor(member.id) }
		if (chords.size == members.size) {
			hints.add(StatusHint(chords, label))
		}
	}
	return hints.take(STATUS_HINT_LIMIT)
}