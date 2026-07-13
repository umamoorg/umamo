package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-identical round-trip + typed-accessor checks against the real `.moc3` samples (resolved
 * from the `moc3.samples` system property). Skips gracefully when the samples are absent, so a
 * public checkout still passes.
 */
class MocRoundTripTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	@Test
	fun samplesRoundTripByteIdentical() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping moc3 round-trip test")
			return
		}
		for (file in files) {
			val bytes = file.readBytes()
			val model = MocCodec.read(bytes)

			assertTrue(MocCodec.isMoc3(bytes), "${file.name}: MOC3 magic")
			assertTrue(model.versionByte in 1..6, "${file.name}: version byte ${model.versionByte}")
			assertTrue(!model.isBigEndian, "${file.name}: little-endian")
			assertEquals(0, model.offsets[0] % 64, "${file.name}: first section is 64-byte aligned")
			assertEquals(0, bytes.size % 64, "${file.name}: file is 64-byte aligned")

			assertContentEquals(bytes, MocCodec.write(model), "${file.name}: byte-identical round-trip")
			println("${file.name}: v${model.versionByte} sections=${model.sectionCount} params=${model.parameterCount} parts=${model.partCount} drawables=${model.drawableCount}")
		}
	}

	@Test
	fun typedAccessorsAreConsistent() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping moc3 typed-accessor test")
			return
		}
		for (file in files) {
			val model = MocCodec.read(file.readBytes())

			val canvas = model.canvasInfo
			assertTrue(canvas != null && canvas.pixelsPerUnit > 0f, "${file.name}: canvas ppu")

			val parameters = model.parameters()
			assertEquals(model.parameterCount, parameters.size, "${file.name}: parameter count")
			for (parameter in parameters) {
				assertTrue(parameter.id.isNotEmpty(), "${file.name}: parameter id present")
				assertTrue(
					parameter.minimumValue <= parameter.defaultValue && parameter.defaultValue <= parameter.maximumValue,
					"${file.name}: ${parameter.id} min<=def<=max",
				)
			}

			assertEquals(model.partCount, model.parts().size, "${file.name}: part count")
			model.parts().forEach { assertTrue(it.id.isNotEmpty(), "${file.name}: part id present") }

			val drawables = model.drawables()
			assertEquals(model.drawableCount, drawables.size, "${file.name}: drawable count")
			for (drawable in drawables) {
				assertTrue(drawable.id.isNotEmpty(), "${file.name}: drawable id present")
				assertTrue(drawable.vertexCount > 0, "${file.name}: ${drawable.id} has vertices")
				assertEquals(0, drawable.indexCount % 3, "${file.name}: ${drawable.id} index count is whole triangles")
				assertTrue(drawable.textureIndex >= 0, "${file.name}: ${drawable.id} texture index")
			}
		}
	}
}
