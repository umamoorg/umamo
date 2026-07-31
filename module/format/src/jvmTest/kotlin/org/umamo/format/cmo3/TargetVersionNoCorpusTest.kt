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
	// SDK3.3/4.0/4.2/NA files were saved by the current official editor specifically to pin these
	// literals; the SDKNA file carries the SDK(N/A)/Latest sentinel, which decodes to
	// null (no target version).
	private val expectedByFileName: Map<String, Cmo3TargetVersion?> =
		mapOf(
			"EricaTamamo.cmo3" to Cmo3TargetVersion.V40,
			"ModelWithOffscreen.cmo3" to Cmo3TargetVersion.V53,
			"ModelWithOffscreenSDK3.3.cmo3" to Cmo3TargetVersion.V33,
			"ModelWithoutOffscreenSDK3.3.cmo3" to Cmo3TargetVersion.V33,
			"ModelWithoutOffscreenSDK4.0.cmo3" to Cmo3TargetVersion.V40,
			"ModelWithoutOffscreenSDK4.2.cmo3" to Cmo3TargetVersion.V42,
			"ModelWithoutOffscreenSDKNA.cmo3" to null,
			"modelA.cmo3" to Cmo3TargetVersion.V53,
			"modelB.cmo3" to Cmo3TargetVersion.V50,
			"modelD.cmo3" to Cmo3TargetVersion.V30,
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
			// EVERY probe file's value must deserialize as an Int (or be absent) - a VerbatimNode or
			// other fallback type would silently coerce to null and land every import on NoTarget, so
			// the type is asserted before the tolerant cast.  The pinned subset must additionally
			// decode to its known authored target.
			val rawField = modelRoot.targetVersionNo
			assertTrue(
				rawField == null || rawField is Int,
				"${file.name} targetVersionNo is an Int or absent, got ${rawField?.let { value -> value::class.simpleName }}",
			)
			val rawVersionNo = rawField
			val decoded = Cmo3TargetVersion.fromVersionNo(rawVersionNo)
			if (expectedByFileName.containsKey(file.name)) {
				assertEquals(expectedByFileName[file.name], decoded, "${file.name} targetVersionNo decode")
				pinnedCount++
			}
			if (file.name == "ModelWithoutOffscreenSDKNA.cmo3") {
				// The freshly saved SDK(N/A)/Latest selection pins the sentinel value itself.
				assertEquals(Cmo3TargetVersion.LATEST_VERSION_NO, rawVersionNo, "${file.name} raw sentinel")
			}
		}
		assertTrue(pinnedCount > 0, "probe list contained none of the pinned corpus files")
	}
}
