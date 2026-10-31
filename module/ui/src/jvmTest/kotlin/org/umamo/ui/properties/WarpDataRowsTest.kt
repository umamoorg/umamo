package org.umamo.ui.properties

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.LocalSettings
import org.umamo.ui.sidebar.ProvideSidebarLocals
import org.umamo.ui.sidebar.builtInSidebarRegistry
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.spaces.properties.PropertiesSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Warp w: a 2 x 2 lattice keyed on nothing, with a blend shape on P. */
private val WARP_W = SelectionTarget.Deformer(DeformerId("w"))

/** Drawable d: meshless, with a blend shape on P. */
private val DRAWABLE_D = SelectionTarget.Drawable(DrawableId("d"))

/** The parameter the blend shapes are bound to. */
private val PARAMETER = ParameterId("P")

/**
 * The Data tab's warp rows: the lattice's Rows / Columns edit its resolution, the aspect lock carries the other count,
 * both and the Quad transform are inert outside Edit mode, and the Blend Shapes section lists an item's bindings and hides
 * for an item with none.
 */
@OptIn(ExperimentalTestApi::class)
class WarpDataRowsTest {
	/**
	 * A model with warp w (a 2 x 2 lattice over 0..40 with a blend shape on P) and meshless drawable d (a blend shape on
	 * P with three keys).
	 *
	 * @param Boolean withBlendShapes False to give neither item a blend shape.
	 * @return PuppetModel The model.
	 */
	private fun model(withBlendShapes: Boolean = true): PuppetModel {
		val points = floatArrayOf(0f, 0f, 20f, 0f, 40f, 0f, 0f, 20f, 20f, 20f, 40f, 20f, 0f, 40f, 20f, 40f, 40f, 40f)
		val warp =
			Deformer.Warp(
				id = WARP_W.id,
				name = "w",
				parent = null,
				partId = null,
				rows = 2,
				columns = 2,
				isQuadTransform = true,
				geometryGrid = KeyformGrid(listOf(KeyformAxis(ParameterId("unposed"), floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(points)))),
				blendShapes = if (withBlendShapes) listOf(BlendShapeBinding(PARAMETER, floatArrayOf(0f, 1f), 0, listOf(null, WarpForm(points)))) else emptyList(),
			)
		val drawable =
			Drawable(
				id = DRAWABLE_D.id,
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
				blendShapes = if (withBlendShapes) listOf(BlendShapeBinding(PARAMETER, floatArrayOf(0f, 0.5f, 1f), 0, listOf(null, MeshForm(floatArrayOf(0f, 0f)), MeshForm(floatArrayOf(0f, 0f))))) else emptyList(),
			)

		return PuppetModel(
			parameters = listOf(Parameter(PARAMETER, "Smile", min = 0f, max = 1f, default = 0f)),
			parts = emptyList(),
			deformers = listOf(warp),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(DRAWABLE_D.id)),
			rootPartId = null,
		)
	}

	/**
	 * Mounts the Properties panel on its Data tab over [session], at density 1.
	 *
	 * @param EditorSession session The session the panel reads and writes.
	 */
	private fun ComposeUiTest.mountData(session: EditorSession) {
		val settings = inMemorySettings()
		val scope = AreaScope("properties")
		scope.spaceState(PROPERTIES_VIEW_STATE_KEY) { PropertiesViewState() }.activeTab = PropertyTabId.Data
		setContent {
			UmamoTheme {
				ProvideSidebarLocals(
					session,
					builtInSidebarRegistry(),
					LocalDensity provides Density(1f),
					LocalSettings provides settings,
					LocalPropertyTabRegistry provides defaultPropertyTabRegistry(),
				) {
					Box(modifier = Modifier.size(420.dp, 700.dp)) {
						PropertiesSpace(scope)
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * A session over the model showing [target].
	 *
	 * @param SelectionTarget target The item to show.
	 * @param Boolean withBlendShapes False to give no item a blend shape.
	 * @return EditorSession The session.
	 */
	private fun showing(target: SelectionTarget, withBlendShapes: Boolean = true): EditorSession {
		val session = EditorSession(model(withBlendShapes))
		session.setSelection(Selection(setOf(target), target))

		return session
	}

	/**
	 * Types [typed] into the [index]th field showing [shown].
	 *
	 * @param String shown The text the field shows.
	 * @param Int index Which of the fields showing it.
	 * @param String typed What to type.
	 */
	private fun ComposeUiTest.typeInto(shown: String, index: Int, typed: String) {
		onAllNodesWithText(shown)[index].performMouseInput { click() }
		waitForIdle()
		onNode(hasSetTextAction() and isFocused()).performTextReplacement(typed)
		onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
		waitForIdle()
	}

	/**
	 * The warp of [session]'s model.
	 *
	 * @param EditorSession session The session.
	 * @return Deformer.Warp The warp.
	 */
	private fun warpOf(session: EditorSession): Deformer.Warp = session.model.value.deformerById[WARP_W.id] as Deformer.Warp

	/**
	 * Clicks the Rows field and the Quad toggle over [session] in [mode] and asserts neither took.
	 *
	 * @param EditorSession session The session, showing warp w.
	 * @param EditorMode mode The mode the rows are inert in.
	 */
	private fun ComposeUiTest.assertRowsInertIn(session: EditorSession, mode: EditorMode) {
		runOnIdle { session.setMode(mode) }
		runOnIdle { assertEquals(mode, session.mode.value, "the fixture reached the mode it tests") }
		mountData(session)

		onAllNodesWithText("2")[0].performMouseInput { click() }
		waitForIdle()
		onAllNodes(hasSetTextAction() and isFocused()).assertCountEquals(0)
		onNodeWithText("Bilinear (Quad)").performMouseInput { click() }
		waitForIdle()
		runOnIdle { assertTrue(warpOf(session).isQuadTransform, "the toggle did nothing") }
	}

	@Test
	fun rowsResizeTheLatticeAndTheLockCarriesColumns() =
		runComposeUiTest {
			val session = showing(WARP_W)
			runOnIdle { session.setMode(EditorMode.Edit) }
			runOnIdle { assertEquals(EditorMode.Edit, session.mode.value, "the selected warp opens its lattice") }
			mountData(session)
			onNodeWithText("Rows").assertExists()
			onNodeWithText("Columns").assertExists()

			typeInto("2", index = 0, typed = "3")
			runOnIdle { assertEquals(3 to 2, warpOf(session).rows to warpOf(session).columns, "unlocked, the columns hold") }

			onNodeWithContentDescription("Lock Aspect Ratio").performMouseInput { click() }
			waitForIdle()
			typeInto("3", index = 0, typed = "6")
			runOnIdle { assertEquals(6 to 4, warpOf(session).rows to warpOf(session).columns, "locked, 3 x 2 becomes 6 x 4") }
		}

	@Test
	fun objectModeLeavesTheRowsAndTheQuadToggleInert() =
		runComposeUiTest {
			assertRowsInertIn(showing(WARP_W), EditorMode.Object)
		}

	@Test
	fun poseModeLeavesTheRowsAndTheQuadToggleInert() =
		runComposeUiTest {
			assertRowsInertIn(showing(WARP_W), EditorMode.Pose)
		}

	@Test
	fun blendShapesListEachBindingAndHideWithNone() =
		runComposeUiTest {
			mountData(showing(WARP_W))
			onNodeWithText("Blend Shapes").assertExists()
			onNodeWithText("Smile: 2 keys").assertExists()
		}

	@Test
	fun aDrawablesBlendShapesListToo() =
		runComposeUiTest {
			mountData(showing(DRAWABLE_D))
			onNodeWithText("Smile: 3 keys").assertExists()
		}

	@Test
	fun anItemWithoutBlendShapesShowsNoSection() =
		runComposeUiTest {
			mountData(showing(WARP_W, withBlendShapes = false))
			onAllNodesWithText("Blend Shapes").assertCountEquals(0)
		}
}