package org.umamo.edit

import org.umamo.runtime.model.BlendMode
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
			mesh = if (meshed) DrawableMesh(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), FloatArray(6), intArrayOf(0, 1, 2)) else null,
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
			)

		val session = EditorSession(meshedModel, initialViewState = saved)

		assertEquals(saved.selection, session.selection.value)
		assertEquals(saved.parameterSelection, session.parameterSelection.value)
		assertEquals(Cursor2d(12f, -8f), session.cursor2d.value)
		assertEquals(UvCursor(0.25f, 0.75f), session.uvCursor.value)
		assertEquals(TransformPivotMode.Cursor, session.pivotMode.value)
		assertEquals(ProportionalEditState(ProportionalFalloff.Sharp, 64f, connectedOnly = true), session.proportionalEdit.value)
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
			)

		val session = EditorSession(meshedModel, initialViewState = saved)

		assertEquals(Selection(setOf(SelectionTarget.Drawable(DrawableId("a"))), SelectionTarget.Drawable(DrawableId("a"))), session.selection.value)
		assertTrue(session.parameterSelection.value.isEmpty)
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

	/** A saved Edit mode the model cannot satisfy opens in Object, the refusal setMode makes. */
	@Test
	fun anEditModeWithNothingEditableOpensInObject() {
		val session = EditorSession(model(listOf(drawable("flat", meshed = false))), initialViewState = SessionViewState(mode = EditorMode.Edit))

		assertEquals(EditorMode.Object, session.mode.value)
		assertTrue(session.meshSelection.value.drawableIds.isEmpty())
	}

	/** The grid follows the application unless the document brought its own, and only a document-owned grid is gathered. */
	@Test
	fun aDocumentOwnedGridHoldsAgainstTheApplicationDefault() {
		val plain = EditorSession(meshedModel)
		assertTrue(plain.gridFollowsApplication)
		assertNull(plain.viewState().gridConfig, "a grid that merely mirrors the setting is not the document's to save")

		val owned = EditorSession(meshedModel, initialViewState = SessionViewState(gridConfig = GridConfig(50f, 4)))
		assertFalse(owned.gridFollowsApplication)
		assertEquals(GridConfig(50f, 4), owned.gridConfig.value)
		assertEquals(GridConfig(50f, 4), owned.viewState().gridConfig)
	}

	/** A plain open is untouched: no saved state means the defaults the session always had. */
	@Test
	fun aPlainOpenKeepsTheDefaults() {
		val session = EditorSession(meshedModel)

		assertEquals(SessionViewState(proportionalSettings = DEFAULT_PROPORTIONAL_EDIT_STATE), session.viewState())
	}
}