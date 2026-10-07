package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.mesh.commitObjectPositions
import org.umamo.format.uma.UmaModel
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.captureDrawableWorld
import org.umamo.ui.viewport.AtlasPageBinding
import java.io.File
import java.lang.management.ManagementFactory
import java.util.zip.Deflater
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Measures what the mesh deltas cost on real documents: the heap an open document holds, the delta arrays' share of
 * it, the `.uma` a save writes (its stored `buffers.bin`, and what deflating it would give), and what a select-all
 * Object-mode move costs the undo history.  Written to run unchanged whether a build stores its deltas as float32
 * or float64, so the two builds can be compared side by side.
 *
 * A probe, not a gate: it asserts nothing about the numbers and self-skips without `-Dprofile.samples` (a
 * comma-separated list of `.cmo3` and `.moc3` files).  `-Dprofile.output` names where the `.uma` files go.
 */
class DeltaPrecisionProfileProbe {
	/** How many select-all moves each sample commits, so a step's history cost is an average. */
	private val moveCount = 3

	/**
	 * The heap the live objects hold, after full collections have settled: the heap's used bytes right after them,
	 * so metaspace and code cache stay out of it.
	 *
	 * @return Long The bytes.
	 */
	private fun settledHeapBytes(): Long {
		repeat(4) {
			System.gc()
			Thread.sleep(150)
		}
		return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
	}

	/**
	 * Bytes the calling thread has allocated so far.
	 *
	 * @return Long The bytes.
	 */
	private fun threadAllocatedBytes(): Long = (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).getThreadAllocatedBytes(Thread.currentThread().id)

	/**
	 * The heap one primitive array occupies: a 16-byte header and its components, padded to 8 bytes.
	 *
	 * @param Int componentCount The component count.
	 * @param Int componentBytes The bytes per component.
	 * @return Long The bytes.
	 */
	private fun arrayBytes(componentCount: Int, componentBytes: Int): Long = ((16L + componentCount.toLong() * componentBytes + 7L) / 8L) * 8L

	/**
	 * The bytes per component of a delta array, whichever precision this build stores.
	 *
	 * @param Any deltas The delta array.
	 * @return Int 4 for float32, 8 for float64.
	 */
	private fun componentBytesOf(deltas: Any): Int =
		when (deltas) {
			is FloatArray -> 4
			is DoubleArray -> 8
			else -> error("unexpected delta array ${deltas::class}")
		}

	/**
	 * Every mesh delta array of [drawableIds] (every drawable when null): grid cells and blend-shape forms.
	 *
	 * @param PuppetModel      puppet      The model.
	 * @param Set<DrawableId>? drawableIds The drawables, or null for all.
	 * @return List<Any> The arrays.
	 */
	private fun deltaArraysOf(puppet: PuppetModel, drawableIds: Set<DrawableId>? = null): List<Any> =
		puppet.drawables
			.filter { drawable -> drawableIds == null || drawable.id in drawableIds }
			.flatMap { drawable ->
				drawable.geometryGrid?.cells.orEmpty().map { cell -> cell.form.positionDeltas as Any } +
					drawable.blendShapes.flatMap { binding -> binding.forms.filterNotNull().map { form -> form.positionDeltas as Any } }
			}

	/**
	 * The heap a set of delta arrays occupies.
	 *
	 * @param List<Any> arrays The arrays.
	 * @return Pair The component count and the bytes.
	 */
	private fun inventoryOf(arrays: List<Any>): Pair<Long, Long> {
		var components = 0L
		var bytes = 0L
		for (array in arrays) {
			val size =
				when (array) {
					is FloatArray -> array.size
					is DoubleArray -> array.size
					else -> 0
				}
			components += size
			bytes += arrayBytes(size, componentBytesOf(array))
		}
		return components to bytes
	}

	/**
	 * The heap of every array [after] holds that [before] does not share by identity - what one history step keeps
	 * alive beyond the model before it: canvas meshes, bases, uvs, indices, and mesh deltas, an array two fields
	 * share counted once.  Deterministic, unlike a heap reading.
	 *
	 * @param PuppetModel before The model before the commit.
	 * @param PuppetModel after  The model after it.
	 * @return Long The bytes.
	 */
	private fun newArrayBytes(before: PuppetModel, after: PuppetModel): Long {
		/**
		 * Every array a model's drawables hold.
		 *
		 * @param PuppetModel model The model.
		 * @return List<Any> The arrays.
		 */
		fun arraysOf(model: PuppetModel): List<Any> =
			model.drawables.flatMap { drawable -> listOfNotNull(drawable.mesh?.positions, drawable.mesh?.localPositions, drawable.mesh?.uvs, drawable.mesh?.indices) } + deltaArraysOf(model)
		val known = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
		known.addAll(arraysOf(before))
		val counted = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
		var bytes = 0L
		for (array in arraysOf(after)) {
			if (array in known || !counted.add(array)) {
				continue
			}
			bytes +=
				when (array) {
					is FloatArray -> arrayBytes(array.size, 4)
					is DoubleArray -> arrayBytes(array.size, 8)
					is IntArray -> arrayBytes(array.size, 4)
					else -> 0L
				}
		}
		return bytes
	}

	/**
	 * A byte count in mebibytes, for the report.
	 *
	 * @param Long bytes The bytes.
	 * @return String The text.
	 */
	private fun mib(bytes: Long): String = "%.2f MiB".format(bytes / 1048576.0)

	@Test
	fun profileDeltaPrecision() =
		runBlocking {
			val samples = System.getProperty("profile.samples")?.split(',')?.map { path -> File(path.trim()) }?.filter { file -> file.isFile }.orEmpty()
			if (samples.isEmpty()) {
				println("[profile] no profile.samples; skipping")
				return@runBlocking
			}
			val outputDirectory = File(System.getProperty("profile.output") ?: createTempDirectory("delta-profile").toString()).also { directory -> directory.mkdirs() }
			println("[profile] heap cap ${mib(Runtime.getRuntime().maxMemory())}")
			if (System.getProperty("profile.openOnly") == "true") {
				// The heap an open document holds, read in a JVM that has opened nothing else: across several samples
				// in one JVM, what the previous one left behind is still settling when the next baseline is read.
				for (sample in samples) {
					reportOpenOnly(sample)
				}
				return@runBlocking
			}
			// A first sample run and thrown away, so the timings below are not the JIT warming up.
			System.getProperty("profile.warmup")?.let(::File)?.takeIf { file -> file.isFile }?.let { warmup -> profileSample(warmup, outputDirectory, report = false) }
			for (sample in samples) {
				profileSample(sample, outputDirectory, report = true)
			}
		}

	/**
	 * Opens one sample and prints the heap the open document holds, as a `[profile-open]` JSON line.
	 *
	 * @param File sample The `.cmo3` or `.moc3`.
	 */
	private suspend fun reportOpenOnly(sample: File) {
		val baseline = settledHeapBytes()
		val document = (loadDocument(PlatformFile(sample)) as? DocumentLoad.Loaded)?.document as? PuppetDocument
		val opened = settledHeapBytes()
		java.lang.ref.Reference.reachabilityFence(document)
		val line =
			buildJsonObject {
				put("sample", JsonPrimitive(sample.name))
				put("format", JsonPrimitive(sample.extension))
				put("status", JsonPrimitive(if (document != null) "ok" else "failed"))
				put("openRetainedBytes", JsonPrimitive(opened - baseline))
			}
		println("[profile-open] $line")
	}

	/**
	 * Profiles one sample and prints its report line, a JSON object prefixed `[profile-json]`; a sample that does not
	 * open or save is reported as failed with the reason, so one bad file does not end the run.
	 *
	 * @param File    sample          The `.cmo3` or `.moc3`.
	 * @param File    outputDirectory Where the `.uma` goes.
	 * @param Boolean report          Whether to print the report line.
	 */
	private suspend fun profileSample(sample: File, outputDirectory: File, report: Boolean) {
		val metrics = LinkedHashMap<String, Any>()
		metrics["sample"] = sample.name
		metrics["format"] = sample.extension
		try {
			measureSample(sample, outputDirectory, metrics)
			metrics["status"] = "ok"
		} catch (failure: Exception) {
			metrics["status"] = "failed"
			metrics["reason"] = "${failure::class.simpleName}: ${failure.message}".take(300)
		} catch (failure: AssertionError) {
			metrics["status"] = "failed"
			metrics["reason"] = "${failure::class.simpleName}: ${failure.message}".take(300)
		}
		if (report) {
			println("[profile-json] " + buildJsonObject { metrics.forEach { (key, value) -> put(key, if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString())) } })
		}
	}

	/**
	 * Measures one sample into [metrics]: the open, the save, and the select-all moves.
	 *
	 * @param File                    sample          The `.cmo3` or `.moc3`.
	 * @param File                    outputDirectory Where the `.uma` goes.
	 * @param MutableMap<String, Any> metrics         Where the measurements go.
	 */
	private suspend fun measureSample(sample: File, outputDirectory: File, metrics: MutableMap<String, Any>) {
		val label = sample.nameWithoutExtension
		val baseline = settledHeapBytes()
		val loadStart = System.nanoTime()
		var document: PuppetDocument? = assertIs<PuppetDocument>(assertIs<DocumentLoad.Loaded>(loadDocument(PlatformFile(sample))).document)
		metrics["openMillis"] = (System.nanoTime() - loadStart) / 1_000_000
		val opened = settledHeapBytes()
		val puppet = document!!.puppet
		val deltaArrays = deltaArraysOf(puppet)
		val (deltaComponents, deltaBytes) = inventoryOf(deltaArrays)
		metrics["drawables"] = puppet.drawables.size
		metrics["openRetainedBytes"] = opened - baseline
		metrics["deltaArrays"] = deltaArrays.size
		metrics["deltaComponents"] = deltaComponents
		metrics["deltaHeapBytes"] = deltaBytes
		// The bases a document holds apart from its canvas meshes: one array per mesh that does not share it.
		val separateBases = puppet.drawables.mapNotNull { drawable -> drawable.mesh?.takeIf { mesh -> mesh.localPositions !== mesh.positions }?.localPositions }
		metrics["separateBases"] = separateBases.size
		metrics["separateBaseHeapBytes"] = separateBases.sumOf { base -> arrayBytes(base.size, 4) }

		measureSave(document, puppet, outputDirectory, label, metrics)

		// A select-all Object-mode move, committed the way the overlay commits it, [moveCount] times.
		var session: EditorSession? = EditorSession(puppet, document.liveParams.values)
		val historyBaseline = settledHeapBytes()
		var commitNanos = 0L
		var commitAllocated = 0L
		var movedIds: Set<DrawableId> = emptySet()
		var stepArrayBytes = 0L
		repeat(moveCount) { stepIndex ->
			val model = session!!.model.value
			val pose = session!!.pose.value
			val geometries = model.drawables.filter { drawable -> drawable.mesh != null }.mapNotNull { drawable -> captureDrawableWorld(model, pose, drawable.id) }
			val newPositions =
				geometries.associate { geometry ->
					val moved = FloatArray(geometry.world.size) { componentIndex -> if (componentIndex % 2 == 0) geometry.world[componentIndex] + 1f + stepIndex else geometry.world[componentIndex] }
					geometry.drawableId to geometry.worldToRest(moved)
				}
			movedIds = newPositions.keys
			val allocatedBefore = threadAllocatedBytes()
			val commitStart = System.nanoTime()
			session!!.commitObjectPositions(MeshChange.TransformDrawables(newPositions.keys.toList(), MeshOperatorKind.Grab), newPositions)
			commitNanos += System.nanoTime() - commitStart
			commitAllocated += threadAllocatedBytes() - allocatedBefore
			stepArrayBytes += newArrayBytes(model, session!!.model.value)
		}
		val historyAfter = settledHeapBytes()
		// Nothing reads the session after the moves, so without the fence the collector may take it - history and all -
		// before the reading above.
		java.lang.ref.Reference.reachabilityFence(session)
		val (_, movedDeltaBytes) = inventoryOf(deltaArraysOf(puppet, movedIds))
		metrics["movedDrawables"] = movedIds.size
		metrics["commitMicros"] = commitNanos / 1000 / moveCount
		metrics["commitAllocatedBytes"] = commitAllocated / moveCount
		metrics["historyStepBytes"] = stepArrayBytes / moveCount
		metrics["historyHeapBytes"] = (historyAfter - historyBaseline) / moveCount
		metrics["movedPositionBytes"] = puppet.drawables.filter { drawable -> drawable.id in movedIds }.sumOf { drawable -> arrayBytes(drawable.mesh!!.positions.size, 4) }
		metrics["movedDeltaBytes"] = movedDeltaBytes
		// A base apart from its canvas mesh is a second array a move rewrites; a shared one costs nothing more.
		metrics["movedLocalBytes"] =
			puppet.drawables
				.filter { drawable -> drawable.id in movedIds && drawable.mesh!!.localPositions !== drawable.mesh!!.positions }
				.sumOf { drawable -> arrayBytes(drawable.mesh!!.localPositions.size, 4) }
		session = null
		document = null
	}

	/**
	 * Saves [document] the way File > Save does, to a fresh file, records the file's sizes, and deletes it.  Its own
	 * function so nothing it allocates is still reachable, or dies, while the history is measured afterwards.
	 *
	 * @param PuppetDocument          document        The open document.
	 * @param PuppetModel             puppet          Its model.
	 * @param File                    outputDirectory Where the `.uma` goes.
	 * @param String                  label           The sample, for the file name.
	 * @param MutableMap<String, Any> metrics         Where the measurements go.
	 */
	private suspend fun measureSave(document: PuppetDocument, puppet: PuppetModel, outputDirectory: File, label: String, metrics: MutableMap<String, Any>) {
		val target = File(outputDirectory, "$label.uma")
		target.delete()
		val binding = AtlasPageBinding(puppet.atlas, document.textures)
		val saveStart = System.nanoTime()
		val outcome = writeUmaDocument(document, UmaModel.create(umamoWriterInfo()), puppet, binding, JsonObject(emptyMap()), PlatformFile(target))
		metrics["saveMillis"] = (System.nanoTime() - saveStart) / 1_000_000
		assertIs<UmaWriteOutcome.Written>(outcome, "the save of $label")
		ZipFile(target).use { zip ->
			// A document with no mesh writes no buffer (UMA §4.9).
			val bufferBytes = zip.getEntry("model/buffers.bin")?.let { buffer -> zip.getInputStream(buffer).use { stream -> stream.readBytes() } } ?: ByteArray(0)
			val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
			deflater.setInput(bufferBytes)
			deflater.finish()
			val scratch = ByteArray(1 shl 16)
			var deflatedBytes = 0L
			while (!deflater.finished()) {
				deflatedBytes += deflater.deflate(scratch)
			}
			deflater.end()
			metrics["fileBytes"] = target.length()
			metrics["bufferBytes"] = bufferBytes.size
			metrics["bufferDeflatedBytes"] = deflatedBytes
			metrics["puppetJsonBytes"] = zip.getEntry("model/puppet.json")?.size ?: 0L
		}
		target.delete()
	}
}