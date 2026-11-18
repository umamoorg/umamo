package org.umamo.ui.properties

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.sidebar.ProvideSidebarLocals
import org.umamo.ui.sidebar.builtInSidebarRegistry
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.RelationPickController
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.relationCommands
import org.umamo.ui.workspace.spaces.properties.PropertiesSpace
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A right-click on a relation field in the Properties panel opens its relation menu, merged above the field's own
 * clipboard rows, and a right-click on a Masked By row opens that row's.
 */
@OptIn(ExperimentalTestApi::class)
class RelationFieldMenuUiTest {
	/**
	 * A session showing drawable "art", deformed by warp "lattice" and masked by drawable "clip".
	 *
	 * @return EditorSession The session.
	 */
	private fun session(): EditorSession {
		/**
		 * A mesh-less root drawable.
		 *
		 * @param String id The id.
		 * @param DeformerId? parent Its parent deformer.
		 * @param List maskedBy Its masks.
		 * @return Drawable The drawable.
		 */
		fun drawable(id: String, parent: DeformerId?, maskedBy: List<DrawableId> = emptyList()): Drawable =
			Drawable(id = DrawableId(id), name = id, parentDeformerId = parent, blendMode = BlendMode.Normal, maskedBy = maskedBy, mesh = null, geometryGrid = null)

		val lattice = DeformerId("lattice")
		val session =
			EditorSession(
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = listOf(Deformer.Warp(id = lattice, name = "lattice", parent = null, partId = null, rows = 1, columns = 1, isQuadTransform = true, geometryGrid = null)),
					drawables = listOf(drawable("art", lattice, listOf(DrawableId("clip"))), drawable("clip", null)),
					rootChildren = listOf(OrgChild.Drawable(DrawableId("art")), OrgChild.Drawable(DrawableId("clip"))),
					rootPartId = null,
				),
			)
		val art = SelectionTarget.Drawable(DrawableId("art"))
		session.setSelection(Selection(setOf(art), art))

		return session
	}

	/**
	 * Mounts the Properties panel on [tab] over [session], with the relation table registered.
	 *
	 * @param EditorSession session The session.
	 * @param PropertyTabId tab The tab to show.
	 */
	private fun ComposeUiTest.mount(session: EditorSession, tab: PropertyTabId) {
		val commands = CommandRegistry().also { registry -> registry.registerAll(relationCommands(session, RelationPickController(), CommandRouting { null }, SessionAvailability(session))) }
		val scope = AreaScope("properties")
		scope.spaceState(PROPERTIES_VIEW_STATE_KEY) { PropertiesViewState() }.activeTab = tab
		setContent {
			UmamoTheme {
				ProvideSidebarLocals(
					session,
					builtInSidebarRegistry(),
					LocalDensity provides Density(1f),
					LocalSettings provides inMemorySettings(),
					LocalPropertyTabRegistry provides defaultPropertyTabRegistry(),
					LocalCommands provides commands,
				) {
					Box(modifier = Modifier.size(420.dp, 900.dp)) {
						PropertiesSpace(scope)
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * Whether an open popup shows [label].
	 *
	 * @param String label The text.
	 * @return Boolean True when it does.
	 */
	private fun ComposeUiTest.menuShows(label: String): Boolean = onAllNodes(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

	@Test
	fun aRelationFieldsMenuSitsAboveItsClipboardRows() =
		runComposeUiTest {
			mount(session(), PropertyTabId.Object)

			onNode(hasText("lattice") and hasSetTextAction()).performMouseInput {
				moveTo(center)
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()

			for (label in listOf("Select", "Clear", "Pick from Viewport", "Copy to Selected", "Copy", "Select All")) {
				assertTrue(menuShows(label), "the menu shows $label")
			}
		}

	@Test
	fun aMaskedByRowsMenuIsTheRowsOwn() =
		runComposeUiTest {
			mount(session(), PropertyTabId.Data)

			onNode(hasText("clip") and !hasSetTextAction()).performMouseInput {
				moveTo(center)
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()

			for (label in listOf("Select", "Remove", "Copy to Selected")) {
				assertTrue(menuShows(label), "the menu shows $label")
			}
		}
}