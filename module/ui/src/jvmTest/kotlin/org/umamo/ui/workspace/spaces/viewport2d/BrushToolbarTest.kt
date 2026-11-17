package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.ActiveBrush
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.HoveredSurface
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.brushCommands
import org.umamo.ui.workspace.commands.registerAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the Deform brush's button on the viewport toolbar: it stands there with its glyph, it is gray until
 * Edit mode has a warp to smear, and it arms the brush when it is pressed.
 *
 * The gray-until case is the toolbar's own: a button's enabled state is read when it is composed, so the
 * strip has to be subscribed to the mode and the element selection for the brush's button to follow them.
 */
@OptIn(ExperimentalTestApi::class)
class BrushToolbarTest {
	private val parameter = ParameterId("P")
	private val warpId = DeformerId("warp")
	private val areaId = "area-1"

	/**
	 * A rig with one keyed warp, the session's element owner in Edit mode.
	 *
	 * @return EditorSession The session.
	 */
	private fun sessionOverAWarp(): EditorSession {
		val lattice = floatArrayOf(0f, 0f, 40f, 0f, 0f, 40f, 40f, 40f)
		val model =
			PuppetModel(
				parameters = listOf(Parameter(parameter, "P", -1f, 1f, 0f)),
				parts = emptyList(),
				deformers =
					listOf(
						Deformer.Warp(
							id = warpId,
							name = "warp",
							parent = null,
							partId = null,
							rows = 1,
							columns = 1,
							isQuadTransform = true,
							geometryGrid =
								KeyformGrid(
									listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))),
									listOf(
										KeyformCell(intArrayOf(0), WarpLatticeForm(lattice.copyOf())),
										KeyformCell(intArrayOf(1), WarpLatticeForm(lattice.copyOf())),
									),
								),
						),
					),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
			)
		val session = EditorSession(model)
		session.setSelection(Selection(setOf(SelectionTarget.Deformer(warpId)), SelectionTarget.Deformer(warpId)))

		return session
	}

	/**
	 * The brush's button on the toolbar, over a session in Edit mode with a warp selected.
	 *
	 * @return Boolean True when the button stands there.
	 */
	private fun ComposeUiTest.brushButtonStands(): Boolean = onAllNodes(hasContentDescription("Deform Brush")).fetchSemanticsNodes().isNotEmpty()

	/**
	 * The button stands there with its glyph, is gray until Edit mode has a warp, and arms the brush when
	 * it is pressed.
	 */
	@Test
	fun theToolbarBrushButtonArmsTheBrushInEditModeOverAWarp() =
		runComposeUiTest {
			val session = sessionOverAWarp()
			val registry = CommandRegistry()
			registry.registerAll(
				brushCommands(
					session,
					CommandRouting { HoveredSurface(areaId, SpaceKind.Viewport2D) },
					SessionAvailability(session),
				),
			)

			setContent {
				UmamoTheme {
					CompositionLocalProvider(
						LocalCommands provides registry,
						LocalEditorSession provides session,
					) {
						ViewportToolbarOverlay()
					}
				}
			}
			waitForIdle()

			assertEquals(true, brushButtonStands(), "the brush's button stands on the toolbar")
			onNodeWithContentDescription("Deform Brush").assertIsNotEnabled()

			session.setMode(EditorMode.Edit)
			waitForIdle()

			onNodeWithContentDescription("Deform Brush").assertIsEnabled()
			onNodeWithContentDescription("Deform Brush").performClick()
			waitForIdle()

			val brush = assertNotNull(session.activeBrush.value, "the brush is armed")
			assertEquals(areaId, brush.areaId, "in the viewport the pointer was over")
			assertEquals(ActiveBrush(brush.radiusWorld, areaId), brush)
		}
}