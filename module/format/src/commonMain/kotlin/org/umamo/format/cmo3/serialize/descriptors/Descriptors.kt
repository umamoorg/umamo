package org.umamo.format.cmo3.serialize.descriptors

import kotlin.reflect.KClass

/**
 * How a serialized-as-attribute property's raw text parses into its property type.
 *
 * The kinds and their exact parse semantics mirror the CMO3 attribute scalar forms: Boolean is a
 * literal "true" comparison (anything else reads false), Char takes the first character (empty text
 * is an error, which the engine's verbatim-fallback net absorbs), and STRING passes the raw text
 * through — including every non-primitive property type, which receives the raw attribute string.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal enum class ScalarKind {
	INT,
	FLOAT,
	DOUBLE,
	LONG,
	SHORT,
	BYTE,
	BOOLEAN,
	CHAR,
	STRING,
	;

	/**
	 * Parses [raw] attribute text into this kind's value.
	 *
	 * @param String raw The raw attribute text.
	 * @return Any The parsed scalar (never null; absent attributes never reach the parser).
	 */
	fun parse(raw: String): Any =
		when (this) {
			INT -> raw.toInt()
			FLOAT -> raw.toFloat()
			DOUBLE -> raw.toDouble()
			LONG -> raw.toLong()
			SHORT -> raw.toShort()
			BYTE -> raw.toByte()
			BOOLEAN -> raw == "true"
			CHAR -> raw[0]
			STRING -> raw
		}
}

/**
 * One serialized property of a model class, in the exact shape the engine consumes.
 *
 * [name] is the Kotlin property name — child-order replay slots (ChildSlot.KnownField) and the
 * graph-editor bookkeeping (ensureKnownChildSlot / ensurePresentAttr) key on it, so it must stay
 * stable across regenerations.  [serialName] is the on-disk xs.n child name (a @SerialName
 * override, else [name]).  A non-null [attributeName] marks the property as written on the owning
 * tag as an XML attribute of that name (a @SerialAttribute name override, else [name]) instead of
 * as a child element; [scalarKind] is non-null for exactly those attribute properties.
 * [skipIfDefault] is the resolved @DontSerializeIfDefault flag (a class-level annotation promotes
 * every property).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal class PropertyDescriptor(
	val name: String,
	val serialName: String,
	val attributeName: String?,
	val skipIfDefault: Boolean,
	val scalarKind: ScalarKind?,
	val get: (Any) -> Any?,
	val set: (Any, Any?) -> Unit,
)

/**
 * The serialization shape of one model class, bound to one class in the hierarchy: [properties]
 * are the class's DECLARED serialized properties only (mutable, not @DontSerialize), in backing
 * field declaration order — inherited state belongs to [superDescriptor], emitted as the nested
 * `<SuperTag xs.n="super">` child.  [factory] builds an instance with every property at its
 * declared default (model classes are no-arg constructible by construction); the engine uses it
 * both to create instances on read and to materialize the default instance @DontSerializeIfDefault
 * compares against.  [version] is the @SerialTag version, -1 when the class declares none.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Serializer mechanics</a>
 */
internal class ClassDescriptor(
	val tag: String,
	val version: Int,
	val kClass: KClass<*>,
	val factory: () -> Any,
	val properties: List<PropertyDescriptor>,
	val superDescriptor: ClassDescriptor?,
)

/**
 * The serialization shape of one model enum: its element tag and its constants in declaration
 * order.  The engine writes `<Tag xs.n="field" v="CONSTANT" />` and resolves the `v` attribute
 * against [entries] by constant name on read.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §3 Primitive & collection tags</a>
 */
internal class EnumDescriptor(
	val tag: String,
	val version: Int,
	val kClass: KClass<*>,
	val entries: List<Enum<*>>,
)