package org.umamo.interop.fidelity

import org.umamo.interop.PartField
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every property of the model's classes is placed against the CMO3 export's diff, and the diff agrees with every
 * placement: a property it compares is reported when edited, and one it skips is not.
 *
 * Adding a property to any covered class fails here until it is placed.  A diff that starts comparing a skipped
 * property fails here too, until its entry moves to Compared.
 */
class ModelDiffCoverageTest {
	private val fixture = CoverageFixture.model()

	/**
	 * Fails with every problem at once, one per line.
	 *
	 * @param List problems The problems found.
	 */
	private fun assertNone(problems: List<String>) {
		if (problems.isNotEmpty()) {
			fail(problems.joinToString(separator = "\n", prefix = "${problems.size} coverage problems:\n"))
		}
	}

	@Test
	fun everyPropertyIsPlacedOnce() {
		assertNone(ModelDiffCoverageTables.all.flatMap { coverage -> coverage.placementProblems() })
	}

	@Test
	fun everyEditChangesItsOwnPropertyAlone() {
		assertNone(ModelDiffCoverageTables.all.flatMap { coverage -> coverage.mutationProblems(fixture) })
	}

	@Test
	fun theDiffAgreesWithEveryPlacement() {
		assertNone(ModelDiffCoverageTables.all.flatMap { coverage -> coverage.diffProblems(fixture) })
	}

	/** Without this, an edit could be reported for a difference the fixture's own rebuild introduces. */
	@Test
	fun twoBuildsOfTheFixtureDiffToNothing() {
		assertTrue(diffPuppetModels(fixture, CoverageFixture.model()).isEmpty)
	}

	@Test
	fun everyDeformerAndParameterNodeKindHasATable() {
		val deformerKinds = fixture.deformers.map { deformer -> ModelDiffCoverageTables.forDeformer(deformer) }
		val nodeKinds = fixture.parameterTree.flatMap { node -> listOf(node) + ((node as? ParameterNode.Group)?.children ?: emptyList()) }

		assertEquals(setOf(ModelDiffCoverageTables.warp, ModelDiffCoverageTables.rotation), deformerKinds.toSet())
		assertEquals(setOf(ModelDiffCoverageTables.parameterGroup, ModelDiffCoverageTables.parameterLeaf), nodeKinds.map { node -> ModelDiffCoverageTables.forParameterNode(node) }.toSet())
		assertTrue(ModelDiffCoverageTables.all.containsAll(deformerKinds))
	}

	/** The checks are not vacuous: a property left out and a compared one filed as dropped are both reported. */
	@Test
	fun theGateReportsAnUnplacedPropertyAndAStalePlacement() {
		val misfiled =
			ModelDiffCoverageTables.part.entries
				.filter { entry -> entry.property != "name" }
				.map { entry ->
					if (entry.property == "isVisible") {
						PropertyCoverage(entry.property, DiffPlacement.NotCarried("misfiled on purpose", null), entry.mutate)
					} else {
						entry
					}
				}
		val broken = ClassCoverage(Part::class.java, CoverageFixture.partRoute, misfiled)
		val placement = broken.placementProblems()
		val diff = broken.diffProblems(fixture)

		assertEquals(1, placement.size, "$placement")
		assertTrue(placement.single().startsWith("Part.name is in no coverage table"), placement.single())
		assertEquals(1, diff.size, "$diff")
		assertTrue(diff.single().startsWith("Part.isVisible is placed NotCarried") && "PartField.${PartField.VISIBLE.name}" in diff.single(), diff.single())
	}
}