package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layer-1 typed-section decode: every present section re-encodes to its exact on-disk element
 * region (lossless decode), plus structural invariants that tie sections to CountInfo. Skips
 * gracefully without samples.
 */
class MocSectionsTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	@Test
	fun everyPresentSectionDecodesLosslessly() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping section decode test")
			return
		}
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			var checked = 0
			for (section in Section.entries) {
				if (!model.sections.isPresent(section)) {
					continue
				}
				assertContentEquals(
					model.sections.rawElementRegion(section),
					model.sections.reencodeElementRegion(section),
					"${file.name}: $section lossless typed decode",
				)
				checked++
			}
			println("${file.name}: v${model.versionByte} decoded $checked/${Section.entries.size} sections losslessly")
		}
	}

	@Test
	fun structuralInvariants() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping structural test")
			return
		}
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val sections = model.sections
			val warps = model.countInfo[Sections.CI_WARPS]
			val rotations = model.countInfo[Sections.CI_ROTATIONS]
			val deformers = model.countInfo[Sections.CI_DEFORMERS]

			// Deformer type column splits into the CountInfo warp/rotation counts.
			val types = sections.intArray(Section.DEFORMER_TYPE)
			assertEquals(deformers, types.size, "${file.name}: deformer type count")
			assertEquals(warps, types.count { it == 0 }, "${file.name}: warp count")
			assertEquals(rotations, types.count { it == 1 }, "${file.name}: rotation count")

			// Parallel per-object arrays are sized by their CountInfo counts.
			assertEquals(warps, sections.intArray(Section.WARP_ROWS).size, "${file.name}: warp rows")
			assertEquals(warps, sections.intArray(Section.WARP_COLUMNS).size, "${file.name}: warp cols")
			assertEquals(
				rotations,
				sections.floatArray(Section.ROTATION_BASE_ANGLE).size,
				"${file.name}: rotation base angle",
			)
			assertEquals(
				model.drawableCount,
				sections.intArray(Section.ARTMESH_PARENT_DEFORMER).size,
				"${file.name}: artmesh parent deformer",
			)
			assertEquals(
				model.partCount,
				sections.intArray(Section.PART_KEYFORM_BINDING).size,
				"${file.name}: part keyform binding",
			)
			assertEquals(
				model.parameterCount,
				sections.intArray(Section.PARAMETER_BINDING_COUNT).size,
				"${file.name}: parameter binding count",
			)

			// Deformer parents are -1 (root) or a valid deformer index.
			val parents = sections.intArray(Section.DEFORMER_PARENT)
			assertTrue(parents.all { it == -1 || it in 0 until deformers }, "${file.name}: deformer parent range")

			// Art-mesh keyform position base is a non-decreasing cumulative table.
			val posBase = sections.intArray(Section.ARTMESH_KEYFORM_BASE)
			assertTrue(
				(1 until posBase.size).all { posBase[it - 1] <= posBase[it] },
				"${file.name}: keyform base monotonic",
			)

			// Version gating: color-base sections are v4+ only.  Per-mesh, so a mesh-less model
			// (the offscreen extraction family) has an empty region even on v6 - the invariant is
			// v4+ AND at least one drawable.
			val v4plus = model.versionByte >= MocVersion.V42.byteValue
			assertEquals(
				v4plus && model.drawableCount > 0,
				sections.isPresent(Section.ARTMESH_COLOR_BASE),
				"${file.name}: color base present iff v4+ with drawables",
			)
		}
	}
}
