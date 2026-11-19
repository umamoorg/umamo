package org.umamo.ui.workspace.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.relation.RelationSlot
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.workspace.PickKind
import org.umamo.ui.workspace.RelationPickController
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.relationCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An armed relation pick ends on any command the registry runs, however it was dispatched, for as long as the shell's
 * observer is composed: a command run under the pick would start beneath its hidden cursor.
 */
@OptIn(ExperimentalTestApi::class)
class RelationPickCommandEndTest {
	private val owner = SelectionTarget.Drawable(DrawableId("a"))
	private val other = SelectionTarget.Drawable(DrawableId("b"))

	/**
	 * A session over two root drawables, a selected.
	 *
	 * @return EditorSession The session.
	 */
	private fun session(): EditorSession {
		/**
		 * A mesh-less root drawable.
		 *
		 * @param String id The id.
		 * @return Drawable The drawable.
		 */
		fun drawable(id: String): Drawable =
			Drawable(id = DrawableId(id), name = id, parentDeformerId = null, blendMode = BlendMode.Normal, maskedBy = emptyList(), mesh = null, geometryGrid = null)

		val session =
			EditorSession(
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = emptyList(),
					drawables = listOf(drawable("a"), drawable("b")),
					rootChildren = emptyList(),
					rootPartId = null,
				),
			)
		session.setSelection(Selection(setOf(owner), owner))

		return session
	}

	@Test
	fun aCommandEndsThePickBeforeItsHandlerRuns() =
		runComposeUiTest {
			val registry = CommandRegistry()
			val relationPick = RelationPickController()
			var armedWhenRun: Boolean? = null
			registry.register(Command("test.grab", title = null) { armedWhenRun = relationPick.request != null })
			setContent { EndRelationPickOnCommand(registry, relationPick) }

			runOnIdle {
				relationPick.arm(accepts = setOf(PickKind.Deformer)) {}

				assertTrue(registry.invoke("test.grab"))

				assertNull(relationPick.request, "the command ends the pick")
				assertEquals(false, armedWhenRun, "and runs with no pick armed")
			}
		}

	@Test
	fun anUnavailableCommandLeavesThePickArmed() =
		runComposeUiTest {
			val registry = CommandRegistry()
			val relationPick = RelationPickController()
			registry.register(Command("test.gated", title = null, availability = { false }) {})
			setContent { EndRelationPickOnCommand(registry, relationPick) }

			runOnIdle {
				relationPick.arm(accepts = setOf(PickKind.Deformer)) {}

				assertFalse(registry.invoke("test.gated"))

				assertNotNull(relationPick.request, "a command that runs nothing ends nothing")
			}
		}

	@Test
	fun pickFromAFieldsMenuArmsThatField() =
		runComposeUiTest {
			val session = session()
			val registry = CommandRegistry()
			val relationPick = RelationPickController()
			registry.registerAll(relationCommands(session, relationPick, CommandRouting { null }, SessionAvailability(session)))
			setContent { EndRelationPickOnCommand(registry, relationPick) }
			val ownerSlot = RelationSlot.ParentDeformer(owner)
			val otherSlot = RelationSlot.ParentDeformer(other)

			runOnIdle {
				relationPick.armFor(ownerSlot, session) { null }

				assertTrue(registry.invoke("relation.pick", otherSlot))
				assertEquals(otherSlot, relationPick.request?.owner, "the first field goes dark and the second lights")

				assertTrue(registry.invoke("relation.pick", otherSlot))
				assertEquals(otherSlot, relationPick.request?.owner, "Pick on the armed field re-arms it")
			}
		}

	@Test
	fun commandsLeaveThePickAloneOnceTheObserverLeaves() =
		runComposeUiTest {
			val registry = CommandRegistry()
			val relationPick = RelationPickController()
			var mounted by mutableStateOf(true)
			registry.register(Command("test.grab", title = null) {})
			setContent {
				if (mounted) {
					EndRelationPickOnCommand(registry, relationPick)
				}
			}

			runOnIdle { mounted = false }
			runOnIdle {
				relationPick.arm(accepts = setOf(PickKind.Deformer)) {}

				assertTrue(registry.invoke("test.grab"))

				assertNotNull(relationPick.request, "the observer stopped with the shell")
			}
		}
}