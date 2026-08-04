package org.umamo.render.eval

import org.junit.Assume
import java.io.File
import kotlin.math.abs

/*
 * Shared helpers for differential-oracle tests that shell out to the rebuilt dump_model tool
 * (the Umamo C++ Runtime harness's dump_model.c). Mirrors DeformationOracleTest's hashing/comparison exactly -
 * that test's default-pose gate is deliberately left textually untouched (see its 156 re-pin
 * note), so the shared pieces live here for the posed tests instead of being extracted from it.
 */

/**
 * One dumped drawable: its vertex count, the oracle's position/uv rolling hashes, and the
 * post-update scalar channels ([opacity] and the multiply/screen RGBA the `--channels` flag adds).
 *
 * The channels are the cascade result, not the drawable's own keyform value - the oracle folds its
 * parent deformer chain and its ancestor parts in before exposing them - which is exactly what makes
 * them a usable gate on our own cascade.
 */
internal data class OracleEntry(
	val vtx: Int,
	val vposH: Double,
	val vuvH: Double,
	val opacity: Float,
	val multiplyRgba: List<Float>,
	val screenRgba: List<Float>,
	/** The core's post-update draw order (its rounded integer form of the blended float). */
	val drawOrder: Int = 0,
	/** The core's post-update RENDER order - the drawable's slot after draw-group depth sorting. */
	val renderOrder: Int = 0,
)

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

/**
 * A parsed dump: the model-space canvas header plus the per-drawable entries by id.
 *
 * The two index->id lists exist because a moc addresses parts and drawables positionally while the
 * export re-derives its own ordering, so ANY cross-file comparison of an index (an offscreen's owner,
 * a mask list) has to travel through the id or it compares two different numbering schemes.
 */
internal data class OracleDump(
	val pixelsPerUnit: Float,
	val originX: Float,
	val originY: Float,
	val entries: Map<String, OracleEntry>,
	val offscreens: List<OracleOffscreen> = emptyList(),
	/** Part ids in file-index order, from the `T` lines. */
	val partIds: List<String> = emptyList(),
	/** Drawable ids in file-index order, from the `D` lines. */
	val drawableIds: List<String> = emptyList(),
)

/**
 * Runs dump_model against [coreLib] and [moc3] at [pose] (empty = default pose) and parses the
 * canvas header plus the `T` (part), `O` (offscreen), and `D` (drawable) lines.
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
	// Adds mul=/scr= to each D line. Always on: the extra columns cost nothing to parse and the
	// alternative is two dump invocations for tests that want geometry AND colour.
	command.add("--channels")
	for ((parameterId, value) in pose) {
		command.add("--param")
		command.add("$parameterId=$value")
	}
	val process = ProcessBuilder(command).redirectErrorStream(true).start()
	val output = process.inputStream.bufferedReader().readText()
	val exit = process.waitFor()
	// The file is named because a non-zero exit IS the "does it even load" gate: without it a rejected
	// export reads as an unattributable crash in whichever model happened to be next.
	check(exit == 0) { "dump_model failed on ${moc3.path} (exit $exit): ${output.take(300)}" }

	val canvasRegex = Regex("""# canvas size=(\S+),(\S+) origin=(\S+),(\S+) ppu=(\S+)""")
	val canvas = canvasRegex.find(output) ?: error("no canvas header in dump")
	val idRegex = Regex("""id=(\S+)""")
	val vtxRegex = Regex("""vtx=(\d+)""")
	val vposRegex = Regex("""vpos_h=(\S+)""")
	val vuvRegex = Regex("""vuv_h=(\S+)""")
	// D-line channels: op= comes from --update, mul=/scr= from --channels. Anchored to ` op=` with a
	// leading space so it cannot match the `vpos_h=`/`vuv_h=` suffixes or an id containing "op=".
	val opacityRegex = Regex(""" op=(\S+)""")
	val drawOrderRegex = Regex(""" draw=(-?\d+)""")
	val renderOrderRegex = Regex(""" render=(-?\d+)""")
	val multiplyRegex = Regex(""" mul=(\S+)""")
	val screenRegex = Regex(""" scr=(\S+)""")
	// O <i> owner=<d> blend=<d> cflag=0x<hex> masks=<n>:<i0>,<i1>,… op=<g> mul=<r>,<g>,<b>,<a> scr=<r>,<g>,<b>,<a>
	val offscreenRegex =
		Regex("""O \d+ owner=(-?\d+) blend=(-?\d+) cflag=0x([0-9a-fA-F]+) masks=\d+:(\S*) op=(\S+) mul=(\S+) scr=(\S+)""")
	val entries = HashMap<String, OracleEntry>()
	val offscreens = ArrayList<OracleOffscreen>()
	val partIds = ArrayList<String>()
	val drawableIds = ArrayList<String>()
	// T <index> id=<partId> parent=<index>
	val partRegex = Regex("""T \d+ id=(\S+)""")
	for (line in output.lineSequence()) {
		if (line.startsWith("T ")) {
			partRegex.find(line)?.let { partIds.add(it.groupValues[1]) }
			continue
		}
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
		// Recorded before the geometry parse: the id list is the file's INDEX ordering, so a line this
		// parser gives up on would silently shift every later index.
		drawableIds.add(id)
		val vtx = vtxRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
		// Non-finite hashes are kept rather than skipped, for the same reason: a dropped entry reads as
		// "the exported file has no such drawable", which points at the wrong thing entirely.
		val vposH = vposRegex.find(line)?.let { it.groupValues[1].toDoubleOrNull() ?: Double.NaN } ?: continue
		val vuvH = vuvRegex.find(line)?.let { it.groupValues[1].toDoubleOrNull() ?: Double.NaN } ?: continue
		val opacity = opacityRegex.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: Float.NaN
		// A non-finite channel is DATA, not a parse error: the core prints "-nan" for a drawable whose
		// inputs went bad, and that is exactly the kind of thing a differential gate exists to catch.
		// Throwing here would turn a reportable mismatch into an unattributable test crash.
		val multiplyRgba =
			multiplyRegex.find(line)?.groupValues?.get(1)?.split(',')?.map { it.toFloatOrNull() ?: Float.NaN }
				?: emptyList()
		val screenRgba =
			screenRegex.find(line)?.groupValues?.get(1)?.split(',')?.map { it.toFloatOrNull() ?: Float.NaN }
				?: emptyList()
		val drawOrder = drawOrderRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
		val renderOrder = renderOrderRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
		entries[id] = OracleEntry(vtx, vposH, vuvH, opacity, multiplyRgba, screenRgba, drawOrder, renderOrder)
	}
	return OracleDump(
		pixelsPerUnit = canvas.groupValues[5].toFloat(),
		originX = canvas.groupValues[3].toFloat(),
		originY = canvas.groupValues[4].toFloat(),
		entries = entries,
		offscreens = offscreens,
		partIds = partIds,
		drawableIds = drawableIds,
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

/**
 * Resolves a `-D`-supplied oracle input, skipping the calling test when it is absent.
 *
 * Every oracle gate needs the same two inputs and each had grown its own private copy of this; one
 * definition keeps the skip MESSAGE identical too, which is what makes an absent harness legible in
 * the test log rather than looking like a silent pass.
 *
 * @param String property The system property naming the file.
 * @return File The existing file (the test is skipped before this returns otherwise).
 */
internal fun requireOracleInput(property: String): File {
	val file = System.getProperty(property)?.let(::File)?.takeIf { it.exists() }
	Assume.assumeTrue("[oracle] absent -D$property", file != null)
	return file!!
}

/**
 * Whether the oracle never evaluated [entry] - an all-zero row rather than a real result.
 *
 * The core leaves a drawable untouched when nothing drives it at this pose, and the zeroed row is
 * indistinguishable from a legitimately transparent one by value alone; the combination of a zero
 * position hash AND zero opacity AND a zero (not white) multiply is what separates them, since an
 * evaluated drawable still reports real geometry and a white multiply.
 *
 * @param OracleEntry entry The dumped drawable.
 * @return Boolean True when the oracle never evaluated it.
 */
internal fun oracleNeverEvaluated(entry: OracleEntry): Boolean =
	entry.vposH == 0.0 &&
		entry.opacity == 0f &&
		entry.multiplyRgba.size == 4 &&
		entry.multiplyRgba[0] == 0f &&
		entry.multiplyRgba[1] == 0f &&
		entry.multiplyRgba[2] == 0f
