package org.umamo.ui.document

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Discovery and failure classification of the MOC3 sidecar loader ([buildMoc3Document]): every
 * rejection path must report the matching [DocumentOpenError], and the optional-cdi3 degradation
 * must keep the import working on raw ids.  All file IO is injected, so the rules run against fake
 * readers; the moc-dependent paths read the corpus sample (`-Dmoc3.sample`, defaulted to the local
 * corpus by the build) and self-skip without it.
 */
class Moc3DocumentLoadTest {
	private val sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }

	/** A minimal parseable manifest whose moc/texture references the tests override per case. */
	private val minimalManifestText = """{"Version":3,"FileReferences":{"Moc":"puppet.moc3","Textures":["missing.png"]}}"""

	@Test
	fun missingManifestFailsAsMissingManifest() {
		val load = buildMoc3Document("puppet.moc3", "puppet.moc3", ByteArray(64)) { _ -> null }
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.MissingManifest, failed.failure.error)
		assertEquals("puppet.moc3", failed.failure.displayName)
	}

	@Test
	fun unparseableManifestFailsAsParseFailed() {
		// The manifest EXISTS but is not JSON: that is a parse failure, not a missing file - the
		// alert must not claim a file is absent when it sits right next to the moc.
		val load =
			buildMoc3Document("puppet.moc3", "puppet.moc3", ByteArray(64)) { reference ->
				if (reference == "puppet.model3.json") "not json".encodeToByteArray() else null
			}
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.ParseFailed, failed.failure.error)
	}

	@Test
	fun manifestWithUnknownKeysStillParses() {
		// Newer Cubism exports add manifest keys the schema does not model (e.g. MotionSync); the
		// reader must tolerate them.  The unknown-keyed manifest parses, so the load proceeds past
		// the manifest gate and fails later on the unresolvable texture instead.
		val manifestWithExtras =
			"""{"Version":3,"MotionSync":"puppet.motionsync3.json","FileReferences":{"Moc":"puppet.moc3","MotionSync":"x.json","Textures":["missing.png"]}}"""
		val load =
			buildMoc3Document("puppet.moc3", "puppet.moc3", ByteArray(64)) { reference ->
				if (reference == "puppet.model3.json") manifestWithExtras.encodeToByteArray() else null
			}
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.ParseFailed, failed.failure.error, "moc bytes are garbage, so decode fails AFTER the manifest gate")
	}

	@Test
	fun uppercaseExtensionDerivesTheLowercaseFamilyBasename() {
		// Every route here matches the extension case-insensitively, so "PUPPET.MOC3" must look for
		// "PUPPET.model3.json", not "PUPPET.MOC3.model3.json".
		var requestedManifest: String? = null
		buildMoc3Document("PUPPET.MOC3", "PUPPET.MOC3", ByteArray(64)) { reference ->
			if (reference.endsWith(".model3.json")) {
				requestedManifest = reference
			}
			null
		}
		assertEquals("PUPPET.model3.json", requestedManifest)
	}

	@Test
	fun corruptMocFailsAsParseFailed() {
		// A parseable manifest next to garbage moc bytes: the manifest gate passes, the decode throws.
		val load =
			buildMoc3Document("puppet.moc3", "puppet.moc3", ByteArray(64)) { reference ->
				if (reference == "puppet.model3.json") minimalManifestText.encodeToByteArray() else null
			}
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.ParseFailed, failed.failure.error)
	}

	@Test
	fun missingTextureFailsAsMissingTexture() {
		val mocFile = sample
		if (mocFile == null) {
			println("moc3.sample not present; skipping missing-texture test")
			return
		}
		// A real decodable moc whose manifest lists a texture the reader cannot resolve.
		val load =
			buildMoc3Document(mocFile.path, mocFile.name, mocFile.readBytes()) { reference ->
				if (reference.endsWith(".model3.json")) minimalManifestText.encodeToByteArray() else null
			}
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.MissingTexture, failed.failure.error)
	}

	@Test
	fun undecodableTextureFailsAsMissingTexture() {
		val mocFile = sample
		if (mocFile == null) {
			println("moc3.sample not present; skipping undecodable-texture test")
			return
		}
		val load =
			buildMoc3Document(mocFile.path, mocFile.name, mocFile.readBytes()) { reference ->
				when {
					reference.endsWith(".model3.json") -> minimalManifestText.encodeToByteArray()
					reference == "missing.png" -> "not a png".encodeToByteArray()
					else -> null
				}
			}
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.MissingTexture, failed.failure.error)
	}

	@Test
	fun loadsCorpusFamilyAndDegradesWithoutCdi3() {
		val mocFile = sample
		if (mocFile == null) {
			println("moc3.sample not present; skipping corpus load test")
			return
		}
		val directory = mocFile.parentFile

		fun readRelative(reference: String): ByteArray? = directory.resolve(reference).takeIf { it.isFile }?.readBytes()

		// Full family: manifest + textures + cdi3 resolve from the corpus directory.
		val load = buildMoc3Document(mocFile.path, mocFile.name, mocFile.readBytes(), ::readRelative)
		val loaded = assertIs<DocumentLoad.Loaded>(load)
		val document = assertIs<Moc3Document>(loaded.document)
		assertTrue(document.puppet.drawables.isNotEmpty(), "expected drawables")
		assertTrue(document.textures.atlases.isNotEmpty(), "expected decoded atlas pages")
		assertEquals(
			document.puppet.drawables.size,
			document.textures.atlasIndexByDrawableId.size,
			"every drawable resolves an atlas page",
		)
		assertTrue(
			document.puppet.parameters.any { parameter -> parameter.id.raw != parameter.name },
			"cdi3 display names applied",
		)

		// cdi3 withheld: the import still succeeds and every name degrades to the raw format id.
		val withoutCdi3 =
			buildMoc3Document(mocFile.path, mocFile.name, mocFile.readBytes()) { reference ->
				if (reference.endsWith(".cdi3.json")) null else readRelative(reference)
			}
		val degraded = assertIs<Moc3Document>(assertIs<DocumentLoad.Loaded>(withoutCdi3).document)
		assertTrue(
			degraded.puppet.parameters.all { parameter -> parameter.id.raw == parameter.name },
			"raw-id parameter names without cdi3",
		)
		assertTrue(degraded.puppet.parameterTree.isEmpty(), "no parameter tree without cdi3")
		assertTrue(degraded.puppet.parameterLinks.isEmpty(), "no parameter links without cdi3")
	}
}
