package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.AreaCameraKey
import org.umamo.ui.viewport.CameraSurface

/**
 * Every area's view state for ONE open document: the scope each area parks its space state on, seeded from what
 * the document was saved with and gathered back for its next save (docs/format/UMA.md §7.3).
 *
 * The scopes live here rather than in each leaf's composition so their lifetime is the document's.  A workspace
 * tab switch disposes the inactive tree and recomposes it on return; with the scope held by the leaf, every space
 * would come back at its defaults.  A new document gets a new holder, so nothing carries from one rig to the next.
 *
 * The area ids are the application layout's own (`interface.layout`), borrowed as opaque keys (UMA §7.3): the
 * document does not own them, and a saved id the current layout lacks simply seeds nothing.
 *
 * @param JsonObject? restoredAreas The `areas` member the document was opened with, or null.
 */
class AreaViewStates(private val restoredAreas: JsonObject? = null) {
	/** Each area's scope, created the first time the area is shown for this document. */
	private val scopesByAreaId = LinkedHashMap<String, AreaScope>()

	/**
	 * Every leaf id of the LIVE layout, workspaces in tab order and each tree in its own order, published by the
	 * shell on each layout change.  The live layout rather than the `interface.layout` setting, whose write is
	 * debounced: a save right after a split would otherwise miss the new area.
	 */
	var layoutAreaIds: List<String> = emptyList()

	/**
	 * Reads every remembered camera from the platform's render service, parked here by whoever builds that
	 * service and cleared when it goes; null where there is none (Android today).  The holder is the rendezvous
	 * because both sides already meet at it by area id - the save path never has to reach into the viewport.
	 */
	var cameraReader: (() -> Map<AreaCameraKey, ViewportCamera>)? = null

	/**
	 * The cameras the document was saved with, by area and surface (UMA §7.3): a `[centerX, centerY, zoom]` with a
	 * zoom above zero under each surface's name.  Anything else is skipped, and that area fits its content as a
	 * fresh one does.  Kept apart by surface because a view of the puppet's world means nothing over a texture's
	 * pixels: an area saved as a UV editor and reopened as a 2D viewport must not take the page's pan and zoom.
	 *
	 * @return Map The saved cameras.
	 */
	fun restoredCameras(): Map<AreaCameraKey, ViewportCamera> {
		val cameras = HashMap<AreaCameraKey, ViewportCamera>()
		for ((areaId, block) in restoredAreas.orEmpty()) {
			val saved = (block as? JsonObject)?.get(AREA_CAMERAS_MEMBER) as? JsonObject ?: continue
			for (surface in CameraSurface.entries) {
				val (centerX, centerY, zoom) = floatListOf(saved, cameraSurfaceWireName(surface), 3) ?: continue
				if (zoom > 0f) {
					cameras[AreaCameraKey(areaId, surface)] = ViewportCamera(centerX, centerY, zoom)
				}
			}
		}
		return cameras
	}

	/**
	 * The scope for [areaId], created on first use over the block the document saved for that id.
	 *
	 * @param String areaId The hosting leaf's area id.
	 * @return AreaScope The one scope this document has for the area.
	 */
	fun scopeFor(areaId: String): AreaScope = scopesByAreaId.getOrPut(areaId) { AreaScope(areaId, restoredAreas?.get(areaId) as? JsonObject) }

	/**
	 * The `areas` member of a save's merge patch (UMA §7.5): a block for each area of the saver's layout that was
	 * shown in this session, and a null for every id the document held that the layout does not have - last writer
	 * wins, which is also what keeps another layout's tokens from piling up in the file.
	 *
	 * An area of the layout that was never shown (an inactive workspace's, say) is not named, so the block the
	 * file had for it survives the merge.  An area that was shown and then closed is not in [layoutAreaIds], so it
	 * is not written either.
	 *
	 * New blocks enter the file in [layoutAreaIds]'s order.
	 *
	 * @return JsonObject The patch's `areas` member.
	 */
	fun gather(): JsonObject {
		val inLayout = layoutAreaIds.toHashSet()
		return buildJsonObject {
			for (staleAreaId in restoredAreas?.keys.orEmpty()) {
				if (staleAreaId !in inLayout) {
					put(staleAreaId, JsonNull)
				}
			}
			val cameras = cameraReader?.invoke().orEmpty()
			for (areaId in layoutAreaIds) {
				val spaces = scopesByAreaId[areaId]?.gather()
				val views =
					CameraSurface.entries.mapNotNull { surface ->
						cameras[AreaCameraKey(areaId, surface)]
							?.takeIf { view -> view.centerX.isFinite() && view.centerY.isFinite() && view.zoom.isFinite() }
							?.let { view -> surface to view }
					}
				if (spaces == null && views.isEmpty()) {
					continue
				}
				put(
					areaId,
					buildJsonObject {
						// UMA §7.3: `cameras` leads the block.  A surface is named only when the engine holds a view of
						// it for the area, so one the area is not showing today keeps the view it was saved with.
						if (views.isNotEmpty()) {
							put(
								AREA_CAMERAS_MEMBER,
								buildJsonObject {
									for ((surface, view) in views) {
										put(cameraSurfaceWireName(surface), JsonArray(listOf(JsonPrimitive(view.centerX), JsonPrimitive(view.centerY), JsonPrimitive(view.zoom))))
									}
								},
							)
						}
						spaces?.forEach { (memberName, member) -> put(memberName, member) }
					},
				)
			}
		}
	}
}

/**
 * The open document's area view states, or null outside a document (previews, tests), where a leaf keeps a scope
 * of its own.
 *
 * Static because the holder is swapped whole with the document and never mutated as a value.
 */
val LocalAreaViewStates = staticCompositionLocalOf<AreaViewStates?> { null }