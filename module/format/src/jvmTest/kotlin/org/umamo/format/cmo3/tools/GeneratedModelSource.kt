package org.umamo.format.cmo3.tools

import java.io.File

/*
 * A reader for the previously generated model, so ModelGenerator can ADD to it instead of replacing
 * it.
 *
 * Why this exists: the generator only ever sees what the corpus exercises, so a regenerate from
 * samples alone is destructive.  Measured against the 4-sample corpus it dropped 31 enum constants,
 * 7 whole enums, 29 classes, and 128 fields -- every one of them a real part of the format that
 * simply had no sample.  Worse, nothing would catch it: a constant no sample uses fails no test, and
 * a dropped class falls back to verbatim and still round-trips byte-identical.  So the previous
 * output is an input, and generation is a union.
 *
 * The file is machine-written to a rigid shape, which is what makes parsing it reasonable rather
 * than reckless.  Both shapes it emits are handled: a class with a body, and the brace-less
 * `class X : Y()` form used for an empty one.
 */

/** One serialized field of a generated class, kept verbatim so re-emission is faithful. */
internal class ModelField(
	/** The on-disk `xs.n` name, which is what sample inference matches against. */
	val serialName: String,
	/** The property lines exactly as previously emitted, annotations included. */
	val lines: List<String>,
) {
	/** True when the field already carries @DontSerializeIfDefault. */
	val dontSerializeIfDefault: Boolean get() = lines.any { it.trim() == "@DontSerializeIfDefault" }
}

/** One generated class, with its fields in declaration order -- the order the serializer emits. */
internal class ModelClass(
	val tag: String,
	val declaration: String,
	val fields: MutableList<ModelField> = mutableListOf(),
) {
	/** True for the brace-less `class X : Y()` form, which has no body to merge into. */
	val isBodyless: Boolean get() = !declaration.trimEnd().endsWith("{")
}

/** The previously generated model: enum values and classes, both keyed by serial tag. */
internal class GeneratedModelSource(
	val enums: MutableMap<String, MutableSet<String>> = linkedMapOf(),
	val classes: MutableMap<String, ModelClass> = linkedMapOf(),
)

private val SERIAL_TAG = Regex("""^@SerialTag\("([^"]+)"\)$""")
private val ENUM_DECLARATION = Regex("""^public enum class \w+ \{([^}]*)}$""")
private val CLASS_DECLARATION = Regex("""^public (?:open )?class \w+""")
private val PROPERTY_DECLARATION = Regex("""^\tpublic (?:open )?var `([^`]+)`:""")
private val SERIAL_NAME = Regex("""^\t@SerialName\("([^"]+)"\)$""")

/**
 * Parses a previously generated model file, or returns an empty model when it does not exist.
 *
 * Splitting strictly on `@SerialTag` boundaries is deliberate: an earlier brace-matching attempt
 * silently swallowed every declaration following a brace-less `class X : Y()`, and reported eleven
 * classes as missing that were present all along.  Anchoring on the tag line cannot drift that way.
 *
 * @param File file The generated model file.
 * @return GeneratedModelSource The parsed model; empty when the file is absent.
 */
internal fun parseGeneratedModel(file: File): GeneratedModelSource {
	val model = GeneratedModelSource()
	if (!file.isFile) {
		return model
	}
	val lines = file.readLines()
	var index = 0
	while (index < lines.size) {
		val tagMatch = SERIAL_TAG.find(lines[index].trim())
		if (tagMatch == null) {
			index++
			continue
		}
		val tag = tagMatch.groupValues[1]
		index++
		if (index >= lines.size) {
			break
		}
		val declaration = lines[index]
		val enumMatch = ENUM_DECLARATION.find(declaration.trim())
		if (enumMatch != null) {
			model.enums[tag] = enumMatch.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
			index++
			continue
		}
		if (!CLASS_DECLARATION.containsMatchIn(declaration.trim())) {
			continue
		}
		val modelClass = ModelClass(tag, declaration)
		model.classes[tag] = modelClass
		index++
		if (modelClass.isBodyless) {
			continue
		}
		// Accumulate annotation lines until the property they decorate closes the field.
		val pending = mutableListOf<String>()
		var serialNameOverride: String? = null
		while (index < lines.size && lines[index] != "}") {
			val line = lines[index]
			SERIAL_NAME.find(line)?.let { serialNameOverride = it.groupValues[1] }
			pending += line
			val propertyMatch = PROPERTY_DECLARATION.find(line)
			if (propertyMatch != null) {
				// @SerialName carries the real on-disk name when it is not a legal Kotlin identifier.
				val serialName = serialNameOverride ?: propertyMatch.groupValues[1]
				modelClass.fields += ModelField(serialName, pending.toList())
				pending.clear()
				serialNameOverride = null
			}
			index++
		}
	}
	return model
}

/**
 * Merges two field orders, preserving both, so a new field lands where the format actually puts it.
 *
 * Order is load-bearing: the serializer emits fields in `declaredFields` order (see
 * Serializers.buildProperties), so appending a newly discovered field would move it in the XML and
 * break byte-identity.  A longest-common-subsequence merge keeps every field of both inputs in their
 * relative positions, anchored on the fields the two agree about.
 *
 * @param List<String> existing The order already generated.
 * @param List<String> inferred The order observed in the samples.
 * @return List<String> The merged order, containing the union of both.
 */
internal fun mergeFieldOrder(existing: List<String>, inferred: List<String>): List<String> {
	val lengths = Array(existing.size + 1) { IntArray(inferred.size + 1) }
	for (existingIndex in existing.indices.reversed()) {
		for (inferredIndex in inferred.indices.reversed()) {
			lengths[existingIndex][inferredIndex] =
				if (existing[existingIndex] == inferred[inferredIndex]) {
					lengths[existingIndex + 1][inferredIndex + 1] + 1
				} else {
					maxOf(lengths[existingIndex + 1][inferredIndex], lengths[existingIndex][inferredIndex + 1])
				}
		}
	}
	val merged = mutableListOf<String>()
	var existingIndex = 0
	var inferredIndex = 0
	while (existingIndex < existing.size && inferredIndex < inferred.size) {
		when {
			existing[existingIndex] == inferred[inferredIndex] -> {
				merged += existing[existingIndex]
				existingIndex++
				inferredIndex++
			}
			// Off the common subsequence: emit from whichever side the LCS says to advance, so both
			// inputs' relative orders survive.
			lengths[existingIndex + 1][inferredIndex] >= lengths[existingIndex][inferredIndex + 1] -> {
				if (existing[existingIndex] !in merged) {
					merged += existing[existingIndex]
				}
				existingIndex++
			}
			else -> {
				if (inferred[inferredIndex] !in merged) {
					merged += inferred[inferredIndex]
				}
				inferredIndex++
			}
		}
	}
	while (existingIndex < existing.size) {
		if (existing[existingIndex] !in merged) {
			merged += existing[existingIndex]
		}
		existingIndex++
	}
	while (inferredIndex < inferred.size) {
		if (inferred[inferredIndex] !in merged) {
			merged += inferred[inferredIndex]
		}
		inferredIndex++
	}
	return merged
}
