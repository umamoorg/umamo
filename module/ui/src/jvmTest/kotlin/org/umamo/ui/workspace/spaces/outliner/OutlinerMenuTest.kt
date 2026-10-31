package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.OrgChild
import org.umamo.ui.workspace.spaces.keyformsheet.anyPopupOpen
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.clickMenuEntry
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.parameters.renameFieldOpen
import org.umamo.ui.workspace.spaces.parameters.secondaryClickAt
import org.umamo.ui.workspace.spaces.parameters.shownInOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A row's context menu: which entries each kind of row offers, and that every entry does what the inline
 * affordance beside it does.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerMenuTest {
	/** A part offers Select Hierarchy, the two toggles, Rename, then Delete and Delete Hierarchy, in that order. */
	@Test
	fun aPartsMenuListsEveryEntryInOrder() =
		runComposeUiTest {
			val harness = mountOutliner()

			secondaryClickAt(rowBox(OutlinerNames.HEAD).center)

			val entries =
				listOf(
					harness.text.selectHierarchy,
					harness.text.toggleVisibility,
					harness.text.toggleSelectable,
					harness.text.outlinerRename,
					harness.text.outlinerDelete,
					harness.text.deleteHierarchy,
				)
			assertEquals(entries, shownInOrder(entries))
		}

	/** A drawable offers the visibility toggle and a single Delete: it has no hierarchy to delete. */
	@Test
	fun aDrawablesMenuHasASingleDelete() =
		runComposeUiTest {
			val harness = mountOutliner()

			secondaryClickAt(rowBox(OutlinerNames.LOOSE).center)

			assertTrue(popupShows(harness.text.toggleVisibility))
			assertTrue(popupShows(harness.text.outlinerDelete))
			assertFalse(popupShows(harness.text.deleteHierarchy))
		}

	/** A deformer offers no visibility toggle, keeps the selectability toggle, and has a single Delete. */
	@Test
	fun aDeformersMenuHasNoVisibilityEntry() =
		runComposeUiTest {
			val harness = mountOutliner()
			clickAt(slotPoint(harness.text.expand, harness.text.outlinerArmature))

			secondaryClickAt(rowBox(OutlinerNames.ROOT_WARP).center)

			assertFalse(popupShows(harness.text.toggleVisibility))
			assertTrue(popupShows(harness.text.toggleSelectable))
			assertTrue(popupShows(harness.text.outlinerDelete))
			assertFalse(popupShows(harness.text.deleteHierarchy))
		}

	/** A synthetic row names no entity, so it opens no menu. */
	@Test
	fun aSyntheticRowOpensNoMenu() =
		runComposeUiTest {
			val harness = mountOutliner()

			secondaryClickAt(rowBox(harness.text.outlinerRoot).center)

			assertFalse(anyPopupOpen())
		}

	/** Select Hierarchy replaces the selection with the row's whole subtree, dispatched through the registry. */
	@Test
	fun selectHierarchySelectsTheSubtree() =
		runComposeUiTest {
			val harness = mountOutliner()

			secondaryClickAt(rowBox(OutlinerNames.HEAD).center)
			clickMenuEntry(harness.text.selectHierarchy)

			val selection = harness.session.selection.value
			assertEquals(
				setOf(
					SelectionTarget.Part(OutlinerIds.head),
					SelectionTarget.Drawable(OutlinerIds.eye),
					SelectionTarget.Part(OutlinerIds.hair),
					SelectionTarget.Drawable(OutlinerIds.bang),
				),
				selection.targets,
			)
			assertEquals(SelectionTarget.Part(OutlinerIds.head), selection.active)
		}

	/** Delete on a part ungroups it: its contents rise into its parent, in its place, as one undo step. */
	@Test
	fun deleteOnAPartUngroupsIt() =
		runComposeUiTest {
			val harness = mountOutliner()
			val cursorBefore = harness.historyCursor

			secondaryClickAt(rowBox(OutlinerNames.HEAD).center)
			clickMenuEntry(harness.text.outlinerDelete)

			val model = harness.session.model.value
			assertTrue(model.parts.none { part -> part.id == OutlinerIds.head })
			assertEquals(
				listOf(OrgChild.Drawable(OutlinerIds.eye), OrgChild.Part(OutlinerIds.hair), OrgChild.Drawable(OutlinerIds.loose), OrgChild.Part(OutlinerIds.limbs)),
				model.rootChildren,
			)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** Delete Hierarchy on a part removes the whole subtree. */
	@Test
	fun deleteHierarchyOnAPartRemovesTheSubtree() =
		runComposeUiTest {
			val harness = mountOutliner()

			secondaryClickAt(rowBox(OutlinerNames.HEAD).center)
			clickMenuEntry(harness.text.deleteHierarchy)

			val model = harness.session.model.value
			assertEquals(setOf(OutlinerIds.limbs), model.parts.map { part -> part.id }.toSet())
			assertEquals(setOf(OutlinerIds.loose, OutlinerIds.chest, OutlinerIds.hand), model.drawables.map { drawable -> drawable.id }.toSet())
			assertEquals(listOf(OrgChild.Drawable(OutlinerIds.loose), OrgChild.Part(OutlinerIds.limbs)), model.rootChildren)
		}

	/** Rename from the menu opens the same inline field a double click does. */
	@Test
	fun renameFromTheMenuOpensTheField() =
		runComposeUiTest {
			val harness = mountOutliner()

			secondaryClickAt(rowBox(OutlinerNames.HEAD).center)
			clickMenuEntry(harness.text.outlinerRename)

			assertTrue(renameFieldOpen())
		}

	/** The menu's toggles act on the row alone: no Shift, so nothing under it is touched. */
	@Test
	fun theMenuTogglesActOnTheRowAlone() =
		runComposeUiTest {
			val harness = mountOutliner()

			secondaryClickAt(rowBox(OutlinerNames.HEAD).center)
			clickMenuEntry(harness.text.toggleVisibility)

			assertFalse(partOf(harness, OutlinerIds.head).isVisible)
			assertTrue(drawableOf(harness, OutlinerIds.eye).isVisible, "the drawable under it keeps its own flag")

			secondaryClickAt(rowBox(OutlinerNames.HEAD).center)
			clickMenuEntry(harness.text.toggleSelectable)

			assertFalse(partOf(harness, OutlinerIds.head).isSelectable)
			assertTrue(drawableOf(harness, OutlinerIds.eye).isSelectable)
		}
}