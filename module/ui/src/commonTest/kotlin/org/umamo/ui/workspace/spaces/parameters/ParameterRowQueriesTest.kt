package org.umamo.ui.workspace.spaces.parameters

import org.umamo.edit.ParameterSelection
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the panel asks of its rows: which parameters a press on a row targets, what a dragged row
 * is called, which parameters the two filters leave, and where the row being renamed sits.
 */
class ParameterRowQueriesTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val bodyX = ParameterId("ParamBodyX")
	private val breath = ParameterId("ParamBreath")
	private val face = ParameterGroupId("GroupFace")
	private val art = DrawableId("art")

	/**
	 * An animatable parameter named apart from its id.
	 *
	 * @param ParameterId id The parameter id.
	 * @param String name The display name.
	 * @return Parameter The fixture parameter.
	 */
	private fun parameter(id: ParameterId, name: String): Parameter = Parameter(id, name, min = -1f, max = 1f, default = 0f)

	private val rows: List<ParameterRow> =
		listOf(
			ParameterRow.GroupHeader(face, "Face", depth = 0, expanded = true),
			ParameterRow.Single(parameter(breath, "Breath"), depth = 1),
			ParameterRow.Pair2D(parameter(angleX, "Angle X"), parameter(angleY, "Angle Y"), depth = 0),
			ParameterRow.Single(parameter(bodyX, "Body X"), depth = 0),
		)

	/**
	 * A model of the four fixture parameters with one drawable keyed on Body X.
	 *
	 * @return PuppetModel The fixture model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(parameter(breath, "Breath"), parameter(angleX, "Angle X"), parameter(angleY, "Angle Y"), parameter(bodyX, "Body X")),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = art,
						name = "art",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = null,
						geometryGrid =
							KeyformGrid(
								listOf(KeyformAxis(bodyX, floatArrayOf(0f))),
								listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0f, 0f)))),
							),
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	private val artSelected = Selection(setOf(SelectionTarget.Drawable(art)), SelectionTarget.Drawable(art))

	/** A slider targets its one parameter. */
	@Test
	fun aSliderTargetsItsParameter() {
		assertEquals(ParameterSelection.of(bodyX), parameterSelectionOf(rows[3]))
	}

	/** A pad targets both its axes with the horizontal one active, so the keyform sheet shows a section per axis. */
	@Test
	fun aPadTargetsBothAxesWithTheHorizontalActive() {
		assertEquals(ParameterSelection(setOf(angleX, angleY), angleX), parameterSelectionOf(rows[2]))
	}

	/** A group header owns no parameter to target. */
	@Test
	fun aGroupTargetsNothing() {
		assertNull(parameterSelectionOf(rows[0]))
	}

	/** A dragged row goes by its name, and a pad by its horizontal axis's. */
	@Test
	fun aDraggedRowIsCalledByItsName() {
		assertEquals("Body X", draggedRowLabel(rows, rowKey(rows[3])))
		assertEquals("Angle X", draggedRowLabel(rows, rowKey(rows[2])))
		assertEquals("Face", draggedRowLabel(rows, rowKey(rows[0])))
	}

	/** With no drag, or a drag whose row has left the list, there is nothing to call it. */
	@Test
	fun aDragWithNoRowHasNoName() {
		assertEquals("", draggedRowLabel(rows, null))
		assertEquals("", draggedRowLabel(rows, "param:ParamGone"))
	}

	/** With neither filter on there is no restriction at all, which is not the same as an empty one. */
	@Test
	fun noFilterLeavesEverything() {
		assertNull(visibleParameterIds(model(), Selection(), showOnlySelected = false, searchQuery = ""))
	}

	/** The selection filter keeps what drives the selection. */
	@Test
	fun theSelectionFilterKeepsWhatDrivesTheSelection() {
		assertEquals(setOf(bodyX), visibleParameterIds(model(), artSelected, showOnlySelected = true, searchQuery = ""))
	}

	/** Switched on with nothing selected the filter is inert, so the panel is never mysteriously blank. */
	@Test
	fun theSelectionFilterIsInertWithNothingSelected() {
		assertNull(visibleParameterIds(model(), Selection(), showOnlySelected = true, searchQuery = ""))
	}

	/** Switched off, a selection restricts nothing. */
	@Test
	fun aSelectionAloneRestrictsNothing() {
		assertNull(visibleParameterIds(model(), artSelected, showOnlySelected = false, searchQuery = ""))
	}

	/** The search keeps what it matches. */
	@Test
	fun theSearchKeepsWhatItMatches() {
		assertEquals(setOf(angleX, angleY), visibleParameterIds(model(), Selection(), showOnlySelected = false, searchQuery = "angle"))
	}

	/** Both filters restrict, so a parameter has to pass each of them. */
	@Test
	fun bothFiltersIntersect() {
		assertEquals(setOf(bodyX), visibleParameterIds(model(), artSelected, showOnlySelected = true, searchQuery = "body"))
		assertEquals(emptySet(), visibleParameterIds(model(), artSelected, showOnlySelected = true, searchQuery = "angle"))
	}

	/** The parameter being named is kept although neither filter would keep it. */
	@Test
	fun theParameterBeingNamedPassesEveryFilter() {
		assertEquals(
			setOf(angleX, angleY, breath),
			visibleParameterIds(model(), Selection(), showOnlySelected = false, searchQuery = "angle", namingParameterId = breath),
		)
		assertEquals(
			setOf(bodyX, breath),
			visibleParameterIds(model(), artSelected, showOnlySelected = true, searchQuery = "", namingParameterId = breath),
		)
		assertEquals(
			setOf(breath),
			visibleParameterIds(model(), artSelected, showOnlySelected = true, searchQuery = "angle", namingParameterId = breath),
		)
	}

	/** With no filter on there is still no restriction: a name open for editing does not make one. */
	@Test
	fun aNameOpenForEditingMakesNoFilter() {
		assertNull(visibleParameterIds(model(), Selection(), showOnlySelected = false, searchQuery = "", namingParameterId = breath))
	}

	/** A row is being named when it holds the name that is open, and a pad holds two. */
	@Test
	fun aRowIsBeingNamedByWhatItHolds() {
		assertTrue(isBeingNamed(rows[0], renamingGroupId = face, renamingParameterId = null))
		assertTrue(isBeingNamed(rows[3], renamingGroupId = null, renamingParameterId = bodyX))
		assertTrue(isBeingNamed(rows[2], renamingGroupId = null, renamingParameterId = angleY))
		assertFalse(isBeingNamed(rows[1], renamingGroupId = face, renamingParameterId = bodyX))
	}

	/** With no name open, no row is being named. */
	@Test
	fun noRowIsBeingNamedWithNoNameOpen() {
		rows.forEach { row -> assertFalse(isBeingNamed(row, renamingGroupId = null, renamingParameterId = null)) }
	}

	/** The row being renamed is found by its group or by its parameter. */
	@Test
	fun theRenamedRowIsFoundByWhatItHolds() {
		assertEquals(0, indexOfRenamedRow(rows, renamingGroupId = face, renamingParameterId = null))
		assertEquals(3, indexOfRenamedRow(rows, renamingGroupId = null, renamingParameterId = bodyX))
	}

	/** A pad answers for either of its axes, since both names are on the one row. */
	@Test
	fun aPadAnswersForEitherAxis() {
		assertEquals(2, indexOfRenamedRow(rows, renamingGroupId = null, renamingParameterId = angleX))
		assertEquals(2, indexOfRenamedRow(rows, renamingGroupId = null, renamingParameterId = angleY))
	}

	/** A row the list does not show has no index, which is what the reveal waits on. */
	@Test
	fun aRowTheListDoesNotShowHasNoIndex() {
		assertEquals(-1, indexOfRenamedRow(rows, renamingGroupId = null, renamingParameterId = ParameterId("ParamGone")))
		assertEquals(-1, indexOfRenamedRow(rows, renamingGroupId = ParameterGroupId("GroupGone"), renamingParameterId = null))
	}
}