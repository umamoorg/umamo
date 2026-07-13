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
 * One-shot, sample-driven code generator for the reflective Cubism model classes. Reads the sample's
 * main.xml, infers each tag's serialized field signature (name/type/order, <super> inheritance,
 * @DontSerializeIfDefault for sometimes-present fields), detects enums, and reports custom
 * (attribute-bearing) tags for hand-writing. Emits model classes + a registration function.
 *
 * Run with: -Dcmo3.generate=true (writes into src/commonMain + src/jvmAndroidMain). The byte-identity gate
 * validates the output.
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
		val rootDir = sampleFiles.first().parentFile.parentFile

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

	private fun writeModel(rootDir: File, reflective: Map<String, TagInfo>, enums: Map<String, TagInfo>) {
		val builder = StringBuilder()
		builder.appendLine("// GENERATED by ModelGenerator from the sample main.xml. Do not edit by hand.")
		builder.appendLine("package org.umamo.format.cmo3.model.gen")
		builder.appendLine()
		builder.appendLine("import org.umamo.format.cmo3.serialize.annotations.DontSerializeIfDefault")
		builder.appendLine("import org.umamo.format.cmo3.serialize.annotations.SerialName")
		builder.appendLine("import org.umamo.format.cmo3.serialize.annotations.SerialTag")
		builder.appendLine()
		for ((tag, info) in enums) {
			builder.appendLine("@SerialTag(\"$tag\")")
			builder.appendLine("public enum class $tag { ${info.enumValues.joinToString(", ")} }")
			builder.appendLine()
		}
		for ((tag, info) in reflective) {
			val openMod = if (info.isSuperOf.isNotEmpty()) "open " else ""
			// Only extend a superclass that is itself generated; an external/hand-written super is left
			// off (its <super> element is preserved verbatim as an unmatched child at runtime).
			val superClause = info.superTag?.takeIf { reflective.containsKey(it) }?.let { " : $it()" } ?: ""
			builder.appendLine("@SerialTag(\"$tag\")")
			builder.appendLine("public ${openMod}class $tag$superClause {")
			val fields = LinkedHashSet(info.bestOrder)
			info.fieldTypeTags.keys.forEach { fields += it } // union, in case a field only appears outside the richest instance
			// Open classes get open properties so hand-written subclasses can override (e.g. CLayer
			// overriding the inherited _optionOfIOption); final classes keep plain properties.
			val propertyModifier = if (openMod.isNotEmpty()) "open " else ""
			for (field in fields) {
				val (baseType, baseDefault) = ktTypeAndDefault(info.fieldTypeTags[field] ?: emptySet())
				// Nullable when observed as <null>, or always for String (commonly null in the data).
				val nullable = baseType != "Any?" && (field in info.nullableFields || baseType == "String")
				val type = if (nullable) "$baseType?" else baseType
				val default = if (nullable) "null" else baseDefault
				val sometimes = (info.fieldPresence[field] ?: 0) < info.instances
				val dsd = if (sometimes) "\t@DontSerializeIfDefault\n" else ""
				// Field names that are not legal Kotlin identifiers (e.g. "parameters.keys") get a
				// sanitized property name + @SerialName carrying the real on-disk xs.n.
				val legal = field.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
				val kotlinName = if (legal) field else field.replace(Regex("[^A-Za-z0-9_]"), "_")
				val nameAnno = if (legal) "" else "\t@SerialName(\"$field\")\n"
				builder.appendLine("$dsd$nameAnno\tpublic ${propertyModifier}var `$kotlinName`: $type = $default")
			}
			builder.appendLine("}")
			builder.appendLine()
		}
		val outFile =
			File(rootDir, "module/format/src/commonMain/kotlin/org/umamo/format/cmo3/model/gen/GeneratedModel.kt")
		outFile.parentFile.mkdirs()
		outFile.writeText(builder.toString())
	}

	private fun writeRegistration(rootDir: File, reflective: Map<String, TagInfo>, enums: Map<String, TagInfo>) {
		val builder = StringBuilder()
		builder.appendLine("// GENERATED by ModelGenerator. Do not edit by hand.")
		builder.appendLine("package org.umamo.format.cmo3.serialize.gen")
		builder.appendLine()
		builder.appendLine("import org.umamo.format.cmo3.model.gen.*")
		builder.appendLine("import org.umamo.format.cmo3.serialize.SerializerRegistry")
		builder.appendLine()
		builder.appendLine("/** Registers every generated reflective class + enum. */")
		builder.appendLine("internal fun registerGeneratedSubsystem(registry: SerializerRegistry) {")
		for (tag in (reflective.keys + enums.keys).sorted()) {
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
