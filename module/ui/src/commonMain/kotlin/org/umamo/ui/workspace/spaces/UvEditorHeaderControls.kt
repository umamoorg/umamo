package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.OverflowRowScope
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalPuppetTextures
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.atlasPageIndexFor
import org.umamo.ui.workspace.AreaScope

/**
 * The UV editor's space-specific header strip (mounted via SpaceDescriptor.headerContent): the
 * texture selector naming what the space shows (follow the selection, or a pinned atlas page),
 * then the vertex / edge / face select-mode buttons, the transform pivot dropdown, and the
 * proportional-editing controls - the shared EditHeaderControls.kt composables the 2D viewport's
 * header also mounts, so the two surfaces stay one behavior.  The shared controls drive the SHARED
 * session state (the selection and its select mode are one, Blender's UV sync selection): switching
 * to face mode here switches the viewport too, by design.  The texture selector instead reads and
 * writes the area's own UvEditorViewState, so two UV editors pin independently.  Each control gates
 * itself, and one that renders nothing measures zero and costs the strip nothing.
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 */
internal fun OverflowRowScope.uvEditorHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(UV_EDITOR_VIEW_STATE_KEY) { UvEditorViewState() }
	item("textureSelector") {
		// The vanish gate: without a document there are no pages to choose, and the space body is a
		// placeholder anyway - an item that renders nothing measures zero.
		if (LocalPuppet.current != null) {
			UvTextureSelectorDropdown(viewState)
		}
	}
	item("selectMode") { MeshSelectModeButtons() }
	item("pivot") { PivotModeDropdown() }
	item("proportional") { ProportionalEditControls() }
}

/**
 * The texture selector: a label chip naming what the space shows, opening a menu of Follow Selection
 * plus one row per atlas page (number, texel dimensions, meshed-drawable count - the page inventory).
 * Choosing a row mutates the area's [UvEditorViewState] directly - a per-area view choice, not a
 * session operation, so no registry dispatch (the uv.page.* palette commands are the separate,
 * hovered-area-routed path onto the same state).
 *
 * The chip face labels the EFFECTIVE selection: a pin the current textures cannot satisfy reads as
 * Follow Selection - matching what the body resolves - without clearing what is stored.
 *
 * @param UvEditorViewState viewState The area's shared texture-selection state.
 */
@Composable
private fun UvTextureSelectorDropdown(viewState: UvEditorViewState) {
	val model = LocalPuppet.current ?: return
	val textures = LocalPuppetTextures.current
	val atlases = textures?.atlases.orEmpty()
	val storedSelection = viewState.textureSelection
	val effectiveSelection =
		if (storedSelection is UvTextureSelection.PinnedPage && storedSelection.pageIndex in atlases.indices) {
			storedSelection
		} else {
			UvTextureSelection.FollowSelection
		}
	val currentLabel =
		when (effectiveSelection) {
			is UvTextureSelection.PinnedPage -> stringResource(Res.string.uv_texture_selector_page, effectiveSelection.pageIndex + 1)
			UvTextureSelection.FollowSelection -> stringResource(Res.string.uv_texture_selector_follow)
		}
	// The inventory rows' meshed-drawable counts.  Each drawable resolves its page through
	// atlasPageIndexFor (the textureSourceId indirection); inverting the raw-id atlas map instead
	// would miss session-created duplicates sharing a source.
	val meshedCountByPage =
		remember(model, textures) {
			val counts = IntArray(atlases.size)
			if (textures != null) {
				for (drawable in model.drawables) {
					if (drawable.mesh != null) {
						val pageIndex = atlasPageIndexFor(drawable, textures)
						if (pageIndex != null && pageIndex in counts.indices) {
							counts[pageIndex] = counts[pageIndex] + 1
						}
					}
				}
			}
			counts
		}
	var expanded by remember { mutableStateOf(false) }
	// Follow Selection first, then one row per page; with no textures the menu is the Follow row
	// alone.  The menu's own dismiss closes the popup, so onSelect need not toggle `expanded`.
	val followRow =
		MenuItem.Action(
			label = stringResource(Res.string.uv_texture_selector_follow),
			onSelect = { viewState.textureSelection = UvTextureSelection.FollowSelection },
		)
	val pageRows =
		atlases.mapIndexed { pageIndex, page ->
			MenuItem.Action(
				label =
					stringResource(
						Res.string.uv_texture_selector_page_row,
						pageIndex + 1,
						page.width,
						page.height,
						meshedCountByPage[pageIndex],
					),
				onSelect = { viewState.textureSelection = UvTextureSelection.PinnedPage(pageIndex) },
			)
		}
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = true },
		contentDescription = currentLabel,
		label = currentLabel,
	) {
		Menu(
			items = listOf(followRow) + pageRows,
			onDismissRequest = { expanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}