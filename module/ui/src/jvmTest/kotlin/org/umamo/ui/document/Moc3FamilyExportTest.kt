package org.umamo.ui.document

import org.umamo.format.moc3.Moc3
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The exported moc FAMILY loads back through the importer that reads a Cubism-baked one.
 *
 * The MOC3's own fidelity is gated far more sharply elsewhere (against the official core); what this
 * covers is everything AROUND it - that the manifest names files that exist, that the atlas pages are
 * where the manifest says, that the cdi3 carries the display names a moc cannot, and that the
 * sidecars an import retained come back out.  Those are exactly the parts a runtime needs and the moc
 * itself says nothing about.
 *
 * The round trip runs entirely in memory once the sample is read: the bundle is a list of named byte
 * arrays, so the loader's injected `readRelative` reads it directly and no temporary directory is
 * involved.
 *
 * Gated on `-Dmoc3.sample` (defaulted to the local corpus by the build); self-skips without it.
 */
class Moc3FamilyExportTest {
	private val sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }

	/**
	 * Imports the sample, exports its family, and re-imports that.
	 *
	 * @param String basename The base name to export under.
	 * @return Pair The source document and the re-imported one, or null when the sample is absent.
	 */
	private fun roundTrip(basename: String): Pair<Moc3Document, Moc3Document>? {
		val sampleFile = sample ?: return null
		val directory = sampleFile.parentFile
		val sourceName = sampleFile.name
		val source =
			buildMoc3Document(sampleFile.path, sourceName, sampleFile.readBytes()) { reference ->
				File(directory, reference).takeIf { it.isFile }?.readBytes()
			}
		val loaded = assertIs<DocumentLoad.Loaded>(source).document as Moc3Document

		// Through the SHIPPED policy, not a copy of it: the page naming, the verbatim-vs-re-encode
		// choice, and the basename strip are the rules under test, so re-deriving them here would only
		// ever assert that the test agrees with itself.
		val bundle = prepareMoc3Export(loaded, loaded.puppet, "$basename.moc3")
		val byName = bundle.files.associate { file -> file.name to file.bytes }
		val reimported =
			buildMoc3Document("$basename.moc3", "$basename.moc3", byName.getValue("$basename.moc3")) { reference ->
				byName[reference]
			}
		return loaded to (assertIs<DocumentLoad.Loaded>(reimported).document as Moc3Document)
	}

	@Test
	fun theExportedFamilyReimports() {
		val (source, reimported) = roundTrip("Exported") ?: return
		assertEquals(source.puppet.drawables.size, reimported.puppet.drawables.size, "drawable count")
		assertEquals(source.puppet.parts.size, reimported.puppet.parts.size, "part count")
		assertEquals(source.puppet.deformers.size, reimported.puppet.deformers.size, "deformer count")
		assertEquals(source.puppet.parameters.size, reimported.puppet.parameters.size, "parameter count")
		assertEquals(source.atlasPages.size, reimported.atlasPages.size, "atlas page count")
	}

	@Test
	fun displayNamesSurviveTheFamily() {
		val (source, reimported) = roundTrip("Exported") ?: return
		// Compared BY ID: the export re-derives its own object ordering (parts pre-order, deformers
		// parent-first), so a positional comparison would report every name as wrong on any model whose
		// authored order differs from that walk.
		assertEquals(
			source.puppet.drawables.associate { it.id to it.name },
			reimported.puppet.drawables.associate { it.id to it.name },
			"art-mesh display names - the Umamo cdi3 extension is the only thing carrying these",
		)
		assertEquals(
			source.puppet.parts.associate { it.id to it.name },
			reimported.puppet.parts.associate { it.id to it.name },
			"part names",
		)
		assertEquals(
			source.puppet.parameters.associate { it.id to it.name },
			reimported.puppet.parameters.associate { it.id to it.name },
			"parameter names",
		)
		assertEquals(
			source.puppet.parameterLinks,
			reimported.puppet.parameterLinks,
			"combined-parameter pairs",
		)
	}

	@Test
	fun retainedSidecarsAreReemittedVerbatim() {
		val (source, reimported) = roundTrip("Exported") ?: return
		// Keyed by (kind, name) rather than compared directly: PassThroughSidecar has no value equality,
		// and the KIND is half of what this asserts.  A sidecar that returns with the right bytes filed
		// under the wrong manifest section is written beside the moc but no longer wired to the rig.
		assertEquals(
			source.sidecars.associate { sidecar -> (sidecar.kind to sidecar.fileName) to sidecar.text },
			reimported.sidecars.associate { sidecar -> (sidecar.kind to sidecar.fileName) to sidecar.text },
			"the sidecars must come back byte-identical, under the same manifest sections",
		)
	}

	@Test
	fun theManifestNamesOnlyFilesTheBundleContains() {
		val sampleFile = sample ?: return
		val directory = sampleFile.parentFile
		val loaded =
			assertIs<DocumentLoad.Loaded>(
				buildMoc3Document(sampleFile.path, sampleFile.name, sampleFile.readBytes()) { reference ->
					File(directory, reference).takeIf { it.isFile }?.readBytes()
				},
			).document as Moc3Document
		val bundle = prepareMoc3Export(loaded, loaded.puppet, "Exported.moc3")
		val names = bundle.files.map { file -> file.name }.toSet()
		val manifest = Moc3.readModel3(bundle.files.first { it.name == "Exported.model3.json" }.bytes.decodeToString())
		val references =
			buildList {
				add(manifest.fileReferences.moc)
				addAll(manifest.fileReferences.textures)
				manifest.fileReferences.pose?.let(::add)
				manifest.fileReferences.physics?.let(::add)
				manifest.fileReferences.userData?.let(::add)
				manifest.fileReferences.displayInfo?.let(::add)
				manifest.fileReferences.expressions?.forEach { expression -> add(expression.file) }
				manifest.fileReferences.motions?.values?.forEach { motions ->
					motions.forEach { motion -> add(motion.file) }
				}
			}
		assertTrue(references.isNotEmpty(), "the manifest must reference something")
		for (reference in references) {
			assertContains(names, reference, "the manifest names $reference, which the bundle does not contain")
		}
		// The non-file sections are user data with no home in PuppetModel; losing them on a round trip
		// would silently delete a rigger's eye-blink wiring.
		assertEquals(loaded.manifest.groups, manifest.groups, "auto-wiring groups")
		assertEquals(loaded.manifest.hitAreas, manifest.hitAreas, "hit areas")
	}
}