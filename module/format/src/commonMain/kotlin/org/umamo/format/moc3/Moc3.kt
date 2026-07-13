package org.umamo.format.moc3

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.umamo.format.FileKind
import org.umamo.format.FormatCodec
import org.umamo.format.FormatVersion
import org.umamo.format.moc3.decode.MocDecoder
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.format.moc3.json.Model3Json
import org.umamo.format.moc3.json.Physics3Json
import org.umamo.format.moc3.json.SidecarJson
import org.umamo.format.moc3.json.UserData3Json
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.MocVersion

/**
 * Reads and writes Live2D Cubism runtime assets: the `.moc3` binary model and its JSON sidecars.
 *
 * EN: Implements [FormatCodec] for the binary `.moc3` container (round-trip is byte-identical for
 *     unedited files); the JSON sidecar helpers handle `model3.json` and friends. Those sidecars are
 *     `String`-shaped (not `ByteArray`), so they sit alongside the [FormatCodec] members rather than
 *     within them. The `.moc3` and its sidecars are sibling files (the manifest references the rest
 *     by path), so they are decoupled here - pair them yourself. Pure Kotlin (JVM + Android); no
 *     JDOM/reflection.
 * JA: `.moc3` バイナリと JSON サイドカーの読み書き。未編集の `.moc3` はバイト単位で再生成。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public object Moc3 : FormatCodec<MocModel> {
	/** This codec handles [FileKind.Moc3]. */
	override val kind: FileKind get() = FileKind.Moc3

	/**
	 * True if [candidateBytes] starts with the `MOC3` magic and is at least header-sized.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether this looks like a `.moc3`.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean = MocCodec.isMoc3(candidateBytes)

	/**
	 * Cheap version probe: reads the version byte without parsing the section table.
	 *
	 * @param ByteArray bytes The complete file contents.
	 * @return FormatVersion? The `.moc3` version, or null when the magic or byte is unrecognized.
	 */
	override fun getVersion(bytes: ByteArray): FormatVersion? {
		if (!MocCodec.isMoc3(bytes)) {
			return null
		}
		// MOC3.md §version gating: version byte @ +0x04.
		val versionByte = bytes[4].toInt() and 0xFF
		return MocVersion.entries.firstOrNull { it.byteValue == versionByte }
	}

	/**
	 * Parses a `.moc3` from raw bytes.
	 *
	 * @param ByteArray bytes The file contents.
	 * @return MocModel The parsed model.
	 */
	override fun read(bytes: ByteArray): MocModel = MocCodec.read(bytes)

	/**
	 * Serializes a [MocModel] to `.moc3` bytes.
	 * Byte-identical to the source for an unedited model.
	 *
	 * @param MocModel model The model to write.
	 * @return ByteArray The complete `.moc3` file bytes.
	 */
	override fun write(model: MocModel): ByteArray = MocCodec.write(model)

	/**
	 * Decodes a parsed [MocModel] into the full semantic [MocDocument].
	 *
	 * @param MocModel model The parsed model.
	 * @return MocDocument The decoded document.
	 */
	public fun decode(model: MocModel): MocDocument = MocDecoder.decode(model)

	/**
	 * Reads and decodes a `.moc3` straight to the semantic [MocDocument].
	 *
	 * @param ByteArray bytes The file contents.
	 * @return MocDocument The decoded document.
	 */
	public fun decode(bytes: ByteArray): MocDocument = MocDecoder.decode(MocCodec.read(bytes))

	/**
	 * Bakes a (possibly-edited) [MocDocument] to runtime-valid `.moc3` bytes, using [reference] for the
	 * sections not yet synthesized from the object model (CountInfo, runtime pointer arrays, blend-shape
	 * / offscreen value tables).  Unedited documents reproduce the original section data.
	 *
	 * @param MocModel reference The original `.moc3` to use for the sections not yet synthesized.
	 * @param MocDocument doc The document to bake.
	 * @return ByteArray The baked `.moc3` file bytes.
	 */
	public fun bake(reference: MocModel, doc: MocDocument): ByteArray =
		org.umamo.format.moc3.encode.MocEncoder.bake(reference, doc)

	/**
	 * Parses a `model3.json` manifest.
	 *
	 * @param String text The file contents.
	 * @return Model3Json The parsed manifest.
	 */
	public fun readModel3(text: String): Model3Json = SidecarJson.decodeFromString(text)

	/**
	 * Serializes a `model3.json` manifest (matches the editor's formatting).
	 *
	 * @param Model3Json model The manifest to write.
	 * @return String The serialized JSON.
	 */
	public fun writeModel3(model: Model3Json): String = SidecarJson.encodeToString(model)

	/**
	 * Parses a `physics3.json` rig definition.
	 *
	 * @param String text The file contents.
	 * @return Physics3Json The parsed rig definition.
	 */
	public fun readPhysics3(text: String): Physics3Json = SidecarJson.decodeFromString(text)

	/**
	 * Serializes a `physics3.json` rig definition.
	 *
	 * @param Physics3Json physics The rig definition to write.
	 * @return String The serialized JSON.
	 */
	public fun writePhysics3(physics: Physics3Json): String = SidecarJson.encodeToString(physics)

	/**
	 * Parses a `cdi3.json` display-info file.
	 *
	 * @param String text The file contents.
	 * @return Cdi3Json The parsed display info.
	 */
	public fun readCdi3(text: String): Cdi3Json = SidecarJson.decodeFromString(text)

	/**
	 * Serializes a `cdi3.json` display-info file.
	 *
	 * @param Cdi3Json displayInfo The display info to write.
	 * @return String The serialized JSON.
	 */
	public fun writeCdi3(displayInfo: Cdi3Json): String = SidecarJson.encodeToString(displayInfo)

	/**
	 * Parses a `userdata3.json` file.
	 *
	 * @param String text The file contents.
	 * @return UserData3Json The parsed user data.
	 */
	public fun readUserData3(text: String): UserData3Json = SidecarJson.decodeFromString(text)

	/**
	 * Serializes a `userdata3.json` file.
	 *
	 * @param UserData3Json userData The user data to write.
	 * @return String The serialized JSON.
	 */
	public fun writeUserData3(userData: UserData3Json): String = SidecarJson.encodeToString(userData)
}
