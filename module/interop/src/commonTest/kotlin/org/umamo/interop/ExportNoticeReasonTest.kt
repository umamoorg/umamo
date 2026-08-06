package org.umamo.interop

import org.umamo.runtime.model.FormChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the structural rendering [ExportNoticeReason] promises its log mirror.
 *
 * The app logs each notice with the reason's own `toString()` rather than a hand-written English
 * sentence, so that the diagnostic surface cannot drift from the case list the way a second copy of
 * the prose would.  That only works while every case is a `data object` or `data class` - a bare
 * `object` would print an identity hash - and while no field's own `toString()` is an identity one.
 * These tests fail the moment either holds false.
 */
class ExportNoticeReasonTest {
	@Test
	fun anArgumentFreeReasonPrintsItsCaseName() {
		assertEquals(
			"NoMatchingSourceToReconcile",
			ExportNoticeReason.NoMatchingSourceToReconcile.toString(),
		)
		assertEquals("DrawableHasNoMesh", ExportNoticeReason.DrawableHasNoMesh.toString())
		assertEquals("NoAuthoredWorldOrigin", ExportNoticeReason.NoAuthoredWorldOrigin.toString())
	}

	@Test
	fun aParameterizedReasonPrintsItsFieldsByName() {
		val truncated = ExportNoticeReason.IdTruncated(recordByteWidth = 64, writtenId = "Param01")
		assertTrue(truncated.toString().contains("writtenId=Param01"), truncated.toString())
		assertTrue(truncated.toString().contains("recordByteWidth=64"), truncated.toString())

		val reordered = ExportNoticeReason.CombinedPairReordered("ParamAngleX", "ParamAngleY")
		assertTrue(reordered.toString().contains("ParamAngleX"), reordered.toString())
		assertTrue(reordered.toString().contains("ParamAngleY"), reordered.toString())
	}

	@Test
	fun aNestedRejectionPrintsThroughItsWrapper() {
		// KeyformCannotBundle is the one case carrying another sealed type, so its log line depends on
		// KeyformBundleRejection honoring the same data-class rule.
		val nested =
			ExportNoticeReason.KeyformCannotBundle(
				KeyformBundleRejection.KeysOutsideChannelSpan(FormChannel.OPACITY),
			)
		assertTrue(nested.toString().contains("OPACITY"), nested.toString())
		assertTrue(nested.toString().contains("KeysOutsideChannelSpan"), nested.toString())
		assertEquals(
			"KeysOutsideGeometrySpan",
			KeyformBundleRejection.KeysOutsideGeometrySpan.toString(),
		)
	}
}
