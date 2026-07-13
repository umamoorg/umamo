package org.umamo.ui.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.umamo.settings.Settings

/** The settings key the whole interface layout is stored under. */
const val INTERFACE_LAYOUT_KEY: String = "interface.layout"

/**
 * The JSON configuration for (de)serializing the area-tree layout. classDiscriminator = "type" pairs
 * with each AreaNode subtype's @SerialName ("split"/"leaf") to drive the sealed-interface
 * polymorphism; encodeDefaults so ratio and other defaulted fields always persist; ignoreUnknownKeys
 * so a layout written by a newer build (extra fields) still loads. Held module-internal - callers go
 * through the encode/decode helpers, not this directly.
 *
 * エリアツリー直列化用の JSON 設定。type 識別子で sealed の多態を解決し、既定値も書き出す。
 */
internal val LayoutJson: Json =
	Json {
		classDiscriminator = "type"
		encodeDefaults = true
		ignoreUnknownKeys = true
		prettyPrint = false
	}

/**
 * The same scheme as [LayoutJson] but pretty-printed, used only for the human-facing workspace.json that
 * Export writes (a file a user may read or hand-edit before re-importing).  Import parses with [LayoutJson],
 * which reads compact and pretty alike, so the two stay interchangeable.
 *
 * Export 用に整形して出力する点だけが [LayoutJson] と異なる。読み込みは [LayoutJson] が両方を解釈する。
 */
internal val LayoutExportJson: Json =
	Json {
		classDiscriminator = "type"
		encodeDefaults = true
		ignoreUnknownKeys = true
		prettyPrint = true
	}

/**
 * Encodes an [InterfaceLayout] to a [JsonElement] for storage under the interface.layout settings key
 * (Settings.set takes a JsonElement, so we stay inside its API without re-stringifying).
 *
 * レイアウトを JsonElement に符号化する（Settings.set が JsonElement を取るため）。
 *
 * @param InterfaceLayout layout The layout to encode.
 * @return JsonElement The encoded tree.
 */
fun encodeLayout(layout: InterfaceLayout): JsonElement = LayoutJson.encodeToJsonElement(InterfaceLayout.serializer(), layout)

/**
 * Decodes an [InterfaceLayout] from a [JsonElement], or null if the element is malformed or describes
 * an empty layout (no workspaces) - both of which mean "fall back to the seeded defaults". Never
 * throws: a corrupt stored layout must not prevent the editor from opening.
 *
 * JsonElement からレイアウトを復号する。不正・空なら null（既定レイアウトにフォールバック）。例外は投げない。
 *
 * @param JsonElement element The stored layout element.
 * @return InterfaceLayout? The decoded layout, or null to signal "use defaults".
 */
fun decodeLayout(element: JsonElement): InterfaceLayout? =
	runCatching { LayoutJson.decodeFromJsonElement(InterfaceLayout.serializer(), element) }
		.getOrNull()
		?.takeIf { layout -> layout.workspaces.isNotEmpty() }

/**
 * Loads the persisted layout, or seeds the defaults when none is stored (decode failure or the empty
 * `interface.layout.workspaces: []` baseline). On a seed it writes the concrete default back once, so
 * the stored layout is materialised on first run (mirrors how window state seeds its defaults).
 *
 * 保存済みレイアウトを読み込む。無ければ既定を種として書き戻す。
 *
 * @param Settings settings The settings store to read/seed.
 * @return InterfaceLayout The loaded or freshly-seeded layout.
 */
fun loadLayout(settings: Settings): InterfaceLayout {
	val stored = settings.get(INTERFACE_LAYOUT_KEY)?.let { element -> decodeLayout(element) }
	if (stored != null) {
		return stored
	}
	val seeded = defaultLayout()
	saveLayout(settings, seeded)
	return seeded
}

/**
 * Persists [layout] under the interface.layout settings key as a JsonElement (staying inside the
 * Settings API, which takes/returns JsonElement). Settings handles the write to the user layer, the
 * re-merge over defaults, and the disk write.
 *
 * レイアウトを interface.layout キーに JsonElement として保存する。
 *
 * @param Settings settings The settings store to write to.
 * @param InterfaceLayout layout The layout to persist.
 */
fun saveLayout(settings: Settings, layout: InterfaceLayout) {
	settings.set(INTERFACE_LAYOUT_KEY, encodeLayout(layout))
}

/**
 * Parses a whole-layout workspace.json [text] into a validated [InterfaceLayout], or null if it is
 * malformed or not a full layout.  Wraps the same [decodeLayout] validator the persisted layout uses, so
 * a single-workspace export (no `workspaces` array) returns null here - that is how Import tells the two
 * file shapes apart (try this first, then [decodeWorkspaceText]).  Never throws.
 *
 * レイアウト全体の workspace.json を検証付きで [InterfaceLayout] に復号する。不正・単一ワークスペースなら null。
 *
 * @param String text The raw JSON file contents.
 * @return InterfaceLayout? The decoded layout, or null when it is not a valid full layout.
 */
fun decodeLayoutText(text: String): InterfaceLayout? =
	runCatching { LayoutJson.parseToJsonElement(text) }.getOrNull()?.let { element -> decodeLayout(element) }

/**
 * Parses a single-workspace workspace.json [text] into a [Workspace], or null if it is malformed or not a
 * single workspace.  A full-layout file (no `root`/`id` at the top level) fails to decode here, so this is
 * the second branch Import tries after [decodeLayoutText].  The caller is expected to re-mint ids before
 * inserting the result (see the append path) so it never collides with an existing workspace.  Never throws.
 *
 * 単一ワークスペースの workspace.json を [Workspace] に復号する。不正・レイアウト全体なら null。
 *
 * @param String text The raw JSON file contents.
 * @return Workspace? The decoded workspace, or null when it is not a valid single workspace.
 */
fun decodeWorkspaceText(text: String): Workspace? =
	runCatching { LayoutJson.decodeFromString(Workspace.serializer(), text) }.getOrNull()

/**
 * Renders the persisted interface layout as pretty-printed JSON for Export All Workspaces, or null when no
 * layout is stored yet.  Exports the saved element verbatim (just reformatted), matching "the saved JSON
 * from settings.json": any forward-compatible extra keys a newer build wrote are preserved, not dropped.
 *
 * 保存済みのレイアウトを整形 JSON として出力する（未保存なら null）。保存された要素をそのまま整形する。
 *
 * @param Settings settings The settings store to read the layout from.
 * @return String? The pretty-printed layout JSON, or null when nothing is stored.
 */
fun exportLayoutText(settings: Settings): String? =
	settings.get(INTERFACE_LAYOUT_KEY)?.let { element -> LayoutExportJson.encodeToString(JsonElement.serializer(), element) }

/**
 * Renders a single [workspace] as pretty-printed JSON for Export This Workspace.  The result decodes back
 * through [decodeWorkspaceText]; on re-import it is appended as a fresh tab (ids re-minted by the caller).
 *
 * 単一ワークスペースを整形 JSON として出力する（Export This Workspace 用）。
 *
 * @param Workspace workspace The workspace to serialize.
 * @return String The pretty-printed workspace JSON.
 */
fun exportWorkspaceText(workspace: Workspace): String = LayoutExportJson.encodeToString(Workspace.serializer(), workspace)
