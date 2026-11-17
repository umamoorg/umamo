package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
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
import kotlin.test.assertNull

/**
 * Pins the Deform brush's button on the viewport toolbar: it stands there only in Edit mode, where it is never
 * grayed, and pressing it arms the brush.
 *
 * The brush is a tool of Edit mode, so the strip leaves it out elsewhere rather than showing a button that would
 * refuse - and the palette hides it the same way.
 */
@OptIn(ExperimentalTestApi::class)
class BrushToolbarTest {
	private val parameter = ParameterId("P")
	private val warpId = DeformerId("warp")
	private val artId = DrawableId("art")
	private val areaId = "area-1"

	/**
	 * A rig with one keyed warp and a quad of art, so Edit mode can open over either.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val lattice = floatArrayOf(0f, 0f, 40f, 0f, 0f, 40f, 40f, 40f)
		val art =
			Drawable(
				id = artId,
				name = "art",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
				geometryGrid = null,
			)

		return PuppetModel(
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
			drawables = listOf(art),
			rootChildren = listOf(OrgChild.Drawable(artId)),
			rootPartId = null,
		)
	}

	/**
	 * Mounts the toolbar over a session over the rig, with [warpSelected] telling whether the warp is selected.
	 *
	 * @param Boolean warpSelected True to select the warp.
	 * @return EditorSession The session the toolbar reads.
	 */
	private fun ComposeUiTest.mountToolbar(warpSelected: Boolean): EditorSession {
		val session = EditorSession(model())

		if (warpSelected) {
			session.setSelection(Selection(setOf(SelectionTarget.Deformer(warpId)), SelectionTarget.Deformer(warpId)))
		} else {
			session.setSelection(Selection(setOf(SelectionTarget.Drawable(artId)), SelectionTarget.Drawable(artId)))
		}

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

		return session
	}

	/**
	 * Whether the brush's button stands on the toolbar.
	 *
	 * @return Boolean True when it stands there.
	 */
	private fun ComposeUiTest.brushButtonStands(): Boolean = onAllNodes(hasContentDescription("Deform Brush")).fetchSemanticsNodes().isNotEmpty()

	/**
	 * The brush's button is a tool of Edit mode: the strip leaves it out elsewhere, and in Edit mode it stands
	 * there, enabled, and arms the brush when it is pressed.
	 */
	@Test
	fun theToolbarBrushButtonStandsOnlyInEditModeAndArmsTheBrush() =
		runComposeUiTest {
			val session = mountToolbar(warpSelected = true)

			assertEquals(false, brushButtonStands(), "the strip leaves the brush out in Object mode")

			session.setMode(EditorMode.Edit)
			waitForIdle()

			assertEquals(true, brushButtonStands(), "the strip shows the brush in Edit mode")
			onNodeWithContentDescription("Deform Brush").assertIsEnabled()

			onNodeWithContentDescription("Deform Brush").performClick()
			waitForIdle()

			val brush = assertNotNull(session.activeBrush.value, "the brush is armed")
			assertEquals(areaId, brush.areaId, "in the viewport the pointer was over")
		}

	/** Leaving Edit mode drops the armed brush, so the default element picking is back. */
	@Test
	fun leavingEditModeDropsTheArmedBrush() =
		runComposeUiTest {
			val session = mountToolbar(warpSelected = true)
			session.setMode(EditorMode.Edit)
			waitForIdle()
			onNodeWithContentDescription("Deform Brush").performClick()
			waitForIdle()
			assertNotNull(session.activeBrush.value, "the brush is armed")

			session.setMode(EditorMode.Object)
			waitForIdle()

			assertNull(session.activeBrush.value, "the brush is dropped with the mode")
			assertEquals(false, brushButtonStands(), "and the strip leaves its button out again")
		}

	/**
	 * The brush stands in Edit mode whatever the selection holds, and says why it cannot smear when the session
	 * has no warp - rather than leaving the rigger to wonder why the brush did nothing.
	 */
	@Test
	fun theBrushSaysWhyItCannotSmearWithoutAWarp() =
		runComposeUiTest {
			val session = mountToolbar(warpSelected = false)
			session.setMode(EditorMode.Edit)
			waitForIdle()

			assertEquals(true, brushButtonStands(), "the strip shows the brush in Edit mode")
			onNodeWithContentDescription("Deform Brush").assertIsEnabled()

			onNodeWithContentDescription("Deform Brush").performClick()
			waitForIdle()

			assertNull(session.activeBrush.value, "nothing to smear, so no brush")
			assertEquals("notice.edit.deformBrushNeedsAWarp", session.notice.value?.messageKey, "and it says why")
		}
}