package org.umamo.format.cmo3.tools

import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLabelColor
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CRotationDeformerForm
import org.umamo.format.cmo3.model.custom.CSize
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.custom.FilterInstance
import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.custom.RotationDeformerOriginalShape
import org.umamo.format.cmo3.model.custom.WarpDeformerOriginalShape
import org.umamo.format.cmo3.model.drawable.CoordType
import org.umamo.format.cmo3.model.drawable.MeshPointRef
import org.umamo.format.cmo3.model.drawable.PointInTriangle
import org.umamo.format.cmo3.model.drawable.PointOnCurve
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CColor
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.serialize.annotations.SerialAttribute
import org.umamo.format.cmo3.serialize.annotations.SerialName
import org.umamo.format.cmo3.serialize.annotations.SuppressSerializeSuperClass
import org.umamo.format.cmo3.serialize.cubismEngine
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test

/**
 * Emits the checked-in descriptor sources (GeneratedDescriptors.kt, GeneratedRegistration.kt) from
 * the model classes, deriving every descriptor with the same kotlin-reflect calls the runtime
 * serializer historically made (via [DescriptorReflection]) and rendering them as commonMain DSL
 * calls.  The emitted text is ktlint-clean by construction, so generation followed by ktlintFormat
 * changes nothing and the drift gate can compare bytes.
 *
 * Inputs, chosen to break the regeneration cycle: the generated-model class/enum list is parsed
 * from GeneratedModel.kt (never harvested from the registry, which post-flip is itself built from
 * this emitter's output), and the hand-registered reflective classes are the explicit lists below,
 * cross-checked against the live cubismEngine registry so drift in either direction fails
 * generation loudly.
 */
internal object DescriptorSource {
	const val DESCRIPTORS_PATH: String =
		"module/format/src/commonMain/kotlin/org/umamo/format/cmo3/serialize/gen/GeneratedDescriptors.kt"
	const val REGISTRATION_PATH: String =
		"module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/serialize/gen/GeneratedRegistration.kt"
	const val GENERATED_MODEL_PATH: String =
		"module/format/src/commonMain/kotlin/org/umamo/format/cmo3/model/gen/GeneratedModel.kt"
	const val REGENERATION_COMMAND: String =
		"./gradlew :format:jvmTest --tests \"org.umamo.format.cmo3.tools.DescriptorGenerator\" " +
			"-Dcmo3.generateDescriptors=true --rerun"

	private const val GEN_MODEL_PACKAGE = "org.umamo.format.cmo3.model.gen"
	private const val MODEL_PACKAGE_PREFIX = "org.umamo.format.cmo3.model."
	private const val DESCRIPTORS_PACKAGE = "org.umamo.format.cmo3.serialize.descriptors"

	/**
	 * The hand-registered reflective classes, mirroring the subsystem registration lists
	 * (registerValueTypeSubsystem, registerMeshSubsystem, registerCustomSubsystem).  Kept explicit
	 * rather than harvested so regeneration does not echo its own previous output; the cross-check
	 * against the live registry catches a subsystem edit this list misses.
	 */
	val handReflectiveClasses: List<KClass<*>> =
		listOf(
			GVector2::class,
			CRect::class,
			CColor::class,
			CoordType::class,
			PointInTriangle::class,
			PointOnCurve::class,
			MeshPointRef::class,
			CFloatColor::class,
			CLabelColor::class,
			WarpDeformerOriginalShape::class,
			RotationDeformerOriginalShape::class,
			CSize::class,
			CRotationDeformerForm::class,
			CWritableImage::class,
			CImageResource::class,
			FilterInstance::class,
			CModelImage::class,
			GEditableMesh2::class,
			CLayer::class,
			CModelSource::class,
		)

	/**
	 * The classes served by hand-written custom serializers: they get no descriptors, so the graph
	 * walkers never descend into them — every property they declare must therefore be leaf-typed,
	 * which [validate] enforces.
	 */
	val customSerializedClasses: List<KClass<*>> = listOf(Guid::class, Id::class, FileRef::class, CAffine::class)

	private val leafClassifiers: Set<KClass<*>> =
		setOf(
			String::class,
			Int::class,
			Float::class,
			Double::class,
			Long::class,
			Short::class,
			Byte::class,
			Boolean::class,
			Char::class,
		)

	/** The descriptor generation input: reflective classes and enums, both sorted by simple name. */
	class Input(val classes: List<KClass<*>>, val enums: List<KClass<*>>)

	/**
	 * The repository root, located by walking up for settings.gradle.kts (the same rule
	 * ModelGenerator uses, so both generators agree on where the checked-in sources live).
	 *
	 * @return File The repository root.
	 */
	fun repositoryRoot(): File {
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
	 * Builds the generation input: the generated-model classes and enums parsed from
	 * GeneratedModel.kt plus [handReflectiveClasses], cross-checked against the live registry.
	 *
	 * @param File rootDir The repository root.
	 * @return Input The reflective classes and enums to emit descriptors for.
	 */
	fun input(rootDir: File): Input {
		val model = parseGeneratedModel(File(rootDir, GENERATED_MODEL_PATH))
		check(model.classes.isNotEmpty()) { "GeneratedModel.kt missing or empty at $rootDir/$GENERATED_MODEL_PATH" }
		val genClasses = model.classes.keys.sorted().map { tag -> genModelClass(tag) }
		val genEnums = model.enums.keys.sorted().map { tag -> genModelClass(tag) }
		val input =
			Input(
				classes = (genClasses + handReflectiveClasses).sortedBy { it.simpleName },
				enums = genEnums.sortedBy { it.simpleName },
			)
		crossCheckAgainstRegistry(input)
		return input
	}

	/**
	 * Resolves a generated-model tag to its KClass (class name == tag in the generated model).
	 *
	 * @param String tag The @SerialTag value.
	 * @return KClass The model class.
	 */
	private fun genModelClass(tag: String): KClass<*> = Class.forName("$GEN_MODEL_PACKAGE.$tag").kotlin

	/**
	 * Fails generation when the input set disagrees with what the production engine actually
	 * registers — a registration edit that bypassed this generator, or vice versa.
	 *
	 * @param Input input The generation input to check.
	 */
	private fun crossCheckAgainstRegistry(input: Input) {
		val registry = cubismEngine().toolingRegistry
		val expected = (input.classes + input.enums).toSet()
		val actual = registry.registeredClasses() - registry.customSerializedClasses()
		check(actual == expected) {
			"descriptor input drift: registry-only=${(actual - expected).map { it.simpleName }}, " +
				"input-only=${(expected - actual).map { it.simpleName }}"
		}
		check(registry.customSerializedClasses() == customSerializedClasses.toSet()) {
			"custom-serialized drift: registry has ${registry.customSerializedClasses().map { it.simpleName }}, " +
				"generator expects ${customSerializedClasses.map { it.simpleName }}"
		}
	}

	/**
	 * The serializable superclass of [kClass] under the engine's rules: any non-Any, non-interface
	 * superclass, unless @SuppressSerializeSuperClass.
	 *
	 * @param KClass kClass The model class.
	 * @return KClass? The serializable superclass, or null.
	 */
	private fun serializableSuperOf(kClass: KClass<*>): KClass<*>? {
		if (kClass.findAnnotation<SuppressSerializeSuperClass>() != null) {
			return null
		}
		val superJava = kClass.java.superclass ?: return null
		if (superJava == Any::class.java || superJava.isInterface) {
			return null
		}
		return superJava.kotlin
	}

	/**
	 * Hard-fails on any shape the descriptor scheme cannot represent faithfully: an excluded (val
	 * or @DontSerialize) property with a non-leaf type would be invisible to the descriptor-driven
	 * graph walkers, an enum-typed @SerialAttribute would read back as a raw String, and every
	 * property of a custom-serialized class must be leaf-typed for the same walker reason.
	 *
	 * @param Input input The generation input to check.
	 */
	private fun validate(input: Input) {
		for (kClass in customSerializedClasses) {
			for (property in kClass.declaredMemberProperties) {
				check(isLeafType(property.returnType)) {
					"custom-serialized ${kClass.simpleName}.${property.name} is not leaf-typed; the graph " +
						"walkers skip descriptor-less classes, so a reference-typed field here would be invisible"
				}
			}
		}
		for (kClass in input.classes) {
			val serializedNames = DescriptorReflection.serializedProperties(kClass).map { it.name }.toSet()
			for (property in kClass.declaredMemberProperties) {
				if (property.name in serializedNames) {
					val attributeAnnotation = property.findAnnotation<SerialAttribute>()
					val classifier = property.returnType.classifier as? KClass<*>
					check(attributeAnnotation == null || classifier?.java?.isEnum != true) {
						"${kClass.simpleName}.${property.name} is an enum-typed @SerialAttribute; parsing " +
							"would assign the raw String — model it as a child element or hand-write a serializer"
					}
				} else {
					check(isLeafType(property.returnType)) {
						"excluded property ${kClass.simpleName}.${property.name} is not leaf-typed; the " +
							"descriptor-driven graph walkers only see serialized properties, so a reference " +
							"held here would be invisible to reachability"
					}
				}
			}
		}
	}

	/**
	 * True for the walker leaf types (scalars, strings, enums) — values the graph walks never
	 * descend into.
	 *
	 * @param KType type The property type.
	 * @return Boolean Whether the type is a leaf.
	 */
	private fun isLeafType(type: KType): Boolean {
		val classifier = type.classifier as? KClass<*> ?: return false
		return classifier in leafClassifiers || classifier.java.isEnum
	}

	/**
	 * The generated descriptor val name for [kClass]: its simple name decapitalized.
	 *
	 * @param KClass kClass The model class or enum.
	 * @return String The val name.
	 */
	private fun valNameOf(kClass: KClass<*>): String {
		val simpleName = kClass.simpleName ?: error("anonymous class in descriptor input: $kClass")
		return simpleName.replaceFirstChar { it.lowercaseChar() }
	}

	/**
	 * Renders the Kotlin source text of a property type for a set-lambda cast, recording any
	 * non-gen model class into [imports].  Only parameterless kotlin.* and model types occur.
	 *
	 * @param KType      type    The declared property type.
	 * @param MutableSet imports Collector for explicitly imported classes.
	 * @return String The type text, e.g. "Boolean", "String?", "Any?", "Guid?".
	 */
	private fun renderTypeText(type: KType, imports: MutableSet<KClass<*>>): String {
		check(type.arguments.isEmpty()) { "generic property types are not representable in a cast: $type" }
		val classifier = type.classifier as? KClass<*> ?: error("non-class property type: $type")
		val qualified = classifier.qualifiedName ?: error("local/anonymous property type: $type")
		val simple = classifier.simpleName ?: error("anonymous property type: $type")
		check(qualified == "kotlin.$simple" || qualified.startsWith(MODEL_PACKAGE_PREFIX)) {
			"unexpected property type $qualified; extend the generator before using it in a model class"
		}
		if (qualified.startsWith(MODEL_PACKAGE_PREFIX) && !qualified.startsWith("$GEN_MODEL_PACKAGE.")) {
			imports += classifier
		}
		return simple + (if (type.isMarkedNullable) "?" else "")
	}

	/**
	 * Renders one property() / attribute() DSL line for [property].
	 *
	 * @param KMutableProperty1 property The serialized property.
	 * @param KClass            owner    The declaring class.
	 * @param MutableSet        imports  Collector for explicitly imported classes.
	 * @return String The DSL call line, tab-indented for the builder body.
	 */
	private fun renderPropertyLine(
		property: KMutableProperty1<Any, Any?>,
		owner: KClass<*>,
		imports: MutableSet<KClass<*>>,
	): String {
		val descriptor = DescriptorReflection.propertyDescriptorFor(owner, property)
		val accessor = "`${property.name}`"
		val typeText = renderTypeText(property.returnType, imports)
		val castSuffix = if (typeText == "Any?") "" else " as $typeText"
		val getLambda = "{ it.$accessor }"
		val setLambda = "{ obj, value -> obj.$accessor = value$castSuffix }"
		val attributeAnnotation = property.findAnnotation<SerialAttribute>()
		val arguments = mutableListOf("\"${property.name}\"")
		if (attributeAnnotation != null) {
			arguments += "ScalarKind.${DescriptorReflection.scalarKindOf(property).name}"
			arguments += getLambda
			arguments += setLambda
			if (descriptor.attributeName != property.name) {
				arguments += "attributeName = \"${descriptor.attributeName}\""
			}
		} else {
			arguments += getLambda
			arguments += setLambda
			val serialNameOverride = property.findAnnotation<SerialName>()?.name
			if (serialNameOverride != null) {
				arguments += "serialName = \"$serialNameOverride\""
			}
		}
		if (descriptor.skipIfDefault) {
			arguments += "skipIfDefault = true"
		}
		val callName = if (attributeAnnotation != null) "attribute" else "property"
		return "\t\t\t$callName(${arguments.joinToString(", ")})"
	}

	/**
	 * Emits GeneratedDescriptors.kt: one val + one private builder function per class (small
	 * methods keep every initializer far from the JVM's method-size limit), vals in topological
	 * order so a superDescriptor is always an already-initialized sibling, enums inline, and the
	 * allClassDescriptors / allEnumDescriptors lists the gates iterate.
	 *
	 * @param Input input The generation input.
	 * @return String The complete file text (no trailing newline, per .editorconfig).
	 */
	fun descriptorsSource(input: Input): String {
		validate(input)
		val classSet = input.classes.toSet()
		for (kClass in input.classes) {
			val superClass = serializableSuperOf(kClass)
			check(superClass == null || superClass in classSet) {
				"${kClass.simpleName} extends unregistered ${superClass?.simpleName}; register the superclass " +
					"(the engine serializes every non-Any superclass) before generating descriptors"
			}
		}

		// Topological order, alphabetical tiebreak: supers first so each val reads initialized state.
		val ordered = LinkedHashSet<KClass<*>>()

		fun visit(kClass: KClass<*>) {
			if (kClass in ordered) {
				return
			}
			serializableSuperOf(kClass)?.let { superClass -> visit(superClass) }
			ordered += kClass
		}
		input.classes.forEach { kClass -> visit(kClass) }

		val imports = sortedSetOf<KClass<*>>(compareBy { it.qualifiedName })
		val valLines = mutableListOf<String>()
		val functionBlocks = mutableListOf<String>()
		var usesScalarKind = false
		for (kClass in ordered) {
			val valName = valNameOf(kClass)
			val simpleName = kClass.simpleName ?: error("anonymous class: $kClass")
			val qualified = kClass.qualifiedName ?: error("local class: $kClass")
			if (!qualified.startsWith("$GEN_MODEL_PACKAGE.")) {
				imports += kClass
			}
			val superClass = serializableSuperOf(kClass)
			val superArgument = superClass?.let { valNameOf(it) } ?: "null"
			valLines += "\tval $valName: ClassDescriptor = ${valName}Descriptor($superArgument)"

			val tag = DescriptorReflection.tagOf(kClass)
			val version = DescriptorReflection.versionOf(kClass)
			val versionArgument = if (version >= 0) ", version = $version" else ""
			val properties = DescriptorReflection.serializedProperties(kClass)
			val callHead = "classDescriptor(\"$tag\", ::$simpleName, superDescriptor$versionArgument)"
			val block = StringBuilder()
			block.appendLine("\t/** Builds the $tag descriptor. */")
			block.appendLine("\tprivate fun ${valName}Descriptor(superDescriptor: ClassDescriptor?): ClassDescriptor =")
			if (properties.isEmpty()) {
				block.append("\t\t$callHead")
			} else {
				block.appendLine("\t\t$callHead {")
				for (property in properties) {
					val line = renderPropertyLine(property, kClass, imports)
					if (line.contains("ScalarKind.")) {
						usesScalarKind = true
					}
					block.appendLine(line)
				}
				block.append("\t\t}")
			}
			functionBlocks += block.toString()
		}

		val enumValLines =
			input.enums.map { kClass ->
				val tag = DescriptorReflection.tagOf(kClass)
				val version = DescriptorReflection.versionOf(kClass)
				val versionArgument = if (version >= 0) ", version = $version" else ""
				"\tval ${valNameOf(kClass)}: EnumDescriptor = enumDescriptor<${kClass.simpleName}>(\"$tag\"$versionArgument)"
			}

		val importLines = sortedSetOf<String>()
		importLines += "import $GEN_MODEL_PACKAGE.*"
		importLines += "import $DESCRIPTORS_PACKAGE.ClassDescriptor"
		importLines += "import $DESCRIPTORS_PACKAGE.EnumDescriptor"
		importLines += "import $DESCRIPTORS_PACKAGE.classDescriptor"
		importLines += "import $DESCRIPTORS_PACKAGE.enumDescriptor"
		if (usesScalarKind) {
			importLines += "import $DESCRIPTORS_PACKAGE.ScalarKind"
		}
		imports.forEach { importLines += "import ${it.qualifiedName}" }
		val importedSimpleNames = imports.map { it.simpleName }
		check(importedSimpleNames.size == importedSimpleNames.toSet().size) {
			"simple-name collision among imported model classes: $importedSimpleNames"
		}

		val builder = StringBuilder()
		builder.appendLine("// GENERATED by DescriptorGenerator. Do not edit by hand; re-run with")
		builder.appendLine("// -Dcmo3.generateDescriptors=true (after any ModelGenerator run), then ktlintFormat.")
		builder.appendLine("package org.umamo.format.cmo3.serialize.gen")
		builder.appendLine()
		importLines.forEach { builder.appendLine(it) }
		builder.appendLine()
		builder.appendLine("/**")
		builder.appendLine(" * Serialization descriptors for every reflectively-modelled CMO3 class and enum: the generated")
		builder.appendLine(" * model (model/gen), the value types, the mesh types, and the hand-written custom classes.  Each")
		builder.appendLine(" * descriptor freezes what the engine knows about a class — tag, factory, serialized properties in")
		builder.appendLine(" * backing-field declaration order, and the super chain — so serialization needs no runtime")
		builder.appendLine(" * reflection.  The custom-serialized classes (Guid, Id, FileRef, CAffine) keep hand-written")
		builder.appendLine(" * serializers and have no descriptors.")
		builder.appendLine(" */")
		builder.appendLine("internal object GeneratedDescriptors {")
		valLines.forEach { builder.appendLine(it) }
		enumValLines.forEach { builder.appendLine(it) }
		builder.appendLine()
		builder.appendLine("\t/** Every class descriptor, supers before subclasses. */")
		builder.appendLine("\tval allClassDescriptors: List<ClassDescriptor> =")
		builder.appendLine("\t\tlistOf(")
		ordered.forEach { builder.appendLine("\t\t\t${valNameOf(it)},") }
		builder.appendLine("\t\t)")
		builder.appendLine()
		builder.appendLine("\t/** Every enum descriptor, alphabetical. */")
		builder.appendLine("\tval allEnumDescriptors: List<EnumDescriptor> =")
		builder.appendLine("\t\tlistOf(")
		input.enums.forEach { builder.appendLine("\t\t\t${valNameOf(it)},") }
		builder.appendLine("\t\t)")
		functionBlocks.forEach { block ->
			builder.appendLine()
			builder.appendLine(block)
		}
		builder.append("}")
		return builder.toString()
	}

	/**
	 * Emits GeneratedRegistration.kt: one register() call per generated-model descriptor (classes
	 * and enums interleaved alphabetically, matching the historical file).  Hand-registered classes
	 * stay registered by their own subsystem files.
	 *
	 * @param Input input The generation input.
	 * @return String The complete file text (no trailing newline, per .editorconfig).
	 */
	fun registrationSource(input: Input): String {
		val genEntities =
			(input.classes + input.enums)
				.filter { (it.qualifiedName ?: "").startsWith("$GEN_MODEL_PACKAGE.") }
				.sortedBy { it.simpleName }
		val builder = StringBuilder()
		builder.appendLine("// GENERATED by DescriptorGenerator. Do not edit by hand.")
		builder.appendLine("package org.umamo.format.cmo3.serialize.gen")
		builder.appendLine()
		builder.appendLine("import org.umamo.format.cmo3.serialize.SerializerRegistry")
		builder.appendLine()
		builder.appendLine("/** Registers every generated model class + enum descriptor. */")
		builder.appendLine("internal fun registerGeneratedSubsystem(registry: SerializerRegistry) {")
		genEntities.forEach { builder.appendLine("\tregistry.register(GeneratedDescriptors.${valNameOf(it)})") }
		builder.appendLine("}")
		return builder.toString().trimEnd()
	}
}

/**
 * The descriptor-source generator: a corpus-free tool run explicitly with
 * -Dcmo3.generateDescriptors=true (forwarded by the umamoTestCorpus flag wiring); a plain test run
 * skips it.  Regenerate after any ModelGenerator run or model-class edit, then run ktlintFormat
 * and the drift gate.
 */
class DescriptorGenerator {
	@Test
	fun generate() {
		if (System.getProperty("cmo3.generateDescriptors") != "true") {
			return
		}
		val rootDir = DescriptorSource.repositoryRoot()
		val input = DescriptorSource.input(rootDir)
		val descriptorsFile = File(rootDir, DescriptorSource.DESCRIPTORS_PATH)
		descriptorsFile.parentFile.mkdirs()
		descriptorsFile.writeText(DescriptorSource.descriptorsSource(input))
		val registrationFile = File(rootDir, DescriptorSource.REGISTRATION_PATH)
		registrationFile.parentFile.mkdirs()
		registrationFile.writeText(DescriptorSource.registrationSource(input))
		println("=== GENERATED ${input.classes.size} class descriptors, ${input.enums.size} enum descriptors ===")
		println("=== into $descriptorsFile ===")
		println("=== and $registrationFile ===")
	}
}