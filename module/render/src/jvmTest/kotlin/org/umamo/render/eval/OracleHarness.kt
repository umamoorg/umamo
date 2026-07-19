package org.umamo.render.eval

import java.io.File
import kotlin.math.abs

/*
 * Shared helpers for differential-oracle tests that shell out to the rebuilt dump_model tool
 * (the Umamo C++ Runtime harness's dump_model.c). Mirrors DeformationOracleTest's hashing/comparison exactly -
 * that test's default-pose gate is deliberately left textually untouched (see its 156 re-pin
 * note), so the shared pieces live here for the posed tests instead of being extracted from it.
 */

/** One dumped drawable: its vertex count and the oracle's position/uv rolling hashes. */
internal data class OracleEntry(val vtx: Int, val vposH: Double, val vuvH: Double)

/**
 * One dumped offscreen (an `O` line): the static moc fields plus the post-update interpolated
 * channels (opacity, multiply/screen RGBA - the oracle's evalOffscreens output).
 */
internal data class OracleOffscreen(
	val ownerPartIndex: Int,
	val blendMode: Int,
	val constantFlags: Int,
	val maskIndices: List<Int>,
	val opacity: Float,
	val multiplyRgba: List<Float>,
	val screenRgba: List<Float>,
)

/** A parsed dump: the model-space canvas header plus the per-drawable entries by id. */
internal data class OracleDump(
	val pixelsPerUnit: Float,
	val originX: Float,
	val originY: Float,
	val entries: Map<String, OracleEntry>,
	val offscreens: List<OracleOffscreen> = emptyList(),
)

/**
 * Runs dump_model against [coreLib] and [moc3] at [pose] (empty = default pose) and parses the
 * canvas header + `D` lines.
 *
 * @param File dumpModel The dump_model binary.
 * @param File coreLib   .so to dlopen - Must be compatible with the official Cubism API.
 * @param File moc3      The model to dump.
 * @param Map  pose      Parameter id -> value written before csmUpdateModel.
 * @return OracleDump The parsed dump.
 */
internal fun runOracleDump(dumpModel: File, coreLib: File, moc3: File, pose: Map<String, Float>): OracleDump {
	val command = ArrayList<String>()
	command.add(dumpModel.absolutePath)
	command.add(coreLib.absolutePath)
	command.add(moc3.absolutePath)
	command.add("--update")
	for ((parameterId, value) in pose) {
		command.add("--param")
		command.add("$parameterId=$value")
	}
	val process = ProcessBuilder(command).redirectErrorStream(true).start()
	val output = process.inputStream.bufferedReader().readText()
	val exit = process.waitFor()
	check(exit == 0) { "dump_model failed (exit $exit): ${output.take(300)}" }

	val canvasRegex = Regex("""# canvas size=(\S+),(\S+) origin=(\S+),(\S+) ppu=(\S+)""")
	val canvas = canvasRegex.find(output) ?: error("no canvas header in dump")
	val idRegex = Regex("""id=(\S+)""")
	val vtxRegex = Regex("""vtx=(\d+)""")
	val vposRegex = Regex("""vpos_h=(\S+)""")
	val vuvRegex = Regex("""vuv_h=(\S+)""")
	// O <i> owner=<d> blend=<d> cflag=0x<hex> masks=<n>:<i0>,<i1>,… op=<g> mul=<r>,<g>,<b>,<a> scr=<r>,<g>,<b>,<a>
	val offscreenRegex =
		Regex("""O \d+ owner=(-?\d+) blend=(-?\d+) cflag=0x([0-9a-fA-F]+) masks=\d+:(\S*) op=(\S+) mul=(\S+) scr=(\S+)""")
	val entries = HashMap<String, OracleEntry>()
	val offscreens = ArrayList<OracleOffscreen>()
	for (line in output.lineSequence()) {
		if (line.startsWith("O ")) {
			val match = offscreenRegex.find(line) ?: continue
			offscreens.add(
				OracleOffscreen(
					ownerPartIndex = match.groupValues[1].toInt(),
					blendMode = match.groupValues[2].toInt(),
					constantFlags = match.groupValues[3].toInt(16),
					maskIndices = match.groupValues[4].split(',').mapNotNull { it.toIntOrNull() },
					opacity = match.groupValues[5].toFloat(),
					multiplyRgba = match.groupValues[6].split(',').map { it.toFloat() },
					screenRgba = match.groupValues[7].split(',').map { it.toFloat() },
				),
			)
			continue
		}
		if (!line.startsWith("D ")) {
			continue
		}
		val id = idRegex.find(line)?.groupValues?.get(1) ?: continue
		val vtx = vtxRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
		val vposH = vposRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
		val vuvH = vuvRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
		entries[id] = OracleEntry(vtx, vposH, vuvH)
	}
	return OracleDump(
		pixelsPerUnit = canvas.groupValues[5].toFloat(),
		originX = canvas.groupValues[3].toFloat(),
		originY = canvas.groupValues[4].toFloat(),
		entries = entries,
		offscreens = offscreens,
	)
}

/** The oracle's rolling hash, `hp = hp*1.0000001 + v`, over canvas->model transformed vertices. */
internal fun oracleTransformedHash(world: FloatArray, offsetX: Float, offsetY: Float, scale: Float): Double {
	var hash = 0.0
	var index = 0
	while (index < world.size) {
		hash = hash * 1.0000001 + (world[index] - offsetX) / scale
		hash = hash * 1.0000001 + (world[index + 1] - offsetY) / scale
		index += 2
	}
	return hash
}

/** Relative/absolute closeness at the oracle's 1e-5 tolerance (mirrors DeformationOracleTest). */
internal fun oracleCloseEnough(a: Double, b: Double): Boolean {
	val scale = maxOf(1.0, abs(a), abs(b))
	return abs(a - b) <= 1e-5 * scale
}
