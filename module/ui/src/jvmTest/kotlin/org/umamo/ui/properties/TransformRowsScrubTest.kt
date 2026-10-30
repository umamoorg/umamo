package org.umamo.ui.properties

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Pose
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.transform.MeshBounds
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.kit.field.LocalScrubCancel
import org.umamo.ui.kit.field.ScrubCancelController
import org.umamo.ui.kit.field.formatDecimals
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.transform.drawableWorldTransform
import org.umamo.ui.viewport.uv.RecordingPuppetRenderSync
import org.umamo.ui.workspace.operationstrip.LocalOperationStripArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Object tab's Transform rows under real pointer input: a scrub reaches the renderer frame by frame and
 * the session once, at release; a locked Size scrub carries its partner field along; and Edit mode shows,
 * and edits, the rig at rest.
 *
 * Driven through a composition because each half lives somewhere different - the preview in the row's
 * scrub helper over the render-sync seam, the partner in the Size stack's own state, the pose and mode in
 * the session's flows - and only a real drag runs them in the order they meet.
 */
@OptIn(ExperimentalTestApi::class)
class TransformRowsScrubTest {
	private val drawableId = DrawableId("d")
	private val rotationId = DeformerId("rot")
	private val parameterId = ParameterId("Param")
	private val warpId = DeformerId("w")
	private val drawableTarget = SelectionTarget.Drawable(drawableId)
	private val rotationTarget = SelectionTarget.Deformer(rotationId)
	private val warpTarget = SelectionTarget.Deformer(warpId)

	/** At the parameter's maximum the square sits 100 right of rest, so a posed rig shows a shape rest does not. */
	private val posed: Pose = mapOf(parameterId to 1f)

	/**
	 * A 100 x 100 square centered on world (500, -500) at rest, keyed to move 100 right at the parameter's
	 * maximum, beside a rotation deformer for the Base Angle row and a 1 x 1 warp over canvas 100..200 (centered
	 * on world (150, -150)), keyed the same at every key.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val square = floatArrayOf(450f, 450f, 550f, 450f, 550f, 550f, 450f, 550f)
		val atRest = FloatArray(square.size)
		val movedRight = FloatArray(square.size) { componentIndex -> if (componentIndex % 2 == 0) 100f else 0f }

		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "Param", min = -1f, max = 1f, default = 0f)),
			parts = emptyList(),
			deformers =
				listOf(
					Deformer.Rotation(id = rotationId, name = "rot", parent = null, partId = null, baseAngle = 15f, geometryGrid = null),
					Deformer.Warp(
						id = warpId,
						name = "w",
						parent = null,
						partId = null,
						rows = 1,
						columns = 1,
						isQuadTransform = true,
						geometryGrid =
							KeyformGrid(
								axes = listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 1f))),
								cells =
									listOf(-1, 1).mapIndexed { keyIndex, _ ->
										KeyformCell(intArrayOf(keyIndex), WarpLatticeForm(floatArrayOf(100f, 100f, 200f, 100f, 100f, 200f, 200f, 200f)))
									},
							),
					),
				),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "d",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh.withLocalEqualToCanvas(square, FloatArray(square.size), intArrayOf(0, 1, 2, 0, 2, 3)),
						geometryGrid =
							KeyformGrid(
								axes = listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 0f, 1f))),
								cells =
									listOf(
										KeyformCell(intArrayOf(0), MeshDeltaForm(atRest.copyOf())),
										KeyformCell(intArrayOf(1), MeshDeltaForm(atRest.copyOf())),
										KeyformCell(intArrayOf(2), MeshDeltaForm(movedRight)),
									),
							),
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * A session over [model] with [target] selected, as the Properties panel only shows it.
	 *
	 * @param SelectionTarget target The active item.
	 * @param Pose pose The rig's pose.
	 * @return EditorSession The session, in Object mode.
	 */
	private fun sessionSelecting(target: SelectionTarget, pose: Pose = emptyMap()): EditorSession {
		val session = EditorSession(model(), initialPose = pose)
		session.setSelection(Selection(setOf(target), target))

		return session
	}

	/** A Position scrub moves the render with every frame, and only the release reaches the session - once. */
	@Test
	fun aPositionScrubPreviewsEachFrameAndCommitsOnceOnRelease() =
		runComposeUiTest {
			val session = sessionSelecting(drawableTarget)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, drawableTarget, renderSync)
			val before = session.model.value

			pressAndScrub(pointOfText(REST_POSITION_X))

			assertTrue(renderSync.previewed.isNotEmpty(), "the renderer has to see the scrub as it happens")
			assertSame(before, session.model.value, "a preview must never reach the session")
			assertEquals(0, renderSync.resyncs, "nothing hands the renderer back mid-drag")
			val lastFrame = worldBoundsOf(renderSync.previewed.last())
			assertTrue(lastFrame.centerX > 500f, "a rightward scrub moves the mesh right")

			releaseScrub()

			assertEquals(lastFrame.centerX, worldBoundsOf(session.model.value).centerX, 1e-3f, "the release lands what the last frame showed")
			assertEquals(1, renderSync.resyncs, "the release hands the renderer back to the session")
			assertEquals(STRIP_AREA, session.adjustableOperation.value?.areaId, "and registers the strip where the shell's routing says")
			runOnIdle { session.undo() }
			assertSame(before, session.model.value, "the whole drag is one undo step")
		}

	/** With the aspect locked, scrubbing one Size field moves the other while the drag is still held. */
	@Test
	fun aLockedSizeScrubCarriesItsPartnerAlong() =
		runComposeUiTest {
			val session = sessionSelecting(drawableTarget)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, drawableTarget, renderSync)
			onNodeWithContentDescription(LOCK_ASPECT).performClick()

			pressAndScrub(pointOfText(REST_SIZE, index = 0))

			val lastFrame = worldBoundsOf(renderSync.previewed.last())
			assertTrue(lastFrame.width > 100f, "a rightward scrub grows the width")
			assertEquals(lastFrame.width, lastFrame.height, 1e-3f, "the locked height scales with it")
			onAllNodesWithText(formatDecimals(lastFrame.width, 1)).assertCountEquals(2)
			onAllNodesWithText(REST_SIZE).assertCountEquals(0)

			releaseScrub()

			val committed = worldBoundsOf(session.model.value)
			assertEquals(lastFrame.width, committed.width, 1e-3f)
			assertEquals(lastFrame.height, committed.height, 1e-3f)
		}

	/** Unlocked, the partner holds still while the other field is scrubbed. */
	@Test
	fun anUnlockedSizeScrubLeavesItsPartnerAlone() =
		runComposeUiTest {
			val session = sessionSelecting(drawableTarget)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, drawableTarget, renderSync)

			pressAndScrub(pointOfText(REST_SIZE, index = 0))

			assertEquals(100f, worldBoundsOf(renderSync.previewed.last()).height, 1e-3f)
			onAllNodesWithText(REST_SIZE).assertCountEquals(1)
			releaseScrub()
		}

	/** Posed, the rows show the posed shape and are inert; Edit mode shows the rest shape and is inert too. */
	@Test
	fun editModeShowsTheRestShapeButOnlyObjectModeWrites() =
		runComposeUiTest {
			val session = sessionSelecting(drawableTarget, posed)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, drawableTarget, renderSync)
			val before = session.model.value

			pressAndScrub(pointOfText(POSED_POSITION_X))
			releaseScrub()
			assertTrue(renderSync.previewed.isEmpty(), "posed, the fields are inert")
			assertSame(before, session.model.value)

			runOnIdle { session.setMode(EditorMode.Edit) }
			waitForIdle()
			onNodeWithText(REST_POSITION_X).assertExists()

			pressAndScrub(pointOfText(REST_POSITION_X))
			releaseScrub()

			assertTrue(renderSync.previewed.isEmpty(), "Edit mode shapes interiors, so the rows do not write")
			assertSame(before, session.model.value)
			assertEquals(posed, session.pose.value, "the pinned pose is held as it is")
		}

	/** A warp's Position scrub moves the whole warp in the render as it goes, and commits once on release. */
	@Test
	fun aWarpPositionScrubMovesTheWholeWarpAndCommitsOnce() =
		runComposeUiTest {
			val session = sessionSelecting(warpTarget)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, warpTarget, renderSync)
			val before = session.model.value

			pressAndScrub(pointOfText(WARP_POSITION_X))

			assertTrue(renderSync.previewed.isNotEmpty(), "the renderer sees the scrub as it happens")
			assertSame(before, session.model.value, "a preview never reaches the session")
			val lastFrame = warpCenterX(renderSync.previewed.last())
			assertTrue(lastFrame > 150f, "a rightward scrub moves the warp right")

			releaseScrub()

			assertEquals(lastFrame, warpCenterX(session.model.value), 1e-3f, "the release lands what the last frame showed")
			assertEquals(STRIP_AREA, session.adjustableOperation.value?.areaId, "and registers the strip")
			assertEquals("change.object.move", session.historyView.value.steps.last().labelKey)
			runOnIdle { session.undo() }
			assertSame(before, session.model.value, "the whole drag is one undo step")
		}

	/** A warp's rows move it only in Object mode at rest: posed they are inert, and so they are in Edit mode. */
	@Test
	fun aWarpsRowsAreInertPosedAndInEditMode() =
		runComposeUiTest {
			val session = sessionSelecting(warpTarget, posed)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, warpTarget, renderSync)
			val before = session.model.value

			pressAndScrub(pointOfText(WARP_POSITION_X))
			releaseScrub()
			assertTrue(renderSync.previewed.isEmpty(), "posed, the fields are inert")

			runOnIdle { session.setMode(EditorMode.Edit) }
			waitForIdle()
			pressAndScrub(pointOfText(WARP_POSITION_X))
			releaseScrub()
			assertTrue(renderSync.previewed.isEmpty(), "Edit mode shapes a lattice without moving the art, so the rows do not move the warp")
			assertSame(before, session.model.value)
		}

	/** A warp the pose does not draw has no box: one note stands for the pair of rows, not one each. */
	@Test
	fun aWarpThePoseHidesShowsOneNoteForBothRows() =
		runComposeUiTest {
			val session = sessionSelecting(warpTarget, mapOf(parameterId to 2f))
			mountTransformRows(session, warpTarget, RecordingPuppetRenderSync())

			onAllNodesWithText(UNAVAILABLE_NOTE).assertCountEquals(1)
		}

	/** Pose mode poses the warp rather than moving it, so its rows are inert there as in Edit mode. */
	@Test
	fun aWarpsRowsAreInertInPoseMode() =
		runComposeUiTest {
			val session = sessionSelecting(warpTarget)
			runOnIdle { session.setMode(EditorMode.Pose) }
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, warpTarget, renderSync)
			val before = session.model.value

			pressAndScrub(pointOfText(WARP_POSITION_X))
			releaseScrub()

			assertTrue(renderSync.previewed.isEmpty(), "Pose mode poses the warp, so the rows do not move it")
			assertSame(before, session.model.value)
		}

	/** A Base Angle scrub turns the deformer in the render as it goes, and commits once. */
	@Test
	fun aBaseAngleScrubPreviewsEachFrameAndCommitsOnce() =
		runComposeUiTest {
			val session = sessionSelecting(rotationTarget)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, rotationTarget, renderSync)
			val before = session.model.value

			pressAndScrub(pointOfText(BASE_ANGLE))

			val lastFrameAngle = baseAngleOf(renderSync.previewed.last())
			assertTrue(lastFrameAngle > 15f, "a rightward scrub turns the deformer")
			assertSame(before, session.model.value, "a preview must never reach the session")

			releaseScrub()

			assertEquals(lastFrameAngle, baseAngleOf(session.model.value))
			assertEquals(1, renderSync.resyncs)
			runOnIdle { session.undo() }
			assertSame(before, session.model.value, "the whole drag is one undo step")
		}

	/** A row that leaves the composition mid-drag cancels its scrub: the renderer goes back, nothing lands. */
	@Test
	fun unmountingMidScrubCancelsTheScrub() =
		runComposeUiTest {
			val session = sessionSelecting(drawableTarget)
			val renderSync = RecordingPuppetRenderSync()
			val mounted = mutableStateOf(true)
			mountTransformRows(session, drawableTarget, renderSync, mounted)
			val before = session.model.value

			pressAndScrub(pointOfText(REST_POSITION_X))
			assertTrue(renderSync.previewed.isNotEmpty(), "precondition: the scrub previewed")
			runOnIdle { mounted.value = false }
			waitForIdle()

			assertEquals(1, renderSync.resyncs, "the renderer goes back to the session exactly once")
			assertNull(renderSync.preview.value, "and is not left on the preview")
			releaseScrub()
			assertSame(before, session.model.value, "a drag taken from under its field lands nothing")
		}

	/** Cancelling through the shell's seam mid-drag resyncs, commits nothing, and the locked partner snaps back. */
	@Test
	fun cancellingThroughTheSeamRestoresBothSizeFields() =
		runComposeUiTest {
			val session = sessionSelecting(drawableTarget)
			val renderSync = RecordingPuppetRenderSync()
			val scrubCancel = ScrubCancelController()
			mountTransformRows(session, drawableTarget, renderSync, scrubCancel = scrubCancel)
			onNodeWithContentDescription(LOCK_ASPECT).performClick()
			val before = session.model.value

			pressAndScrub(pointOfText(REST_SIZE, index = 0))
			assertEquals(0, onAllNodesWithText(REST_SIZE).fetchSemanticsNodes().size, "precondition: both fields moved")
			val parked = scrubCancel.cancel
			assertNotNull(parked, "the scrub parks its cancel for Escape")
			runOnIdle { parked() }
			waitForIdle()

			onAllNodesWithText(REST_SIZE).assertCountEquals(2)
			assertEquals(1, renderSync.resyncs)
			assertNull(scrubCancel.cancel, "the slot is released with the scrub")
			releaseScrub()

			assertSame(before, session.model.value, "a cancelled scrub commits nothing")
			assertEquals(1, renderSync.resyncs, "and its release resyncs nothing more")
		}

	/** Leaving Object mode mid-drag disables the field; its preview goes and nothing lands. */
	@Test
	fun leavingObjectModeMidScrubDropsThePreview() =
		runComposeUiTest {
			val session = sessionSelecting(drawableTarget)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, drawableTarget, renderSync)
			val before = session.model.value

			pressAndScrub(pointOfText(REST_POSITION_X))
			assertTrue(renderSync.previewed.isNotEmpty(), "precondition: the scrub previewed")
			runOnIdle { session.setMode(EditorMode.Edit) }
			waitForIdle()

			assertEquals(1, renderSync.resyncs, "the disabled field's preview must go")
			releaseScrub()
			assertSame(before, session.model.value, "a field disabled mid-drag commits nothing")
		}

	/** A typed entry commits without ever previewing, so it never resyncs over another surface's preview. */
	@Test
	fun aTypedEntryNeverTouchesTheRenderSync() =
		runComposeUiTest {
			val session = sessionSelecting(drawableTarget)
			val renderSync = RecordingPuppetRenderSync()
			mountTransformRows(session, drawableTarget, renderSync)

			clickAt(pointOfText(REST_POSITION_X))
			onNode(hasSetTextAction() and isFocused()).performTextReplacement("42")
			onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()

			assertEquals(42f, worldBoundsOf(session.model.value).centerX, 1e-3f, "the entry commits")
			assertTrue(renderSync.previewed.isEmpty())
			assertEquals(0, renderSync.resyncs)
		}

	/**
	 * Mounts the Transform section's rows for [target] the way the Properties panel draws them.
	 *
	 * @param EditorSession session The session the rows edit.
	 * @param SelectionTarget target The active item.
	 * @param RecordingPuppetRenderSync renderSync The render-sync stand-in the previews reach.
	 * @param MutableState mounted Whether the rows are in the composition; flipping it unmounts them.
	 * @param ScrubCancelController scrubCancel The shell's scrub seam, so a test can cancel the way Escape does.
	 */
	private fun ComposeUiTest.mountTransformRows(
		session: EditorSession,
		target: SelectionTarget,
		renderSync: RecordingPuppetRenderSync,
		mounted: MutableState<Boolean> = mutableStateOf(true),
		scrubCancel: ScrubCancelController = ScrubCancelController(),
	) {
		setContent {
			UmamoTheme {
				val puppet by session.model.collectAsState()
				CompositionLocalProvider(
					LocalPuppet provides puppet,
					LocalEditorSession provides session,
					LocalPuppetRenderSync provides renderSync,
					LocalScrubCancel provides scrubCancel,
					LocalOperationStripArea provides { STRIP_AREA },
				) {
					Column(modifier = Modifier.size(width = 400.dp, height = 200.dp).testTag(ROWS_TAG)) {
						if (mounted.value) {
							val context = PropertyContext(puppet, Selection(setOf(target), target), target, session)

							for (row in TransformSection.rows(context)) {
								row.content(context)
							}
						}
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * The center of the [index]th node showing [text], in the rows' own pixels.
	 *
	 * @param String text The text the node shows.
	 * @param Int index Which of the nodes showing it, in composition order.
	 * @return Offset The point.
	 */
	private fun ComposeUiTest.pointOfText(text: String, index: Int = 0): Offset {
		val rows = onNodeWithTag(ROWS_TAG).fetchSemanticsNode().boundsInRoot
		val field = onAllNodesWithText(text)[index].fetchSemanticsNode().boundsInRoot

		return field.center - rows.topLeft
	}

	/**
	 * Presses at [from] and drags [SCRUB_PIXELS] right, leaving the button held.
	 *
	 * @param Offset from Where the press lands, in the rows' pixels.
	 */
	private fun ComposeUiTest.pressAndScrub(from: Offset) {
		onNodeWithTag(ROWS_TAG).performMouseInput {
			advanceEventTime(GESTURE_GAP_MILLIS)
			moveTo(from)
			press()

			for (stepIndex in 1..SCRUB_STEPS) {
				advanceEventTime(GESTURE_STEP_MILLIS)
				moveTo(from + Offset(SCRUB_PIXELS * stepIndex / SCRUB_STEPS, 0f))
			}
		}
		waitForIdle()
	}

	/** Releases the button a [pressAndScrub] left held. */
	private fun ComposeUiTest.releaseScrub() {
		onNodeWithTag(ROWS_TAG).performMouseInput {
			advanceEventTime(GESTURE_STEP_MILLIS)
			release()
		}
		waitForIdle()
	}

	/**
	 * A single click at [point].
	 *
	 * @param Offset point Where to click, in the rows' pixels.
	 */
	private fun ComposeUiTest.clickAt(point: Offset) {
		onNodeWithTag(ROWS_TAG).performMouseInput {
			advanceEventTime(GESTURE_GAP_MILLIS)
			moveTo(point)
			press()
			advanceEventTime(GESTURE_STEP_MILLIS)
			release()
		}
		waitForIdle()
	}

	/**
	 * The drawable's world bounds in [model] at rest, which is where every frame these tests check is shown.
	 *
	 * @param PuppetModel model The model.
	 * @return MeshBounds The bounds.
	 */
	private fun worldBoundsOf(model: PuppetModel): MeshBounds = drawableWorldTransform(model, emptyMap(), drawableId, EditorMode.Object)!!.bounds

	/**
	 * The rotation deformer's base angle in [model].
	 *
	 * @param PuppetModel model The model.
	 * @return Float The angle in degrees.
	 */
	private fun baseAngleOf(model: PuppetModel): Float =
		(model.deformers.first { deformer -> deformer.id == rotationId } as Deformer.Rotation).baseAngle

	/**
	 * The world x of the warp's lattice center at rest in [model].
	 *
	 * @param PuppetModel model The model.
	 * @return Float The center's world x.
	 */
	private fun warpCenterX(model: PuppetModel): Float {
		val world = assertNotNull(warpLatticeAt(model, emptyMap(), warpId)).world

		return (0 until world.size / 2).sumOf { pointIndex -> world[pointIndex * 2].toDouble() }.toFloat() / (world.size / 2)
	}

	private companion object {
		const val ROWS_TAG = "transformRows"
		const val STRIP_AREA = "viewport-1"
		const val LOCK_ASPECT = "Lock Aspect Ratio"

		/** Position X at rest, and with the rig posed 100 to the right. */
		const val REST_POSITION_X = "500.0"
		const val POSED_POSITION_X = "600.0"

		/** Both Size fields at rest. */
		const val REST_SIZE = "100.0"

		const val BASE_ANGLE = "15.0"

		/** The warp's Position X: its lattice's center, at rest and posed alike. */
		const val WARP_POSITION_X = "150.0"

		/** The note a Transform row shows for an item with no box at the pose. */
		const val UNAVAILABLE_NOTE = "This item has no placement to show."

		/** Far enough past the drag slop to move every field by several steps. */
		const val SCRUB_PIXELS = 90f
		const val SCRUB_STEPS = 6

		const val GESTURE_STEP_MILLIS = 16L
		const val GESTURE_GAP_MILLIS = 1_000L
	}
}