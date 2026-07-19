package org.umamo.ui.workspace

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The closed inventory of editor space types an area can host. This is the Blender "editor type":
 * any area can be switched to any of these via its header dropdown, so the set is agnostic, not tied
 * to a fixed dock position.
 *
 * Each entry carries a stable string [key] used for serialization - we serialize by key, not by
 * ordinal or enum name, so reordering or renaming an entry never corrupts a saved layout.
 *
 * エリアが表示できるエディタ種別の閉じた集合（Blender のエディタタイプ相当）。各エリアはヘッダの
 * ドロップダウンで任意の種別へ切替可能。直列化は安定した key 文字列で行う。
 *
 * @property String key The stable serialization token (never reorder-sensitive).
 */
@Serializable(with = SpaceKindSerializer::class)
enum class SpaceKind(val key: String) {
	/** The 2D modelling viewport (the GL work surface: mesh + deformers). */
	Viewport2D("viewport2d"),

	/** The UV / texture-atlas editor. */
	UvEditor("uv"),

	/** The parts + deformer hierarchy (Blender's Outliner equivalent). */
	Outliner("outliner"),

	/** The parameter cockpit: sliders and (later) the multi-key 2D pad. */
	Parameters("parameters"),

	/** Properties of the selected drawable / deformer / part. */
	Inspector("inspector"),

	/** Settings for the active tool. */
	ToolDetails("tooldetails"),

	/** The undo-history stack: every step, the live one highlighted, click to jump. */
	History("history"),

	/** The diagnostic log console. */
	Logs("logs"),
	;

	companion object {
		/**
		 * Resolves a serialized [key] back to its [SpaceKind], or null when unknown.
		 *
		 * @param String key The stored key token.
		 * @return SpaceKind? The matching kind, or null.
		 */
		fun fromKey(key: String): SpaceKind? = entries.firstOrNull { kind -> kind.key == key }
	}
}

/**
 * Serializes [SpaceKind] as its stable [SpaceKind.key] string. An unknown key (a layout written by a
 * newer build that has a space this build lacks) decodes to a neutral [SpaceKind.Outliner] rather
 * than failing the whole layout - a forward-compatible, non-destructive fallback that also avoids
 * spinning up a GL viewport for an unknown kind.
 *
 * SpaceKind を安定キー文字列として直列化する。未知のキーは中立な Outliner にフォールバックする。
 */
object SpaceKindSerializer : KSerializer<SpaceKind> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("org.umamo.ui.workspace.SpaceKind", PrimitiveKind.STRING)

	/**
	 * Writes the kind's stable key.
	 *
	 * @param Encoder encoder The output encoder.
	 * @param SpaceKind value The kind to write.
	 */
	override fun serialize(encoder: Encoder, value: SpaceKind) {
		encoder.encodeString(value.key)
	}

	/**
	 * Reads a key and resolves it, falling back to [SpaceKind.Outliner] for an unrecognised key.
	 *
	 * @param Decoder decoder The input decoder.
	 * @return SpaceKind The resolved kind.
	 */
	override fun deserialize(decoder: Decoder): SpaceKind = SpaceKind.fromKey(decoder.decodeString()) ?: SpaceKind.Outliner
}
