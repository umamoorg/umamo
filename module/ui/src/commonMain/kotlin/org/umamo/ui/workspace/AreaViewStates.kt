package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.render.ViewportCamera

/**
 * Every area's view state for ONE open document: the scope each area parks its space state on, seeded from what
 * the document was saved with and gathered back for its next save (docs/format/UMA.md §7.3).
 *
 * The scopes live here rather than in each leaf's composition so their lifetime is the document's.  A workspace
 * tab switch disposes the inactive tree and recomposes it on return; with the scope held by the leaf, every space
 * would come back at its defaults.  A new document gets a new holder, so nothing carries from one rig to the next.
 *
 * The area ids are the application layout's own (`interface.layout`), borrowed as opaque keys (UMA D31): the
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
	 * Reads every area's camera from the platform's render service, parked here by whoever builds that service
	 * and cleared when it goes; null where there is none (Android today).  The holder is the rendezvous because
	 * both sides already meet at it by area id - the save path never has to reach into the viewport.
	 */
	var cameraReader: (() -> Map<String, ViewportCamera>)? = null

	/**
	 * The cameras the document was saved with, by area id (UMA §7.3): `[centerX, centerY, zoom]` with a zoom above
	 * zero.  Anything else is skipped, and that area fits its content as a fresh one does.
	 *
	 * @return Map The saved cameras.
	 */
	fun restoredCameras(): Map<String, ViewportCamera> {
		val cameras = HashMap<String, ViewportCamera>()
		for ((areaId, block) in restoredAreas.orEmpty()) {
			val components = (block as? JsonObject)?.let { areaBlock -> floatListOf(areaBlock, AREA_CAMERA_MEMBER, 3) } ?: continue
			val (centerX, centerY, zoom) = components
			if (zoom > 0f) {
				cameras[areaId] = ViewportCamera(centerX, centerY, zoom)
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
				val camera = cameras[areaId]?.takeIf { view -> view.centerX.isFinite() && view.centerY.isFinite() && view.zoom.isFinite() }
				if (spaces == null && camera == null) {
					continue
				}
				put(
					areaId,
					buildJsonObject {
						// UMA §7.3: `camera` leads the block.  It is named only when the engine holds one for the area; an
						// area showing another space today keeps the view it was saved with for the day it shows a viewport again.
						camera?.let { view -> put(AREA_CAMERA_MEMBER, JsonArray(listOf(JsonPrimitive(view.centerX), JsonPrimitive(view.centerY), JsonPrimitive(view.zoom)))) }
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