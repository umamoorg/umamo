package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins how a session opens on the state a document was saved with (docs/format/UMA.md § 7.4): it is where the
 * history STARTS - never an undo step, never a dirty mark - it is fitted to the model first, and what the session
 * gathers for the next save is what it was opened with.
 */
class SessionViewStateTest {
	/**
	 * A drawable with or without a mesh.
	 *
	 * @param String  id     The drawable id.
	 * @param Boolean meshed Whether it carries a one-triangle mesh.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, meshed: Boolean): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = if (meshed) DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), FloatArray(6), intArrayOf(0, 1, 2)) else null,
			geometryGrid = null,
		)

	/**
	 * A model of one part, the given drawables at the root, and one parameter.
	 *
	 * @param List drawables The model's drawables.
	 * @return PuppetModel The model.
	 */
	private fun model(drawables: List<Drawable>): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f)),
			parts = listOf(Part(PartId("face"), "Face", children = emptyList())),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = listOf(OrgChild.Part(PartId("face"))) + drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
		).withDerivedRenderRoot()

	private val meshedModel = model(listOf(drawable("a", meshed = true), drawable("b", meshed = true)))

	/** A restored selection, parameter target, and tool state are the session's opening state, not steps on top of it. */
	@Test
	fun aSavedStateIsWhereTheHistoryStarts() {
		val saved =
			SessionViewState(
				selection = Selection(setOf(SelectionTarget.Drawable(DrawableId("a")), SelectionTarget.Part(PartId("face"))), SelectionTarget.Drawable(DrawableId("a"))),
				parameterSelection = ParameterSelection(setOf(ParameterId("ParamAngleX")), ParameterId("ParamAngleX")),
				cursor2d = Cursor2d(12f, -8f),
				uvCursor = UvCursor(0.25f, 0.75f),
				pivotMode = TransformPivotMode.Cursor,
				proportionalEnabled = true,
				proportionalSettings = ProportionalEditState(ProportionalFalloff.Sharp, 64f, connectedOnly = true),
				parameterControls = mapOf(ParameterId("ParamAngleX") to ParameterControl.Dial),
			)

		val session = EditorSession(meshedModel, initialViewState = saved)

		assertEquals(saved.selection, session.selection.value)
		assertEquals(saved.parameterSelection, session.parameterSelection.value)
		assertEquals(Cursor2d(12f, -8f), session.cursor2d.value)
		assertEquals(UvCursor(0.25f, 0.75f), session.uvCursor.value)
		assertEquals(TransformPivotMode.Cursor, session.pivotMode.value)
		assertEquals(ProportionalEditState(ProportionalFalloff.Sharp, 64f, connectedOnly = true), session.proportionalEdit.value)
		assertEquals(mapOf(ParameterId("ParamAngleX") to ParameterControl.Dial), session.parameterControls.value)
		assertFalse(session.canUndo.value, "nothing restored is an undo step")
		assertFalse(session.dirty.value, "and nothing restored is a change to the document")
		assertNull(session.notice.value, "and nothing restored posts a notice")
		assertEquals(saved, session.viewState(), "what the session gathers is what it opened with")
	}

	/** Proportional editing saved OFF keeps the configuration a toggle brings back. */
	@Test
	fun aDisabledProportionalConfigurationComesBackOnToggle() {
		val saved = SessionViewState(proportionalSettings = ProportionalEditState(ProportionalFalloff.Root, 48f))

		val session = EditorSession(meshedModel, initialViewState = saved)
		assertNull(session.proportionalEdit.value)
		session.toggleProportionalEdit()

		assertEquals(ProportionalEditState(ProportionalFalloff.Root, 48f), session.proportionalEdit.value)
	}

	/** A saved id the model no longer has is dropped, and the active falls to a target that survived. */
	@Test
	fun referencesTheModelLacksAreDropped() {
		val saved =
			SessionViewState(
				selection = Selection(setOf(SelectionTarget.Drawable(DrawableId("a")), SelectionTarget.Drawable(DrawableId("gone"))), SelectionTarget.Drawable(DrawableId("gone"))),
				parameterSelection = ParameterSelection(setOf(ParameterId("ParamGone")), ParameterId("ParamGone")),
				parameterControls = mapOf(ParameterId("ParamGone") to ParameterControl.Dial, ParameterId("ParamAngleX") to ParameterControl.Slider),
			)

		val session = EditorSession(meshedModel, initialViewState = saved)

		assertEquals(Selection(setOf(SelectionTarget.Drawable(DrawableId("a"))), SelectionTarget.Drawable(DrawableId("a"))), session.selection.value)
		assertTrue(session.parameterSelection.value.isEmpty)
		assertEquals(mapOf(ParameterId("ParamAngleX") to ParameterControl.Slider), session.parameterControls.value)
	}

	/** Picking a parameter's control is no undo step and no change to the document, and following again clears it. */
	@Test
	fun aParameterControlIsNeitherAStepNorAChange() {
		val session = EditorSession(meshedModel)
		val angleX = ParameterId("ParamAngleX")
		val published = session.parameterControls.value

		session.setParameterControl(angleX, null)
		assertSame(published, session.parameterControls.value, "following a parameter that already follows publishes nothing")

		session.setParameterControl(angleX, ParameterControl.Dial)
		assertEquals(mapOf(angleX to ParameterControl.Dial), session.parameterControls.value)
		assertEquals(mapOf(angleX to ParameterControl.Dial), session.viewState().parameterControls, "a save gathers it")

		val picked = session.parameterControls.value
		session.setParameterControl(angleX, ParameterControl.Dial)
		assertSame(picked, session.parameterControls.value, "picking the same control again publishes nothing")

		session.setParameterControl(angleX, null)
		assertTrue(session.parameterControls.value.isEmpty(), "following the preference again leaves no entry")
		assertFalse(session.canUndo.value, "a pick is no undo step")
		assertFalse(session.dirty.value, "and no change to the document")
	}

	/** A saved Edit mode opens the session in Edit over the restored selection, with the saved select mode. */
	@Test
	fun aSavedEditModeOpensOverTheRestoredSelection() {
		val saved =
			SessionViewState(
				selection = Selection(setOf(SelectionTarget.Drawable(DrawableId("b"))), SelectionTarget.Drawable(DrawableId("b"))),
				mode = EditorMode.Edit,
				selectMode = MeshSelectMode.Face,
			)

		val session = EditorSession(meshedModel, initialViewState = saved)

		assertEquals(EditorMode.Edit, session.mode.value)
		assertEquals(listOf(DrawableId("b")), session.meshSelection.value.drawableIds)
		assertEquals(DrawableId("b"), session.meshSelection.value.activeDrawableId)
		assertEquals(MeshSelectMode.Face, session.meshSelection.value.selectMode)
		assertFalse(session.canUndo.value, "opening in Edit is not a mode-change step")

		session.setMode(EditorMode.Object)
		assertTrue(session.canUndo.value, "leaving it is an ordinary step")
		session.undo()
		assertEquals(EditorMode.Edit, session.mode.value, "and undo returns to the mode the document opened in")
	}

	/** The select mode is saved and opened in Object mode too, so the next Edit entry selects in it. */
	@Test
	fun theSelectModeHoldsInObjectMode() {
		val editing = EditorSession(meshedModel)
		editing.setSelection(Selection(setOf(SelectionTarget.Drawable(DrawableId("b"))), SelectionTarget.Drawable(DrawableId("b"))))
		editing.setMode(EditorMode.Edit)
		editing.setMeshSelectMode(MeshSelectMode.Edge)
		editing.setMode(EditorMode.Object)
		assertEquals(MeshSelectMode.Edge, editing.viewState().selectMode, "a save made in Object mode keeps it")

		val saved =
			SessionViewState(
				selection = Selection(setOf(SelectionTarget.Drawable(DrawableId("b"))), SelectionTarget.Drawable(DrawableId("b"))),
				selectMode = MeshSelectMode.Face,
			)
		val opened = EditorSession(meshedModel, initialViewState = saved)
		assertEquals(EditorMode.Object, opened.mode.value)

		opened.setMode(EditorMode.Edit)
		assertEquals(MeshSelectMode.Face, opened.meshSelection.value.selectMode)
	}

	/** A saved Edit mode the model cannot satisfy opens in Object, the refusal setMode makes. */
	@Test
	fun anEditModeWithNothingEditableOpensInObject() {
		val session = EditorSession(model(listOf(drawable("flat", meshed = false))), initialViewState = SessionViewState(mode = EditorMode.Edit))

		assertEquals(EditorMode.Object, session.mode.value)
		assertTrue(session.meshSelection.value.drawableIds.isEmpty())
	}

	/** A saved Pose mode opens in Pose over a model with a deformer, and undo has no step to take back. */
	@Test
	fun aSavedPoseModeOpensInPose() {
		val warp = Deformer.Warp(DeformerId("w"), "W", parent = null, partId = null, rows = 1, columns = 1, isQuadTransform = true, geometryGrid = null)
		val session = EditorSession(meshedModel.copy(deformers = listOf(warp)), initialViewState = SessionViewState(mode = EditorMode.Pose))

		assertEquals(EditorMode.Pose, session.mode.value)
		assertFalse(session.canUndo.value, "the opening mode is where the history starts")
	}

	/** A saved Pose mode whose selection holds no warp opens on the first warp the pose draws, selected as entry selects it. */
	@Test
	fun aSavedPoseModeSelectsTheWarpItFallsBackTo() {
		val saved = SessionViewState(selection = SelectionOps.replace(SelectionTarget.Drawable(PoseLatticeTestRig.art)), mode = EditorMode.Pose)

		val session = EditorSession(PoseLatticeTestRig.model(), initialViewState = saved)

		assertEquals(EditorMode.Pose, session.mode.value)
		assertEquals(SelectionOps.replace(SelectionTarget.Deformer(PoseLatticeTestRig.cage)), session.selection.value)
		assertFalse(session.canUndo.value, "the opening selection is where the history starts")
	}

	/** A saved Edit mode whose selection holds no mesh opens on the topmost one, selected as entry selects it. */
	@Test
	fun aSavedEditModeSelectsTheDrawableItFallsBackTo() {
		val saved = SessionViewState(selection = SelectionOps.replace(SelectionTarget.Part(PartId("face"))), mode = EditorMode.Edit)

		val session = EditorSession(meshedModel, initialViewState = saved)

		assertEquals(EditorMode.Edit, session.mode.value)
		assertEquals(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("a"))), session.selection.value)
	}

	/** A saved Pose mode the model cannot satisfy - nothing to pose - opens in Object, the refusal setMode makes. */
	@Test
	fun aPoseModeWithNoDeformerOpensInObject() {
		val session = EditorSession(meshedModel, initialViewState = SessionViewState(mode = EditorMode.Pose))

		assertEquals(EditorMode.Object, session.mode.value)
	}

	/** A plain open is untouched: no saved state means the defaults the session always had. */
	@Test
	fun aPlainOpenKeepsTheDefaults() {
		val session = EditorSession(meshedModel)

		assertEquals(SessionViewState(proportionalSettings = DEFAULT_PROPORTIONAL_EDIT_STATE), session.viewState())
	}
}