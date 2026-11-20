package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins Toggle Repeat: it runs on the parameter it is aimed at, under the same runtime-target gate as the
 * checkbox, and grays everywhere it has nothing to aim at.
 */
class ParameterCommandsTest {
	private val drip = ParameterId("ParamDrip")
	private val angle = ParameterId("ParamAngleX")

	/**
	 * A rig with a repeating drip and a normal angle, under [target].
	 *
	 * @param RuntimeTarget target The runtime the rig targets.
	 * @return PuppetModel The rig.
	 */
	private fun rig(target: RuntimeTarget): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(drip, "Drip", 0f, 1f, 0f, repeat = true), Parameter(angle, "Angle X", -30f, 30f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			runtimeTarget = target,
		)

	/**
	 * The registered Toggle Repeat over [session].
	 *
	 * @param EditorSession? session The session, or null for no document.
	 * @return Command The command.
	 */
	private fun toggleRepeatOver(session: EditorSession?): Command = parameterCommands(session, SessionAvailability(session)).single { command -> command.id == "parameter.toggleRepeat" }

	/** Nothing to aim at - no argument, an unknown parameter, no document - grays it. */
	@Test
	fun itGraysWithNothingToAimAt() {
		val session = EditorSession(rig(RuntimeTarget.NoTarget))

		assertFalse(toggleRepeatOver(session).isAvailable(null), "a chord or the palette runs it with no parameter")
		assertFalse(toggleRepeatOver(session).isAvailable(ParameterId("ParamGone")))
		assertFalse(toggleRepeatOver(null).isAvailable(angle))
		assertTrue(toggleRepeatOver(session).isAvailable(angle))
	}

	/** Below 5.3 it offers only to turn a flag the rig already holds off. */
	@Test
	fun belowFiveThreeItOnlyTurnsAFlagOff() {
		val session = EditorSession(rig(RuntimeTarget.Cubism50))

		assertFalse(toggleRepeatOver(session).isAvailable(angle), "5.0 has no repeating parameters to author")
		assertTrue(toggleRepeatOver(session).isAvailable(drip), "the flag the rig holds can still be turned off")
	}

	/** A run flips the flag as one step, and the registry refuses a run its gate refuses. */
	@Test
	fun aRunTogglesTheFlagAsOneStep() {
		val session = EditorSession(rig(RuntimeTarget.Cubism50))
		val registry = CommandRegistry().also { registry -> registry.registerAll(parameterCommands(session, SessionAvailability(session))) }
		val stepsBefore = session.historyView.value.steps.size

		assertFalse(registry.invoke("parameter.toggleRepeat", angle), "the gate holds at dispatch too")
		assertEquals(stepsBefore, session.historyView.value.steps.size)

		assertTrue(registry.invoke("parameter.toggleRepeat", drip))
		assertFalse(session.model.value.parameters.first { parameter -> parameter.id == drip }.repeat)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.parameter.repeat", session.historyView.value.steps.last().labelKey)
	}
}