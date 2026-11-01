package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.ParameterSelection
import org.umamo.edit.TrackKeyRef
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.PanelNames
import org.umamo.ui.workspace.spaces.parameters.assertNear
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.drag
import org.umamo.ui.workspace.spaces.parameters.pressAndMove
import org.umamo.ui.workspace.spaces.parameters.releasePress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the sheet's pointer gestures do to the keys, the key selection, the pose, and the undo stack: a
 * click on a mark, Shift+click, a drag of one mark, a drag of a selection, a folded group's summary marks,
 * a press on empty track, and folding a section or a group.
 */
@OptIn(ExperimentalTestApi::class)
class KeyformSheetGestureTest {
	private val geometryKey0 = TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 0)
	private val geometryKey1 = TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 1)
	private val geometryKey2 = TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 2)
	private val opacityKey0 = TrackKeyRef(PanelIds.bodyX, SheetRows.OPACITY, 0)
	private val opacityKey1 = TrackKeyRef(PanelIds.bodyX, SheetRows.OPACITY, 1)

	/** A click on a mark selects its key and lands the pose on it, as one step. */
	@Test
	fun aMarkClickSelectsTheKeyAndLandsThePoseOnIt() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val cursorBefore = harness.historyCursor

			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f))

			assertEquals(setOf(geometryKey2), harness.session.keySelection.value)
			assertEquals(5f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** Shift+click toggles a mark in and out of the selection, and leaves the pose where it was. */
	@Test
	fun aShiftClickTogglesAMarkWithoutMovingThePose() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))

			shiftClickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f))
			assertEquals(setOf(geometryKey0, geometryKey2), harness.session.keySelection.value, "a Shift+click adds")
			assertEquals(-5f, harness.committed(PanelIds.bodyX), "and does not scrub")

			shiftClickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f))
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value, "a second one takes it back out")
		}

	/**
	 * A selected key dragged past its neighbour lands on a new ordinal, and the selection goes with it, in
	 * one step that one undo reverses whole.
	 */
	@Test
	fun aSelectedMarkDraggedPastItsNeighbourKeepsTheSelectionOnIt() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			val cursorBefore = harness.historyCursor

			drag(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(
					lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f),
					lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 2.5f),
				),
			)

			assertKeysNear(listOf(0f, 2.5f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the key crossed the one at 0")
			assertEquals(setOf(geometryKey1), harness.session.keySelection.value, "the selection names the key where it landed")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			runOnIdle { harness.session.undo() }
			waitForIdle()
			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value, "undo puts the selection back on the key")
		}

	/**
	 * In Edit mode, which changes no key, a dragged mark stays where it is: the press is a click on it, which selects
	 * its key and moves nothing.
	 */
	@Test
	fun inEditModeADraggedMarkStaysAndIsSelected() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			runOnIdle { harness.enterEditMode() }
			waitForIdle()

			drag(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(
					lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f),
					lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 2.5f),
				),
			)

			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "no key moved")
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value, "the press selected the key under it")
		}

	/**
	 * An unselected key dragged while other keys are selected replaces the selection as the drag starts, as a
	 * click on it would, and moves alone, in one step that one undo reverses whole.
	 */
	@Test
	fun anUnselectedMarkDraggedReplacesTheSelection() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f))
			shiftClickAt(lanePoint(harness, SheetRows.OPACITY, PanelIds.bodyX, 0f))
			assertEquals(setOf(geometryKey2, opacityKey0), harness.session.keySelection.value, "two keys selected before the drag")
			val cursorBefore = harness.historyCursor

			pressAndMove(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(
					lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f),
					lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 2.5f),
				),
			)
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value, "mid-drag, the dragged key is the selection")
			assertNull(harness.sheetViewState.dragPreviewFraction, "and it moves alone")
			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the model is untouched until the release")
			assertEquals(cursorBefore, harness.historyCursor, "and so is history")

			releasePress()
			assertKeysNear(listOf(0f, 2.5f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "only the dragged key moved, past the one at 0")
			assertEquals(listOf(0f, 5f, 8f), opacityKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the selected opacity key stayed put")
			assertEquals(setOf(geometryKey1), harness.session.keySelection.value, "the dragged key, where it landed, is all that is selected")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			runOnIdle { harness.session.undo() }
			waitForIdle()
			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(setOf(geometryKey2, opacityKey0), harness.session.keySelection.value, "undo brings the selection back")
		}

	/**
	 * Dragging one mark of a selection drags them all: while the button is held only the drawing moves, and
	 * the release commits every key as one step.
	 */
	@Test
	fun aSelectionDragsTogetherAndCommitsOnRelease() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			shiftClickAt(lanePoint(harness, SheetRows.OPACITY, PanelIds.bodyX, 0f))
			val cursorBefore = harness.historyCursor

			pressAndMove(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(
					lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -4f),
					lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -3f),
				),
			)
			// Two units of Body X's twenty is a tenth of its range.
			assertNear(0.1f, harness.sheetViewState.dragPreviewFraction, "mid-drag, the preview holds the drag")
			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "and the model is untouched")
			assertEquals(cursorBefore, harness.historyCursor)

			releasePress()
			assertKeysNear(listOf(-3f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the geometry key moved")
			assertKeysNear(listOf(2f, 5f, 8f), opacityKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "and the opacity key with it")
			assertEquals(setOf(geometryKey0, opacityKey0), harness.session.keySelection.value)
			assertNull(harness.sheetViewState.dragPreviewFraction, "the release ends the preview")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A selection spanning a linked pad's two sections drags both axes by the same share of their ranges. */
	@Test
	fun aSelectionAcrossTwoSectionsDragsBothAxes() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.angleX, PanelIds.angleY))
			val padGeometryKey0 = TrackKeyRef(PanelIds.angleX, SheetRows.PAD_GEOMETRY, 0)
			val padOpacityKey0 = TrackKeyRef(PanelIds.angleY, SheetRows.PAD_OPACITY, 0)
			clickAt(lanePoint(harness, SheetRows.PAD_GEOMETRY, PanelIds.angleX, -15f))
			shiftClickAt(lanePoint(harness, SheetRows.PAD_OPACITY, PanelIds.angleY, -15f))
			assertEquals(setOf(padGeometryKey0, padOpacityKey0), harness.session.keySelection.value, "one key selected in each section")
			val cursorBefore = harness.historyCursor

			drag(
				lanePoint(harness, SheetRows.PAD_GEOMETRY, PanelIds.angleX, -15f),
				listOf(
					lanePoint(harness, SheetRows.PAD_GEOMETRY, PanelIds.angleX, -12f),
					lanePoint(harness, SheetRows.PAD_GEOMETRY, PanelIds.angleX, -9f),
				),
			)

			assertKeysNear(listOf(-9f, 15f), geometryKeysOf(harness, SheetIds.padDrawable, PanelIds.angleX), "the dragged axis")
			assertKeysNear(listOf(-9f, 15f), opacityKeysOf(harness, SheetIds.padDrawable, PanelIds.angleY), "the other section's axis")
			assertEquals(setOf(padGeometryKey0, padOpacityKey0), harness.session.keySelection.value)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A folded group's summary mark stands for every key stacked at its value, so a click selects them all. */
	@Test
	fun aSummaryClickSelectsEveryKeyStackedUnderIt() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(chevronPoint(harness, SheetRows.DRAWABLE))
			assertTrue(SheetRows.DRAWABLE !in harness.sheetViewState.expandedKeys, "the group must really be folded")

			clickAt(lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, 0f))

			assertEquals(setOf(geometryKey1, opacityKey0), harness.session.keySelection.value)
			assertEquals(0f, harness.committed(PanelIds.bodyX))
		}

	/**
	 * An unselected summary mark dragged selects every key it stands for as the drag starts, in place of the
	 * selection, and drags them together, in one step that one undo reverses whole.
	 */
	@Test
	fun anUnselectedSummaryDragSelectsAndMovesItsKeys() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(chevronPoint(harness, SheetRows.DRAWABLE))
			clickAt(lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, -5f))
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value, "a selection for the drag to replace")
			val cursorBefore = harness.historyCursor

			pressAndMove(
				lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, 5f),
				listOf(
					lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, 6f),
					lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, 7f),
				),
			)
			assertEquals(setOf(geometryKey2, opacityKey1), harness.session.keySelection.value, "mid-drag, the summary's keys are the selection")
			assertNear(0.1f, harness.sheetViewState.dragPreviewFraction, "and they drag together")
			assertEquals(cursorBefore, harness.historyCursor)

			releasePress()
			assertKeysNear(listOf(-5f, 0f, 7f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the geometry member")
			assertKeysNear(listOf(0f, 7f, 8f), opacityKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the opacity member")
			assertEquals(setOf(geometryKey2, opacityKey1), harness.session.keySelection.value, "they stay selected where they landed")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			runOnIdle { harness.session.undo() }
			waitForIdle()
			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value, "undo brings the selection back")
		}

	/** A summary mark whose keys are already selected drags the whole selection, like a selected key does. */
	@Test
	fun aSelectedSummaryDragsTheWholeSelection() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val partOpacityKey0 = TrackKeyRef(PanelIds.bodyX, SheetRows.PART_OPACITY, 0)
			clickAt(chevronPoint(harness, SheetRows.DRAWABLE))
			clickAt(lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, -5f))
			shiftClickAt(lanePoint(harness, SheetRows.PART_OPACITY, PanelIds.bodyX, -8f))
			assertEquals(setOf(geometryKey0, partOpacityKey0), harness.session.keySelection.value, "the summary's key and one other")
			val cursorBefore = harness.historyCursor

			drag(
				lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, -5f),
				listOf(
					lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, -4f),
					lanePoint(harness, SheetRows.DRAWABLE, PanelIds.bodyX, -3f),
				),
			)

			assertKeysNear(listOf(-3f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the summary's key")
			assertKeysNear(listOf(-6f, 8f), partOpacityKeysOf(harness), "and the other selected key, by the same share")
			assertEquals(setOf(geometryKey0, partOpacityKey0), harness.session.keySelection.value)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A press on empty track drops the selection and scrubs the pose there, as one step. */
	@Test
	fun anEmptyTrackPressDropsTheSelectionAndScrubsAsOneStep() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			val cursorBefore = harness.historyCursor

			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f))

			assertEquals(emptySet(), harness.session.keySelection.value)
			assertNear(-2.5f, harness.committed(PanelIds.bodyX), "the press scrubbed")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			runOnIdle { harness.session.undo() }
			waitForIdle()
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value, "one undo brings the selection back")
			assertEquals(-5f, harness.committed(PanelIds.bodyX), "and the pose")
		}

	/** A Shift press on empty track scrubs and keeps the selection, so a near miss while building one costs nothing. */
	@Test
	fun aShiftPressOnEmptyTrackKeepsTheSelection() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))

			shiftClickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f))

			assertEquals(setOf(geometryKey0), harness.session.keySelection.value)
			assertNear(-2.5f, harness.committed(PanelIds.bodyX), "the press scrubbed")
		}

	/** A click on a section's header folds its rows away, and another brings them back. */
	@Test
	fun aSectionHeaderFoldsItsSection() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			assertEquals(1, sheetCountOfText("Head"), "the part's group row shows")

			clickAt(sheetBoundsOfText(PanelNames.BODY_X).center)
			assertEquals(setOf(PanelIds.bodyX), harness.sheetViewState.collapsedParameters)
			assertEquals(0, sheetCountOfText("Head"), "folded, the section shows no rows")
			assertEquals(1, sheetCountOfText(PanelNames.BODY_X), "but keeps its header")

			clickAt(sheetBoundsOfText(PanelNames.BODY_X).center)
			assertEquals(emptySet(), harness.sheetViewState.collapsedParameters)
			assertEquals(1, sheetCountOfText("Head"))
		}

	/** A group's chevron folds its tracks away, leaving the group row. */
	@Test
	fun aGroupChevronFoldsItsTracks() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			assertEquals(setOf(SheetRows.PART, SheetRows.DRAWABLE), harness.sheetViewState.expandedKeys, "a fresh sheet opens every group")
			assertEquals(1, sheetCountOfText(harness.text.trackGeometry))

			clickAt(chevronPoint(harness, SheetRows.DRAWABLE))

			assertEquals(setOf(SheetRows.PART), harness.sheetViewState.expandedKeys)
			assertEquals(0, sheetCountOfText(harness.text.trackGeometry), "the drawable's tracks are folded away")
			assertEquals(1, sheetCountOfText("a"), "its group row stays")
		}

	/** The sheet lists one section per targeted parameter, in the model's order, and follows the target. */
	@Test
	fun theSheetShowsASectionPerTargetedParameter() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.angleY, PanelIds.angleX))
			val angleX = sheetBoundsOfText(PanelNames.ANGLE_X)
			val angleY = sheetBoundsOfText(PanelNames.ANGLE_Y)
			assertTrue(angleX.top < angleY.top, "sections read in the model's order, not the selection's")

			runOnIdle { harness.session.setParameterSelection(ParameterSelection.of(PanelIds.bodyX)) }
			waitForIdle()
			assertEquals(0, sheetCountOfText(PanelNames.ANGLE_X))
			assertNotNull(harness.sheetViewState.laneBounds[SheetRows.DRAWABLE], "the Body X section lists its groups")
		}
}