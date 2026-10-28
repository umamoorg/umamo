package org.umamo.ui.workspace.operationstrip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.properties.setDrawableWorldSizeAdjustable
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.changeLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The test tag on the box the host is mounted in. */
private const val BODY_TAG = "areaBody"

/** The id of the area the host is mounted as, and the area the strip's record names. */
private const val AREA_ID = "viewport-1"

/** The value the Scale X field shows after the Size edit, a point on the strip's first row. */
private const val SCALE_X_SHOWN = "0.50"

/** How far into the strip's 12 dp row indent the indent clicks land. */
private const val INDENT_DEPTH = 6f

/** How far right of the strip the surface clicks land. */
private const val SURFACE_GAP = 20f

/**
 * The center of a node's bounds, in the root's px at density 1.
 *
 * @return Offset The center.
 */
private fun DpRect.center(): Offset = Offset((left.value + right.value) / 2f, (top.value + bottom.value) / 2f)

/** A stand-in for the work surface under the strip: it counts the presses that reach it. */
private class PressRecorder {
	/** The presses that reached the surface. */
	var presses = 0
}

/**
 * The operation strip stops the pointer from reaching the work surface under it, the empty indent beside its
 * rows included, and lets it through again while one of its area's gestures owns the pointer.
 *
 * A press is claimed among siblings by the first one with a pointer node under the pointer, so a strip with no
 * pointer node of its own would hand a click on its empty body to the surface beneath, where an Object-mode
 * click deselects and the history step it pushes takes the strip away.  These cases mount the real host over a
 * recording surface after a Size edit registered its Scale.
 */
@OptIn(ExperimentalTestApi::class)
class OperationStripPointerTest {
	private val drawableId = DrawableId("d")

	/**
	 * A 100 x 100 square centered on canvas (500, 500).
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val square = floatArrayOf(450f, 450f, 550f, 450f, 550f, 550f, 450f, 550f)

		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "d",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh.withLocalEqualToCanvas(square, FloatArray(square.size), intArrayOf(0, 1, 2, 0, 2, 3)),
						geometryGrid = null,
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * A session whose last operation is a Size edit in [AREA_ID], so the strip shows there.
	 *
	 * @return EditorSession The session.
	 */
	private fun sessionAfterASizeEdit(): EditorSession {
		val session = EditorSession(model())
		session.setDrawableWorldSizeAdjustable(drawableId, 50f, 25f, AREA_ID)
		assertNotNull(session.adjustableOperation.value, "precondition: the Size edit registered the strip")

		return session
	}

	/**
	 * Mounts the host as a 600 x 400 2D viewport area over a recording surface, at density 1, with the strip
	 * expanded.
	 *
	 * @param EditorSession session The session.
	 * @param OperationStripState strip The window's strip state.
	 * @param PressRecorder recorder The surface stand-in.
	 * @return String The strip's heading, which the header node is found by.
	 */
	private fun ComposeUiTest.mountHost(session: EditorSession, strip: OperationStripState, recorder: PressRecorder): String {
		var heading = ""
		setContent {
			UmamoTheme {
				heading = changeLabel(assertNotNull(session.adjustableOperation.value).change.labelKey)
				CompositionLocalProvider(
					LocalDensity provides Density(1f),
					LocalEditorSession provides session,
					LocalOperationStrip provides strip,
				) {
					Box(modifier = Modifier.size(600.dp, 400.dp).testTag(BODY_TAG)) {
						OperationStripHost(areaId = AREA_ID, kind = SpaceKind.Viewport2D) {
							Box(
								modifier =
									Modifier.fillMaxSize().pointerInput(Unit) {
										awaitPointerEventScope {
											while (true) {
												if (awaitPointerEvent().type == PointerEventType.Press) {
													recorder.presses++
												}
											}
										}
									},
							)
						}
					}
				}
			}
		}
		waitForIdle()

		return heading
	}

	/**
	 * A point in the strip's row indent, level with its first row and clear of every control.
	 *
	 * @param String heading The strip's heading.
	 * @return Offset The point, in the body's px.
	 */
	private fun ComposeUiTest.inTheIndent(heading: String): Offset {
		val header = onNodeWithText(heading).getUnclippedBoundsInRoot()
		val row = onAllNodesWithText(SCALE_X_SHOWN)[0].getUnclippedBoundsInRoot()

		return Offset(header.left.value + INDENT_DEPTH, row.center().y)
	}

	/**
	 * A point on the surface just right of the strip, level with its header.
	 *
	 * @param String heading The strip's heading.
	 * @return Offset The point, in the body's px.
	 */
	private fun ComposeUiTest.rightOfTheStrip(heading: String): Offset {
		val header = onNodeWithText(heading).getUnclippedBoundsInRoot()

		return Offset(header.right.value + SURFACE_GAP, header.center().y)
	}

	/**
	 * Clicks the primary button at [point] in the body.
	 *
	 * @param Offset point Where to click, in the body's px.
	 */
	private fun ComposeUiTest.clickAt(point: Offset) {
		onNodeWithTag(BODY_TAG).performMouseInput { click(point) }
		waitForIdle()
	}

	/** The control the other cases lean on: the surface beside the strip receives a press. */
	@Test
	fun theSurfaceBesideTheStripReceivesAPress() =
		runComposeUiTest {
			val recorder = PressRecorder()
			val heading = mountHost(sessionAfterASizeEdit(), OperationStripState().also { state -> state.expanded = true }, recorder)
			clickAt(rightOfTheStrip(heading))
			assertEquals(1, recorder.presses, "the surface beside the strip is the surface's")
		}

	/** A click in the indent beside the strip's rows stays with the strip, and the strip stays. */
	@Test
	fun theRowIndentSwallowsAPress() =
		runComposeUiTest {
			val recorder = PressRecorder()
			val session = sessionAfterASizeEdit()
			val heading = mountHost(session, OperationStripState().also { state -> state.expanded = true }, recorder)
			clickAt(inTheIndent(heading))
			assertEquals(0, recorder.presses, "the click in the indent did not reach the surface")
			assertNotNull(session.adjustableOperation.value, "the strip's record survives the click")
		}

	/** The header folds the strip without the click reaching the surface. */
	@Test
	fun theHeaderFoldsTheStripWithoutReachingTheSurface() =
		runComposeUiTest {
			val recorder = PressRecorder()
			val strip = OperationStripState().also { state -> state.expanded = true }
			val heading = mountHost(sessionAfterASizeEdit(), strip, recorder)
			clickAt(onNodeWithText(heading).getUnclippedBoundsInRoot().center())
			assertFalse(strip.expanded, "the header folded the strip")
			assertEquals(0, recorder.presses, "the click on the header did not reach the surface")
		}

	/**
	 * While a gesture of this area owns the pointer, the strip steps aside: clicks over it reach the surface,
	 * which confirms the gesture there, and the header under one folds nothing.
	 */
	@Test
	fun aGestureOwnedByThisAreaPassesThroughTheStrip() =
		runComposeUiTest {
			val recorder = PressRecorder()
			val session = sessionAfterASizeEdit()
			session.armZoomRegion(AREA_ID)
			assertNotNull(session.adjustableOperation.value, "precondition: arming the gesture keeps the strip's record")
			val strip = OperationStripState().also { state -> state.expanded = true }
			val heading = mountHost(session, strip, recorder)
			clickAt(inTheIndent(heading))
			assertEquals(1, recorder.presses, "the armed Zoom Region receives the click over the indent")
			clickAt(onNodeWithText(heading).getUnclippedBoundsInRoot().center())
			assertEquals(2, recorder.presses, "the armed Zoom Region receives the click over the header")
			assertTrue(strip.expanded, "the header under the click folded nothing")
		}

	/** A gesture owned by another area leaves this area's strip blocking. */
	@Test
	fun aGestureInAnotherAreaKeepsTheStripBlocking() =
		runComposeUiTest {
			val recorder = PressRecorder()
			val session = sessionAfterASizeEdit()
			session.armZoomRegion("area-2")
			val heading = mountHost(session, OperationStripState().also { state -> state.expanded = true }, recorder)
			clickAt(inTheIndent(heading))
			assertEquals(0, recorder.presses, "another area's gesture does not open this area's strip")
		}
}