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
 * and of one group in it - and an unavailable command neither runs nor consumes its trigger.
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
	 * every other table's and the ones in no group at all.
	 */
	@Test
	fun listsAGroupInRegistrationOrder() {
		val registry = CommandRegistry()
		registry.register(Command("select.first", title = null, group = CommandTable.Select, handler = {}))
		registry.register(Command("overlay.only", title = null, group = CommandTable.Overlay, handler = {}))
		registry.register(Command("select.member", title = null, group = CommandFamily.SelectAllOfKind, groupLabel = Res.string.menu_select_kind_parts, handler = {}))
		registry.register(Command("ungrouped", title = null, handler = {}))
		registry.register(Command("select.last", title = null, group = CommandTable.Select, handler = {}))

		assertEquals(listOf("select.first", "select.member", "select.last"), registry.inGroup(CommandTable.Select).map { command -> command.id })
		assertEquals(listOf("select.member"), registry.inGroup(CommandFamily.SelectAllOfKind).map { command -> command.id })
		assertEquals(emptyList(), registry.inGroup(CommandFamily.InvertSelectionOfKind), "a group nothing is registered in lists nothing")

		registry.unregister("select.member")

		assertEquals(listOf("select.first", "select.last"), registry.inGroup(CommandTable.Select).map { command -> command.id })
		assertEquals(emptyList(), registry.inGroup(CommandFamily.SelectAllOfKind))
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
		assertEquals(emptyList(), listed.value)

		registry.register(Command("select.first", title = null, group = CommandTable.Select, handler = {}))
		assertEquals(listOf("select.first"), listed.value, "a registration reaches a listing made before it")

		registry.unregister("select.first")
		assertEquals(emptyList(), listed.value, "and so does an unregistration")
	}

	/**
	 * A group's label needs a group, and a family's member needs a label: a family is a list of rows, where a
	 * table's own commands may go unlabeled.
	 */
	@Test
	fun aFamilyMemberCarriesARowLabel() {
		assertFailsWith<IllegalArgumentException> { Command("test.label", title = null, groupLabel = Res.string.menu_select_kind_parts, handler = {}) }
		assertFailsWith<IllegalArgumentException> { Command("test.row", title = null, group = CommandFamily.SelectAllOfKind, handler = {}) }

		val unlabeled = Command("test.table", title = null, group = CommandTable.Select, handler = {})
		assertNull(unlabeled.rowLabel)

		val titled = Command("test.titled", title = Res.string.cmd_select_all_parts, group = CommandFamily.SelectAllOfKind, handler = {})
		assertEquals(Res.string.cmd_select_all_parts, titled.rowLabel, "with no label of its own the row shows the title")

		val labeled = Command("test.labeled", title = Res.string.cmd_select_all_parts, group = CommandFamily.SelectAllOfKind, groupLabel = Res.string.menu_select_kind_parts, handler = {})
		assertEquals(Res.string.menu_select_kind_parts, labeled.rowLabel)
	}

	/** A group is within itself and everything it sits inside, and its table is where that chain ends. */
	@Test
	fun aGroupIsWithinItsAncestors() {
		assertTrue(CommandFamily.SelectAllOfKind.isWithin(CommandFamily.SelectAllOfKind))
		assertTrue(CommandFamily.SelectAllOfKind.isWithin(CommandTable.Select))
		assertFalse(CommandFamily.SelectAllOfKind.isWithin(CommandTable.Overlay))
		assertFalse(CommandFamily.SelectAllOfKind.isWithin(CommandFamily.InvertSelectionOfKind))
		assertFalse(CommandTable.Select.isWithin(CommandFamily.SelectAllOfKind), "a table is in none of its families")
		assertEquals(CommandTable.Select, CommandFamily.SelectAllOfKind.table)
		assertEquals(CommandTable.Select, CommandTable.Select.table)
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