package org.umamo.format.uma

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.umamo.format.binary.ZipArchive
import org.umamo.format.uma.puppet.samplePuppet
import org.umamo.format.uma.sources.UmaSource
import org.umamo.format.uma.sources.UmaSources
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the editor entry (docs/format/UMA.md §7): it is written as a JSON Merge Patch over the tree as read, it is
 * never minted empty, and a file with it stripped loads the same puppet and sources (UMA §7).
 */
class UmaEditorEntryTest {
	/**
	 * An outliner block with the given open branches, under one area id.
	 *
	 * @param String areaId   The area the block belongs to.
	 * @param List   expanded The open node ids.
	 * @return JsonObject The patch.
	 */
	private fun outlinerPatch(areaId: String, expanded: List<String>): JsonObject =
		buildJsonObject {
			put(
				"areas",
				buildJsonObject {
					put(
						areaId,
						buildJsonObject {
							put("outliner", buildJsonObject { put("expanded", JsonArray(expanded.map(::JsonPrimitive))) })
						},
					)
				},
			)
		}

	/**
	 * A document holding a puppet and a source list, which is what a stripped editor entry must not disturb.
	 *
	 * @return UmaModel The document.
	 */
	private fun sampleDocument(): UmaModel =
		UmaModel
			.create(TEST_WRITER)
			.withPuppet(samplePuppet())
			.withSources(UmaSources(listOf(UmaSource("guid-7", "Erica.psd", "psd"))))

	/** Objects merge, any other value replaces whole, and null removes (RFC 7386). */
	@Test
	fun aPatchMergesObjectsReplacesValuesAndRemovesNulls() {
		val target =
			buildJsonObject {
				put("kept", "as read")
				put("replaced", buildJsonArray { add(JsonPrimitive("old")) })
				put("removed", 1)
				put("nested", buildJsonObject { put("foreign", true) })
			}
		val patch =
			buildJsonObject {
				put("replaced", buildJsonArray { add(JsonPrimitive("new")) })
				put("removed", JsonNull)
				put("nested", buildJsonObject { put("mine", 2) })
				put("added", "last")
			}

		val merged = applyMergePatch(target, patch)

		assertEquals(listOf("kept", "replaced", "nested", "added"), merged.keys.toList(), "members keep their order and new ones follow")
		assertEquals("as read", merged["kept"]!!.jsonPrimitive.content)
		assertEquals(listOf("new"), merged["replaced"]!!.jsonArray.map { element -> element.jsonPrimitive.content }, "an array replaces whole")
		assertEquals(setOf("foreign", "mine"), merged["nested"]!!.jsonObject.keys, "an object merges member by member")
	}

	/** A deviation that returns to its default takes its block with it, up to the root. */
	@Test
	fun anObjectTheMergeEmptiesIsRemoved() {
		val target = outlinerPatch("area-1", listOf("part:12"))
		val patch =
			buildJsonObject {
				put("areas", buildJsonObject { put("area-1", buildJsonObject { put("outliner", buildJsonObject { put("expanded", JsonNull) }) }) })
			}

		assertEquals(JsonObject(emptyMap()), applyMergePatch(target, patch))
	}

	/** A member the patch does not name survives, which is what keeps a newer writer's state in the file. */
	@Test
	fun membersThePatchDoesNotNameSurvive() {
		val asRead =
			buildJsonObject {
				put("timeline", buildJsonObject { put("from", "future") })
				put("areas", buildJsonObject { put("area-1", buildJsonObject { put("dopesheet", buildJsonObject { put("from", "future") }) }) })
			}
		val document = sampleDocument().withLiveContent(UmaEntryKind.Editor, asRead)

		val saved = document.withEditorState(outlinerPatch("area-1", listOf("part:12")))
		val tree = assertNotNull(Uma.read(Uma.write(saved)).editorState)

		assertEquals("future", tree["timeline"]!!.jsonObject["from"]!!.jsonPrimitive.content)
		val area = tree["areas"]!!.jsonObject["area-1"]!!.jsonObject
		assertEquals(setOf("dopesheet", "outliner"), area.keys, "the foreign block stays beside the one this writer set")
	}

	/** A null for an area removes it: how a stale layout's ids leave the file (UMA §7.5). */
	@Test
	fun aNullAreaLeavesTheFile() {
		val document = sampleDocument().withEditorState(outlinerPatch("area-stale", listOf("part:1")))
		val patch =
			buildJsonObject {
				put(
					"areas",
					buildJsonObject {
						put("area-stale", JsonNull)
						put("area-mine", buildJsonObject { put("outliner", buildJsonObject { put("expanded", buildJsonArray { add(JsonPrimitive("part:2")) }) }) })
					},
				)
			}

		val areas = assertNotNull(document.withEditorState(patch).editorState)["areas"]!!.jsonObject

		assertEquals(setOf("area-mine"), areas.keys)
	}

	/** Untouched editor state mints no entry, and an unchanged tree is the same document. */
	@Test
	fun anEmptyPatchAddsNoEntryAndChangesNothing() {
		val bare = sampleDocument()
		assertSame(bare, bare.withEditorState(JsonObject(emptyMap())), "no entry for a tree with nothing in it")
		assertNull(bare.editorState)
		assertNull(bare.entries.firstOrNull { entry -> entry.kind == UmaEntryKind.Editor.wireName })

		val patch = outlinerPatch("area-1", listOf("part:12"))
		val withState = bare.withEditorState(patch)
		assertSame(withState, withState.withEditorState(patch), "the same state again is not a new document")
		assertContentEquals(Uma.write(withState), Uma.write(Uma.read(Uma.write(withState)).withEditorState(patch)), "and writes the same bytes")
	}

	/** The entry is optional, at the kind's default path, and reads back as the tree that was written. */
	@Test
	fun theEntryRoundTripsAsAnOptionalLiveTree() {
		val patch = outlinerPatch("area-1", listOf("part:12", "deformer:4"))

		val reopened = Uma.read(Uma.write(sampleDocument().withEditorState(patch)))

		assertEquals(patch, reopened.editorState)
		val entry = reopened.entries.single { candidate -> candidate.kind == UmaEntryKind.Editor.wireName }
		assertEquals(UmaEntryKind.Editor.defaultPath, entry.path)
		assertEquals(false, entry.required, "editor state is never load-bearing")
	}

	/** An editor entry too new to interpret is carried, and a patch that would change it is refused. */
	@Test
	fun aTooNewEditorEntryIsCarriedNotReplaced() {
		val futureBytes = """{ "layout": "v2" }""".encodeToByteArray()
		val archive =
			umaArchiveOf(
				manifestJson(
					listOf(
						recordJson("model/puppet.json", "puppet", required = true),
						recordJson("editor/state.json", "editor", version = 2, minVersion = 2),
					),
				),
				listOf(TestEntry("model/puppet.json", """{ "marker": "puppet" }""".encodeToByteArray()), TestEntry("editor/state.json", futureBytes)),
			)
		val document = Uma.read(archive)

		assertNull(document.editorState, "a tree this reader would misread is not read")
		assertEquals(true, document.holdsTooNewEntry(UmaEntryKind.Editor))
		assertEquals(false, document.isReadOnly, "and never blocks a save: the entry is optional")
		assertSame(document, document.withEditorState(JsonObject(emptyMap())), "nothing to write is nothing to refuse")
		assertFailsWith<UmaWriteException> { document.withEditorState(outlinerPatch("area-1", listOf("part:1"))) }
	}

	/**
	 * UMA §7: a file with its editor entry and that entry's manifest record stripped loads the same puppet and
	 * the same sources, because nothing in the model is read from the editor entry.
	 */
	@Test
	fun aFileStrippedOfItsEditorEntryLoadsTheSameDocument() {
		val full = Uma.write(sampleDocument().withEditorState(outlinerPatch("area-1", listOf("part:12"))))
		val archive = ZipArchive.read(full)
		val manifest = parseJsonObject(archive.contents(archive.entry(UmaContainer.MANIFEST_PATH)!!)) { detail -> UmaReadFailure.MalformedManifest(detail) }
		val editorPath = UmaEntryKind.Editor.defaultPath
		val strippedManifest =
			JsonObject(
				manifest + ("entries" to JsonArray(manifest["entries"]!!.jsonArray.filter { record -> record.jsonObject["path"]!!.jsonPrimitive.content != editorPath })),
			)
		val keptEntries =
			archive.entries
				.filter { entry -> entry.name != editorPath && !UmaContainer.isReservedPath(entry.name) }
				.map { entry -> TestEntry(entry.name, archive.contents(entry)) }
		val stripped = Uma.read(umaArchiveOf(strippedManifest.toString(), keptEntries))

		val whole = Uma.read(full)
		assertNotNull(whole.editorState)
		assertNull(stripped.editorState, "the entry is gone")
		assertEquals(whole.puppet, stripped.puppet, "and the puppet is the same without it")
		assertEquals(whole.sources, stripped.sources)
		assertEquals(whole.textures, stripped.textures)
	}
}