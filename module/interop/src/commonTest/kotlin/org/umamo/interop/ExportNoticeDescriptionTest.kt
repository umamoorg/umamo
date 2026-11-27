package org.umamo.interop

import org.umamo.runtime.model.RuntimeFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the one log line per export notice that the editor's log and the command-line tool's report share.
 *
 * The line is a diagnostic surface read off a bug report, so it has to name what it is about and stay
 * short: a finding over a whole rig can list hundreds of drawables, and the line names the first few and
 * counts the rest instead of burying every other line of the log.
 */
class ExportNoticeDescriptionTest {
	@Test
	fun anUnsupportedChangeNamesItsCategoryItsSubjectAndItsReason() {
		val onAnEntity = ExportNotice.UnsupportedChange(ExportEntityCategory.Drawable, "ArtMesh12", ExportNoticeReason.DrawableHasNoMesh)
		val onTheDocument = ExportNotice.UnsupportedChange(ExportEntityCategory.Document, null, ExportNoticeReason.NoAuthoredWorldOrigin)

		assertEquals("[Drawable] ArtMesh12: DrawableHasNoMesh", describeExportNotice(onAnEntity))
		assertEquals("[Document] NoAuthoredWorldOrigin", describeExportNotice(onTheDocument), "a document-level finding has no subject to name")
	}

	@Test
	fun aLongSubjectListNamesTheFirstEightAndCountsTheRest() {
		val names = (1..11).map { index -> "Mesh$index" }

		val stripped = describeExportNotice(ExportNotice.FeatureStripped(RuntimeFeature.ReversedMask, names))

		assertTrue(stripped.contains("Mesh8"), stripped)
		assertTrue(!stripped.contains("Mesh9"), stripped)
		assertTrue(stripped.contains("(+3 more)"), stripped)
		assertTrue(stripped.startsWith("ReversedMask "), stripped)
	}

	@Test
	fun aShortSubjectListIsNamedInFull() {
		val line = describeExportNotice(ExportNotice.WeldDivergence(listOf("Hair", "Brow")))

		assertEquals("weld divergence on Hair, Brow", line)
	}
}