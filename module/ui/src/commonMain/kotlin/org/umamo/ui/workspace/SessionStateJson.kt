package org.umamo.ui.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.edit.Cursor2d
import org.umamo.edit.DEFAULT_PROPORTIONAL_EDIT_STATE
import org.umamo.edit.EditorMode
import org.umamo.edit.GridConfig
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.ParameterSelection
import org.umamo.edit.Pose
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SessionViewState
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.UvCursor
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/*
 * The editor entry's session block (docs/format/UMA.md §7.4): the pose and the session state a document was
 * saved with.  Read tolerantly and written with every known member named, like the area blocks (§7.1, §7.5).
 * Nothing here validates an id against the model - the session does that when it opens (SessionViewState.fittedTo)
 * and the loader clamps the pose - so this file stays a plain mapping between the tree and the data.
 */

/** The root member holding the session block (UMA §7.2). */
const val EDITOR_STATE_SESSION: String = "session"

/**
 * The session block for a save.
 *
 * @param SessionViewState viewState The session's state as gathered.
 * @param Pose             pose      The session's pose as gathered.
 * @param PuppetModel      model     The model being saved, whose parameter defaults decide which pose values deviate.
 * @return JsonObject The block, every known member named.
 */
fun sessionStateJson(viewState: SessionViewState, pose: Pose, model: PuppetModel): JsonObject =
	buildJsonObject {
		// UMA §7.4: `pose` lists each parameter away from its default, in the model's parameter order.
		val posed =
			model.parameters.mapNotNull { parameter ->
				val value = pose[parameter.id]?.takeIf { posedValue -> posedValue.isFinite() && posedValue != parameter.default } ?: return@mapNotNull null
				buildJsonObject {
					put("parameter", JsonPrimitive(parameter.id.raw))
					put("value", JsonPrimitive(value))
				}
			}
		put("pose", if (posed.isEmpty()) JsonNull else JsonArray(posed))
		put(
			"selection",
			if (viewState.selection.isEmpty) {
				JsonNull
			} else {
				buildJsonObject {
					put("targets", JsonArray(viewState.selection.targets.map { target -> JsonPrimitive(objectReferenceOf(target)) }))
					put("active", viewState.selection.active?.let { active -> JsonPrimitive(objectReferenceOf(active)) } ?: JsonNull)
				}
			},
		)
		put(
			"parameterSelection",
			if (viewState.parameterSelection.isEmpty) {
				JsonNull
			} else {
				buildJsonObject {
					put("ids", JsonArray(viewState.parameterSelection.ids.map { parameterId -> JsonPrimitive(parameterId.raw) }))
					put("active", viewState.parameterSelection.active?.let { active -> JsonPrimitive(active.raw) } ?: JsonNull)
				}
			},
		)
		put("mode", if (viewState.mode == EditorMode.Edit) JsonPrimitive("edit") else JsonNull)
		put("selectMode", if (viewState.selectMode == MeshSelectMode.Vertex) JsonNull else JsonPrimitive(selectModeWireName(viewState.selectMode)))
		put("cursor2d", viewState.cursor2d?.let { cursor -> floatPairOrNull(cursor.worldX, cursor.worldY) } ?: JsonNull)
		put("uvCursor", viewState.uvCursor?.let { cursor -> floatPairOrNull(cursor.u, cursor.v) } ?: JsonNull)
		put("pivot", if (viewState.pivotMode == TransformPivotMode.MedianPoint) JsonNull else JsonPrimitive(pivotWireName(viewState.pivotMode)))
		put(
			"proportional",
			viewState.proportionalSettings?.takeIf { settings -> viewState.proportionalEnabled || settings != DEFAULT_PROPORTIONAL_SETTINGS }?.let { settings ->
				buildJsonObject {
					put("enabled", if (viewState.proportionalEnabled) JsonPrimitive(true) else JsonNull)
					put("falloff", JsonPrimitive(falloffWireName(settings.falloff)))
					put("radius", JsonPrimitive(settings.radiusWorld))
					put("connectedOnly", if (settings.connectedOnly) JsonPrimitive(true) else JsonNull)
				}
			} ?: JsonNull,
		)
		put(
			"grid",
			viewState.gridConfig?.let { grid ->
				buildJsonObject {
					put("scale", JsonPrimitive(grid.scale))
					put("subdivisions", JsonPrimitive(grid.subdivisions))
				}
			} ?: JsonNull,
		)
	}

/**
 * The session state a document was saved with, or null when it saved none.
 *
 * @param JsonObject? tree The session block as the file held it, or null.
 * @return SessionViewState? The state, not yet fitted to any model.
 */
fun sessionViewStateOf(tree: JsonObject?): SessionViewState? {
	if (tree == null) {
		return null
	}
	val selection = tree["selection"] as? JsonObject
	val targets = selection?.let { block -> stringListOf(block, "targets") }.orEmpty().mapNotNullTo(LinkedHashSet(), ::selectionTargetOf)
	val parameterSelection = tree["parameterSelection"] as? JsonObject
	val parameterIds = parameterSelection?.let { block -> stringListOf(block, "ids") }.orEmpty().mapTo(LinkedHashSet(), ::ParameterId)
	val proportional = tree["proportional"] as? JsonObject
	val grid = tree["grid"] as? JsonObject
	return SessionViewState(
		selection = Selection(targets, selection?.let { block -> stringOf(block, "active") }?.let(::selectionTargetOf)),
		parameterSelection = ParameterSelection(parameterIds, parameterSelection?.let { block -> stringOf(block, "active") }?.let(::ParameterId)),
		mode = if (stringOf(tree, "mode") == "edit") EditorMode.Edit else EditorMode.Object,
		selectMode = stringOf(tree, "selectMode")?.let { wireName -> MeshSelectMode.entries.firstOrNull { mode -> selectModeWireName(mode) == wireName } } ?: MeshSelectMode.Vertex,
		cursor2d = floatListOf(tree, "cursor2d", 2)?.let { (worldX, worldY) -> Cursor2d(worldX, worldY) },
		uvCursor = floatListOf(tree, "uvCursor", 2)?.let { (u, v) -> UvCursor(u, v) },
		pivotMode = stringOf(tree, "pivot")?.let { wireName -> TransformPivotMode.entries.firstOrNull { mode -> pivotWireName(mode) == wireName } } ?: TransformPivotMode.MedianPoint,
		proportionalEnabled = proportional?.let { block -> booleanOf(block, "enabled") } ?: false,
		proportionalSettings =
			proportional?.let { block ->
				ProportionalEditState(
					falloff = stringOf(block, "falloff")?.let { wireName -> ProportionalFalloff.entries.firstOrNull { falloff -> falloffWireName(falloff) == wireName } } ?: DEFAULT_PROPORTIONAL_SETTINGS.falloff,
					radiusWorld = finiteFloatOf(block["radius"])?.takeIf { radius -> radius > 0f } ?: DEFAULT_PROPORTIONAL_SETTINGS.radiusWorld,
					connectedOnly = booleanOf(block, "connectedOnly") ?: false,
				)
			},
		// UMA §7.4: a grid needs a positive scale and at least one subdivision; anything else follows the application's.
		gridConfig =
			grid?.let { block ->
				val scale = finiteFloatOf(block["scale"])?.takeIf { value -> value > 0f }
				val subdivisions = intOf(block["subdivisions"])?.takeIf { value -> value >= 1 }
				if (scale != null && subdivisions != null) GridConfig(scale, subdivisions) else null
			},
	)
}

/**
 * The pose a document was saved with, by parameter id: only what the file listed, unclamped and unchecked against
 * any model - the loader does both as it builds the live parameters.
 *
 * @param JsonObject? tree The session block as the file held it, or null.
 * @return Map The saved values, empty when the file saved none.
 */
fun savedPoseOf(tree: JsonObject?): Map<ParameterId, Float> {
	val entries = tree?.get("pose") as? JsonArray ?: return emptyMap()
	val pose = LinkedHashMap<ParameterId, Float>()
	for (entry in entries) {
		val block = entry as? JsonObject ?: continue
		val parameterId = stringOf(block, "parameter") ?: continue
		val value = finiteFloatOf(block["value"]) ?: continue
		pose[ParameterId(parameterId)] = value
	}
	return pose
}

/** The proportional configuration a session starts with, which a save leaves out while proportional editing is off. */
private val DEFAULT_PROPORTIONAL_SETTINGS: ProportionalEditState = DEFAULT_PROPORTIONAL_EDIT_STATE

/**
 * Two floats as the entry's pair form, or a JSON null when either is not finite (UMA §7.1).
 *
 * @param Float first  The first component.
 * @param Float second The second component.
 * @return JsonElement The pair, or null.
 */
private fun floatPairOrNull(first: Float, second: Float): JsonElement =
	if (first.isFinite() && second.isFinite()) JsonArray(listOf(JsonPrimitive(first), JsonPrimitive(second))) else JsonNull

/**
 * A selection target as an object reference (UMA §7.1): `part:`, `drawable:`, or `deformer:` plus the model's id.
 *
 * @param SelectionTarget target The target.
 * @return String The reference.
 */
internal fun objectReferenceOf(target: SelectionTarget): String =
	when (target) {
		is SelectionTarget.Part -> "part:${target.id.raw}"
		is SelectionTarget.Drawable -> "drawable:${target.id.raw}"
		is SelectionTarget.Deformer -> "deformer:${target.id.raw}"
	}

/**
 * The selection target an object reference names, or null for a reference of no known kind.
 *
 * @param String reference The reference.
 * @return SelectionTarget? The target.
 */
internal fun selectionTargetOf(reference: String): SelectionTarget? =
	when {
		reference.startsWith("part:") -> SelectionTarget.Part(PartId(reference.removePrefix("part:")))
		reference.startsWith("drawable:") -> SelectionTarget.Drawable(DrawableId(reference.removePrefix("drawable:")))
		reference.startsWith("deformer:") -> SelectionTarget.Deformer(DeformerId(reference.removePrefix("deformer:")))
		else -> null
	}

/**
 * A mesh select mode's name in the editor entry.
 *
 * @param MeshSelectMode mode The mode.
 * @return String The wire name.
 */
private fun selectModeWireName(mode: MeshSelectMode): String =
	// UMA §7.4: `selectMode`.
	when (mode) {
		MeshSelectMode.Vertex -> "vertex"
		MeshSelectMode.Edge -> "edge"
		MeshSelectMode.Face -> "face"
	}

/**
 * A pivot mode's name in the editor entry.
 *
 * @param TransformPivotMode mode The mode.
 * @return String The wire name.
 */
private fun pivotWireName(mode: TransformPivotMode): String =
	// UMA §7.4: `pivot`.
	when (mode) {
		TransformPivotMode.MedianPoint -> "medianPoint"
		TransformPivotMode.IndividualOrigins -> "individualOrigins"
		TransformPivotMode.ActiveElement -> "activeElement"
		TransformPivotMode.Cursor -> "cursor"
	}

/**
 * A proportional falloff's name in the editor entry.
 *
 * @param ProportionalFalloff falloff The falloff.
 * @return String The wire name.
 */
private fun falloffWireName(falloff: ProportionalFalloff): String =
	// UMA §7.4: `proportional.falloff`.
	when (falloff) {
		ProportionalFalloff.Smooth -> "smooth"
		ProportionalFalloff.Sphere -> "sphere"
		ProportionalFalloff.Root -> "root"
		ProportionalFalloff.Sharp -> "sharp"
		ProportionalFalloff.Linear -> "linear"
		ProportionalFalloff.Constant -> "constant"
	}