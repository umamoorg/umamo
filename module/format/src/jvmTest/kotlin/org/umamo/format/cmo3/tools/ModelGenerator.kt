package org.umamo.format.cmo3.tools

import org.jdom.Element
import org.jdom.input.SAXBuilder
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.serialize.GUID_TAGS
import org.umamo.format.cmo3.serialize.ID_TAGS
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test

/**
 * Sample-driven code generator for the reflective Cubism model classes. Reads each sample's main.xml,
 * infers every tag's serialized field signature (name/type/order, <super> inheritance,
 * @DontSerializeIfDefault for sometimes-present fields), detects enums, and reports custom
 * (attribute-bearing) tags for hand-writing. Emits model classes + a registration function.
 *
 * ADDITIVE, never destructive. The previously generated model is an INPUT: the output is the union of
 * what is already there and what the corpus proves. This is not a nicety. The generator only ever sees
 * what the samples happen to exercise, so a replace-everything run deletes every part of the format
 * that no sample reaches -- measured against a 4-sample corpus, that was 31 enum constants, 7 whole
 * enums, 29 classes, and 128 fields. And nothing would catch it: an unused enum constant fails no
 * test, and a dropped class falls back to verbatim and still round-trips byte-identical. So the rule
 * is: add what is newly proven, keep everything else exactly as it stands.
 *
 * Run with: -Dcmo3.generate=true. Input samples come from -Dcmo3.gensample (defaults to the whole
 * cmo3/ corpus, so the union spans every version and feature available). The byte-identity gate over
 * that same corpus validates the output.
 */
class ModelGenerator {
	private val primitiveTags =
		mapOf(
			"i" to ("Int" to "0"),
			"f" to ("Float" to "0f"),
			"d" to ("Double" to "0.0"),
			"l" to ("Long" to "0L"),
			"short" to ("Short" to "0"),
			"byte" to ("Byte" to "0"),
			"char" to ("Char" to "'\\u0000'"),
			"b" to ("Boolean" to "false"),
			"s" to ("String" to "\"\""),
		)
	private val collectionTags =
		setOf(
			"array_list",
			"carray_list",
			"hash_map",
			"linked_map",
			"linked_set",
			"entry",
			"null",
			"float",
			"float-array",
			"int-array",
			"long-array",
			"short-array",
			"double-array",
			"byte-array",
			"char-array",
			"bool-array",
		)

	// Already handled by hand (custom serializers or hand-written reflective classes).
	private val handHandled =
		buildSet {
			addAll(GUID_TAGS)
			addAll(ID_TAGS)
			addAll(
				listOf(
					"CAffine",
					"GVector2",
					"CRect",
					"CColor",
					"CFloatColor",
					"CLabelColor",
					"CoordType",
					"PointInTriangle",
					"PointOnCurve",
					"MeshPointRef",
					"WarpDeformerOriginalShape",
					"RotationDeformerOriginalShape",
				),
			)
			add("root")
			add("shared")
			add("main")
			add("file")
		}

	private val neutralAttrs = setOf("xs.n", "xs.id", "xs.idx", "xs.ref", "count", "keyType")

	private class TagInfo {
		var instances = 0
		var hasChildren = false
		var superTag: String? = null
		val customAttrs = sortedSetOf<String>()
		val enumValues = sortedSetOf<String>()
		val fieldTypeTags = LinkedHashMap<String, MutableSet<String>>() // field -> observed child tags
		val fieldPresence = HashMap<String, Int>()
		val nullableFields = sortedSetOf<String>() // fields observed as <null> in some instance
		var bestOrder: List<String> = emptyList() // field order from the richest instance
		var bestOrderSize = -1
		val isSuperOf = sortedSetOf<String>()
	}

	@Test
	fun generate() {
		if (System.getProperty("cmo3.generate") != "true") return
		// Generate from cmo3.gensample if given (a comma-separated list unions several projects, e.g. the
		// feature-complete so enum constants and fields from every version are covered), else the default single sample.
		val sampleSpec =
			System.getProperty("cmo3.gensample") ?: System.getProperty("cmo3.sample")
				?: error("cmo3.sample required")
		val sampleFiles = sampleSpec.split(',').map { File(it.trim()) }.filter { it.isFile }
		require(sampleFiles.isNotEmpty()) { "no readable sample in cmo3.gensample/cmo3.sample" }
		val rootDir = repositoryRoot()
		println("=== generating from ${sampleFiles.size} sample(s) into $rootDir ===")

		// Walk every sample into one accumulator: instances/enum-values/fields union across versions.
		val tags = HashMap<String, TagInfo>()
		for (sampleFile in sampleFiles) {
			val mainXml =
				CaffCodec.read(sampleFile.readBytes())
					.firstByTag(CaffArchive.TAG_MAIN_XML)?.content ?: error("no main_xml in ${sampleFile.name}")
			walk(SAXBuilder().build(ByteArrayInputStream(mainXml)).rootElement, tags)
		}

		val reflective = sortedMapOf<String, TagInfo>()
		val enums = sortedMapOf<String, TagInfo>()
		val custom = sortedSetOf<String>()
		for ((tag, info) in tags) {
			if (tag in handHandled || tag in primitiveTags || tag in collectionTags) continue
			when {
				info.customAttrs == sortedSetOf("v") && !info.hasChildren -> enums[tag] = info
				info.customAttrs.isNotEmpty() -> custom += "$tag ${info.customAttrs}"
				else -> reflective[tag] = info
			}
		}

		// Exclude classes that would redeclare an inherited field (e.g. the IOption mixin pattern):
		// Kotlin forbids hiding a supertype member, and a safe override is risky to infer. Skip such
		// classes (and anything extending them) - they stay verbatim and round-trip byte-identical.
		fun ownFields(tag: String): Set<String> =
			reflective[tag]?.let { (it.bestOrder + it.fieldTypeTags.keys).toSet() } ?: emptySet()

		fun ancestorFields(tag: String): Set<String> {
			val out = HashSet<String>()
			var superTag = reflective[tag]?.superTag
			while (superTag != null) {
				out += ownFields(superTag)
				superTag = reflective[superTag]?.superTag
			}
			return out
		}

		val skip = sortedSetOf<String>()
		for (tag in reflective.keys) if ((ownFields(tag) intersect ancestorFields(tag)).isNotEmpty()) skip += tag
		var changed = true
		while (changed) { // cascade: drop anything extending a skipped class
			changed = false
			for ((tag, info) in reflective) {
				val superTag = info.superTag
				if (tag !in skip && superTag != null && superTag in skip) {
					skip += tag
					changed = true
				}
			}
		}
		val emit = reflective.filterKeys { it !in skip }.toSortedMap()

		writeModel(rootDir, emit, enums)
		writeRegistration(rootDir, emit, enums)

		println("=== GENERATED ${emit.size} reflective classes, ${enums.size} enums ===")
		println("=== SKIPPED (inherited-field collision; stay verbatim) ${skip.size}: $skip ===")
		println("=== CUSTOM (hand-write these ${custom.size}): ===")
		custom.forEach { println("  $it") }
	}

	private fun walk(element: Element, tags: HashMap<String, TagInfo>) {
		// A reference (xs.ref) is not a definition: it has no fields and must not be counted as an
		// instance (doing so made every field look "sometimes-present").
		if (element.getAttributeValue("xs.ref") != null) return
		val tag = element.name
		if (tag !in primitiveTags && tag !in collectionTags && tag != "entry") {
			val info = tags.getOrPut(tag) { TagInfo() }
			info.instances++
			@Suppress("UNCHECKED_CAST")
			val attrs = element.attributes as List<org.jdom.Attribute>
			for (attribute in attrs) {
				if (attribute.name !in neutralAttrs) {
					info.customAttrs += attribute.name
					if (attribute.name == "v") info.enumValues += attribute.value
				}
			}
			@Suppress("UNCHECKED_CAST")
			val children = element.children as List<Element>
			info.hasChildren = info.hasChildren || children.any { it.getAttributeValue("xs.n") != null }
			val order = ArrayList<String>()
			for (child in children) {
				val fieldName = child.getAttributeValue("xs.n") ?: continue
				if (fieldName == "super") {
					info.superTag = child.name
					tags.getOrPut(child.name) { TagInfo() }.isSuperOf += tag
					continue
				}
				if (child.name != "null") {
					info.fieldTypeTags.getOrPut(fieldName) { sortedSetOf() } += child.name
				} else {
					info.fieldTypeTags.getOrPut(fieldName) { sortedSetOf() }
					info.nullableFields += fieldName // a <null> value means the field must be nullable
				}
				order += fieldName
				info.fieldPresence[fieldName] = (info.fieldPresence[fieldName] ?: 0) + 1
			}
			if (order.size > info.bestOrderSize) {
				info.bestOrder = order
				info.bestOrderSize = order.size
			}
		}
		@Suppress("UNCHECKED_CAST")
		for (child in element.children as List<Element>) walk(child, tags)
	}

	private fun ktTypeAndDefault(typeTags: Set<String>): Pair<String, String> {
		if (typeTags.size == 1) primitiveTags[typeTags.first()]?.let { return it }
		return "Any?" to "null"
	}

	/**
	 * The repository root, located by walking up for settings.gradle.kts.
	 *
	 * Not derived from the sample path, which is what it used to be: `sample.parentFile.parentFile`
	 * assumed the corpus sat exactly two levels below the root, so with samples at test/corpus/cmo3/ it
	 * resolved to test/corpus and wrote the "generated" model into the gitignored corpus, where nothing
	 * would ever compile it.
	 *
	 * @return File The repository root.
	 */
	private fun repositoryRoot(): File {
		var candidate: File? = File(".").absoluteFile
		while (candidate != null) {
			if (File(candidate, "settings.gradle.kts").isFile) {
				return candidate
			}
			candidate = candidate.parentFile
		}
		error("no settings.gradle.kts above ${File(".").absolutePath}; cannot locate the repository root")
	}

	/**
	 * The property lines for a newly inferred field, annotations included.
	 *
	 * @param String tag             The owning tag, for the @SerialName fallback.
	 * @param String field           The on-disk `xs.n` field name.
	 * @param TagInfo info           The inferred facts about the owning tag.
	 * @param String propertyModifier "open " for a class that is extended, else empty.
	 * @return List<String> The lines to emit.
	 */
	private fun newFieldLines(tag: String, field: String, info: TagInfo, propertyModifier: String): List<String> {
		val lines = mutableListOf<String>()
		val (baseType, baseDefault) = ktTypeAndDefault(info.fieldTypeTags[field] ?: emptySet())
		// Nullable when observed as <null>, or always for String (commonly null in the data).
		val nullable = baseType != "Any?" && (field in info.nullableFields || baseType == "String")
		val type = if (nullable) "$baseType?" else baseType
		val default = if (nullable) "null" else baseDefault
		if ((info.fieldPresence[field] ?: 0) < info.instances) {
			lines += "\t@DontSerializeIfDefault"
		}
		// Field names that are not legal Kotlin identifiers (e.g. "parameters.keys") get a
		// sanitized property name + @SerialName carrying the real on-disk xs.n.
		val legal = field.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
		val kotlinName = if (legal) field else field.replace(Regex("[^A-Za-z0-9_]"), "_")
		if (!legal) {
			lines += "\t@SerialName(\"$field\")"
		}
		lines += "\tpublic ${propertyModifier}var `$kotlinName`: $type = $default"
		return lines
	}

	/**
	 * Emits the model as the union of what was already generated and what the samples prove.
	 *
	 * Every existing declaration is carried through verbatim, including ones no sample exercises: the
	 * corpus is evidence of what the format DOES contain, never of what it does not.  New tags, new
	 * enum constants, and new fields are added; nothing is removed or rewritten.
	 *
	 * @param File rootDir                     The repository root.
	 * @param Map<String, TagInfo> reflective  Inferred reflective classes.
	 * @param Map<String, TagInfo> enums       Inferred enums.
	 */
	private fun writeModel(rootDir: File, reflective: Map<String, TagInfo>, enums: Map<String, TagInfo>) {
		val outFile =
			File(rootDir, "module/format/src/commonMain/kotlin/org/umamo/format/cmo3/model/gen/GeneratedModel.kt")
		val existing = parseGeneratedModel(outFile)
		val added = mutableListOf<String>()

		val builder = StringBuilder()
		builder.appendLine("// GENERATED by ModelGenerator from the corpus main.xml, merged over the previous output.")
		builder.appendLine("// Do not edit by hand; re-run with -Dcmo3.generate=true. Generation only ever ADDS.")
		builder.appendLine("package org.umamo.format.cmo3.model.gen")
		builder.appendLine()
		builder.appendLine("import org.umamo.format.cmo3.serialize.annotations.DontSerializeIfDefault")
		builder.appendLine("import org.umamo.format.cmo3.serialize.annotations.SerialName")
		builder.appendLine("import org.umamo.format.cmo3.serialize.annotations.SerialTag")
		builder.appendLine()

		for (tag in (existing.enums.keys + enums.keys).sorted()) {
			val previous = existing.enums[tag] ?: emptySet<String>()
			val inferred = enums[tag]?.enumValues ?: emptySet<String>()
			(inferred - previous).forEach { added += "enum $tag.$it" }
			if (tag !in existing.enums) {
				added += "enum $tag (new)"
			}
			val values = (previous + inferred).sorted()
			builder.appendLine("@SerialTag(\"$tag\")")
			builder.appendLine("public enum class $tag { ${values.joinToString(", ")} }")
			builder.appendLine()
		}

		for (tag in (existing.classes.keys + reflective.keys).sorted()) {
			val previousClass = existing.classes[tag]
			val info = reflective[tag]
			if (previousClass != null) {
				// Keep the declaration exactly: its `open` modifier and super clause encode decisions
				// (including hand-written supers) that a fresh inference over a different corpus may not
				// reproduce.
				builder.appendLine("@SerialTag(\"$tag\")")
				builder.appendLine(previousClass.declaration)
				if (previousClass.isBodyless) {
					builder.appendLine()
					continue
				}
				val propertyModifier = if (previousClass.declaration.contains("open class")) "open " else ""
				val byName = previousClass.fields.associateBy { it.serialName }
				val inferredOrder =
					if (info != null) {
						LinkedHashSet(info.bestOrder).also { it += info.fieldTypeTags.keys }.toList()
					} else {
						emptyList()
					}
				for (field in mergeFieldOrder(previousClass.fields.map { it.serialName }, inferredOrder)) {
					val known = byName[field]
					if (known != null) {
						known.lines.forEach { builder.appendLine(it) }
					} else {
						added += "$tag.$field"
						newFieldLines(tag, field, info!!, propertyModifier).forEach { builder.appendLine(it) }
					}
				}
				builder.appendLine("}")
				builder.appendLine()
				continue
			}

			// A tag the previous output never had.
			added += "class $tag (new)"
			val openMod = if (info!!.isSuperOf.isNotEmpty()) "open " else ""
			// Only extend a superclass that is itself generated; an external/hand-written super is left
			// off (its <super> element is preserved verbatim as an unmatched child at runtime).
			val superClause =
				info.superTag?.takeIf { reflective.containsKey(it) || existing.classes.containsKey(it) }?.let { " : $it()" } ?: ""
			builder.appendLine("@SerialTag(\"$tag\")")
			builder.appendLine("public ${openMod}class $tag$superClause {")
			val fields = LinkedHashSet(info.bestOrder)
			info.fieldTypeTags.keys.forEach { fields += it } // union, in case a field only appears outside the richest instance
			// Open classes get open properties so hand-written subclasses can override (e.g. CLayer
			// overriding the inherited _optionOfIOption); final classes keep plain properties.
			for (field in fields) {
				newFieldLines(tag, field, info, if (openMod.isNotEmpty()) "open " else "").forEach { builder.appendLine(it) }
			}
			builder.appendLine("}")
			builder.appendLine()
		}

		outFile.parentFile.mkdirs()
		outFile.writeText(builder.toString())
		println("=== ADDED ${added.size} declaration(s) ===")
		added.sorted().forEach { println("  + $it") }
	}

	/**
	 * Emits the registration function over the union of every generated declaration.
	 *
	 * Reads the model file back rather than registering only what this run inferred: the model is a
	 * union, so registering just the inferred subset would leave every carried-over class unregistered
	 * and silently drop it to verbatim -- the same deletion the merge exists to prevent, one file over.
	 *
	 * @param File rootDir                     The repository root.
	 * @param Map<String, TagInfo> reflective  Inferred reflective classes (already merged into the model).
	 * @param Map<String, TagInfo> enums       Inferred enums (already merged into the model).
	 */
	private fun writeRegistration(rootDir: File, reflective: Map<String, TagInfo>, enums: Map<String, TagInfo>) {
		val model =
			parseGeneratedModel(
				File(rootDir, "module/format/src/commonMain/kotlin/org/umamo/format/cmo3/model/gen/GeneratedModel.kt"),
			)
		val builder = StringBuilder()
		builder.appendLine("// GENERATED by ModelGenerator. Do not edit by hand.")
		builder.appendLine("package org.umamo.format.cmo3.serialize.gen")
		builder.appendLine()
		builder.appendLine("import org.umamo.format.cmo3.model.gen.*")
		builder.appendLine("import org.umamo.format.cmo3.serialize.SerializerRegistry")
		builder.appendLine()
		builder.appendLine("/** Registers every generated reflective class + enum. */")
		builder.appendLine("internal fun registerGeneratedSubsystem(registry: SerializerRegistry) {")
		for (tag in (model.classes.keys + model.enums.keys + reflective.keys + enums.keys).sorted()) {
			builder.appendLine("\tregistry.register($tag::class)")
		}
		builder.appendLine("}")
		val outFile =
			File(
				rootDir,
				"module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/serialize/gen/GeneratedRegistration.kt",
			)
		outFile.parentFile.mkdirs()
		outFile.writeText(builder.toString())
	}
}
