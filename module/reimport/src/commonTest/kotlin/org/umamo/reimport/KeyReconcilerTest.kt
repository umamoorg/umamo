package org.umamo.reimport

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertEquals

/** The reconcile by key: matched, added, and missing, with non-raster layers invisible to it. */
class KeyReconcilerTest {
	private val source = ArtSourceId("art-0")

	private fun layer(key: String, order: Int, kind: SourceLayerKind = SourceLayerKind.Raster): TestLayer =
		TestLayer(key, key, order, LayerBounds(0, 0, 2, 2), solidRaster(2, 2, 1), kind = kind)

	@Test
	fun bindingsMatchByKeyAndTheRestClassify() {
		val art = TestArt(listOf(layer("lyid:2", 0), layer("lyid:1", 1), layer("text", 2, SourceLayerKind.Text), layer("lyid:9", 3)))
		val bindings = listOf(SourceLayerRef(source, "lyid:1", true), SourceLayerRef(source, "lyid:7", true), SourceLayerRef(source, "text", true))
		val report = KeyReconciler.reconcile(bindings, art)
		assertEquals(
			listOf(
				ReconcileResult.Matched(bindings[0], "lyid:1"),
				ReconcileResult.NeedsReview(bindings[1], ReviewReason.LayerMissing),
				ReconcileResult.NeedsReview(bindings[2], ReviewReason.LayerMissing),
				ReconcileResult.Added("lyid:2"),
				ReconcileResult.Added("lyid:9"),
			),
			report.results,
			"bindings first in their order, then the added raster layers in the art's order; a folder is neither",
		)
		assertEquals(2, report.needsReview.size)
	}
}