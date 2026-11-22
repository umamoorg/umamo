package org.umamo.ui.settings

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.defaultSettingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The preferences window's persisted fold state: a fresh install folds nothing, a header click folds and opens its
 * own card only, and a stored value this build cannot read folds nothing rather than failing.
 */
class PreferencesSettingsTest {
	/** The bundled baseline is an empty list, so every card starts open. */
	@Test
	fun theBundledFoldListIsEmpty() {
		val settings = bundledSettings()

		assertEquals(JsonArray(emptyList()), settings.get(PreferencesSettings.COLLAPSED_SECTIONS_KEY))
		assertEquals(emptySet(), collapsedSectionIdsOf(settings))
	}

	/** A toggle folds the card and persists its id; a second toggle opens it and drops the id again. */
	@Test
	fun aToggleFoldsAndASecondOneOpens() {
		val settings = bundledSettings()
		val folds = PreferencesFolds(settings)

		folds.toggle(PreferencesSection.ViewportGrids)

		assertFalse(folds.isExpanded(PreferencesSection.ViewportGrids))
		assertTrue(folds.isExpanded(PreferencesSection.ViewportNavigation), "only the clicked card folds")
		assertEquals(setOf("viewport.grids"), collapsedSectionIdsOf(settings))

		folds.toggle(PreferencesSection.ViewportGrids)

		assertTrue(folds.isExpanded(PreferencesSection.ViewportGrids))
		assertEquals(emptySet(), collapsedSectionIdsOf(settings))
	}

	/** A card and the sub-panel inside it fold independently. */
	@Test
	fun aSubPanelFoldsApartFromItsCard() {
		val folds = PreferencesFolds(bundledSettings())

		folds.toggle(PreferencesSection.ColorsMeshEditingEdge)

		assertTrue(folds.isExpanded(PreferencesSection.ColorsMeshEditing))
		assertFalse(folds.isExpanded(PreferencesSection.ColorsMeshEditingEdge))
		assertTrue(folds.isExpanded(PreferencesSection.ColorsMeshEditingVertex))
	}

	/** Reloading picks up a fold written from elsewhere. */
	@Test
	fun aReloadFollowsAnOutsideWrite() {
		val settings = bundledSettings()
		val folds = PreferencesFolds(settings)

		settings.set(PreferencesSettings.COLLAPSED_SECTIONS_KEY, JsonArray(listOf(JsonPrimitive("import.meshes"))))
		folds.reload()

		assertFalse(folds.isExpanded(PreferencesSection.ImportMeshes))
	}

	/** A value that is not an array folds nothing, and an entry that is not a string is skipped. */
	@Test
	fun anUnreadableValueFoldsNothing() {
		val settings = bundledSettings()

		settings.set(PreferencesSettings.COLLAPSED_SECTIONS_KEY, JsonPrimitive("viewport.grids"))

		assertEquals(emptySet(), collapsedSectionIdsOf(settings), "a bare string")

		settings.set(PreferencesSettings.COLLAPSED_SECTIONS_KEY, JsonArray(listOf(JsonPrimitive(3), JsonPrimitive("viewport.grids"))))

		assertEquals(setOf("viewport.grids"), collapsedSectionIdsOf(settings), "a number among the ids")
	}

	/** Every card and sub-panel persists under an id of its own, so folding one never folds another. */
	@Test
	fun everySectionHasItsOwnId() {
		val ids = PreferencesSection.entries.map { section -> section.id }

		assertEquals(ids.size, ids.toSet().size, ids.toString())
	}

	/**
	 * Settings over the bundled defaults and an empty in-memory config directory - a first run's.
	 *
	 * @return Settings The loaded settings.
	 */
	private fun bundledSettings(): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())

		return Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), runBlocking { defaultSettingsJson() })
	}
}