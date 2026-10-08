package org.umamo.ui.workspace.spaces

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import org.umamo.ui.workspace.editorstate.booleanOf

/*
 * The `overlays` member of a work surface's area block (docs/format/UMA.md § 7.3): how an area's
 * ViewportOverlayState is written into the editor state and read back.  Both hosts - the 2D viewport's
 * block and the UV editor's - write the one shape, so the two surfaces stay one format.
 */

/** The member name an area block holds its overlay flags under (UMA § 7.3). */
internal const val OVERLAYS_MEMBER = "overlays"

/**
 * This state as its area block's `overlays` member: a JSON null while every flag sits at its default, else
 * an object naming EVERY key the surface uses - the flag's value where it deviates, a JSON null where it
 * does not.  Naming every key is what lets a flag go back to its default in the file: the entry is saved as
 * a merge patch (UMA § 7.5), which keeps a member the writer does not name.  Keys the surface has no
 * overlay for (the axes and the wireframe under a UV editor) are never written.
 *
 * @return JsonElement The member value.
 */
internal fun ViewportOverlayState.overlaysJsonOrNull(): JsonElement {
	if (isAtDefaults) {
		return JsonNull
	}
	val carriesViewportOnlyKeys = surface == OverlaySurface.Viewport2D
	return buildJsonObject {
		// UMA § 7.3 `overlays`: every key's default is true except the wireframe's.
		put("all", flagOrNull(showOverlays, defaultValue = true))
		put("grid", flagOrNull(showGrid, defaultValue = true))
		if (carriesViewportOnlyKeys) {
			put("axes", flagOrNull(showAxes, defaultValue = true))
		}
		put("cursor", flagOrNull(showCursor, defaultValue = true))
		put("info", flagOrNull(showInfo, defaultValue = true))
		if (carriesViewportOnlyKeys) {
			put("wireframe", flagOrNull(showWireframe, defaultValue = false))
		}
	}
}

/**
 * Takes the `overlays` member a document was saved with, resetting every flag first so an absent key means
 * its default (UMA § 7.1).  A key of the wrong type is skipped, and a key the surface has no overlay for is
 * ignored.
 *
 * @param JsonObject? tree The member as the file held it, or null when the block has none.
 */
internal fun ViewportOverlayState.restoreOverlays(tree: JsonObject?) {
	reset()
	if (tree == null) {
		return
	}
	showOverlays = booleanOf(tree, "all") ?: true
	showGrid = booleanOf(tree, "grid") ?: true
	showCursor = booleanOf(tree, "cursor") ?: true
	showInfo = booleanOf(tree, "info") ?: true
	if (surface == OverlaySurface.Viewport2D) {
		showAxes = booleanOf(tree, "axes") ?: true
		showWireframe = booleanOf(tree, "wireframe") ?: false
	}
}

/**
 * A flag as its member value: the value where it deviates from [defaultValue], a JSON null where it does not.
 *
 * @param Boolean value The flag.
 * @param Boolean defaultValue The flag's default.
 * @return JsonElement The member value.
 */
private fun flagOrNull(value: Boolean, defaultValue: Boolean): JsonElement = if (value == defaultValue) JsonNull else JsonPrimitive(value)