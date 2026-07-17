package org.umamo.render

import org.umamo.format.moc3.Moc3
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.ingest.Moc3Import
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Default-pose eval sanity over EVERY corpus moc: imports each `.moc3` found beside the
 * `-Dmoc3.sample` model and asserts the evaluated world geometry is finite and lands within a
 * generous multiple of the canvas.
 */
class Moc3EvalSanityTest {
	private val sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun corpusModelsEvaluateWithinCanvasBounds() {
		val corpusRoot = sample?.parentFile?.parentFile?.takeIf { it.isDirectory }
		if (corpusRoot == null) {
			println("moc3.sample not present; skipping eval sanity test")
			return
		}
		val mocFiles =
			corpusRoot.listFiles { candidate -> candidate.isDirectory }.orEmpty()
				.flatMap { modelDirectory -> modelDirectory.listFiles { candidate -> candidate.isFile && candidate.extension == "moc3" }.orEmpty().toList() }
				.sortedBy { it.path }
		assertTrue(mocFiles.isNotEmpty(), "expected corpus .moc3 files under ${corpusRoot.path}")

		for (mocFile in mocFiles) {
			val mocDocument =
				runCatching { Moc3.decode(mocFile.readBytes()) }.getOrElse {
					println("${mocFile.name}: not decodable, skipping")
					continue
				}
			val model = Moc3Import.fromMocDocument(mocDocument, null)
			if (model.canvasWidth <= 0f || model.canvasHeight <= 0f) {
				println("${mocFile.name}: no canvas, skipping bounds check")
				continue
			}
			val geometry = CpuDeformationEvaluator().evaluate(model, emptyMap())
			assertTrue(geometry.worldPositions.isNotEmpty(), "${mocFile.name}: nothing evaluated at the default pose")

			// World space is origin-centered: x spans about [-originX, canvasWidth - originX], y the
			// negated equivalent.  Allow 4x the canvas's larger side in every direction - room for real
			// overhang, far below a ppu-scale (1000x+) unit-seam explosion.
			val margin = 4f * maxOf(model.canvasWidth, model.canvasHeight)
			var checkedValues = 0
			for ((drawableId, positions) in geometry.worldPositions) {
				for (value in positions) {
					assertTrue(value.isFinite(), "${mocFile.name}: non-finite world coordinate on ${drawableId.raw}")
					assertTrue(
						value > -margin && value < margin,
						"${mocFile.name}: world coordinate $value on ${drawableId.raw} is beyond ±$margin - " +
							"a coordinate/scale seam regression (see Moc3Import's unit-seam notes)",
					)
					checkedValues++
				}
			}
			println("${mocFile.name}: ${geometry.worldPositions.size} drawables, $checkedValues coordinates within ±$margin")
		}
	}
}
