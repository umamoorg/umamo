package org.umamo.ui.properties

import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.relation.RelationSlot
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.chooseEveryRow
import org.umamo.ui.action.commandMenuOf
import org.umamo.ui.action.menuOutline
import org.umamo.ui.action.recordingRegistryOf
import org.umamo.ui.workspace.PickKind
import org.umamo.ui.workspace.RelationPickController
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.relationCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A relation field's context menu and a relation list row's, as the registry lists them: every row runs with the
 * field's slot, grays by what that slot binds and by whether anything else is selected, and does what the field
 * itself would.
 */
class RelationMenuTest {
	private val warp = DeformerId("w")
	private val bound = SelectionTarget.Drawable(DrawableId("a"))
	private val unbound = SelectionTarget.Drawable(DrawableId("b"))
	private val masker = SelectionTarget.Drawable(DrawableId("c"))
	private val picker = RelationPickController()

	/**
	 * A session over a warp, a drawable a it deforms (masked by c), and two root drawables b and c, with [selected]
	 * selected, the first active.
	 *
	 * @param List<SelectionTarget> selected The selection.
	 * @return EditorSession The session.
	 */
	private fun session(selected: List<SelectionTarget>): EditorSession {
		/**
		 * A mesh-less drawable.
		 *
		 * @param String id The id.
		 * @param DeformerId? parent Its parent deformer.
		 * @param List maskedBy Its masks.
		 * @return Drawable The drawable.
		 */
		fun drawable(id: String, parent: DeformerId?, maskedBy: List<DrawableId> = emptyList()): Drawable =
			Drawable(id = DrawableId(id), name = id, parentDeformerId = parent, blendMode = BlendMode.Normal, maskedBy = maskedBy, mesh = null, geometryGrid = null)

		val session =
			EditorSession(
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = listOf(Deformer.Warp(id = warp, name = "w", parent = null, partId = null, rows = 1, columns = 1, isQuadTransform = true, geometryGrid = null)),
					drawables = listOf(drawable("a", warp, listOf(DrawableId("c"))), drawable("b", null), drawable("c", null)),
					rootChildren = emptyList(),
					rootPartId = null,
				),
			)
		session.setSelection(Selection(selected.toSet(), selected.firstOrNull()))

		return session
	}

	/**
	 * A registry holding the relation table over [session].
	 *
	 * @param EditorSession session The session.
	 * @return CommandRegistry The registry.
	 */
	private fun registryOver(session: EditorSession): CommandRegistry =
		CommandRegistry().also { registry -> registry.registerAll(relationCommands(session, picker, CommandRouting { null }, SessionAvailability(session))) }

	@Test
	fun aBoundFieldsMenuIsSelectClearPickAndCopy() {
		val menu = commandMenuOf(relationFieldMenu(RelationSlot.ParentDeformer(bound)), registryOver(session(listOf(bound, unbound))))

		assertEquals(listOf("Select", "---", "Clear", "Pick from Viewport", "---", "Copy to Selected"), menuOutline(menu))
	}

	@Test
	fun anUnboundFieldWithNothingElseSelectedGraysAllButPick() {
		val menu = commandMenuOf(relationFieldMenu(RelationSlot.ParentDeformer(unbound)), registryOver(session(listOf(unbound))))

		assertEquals(listOf("Select (off)", "---", "Clear (off)", "Pick from Viewport", "---", "Copy to Selected (off)"), menuOutline(menu))
	}

	@Test
	fun aListRowsMenuIsSelectRemoveAndCopy() {
		val menu = commandMenuOf(relationEntryMenu(RelationSlot.Mask(bound, masker)), registryOver(session(listOf(bound))))

		assertEquals(listOf("Select", "Remove", "---", "Copy to Selected (off)"), menuOutline(menu))
	}

	@Test
	fun everyRowRunsWithTheFieldsSlot() {
		val slot = RelationSlot.ParentDeformer(bound)
		val dispatched = ArrayList<String>()
		chooseEveryRow(commandMenuOf(relationFieldMenu(slot), recordingRegistryOf(relationCommands(null, picker, CommandRouting { null }, SessionAvailability(null)), dispatched)))

		assertEquals(listOf("relation.select", "relation.clear", "relation.pick", "relation.copyToSelected").map { commandId -> "$commandId($slot)" }, dispatched)
	}

	@Test
	fun theRowsDoWhatTheFieldWould() {
		val slot = RelationSlot.ParentDeformer(bound)
		val session = session(listOf(bound, unbound))
		val registry = registryOver(session)

		assertTrue(registry.invoke("relation.copyToSelected", slot))
		assertEquals(warp, session.model.value.drawables.first { drawable -> drawable.id == unbound.id }.parentDeformerId, "b takes a's parent deformer")

		assertTrue(registry.invoke("relation.pick", slot))
		val request = assertNotNull(picker.request)
		assertEquals(slot, request.owner, "the field's own eyedropper lights")
		assertEquals(setOf(PickKind.Deformer), request.accepts)
		picker.cancel()

		assertTrue(registry.invoke("relation.clear", slot))
		assertNull(session.model.value.drawables.first { drawable -> drawable.id == bound.id }.parentDeformerId)

		assertTrue(registry.invoke("relation.select", RelationSlot.ParentDeformer(unbound)))
		assertEquals(Selection(setOf(SelectionTarget.Deformer(warp)), SelectionTarget.Deformer(warp)), session.selection.value, "the bound object is selected")
	}
}