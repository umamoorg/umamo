package org.umamo.ui.sidebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
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
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.LocalSettings
import org.umamo.ui.properties.LocalPropertyTabRegistry
import org.umamo.ui.properties.PROPERTIES_VIEW_STATE_KEY
import org.umamo.ui.properties.PropertiesViewState
import org.umamo.ui.properties.PropertyTabId
import org.umamo.ui.properties.defaultPropertyTabRegistry
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.sidebar.SidebarState
import org.umamo.ui.workspace.spaces.properties.PropertiesSpace
import kotlin.test.Test
import kotlin.test.assertEquals

/** Drawable a, a 100 px square centered on world (450, -350). */
private val DRAWABLE_A = SelectionTarget.Drawable(DrawableId("a"))

/** Drawable b, a 100 px square centered on world (650, -350). */
private val DRAWABLE_B = SelectionTarget.Drawable(DrawableId("b"))

/** Part p, holding a and b: 300 x 100 centered on world (550, -350). */
private val PART_P = SelectionTarget.Part(PartId("p"))

/** Warp w, its lattice 40 x 60 centered on world (120, -230). */
private val WARP_W = SelectionTarget.Deformer(DeformerId("w"))

/** Rotation r, its pivot at world (30, -70), base angle 10. */
private val ROTATION_R = SelectionTarget.Deformer(DeformerId("r"))

/** The parameter r is keyed on: at 0 r turns 5 more at scale 1, at 1 it turns 45 more at scale 2. */
private val PARAMETER = ParameterId("P")

/**
 * The sidebar's Item tab and the Properties panel's Object tab show the same item by the same rule and edit
 * it through the same writes: a value typed in one shows in the other, both keep the item after a deselect,
 * and both show the active item among several selected.  A part and a deformer show their transform in
 * both, read-only, and it follows the pose.
 */
class ItemPropertiesParityTest {
	/**
	 * A model with drawables a and b, two 100 px squares side by side in part p, a root warp w, and a root
	 * rotation r keyed on P.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		/**
		 * A square drawable spanning canvas x from [left] to [left] + 100 and canvas y 300 to 400.
		 *
		 * @param String id The drawable's id.
		 * @param Float left The square's left edge on the canvas.
		 * @return Drawable The drawable.
		 */
		fun square(id: String, left: Float): Drawable {
			val corners = floatArrayOf(left, 300f, left + 100f, 300f, left + 100f, 400f, left, 400f)

			return Drawable(
				id = DrawableId(id),
				name = id,
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(corners, FloatArray(corners.size), intArrayOf(0, 1, 2, 0, 2, 3)),
				geometryGrid = null,
			)
		}

		val warp =
			Deformer.Warp(
				DeformerId("w"),
				"w",
				null,
				null,
				1,
				1,
				true,
				KeyformGrid(
					listOf(KeyformAxis(ParameterId("unposed"), floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(100f, 200f, 140f, 200f, 100f, 260f, 140f, 260f)))),
				),
			)
		val rotation =
			Deformer.Rotation(
				DeformerId("r"),
				"r",
				null,
				null,
				10f,
				KeyformGrid(
					listOf(KeyformAxis(PARAMETER, floatArrayOf(0f, 1f))),
					listOf(
						KeyformCell(intArrayOf(0), RotationPivotForm(30f, 70f, 5f, 1f)),
						KeyformCell(intArrayOf(1), RotationPivotForm(30f, 70f, 45f, 2f)),
					),
				),
			)

		return PuppetModel(
			parameters = listOf(Parameter(PARAMETER, "P", min = 0f, max = 1f, default = 0f)),
			parts = listOf(Part(PartId("p"), "p", children = listOf(OrgChild.Drawable(DrawableId("a")), OrgChild.Drawable(DrawableId("b"))))),
			deformers = listOf(warp, rotation),
			drawables = listOf(square("a", 400f), square("b", 600f)),
			rootChildren = listOf(OrgChild.Part(PartId("p"))),
			rootPartId = null,
		)
	}

	/**
	 * Drawable a's horizontal center in canvas pixels, which with the world origin at the canvas origin is its
	 * world x.
	 *
	 * @param EditorSession session The session holding the model.
	 * @return Float The center.
	 */
	private fun worldCenterX(session: EditorSession): Float {
		val positions = session.model.value.drawables.first { drawable -> drawable.id == DRAWABLE_A.id }.mesh!!.positions
		val xs = positions.filterIndexed { coordinateIndex, _ -> coordinateIndex % 2 == 0 }

		return (xs.min() + xs.max()) / 2f
	}

	/**
	 * Mounts the Properties panel on its Object tab beside an open sidebar on its Item tab, both over
	 * [session], at density 1.
	 *
	 * @param EditorSession session The session both panels read and write.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountBoth(session: EditorSession) {
		val settings = inMemorySettings()
		val propertiesScope = AreaScope("properties")
		propertiesScope.spaceState(PROPERTIES_VIEW_STATE_KEY) { PropertiesViewState() }.activeTab = PropertyTabId.Object
		val sidebarRegistry = builtInSidebarRegistry()
		setContent {
			UmamoTheme {
				ProvideSidebarLocals(
					session,
					sidebarRegistry,
					LocalDensity provides Density(1f),
					LocalSettings provides settings,
					LocalPropertyTabRegistry provides defaultPropertyTabRegistry(),
				) {
					Row {
						Box(modifier = Modifier.size(360.dp, 500.dp)) {
							PropertiesSpace(propertiesScope)
						}
						SidebarDrawerInArea("area-1", SidebarState(), width = 420.dp, height = 500.dp)
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * Types [typed] into the field showing [shown], the [index]th such field in composition order (the
	 * Properties panel's before the sidebar's).
	 *
	 * @param String shown The text the field shows.
	 * @param Int index Which of the fields showing it.
	 * @param String typed What to type.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.typeInto(shown: String, index: Int, typed: String) {
		onAllNodesWithText(shown)[index].performMouseInput { click() }
		waitForIdle()
		onNode(hasSetTextAction() and isFocused()).performTextReplacement(typed)
		onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
		waitForIdle()
	}

	/**
	 * Selects [target] alone and mounts both panels over a fresh session.
	 *
	 * @param SelectionTarget target The item to show.
	 * @return EditorSession The session.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountShowing(target: SelectionTarget): EditorSession {
		val session = EditorSession(model())
		session.setSelection(Selection(setOf(target), target))
		mountBoth(session)

		return session
	}

	/**
	 * Clicks the [index]th field showing [shown] and asserts no text entry opened: the field is read-only.
	 *
	 * @param String shown The text the field shows.
	 * @param Int index Which of the fields showing it.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.assertReadOnly(shown: String, index: Int) {
		onAllNodesWithText(shown)[index].performMouseInput { click() }
		waitForIdle()
		onAllNodes(hasSetTextAction() and isFocused()).assertCountEquals(0)
	}

	/** A Position X typed in either panel shows in both. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun anEditInEitherPanelShowsInBoth() =
		runComposeUiTest {
			val session = EditorSession(model())
			session.setSelection(Selection(setOf(DRAWABLE_A), DRAWABLE_A))
			mountBoth(session)
			onAllNodesWithText("450.0").assertCountEquals(2)
			typeInto("450.0", index = 1, typed = "137")
			onAllNodesWithText("137.0").assertCountEquals(2)
			typeInto("137.0", index = 0, typed = "251")
			onAllNodesWithText("251.0").assertCountEquals(2)
			runOnIdle { assertEquals(251f, worldCenterX(session), 0.01f) }
		}

	/** After a deselect both panels still show the item last made active. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun bothKeepTheItemAfterADeselect() =
		runComposeUiTest {
			val session = EditorSession(model())
			session.setSelection(Selection(setOf(DRAWABLE_A), DRAWABLE_A))
			mountBoth(session)
			runOnIdle { session.setSelection(Selection()) }
			waitForIdle()
			onAllNodesWithText("450.0").assertCountEquals(2)
		}

	/** Among several selected, both panels show the active item. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun bothShowTheActiveItemAmongSeveral() =
		runComposeUiTest {
			val session = EditorSession(model())
			session.setSelection(Selection(setOf(DRAWABLE_A, DRAWABLE_B), DRAWABLE_B))
			mountBoth(session)
			onAllNodesWithText("650.0").assertCountEquals(2)
			onAllNodesWithText("450.0").assertCountEquals(0)
		}

	/** A part shows the box around what it holds in both panels, read-only. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aPartShowsItsBoundsReadOnlyInBoth() =
		runComposeUiTest {
			mountShowing(PART_P)
			onAllNodesWithText("550.0").assertCountEquals(2)
			onAllNodesWithText("-350.0").assertCountEquals(2)
			onAllNodesWithText("300.0").assertCountEquals(2)
			onAllNodesWithText("100.0").assertCountEquals(2)
			assertReadOnly("550.0", index = 1)
		}

	/** A warp shows its lattice's box in both panels, and a Size typed in one resizes the whole warp in both. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aWarpsLatticeBoxShowsAndEditsInBoth() =
		runComposeUiTest {
			mountShowing(WARP_W)
			onAllNodesWithText("120.0").assertCountEquals(2)
			onAllNodesWithText("-230.0").assertCountEquals(2)
			onAllNodesWithText("40.0").assertCountEquals(2)
			onAllNodesWithText("60.0").assertCountEquals(2)

			typeInto("40.0", index = 0, typed = "80")
			onAllNodesWithText("80.0").assertCountEquals(2)
			onAllNodesWithText("120.0").assertCountEquals(2)
		}

	/**
	 * A rotation shows its pivot and its own Angle and Scale read-only, above a Base Angle that still edits,
	 * and the Angle follows the edit.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aRotationShowsItsPivotAngleAndScaleAboveAnEditableBaseAngle() =
		runComposeUiTest {
			val session = mountShowing(ROTATION_R)
			onAllNodesWithText("30.0").assertCountEquals(2)
			onAllNodesWithText("-70.0").assertCountEquals(2)
			onAllNodesWithText("15.0").assertCountEquals(2)
			onAllNodesWithText("1.00").assertCountEquals(2)
			assertReadOnly("15.0", index = 1)

			typeInto("10.0", index = 1, typed = "20")
			runOnIdle { assertEquals(20f, (session.model.value.deformers.first { deformer -> deformer.id == ROTATION_R.id } as Deformer.Rotation).baseAngle) }
			onAllNodesWithText("25.0").assertCountEquals(2)
		}

	/** The read-only values are measured at the pose, so moving it moves them in both panels. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theReadOnlyValuesFollowThePose() =
		runComposeUiTest {
			val session = mountShowing(ROTATION_R)
			runOnIdle { session.commitPose(ParameterChange.SetValue(listOf(PARAMETER)), mapOf(PARAMETER to 0.5f)) }
			waitForIdle()
			onAllNodesWithText("35.0").assertCountEquals(2)
			onAllNodesWithText("1.50").assertCountEquals(2)
		}
}