package org.umamo.render.eval

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compaction gate that needs NO external oracle: import every corpus model twice - once with the
 * post-import channel compaction on, once off - and assert the two evaluate identically over a pose sweep.
 *
 * This is the cheap check that runs wherever the corpus is present, and it is the one that would actually
 * catch a compaction fault: the C-core oracles compare Umamo against an independent implementation at a
 * single pose, while this compares Umamo against ITSELF across many poses, with the un-compacted import as
 * the reference.  A dropped key that mattered, or a constant lifted onto the wrong static, shows up here.
 *
 * The comparison is to a tolerance, and the reason is worth stating: where a track collapses, compaction
 * is the MORE accurate side.  The reference path evaluates a constant channel as `Σ wᵢ·x` over up to 16
 * corners, which lands an ULP off (a corpus draw order of 900 sums to 899.99994); the compacted path reads
 * the authored 900 from a field.  Asserting equality would therefore be asserting that compaction
 * reproduces the old arithmetic's noise, which is backwards.
 *
 * The tolerance is set far below any granularity that could change behaviour - draw order only orders
 * drawables a whole unit apart, and opacity is displayed to two decimals - so it admits the ULP while
 * still failing on a dropped key that mattered or a constant lifted onto the wrong static.
 */
class CompactionEvalEquivalenceTest {
	private companion object {
		/** Poses swept per parameter, as fractions across its min..max range. */
		val SWEEP_FRACTIONS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

		/** Geometry is never compacted, so it should be bit-identical; this absorbs nothing in practice. */
		const val GEOMETRY_TOLERANCE = 1e-6f

		/** Admits the constant-fold ULP (see the class note) while failing on any real channel change. */
		const val SCALAR_TOLERANCE = 1e-3f
	}

	private fun probeFiles(): List<File> =
		System.getProperty("cmo3.probe")
			?.split(',')
			?.map(::File)
			?.filter { it.isFile }
			?: emptyList()

	private fun importCmo3(file: File, compactChannels: Boolean): PuppetModel {
		val root = Cmo3.read(file).root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		return Cmo3Import.fromModelSource(root, compactChannels = compactChannels)
	}

	/**
	 * Poses that sweep each parameter across its range one at a time, plus the all-default pose.
	 *
	 * One parameter at a time rather than a combinatorial sweep: the grids are multilinear, so a fault on
	 * one axis shows up while the others sit at their defaults, and the corpus models carry enough
	 * parameters that a product sweep would not finish.
	 */
	private fun sweepPoses(model: PuppetModel): List<Map<ParameterId, Float>> {
		val defaults = model.parameters.associate { parameter -> parameter.id to parameter.default }
		val poses = mutableListOf(defaults)
		for (parameter in model.parameters) {
			for (fraction in SWEEP_FRACTIONS) {
				poses.add(defaults + (parameter.id to (parameter.min + (parameter.max - parameter.min) * fraction)))
			}
		}
		return poses
	}

	@Test
	fun compactionDoesNotChangeEvaluation() {
		val files = probeFiles()
		if (files.isEmpty()) {
			println("cmo3.probe not present; skipping compaction equivalence")
			return
		}
		val evaluator = CpuDeformationEvaluator()
		var comparedPoses = 0
		var totalTracksBefore = 0
		var totalTracksAfter = 0
		for (file in files) {
			val reference = importCmo3(file, compactChannels = false)
			val compacted = importCmo3(file, compactChannels = true)
			totalTracksBefore += reference.channelTrackCount()
			totalTracksAfter += compacted.channelTrackCount()

			for (pose in sweepPoses(reference)) {
				val referenceGeometry = evaluator.evaluate(reference, pose)
				val compactedGeometry = evaluator.evaluate(compacted, pose)
				assertEquals(
					referenceGeometry.worldPositions.keys,
					compactedGeometry.worldPositions.keys,
					"${file.name}: the same drawables must be visible",
				)
				for ((drawableId, referencePositions) in referenceGeometry.worldPositions) {
					val compactedPositions = compactedGeometry.worldPositions.getValue(drawableId)
					assertEquals(referencePositions.size, compactedPositions.size, "${file.name}: ${drawableId.raw} vertex count")
					for (coordIndex in referencePositions.indices) {
						assertTrue(
							abs(referencePositions[coordIndex] - compactedPositions[coordIndex]) <= GEOMETRY_TOLERANCE,
							"${file.name}: ${drawableId.raw}[$coordIndex] geometry moved under compaction",
						)
					}
					val referenceDrawOrder = referenceGeometry.drawOrder.getValue(drawableId)
					val compactedDrawOrder = compactedGeometry.drawOrder.getValue(drawableId)
					assertTrue(
						abs(referenceDrawOrder - compactedDrawOrder) <= SCALAR_TOLERANCE,
						"${file.name}: ${drawableId.raw} draw order $referenceDrawOrder -> $compactedDrawOrder",
					)
					val referenceOpacity = referenceGeometry.opacity.getValue(drawableId)
					val compactedOpacity = compactedGeometry.opacity.getValue(drawableId)
					assertTrue(
						abs(referenceOpacity - compactedOpacity) <= SCALAR_TOLERANCE,
						"${file.name}: ${drawableId.raw} opacity $referenceOpacity -> $compactedOpacity",
					)
				}
				comparedPoses++
			}
		}
		println("[Umamo][compaction] $comparedPoses poses over ${files.size} models; channel tracks $totalTracksBefore -> $totalTracksAfter")
		assertTrue(comparedPoses > 0, "compared at least one pose")
		assertTrue(
			totalTracksAfter <= totalTracksBefore,
			"compaction must never ADD tracks (before=$totalTracksBefore after=$totalTracksAfter)",
		)
	}

	/** Every channel track carried by every owner in the model - the thing compaction is meant to shrink. */
	private fun PuppetModel.channelTrackCount(): Int =
		drawables.sumOf { it.channelGrids.gridsByChannel.size } +
			deformers.sumOf { it.channelGrids.gridsByChannel.size } +
			parts.sumOf { it.channelGrids.gridsByChannel.size } +
			glues.sumOf { it.channelGrids.gridsByChannel.size }
}
