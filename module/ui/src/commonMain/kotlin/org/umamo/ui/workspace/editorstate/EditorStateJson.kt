package org.umamo.ui.workspace.editorstate

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import org.umamo.ui.viewport.CameraSurface

/*
 * The editor entry's reading and writing kit (docs/format/UMA.md §7).  The entry is a dynamic tree read
 * tolerantly: every reader here answers null for a member that is absent, of the wrong JSON type, or out of
 * range, and the caller keeps its default - nothing in editor state is worth refusing a file over (UMA §7.1).
 * Every writer names a member whatever its state, a JSON null standing for "at the default", because the entry
 * is saved as a merge patch and a merge alone could never take a deviation back out (UMA §7.5).
 *
 * The session block is in SessionStateJson.kt, and the per-area blocks are gathered and restored by
 * AreaViewStates.kt.
 */

/** The root member holding each area's block, keyed by area id (UMA §7.2). */
const val EDITOR_STATE_AREAS: String = "areas"

/** An area block's cameras member: a `[centerX, centerY, zoom]` per surface the area has shown (UMA §7.3). */
internal const val AREA_CAMERAS_MEMBER: String = "cameras"

/**
 * A camera surface's name in an area block's `cameras`.
 *
 * @param CameraSurface surface The surface.
 * @return String The wire name.
 */
internal fun cameraSurfaceWireName(surface: CameraSurface): String =
	// UMA §7.3: `cameras`.
	when (surface) {
		CameraSurface.Viewport -> "viewport"
		CameraSurface.Uv -> "uv"
	}

/**
 * The strings of the array at [memberName], or null when the member is absent or not an array.  An element that
 * is not a string is skipped.
 *
 * @param JsonObject tree       The object holding the member.
 * @param String     memberName The member's name.
 * @return List<String>? The strings, or null.
 */
internal fun stringListOf(tree: JsonObject, memberName: String): List<String>? =
	(tree[memberName] as? JsonArray)?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }

/**
 * The string at [memberName], or null when the member is absent or not a string.
 *
 * @param JsonObject tree       The object holding the member.
 * @param String     memberName The member's name.
 * @return String? The string, or null.
 */
internal fun stringOf(tree: JsonObject, memberName: String): String? = (tree[memberName] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content

/**
 * The boolean at [memberName], or null when the member is absent or not a boolean.
 *
 * @param JsonObject tree       The object holding the member.
 * @param String     memberName The member's name.
 * @return Boolean? The boolean, or null.
 */
internal fun booleanOf(tree: JsonObject, memberName: String): Boolean? = (tree[memberName] as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.booleanOrNull

/**
 * The finite float [element] holds, or null when it is not a number, or is a NaN or an infinity.
 *
 * @param JsonElement? element The element.
 * @return Float? The float, or null.
 */
internal fun finiteFloatOf(element: JsonElement?): Float? = (element as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.floatOrNull?.takeIf { value -> value.isFinite() }

/**
 * The integer [element] holds, or null when it is not one.
 *
 * @param JsonElement? element The element.
 * @return Int? The integer, or null.
 */
internal fun intOf(element: JsonElement?): Int? = (element as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.intOrNull

/**
 * The array at [memberName] as exactly [size] finite floats, or null when the member is absent, not an array, of
 * another length, or holds anything that is not a finite number.
 *
 * @param JsonObject tree       The object holding the member.
 * @param String     memberName The member's name.
 * @param Int        size       The length the array must have.
 * @return List<Float>? The floats, or null.
 */
internal fun floatListOf(tree: JsonObject, memberName: String, size: Int): List<Float>? {
	val elements = tree[memberName] as? JsonArray ?: return null

	if (elements.size != size) {
		return null
	}

	val floats = elements.mapNotNull(::finiteFloatOf)

	return floats.takeIf { kept -> kept.size == size }
}

/**
 * [values] as a JSON array of strings in a stable order, or a JSON null when there are none - the form an id
 * collection takes in the entry (UMA §7.1), sorted so the same state always writes the same bytes.
 *
 * @param Collection<String> values The ids.
 * @return JsonElement The array, or null.
 */
internal fun stringArrayOrNull(values: Collection<String>): JsonElement = if (values.isEmpty()) JsonNull else JsonArray(values.sorted().map(::JsonPrimitive))

/**
 * A fold map's deviations from its default rule, as the pair of arrays the entry holds: the ids opened against
 * the default and the ids closed against it.  An id whose recorded state equals its default is no deviation and
 * is left out.
 *
 * @param Map      states      Each recorded node's open state.
 * @param Function defaultOpen Whether an id is open when nothing is recorded for it.
 * @return Pair The opened ids, then the closed ones, each as [stringArrayOrNull] writes them.
 */
internal fun foldDeviationsOf(states: Map<String, Boolean>, defaultOpen: (String) -> Boolean): Pair<JsonElement, JsonElement> {
	val opened = states.filter { (nodeId, open) -> open && !defaultOpen(nodeId) }.keys
	val closed = states.filter { (nodeId, open) -> !open && defaultOpen(nodeId) }.keys

	return stringArrayOrNull(opened) to stringArrayOrNull(closed)
}

/**
 * A fold map written as recorded, as the pair of arrays the entry holds: the ids recorded open and the ids
 * recorded closed.  The counterpart of [foldDeviationsOf] for a map that never records a node at its default
 * (the sidebar's), or that keeps what the rigger set even where it matches the default (the parameters panel's).
 *
 * @param Map      states Each recorded node's open state.
 * @param Function idOf   A node's id as the entry names it.
 * @return Pair The open ids, then the closed ones, each as [stringArrayOrNull] writes them.
 */
internal fun <NodeT> recordedFoldsOf(states: Map<NodeT, Boolean>, idOf: (NodeT) -> String): Pair<JsonElement, JsonElement> =
	stringArrayOrNull(states.filterValues { open -> open }.keys.map(idOf)) to stringArrayOrNull(states.filterValues { open -> !open }.keys.map(idOf))

/**
 * A width as the entry holds it: a JSON null at [default], else its value in density-independent pixels.
 *
 * @param Dp value   The width.
 * @param Dp default The width an untouched state has.
 * @return JsonElement The member value.
 */
internal fun dpOrNull(value: Dp, default: Dp): JsonElement = if (value == default) JsonNull else JsonPrimitive(value.value)

/**
 * The width [element] holds, held between [min] and [max], or null when it is not a finite number above zero: a
 * width at or below zero is no width at all, so the caller keeps its default rather than the narrowest.
 *
 * @param JsonElement? element The element.
 * @param Dp           min     The narrowest the width may be.
 * @param Dp           max     The widest the width may be.
 * @return Dp? The width, or null.
 */
internal fun positiveDpOf(element: JsonElement?, min: Dp, max: Dp): Dp? = finiteFloatOf(element)?.takeIf { value -> value > 0f }?.dp?.coerceIn(min, max)

/**
 * Lays a saved fold state into [states]: what was recorded goes, the opened ids are open, and the closed ids are
 * closed.
 *
 * @param MutableMap states     The fold map to fill.
 * @param JsonObject tree       The object holding the two arrays.
 * @param String     openedName The member naming the opened ids.
 * @param String     closedName The member naming the closed ids.
 */
internal fun restoreFoldStates(states: MutableMap<String, Boolean>, tree: JsonObject, openedName: String, closedName: String) {
	states.clear()
	stringListOf(tree, openedName)?.forEach { nodeId -> states[nodeId] = true }
	stringListOf(tree, closedName)?.forEach { nodeId -> states[nodeId] = false }
}