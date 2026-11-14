package org.umamo.ui.workspace.commands

import org.jetbrains.compose.resources.StringResource
import org.umamo.edit.PieMenuKind
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.kit.menu.PieMenuEntry

/**
 * The command family one pie kind draws: its members are the pie's choices and its heading is the pie's title.
 *
 * @param PieMenuKind kind The pie.
 * @return CommandFamily The pie's family.
 */
fun pieFamilyOf(kind: PieMenuKind): CommandFamily =
	when (kind) {
		PieMenuKind.PivotMode -> CommandFamily.PivotMode
		PieMenuKind.Snap -> CommandFamily.Snap
		PieMenuKind.UvSnap -> CommandFamily.UvSnap
		PieMenuKind.MergeTarget -> CommandFamily.MergeTarget
		PieMenuKind.InsertKey -> CommandFamily.InsertKey
	}

/**
 * The entry ring for one pie kind: its family's registered members in the family's order, which the ring lays out in
 * Blender slot order (W, E, S, N, then the diagonals), each enabled exactly when its command is available.  Shared by
 * the shell-level pie host's rendering (ShellCursorOverlays.kt) and the shell's 1..N digit shortcuts, so the picked
 * ordinal always matches the drawn number; the 2D viewport's context menu lists the same family, so a grayed chip is
 * a grayed row.  A member with no icon renders the placeholder square.
 *
 * @param PieMenuKind kind The open pie.
 * @param CommandRegistry commands The registry the family is listed from and the entries dispatch through.
 * @return List<PieMenuEntry> The pie's entries; none while its family is not registered.
 */
fun pieMenuEntriesFor(
	kind: PieMenuKind,
	commands: CommandRegistry,
): List<PieMenuEntry> =
	commands.inGroup(pieFamilyOf(kind)).mapNotNull { member ->
		member.rowLabel?.let { label -> PieMenuEntry(member.id, label, enabled = member.availability.isAvailable(), icon = member.icon) }
	}

/**
 * The center title for one pie kind (Blender names its pies the same way).
 *
 * @param PieMenuKind kind The open pie.
 * @return StringResource The localized title.
 */
fun pieMenuTitleFor(kind: PieMenuKind): StringResource = pieFamilyOf(kind).heading