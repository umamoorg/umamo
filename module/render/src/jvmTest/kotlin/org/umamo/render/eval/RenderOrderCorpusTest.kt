package org.umamo.render.eval

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.runtime.ingest.Cmo3Import
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Corpus E2E for draw order. The official editor's EULA blocks re-saving an edited model, so instead
 * this bumps a real drawable's keyform draw order in flight and asserts the whole chain (CMO3
 * import → deformation eval → [paintOrder]) lifts it to the front - the real-model counterpart of the
 * synthetic `RenderOrderTest`. Gated on `-Dcmo3.sample`; self-skips without it.
 */
class RenderOrderCorpusTest {
	private fun elements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	@Test
	fun bumpedDrawOrderPaintsFrontmostOnCorpus() {
		val file = File(System.getProperty("cmo3.sample") ?: "")
		if (!file.isFile) {
			println("[ord-e2e] no cmo3.sample; skip")
			return
		}
		val root = Cmo3.read(file).root as? CModelSource ?: return

		// The backmost drawable at the default pose (parts-tree order, all draw orders equal - an empty
		// map makes paintOrder fall back to the Cubism default for every drawable, i.e. the base order).
		val baseline = Cmo3Import.fromModelSource(root)
		val backmost = baseline.drawables.firstOrNull() ?: return
		val defaultOrder = paintOrder(baseline.drawables.map { it.id }, emptyMap())
		assertEquals(backmost.id, defaultOrder.first(), "precondition: target starts backmost")
		assertNotEquals(backmost.id, defaultOrder.last(), "precondition: target is not already frontmost")

		// Raise that drawable's keyform draw order on the shared graph, then re-run the full pipeline.
		val artMeshes = elements((root.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()
		val target = artMeshes.firstOrNull { (it.id as? Id)?.idstr == backmost.id.raw } ?: return
		elements(target.keyforms).filterIsInstance<CArtMeshForm>().forEach { it.drawOrder = 999 }

		val puppet = Cmo3Import.fromModelSource(root)
		val geometry = CpuDeformationEvaluator().evaluate(puppet, emptyMap())
		val order = paintOrder(puppet.drawables.map { it.id }, geometry.drawOrder)
		assertEquals(backmost.id, order.last(), "raised draw order should paint frontmost")
	}
}
