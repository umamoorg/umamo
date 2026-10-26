package org.umamo.ui.model.mesh

import org.umamo.edit.mesh.MeshGenerationKind
import org.umamo.interop.art.mesh.MeshFit
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which notice a generation reports: the skips a rigger can act on lead, then drawables glue holds
 * throughout, then any other skip, and a run that needed a raised budget or filled holes says so.  A
 * quad run names quads and shares the notices that are not about what was generated.
 */
class GenerateMeshNoticeTest {
	@Test
	fun drawablesGlueHoldsThroughoutAreNamedAfterTheActionableSkips() {
		val glued = DrawableId("glued")
		val over = DrawableId("over")
		val missing = DrawableId("missing")

		assertEquals(
			GenerateMeshNotice("notice.mesh.gluedThroughout", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout)), MeshGenerationKind.FromArt),
		)
		assertEquals(
			GenerateMeshNotice("notice.mesh.overBudget", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout, over to GenerateMeshSkipReason.OverBudget)), MeshGenerationKind.FromArt),
		)
		assertEquals(
			GenerateMeshNotice("notice.mesh.gluedThroughout", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout, missing to GenerateMeshSkipReason.NoRaster)), MeshGenerationKind.FromArt),
		)
	}

	@Test
	fun meshesThatNeededARaisedBudgetOrFilledHolesAreNamed() {
		val webbed = DrawableId("webbed")
		val empty = PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = emptyList(), drawables = emptyList(), rootChildren = emptyList(), rootPartId = null)
		val refit = GenerateMeshOutcome.Generated(empty, null, listOf(webbed), emptyMap(), emptySet(), emptyMap(), mapOf(webbed to MeshFit(budget = null, holesFilled = true)))

		assertEquals(GenerateMeshNotice("notice.mesh.generatedRefit", listOf("1", "1")), generateMeshNoticeOf(refit, MeshGenerationKind.FromArt))
	}

	@Test
	fun aQuadRunNamesQuads() {
		val quad = DrawableId("quad")
		val pinned = DrawableId("pinned")
		val missing = DrawableId("missing")
		val glued = DrawableId("glued")
		val faint = DrawableId("faint")
		val one = GenerateMeshOutcome.Generated(emptyModel(), null, listOf(quad), emptyMap(), emptySet(), emptyMap())
		val withSkip = GenerateMeshOutcome.Generated(emptyModel(), null, listOf(quad), mapOf(missing to GenerateMeshSkipReason.NoRaster), emptySet(), emptyMap())
		val withPinned = GenerateMeshOutcome.Generated(emptyModel(), null, listOf(quad), mapOf(pinned to GenerateMeshSkipReason.PinnedWithoutRoom, glued to GenerateMeshSkipReason.GluedThroughout), emptySet(), emptyMap())

		assertEquals(GenerateMeshNotice("notice.mesh.quadGenerated", listOf("1")), generateMeshNoticeOf(one, MeshGenerationKind.Quad))
		assertEquals(GenerateMeshNotice("notice.mesh.generated", listOf("1")), generateMeshNoticeOf(one, MeshGenerationKind.FromArt))
		assertEquals(GenerateMeshNotice("notice.mesh.quadGeneratedWithSkips", listOf("1", "1")), generateMeshNoticeOf(withSkip, MeshGenerationKind.Quad))
		assertEquals(GenerateMeshNotice("notice.mesh.quadPinnedWithoutRoom", listOf("1", "1")), generateMeshNoticeOf(withPinned, MeshGenerationKind.Quad))
		assertEquals(
			GenerateMeshNotice("notice.mesh.gluedThroughout", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout, missing to GenerateMeshSkipReason.NoRaster)), MeshGenerationKind.Quad),
		)
		assertEquals(GenerateMeshNotice("notice.mesh.noneGenerated"), generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(faint to GenerateMeshSkipReason.NothingOpaque)), MeshGenerationKind.Quad))
		assertEquals(GenerateMeshNotice("notice.mesh.packRefused"), generateMeshNoticeOf(GenerateMeshOutcome.PackRefused(emptyList(), emptyMap()), MeshGenerationKind.Quad))
		assertEquals("notice.mesh.quadNoTargets", noTargetsNoticeKeyOf(MeshGenerationKind.Quad))
		assertEquals("notice.mesh.noTargets", noTargetsNoticeKeyOf(MeshGenerationKind.FromArt))
	}

	/**
	 * A model with nothing in it, for outcomes whose model the notice never reads.
	 *
	 * @return PuppetModel The model.
	 */
	private fun emptyModel(): PuppetModel = PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = emptyList(), drawables = emptyList(), rootChildren = emptyList(), rootPartId = null)
}