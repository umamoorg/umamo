package org.umamo.format.uma.puppet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Builders for the puppet entry tests: a small puppet that holds one of every object kind, and helpers that
 * plant keys a newer writer might add into the JSON tree.
 */

/**
 * A puppet with at least one of every object kind the puppet entry holds, and a part and a drawable that
 * share a raw id so kind-qualified identities are exercised.
 *
 * @return UmaPuppet The puppet.
 */
internal fun samplePuppet(): UmaPuppet =
	UmaPuppet(
		canvasWidth = 2400f,
		canvasHeight = 3000f,
		runtimeTarget = UmaRuntimeTarget.Cubism53,
		parameters =
			listOf(
				UmaParameter("ParamA", "A", -30f, 30f, 0f),
				UmaParameter("ParamB", "B", 0f, 1f, 0f, kind = UmaParameterKind.BlendShape),
			),
		parameterLinks = listOf(UmaParameterLink("ParamA", "ParamB")),
		parameterTree =
			listOf(
				UmaParameterNode(group = "Face", name = "Face", initiallyOpen = true, children = listOf(UmaParameterNode(parameter = "ParamA"), UmaParameterNode(parameter = "ParamB"))),
			),
		rootPart = "Root",
		rootChildren = listOf(UmaOrgRef(part = "Head"), UmaOrgRef(drawable = "Head")),
		parts =
			listOf(
				UmaPart("Head", "Head", children = listOf(UmaOrgRef(drawable = "Eye")), isVisible = false, composite = UmaPartComposite(opacity = 0.5f)),
				UmaPart("Body", "Body"),
			),
		deformers = listOf(UmaDeformer("Warp1", UmaDeformerKind.Warp, "Warp", rows = 2, columns = 2, isQuadTransform = true)),
		drawables = listOf(UmaDrawable("Eye", "Eye", maskedBy = listOf("Head")), UmaDrawable("Head", "Head face")),
	)

/**
 * [tree] with [key] set to [value], appended after its existing keys.
 *
 * @param JsonObject  tree  The object.
 * @param String      key   The key.
 * @param JsonElement value The value.
 * @return JsonObject The object with the key.
 */
internal fun withKey(tree: JsonObject, key: String, value: JsonElement): JsonObject = JsonObject(LinkedHashMap(tree).also { map -> map[key] = value })

/**
 * [tree] with the array at [key] rewritten element by element.
 *
 * @param JsonObject tree      The object.
 * @param String     key       The array's key.
 * @param Function   transform Rewrites one element given its index.
 * @return JsonObject The object with the rewritten array.
 */
internal fun withArray(tree: JsonObject, key: String, transform: (Int, JsonObject) -> JsonObject): JsonObject =
	withKey(tree, key, JsonArray((tree[key] as JsonArray).mapIndexed { elementIndex, element -> transform(elementIndex, element as JsonObject) }))

/**
 * A planted marker value, telling planted keys apart.
 *
 * @param String marker The marker.
 * @return JsonObject The value.
 */
internal fun plantedValue(marker: String): JsonObject = JsonObject(mapOf("from" to JsonPrimitive("future"), "marker" to JsonPrimitive(marker)))

/**
 * The element of the array at [key] whose [identityKey] is [identityValue], or null.
 *
 * @param JsonObject tree          The object holding the array.
 * @param String     key           The array's key.
 * @param String     identityKey   The key naming each element.
 * @param String     identityValue The element's value for it.
 * @return JsonObject? The element.
 */
internal fun elementOf(tree: JsonObject, key: String, identityKey: String, identityValue: String): JsonObject? =
	(tree[key] as? JsonArray)?.map { element -> element as JsonObject }?.firstOrNull { element -> (element[identityKey] as? JsonPrimitive)?.content == identityValue }