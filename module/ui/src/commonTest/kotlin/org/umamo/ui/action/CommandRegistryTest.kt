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
}
