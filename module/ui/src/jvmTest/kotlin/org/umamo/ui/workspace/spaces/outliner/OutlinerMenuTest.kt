package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
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
import kotlin.test.assertNotNull
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

	/**
	 * Create Warp Deformer reads the session as the menu opens, not as the row last composed: a row that a selection
	 * made elsewhere leaves alone still offers it enabled for that selection, and a row the mode switch leaves alone
	 * stops listing it outside Object mode.
	 */
	@Test
	fun createWarpReadsTheSessionAsTheMenuOpens() =
		runComposeUiTest {
			val harness = mountOutliner(model = withAMeshOnTheEye())

			// The Loose row composed with nothing selected, and selecting the eye recomposes nothing on it.
			harness.session.setSelection(Selection(setOf(SelectionTarget.Drawable(OutlinerIds.eye)), SelectionTarget.Drawable(OutlinerIds.eye)))
			waitForIdle()
			secondaryClickAt(rowBox(OutlinerNames.LOOSE).center)
			clickMenuEntry(harness.text.outlinerCreateWarp)

			val created = assertNotNull(harness.session.model.value.deformers.firstOrNull { deformer -> deformer.name == "Warp" }, "the entry was enabled and wrapped the eye")
			assertEquals(created.id, drawableOf(harness, OutlinerIds.eye).parentDeformerId)

			harness.session.setMode(EditorMode.Edit)
			waitForIdle()
			assertEquals(EditorMode.Edit, harness.session.mode.value)
			secondaryClickAt(rowBox(OutlinerNames.LOOSE).center)

			assertTrue(popupShows(harness.text.outlinerRename), "the menu is open")
			assertFalse(popupShows(harness.text.outlinerCreateWarp), "Edit mode does not list it")
		}

	/**
	 * The fixture model with a triangle mesh on the eye, so a warp can wrap it.
	 *
	 * @return PuppetModel The model.
	 */
	private fun withAMeshOnTheEye(): PuppetModel {
		val base = outlinerFixtureModel()
		val triangle = floatArrayOf(100f, 100f, 140f, 100f, 140f, 140f)

		return base.copy(
			drawables = base.drawables.map { drawable -> if (drawable.id == OutlinerIds.eye) drawable.copy(mesh = DrawableMesh(triangle, triangle, FloatArray(triangle.size), intArrayOf(0, 1, 2))) else drawable },
		)
	}
}