package org.umamo.ui.workspace.spaces

import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.properties.PropertiesViewState
import org.umamo.ui.properties.PropertyTabId
import org.umamo.ui.tracks.TRACK_LABEL_COLUMN_DEFAULT_WIDTH
import org.umamo.ui.tracks.TRACK_LABEL_COLUMN_MAX_WIDTH
import org.umamo.ui.tracks.TrackWindow
import org.umamo.ui.workspace.PersistentSpaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins each space's saved view state (docs/format/UMA.md §7.3): what it writes comes back, a state at its
 * defaults writes nothing but nulls, and a member that is the wrong shape is skipped rather than thrown over.
 */
class PersistentViewStatesTest {
	/**
	 * Whether [state] names every member as a null, which is what an untouched space writes.
	 *
	 * @param PersistentSpaceState state The state.
	 * @return Boolean True when nothing deviates.
	 */
	private fun writesOnlyNulls(state: PersistentSpaceState): Boolean = state.toJson().values.all { value -> value is JsonNull }

	@Test
	fun theOutlinerRoundTripsItsFoldsFiltersAndColumns() {
		val saved = OutlinerViewState()
		assertTrue(writesOnlyNulls(saved), "an untouched outliner has nothing to save")
		saved.query = "arm"
		saved.expanded["part:12"] = true
		saved.expanded["part:13"] = false
		saved.expanded[OUTLINER_ROOT_ID] = false
		saved.showDeformers = false
		saved.showVisibilityColumn = false

		val tree = saved.toJson()
		val reopened = OutlinerViewState().also { state -> state.restore(tree) }

		assertEquals("", reopened.query, "a search query is not saved")
		assertTrue(reopened.isOpen("part:12"))
		assertFalse(reopened.isOpen(OUTLINER_ROOT_ID), "a closed root is a deviation and is saved")
		assertFalse("part:13" in reopened.expanded, "a branch closed at its default is no deviation")
		assertFalse(reopened.showDeformers)
		assertTrue(reopened.showParts && reopened.showDrawables && reopened.showSelectableColumn)
		assertFalse(reopened.showVisibilityColumn)
		assertEquals(tree, reopened.toJson(), "and the restored state writes the same member")
	}

	@Test
	fun theSourcesSpaceRoundTripsItsFoldsAndFilters() {
		val saved = SourcesViewState()
		assertTrue(writesOnlyNulls(saved))
		saved.expanded["source:art-0"] = false
		saved.expanded["layer:art-0/lyid:4"] = true
		saved.setShown(SourcesFilter.Bound, false)
		saved.setShown(SourcesFilter.NeedsReview, false)

		val reopened = SourcesViewState().also { state -> state.restore(saved.toJson()) }

		assertFalse(reopened.isOpen("source:art-0"))
		assertTrue(reopened.isOpen("layer:art-0/lyid:4"))
		assertTrue(reopened.isOpen("source:art-1"), "an unrecorded file is open by default")
		assertEquals(setOf(SourcesFilter.Unbound, SourcesFilter.Missing), reopened.filters)
	}

	@Test
	fun theParametersPanelRoundTripsItsGroupsEditorsAndFilter() {
		val saved = ParametersViewState()
		assertTrue(writesOnlyNulls(saved))
		saved.expandedGroups[ParameterGroupId("face")] = true
		saved.expandedGroups[ParameterGroupId("body")] = false
		saved.openRangeEditors[ParameterId("ParamAngleX")] = true
		saved.openRangeEditors[ParameterId("ParamAngleY")] = false
		saved.showOnlySelected = true
		saved.renamingParameterId = ParameterId("ParamAngleX")

		val reopened = ParametersViewState().also { state -> state.restore(saved.toJson()) }

		assertEquals(mapOf(ParameterGroupId("face") to true, ParameterGroupId("body") to false), reopened.expandedGroups.toMap())
		assertEquals(mapOf(ParameterId("ParamAngleX") to true), reopened.openRangeEditors.toMap(), "a closed range editor is the default")
		assertTrue(reopened.showOnlySelected)
		assertEquals(null, reopened.renamingParameterId, "a rename in flight is not saved")
	}

	@Test
	fun theKeyformSheetRoundTripsItsFoldsFiltersWindowAndWidth() {
		val unseeded = KeyformSheetViewState()
		assertTrue(writesOnlyNulls(unseeded), "a sheet that never seeded has no fold state of its own")

		val saved = KeyformSheetViewState()
		saved.seeded = true
		saved.expandedKeys = setOf("part:3")
		saved.collapsedParameters = setOf(ParameterId("ParamAngleX"))
		saved.showBlendShapes = false
		saved.window = TrackWindow(0.25f, 0.75f)
		saved.labelColumnWidth = 240.dp
		saved.boxSelectArmed = true

		val reopened = KeyformSheetViewState().also { state -> state.restore(saved.toJson()) }

		assertTrue(reopened.seeded, "a saved fold state counts as the seed")
		assertEquals(setOf("part:3"), reopened.expandedKeys)
		assertEquals(setOf(ParameterId("ParamAngleX")), reopened.collapsedParameters)
		assertTrue(reopened.showGeometry && reopened.showChannels)
		assertFalse(reopened.showBlendShapes)
		assertEquals(TrackWindow(0.25f, 0.75f), reopened.window)
		assertEquals(240.dp, reopened.labelColumnWidth)
		assertFalse(reopened.boxSelectArmed, "a gesture in flight is not saved")
	}

	@Test
	fun aSeededSheetWithEveryGroupClosedSavesAnEmptyList() {
		val saved = KeyformSheetViewState()
		saved.seeded = true

		val reopened = KeyformSheetViewState().also { state -> state.restore(saved.toJson()) }

		assertTrue(reopened.seeded, "collapse-everything survives: the reopened sheet does not seed itself open")
		assertEquals(emptySet(), reopened.expandedKeys)
	}

	@Test
	fun thePropertiesPanelRoundTripsItsTabSectionsAndListHeights() {
		val saved = PropertiesViewState()
		assertTrue(writesOnlyNulls(saved))
		saved.query = "mask"
		saved.activeTab = PropertyTabId.Data
		saved.expandedSections["data.mesh"] = false
		saved.expandedSections["data.blend"] = true
		saved.listHeights["maskedBy"] = 220.dp

		val reopened = PropertiesViewState().also { state -> state.restore(saved.toJson()) }

		assertEquals("", reopened.query)
		assertEquals(PropertyTabId.Data, reopened.activeTab)
		assertEquals(mapOf("data.mesh" to false), reopened.expandedSections.toMap(), "an open section is the default")
		assertEquals(mapOf("maskedBy" to 220.dp), reopened.listHeights.toMap())
	}

	@Test
	fun theUvEditorRoundTripsEachTextureSelection() {
		assertTrue(writesOnlyNulls(UvEditorViewState()), "following the selection is the default")
		for (selection in listOf(UvTextureSelection.PinnedPage(2), UvTextureSelection.SourceLayer, UvTextureSelection.FollowSelection)) {
			val saved = UvEditorViewState().also { state -> state.textureSelection = selection }
			val reopened = UvEditorViewState().also { state -> state.restore(saved.toJson()) }
			assertEquals(selection, reopened.textureSelection)
		}
	}

	/** Every member the wrong shape: nothing throws, and every state keeps its defaults (UMA §7.1). */
	@Test
	fun membersOfTheWrongShapeAreSkipped() {
		val junk =
			buildJsonObject {
				for (memberName in listOf("expanded", "collapsed", "hidden", "hiddenColumns", "expandedGroups", "collapsedGroups", "openRangeEditors", "collapsedParameters", "collapsedSections")) {
					put(memberName, "not an array")
				}
				put("onlySelected", "yes")
				put("tab", 7)
				put("texture", buildJsonObject { put("page", "two") })
				put(
					"window",
					buildJsonArray {
						add(JsonPrimitive(0.9f))
						add(JsonPrimitive(0.1f))
					},
				)
				put("labelColumnWidth", 99999)
				put(
					"listHeights",
					buildJsonObject {
						put("maskedBy", -4)
						put("other", "tall")
					},
				)
			}

		val outliner = OutlinerViewState().also { state -> state.restore(junk) }
		val sources = SourcesViewState().also { state -> state.restore(junk) }
		val parameters = ParametersViewState().also { state -> state.restore(junk) }
		val sheet = KeyformSheetViewState().also { state -> state.restore(junk) }
		val properties = PropertiesViewState().also { state -> state.restore(junk) }
		val uvEditor = UvEditorViewState().also { state -> state.restore(junk) }

		assertTrue(writesOnlyNulls(outliner) && writesOnlyNulls(sources) && writesOnlyNulls(parameters) && writesOnlyNulls(uvEditor))
		assertFalse(sheet.seeded)
		assertEquals(TrackWindow.Full, sheet.window, "a window that runs backwards keeps the whole domain")
		assertEquals(TRACK_LABEL_COLUMN_MAX_WIDTH, sheet.labelColumnWidth, "a width past the sheet's limit is clamped to it")
		assertEquals(PropertyTabId.Document, properties.activeTab)
		assertEquals(emptyMap(), properties.listHeights.toMap())
		assertEquals(TRACK_LABEL_COLUMN_DEFAULT_WIDTH, KeyformSheetViewState().also { state -> state.restore(JsonObject(emptyMap())) }.labelColumnWidth)
	}
}