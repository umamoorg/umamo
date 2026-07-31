package org.umamo.runtime.ingest

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.unsupportedFeaturesInUse
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Corpus-gated ingest of the authored runtime target, and the strip diff against a document that
 * deliberately exceeds its own target.  Skips (with a note) when the probe property is absent.
 */
class RuntimeTargetIngestTest {
	private val probeFilesByName: Map<String, File> =
		System.getProperty("cmo3.probe")
			?.split(",")
			?.map(::File)
			?.filter { it.isFile }
			?.associateBy { it.name }
			.orEmpty()

	private fun importByName(fileName: String): PuppetModel? {
		val file = probeFilesByName[fileName] ?: return null
		val root = Cmo3.read(file).root as? CModelSource ?: error("$fileName root is not a CModelSource")
		return Cmo3Import.fromModelSource(root)
	}

	@Test
	fun authoredTargetLandsOnThePuppetModel() {
		val puppet =
			importByName("EricaTamamo.cmo3") ?: run {
				println("cmo3.probe has no EricaTamamo.cmo3; skipping runtime-target ingest test")
				return
			}
		// CMO3: CModelSource field targetVersionNo - EricaTamamo was authored targeting SDK 4.0.
		assertEquals(RuntimeTarget.Cubism40, puppet.runtimeTarget)
	}

	@Test
	fun latestSentinelIngestsAsNoTarget() {
		val puppet =
			importByName("ModelWithoutOffscreenSDKNA.cmo3") ?: run {
				println("cmo3.probe has no ModelWithoutOffscreenSDKNA.cmo3; skipping sentinel ingest test")
				return
			}
		// CMO3: CModelSource field targetVersionNo - the SDK(N/A)/Latest selection restricts nothing.
		assertEquals(RuntimeTarget.NoTarget, puppet.runtimeTarget)
	}

	@Test
	fun overreachingDocumentReportsItsStripDiff() {
		// ModelWithOffscreenSDK3.3 was saved by the official editor with offscreen drawing enabled
		// BEFORE the target was switched to SDK 3.3 - the editor keeps the out-of-target data in the
		// file, the exact never-break behavior Umamo mirrors.  Its ingest must land on Cubism33 and
		// the strip diff against that target must flag the offscreen use.
		val puppet =
			importByName("ModelWithOffscreenSDK3.3.cmo3") ?: run {
				println("cmo3.probe has no ModelWithOffscreenSDK3.3.cmo3; skipping strip-diff corpus test")
				return
			}
		assertEquals(RuntimeTarget.Cubism33, puppet.runtimeTarget)
		val stripDiff = puppet.unsupportedFeaturesInUse(RuntimeTarget.Cubism33)
		assertTrue(RuntimeFeature.PartComposite in stripDiff, "offscreen use is flagged, got $stripDiff")
		assertEquals(emptySet(), puppet.unsupportedFeaturesInUse(RuntimeTarget.NoTarget), "NoTarget strips nothing")
	}
}
