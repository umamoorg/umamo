package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import java.io.File
import kotlin.test.Test

/**
 * The BLENDSHAPE_PARAMETER_BEGIN value for binding-less parameters, and the ordering of the color
 * tables' blend delta region (which carries authored nonzero color morphs on Model A/C).
 */
class BlendEncodeProbeTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	@Test
	fun probeParameterBeginAndColorDeltaOrder() {
		val samples =
			samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
				.orEmpty()
				.filter { file -> listOf("modelA", "modelB", "modelC").any { prefix -> file.name.startsWith(prefix) } }
		if (samples.isEmpty()) {
			println("moc3.samples not present; skipping encode probe")
			return
		}
		for (file in samples) {
			val model = MocCodec.read(file.readBytes())
			val document = Moc3.decode(model)
			val sections = model.sections
			val parameterBegin = sections.intArray(Section.BLENDSHAPE_PARAMETER_BEGIN)
			val parameterCount = sections.intArray(Section.BLENDSHAPE_PARAMETER_COUNT)
			println("[encode-probe] ${file.name} 115=${parameterBegin.toList()}")
			println("[encode-probe] ${file.name} 116=${parameterCount.toList()}")

			// Nonzero color delta rows: row index (from the delta region start) -> channel values.
			val multiplyRed = sections.floatArray(Section.COLOR_MULTIPLY_R)
			val screenRed = sections.floatArray(Section.COLOR_SCREEN_R)
			val recordKeyTotalNoParts =
				document.blendShapes.filter { it.target != org.umamo.format.moc3.model.BlendShapeTarget.PART }
					.sumOf { it.keyPositions.size }
			val deltaStart = multiplyRed.size - recordKeyTotalNoParts
			val nonZeroMultiply =
				(deltaStart until multiplyRed.size).filter { rowIndex -> multiplyRed[rowIndex] != 0f }
					.map { rowIndex -> rowIndex - deltaStart }
			val nonZeroScreen =
				(deltaStart until screenRed.size).filter { rowIndex -> screenRed[rowIndex] != 0f }
					.map { rowIndex -> rowIndex - deltaStart }
			println("[encode-probe] ${file.name} deltaStart=$deltaStart nonzeroMultiplyRows=$nonZeroMultiply nonzeroScreenRows=$nonZeroScreen")

			// My assumed order: warp records, mesh records, rotation records (parts excluded); print
			// each record's delta-row span under that assumption with its kind + target + parameter.
			val warpRecords = document.blendShapes.filter { it.target == org.umamo.format.moc3.model.BlendShapeTarget.WARP }
			val meshRecords = document.blendShapes.filter { it.target == org.umamo.format.moc3.model.BlendShapeTarget.ART_MESH }
			val rotationRecords = document.blendShapes.filter { it.target == org.umamo.format.moc3.model.BlendShapeTarget.ROTATION }
			var rowCursor = 0
			for (record in warpRecords + meshRecords + rotationRecords) {
				val span = rowCursor until rowCursor + record.keyPositions.size
				val interesting = (nonZeroMultiply + nonZeroScreen).any { it in span }
				if (interesting) {
					println(
						"[encode-probe] ${file.name} span=$span kind=${record.target} target=${record.targetIndex} " +
							"param=${document.parameters[record.parameterIndex].id} recordBase=${record.recordBase}",
					)
				}
				rowCursor += record.keyPositions.size
			}
		}
	}
}
