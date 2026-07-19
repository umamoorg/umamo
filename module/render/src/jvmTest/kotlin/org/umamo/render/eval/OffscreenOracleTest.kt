package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.moc3.Moc3
import org.umamo.runtime.ingest.Moc3Import
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Posed differential validation of the offscreen channel eval against the Umamo C++ runtime: for
 * each offscreen corpus model, evaluate poses that exercise the keyformed composite channels
 * (opacity at-key, midpoint, and the arm draw-order crossfades; authored multiply/screen colors)
 * and compare [PoseDeformInputs.partOffscreenStates] against `dump_model`'s per-update
 * interpolated offscreen arrays (`O` lines - probed pose-DYNAMIC: the oracle re-evaluates them
 * each csmUpdateModel).  The oracle's colors are RGBA with alpha pinned 1.0 (the runtime model
 * carries RGB only); the mask joins are compared statically.
 *
 * Gated on `relive.dumpModel` + `relive.coreLib` + `moc3.samples`; skips when any is absent.
 */
class OffscreenOracleTest {
	private data class OffscreenCase(val fileName: String, val poses: List<Map<String, Float>>)

	private val cases =
		listOf(
			// Model A: 24 organic offscreens - the Overall hologram fade (1.0 -> 0.85) and the arm
			// draw-order opacity crossfades, at keys and midpoints.
			OffscreenCase(
				"modelA.moc3",
				listOf(
					emptyMap(),
					mapOf("ParamHologram" to 1f),
					mapOf("ParamHologram" to 0.5f),
					mapOf("ParamHandRDrawOrder" to 1f),
					mapOf("ParamHandRDrawOrder" to 0.5f, "ParamHandLDrawOrder" to 0.5f),
				),
			),
			// The authored non-identity multiply/screen colors (#677CD1 / #95C068).
			OffscreenCase("MultiplyScreenColors.moc3", listOf(emptyMap())),
			// Offscreen clip list (part expanded to drawables) + invert flag carrier.
			OffscreenCase("ModelWithOffscreenPartClipping.moc3", listOf(emptyMap())),
		)

	@Test
	fun offscreenChannelsMatchOracle() {
		val dumpModel = requireInput("relive.dumpModel")
		val coreLib = requireInput("relive.coreLib")
		val samplesByName =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { it.isDirectory }
				?.walkTopDown()
				?.filter { it.isFile && it.extension == "moc3" }
				?.associateBy { it.name }
				?: emptyMap()
		Assume.assumeTrue("[oracle] absent -Dmoc3.samples", samplesByName.isNotEmpty())

		var comparedOffscreens = 0
		val mismatches = ArrayList<String>()
		for (offscreenCase in cases) {
			val mocFile = samplesByName[offscreenCase.fileName]
			if (mocFile == null) {
				println("[oracle] ${offscreenCase.fileName} not in corpus; skipping")
				continue
			}
			val mocDocument = Moc3.decode(mocFile.readBytes())
			val puppet = Moc3Import.fromMocDocument(mocDocument, null)
			// Part/drawable FILE order (oracle indices) - puppet.parts preserves it, puppet.drawables
			// does not (panel reorder), so drawable joins go through the decoded artMesh list.
			val drawableIdByFileIndex = mocDocument.artMeshes.map { DrawableId(it.id) }
			val knownParameters = puppet.parameters.mapTo(HashSet()) { it.id.raw }
			for (pose in offscreenCase.poses) {
				if (!pose.keys.all { it in knownParameters }) {
					println("[oracle] ${offscreenCase.fileName} pose=$pose names an unknown parameter; skipping")
					continue
				}
				val dump = runOracleDump(dumpModel, coreLib, mocFile, pose)
				assertEquals(
					puppet.parts.count { it.offscreen != null },
					dump.offscreens.size,
					"${offscreenCase.fileName}: offscreen count",
				)
				val inputs = preparePose(puppet, pose.entries.associate { ParameterId(it.key) to it.value })
				for (oracleOffscreen in dump.offscreens) {
					val part = puppet.parts[oracleOffscreen.ownerPartIndex]
					val offscreen = assertNotNull(part.offscreen, "${offscreenCase.fileName}: ${part.id.raw} owns an offscreen")
					val state =
						assertNotNull(
							inputs.partOffscreenStates[part.id],
							"${offscreenCase.fileName}: ${part.id.raw} carries a pose state",
						)
					comparedOffscreens++
					val label = "${offscreenCase.fileName} pose=$pose ${part.id.raw}"
					// The dynamic channels: opacity + multiply/screen RGB at the oracle tolerance.
					checkChannel(mismatches, label, "op", oracleOffscreen.opacity, state.opacity)
					checkChannel(mismatches, label, "mulR", oracleOffscreen.multiplyRgba[0], state.multiplyColor.red)
					checkChannel(mismatches, label, "mulG", oracleOffscreen.multiplyRgba[1], state.multiplyColor.green)
					checkChannel(mismatches, label, "mulB", oracleOffscreen.multiplyRgba[2], state.multiplyColor.blue)
					checkChannel(mismatches, label, "scrR", oracleOffscreen.screenRgba[0], state.screenColor.red)
					checkChannel(mismatches, label, "scrG", oracleOffscreen.screenRgba[1], state.screenColor.green)
					checkChannel(mismatches, label, "scrB", oracleOffscreen.screenRgba[2], state.screenColor.blue)
					// The RGB-only runtime model rests on alpha staying 1.0 - pin the assumption.
					assertEquals(1f, oracleOffscreen.multiplyRgba[3], "$label multiply alpha")
					assertEquals(1f, oracleOffscreen.screenRgba[3], "$label screen alpha")
					// Static joins: the offscreen mask list resolves to the same drawables.
					val oracleMaskIds = oracleOffscreen.maskIndices.mapNotNull { drawableIdByFileIndex.getOrNull(it) }.toSet()
					assertEquals(oracleMaskIds, offscreen.maskedBy.toSet(), "$label mask list")
				}
				println("[oracle] ${offscreenCase.fileName} pose=$pose offscreens=${dump.offscreens.size} OK")
			}
		}
		Assume.assumeTrue("[oracle] no offscreen corpus models resolvable", comparedOffscreens > 0)
		// The channels are plain multilinear blends of stored floats on both sides - exact within
		// 1e-5, no pinned floor needed; any mismatch is a real regression.
		assertTrue(
			mismatches.isEmpty(),
			"offscreen channel mismatches (${mismatches.size}):\n" + mismatches.joinToString("\n"),
		)
		println("[oracle] compared $comparedOffscreens offscreen states, all within tolerance")
	}

	private fun checkChannel(mismatches: ArrayList<String>, label: String, channel: String, oracle: Float, ours: Float) {
		if (!oracleCloseEnough(oracle.toDouble(), ours.toDouble())) {
			mismatches.add("$label $channel: oracle=$oracle ours=$ours")
		}
	}

	private fun requireInput(property: String): File {
		val file = System.getProperty(property)?.let(::File)?.takeIf { it.exists() }
		Assume.assumeTrue("[oracle] absent -D$property", file != null)
		return file!!
	}
}
