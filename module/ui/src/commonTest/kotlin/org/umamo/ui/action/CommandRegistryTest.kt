package org.umamo.ui.action

import androidx.compose.runtime.derivedStateOf
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_select_all_parts
import org.umamo.ui.resources.menu_select_kind_parts
import org.umamo.ui.workspace.SpaceScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the action-registry dispatch contract: registered commands run with the forwarded
 * argument, unknown ids fail quietly (no throw), listing is in registration order - of the whole table
 * and of one group in it, a family going section by section - and an unavailable command neither runs nor
 * consumes its trigger.
 */
class CommandRegistryTest {
	/**
	 * Invoking a registered command runs its handler and forwards the argument.
	 */
	@Test
	fun invokesRegisteredCommandWithArgument() {
		val registry = CommandRegistry()
		var receivedArgument: Any? = "unset"
		registry.register(Command("test.echo", title = null, handler = { argument -> receivedArgument = argument }))

		val found = registry.invoke("test.echo", "hello")

		assertTrue(found, "a registered command should be found")
		assertEquals("hello", receivedArgument)
	}

	/**
	 * Invoking an unknown id returns false and never throws - a stray key chord must not crash.
	 */
	@Test
	fun unknownCommandReturnsFalse() {
		val registry = CommandRegistry()
		assertFalse(registry.invoke("does.not.exist"))
	}

	/**
	 * Re-registering an id replaces the handler (last write wins).
	 */
	@Test
	fun reRegisteringReplacesHandler() {
		val registry = CommandRegistry()
		var ran = "none"
		registry.register(Command("test.x", title = null, handler = { ran = "first" }))
		registry.register(Command("test.x", title = null, handler = { ran = "second" }))

		registry.invoke("test.x")

		assertEquals("second", ran)
	}

	/**
	 * An unavailable command is not run and invoke() returns false, so the bound chord falls through
	 * unconsumed; the availability lambda is re-read on every invoke, so live context flips apply.
	 */
	@Test
	fun unavailableCommandDoesNotRunAndReadsLiveContext() {
		val registry = CommandRegistry()
		var applicable = false
		var runCount = 0
		registry.register(
			Command("test.gated", title = null, availability = { applicable }, handler = { runCount++ }),
		)

		assertFalse(registry.invoke("test.gated"), "an unavailable command reports false")
		assertEquals(0, runCount, "the handler never ran")

		applicable = true
		assertTrue(registry.invoke("test.gated"), "the same registration applies once the context allows it")
		assertEquals(1, runCount)
	}

	/**
	 * A command gated on its argument runs only with one it applies to, and with none (a chord, the palette) when it
	 * says so; the palette and the status bar never offer one that needs an argument.
	 */
	@Test
	fun anArgumentGatedCommandRunsOnlyWithAnArgumentItAppliesTo() {
		val registry = CommandRegistry()
		val received = ArrayList<Any?>()
		val gated = Command("test.aimed", title = null, availableFor = { argument -> argument == "bound" }, handler = { argument -> received.add(argument) })
		registry.register(gated)

		assertFalse(registry.invoke("test.aimed", "unbound"))
		assertFalse(registry.invoke("test.aimed"), "a chord carries no argument")
		assertTrue(registry.invoke("test.aimed", "bound"))
		assertEquals(listOf<Any?>("bound"), received)
		assertFalse(gated.isOfferedIn(null), "nor is it offered where nothing aims it")
		assertTrue(Command("test.plain", title = null, handler = {}).isAvailable("anything"), "a command without the gate ignores its argument")
	}

	/**
	 * all() lists commands in registration order; get() and unregister() behave.
	 */
	@Test
	fun listsInRegistrationOrderAndUnregisters() {
		val registry = CommandRegistry()
		registry.register(Command("a", title = null, handler = {}))
		registry.register(Command("b", title = null, handler = {}))
		registry.register(Command("c", title = null, handler = {}))

		assertEquals(listOf("a", "b", "c"), registry.all().map { command -> command.id })

		registry.unregister("b")

		assertEquals(listOf("a", "c"), registry.all().map { command -> command.id })
		assertNull(registry["b"])
	}

	/**
	 * The revision moves on every change to the table and on nothing else - it is what a composable
	 * listing all() subscribes to, since the table itself is a plain map.
	 */
	@Test
	fun theRevisionMovesWithTheTable() {
		val registry = CommandRegistry()
		val initial = registry.revision
		var runCount = 0

		registry.register(Command("a", title = null, handler = { runCount++ }))
		val afterRegister = registry.revision
		assertTrue(afterRegister != initial, "registering moves it")

		// Invoked while registered, so the handler really runs: an invoke that found nothing returns before
		// any bookkeeping a run could bump the revision from.
		assertTrue(registry.invoke("a"))
		assertEquals(1, runCount, "the invoke must reach the handler for this to mean anything")
		assertEquals(afterRegister, registry.revision, "running a command does not change the table")

		registry.unregister("a")
		val afterUnregister = registry.revision
		assertTrue(afterUnregister != afterRegister, "unregistering moves it")

		registry.unregister("a")
		assertEquals(afterUnregister, registry.revision, "unregistering an id that is not there changes nothing")

		registry.invoke("a")
		assertEquals(afterUnregister, registry.revision, "and neither does invoking one")
	}

	/**
	 * inGroup() lists a table's commands in registration order - a family's members among them - and leaves out
	 * every other table's and the ones in no table at all.
	 */
	@Test
	fun listsAGroupInRegistrationOrder() {
		val registry = CommandRegistry()
		registry.register(Command("select.first", title = null, table = CommandTable.Select, handler = {}))
		registry.register(Command("overlay.only", title = null, table = CommandTable.Overlay, handler = {}))
		registry.register(Command("select.member", title = null, table = CommandTable.Select, group = CommandFamily.SelectAllOfKind, groupLabel = Res.string.menu_select_kind_parts, handler = {}))
		registry.register(Command("ungrouped", title = null, handler = {}))
		registry.register(Command("select.last", title = null, table = CommandTable.Select, handler = {}))

		assertEquals(listOf("select.first", "select.member", "select.last"), registry.inGroup(CommandTable.Select).map { command -> command.id })
		assertEquals(listOf("select.member"), registry.inGroup(CommandFamily.SelectAllOfKind).map { command -> command.id })
		assertEquals(emptyList(), registry.inGroup(CommandFamily.InvertSelectionOfKind), "a group nothing is registered in lists nothing")

		registry.unregister("select.member")

		assertEquals(listOf("select.first", "select.last"), registry.inGroup(CommandTable.Select).map { command -> command.id })
		assertEquals(emptyList(), registry.inGroup(CommandFamily.SelectAllOfKind))
	}

	/** A family is listed from whichever tables hold its members: the table a command is in says nothing of where it is shown. */
	@Test
	fun aFamilyDrawsItsMembersFromEveryTable() {
		val registry = CommandRegistry()
		registry.register(Command("select.member", title = null, table = CommandTable.Select, group = CommandFamily.MergeTarget, groupLabel = Res.string.menu_select_kind_parts, handler = {}))
		registry.register(Command("overlay.member", title = null, table = CommandTable.Overlay, group = CommandFamily.MergeTarget, groupLabel = Res.string.menu_select_kind_parts, handler = {}))

		assertEquals(listOf("select.member", "overlay.member"), registry.inGroup(CommandFamily.MergeTarget).map { command -> command.id })
		assertEquals(listOf("overlay.member"), registry.inGroup(CommandTable.Overlay).map { command -> command.id }, "and each is still its own table's")
	}

	/**
	 * A family lists its sections in the order they are declared, whichever was registered first, and each section in
	 * registration order.  Its tables register at different times - and again, at the tail, on a document swap - so
	 * a family in plain registration order would rearrange itself.
	 */
	@Test
	fun aFamilyListsItsSectionsInDeclaredOrder() {
		val registry = CommandRegistry()
		val registered =
			listOf(
				"selection.first" to CommandSection.SnapSelection,
				"cursor.first" to CommandSection.SnapCursor,
				"selection.second" to CommandSection.SnapSelection,
				"cursor.second" to CommandSection.SnapCursor,
			)

		for ((id, section) in registered) {
			registry.register(Command(id, title = null, group = section, groupLabel = Res.string.menu_select_kind_parts, handler = {}))
		}

		assertEquals(listOf("cursor.first", "cursor.second", "selection.first", "selection.second"), registry.inGroup(CommandFamily.Snap).map { command -> command.id })
		assertEquals(listOf("selection.first", "selection.second"), registry.inGroup(CommandSection.SnapSelection).map { command -> command.id }, "a section keeps registration order")
	}

	/**
	 * A family that sits inside a section is a submenu in that block: the outer family lists its members where the
	 * section stands, the section holds them, and they are not the section's own rows.
	 */
	@Test
	fun aFamilyInsideASectionIsListedWithThatSection() {
		val registry = CommandRegistry()
		registry.register(Command("kind.parts", title = null, group = CommandFamily.SelectAllOfKind, groupLabel = Res.string.menu_select_kind_parts, handler = {}))
		registry.register(Command("select.all", title = Res.string.cmd_select_all_parts, group = CommandSection.SelectEverything, handler = {}))
		registry.register(Command("kind.inverted", title = null, group = CommandFamily.InvertSelectionOfKind, groupLabel = Res.string.menu_select_kind_parts, handler = {}))

		assertEquals(listOf("select.all", "kind.parts", "kind.inverted"), registry.inGroup(CommandFamily.Select).map { command -> command.id }, "the first block, then the submenus' block")
		assertEquals(listOf("kind.parts", "kind.inverted"), registry.inGroup(CommandSection.SelectByKind).map { command -> command.id })
		assertEquals(emptyList(), registry.membersOf(CommandSection.SelectByKind), "a submenu's rows are its own, not the block's")
		assertEquals(listOf("select.all"), registry.membersOf(CommandSection.SelectEverything).map { command -> command.id })
		assertEquals(listOf("kind.parts"), registry.membersOf(CommandFamily.SelectAllOfKind).map { command -> command.id })
		assertEquals(emptyList(), registry.membersOf(CommandFamily.Select), "a sectioned family has no row outside its sections")
	}

	/**
	 * Listing a group subscribes the reader to the table, with no revision key of its own: the menu bar lists a
	 * family before the shell's effects have registered it, and must come back when they do.  A derived state
	 * re-evaluates only when something it read changed, so it stands in for a composition here.
	 */
	@Test
	fun listingAGroupSubscribesToTheTable() {
		val registry = CommandRegistry()
		val listed = derivedStateOf { registry.inGroup(CommandTable.Select).map { command -> command.id } }
		val members = derivedStateOf { registry.membersOf(CommandSection.SelectEverything).map { command -> command.id } }
		assertEquals(emptyList(), listed.value)
		assertEquals(emptyList(), members.value)

		registry.register(Command("select.first", title = Res.string.cmd_select_all_parts, table = CommandTable.Select, group = CommandSection.SelectEverything, handler = {}))
		assertEquals(listOf("select.first"), listed.value, "a registration reaches a listing made before it")
		assertEquals(listOf("select.first"), members.value, "and a listing of a group's own rows")

		registry.unregister("select.first")
		assertEquals(emptyList(), listed.value, "and so does an unregistration")
		assertEquals(emptyList(), members.value)
	}

	/**
	 * A group's label needs a group, and a family's member needs a label: a family is a list of rows, where a
	 * command no family shows may go unlabeled.
	 */
	@Test
	fun aFamilyMemberCarriesARowLabel() {
		assertFailsWith<IllegalArgumentException> { Command("test.label", title = null, groupLabel = Res.string.menu_select_kind_parts, handler = {}) }
		assertFailsWith<IllegalArgumentException> { Command("test.row", title = null, group = CommandFamily.SelectAllOfKind, handler = {}) }

		val unlabeled = Command("test.table", title = null, table = CommandTable.Select, handler = {})
		assertNull(unlabeled.rowLabel)

		val titled = Command("test.titled", title = Res.string.cmd_select_all_parts, group = CommandFamily.SelectAllOfKind, handler = {})
		assertEquals(Res.string.cmd_select_all_parts, titled.rowLabel, "with no label of its own the row shows the title")

		val labeled = Command("test.labeled", title = Res.string.cmd_select_all_parts, group = CommandFamily.SelectAllOfKind, groupLabel = Res.string.menu_select_kind_parts, handler = {})
		assertEquals(Res.string.menu_select_kind_parts, labeled.rowLabel)
	}

	/**
	 * A command is in effect only while its state says so, read fresh at each query, and a command with no state is
	 * never the current one.  Like spaces and availability's palette half, the state never gates dispatch.
	 */
	@Test
	fun aCommandIsInEffectOnlyWhileItsStateSaysSo() {
		val registry = CommandRegistry()
		var current = false
		var runCount = 0
		val stateful = Command("test.state", title = null, state = CommandState { current }, handler = { runCount++ })
		registry.register(stateful)

		assertFalse(stateful.isOn())
		assertTrue(registry.invoke("test.state"), "a command that is not the current one still runs")
		assertEquals(1, runCount)

		current = true
		assertTrue(stateful.isOn(), "the state is read live")
		assertFalse(Command("test.stateless", title = null, handler = {}).isOn())
	}

	/**
	 * A listing is within itself and everything it sits inside: a section in its family, a submenu in the section it
	 * sits in and so in the menu above.  A table stands apart from all of them.
	 */
	@Test
	fun aGroupIsWithinItsAncestors() {
		assertTrue(CommandFamily.SelectAllOfKind.isWithin(CommandFamily.SelectAllOfKind))
		assertFalse(CommandFamily.SelectAllOfKind.isWithin(CommandFamily.InvertSelectionOfKind))
		assertTrue(CommandFamily.SelectAllOfKind.isWithin(CommandSection.SelectByKind), "a submenu is in the block it sits in")
		assertTrue(CommandFamily.SelectAllOfKind.isWithin(CommandFamily.Select), "and in the menu that block is a slice of")
		assertFalse(CommandFamily.SelectAllOfKind.isWithin(CommandSection.SelectEverything))
		assertFalse(CommandFamily.Select.isWithin(CommandFamily.SelectAllOfKind), "a menu is not inside its own submenu")
		assertNull(CommandFamily.Select.parent, "a root sits inside nothing")

		assertTrue(CommandSection.UvSnapCursor.isWithin(CommandFamily.UvSnap), "a section is in its family")
		assertFalse(CommandSection.UvSnapCursor.isWithin(CommandSection.UvSnapSelection))
		assertFalse(CommandSection.UvSnapCursor.isWithin(CommandFamily.Snap))

		assertFalse(CommandFamily.SelectAllOfKind.isWithin(CommandTable.Select), "a family is in no table: its members are")
		assertFalse(CommandTable.Select.isWithin(CommandFamily.Select))
		assertTrue(CommandTable.Select.isWithin(CommandTable.Select))
	}

	/** The block of a family a listing is shown in is the family's own section, however far down the listing sits. */
	@Test
	fun aListingKnowsItsSectionOfAFamily() {
		assertEquals(CommandSection.SelectByKind, CommandFamily.SelectAllOfKind.sectionIn(CommandFamily.Select))
		assertEquals(CommandSection.SelectEverything, CommandSection.SelectEverything.sectionIn(CommandFamily.Select))
		assertNull(CommandFamily.Select.sectionIn(CommandFamily.Select), "a family is in none of its own sections")
		assertNull(CommandSection.SnapCursor.sectionIn(CommandFamily.Select), "nor is a section of another family")
	}

	/**
	 * A command's spaces never gate dispatch.  They filter the palette and the status bar; a chord still
	 * reaches the handler wherever the pointer is, and the handler decides what that press means.
	 */
	@Test
	fun spacesNeverGateDispatch() {
		val registry = CommandRegistry()
		var runCount = 0
		registry.register(Command("test.scoped", title = null, spaces = SpaceScope.UvEditor, handler = { runCount++ }))

		assertTrue(registry.invoke("test.scoped"), "the registry knows nothing about where the pointer is")
		assertEquals(1, runCount)
	}
}