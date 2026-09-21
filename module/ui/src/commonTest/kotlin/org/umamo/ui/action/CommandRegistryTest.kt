package org.umamo.ui.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the action-registry dispatch contract: registered commands run with the forwarded
 * argument, unknown ids fail quietly (no throw), listing is in registration order, and an
 * unavailable command neither runs nor consumes its trigger.
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
	 * A command's spaces never gate dispatch.  They filter the palette and the status bar; a chord still
	 * reaches the handler wherever the pointer is, and the handler decides what that press means.
	 */
	@Test
	fun spacesNeverGateDispatch() {
		val registry = CommandRegistry()
		var runCount = 0
		registry.register(Command("test.scoped", title = null, spaces = CommandSpaces.UvEditor, handler = { runCount++ }))

		assertTrue(registry.invoke("test.scoped"), "the registry knows nothing about where the pointer is")
		assertEquals(1, runCount)
	}
}