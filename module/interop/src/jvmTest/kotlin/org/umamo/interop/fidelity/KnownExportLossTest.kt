package org.umamo.interop.fidelity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The allow-list excuses a known loss only on its own gates, and fails a gate whose entry went stale. */
class KnownExportLossTest {
	private val hiddenDeformerShownVisible =
		MocDivergence(MocObjectKind.WARP, "WarpA", DivergenceAspect.Attribute(MocAttribute.VISIBLE, false, true))

	private val glueRenamed =
		MocDivergence(MocObjectKind.GLUE, "MeshA+MeshB", DivergenceAspect.Attribute(MocAttribute.ID, "Glue__MeshA__MeshB", "Glue_0_1_"))

	/**
	 * One divergence of every known loss the gate is listed for, so the stale check has nothing to say.
	 *
	 * @param FidelityGate gate The gate to cover.
	 * @return List A divergence matching each of the gate's entries.
	 */
	private fun oneOfEach(gate: FidelityGate): List<MocDivergence> {
		val samples =
			listOf(
				hiddenDeformerShownVisible,
				MocDivergence(MocObjectKind.BLEND_SHAPE, "PART:PartA:ParamA", DivergenceAspect.Missing),
				MocDivergence(MocObjectKind.CANVAS, "canvas", DivergenceAspect.Attribute(MocAttribute.PIXELS_PER_UNIT, 5000f, 9000f)),
				glueRenamed,
				MocDivergence(MocObjectKind.BLEND_SHAPE, "ART_MESH:MeshA:ParamA", DivergenceAspect.ChannelGained(MocChannel.MULTIPLY_COLOR_DELTA, true)),
				MocDivergence(MocObjectKind.ART_MESH, "MeshA", DivergenceAspect.Attribute(MocAttribute.UVS, floatArrayOf(0f), floatArrayOf(1f))),
			)

		return samples.filter { divergence -> KnownExportLoss.entries.any { entry -> gate in entry.gates && entry.matches(divergence) } }
	}

	@Test
	fun everyEntryHasASampleHere() {
		for (entry in KnownExportLoss.entries) {
			val covered = FidelityGate.entries.any { gate -> oneOfEach(gate).any { divergence -> entry.matches(divergence) } }
			assertTrue(covered, "no sample divergence in this test matches ${entry.name}")
		}
	}

	@Test
	fun aKnownLossOnItsOwnGatePasses() {
		for (gate in FidelityGate.entries) {
			val ledger = FidelityLedger(gate)
			ledger.record("rig", oneOfEach(gate))
			assertEquals(emptyList(), ledger.problems(), "on ${gate.label}")
		}
	}

	@Test
	fun anUnknownDivergenceFails() {
		val ledger = FidelityLedger(FidelityGate.MOC3_ROUND_TRIP)
		val dropped = MocDivergence(MocObjectKind.ART_MESH, "MeshA", DivergenceAspect.Missing)
		ledger.record("rig", oneOfEach(FidelityGate.MOC3_ROUND_TRIP) + dropped)
		val problems = ledger.problems()
		assertEquals(1, problems.size, "problems: $problems")
		assertTrue(problems.single().contains("ART_MESH MeshA: missing"), problems.single())
	}

	@Test
	fun aKnownLossOnAnotherGateFails() {
		val ledger = FidelityLedger(FidelityGate.MOC3_ROUND_TRIP)
		ledger.record("rig", oneOfEach(FidelityGate.MOC3_ROUND_TRIP) + hiddenDeformerShownVisible)
		val problems = ledger.problems()
		assertEquals(1, problems.size, "problems: $problems")
		assertTrue(problems.single().contains(KnownExportLoss.DEFORMER_VISIBILITY.name), problems.single())
	}

	@Test
	fun aDeformerHiddenByTheExportIsNotTheKnownLoss() {
		val hiddenByTheExport = MocDivergence(MocObjectKind.ROTATION, "RotationA", DivergenceAspect.Attribute(MocAttribute.VISIBLE, true, false))
		assertTrue(!KnownExportLoss.DEFORMER_VISIBILITY.matches(hiddenByTheExport))
	}

	@Test
	fun anEntryNoRigShowsAnyMoreFails() {
		val ledger = FidelityLedger(FidelityGate.CMO3_TO_MOC3_VS_EDITOR)
		ledger.record("rig", oneOfEach(FidelityGate.CMO3_TO_MOC3_VS_EDITOR).filterNot { divergence -> divergence == glueRenamed })
		val problems = ledger.problems()
		assertEquals(1, problems.size, "problems: $problems")
		assertTrue(problems.single().contains(KnownExportLoss.GLUE_IDS.name), problems.single())
	}

	@Test
	fun aRunThatRecordedNoRigJudgesNoEntry() {
		assertEquals(emptyList(), FidelityLedger(FidelityGate.MOC3_CMO3_MOC3_LOOP).problems())
	}
}