package org.umamo.ui.workspace.editorstate

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState

/*
 * The `overlays` member of a work surface's block, the `viewport` or `uv` member of an area block
 * (docs/format/UMA.md § 7.3): how an area's ViewportOverlayState is written into the editor state and read
 * back, beside the other editor-state serializers.  Both hosts - the 2D viewport's block and the UV
 * editor's - write the one shape, so the two surfaces stay one format.  Extension functions rather than
 * members of the state, since the viewport root does not import this package.
 */

/** The member name a work surface's block (an area block's `viewport` or `uv` member) holds its overlay flags under (UMA § 7.3). */
internal const val OVERLAYS_MEMBER = "overlays"

/**
 * This state as its surface block's `overlays` member: a JSON null while every flag sits at its default and
 * the grid follows the application, else an object naming EVERY key the surface uses - the flag's value where
 * it deviates, a JSON null where it does not, and the area's own grid under `gridGeometry` or a null while it
 * follows.  Naming every key is what lets a flag go back to its default in the file: the entry is saved as a
 * merge patch (UMA § 7.5), which keeps a member the writer does not name.  Keys the surface has no overlay for
 * (the axes, the wireframe, and the selection tint under a UV editor) are never written, and a UV editor's
 * own grid is written as its subdivisions alone.
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
			put("selectionTint", flagOrNull(showSelectionTint, defaultValue = true))
		}
		// UMA § 7.3 `wireframeOpacity`: the wireframe's (a UV editor's islands') alpha scale, 0 to 1, on both surfaces.
		put("wireframeOpacity", if (wireframeOpacity == 1f) JsonNull else JsonPrimitive(wireframeOpacity))
		// UMA § 7.3 `gridGeometry`: the area's own grid; a UV editor's major spacing is its image, so its scale is not written.
		put(
			"gridGeometry",
			gridGeometry?.let { own ->
				buildJsonObject {
					if (carriesViewportOnlyKeys) {
						put("scale", JsonPrimitive(own.scale))
					}
					put("subdivisions", JsonPrimitive(own.subdivisions))
				}
			} ?: JsonNull,
		)
	}
}

/**
 * Takes the `overlays` member a document was saved with, resetting every flag first so an absent key means
 * its default (UMA § 7.1).  A key of the wrong type is skipped, a key the surface has no overlay for is
 * ignored, a `wireframeOpacity` outside 0 to 1 reads as 1, and a `gridGeometry` that fails its checks leaves
 * the area following the application's grid.
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
		showSelectionTint = booleanOf(tree, "selectionTint") ?: true
	}
	wireframeOpacity = finiteFloatOf(tree["wireframeOpacity"])?.takeIf { value -> value in 0f..1f } ?: 1f
	gridGeometry = gridGeometryOf(tree["gridGeometry"] as? JsonObject)
}

/**
 * The own grid a saved `gridGeometry` member names, or null when it is absent or fails its checks - subdivisions
 * of at least 1 on both surfaces, and a scale above 0 on a 2D viewport (UMA § 7.3) - in which case the area
 * follows the application's grid.
 *
 * @param JsonObject? block The member as the file held it, or null when absent.
 * @return GridConfig? The area's own grid, or null to follow.
 */
private fun ViewportOverlayState.gridGeometryOf(block: JsonObject?): GridConfig? {
	if (block == null) {
		return null
	}
	val subdivisions = intOf(block["subdivisions"])?.takeIf { value -> value >= 1 } ?: return null
	if (surface == OverlaySurface.UvEditor) {
		return GridConfig(subdivisions = subdivisions)
	}
	val scale = finiteFloatOf(block["scale"])?.takeIf { value -> value > 0f } ?: return null
	return GridConfig(scale, subdivisions)
}

/**
 * A flag as its member value: the value where it deviates from [defaultValue], a JSON null where it does not.
 *
 * @param Boolean value The flag.
 * @param Boolean defaultValue The flag's default.
 * @return JsonElement The member value.
 */
private fun flagOrNull(value: Boolean, defaultValue: Boolean): JsonElement = if (value == defaultValue) JsonNull else JsonPrimitive(value)