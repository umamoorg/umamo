package org.umamo.edit.keyform

import org.umamo.edit.EditorSession
import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * What a move or a drag does to the keyform sheet's key selection.
 *
 * A move or drag that crosses a neighbour re-sorts the axis, so the selection has to follow the keys to the
 * ordinals they land on, and it has to ride the edit's own undo step: one undo puts back the keys AND the
 * selection, and a redo re-points it again.  A gesture that ends where it began records the selection
 * change on its own, or it would never reach history at all.  A key dragged while unselected is selected in
 * place of the selection, the way a click on it would select it.
 */
class KeyformKeyMoveSelectionTest {
	private val angleX = ParameterId("ParamAngleX")
	private val parameter = Parameter(angleX, "ParamAngleX", min = -30f, max = 30f, default = 0f)
	private val track = KeyformTrackRef.Geometry(KeyformOwner.Drawable(DrawableId("d")))

	/**
	 * A session over one drawable whose geometry is keyed at -30, 0, and 30 on ParamAngleX.
	 *
	 * @return EditorSession The session.
	 */
	private fun session(): EditorSession =
		EditorSession(
			PuppetModel(
				parameters = listOf(parameter),
				parts = emptyList(),
				deformers = emptyList(),
				drawables =
					listOf(
						Drawable(
							id = DrawableId("d"),
							name = "d",
							parentDeformerId = null,
							blendMode = BlendMode.Normal,
							maskedBy = emptyList(),
							mesh = null,
							geometryGrid =
								KeyformGrid(
									axes = listOf(KeyformAxis(angleX, floatArrayOf(-30f, 0f, 30f))),
									cells =
										listOf(
											KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(-1f, 0f))),
											KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(0f, 0f))),
											KeyformCell(intArrayOf(2), MeshDeltaForm(floatArrayOf(1f, 0f))),
										),
								),
						),
					),
				rootChildren = emptyList(),
				rootPartId = null,
			),
		)

	/**
	 * A key on the geometry row at [ordinal].
	 *
	 * @param Int ordinal The key's ordinal.
	 * @return TrackKeyRef The key.
	 */
	private fun key(ordinal: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:d/geometry", ordinal)

	/**
	 * The key [ordinal] paired with the grid key it resolves to, as the sheet hands a drag over.
	 *
	 * @param Int ordinal The key's ordinal.
	 * @return Pair The ref and its (track, parameter, ordinal).
	 */
	private fun resolved(ordinal: Int): Pair<TrackKeyRef, Triple<KeyformTrackRef, Parameter, Int>> = key(ordinal) to Triple(track, parameter, ordinal)

	/**
	 * The session's geometry key positions.
	 *
	 * @return List<Float> The positions, ascending.
	 */
	private fun EditorSession.keys(): List<Float> = model.value.drawables.single().geometryGrid!!.axes.single().keys.toList()

	/**
	 * The session's undo position.
	 *
	 * @return Int The cursor.
	 */
	private fun EditorSession.cursor(): Int = historyView.value.cursor

	/** A drag across a neighbour leaves the selection on the dragged key, and one undo reverses both. */
	@Test
	fun aDragAcrossANeighbourKeepsTheSelectionOnTheKey() {
		val session = session()
		session.setKeySelection(setOf(key(0)))
		val cursorBefore = session.cursor()

		// Three quarters of the 60-wide range carries the key at -30 to 15, past the one at 0.
		session.dragTrackKeysKeepingSelection(listOf(resolved(0)), fraction = 0.75f)

		assertEquals(listOf(0f, 15f, 30f), session.keys())
		assertEquals(setOf(key(1)), session.keySelection.value)
		assertEquals(cursorBefore + 1, session.cursor(), "the drag and the re-pointing are one step")

		session.undo()
		assertEquals(listOf(-30f, 0f, 30f), session.keys())
		assertEquals(setOf(key(0)), session.keySelection.value)

		session.redo()
		assertEquals(setOf(key(1)), session.keySelection.value, "redo re-points it again")
	}

	/**
	 * A drag held to a standstill by the range still records the selection it leaves, which is exactly the
	 * keys it was given: a selected ref the sheet could not resolve drops out.
	 */
	@Test
	fun aDragHeldAtTheWallStillRecordsTheSelection() {
		val session = session()
		val unresolved = TrackKeyRef(angleX, "drawable:gone/geometry", 0)
		session.setKeySelection(setOf(key(2), unresolved))
		val modelBefore = session.model.value
		val cursorBefore = session.cursor()

		session.dragTrackKeysKeepingSelection(listOf(resolved(2)), fraction = 0.5f)

		assertSame(modelBefore, session.model.value, "the key at the range's end cannot move further")
		assertEquals(setOf(key(2)), session.keySelection.value)
		assertEquals(cursorBefore + 1, session.cursor(), "the confirm records the selection on its own")
	}

	/** A drag of nothing does nothing. */
	@Test
	fun aDragOfNothingRecordsNothing() {
		val session = session()
		session.setKeySelection(setOf(key(1)))
		val cursorBefore = session.cursor()

		session.dragTrackKeysKeepingSelection(emptyList(), fraction = 0.5f)

		assertEquals(setOf(key(1)), session.keySelection.value)
		assertEquals(cursorBefore, session.cursor())
	}

	/** A selected key moved across a neighbour takes its ref along, and the rest of the selection stays put. */
	@Test
	fun aSelectedKeyMovedAcrossANeighbourKeepsItsSelection() {
		val session = session()
		val selection = setOf(key(0), key(2))
		session.setKeySelection(selection)
		val cursorBefore = session.cursor()

		session.moveTrackKeySelectingIt(key(0), track, parameter, toValue = 15f, selection = selection)

		assertEquals(listOf(0f, 15f, 30f), session.keys())
		assertEquals(setOf(key(1), key(2)), session.keySelection.value)
		assertEquals(cursorBefore + 1, session.cursor())

		session.undo()
		assertEquals(listOf(-30f, 0f, 30f), session.keys())
		assertEquals(selection, session.keySelection.value)
	}

	/** A selected key released where it was picked up records nothing at all. */
	@Test
	fun aSelectedKeyReleasedInPlaceRecordsNothing() {
		val session = session()
		val selection = setOf(key(1))
		session.setKeySelection(selection)
		val cursorBefore = session.cursor()

		session.moveTrackKeySelectingIt(key(1), track, parameter, toValue = 0f, selection = selection)

		assertEquals(selection, session.keySelection.value)
		assertEquals(cursorBefore, session.cursor())
	}

	/**
	 * An unselected key dragged while other keys are selected replaces the selection, as a click on it would,
	 * in the move's own step: one undo puts back both the key and the selection it replaced.
	 */
	@Test
	fun anUnselectedKeyReplacesTheSelection() {
		val session = session()
		val selection = setOf(key(2), TrackKeyRef(angleX, "drawable:other/geometry", 0))
		session.setKeySelection(selection)
		val cursorBefore = session.cursor()

		session.moveTrackKeySelectingIt(key(0), track, parameter, toValue = -20f, selection = selection)

		assertEquals(listOf(-20f, 0f, 30f), session.keys())
		assertEquals(setOf(key(0)), session.keySelection.value)
		assertEquals(cursorBefore + 1, session.cursor())

		session.undo()
		assertEquals(listOf(-30f, 0f, 30f), session.keys())
		assertEquals(selection, session.keySelection.value)
	}

	/**
	 * An unselected key dragged past a selected one ends up the selected key at the ordinal it lands on, and
	 * the key that was selected is not.
	 */
	@Test
	fun anUnselectedKeyDraggedPastASelectedOneIsTheOneSelected() {
		val session = session()
		val selection = setOf(key(2))
		session.setKeySelection(selection)

		session.moveTrackKeySelectingIt(key(0), track, parameter, toValue = 15f, selection = selection)

		assertEquals(listOf(0f, 15f, 30f), session.keys())
		assertEquals(setOf(key(1)), session.keySelection.value, "the key now at 15")
	}

	/** With nothing selected, the dragged key is selected. */
	@Test
	fun anUnselectedKeyIsSelectedWhenNothingWas() {
		val session = session()
		val cursorBefore = session.cursor()

		session.moveTrackKeySelectingIt(key(2), track, parameter, toValue = 20f, selection = emptySet())

		assertEquals(listOf(-30f, 0f, 20f), session.keys())
		assertEquals(setOf(key(2)), session.keySelection.value)
		assertEquals(cursorBefore + 1, session.cursor())
	}

	/** An unselected key released where it was picked up is still selected, as a step of its own, like a click. */
	@Test
	fun anUnselectedKeyReleasedInPlaceIsStillSelected() {
		val session = session()
		session.setKeySelection(setOf(key(2)))
		val modelBefore = session.model.value
		val cursorBefore = session.cursor()

		session.moveTrackKeySelectingIt(key(0), track, parameter, toValue = -30f, selection = setOf(key(2)))

		assertSame(modelBefore, session.model.value)
		assertEquals(setOf(key(0)), session.keySelection.value)
		assertEquals(cursorBefore + 1, session.cursor())
	}
}