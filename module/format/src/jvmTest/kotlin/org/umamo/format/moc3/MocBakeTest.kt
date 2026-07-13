package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.moc.MocCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Serializer (bake-framing) self-consistency: re-frame each decoded model with [MocEncoder] and
 * confirm a re-decode yields the identical semantic model. This validates the offset table / section
 * ordering / alignment independent of the semantic lowering. Baked files are also written to `work/`
 * for a manual runtime check. Skips gracefully without samples.
 */
class MocBakeTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	@Test
	fun repackRoundTripsSemanticModel() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping bake test")
			return
		}
		val workDir = samplesDir!!.parentFile.resolve("work").apply { mkdirs() }
		for (file in files) {
			val original = MocCodec.read(file.readBytes())
			val baked = MocEncoder.repack(original)

			// The repack is a valid container our codec reads back to the same semantic model.
			val before = Moc3.decode(original)
			val after = Moc3.decode(MocCodec.read(baked))
			assertEquals(before.version, after.version, "${file.name}: version")
			assertEquals(before.parameters, after.parameters, "${file.name}: parameters")
			assertEquals(before.parts, after.parts, "${file.name}: parts")
			assertEquals(before.deformers, after.deformers, "${file.name}: deformers")
			assertEquals(before.artMeshes, after.artMeshes, "${file.name}: art meshes")
			assertEquals(before.glues, after.glues, "${file.name}: glues")
			assertEquals(before.renderOrderGroups, after.renderOrderGroups, "${file.name}: render-order groups")
			assertEquals(before.blendShapes, after.blendShapes, "${file.name}: blend shapes")
			assertEquals(before.offscreens, after.offscreens, "${file.name}: offscreens")
			assertEquals(0, baked.size % 64, "${file.name}: baked file 64-aligned")

			workDir.resolve("baked-${file.name}").writeBytes(baked)

			// Full bake: synthesize sections from the (editable) document, carry the rest. Re-decode
			// must reproduce the same semantic model, and the synthesized sections come from `before`.
			val viaDocument = Moc3.bake(original, before)
			val rebaked = Moc3.decode(MocCodec.read(viaDocument))
			assertEquals(before.parameters, rebaked.parameters, "${file.name}: bake parameters")
			assertEquals(before.artMeshes, rebaked.artMeshes, "${file.name}: bake art meshes")
			assertEquals(before.deformers, rebaked.deformers, "${file.name}: bake deformers")
			assertEquals(before.parts, rebaked.parts, "${file.name}: bake parts")
			assertEquals(before.glues, rebaked.glues, "${file.name}: bake glues")
			assertEquals(before.blendShapes, rebaked.blendShapes, "${file.name}: bake blend shapes")
			assertEquals(before.offscreens, rebaked.offscreens, "${file.name}: bake offscreens")
			workDir.resolve("docbaked-${file.name}").writeBytes(viaDocument)
			println("${file.name}: repack ${file.length()} -> ${baked.size}; doc-bake -> ${viaDocument.size} bytes, semantic model preserved")
		}
	}
}
