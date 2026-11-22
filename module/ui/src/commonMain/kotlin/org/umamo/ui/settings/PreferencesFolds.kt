package org.umamo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource
import org.umamo.settings.Settings
import org.umamo.ui.LocalSettings
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_colors_deformers
import org.umamo.ui.resources.settings_colors_group_edge
import org.umamo.ui.resources.settings_colors_group_face
import org.umamo.ui.resources.settings_colors_group_vertex
import org.umamo.ui.resources.settings_colors_mesh_edit
import org.umamo.ui.resources.settings_colors_viewport
import org.umamo.ui.resources.settings_section_artwork_files
import org.umamo.ui.resources.settings_section_display
import org.umamo.ui.resources.settings_section_editing
import org.umamo.ui.resources.settings_section_grids
import org.umamo.ui.resources.settings_section_meshes
import org.umamo.ui.resources.settings_section_navigation
import org.umamo.ui.resources.settings_section_new_models
import org.umamo.ui.resources.settings_section_quality
import org.umamo.ui.resources.settings_section_sources
import org.umamo.ui.resources.settings_section_updates

/**
 * Every collapsible card and sub-panel the preferences pages draw.  The [id] is what the fold state persists, so it
 * never changes once shipped; a renamed card keeps its id.
 *
 * @property String id The stable id written to [PreferencesSettings.COLLAPSED_SECTIONS_KEY].
 * @property StringResource title The card's heading.
 */
internal enum class PreferencesSection(val id: String, val title: StringResource) {
	InterfaceDisplay("interface.display", Res.string.settings_section_display),
	InterfaceEditing("interface.editing", Res.string.settings_section_editing),
	InterfaceUpdates("interface.updates", Res.string.settings_section_updates),
	ColorsViewport("colors.viewport", Res.string.settings_colors_viewport),
	ColorsMeshEditing("colors.meshEditing", Res.string.settings_colors_mesh_edit),
	ColorsMeshEditingVertex("colors.meshEditing.vertex", Res.string.settings_colors_group_vertex),
	ColorsMeshEditingEdge("colors.meshEditing.edge", Res.string.settings_colors_group_edge),
	ColorsMeshEditingFace("colors.meshEditing.face", Res.string.settings_colors_group_face),
	ColorsDeformers("colors.deformers", Res.string.settings_colors_deformers),
	ViewportNavigation("viewport.navigation", Res.string.settings_section_navigation),
	ViewportGrids("viewport.grids", Res.string.settings_section_grids),
	ViewportQuality("viewport.quality", Res.string.settings_section_quality),
	ViewportDisplay("viewport.display", Res.string.settings_section_display),
	ImportNewModels("import.newModels", Res.string.settings_section_new_models),
	ImportMeshes("import.meshes", Res.string.settings_section_meshes),
	ImportArtworkFiles("import.artworkFiles", Res.string.settings_section_artwork_files),
	ImportSources("import.sources", Res.string.settings_section_sources),
}

/**
 * Which preferences cards are folded, bound write-through to [PreferencesSettings.COLLAPSED_SECTIONS_KEY]: a toggle
 * persists at once, like every other preferences change, so the folds survive closing the window and the app.
 *
 * @param Settings settings The settings the folds are read from and written to.
 */
@Stable
internal class PreferencesFolds(private val settings: Settings) {
	private var collapsedIds by mutableStateOf(collapsedSectionIdsOf(settings))

	/**
	 * Whether [section] is open.
	 *
	 * @param PreferencesSection section The card or sub-panel.
	 * @return Boolean True unless the rigger folded it.
	 */
	fun isExpanded(section: PreferencesSection): Boolean = section.id !in collapsedIds

	/**
	 * Opens a folded [section] or folds an open one, and persists the result.
	 *
	 * @param PreferencesSection section The card or sub-panel whose header was clicked.
	 */
	fun toggle(section: PreferencesSection) {
		val toggled = toggledSectionIds(collapsedIds, section.id)
		collapsedIds = toggled
		settings.set(PreferencesSettings.COLLAPSED_SECTIONS_KEY, JsonArray(toggled.sorted().map { sectionId -> JsonPrimitive(sectionId) }))
	}

	/** Re-reads the folds after the key was written from elsewhere. */
	fun reload() {
		collapsedIds = collapsedSectionIdsOf(settings)
	}
}

/**
 * The preferences window's fold state over [LocalSettings], following the key when anything else writes it.
 *
 * @return PreferencesFolds The fold state.
 */
@Composable
internal fun rememberPreferencesFolds(): PreferencesFolds {
	val settings = LocalSettings.current
	val folds = remember(settings) { PreferencesFolds(settings) }
	LaunchedEffect(settings, folds) {
		settings.changes.collect { changedKey ->
			if (changedKey == PreferencesSettings.COLLAPSED_SECTIONS_KEY) {
				folds.reload()
			}
		}
	}

	return folds
}

/**
 * The folded section ids stored in [settings]; an absent or malformed value, and any entry that is not a string,
 * reads as nothing folded.
 *
 * @param Settings settings The settings to read.
 * @return Set The folded ids.
 */
internal fun collapsedSectionIdsOf(settings: Settings): Set<String> {
	val stored = settings.get(PreferencesSettings.COLLAPSED_SECTIONS_KEY) as? JsonArray ?: return emptySet()

	return stored.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }.toSet()
}

/**
 * The folded ids after a click on [sectionId]'s header: removed when it was folded, added when it was open.
 *
 * @param Set collapsedIds The folded ids before the click.
 * @param String sectionId The id of the clicked card or sub-panel.
 * @return Set The folded ids after it.
 */
internal fun toggledSectionIds(collapsedIds: Set<String>, sectionId: String): Set<String> =
	if (sectionId in collapsedIds) {
		collapsedIds - sectionId
	} else {
		collapsedIds + sectionId
	}