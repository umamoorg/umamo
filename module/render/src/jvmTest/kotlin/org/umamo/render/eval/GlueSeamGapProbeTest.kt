package org.umamo.render.eval

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.ContentBounds
import org.umamo.render.ViewportCamera
import org.umamo.render.glsl.MAX_GLUES
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test

/** The output prefix every row carries, so the rows grep out of a verbose test log. */
private const val PROBE_TAG = "[glue-seam-gap]"

/** The viewport the pixel rows are measured in: the perf probes' area. */
private const val VIEW_WIDTH = 1600

/** The viewport height the pixel rows are measured in. */
private const val VIEW_HEIGHT = 900

/** The Edit-mode vertex dot radius in pixels, the threshold past which a seam dot visibly misses its art. */
private const val VERTEX_DOT_RADIUS_PX = 3.5

/**
 * Print-only probe for decision D15 in docs/plan/edit-mode-performance.md: how far the art's weld moves a
 * glued vertex at the rest pose Edit mode is pinned to.  The Edit overlay draws a glue mesh's vertices at
 * their pre-weld positions (the cage the picks and the transforms read) while the art draws welded, so
 * this displacement is exactly how far a seam dot sits from the seam it belongs to.  For every corpus rig
 * that carries glue it prints the displacement's max and percentiles in world units and in pixels at a
 * 1600x900 fit and at four times that zoom, how many glued vertices miss by more than a pixel and by
 * more than a vertex dot's radius, and the glue that moves its vertices furthest; with the gap a pair
 * keeps after welding, which an intensity below one leaves open.
 *
 * Pins nothing.  The CPU weld runs the pairs in order, where the shader welds each vertex toward one
 * partner; the two agree for a vertex in a single pair, which is the corpus's case.  Skips rigs without
 * glue and the whole run without the corpus.  Standard streams are off in the build, so the rows show
 * with --info or in build/test-results.
 */
class GlueSeamGapProbeTest {
	@Test
	fun probeRestPoseWeldDisplacement() {
		val rigs = corpusRigs()
		if (rigs.isEmpty()) {
			println("$PROBE_TAG no corpus rigs (cmo3.probe, moc3.samples); skipping")
			return
		}
		for (rig in rigs) {
			val model =
				try {
					rig.load()
				} catch (failure: Exception) {
					report("${rig.name}: could not load (${failure.message}); skipped")
					continue
				}
			if (model.glues.isEmpty()) {
				report("${rig.name}: no glue")
				continue
			}
			measure(rig.name, model)
		}
	}

	/**
	 * Prints one rig's rows.
	 *
	 * @param String rigName The rig's name.
	 * @param PuppetModel model The rig, its glues included.
	 */
	private fun measure(rigName: String, model: PuppetModel) {
		val unwelded = model.copy(glues = emptyList())
		val before = applyCpuDeform(unwelded, preparePose(unwelded, emptyMap())).worldPositions
		val after = applyCpuDeform(model, preparePose(model, emptyMap())).worldPositions
		val zoom = fitZoom(after)

		val displacementByVertex = HashMap<Pair<DrawableId, Int>, Double>()
		val leftoverGaps = ArrayList<Double>()
		var worstGlue = ""
		var worstDisplacement = 0.0
		for (glue in model.glues) {
			val beforeA = before[glue.meshA] ?: continue
			val beforeB = before[glue.meshB] ?: continue
			val afterA = after[glue.meshA] ?: continue
			val afterB = after[glue.meshB] ?: continue
			var glueWorst = 0.0
			for (pair in glue.pairs) {
				val movedA = distance(beforeA, afterA, pair.indexA, pair.indexA)
				val movedB = distance(beforeB, afterB, pair.indexB, pair.indexB)
				displacementByVertex[glue.meshA to pair.indexA] = movedA
				displacementByVertex[glue.meshB to pair.indexB] = movedB
				leftoverGaps.add(distance(afterA, afterB, pair.indexA, pair.indexB))
				glueWorst = maxOf(glueWorst, movedA, movedB)
			}
			if (glueWorst > worstDisplacement) {
				worstDisplacement = glueWorst
				worstGlue = glue.id ?: "${glue.meshA.raw}/${glue.meshB.raw}"
			}
		}
		val displacements = displacementByVertex.values.sorted()
		if (displacements.isEmpty()) {
			report("$rigName: ${model.glues.size} glues, none posed at rest")
			return
		}
		val pastShaderLimit = (model.glues.size - MAX_GLUES).coerceAtLeast(0)
		report("$rigName: glues=${model.glues.size} (past the shader's $MAX_GLUES: $pastShaderLimit) gluedVertices=${displacements.size} fitZoom=${"%.4f".format(zoom)} px/unit")
		report("$rigName:   weld displacement, world  ${percentileRow(displacements, 1.0)}")
		report("$rigName:   weld displacement, px@fit ${percentileRow(displacements, zoom)}")
		report("$rigName:   weld displacement, px@4x  ${percentileRow(displacements, zoom * 4)}")
		report(
			"$rigName:   at fit: over 1 px ${displacements.count { moved -> moved * zoom > 1.0 }}, " +
				"over the ${VERTEX_DOT_RADIUS_PX} px dot ${displacements.count { moved -> moved * zoom > VERTEX_DOT_RADIUS_PX }} " +
				"of ${displacements.size}; worst glue $worstGlue at ${"%.2f".format(worstDisplacement * zoom)} px",
		)
		report("$rigName:   leftover pair gap after the weld, px@fit ${percentileRow(leftoverGaps.sorted(), zoom)}")
	}

	/**
	 * One percentile row: max, p50, p90, p99 of sorted values, scaled.
	 *
	 * @param List<Double> sorted The values, ascending.
	 * @param Double scale What to multiply each by.
	 * @return String The row.
	 */
	private fun percentileRow(sorted: List<Double>, scale: Double): String {
		/**
		 * One percentile of the sorted values, scaled.
		 *
		 * @param Double fraction The percentile as a fraction.
		 * @return String The formatted value.
		 */
		fun at(fraction: Double): String = "%.3f".format(sorted[((sorted.size - 1) * fraction).toInt()] * scale)
		return "max ${at(1.0)}  p50 ${at(0.5)}  p90 ${at(0.9)}  p99 ${at(0.99)}"
	}

	/**
	 * The distance between one vertex of each of two position arrays.
	 *
	 * @param FloatArray first The first array, interleaved x and y.
	 * @param FloatArray second The second array.
	 * @param Int firstIndex The vertex in the first.
	 * @param Int secondIndex The vertex in the second.
	 * @return Double The distance.
	 */
	private fun distance(first: FloatArray, second: FloatArray, firstIndex: Int, secondIndex: Int): Double =
		hypot(
			(first[firstIndex * 2] - second[secondIndex * 2]).toDouble(),
			(first[firstIndex * 2 + 1] - second[secondIndex * 2 + 1]).toDouble(),
		)

	/**
	 * The zoom that fits every posed vertex into the probe's viewport: pixels per world unit.
	 *
	 * @param Map<DrawableId, FloatArray> positions The posed positions.
	 * @return Double The zoom.
	 */
	private fun fitZoom(positions: Map<DrawableId, FloatArray>): Double {
		var minX = Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		for (array in positions.values) {
			var slot = 0
			while (slot + 1 < array.size) {
				minX = minOf(minX, array[slot])
				maxX = maxOf(maxX, array[slot])
				minY = minOf(minY, array[slot + 1])
				maxY = maxOf(maxY, array[slot + 1])
				slot += 2
			}
		}
		return ViewportCamera.fit(ContentBounds(minX, minY, maxX - minX, maxY - minY), VIEW_WIDTH, VIEW_HEIGHT).zoom.toDouble()
	}

	/**
	 * One corpus rig and how to load it.
	 *
	 * @property String name The rig's file name.
	 * @property Function load Reads it into a model.
	 */
	private class CorpusRig(val name: String, val load: () -> PuppetModel)

	/**
	 * Every CMO3 named by cmo3.probe and every MOC3 under moc3.samples.
	 *
	 * @return List<CorpusRig> The rigs, CMO3 first.
	 */
	private fun corpusRigs(): List<CorpusRig> {
		val cmo3Rigs =
			System
				.getProperty("cmo3.probe")
				?.split(",")
				?.map(::File)
				?.filter { file -> file.isFile }
				.orEmpty()
				.map { file ->
					CorpusRig(file.name) {
						val root = Cmo3.read(file).root as? CModelSource ?: error("not a model source")
						Cmo3Import.fromModelSource(root)
					}
				}
		val moc3Rigs =
			System
				.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { directory -> directory.isDirectory }
				?.walkTopDown()
				?.filter { file -> file.isFile && file.extension == "moc3" }
				?.sortedBy { file -> file.name }
				?.toList()
				.orEmpty()
				.map { file -> CorpusRig(file.name) { restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.read(file.readBytes()), null)) } }
		return cmo3Rigs + moc3Rigs
	}

	/**
	 * Prints one tagged line.
	 *
	 * @param String line The line.
	 */
	private fun report(line: String) {
		println("$PROBE_TAG $line")
	}
}