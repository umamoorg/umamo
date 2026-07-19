package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.moc.MocCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * Print-only probe over the baked ModelWithOffscreen family: pairs each file's MOC3 offscreen
 * scalar sections (152-163) with the CMO3-authored enum sweep to derive the blend-mode int
 * mapping, and dumps the sections the lowering currently misses on these mesh-less models
 * (83-85 render-order aggregates, CountInfo, color-table presence).  Feeds
 * docs/plan/offscreen-support.md; pins nothing.
 */
class OffscreenSweepProbeTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun intsOf(bytes: ByteArray?): List<Int> {
		if (bytes == null) {
			return emptyList()
		}
		val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		return (0 until bytes.size / 4).map { buffer.getInt(it * 4) }
	}

	@Test
	fun probeOffscreenSweep() {
		val dir =
			samplesDir ?: run {
				println("moc3.samples not present; skipping")
				return
			}
		// modelA rides along as the organic 24-offscreen cross-check for the section-role hypotheses
		// (160 == 152, 153 sizing) derived from the authored extraction family.
		val files =
			dir.walkTopDown()
				.filter {
					it.isFile &&
						it.extension == "moc3" &&
						(
							it.name.startsWith("ModelWithOffscreen") ||
								it.name.startsWith("MultiplyScreenColors") ||
								it.name == "modelA.moc3"
						)
				}
				.sortedBy { it.name }
				.toList()
		if (files.isEmpty()) {
			println("no ModelWithOffscreen moc3 samples; skipping")
			return
		}
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val doc = Moc3.decode(model)
			println("=== ${file.name} v${model.versionByte} ===")
			println("  parts=${doc.parts.size} meshes=${doc.artMeshes.size} offscreens=${doc.offscreens.size}")
			for ((offscreenIndex, offscreen) in doc.offscreens.withIndex()) {
				val ownerPart = doc.parts.getOrNull(offscreen.ownerPartIndex)
				println(
					"  offscreen[$offscreenIndex] owner=${ownerPart?.id} blendMode=${offscreen.blendMode}" +
						" constantFlags=0b${offscreen.constantFlags.toString(2).padStart(8, '0')}" +
						" maskCount=${offscreen.maskCount} maskIndices=${offscreen.maskIndices.toList()}" +
						" keyforms=${offscreen.keyforms.map { "op=${it.opacity} mul=${it.multiplyColor} scr=${it.screenColor}" }}",
				)
			}
			// Raw v6 offscreen scalar sections, incl. the carried unknowns 153/154/160.
			for (sectionIndex in 152..163) {
				val raw = intsOf(model.section(sectionIndex))
				if (raw.isNotEmpty()) {
					println("  s$sectionIndex=${raw.take(12)}")
				}
			}
			// The lowering mismatches: real vs synthesized 83/84/85 and CountInfo.
			val auxiliary = MocLowering.auxiliarySections(doc)
			for (sectionIndex in 83..85) {
				println("  s$sectionIndex real=${intsOf(model.section(sectionIndex))} ours=${intsOf(auxiliary[sectionIndex])}")
			}
			val realCount = intsOf(model.section(0))
			val ourCount = intsOf(MocLowering.countInfoSection(doc))
			val diffs =
				realCount.indices.filter { fieldIndex ->
					fieldIndex < ourCount.size && realCount[fieldIndex] != ourCount[fieldIndex]
				}
			println("  CountInfo diffs at fields $diffs real=${diffs.map { realCount[it] }} ours=${diffs.map { ourCount[it] }}")
			println("  countInfo[0..22]=${realCount.take(23)}")
			// Color-table presence (the falsified MocSectionsTest invariant).
			println("  colorSections present: ${(108..113).map { model.section(it)?.size ?: 0 }}")
			// Render-order groups for the 84/85 analysis.
			for ((groupIndex, group) in doc.renderOrderGroups.withIndex()) {
				println("  group[$groupIndex] children=${group.children.map { child -> "k${child.kind}:i${child.index}:g${child.groupIndex}" }}")
			}
			// Drawable-level extraction for the PartClipping bake: constant flags (culling bit) and a
			// full section-size map to locate where a drawable's EXTENDED blend (screen/out) lands -
			// the legacy 2-bit flag field cannot express it.
			if ("PartClipping" in file.name) {
				for ((drawableIndex, mesh) in doc.artMeshes.withIndex()) {
					println(
						"  drawable[$drawableIndex] ${mesh.id} flags=0b${mesh.constantFlags.toString(2).padStart(8, '0')}" +
							" masks=${mesh.maskDrawableIndices.toList()}",
					)
				}
				val sectionSizes =
					(0 until 256).mapNotNull { sectionIndex ->
						model.section(sectionIndex)?.let { sectionIndex to it.size }
					}
				println("  sections: $sectionSizes")
			}
		}
	}
}
