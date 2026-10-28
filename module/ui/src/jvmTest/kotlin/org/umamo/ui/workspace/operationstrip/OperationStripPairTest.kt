package org.umamo.ui.workspace.operationstrip

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorSession
import org.umamo.edit.floatValue
import org.umamo.edit.transform.MeshBounds
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.properties.setDrawableWorldSizeAdjustable
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.transform.drawableWorldTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The strip's Scale rows as one aspect-lockable pair, under real input: after a Size edit registers its Scale, a
 * typed Scale X carries Scale Z along while the lock is on and leaves it alone while it is off, and either way
 * the adjustment re-lands the drawable over the same step.
 */
@OptIn(ExperimentalTestApi::class)
class OperationStripPairTest {
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
	 * A session whose last operation is a Size edit to 50 x 25: a Scale registered with factors 0.5 and 0.25.
	 *
	 * @return EditorSession The session.
	 */
	private fun sessionAfterASizeEdit(): EditorSession {
		val session = EditorSession(model())
		session.setDrawableWorldSizeAdjustable(drawableId, 50f, 25f, STRIP_AREA)
		assertNotNull(session.adjustableOperation.value, "precondition: the Size edit registered the strip")

		return session
	}

	/**
	 * Mounts the strip for [session]'s adjustable operation, expanded.
	 *
	 * @param EditorSession session The session.
	 */
	private fun ComposeUiTest.mountStrip(session: EditorSession) {
		setContent {
			UmamoTheme {
				val strip = remember { OperationStripState().also { state -> state.expanded = true } }
				val record = session.adjustableOperation.collectAsState().value
				CompositionLocalProvider(LocalOperationStrip provides strip) {
					if (record != null) {
						OperationStrip(record, session)
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * Types [typed] into the field showing [shown].
	 *
	 * @param String shown The text the field shows.
	 * @param String typed What to type.
	 */
	private fun ComposeUiTest.typeInto(shown: String, typed: String) {
		onAllNodesWithText(shown)[0].performMouseInput {
			advanceEventTime(GESTURE_GAP_MILLIS)
			click()
		}
		waitForIdle()
		onNode(hasSetTextAction() and isFocused()).performTextReplacement(typed)
		onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
		waitForIdle()
	}

	/**
	 * The drawable's world bounds in [session]'s model.
	 *
	 * @param EditorSession session The session.
	 * @return MeshBounds The bounds.
	 */
	private fun boundsOf(session: EditorSession): MeshBounds = assertNotNull(drawableWorldTransform(session.model.value, emptyMap(), drawableId)).bounds

	/** Locked, a typed Scale X scales Scale Z by the same factor, and the drawable re-lands at both. */
	@Test
	fun aLockedScaleRowCarriesItsPartner() =
		runComposeUiTest {
			val session = sessionAfterASizeEdit()
			val stepsBefore = session.historyView.value.steps.size
			mountStrip(session)
			onNodeWithContentDescription(LOCK_ASPECT).performClick()
			waitForIdle()

			typeInto("0.50", "1")

			val record = assertNotNull(session.adjustableOperation.value)
			assertEquals(1f, record.parameters.floatValue(TransformParameterKeys.SCALE_X, Float.NaN))
			assertEquals(0.5f, record.parameters.floatValue(TransformParameterKeys.SCALE_Z, Float.NaN), "the locked partner keeps the ratio")
			assertEquals(TransformParameterKeys.SCALE_X, record.adjustedKey, "the pair adjusts as one setting, named by its first row")
			assertEquals(100f, boundsOf(session).width, 1e-3f)
			assertEquals(50f, boundsOf(session).height, 1e-3f)
			assertEquals(stepsBefore, session.historyView.value.steps.size, "the adjustment amends the Size edit's step")
		}

	/** Unlocked, a typed Scale X leaves Scale Z where the Size edit put it. */
	@Test
	fun anUnlockedScaleRowLeavesItsPartner() =
		runComposeUiTest {
			val session = sessionAfterASizeEdit()
			mountStrip(session)

			typeInto("0.50", "1")

			val record = assertNotNull(session.adjustableOperation.value)
			assertEquals(1f, record.parameters.floatValue(TransformParameterKeys.SCALE_X, Float.NaN))
			assertEquals(0.25f, record.parameters.floatValue(TransformParameterKeys.SCALE_Z, Float.NaN))
			assertEquals(100f, boundsOf(session).width, 1e-3f)
			assertEquals(25f, boundsOf(session).height, 1e-3f)
		}

	private companion object {
		const val STRIP_AREA = "viewport-1"
		const val GESTURE_GAP_MILLIS = 1000L
		const val LOCK_ASPECT = "Lock Aspect Ratio"
	}
}