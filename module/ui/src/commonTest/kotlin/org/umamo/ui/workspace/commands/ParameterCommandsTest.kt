package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterControl
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.bindableCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins Toggle Repeat: it runs on the parameter it is aimed at, under every runtime target, and grays
 * everywhere it has nothing to aim at.  Pins the control commands too: a single row's run with a parameter in no
 * link, a pair's with its horizontal member, each is the one in effect while its control shows, and a pick is no
 * undo step and no change to the document.
 */
class ParameterCommandsTest {
	private val drip = ParameterId("ParamDrip")
	private val angle = ParameterId("ParamAngleX")
	private val eyeX = ParameterId("ParamEyeBallX")
	private val eyeY = ParameterId("ParamEyeBallY")

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

	/** The oldest target offers it both ways, since no target restricts repeat. */
	@Test
	fun theOldestTargetOffersItBothWays() {
		val session = EditorSession(rig(RuntimeTarget.Cubism30))

		assertTrue(toggleRepeatOver(session).isAvailable(angle), "a normal parameter can be made to repeat")
		assertTrue(toggleRepeatOver(session).isAvailable(drip), "a repeating parameter can be turned back")
	}

	/** A run flips the flag as one step, and the registry refuses a run its gate refuses. */
	@Test
	fun aRunTogglesTheFlagAsOneStep() {
		val session = EditorSession(rig(RuntimeTarget.Cubism30))
		val registry = CommandRegistry().also { registry -> registry.registerAll(parameterCommands(session, SessionAvailability(session))) }
		val stepsBefore = session.historyView.value.steps.size

		assertFalse(registry.invoke("parameter.toggleRepeat", ParameterId("ParamGone")), "the gate holds at dispatch too")
		assertEquals(stepsBefore, session.historyView.value.steps.size)

		assertTrue(registry.invoke("parameter.toggleRepeat", drip))
		assertFalse(session.model.value.parameters.first { parameter -> parameter.id == drip }.repeat)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.parameter.repeat", session.historyView.value.steps.last().labelKey)
	}

	/**
	 * A rig holding a linked pair beside the drip and the angle.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun pairedRig(): PuppetModel =
		rig(RuntimeTarget.NoTarget).let { model ->
			model.copy(
				parameters = model.parameters + listOf(Parameter(eyeX, "Eye X", -1f, 1f, 0f), Parameter(eyeY, "Eye Y", -1f, 1f, 0f)),
				parameterLinks = listOf(ParameterLink(eyeX, eyeY)),
			)
		}

	/**
	 * The registered command [id] over [session].
	 *
	 * @param EditorSession? session The session, or null for no document.
	 * @param String id The command's id.
	 * @return Command The command.
	 */
	private fun commandOver(session: EditorSession?, id: String): Command = parameterCommands(session, SessionAvailability(session)).single { command -> command.id == id }

	/** A single row's control runs with a parameter in no link, repeating or not, and a pair's with its horizontal member alone. */
	@Test
	fun aControlRunsWithTheRowsOwnParameter() {
		val session = EditorSession(pairedRig())

		for (id in listOf("parameter.control.follow", "parameter.control.slider", "parameter.control.dial")) {
			val command = commandOver(session, id)
			assertTrue(command.isAvailable(drip), "$id on a repeating parameter")
			assertTrue(command.isAvailable(angle), "$id on a parameter that does not repeat")
			assertFalse(command.isAvailable(eyeX), "$id never runs on a pair's member, whose row is the pad")
			assertFalse(command.isAvailable(eyeY), "$id on the lower member either")
			assertFalse(command.isAvailable(null), "$id with no parameter")
			assertFalse(command.isAvailable(ParameterId("ParamGone")), "$id on a parameter the document lacks")
			assertFalse(commandOver(null, id).isAvailable(angle), "$id with no document")
		}

		for (id in listOf("parameter.pairControl.follow", "parameter.pairControl.pad", "parameter.pairControl.dials")) {
			val command = commandOver(session, id)
			assertTrue(command.isAvailable(eyeX), "$id on the pair's horizontal member")
			assertFalse(command.isAvailable(eyeY), "$id never on the lower member")
			assertFalse(command.isAvailable(angle), "$id never on a parameter in no link")
			assertFalse(command.isAvailable(null), "$id with no parameter")
		}
	}

	/**
	 * A rig whose link has a flat lower member: the panel folds no pad over it, so its upper member keeps a row of
	 * its own.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun flatLinkRig(): PuppetModel =
		rig(RuntimeTarget.NoTarget).let { model ->
			model.copy(
				parameters = model.parameters + listOf(Parameter(eyeX, "Eye X", -1f, 1f, 0f), Parameter(eyeY, "Eye Y", 0f, 0f, 0f)),
				parameterLinks = listOf(ParameterLink(eyeX, eyeY)),
			)
		}

	/** A member of a link the panel shows no pad for is on a row of its own, so the single row's control is its, and the pair's is nobody's. */
	@Test
	fun aMemberOfALinkThatShowsNoPadTakesTheSingleRowsControl() {
		val session = EditorSession(flatLinkRig())
		val registry = CommandRegistry().also { registry -> registry.registerAll(parameterCommands(session, SessionAvailability(session))) }

		for (id in listOf("parameter.control.follow", "parameter.control.slider", "parameter.control.dial")) {
			assertTrue(commandOver(session, id).isAvailable(eyeX), "$id on the member whose row is its own")
			assertFalse(commandOver(session, id).isAvailable(eyeY), "$id on the flat member, which has no row at all")
		}

		for (id in listOf("parameter.pairControl.follow", "parameter.pairControl.pad", "parameter.pairControl.dials")) {
			assertFalse(commandOver(session, id).isAvailable(eyeX), "$id where no pad shows")
		}

		assertTrue(registry.invoke("parameter.control.dial", eyeX))
		assertEquals(ParameterControl.Dial, session.parameterControls.value[eyeX])
		assertTrue(commandOver(session, "parameter.control.dial").isOn(eyeX), "the pick is the one in effect")
	}

	/**
	 * Every command here runs with the parameter a row hands it, and says so: none applies with no argument, and the
	 * keybindings list offers none of them, Toggle Repeat included though it has a title to list.  A control is
	 * untitled besides, since only its family's menu ever shows it.
	 */
	@Test
	fun everyCommandNeedsItsParameterAndIsNeverOfferedAChord() {
		val session = EditorSession(pairedRig())
		val commands = parameterCommands(session, SessionAvailability(session))

		assertEquals(7, commands.size)

		for (command in commands) {
			assertTrue(command.needsArgument, "${command.id} does not say it needs an argument")
			assertFalse(command.isAvailable(null), "${command.id} applies with nothing to aim it")
		}

		assertEquals(emptyList(), bindableCommands(commands).map { command -> command.id }, "a binding to any of them could never fire")
		assertNotNull(commands.single { command -> command.id == "parameter.toggleRepeat" }.title, "Toggle Repeat's row goes by its title")

		for (command in commands.filter { command -> command.group != null }) {
			assertNull(command.title, "${command.id} has a title nothing shows")
			assertNotNull(command.rowLabel, "${command.id} has no label for its row")
		}
	}

	/** Follow is the one in effect until a pick, a pick moves it, and no pick is an undo step or a change. */
	@Test
	fun aPickMovesTheCurrentControlAndRecordsNothing() {
		val session = EditorSession(pairedRig())
		val registry = CommandRegistry().also { registry -> registry.registerAll(parameterCommands(session, SessionAvailability(session))) }
		val follow = commandOver(session, "parameter.control.follow")
		val dial = commandOver(session, "parameter.control.dial")
		val pad = commandOver(session, "parameter.pairControl.pad")

		assertTrue(follow.isOn(angle), "a parameter follows the preference until it picks")
		assertFalse(dial.isOn(angle))

		assertTrue(registry.invoke("parameter.control.dial", angle))
		assertEquals(mapOf(angle to ParameterControl.Dial), session.parameterControls.value)
		assertTrue(dial.isOn(angle), "the pick is the one in effect")
		assertFalse(follow.isOn(angle))
		assertTrue(follow.isOn(drip), "for that parameter alone")

		assertTrue(registry.invoke("parameter.pairControl.pad", eyeX))
		assertEquals(ParameterControl.Slider, session.parameterControls.value[eyeX], "a pair's pad is its slider choice, keyed on its horizontal member")
		assertTrue(pad.isOn(eyeX))

		assertFalse(registry.invoke("parameter.pairControl.pad", eyeY), "the gate holds at dispatch")

		assertTrue(registry.invoke("parameter.control.follow", angle))
		assertTrue(follow.isOn(angle), "following again puts the preference back")
		assertFalse(session.canUndo.value, "no pick is an undo step")
		assertFalse(session.dirty.value, "and none is a change to the document")
	}
}