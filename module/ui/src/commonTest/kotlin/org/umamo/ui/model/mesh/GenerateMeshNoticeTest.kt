package org.umamo.ui.model.mesh

import org.umamo.interop.art.mesh.MeshFit
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which notice a generation reports: the skips a rigger can act on lead, then drawables glue holds
 * throughout, then any other skip, and a run that needed a raised budget or filled holes says so.
 */
class GenerateMeshNoticeTest {
	@Test
	fun drawablesGlueHoldsThroughoutAreNamedAfterTheActionableSkips() {
		val glued = DrawableId("glued")
		val over = DrawableId("over")
		val missing = DrawableId("missing")

		assertEquals(
			GenerateMeshNotice("notice.mesh.gluedThroughout", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout))),
		)
		assertEquals(
			GenerateMeshNotice("notice.mesh.overBudget", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout, over to GenerateMeshSkipReason.OverBudget))),
		)
		assertEquals(
			GenerateMeshNotice("notice.mesh.gluedThroughout", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout, missing to GenerateMeshSkipReason.NoRaster))),
		)
	}

	@Test
	fun meshesThatNeededARaisedBudgetOrFilledHolesAreNamed() {
		val webbed = DrawableId("webbed")
		val empty = PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = emptyList(), drawables = emptyList(), rootChildren = emptyList(), rootPartId = null)
		val refit = GenerateMeshOutcome.Generated(empty, null, listOf(webbed), emptyMap(), emptySet(), emptyMap(), mapOf(webbed to MeshFit(budget = null, holesFilled = true)))

		assertEquals(GenerateMeshNotice("notice.mesh.generatedRefit", listOf("1", "1")), generateMeshNoticeOf(refit))
	}
}