package org.umamo.format.uma.puppet

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaIdentityTable
import org.umamo.format.uma.UmaListRule
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaScratchBuffer
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.decodeUmaEntry
import org.umamo.format.uma.firstAccessorProblem
import org.umamo.format.uma.firstIdentityProblem
import org.umamo.format.uma.identityByStringKey
import org.umamo.format.uma.jsonStringOrNull
import org.umamo.format.uma.umaEntryJsonWithBuffers

/**
 * The puppet entry's codec over its JSON tree: decoding with every rule UMA §4 sets, encoding for a save,
 * and the element identities its arrays merge by.
 */
internal object UmaPuppetEntry {
	// Separates the two ids of a composite identity: the NUL character, which no id contains.  Built with Char(0)
	// rather than written as a character literal, so the source stays plain text.
	private val IDENTITY_SEPARATOR: Char = Char(0)

	/**
	 * UMA §4.7: how each array of objects in the puppet entry matches elements across a save.  A parameter,
	 * part, deformer, or drawable is its id; a link is its pair; a parameter-tree node and an org ref are
	 * their id qualified by which kind of id it is, so a part and a drawable that share a raw id stay apart.
	 * A grid axis, a blend shape, and a limit are their parameter; a cell is its key value on each axis; a glue is
	 * its ordered mesh pair (D11).  Blend forms and limit points are values, replaced whole (D10).
	 */
	val identities: UmaIdentityTable =
		UmaIdentityTable(
			mapOf(
				UmaParameter.serializer().descriptor.serialName to identityByStringKey("id"),
				UmaPart.serializer().descriptor.serialName to identityByStringKey("id"),
				UmaDeformer.serializer().descriptor.serialName to identityByStringKey("id"),
				UmaDrawable.serializer().descriptor.serialName to identityByStringKey("id"),
				UmaParameterLink.serializer().descriptor.serialName to byKeyPair("horizontal", "vertical"),
				UmaParameterNode.serializer().descriptor.serialName to byOneOfKeys("parameter", "group"),
				UmaOrgRef.serializer().descriptor.serialName to byOneOfKeys("part", "drawable"),
				UmaAxis.serializer().descriptor.serialName to identityByStringKey("parameter"),
				UmaMeshCell.serializer().descriptor.serialName to UmaListRule.ByIdentity(::cellIdentitiesIn),
				UmaDeformerCell.serializer().descriptor.serialName to UmaListRule.ByIdentity(::cellIdentitiesIn),
				UmaChannelCell.serializer().descriptor.serialName to UmaListRule.ByIdentity(::cellIdentitiesIn),
				UmaMeshBlendShape.serializer().descriptor.serialName to identityByStringKey("parameter"),
				UmaDeformerBlendShape.serializer().descriptor.serialName to identityByStringKey("parameter"),
				UmaPartBlendShape.serializer().descriptor.serialName to identityByStringKey("parameter"),
				UmaBlendLimit.serializer().descriptor.serialName to identityByStringKey("parameter"),
				UmaGlue.serializer().descriptor.serialName to byKeyPair("meshA", "meshB"),
				UmaMeshForm.serializer().descriptor.serialName to UmaListRule.Whole,
				UmaDeformerForm.serializer().descriptor.serialName to UmaListRule.Whole,
				UmaPartForm.serializer().descriptor.serialName to UmaListRule.Whole,
				UmaBlendLimitPoint.serializer().descriptor.serialName to UmaListRule.Whole,
			),
		)

	/**
	 * Decodes a puppet entry's tree, failing loudly on anything the schema does not allow.
	 *
	 * @param JsonObject tree    The entry's JSON.
	 * @param String     path    The entry's path, for the failure.
	 * @param Function   buffers Resolves a buffer path to its bytes, or null when there is no such buffer.
	 * @return UmaPuppet The puppet.
	 * @throws UmaFormatException When the tree breaks the schema or a shape rule, names bytes a buffer does not hold,
	 *   or repeats an identity.
	 */
	fun decode(tree: JsonObject, path: String, buffers: (String) -> ByteArray?): UmaPuppet {
		// UMA §4.9: every accessor is checked, including those under keys this reader does not know, since a
		// save copies their bytes too.
		firstAccessorProblem(tree, buffers)?.let { problem ->
			throw UmaFormatException(UmaReadFailure.MalformedEntry(path, problem))
		}
		val puppet = decodeUmaEntry(umaEntryJsonWithBuffers(buffers, null), UmaPuppet.serializer(), tree, path)
		// UMA §4.8: the shape rules come before identities, so a coordinate outside its axis is reported as that
		// rather than as a cell with no identity.
		val problem = UmaPuppetShape.firstProblem(puppet) ?: firstIdentityProblem(tree, UmaPuppet.serializer().descriptor, identities, "")
		if (problem != null) {
			throw UmaFormatException(UmaReadFailure.MalformedEntry(path, problem))
		}
		return puppet
	}

	/**
	 * Encodes a puppet for a save, its bulk arrays appended to [scratch] and named by accessors into it.
	 *
	 * @param UmaPuppet        puppet  The puppet.
	 * @param String           path    The entry's path, for the failure.
	 * @param UmaScratchBuffer scratch The buffer new arrays append to.
	 * @return JsonObject The entry's JSON, before any merge or buffer layout.
	 * @throws UmaWriteException When a value cannot be written, such as a non-finite inline float, or the puppet
	 *   breaks a rule a reader would refuse.
	 */
	fun encode(puppet: UmaPuppet, path: String, scratch: UmaScratchBuffer): JsonObject {
		// UMA §4.8: a reader refuses a puppet that breaks a shape rule or repeats an identity, so a save that wrote one
		// could never be reopened.
		UmaPuppetShape.firstProblem(puppet)?.let { problem -> throw UmaWriteException(path, problem) }
		val tree =
			try {
				umaEntryJsonWithBuffers({ null }, scratch).encodeToJsonElement(UmaPuppet.serializer(), puppet) as JsonObject
			} catch (failure: SerializationException) {
				// UMA §4.1: JSON has no NaN or infinity, so a non-finite value refuses the save.
				throw UmaWriteException(path, failure.message.orEmpty(), failure)
			}
		firstIdentityProblem(tree, UmaPuppet.serializer().descriptor, identities, "")?.let { problem -> throw UmaWriteException(path, problem) }
		return tree
	}

	/**
	 * The rule matching elements by the ordered pair of strings at [firstKey] and [secondKey].
	 *
	 * @param String firstKey  The first identifying key.
	 * @param String secondKey The second identifying key.
	 * @return UmaListRule The rule.
	 */
	private fun byKeyPair(firstKey: String, secondKey: String): UmaListRule =
		UmaListRule.ByIdentity { _ ->
			{ element ->
				val first = jsonStringOrNull(element[firstKey])
				val second = jsonStringOrNull(element[secondKey])
				if (first == null || second == null) null else "$first$IDENTITY_SEPARATOR$second"
			}
		}

	/**
	 * The rule matching elements that hold exactly one of two kinds of id by that id, qualified by its kind: the key
	 * it sits under.
	 *
	 * @param String firstKey  The first kind's key.
	 * @param String secondKey The second kind's key.
	 * @return UmaListRule The rule; an element holding neither id or both has no identity.
	 */
	private fun byOneOfKeys(firstKey: String, secondKey: String): UmaListRule =
		UmaListRule.ByIdentity { _ ->
			{ element ->
				val firstId = jsonStringOrNull(element[firstKey])
				val secondId = jsonStringOrNull(element[secondKey])
				when {
					firstId != null && secondId == null -> "$firstKey:$firstId"
					secondId != null && firstId == null -> "$secondKey:$secondId"
					else -> null
				}
			}
		}

	/**
	 * One grid axis as a cell's identity reads it.
	 *
	 * @property String      parameter   The axis's parameter.
	 * @property List<Float> keys        The axis's keys, a negative zero read as zero.
	 * @property IntArray    occurrences Each key's count of the equal keys before it.
	 */
	private class IdentityAxis(
		val parameter: String,
		val keys: List<Float>,
		val occurrences: IntArray,
	)

	/**
	 * The reader of a cell's identity in [grid] (UMA §4.7): the key value the cell sits at on each axis, named by the
	 * axis's parameter and in parameter order, so a key added before the cell or a reordering of the axes leaves it
	 * the same cell.  A repeated key is told apart by its position among the keys equal to it.  The axes are read once
	 * per grid; a cell whose coordinate does not resolve through them has no identity, which the shape rules report
	 * first.
	 *
	 * @param JsonObject grid The grid holding the cells.
	 * @return Function The identity reader.
	 */
	private fun cellIdentitiesIn(grid: JsonObject): (JsonObject) -> String? {
		val axisTrees = grid["axes"] as? JsonArray ?: return { null }
		val axes = ArrayList<IdentityAxis>(axisTrees.size)
		for (axisTree in axisTrees) {
			val axis = axisTree as? JsonObject ?: return { null }
			val parameter = jsonStringOrNull(axis["parameter"]) ?: return { null }
			val keyTrees = axis["keys"] as? JsonArray ?: return { null }
			val keys =
				keyTrees.map { keyTree ->
					val key = (keyTree as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.floatOrNull ?: return { null }
					if (key == 0f) 0f else key
				}
			axes += IdentityAxis(parameter, keys, IntArray(keys.size) { keyIndex -> (0 until keyIndex).count { earlierIndex -> keys[earlierIndex] == keys[keyIndex] } })
		}
		val axisOrder = axes.indices.sortedBy { axisIndex -> axes[axisIndex].parameter }
		return identity@{ cell ->
			val coordinate = cell["coordinate"] as? JsonArray ?: return@identity null
			if (coordinate.size != axes.size) {
				return@identity null
			}
			val keyIndices = IntArray(axes.size)
			for ((axisIndex, component) in coordinate.withIndex()) {
				val keyIndex = (component as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.intOrNull ?: return@identity null
				if (keyIndex !in axes[axisIndex].keys.indices) {
					return@identity null
				}
				keyIndices[axisIndex] = keyIndex
			}
			axisOrder.joinToString(IDENTITY_SEPARATOR.toString()) { axisIndex ->
				val axis = axes[axisIndex]
				val keyIndex = keyIndices[axisIndex]
				val occurrence = axis.occurrences[keyIndex]
				if (occurrence == 0) "${axis.parameter}=${axis.keys[keyIndex]}" else "${axis.parameter}=${axis.keys[keyIndex]}#$occurrence"
			}
		}
	}
}