package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two filters over the list: the header's search, and the one that keeps only what drives the
 * selection.  Both are view state, so neither may touch the document.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersFilterTest {
	/**
	 * Selects the fixture's one drawable, which is keyed on Body X.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 */
	private fun selectTheDrawable(harness: ParametersPanelHarness) {
		val target = SelectionTarget.Drawable(PanelIds.drawable)
		harness.session.setSelection(Selection(setOf(target), target))
	}

	/** The search matches the name a row shows. */
	@Test
	fun theSearchMatchesAName() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.query = "breath" }
			waitForIdle()

			assertTrue(showsText(PanelNames.BREATH))
			assertFalse(showsText(PanelNames.BODY_X))
			assertEquals(1, countOfDescription(harness.text.reorderHandle), "a group with nothing left to show goes too")
		}

	/** The search matches the id as well, which is how a rigger coming from Cubism knows the axis. */
	@Test
	fun theSearchMatchesAnId() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.query = "MouthForm" }
			waitForIdle()

			assertTrue(showsText(PanelNames.SMILE_SHAPE))
			assertTrue(showsText(PanelNames.FACE), "the group it sits in stays as its heading")
			assertFalse(showsText(PanelNames.EYE_OPEN))
		}

	/** A search opens a folded group for as long as it runs, and gives the fold back when it ends. */
	@Test
	fun aSearchOpensAFoldedGroupAndGivesTheFoldBack() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			assertFalse(showsText(PanelNames.ARM))

			runOnIdle { harness.viewState.query = "arm" }
			waitForIdle()
			assertTrue(showsText(PanelNames.ARM), "a match must not hide inside a folded group")

			runOnIdle { harness.viewState.query = "" }
			waitForIdle()
			assertFalse(showsText(PanelNames.ARM), "the group folds again once the search is over")
			assertTrue(harness.viewState.expandedGroups.isEmpty(), "a search must not write the fold it overrides")
		}

	/** Surrounding blanks are not part of what was searched for. */
	@Test
	fun theSearchIgnoresSurroundingBlanks() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.query = "  breath  " }
			waitForIdle()
			assertTrue(showsText(PanelNames.BREATH))

			runOnIdle { harness.viewState.query = "   " }
			waitForIdle()
			assertEquals(PanelRows.COUNT, countOfDescription(harness.text.reorderHandle), "a blank search is no search")
		}

	/** The selection filter keeps the parameters that drive what is selected. */
	@Test
	fun theSelectionFilterKeepsWhatDrivesTheSelection() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle {
				selectTheDrawable(harness)
				harness.viewState.showOnlySelected = true
			}
			waitForIdle()

			assertTrue(showsText(PanelNames.BODY_X))
			assertEquals(1, countOfDescription(harness.text.reorderHandle))
		}

	/** With nothing selected the filter is inert, so the panel is never mysteriously empty. */
	@Test
	fun theSelectionFilterIsInertWithNothingSelected() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.showOnlySelected = true }
			waitForIdle()

			assertEquals(PanelRows.COUNT, countOfDescription(harness.text.reorderHandle))
		}

	/** Both filters restrict, so a row has to pass each of them. */
	@Test
	fun theTwoFiltersIntersect() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle {
				selectTheDrawable(harness)
				harness.viewState.showOnlySelected = true
				harness.viewState.query = "breath"
			}
			waitForIdle()
			assertEquals(0, countOfDescription(harness.text.reorderHandle), "Breath does not drive the selection")

			runOnIdle { harness.viewState.query = "body" }
			waitForIdle()
			assertTrue(showsText(PanelNames.BODY_X))
			assertEquals(1, countOfDescription(harness.text.reorderHandle))
		}

	/** A pad is one control, so it stays when either of its axes matches. */
	@Test
	fun aPadStaysWhenEitherAxisMatches() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.query = "Angle Y" }
			waitForIdle()

			assertTrue(showsText(PanelNames.ANGLE_X), "the pad shows both its axes or neither")
			assertTrue(showsText(PanelNames.ANGLE_Y))
			assertEquals(1, countOfDescription(harness.text.reorderHandle))
		}

	/** A row being named is listed whatever the search says, or a create would have nowhere to open its field. */
	@Test
	fun aParameterCreatedDuringASearchIsShownForNaming() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			runOnIdle { harness.viewState.query = "breath" }
			waitForIdle()

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.addKeyFormParameter)

			assertTrue(renameFieldOpen(), "the new row must show although its name does not match")
			assertTrue(showsText(PanelNames.BREATH), "and the search still holds for every other row")
			assertFalse(showsText(PanelNames.BODY_X))
		}

	/** Once named, the row is held to the search like any other. */
	@Test
	fun aNamedRowIsFilteredLikeAnyOther() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			runOnIdle { harness.viewState.query = "breath" }
			waitForIdle()
			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.addKeyFormParameter)

			typeIntoRenameField("Elbow")
			pressKey(Key.Enter)
			assertFalse(showsText("Elbow"), "a name the search does not match leaves the list")

			runOnIdle { harness.viewState.query = "" }
			waitForIdle()
			assertTrue(showsText("Elbow"), "and is there once the search is over")
		}

	/** A name the search matches keeps its row in the list. */
	@Test
	fun aRowNamedToMatchTheSearchStays() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			runOnIdle { harness.viewState.query = "breath" }
			waitForIdle()
			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.addKeyFormParameter)

			typeIntoRenameField("Deep Breath")
			pressKey(Key.Enter)

			assertTrue(showsText("Deep Breath"))
		}

	/** A new group holds nothing, which any filter drops; while it is being named it shows all the same. */
	@Test
	fun aGroupCreatedDuringASearchIsShownForNaming() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			runOnIdle { harness.viewState.query = "breath" }
			waitForIdle()

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.newGroup)
			assertTrue(renameFieldOpen())

			typeIntoRenameField("Lungs")
			pressKey(Key.Enter)
			assertFalse(showsText("Lungs"), "an empty group leaves a filtered list once it is named")

			runOnIdle { harness.viewState.query = "" }
			waitForIdle()
			assertTrue(showsText("Lungs"))
		}

	/** The selection filter hides a new parameter too, since it drives nothing yet. */
	@Test
	fun aParameterCreatedUnderTheSelectionFilterIsShownForNaming() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			runOnIdle {
				selectTheDrawable(harness)
				harness.viewState.showOnlySelected = true
			}
			waitForIdle()

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.addKeyFormParameter)

			assertTrue(renameFieldOpen())
			assertEquals(2, countOfDescription(harness.text.reorderHandle), "Body X, and the row being named")
		}

	/** An existing row opened for naming from outside the list shows through the search as well. */
	@Test
	fun aRowMarkedForNamingShowsThroughTheSearch() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			runOnIdle { harness.viewState.query = "breath" }
			waitForIdle()

			runOnIdle { harness.viewState.renamingGroupId = PanelIds.face }
			waitForIdle()

			assertTrue(renameFieldOpen())
			assertFalse(showsText(PanelNames.EYE_OPEN), "the group shows for its name, not for rows the search left out")
		}

	/** During a search every group is open, so a press on a header has nothing to fold and changes no fold. */
	@Test
	fun aGroupClickDuringASearchFoldsNothing() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			runOnIdle { harness.viewState.query = "arm" }
			waitForIdle()
			assertTrue(showsText(PanelNames.ARM))

			clickAt(panelBoundsOfText(PanelNames.BODY).center)
			assertTrue(showsText(PanelNames.ARM))
			assertTrue(harness.viewState.expandedGroups.isEmpty(), "a press during a search must not write the fold")

			runOnIdle { harness.viewState.query = "" }
			waitForIdle()
			assertFalse(showsText(PanelNames.ARM), "the group is folded as it was before the search")
		}

	/** A double click during a search renames, and has no first toggle to undo. */
	@Test
	fun aDoubleClickOnAGroupDuringASearchRenamesAndFoldsNothing() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			runOnIdle { harness.viewState.query = "arm" }
			waitForIdle()

			doubleClickAt(panelBoundsOfText(PanelNames.BODY).center)

			assertEquals(PanelIds.body, harness.viewState.renamingGroupId)
			assertTrue(renameFieldOpen())
			assertTrue(harness.viewState.expandedGroups.isEmpty())
		}

	/** A filter is a view of the document, never an edit to it. */
	@Test
	fun aFilterLeavesTheDocumentAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val modelBefore = harness.session.model.value
			val cursorBefore = harness.historyCursor

			runOnIdle {
				harness.viewState.query = "breath"
				harness.viewState.showOnlySelected = true
			}
			waitForIdle()

			assertTrue(harness.session.model.value === modelBefore)
			assertEquals(cursorBefore, harness.historyCursor)
		}
}