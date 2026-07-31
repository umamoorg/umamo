package org.umamo.format.cmo3

import org.umamo.format.cmo3.model.custom.CModelSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Corpus-gated targetVersionNo decode: known corpus documents must decode to their authored SDK
 * target.  Skips (with a note) when the probe property is absent, like the other corpus tests.
 */
class TargetVersionNoCorpusTest {
	private val probeFiles: List<File> =
		System.getProperty("cmo3.probe")
			?.split(",")
			?.map(::File)
			?.filter { it.isFile }
			.orEmpty()

	// CMO3: CModelSource field targetVersionNo - the authored target per corpus file.  The
	// SDK3.3/4.0/4.2 files were saved by the current official editor specifically to pin these
	// literals; haruto carries the unconfirmed 9000000 sentinel, which must stay unknown (null).
	private val expectedByFileName: Map<String, Cmo3TargetVersion?> =
		mapOf(
			"EricaTamamo.cmo3" to Cmo3TargetVersion.V40,
			"ModelWithOffscreen.cmo3" to Cmo3TargetVersion.V53,
			"ModelWithOffscreenSDK3.3.cmo3" to Cmo3TargetVersion.V33,
			"ModelWithoutOffscreenSDK3.3.cmo3" to Cmo3TargetVersion.V33,
			"ModelWithoutOffscreenSDK4.0.cmo3" to Cmo3TargetVersion.V40,
			"ModelWithoutOffscreenSDK4.2.cmo3" to Cmo3TargetVersion.V42,
			"modelA.cmo3" to Cmo3TargetVersion.V53,
			"modelB.cmo3" to Cmo3TargetVersion.V50,
			"modelD.cmo3" to Cmo3TargetVersion.V30,
			"miku.cmo3" to Cmo3TargetVersion.V30,
			"haruto_pc_pro_t02.cmo3" to null,
		)

	@Test
	fun corpusDocumentsDecodeToTheirAuthoredTarget() {
		if (probeFiles.isEmpty()) {
			println("cmo3.probe not present; skipping targetVersionNo corpus test")
			return
		}
		var pinnedCount = 0
		for (file in probeFiles) {
			val modelRoot = Cmo3.read(file).root as? CModelSource
			assertNotNull(modelRoot, "${file.name} root is a CModelSource")
			// Every corpus value must be an int (or absent) and decode without surprises; the pinned
			// subset must decode to its known authored target.
			val decoded = Cmo3TargetVersion.fromVersionNo(modelRoot.targetVersionNo as? Int)
			if (expectedByFileName.containsKey(file.name)) {
				assertEquals(expectedByFileName[file.name], decoded, "${file.name} targetVersionNo decode")
				pinnedCount++
			}
		}
		assertTrue(pinnedCount > 0, "probe list contained none of the pinned corpus files")
	}
}
