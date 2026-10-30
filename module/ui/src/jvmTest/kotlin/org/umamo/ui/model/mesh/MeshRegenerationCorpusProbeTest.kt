package org.umamo.ui.model.mesh

import org.umamo.edit.mesh.MeshRegeneration
import org.umamo.edit.mesh.MeshTopologyEdit
import org.umamo.edit.mesh.RegenerationInput
import org.umamo.edit.mesh.RegenerationRefusal
import org.umamo.edit.mesh.meshRegeneration
import org.umamo.edit.mesh.regenerationInputFor
import org.umamo.edit.mesh.withMeshTopologyEdits
import org.umamo.format.art.LayerRaster
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.png.PngCodec
import org.umamo.geometry.locate.TriangleLocator
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.ArtMeshSettings
import org.umamo.interop.art.mesh.generateArtMesh
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasIngest
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DrawableSpaceResolver
import org.umamo.render.eval.worldToCanvas
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyAffine
import org.umamo.runtime.model.drawableIdsByAtlasTile
import org.umamo.runtime.model.storedToArtAffineForTile
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generating every drawable's mesh from its art on real CMO3 rigs, through the whole chain: the tile
 * rasters the document embeds, the art mesher, the transfer, and the multi-drawable apply.  A tile that
 * several drawables sample is meshed whole for each of them.
 *
 * It gates what must hold on real rigs - no input goes stale between asking and building, every glue
 * pair survives with its weights, every glued vertex stays exactly where it was, the glued seams still
 * weld at a posed tail, a deformer-free drawable and a drawable under rotations only keep landing where
 * they did - and it MEASURES what decides the next step: how far a regenerated warp child's keyform-
 * space base lands from its canvas position, inside the old mesh and past it.  Past it the base is
 * extrapolated through the warp's frame, which bends; if that error is large, the command must re-derive
 * the base through the evaluator instead.
 *
 * Reads `cmo3.sample` (Erica, which carries glue) and the explicit-only comma-separated
 * `meshTransfer.samples`; self-skips with a printed line when neither exists.
 */
class MeshRegenerationCorpusProbeTest {
	/** The configurations meshed: Standard, and a wide outer margin that leans on the extrapolation. */
	private val configurations: List<Pair<String, ArtMeshSettings>> =
		listOf(
			"Standard" to ArtMeshPreset.Standard.settings,
			"Standard, 24 px outer margin" to ArtMeshPreset.Standard.settings.copy(outerMargin = 24.0),
		)

	/** One regenerated drawable's measured landing errors, art-pixel distances past the old mesh alongside. */
	private class Landing(val kind: String, val insideErrors: List<Double>, val extrapolatedErrors: List<Double>, val extrapolationDistances: List<Double>)

	@Test
	fun regeneratedRigsKeepGlueAndReportTheirLanding() {
		val samples =
			(listOfNotNull(System.getProperty("cmo3.sample")) + System.getProperty("meshTransfer.samples").orEmpty().split(','))
				.map { path -> File(path.trim()) }
				.filter { file -> file.isFile }
				.distinct()

		if (samples.isEmpty()) {
			println("[mesh-transfer] no cmo3.sample or meshTransfer.samples; skipping the regeneration probe")

			return
		}

		var builtTotal = 0

		for (sample in samples) {
			for ((configurationName, settings) in configurations) {
				builtTotal += probe(sample, configurationName, settings)
			}
		}

		assertTrue(builtTotal > 0, "the samples must regenerate some drawable, or this probe measured nothing")
	}

	/**
	 * Regenerates every eligible drawable of one rig in one configuration, checks the gates, and prints
	 * the measurement.
	 *
	 * @param File            sample            The CMO3 file.
	 * @param String          configurationName The configuration's name, for the printout.
	 * @param ArtMeshSettings settings          The mesher settings.
	 * @return Int The number of drawables regenerated.
	 */
	private fun probe(sample: File, configurationName: String, settings: ArtMeshSettings): Int {
		val cmo3 = Cmo3.read(sample.readBytes())
		val root = cmo3.root as CModelSource
		val model = Cmo3Import.fromModelSource(root)
		val ingest = cmo3AtlasIngest(root)
		val drawablesByTile = model.drawableIdsByAtlasTile()
		val skips = sortedMapOf<String, Int>()
		val edits = LinkedHashMap<DrawableId, MeshTopologyEdit>()
		val built = LinkedHashMap<DrawableId, Pair<RegenerationInput.Ready, MeshRegeneration.Built>>()
		val generatedPins = HashMap<DrawableId, IntArray>()
		var sharedTileCount = 0

		for (drawable in model.drawables) {
			val tileId = drawable.atlasTileId

			if (tileId == null) {
				skips.merge("no tile", 1, Int::plus)

				continue
			}

			// A tile several drawables sample is meshed whole for each of them - not the command's policy (it
			// keeps each sharer to the art its old mesh covers), but the one that reaches Erica's glued tail strips.
			if ((drawablesByTile[tileId]?.size ?: 0) > 1) {
				sharedTileCount++
			}

			val input =
				when (val asked = model.regenerationInputFor(drawable.id)) {
					is RegenerationInput.Refused -> {
						skips.merge("refused before meshing: ${asked.refusal}", 1, Int::plus)

						continue
					}

					is RegenerationInput.Ready -> {
						asked
					}
				}
			val raster = rasterOf(cmo3, ingest.imageResourceByTile[tileId])

			if (raster == null || raster.width != input.tileWidth || raster.height != input.tileHeight) {
				skips.merge("raster missing or not the tile's size", 1, Int::plus)

				continue
			}

			val result = generateArtMesh(raster, settings, input.pinPoints)
			val generated = result.mesh

			if (generated == null) {
				skips.merge("mesher: ${result.notices.lastOrNull()?.let { notice -> notice::class.simpleName }}", 1, Int::plus)

				continue
			}

			when (val regeneration = model.meshRegeneration(input, generated)) {
				is MeshRegeneration.Refused -> {
					assertTrue(regeneration.refusal != RegenerationRefusal.PinMismatch, "${sample.name}: '${drawable.name}' input went stale on an unchanged model")
					skips.merge("refused after meshing: ${regeneration.refusal}", 1, Int::plus)
				}

				is MeshRegeneration.Built -> {
					edits[drawable.id] = regeneration.edit
					built[drawable.id] = input to regeneration
					generatedPins[drawable.id] = generated.pinVertices
				}
			}
		}

		if (edits.isEmpty()) {
			println("[mesh-transfer] ${sample.name} at $configurationName: nothing regenerated; skips $skips")

			return 0
		}

		val edited = model.withMeshTopologyEdits(edits) ?: error("${sample.name}: the regenerated edits did not apply")
		assertGlueSurvives(sample, model, edited, built, generatedPins)
		val landings = built.keys.mapNotNull { drawableId -> landingOf(model, edited, drawableId, built.getValue(drawableId).second) }
		printReport(sample, "$configurationName ($sharedTileCount over a tile shared with other drawables, meshed whole)", built.size, skips, model, edited, landings)

		return built.size
	}

	/**
	 * Asserts every glue keeps its pairs and weights, every glued vertex keeps its canvas position, and
	 * the glued seams weld at a posed tail as well as before.
	 *
	 * @param File                                                                         sample        The rig's file, for messages.
	 * @param PuppetModel                                                                  model         The rig before.
	 * @param PuppetModel                                                                  edited        The rig after.
	 * @param Map<DrawableId, Pair<RegenerationInput.Ready, MeshRegeneration.Built>>      built         Each regenerated drawable's input and edit.
	 * @param Map<DrawableId, IntArray>                                                    generatedPins Each regenerated drawable's pin vertices.
	 */
	private fun assertGlueSurvives(
		sample: File,
		model: PuppetModel,
		edited: PuppetModel,
		built: Map<DrawableId, Pair<RegenerationInput.Ready, MeshRegeneration.Built>>,
		generatedPins: Map<DrawableId, IntArray>,
	) {
		assertEquals(model.glues.size, edited.glues.size)

		for ((glue, after) in model.glues.zip(edited.glues)) {
			assertEquals(glue.pairs.map { pair -> pair.weightA to pair.weightB }, after.pairs.map { pair -> pair.weightA to pair.weightB }, "${sample.name}: glue ${glue.id} lost a pair")
		}

		for ((drawableId, inputAndEdit) in built) {
			val input = inputAndEdit.first
			val pins = generatedPins.getValue(drawableId)
			val oldCanvas = model.drawables.first { drawable -> drawable.id == drawableId }.mesh!!.positions
			val newCanvas = edited.drawables.first { drawable -> drawable.id == drawableId }.mesh!!.positions

			for (pin in pins.indices) {
				// Two pins on one vertex average their old positions; every other pin is its old vertex.
				if (pins.count { vertex -> vertex == pins[pin] } > 1) {
					continue
				}

				val oldVertex = input.pinnedOldVertices[pin]
				assertEquals(oldCanvas[2 * oldVertex], newCanvas[2 * pins[pin]], "${sample.name}: a glued vertex moved")
				assertEquals(oldCanvas[2 * oldVertex + 1], newCanvas[2 * pins[pin] + 1], "${sample.name}: a glued vertex moved")
			}
		}

		val pose = mapOf(ParameterId("Param_Angle_Rotation3") to 43.31f)
		val gapBefore = largestWeldGap(model, pose)
		val gapAfter = largestWeldGap(edited, pose)
		val gluedRegenerated = built.count { (_, inputAndEdit) -> inputAndEdit.first.pinnedOldVertices.isNotEmpty() }
		val pinTotal = built.values.sumOf { inputAndEdit -> inputAndEdit.first.pinnedOldVertices.size }
		println(
			"[mesh-transfer] ${sample.name}: $gluedRegenerated glued drawables regenerated through $pinTotal pins; " +
				"largest glued seam gap at the posed tail ${format(gapBefore)} px before, ${format(gapAfter)} px after",
		)
		assertTrue(gapAfter <= gapBefore + 1e-3, "${sample.name}: regeneration opened a glued seam ($gapBefore -> $gapAfter px)")
	}

	/**
	 * The widest gap between two glued vertices at a pose.
	 *
	 * @param PuppetModel                model The rig.
	 * @param Map<ParameterId, Float> pose  The pose.
	 * @return Double The gap, world px.
	 */
	private fun largestWeldGap(model: PuppetModel, pose: Map<ParameterId, Float>): Double {
		val geometry = CpuDeformationEvaluator().evaluate(model, pose)
		var largest = 0.0

		for (glue in model.glues) {
			val verticesA = geometry.worldPositions[glue.meshA] ?: continue
			val verticesB = geometry.worldPositions[glue.meshB] ?: continue

			for (pair in glue.pairs) {
				val gap = hypot((verticesA[2 * pair.indexA] - verticesB[2 * pair.indexB]).toDouble(), (verticesA[2 * pair.indexA + 1] - verticesB[2 * pair.indexB + 1]).toDouble())
				largest = maxOf(largest, gap)
			}
		}

		return largest
	}

	/**
	 * Measures one regenerated drawable: how far its new base lands (through its deformers at the
	 * default pose) from its new canvas positions, for the vertices inside the old mesh and those past
	 * it, and how far past.  Null for a drawable with no mapping or whose OLD mesh already lands more
	 * than 0.1 px off (a stale canvas is not the transfer's error).
	 *
	 * @param PuppetModel             model    The rig before.
	 * @param PuppetModel             edited   The rig after.
	 * @param DrawableId              drawableId The drawable.
	 * @param MeshRegeneration.Built  regeneration Its edit.
	 * @return Landing? The measurement.
	 */
	private fun landingOf(model: PuppetModel, edited: PuppetModel, drawableId: DrawableId, regeneration: MeshRegeneration.Built): Landing? {
		val drawable = model.drawables.first { candidate -> candidate.id == drawableId }
		val mapping = DrawableSpaceResolver(model, emptyMap()).mapping(drawableId) ?: return null
		val oldMesh = drawable.mesh!!
		val oldErrors = landingErrors(worldToCanvas(mapping.localToWorld(oldMesh.localPositions)), oldMesh.positions)

		if (oldErrors.max() > 0.1) {
			return null
		}

		val newMesh = edited.drawables.first { candidate -> candidate.id == drawableId }.mesh!!
		val newErrors = landingErrors(worldToCanvas(mapping.localToWorld(newMesh.localPositions)), newMesh.positions)
		val extrapolated = regeneration.extrapolatedVertices.toHashSet()
		val distances = extrapolationDistances(model, drawable, oldMesh, newMesh, regeneration.extrapolatedVertices)

		return Landing(
			kindOf(model, drawable),
			newErrors.filterIndexed { vertex, _ -> vertex !in extrapolated },
			regeneration.extrapolatedVertices.map { vertex -> newErrors[vertex] },
			distances,
		)
	}

	/**
	 * Per-vertex distances between two position arrays.
	 *
	 * @param FloatArray landed The landed positions.
	 * @param FloatArray canvas The canvas positions.
	 * @return List<Double> One distance per vertex.
	 */
	private fun landingErrors(landed: FloatArray, canvas: FloatArray): List<Double> =
		(0 until canvas.size / 2).map { vertex -> hypot((landed[2 * vertex] - canvas[2 * vertex]).toDouble(), (landed[2 * vertex + 1] - canvas[2 * vertex + 1]).toDouble()) }

	/**
	 * How far, in art pixels, each extrapolated vertex lies past the old mesh.
	 *
	 * @param PuppetModel  model     The rig before.
	 * @param Drawable     drawable  The drawable.
	 * @param DrawableMesh oldMesh   Its old mesh.
	 * @param DrawableMesh newMesh   Its new mesh.
	 * @param IntArray     vertices  The extrapolated vertices.
	 * @return List<Double> One distance per extrapolated vertex.
	 */
	private fun extrapolationDistances(model: PuppetModel, drawable: Drawable, oldMesh: DrawableMesh, newMesh: DrawableMesh, vertices: IntArray): List<Double> {
		val tileId: AtlasTileId = drawable.atlasTileId!!
		val tile = model.atlas.tileById.getValue(tileId)
		val storedToArt = model.atlas.storedToArtAffineForTile(tileId)!!
		val oldPoints = artPixels(applyAffine(oldMesh.uvs, storedToArt), tile.width, tile.height)
		val newPoints = artPixels(applyAffine(newMesh.uvs, storedToArt), tile.width, tile.height)
		val locator = TriangleLocator(oldPoints, oldMesh.indices)

		return vertices.map { vertex -> locator.locate(newPoints[2 * vertex].toDouble(), newPoints[2 * vertex + 1].toDouble())?.distance ?: Double.NaN }
	}

	/**
	 * Art UVs scaled to art pixels.
	 *
	 * @param FloatArray artUvs The art UVs.
	 * @param Int        width  The tile width.
	 * @param Int        height The tile height.
	 * @return FloatArray The points.
	 */
	private fun artPixels(artUvs: FloatArray, width: Int, height: Int): FloatArray = FloatArray(artUvs.size) { component -> artUvs[component] * if (component % 2 == 0) width else height }

	/**
	 * The kind of deformer chain a drawable hangs from: none, rotations only, or one with a warp.
	 *
	 * @param PuppetModel model    The rig.
	 * @param Drawable    drawable The drawable.
	 * @return String "root", "rotation", or "warp".
	 */
	private fun kindOf(model: PuppetModel, drawable: Drawable): String {
		val deformersById = model.deformers.associateBy { deformer -> deformer.id }
		var current = drawable.parentDeformerId?.let { parentId -> deformersById[parentId] } ?: return "root"

		while (true) {
			if (current is Deformer.Warp) {
				return "warp"
			}

			current = current.parent?.let { parentId -> deformersById[parentId] } ?: return "rotation"
		}
	}

	/**
	 * Prints one rig's measurement and checks the root and rotation gates.
	 *
	 * @param File                sample            The rig's file.
	 * @param String              configurationName The configuration.
	 * @param Int                 builtCount        How many drawables regenerated.
	 * @param Map<String, Int>    skips             Why the others did not.
	 * @param PuppetModel         model             The rig before.
	 * @param PuppetModel         edited            The rig after.
	 * @param List<Landing>       landings          The measurements.
	 */
	private fun printReport(sample: File, configurationName: String, builtCount: Int, skips: Map<String, Int>, model: PuppetModel, edited: PuppetModel, landings: List<Landing>) {
		val vertexCountBefore = model.drawables.sumOf { drawable -> drawable.mesh?.vertexCount ?: 0 }
		val vertexCountAfter = edited.drawables.sumOf { drawable -> drawable.mesh?.vertexCount ?: 0 }
		println("[mesh-transfer] ${sample.name} at $configurationName: $builtCount regenerated, ${landings.size} measured (the rest land more than 0.1 px off already), vertices $vertexCountBefore -> $vertexCountAfter, skips $skips")

		for (kind in listOf("root", "rotation", "warp")) {
			val ofKind = landings.filter { landing -> landing.kind == kind }

			if (ofKind.isEmpty()) {
				continue
			}

			val inside = ofKind.flatMap { landing -> landing.insideErrors }
			val extrapolated = ofKind.flatMap { landing -> landing.extrapolatedErrors }
			println("[mesh-transfer]   $kind (${ofKind.size} drawables) inside: ${summary(inside)}")
			println("[mesh-transfer]   $kind extrapolated: ${summary(extrapolated)}")

			for ((label, range) in listOf("0-4" to 0.0..4.0, "4-16" to 4.0..16.0, "16-64" to 16.0..64.0, ">64" to 64.0..Double.MAX_VALUE)) {
				val banded = ofKind.flatMap { landing -> landing.extrapolatedErrors.zip(landing.extrapolationDistances).filter { (_, distance) -> distance > range.start && distance <= range.endInclusive }.map { (error, _) -> error } }

				if (banded.isNotEmpty()) {
					println("[mesh-transfer]     $kind extrapolated $label px past the old mesh: ${summary(banded)}")
				}
			}

			if (kind != "warp") {
				val worst = (inside + extrapolated).maxOrNull() ?: 0.0
				assertTrue(worst <= 0.12, "${sample.name}: a $kind drawable lands $worst px off its canvas after regeneration")
			}
		}
	}

	/**
	 * n, median, 90th, 99th percentile, and maximum of some errors.
	 *
	 * @param List<Double> errors The errors.
	 * @return String The summary.
	 */
	private fun summary(errors: List<Double>): String {
		if (errors.isEmpty()) {
			return "n 0"
		}

		val sorted = errors.sorted()
		val percentile = { fraction: Double -> sorted[((sorted.size - 1) * fraction).toInt()] }

		return "n ${sorted.size}, p50 ${format(percentile(0.5))}, p90 ${format(percentile(0.9))}, p99 ${format(percentile(0.99))}, max ${format(sorted.last())} px"
	}

	/**
	 * A tile's embedded raster, decoded.
	 *
	 * @param Cmo3Model       cmo3     The opened file.
	 * @param CImageResource? resource The tile's image resource, or null.
	 * @return LayerRaster? The raster, or null when the tile has none.
	 */
	private fun rasterOf(cmo3: Cmo3Model, resource: CImageResource?): LayerRaster? {
		val bytes = resource?.let(cmo3::extractLayerPng) ?: return null
		val image = PngCodec.read(bytes)

		return LayerRaster(image.width, image.height, image.rgba)
	}

	/**
	 * Formats a distance to three decimals, locale-independent.
	 *
	 * @param Double value The value.
	 * @return String The text.
	 */
	private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}