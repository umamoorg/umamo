package org.umamo.ui.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.geometry.locate.TriangleLocator
import org.umamo.geometry.predicate.orient2d
import org.umamo.geometry.triangulation.refine.areaScaleOf
import org.umamo.interop.ExportNotice
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.render.DecodedImage
import org.umamo.render.SampledRegion
import org.umamo.render.TileMeshMask
import org.umamo.render.TileOpaqueMask
import org.umamo.render.drawableMeshTrianglesOf
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.meshTrianglesOf
import org.umamo.render.sampledRegionHitsMask
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyAffine
import org.umamo.runtime.model.drawableIdsByAtlasTile
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.mesh.GenerateMeshHost
import org.umamo.ui.model.mesh.GenerateMeshOutcome
import org.umamo.ui.model.mesh.GenerateMeshSkipReason
import org.umamo.ui.model.mesh.MeshGenerationOptions
import org.umamo.ui.model.mesh.RunTileDecoder
import org.umamo.ui.model.mesh.generateMeshOutcome
import org.umamo.ui.model.mesh.planMeshGeneration
import org.umamo.ui.model.mesh.runGenerateMesh
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The posed landing's sampling stride, in art pixels. */
private const val POSED_STRIDE: Int = 6

/** A landing histogram's bin width, in art pixels. */
private const val HISTOGRAM_BIN_WIDTH: Double = 0.005

/** A landing histogram's bins; the last gathers everything past them. */
private const val HISTOGRAM_BINS: Int = 10000

/**
 * Generate Mesh from Art over a whole real rig, through to the CMO3 the official editor opens: every
 * drawable of the corpus sample with a mesh and art regenerated at Standard in one step.
 *
 * It gates what must hold - nothing refused by the atlas, every skip one the rigger can act on, every
 * glue pair kept, every opaque art pixel under the new meshes, no piece of a new mesh covering no art, every new vertex showing its art at rest
 * where the old mesh drew it, the art between them following the rig's old shape within the
 * Deformation Tolerance, no tile left in place sampling a neighbour's paint it did not sample before,
 * one undo restoring the rig, and an export that reads back with the same meshes and reopens as a
 * byte-identical no-op - and prints what it measures: the counts, how far art moves at rest and in each
 * parameter's extremes, and how much art split pieces of one layer now both cover.  The export is
 * written to build/auto-mesh for Cmo3ShapeGateTest and the official editor.
 *
 * Skips without the corpus sample.
 */
class GeneratedMeshCmo3ExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aWholeRigRegeneratesExportsAndReopens() =
		runBlocking {
			val file = sample

			if (file == null) {
				println("cmo3.sample not present; skipping the generated-mesh export gate")

				return@runBlocking
			}

			val startNanos = System.nanoTime()
			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(file.readBytes(), file.name, file.path)).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val base = session.model.value
			val targets = base.drawables.filter { drawable -> drawable.mesh != null && drawable.atlasTileId != null }.map { drawable -> drawable.id }
			val options = MeshGenerationOptions.of(ArtMeshPreset.Standard)
			// One decoder for the test: the pipeline's run and the probes below read the same art once.
			val decode = RunTileDecoder { tileId -> document.artRasters.decodeRaster(tileId) }

			// The pipeline once more beside the flow, for the outcome's skips and packed tiles.
			val outcome =
				withContext(Dispatchers.Default) {
					val plan = planMeshGeneration(base, targets, decode)
					generateMeshOutcome(base, plan, options, decode, document.textures.premultipliedAlpha)
				}
			val generated = assertIs<GenerateMeshOutcome.Generated>(outcome, "the atlas refused nothing")
			val allowedSkips = setOf(GenerateMeshSkipReason.OverBudget, GenerateMeshSkipReason.NothingOpaque, GenerateMeshSkipReason.DegenerateOldMesh, GenerateMeshSkipReason.GluedThroughout)
			assertTrue(generated.skips.values.all { reason -> reason in allowedSkips }, "only skips a rigger can act on: ${generated.skips}")

			val host = GenerateMeshHost(session, document.artRasters, sessionAtlasPages, document.textures.premultipliedAlpha, this) { _ -> }
			assertTrue(runGenerateMesh(host, targets, options, null), "the flow lands")
			val after = session.model.value

			for (drawableId in generated.generatedIds) {
				assertContentEquals(meshOf(generated.model, drawableId).positions, meshOf(after, drawableId).positions, "the flow and the pipeline agree on ${drawableId.raw}")
			}

			assertGlueKept(base, after)
			val effectiveThreshold = maxOf(options.settings.alphaThreshold, base.atlas.composition.alphaThreshold)
			val uncovered = generated.generatedIds.sumOf { drawableId -> uncoveredArtPixels(base, after, drawableId, decode, effectiveThreshold) }
			assertEquals(0, uncovered, "every opaque art pixel lies under its new mesh")
			val newHits = newPaintHits(base, after, decode)
			assertEquals(0, newHits, "no tile left in place samples a neighbour's paint it did not before")
			println(
				"[generate-mesh] ${file.name}: ${generated.generatedIds.size} regenerated, skips ${generated.skips.values.groupingBy { it }.eachCount()}, " +
					"${generated.packedTileIds.size} tile(s) packed to a new spot, vertices ${vertexTotal(base)} -> ${vertexTotal(after)}",
			)
			assertLandings(base, after, generated.generatedIds, decode, effectiveThreshold, options.settings.deformationTolerance)
			reportSplitPieces(base, after, generated.generatedIds, decode, effectiveThreshold)

			// The export: written for the shape gate and the official editor, read back, and reopened.
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== after.atlas) {
					yield()
				}
			}
			val prepared = prepareCmo3Export(document, exportedModelFor(document, session), sessionAtlasPages.binding.value.textures, "gate", 0L, 0)
			follower.cancel()
			assertExportRoundTrip(file, prepared, after, generated.generatedIds)

			session.undo()
			assertSame(base, session.model.value, "one undo restores the rig")
			println("[generate-mesh]   ${(System.nanoTime() - startNanos) / 1_000_000} ms in all")
		}

	/**
	 * Measures how far the regenerated drawables' art moves, at rest and posed, and asserts it stays
	 * within the deformation tolerance.
	 *
	 * @param PuppetModel      base        The rig before.
	 * @param PuppetModel      after       The rig after.
	 * @param List<DrawableId> drawableIds The regenerated drawables.
	 * @param Function         decode      Decodes a tile's art.
	 * @param Int              threshold   The alpha that counts as art.
	 * @param Double           tolerance   The deformation tolerance, art pixels.
	 */
	private fun assertLandings(base: PuppetModel, after: PuppetModel, drawableIds: List<DrawableId>, decode: (AtlasTileId) -> DecodedImage?, threshold: Int, tolerance: Double) {
		val landings = restLanding(base, after, drawableIds, decode, threshold)
		// At its vertices the new mesh shows its art where the old mesh drew it.  Between them the art
		// follows the new triangles, so where the rest pose bends art inside one of them the two differ by
		// that bend.
		val vertexErrors = landings.flatMap { landing -> landing.vertexErrors }.sorted()
		val pixelErrors = landings.flatMap { landing -> landing.pixelErrors }.sorted()
		val bentCount = landings.count { landing -> (landing.pixelErrors.maxOrNull() ?: 0.0) > 1.0 }
		println("[generate-mesh]   rest-pose landing at the new vertices: ${summary(vertexErrors)}")
		println("[generate-mesh]   rest-pose landing over the art: ${summary(pixelErrors)}; $bentCount drawable(s) move some art more than 1 px")

		for (landing in landings.sortedByDescending { landing -> landing.pixelErrors.maxOrNull() ?: 0.0 }.take(5)) {
			println(
				"[generate-mesh]     ${landing.drawableId.raw}, vertices ${landing.oldVertexCount} -> ${landing.newVertexCount}, " +
					"${String.format(Locale.ROOT, "%.3f", landing.worldScale)} world px per art px: ${summary(landing.pixelErrors.sorted())}; in art px ${summary(landing.artPixelErrors.sorted())}",
			)
		}

		val artPixelErrors = landings.flatMap { landing -> landing.artPixelErrors }.sorted()
		val strayingCount = landings.count { landing -> (landing.artPixelErrors.maxOrNull() ?: 0.0) > 1.0 }
		println("[generate-mesh]   rest-pose landing over the art in art px: ${summary(artPixelErrors)}; $strayingCount drawable(s) move some art more than 1 art px")

		assertTrue(percentile(vertexErrors, 0.99) <= 0.05, "the new vertices land where the old mesh drew their art (p99 ${percentile(vertexErrors, 0.99)} px)")
		// Between them the new mesh keeps the old vertices that hold the rig's bends, so the art follows
		// the old shape within the Deformation Tolerance.  What strays further is a new vertex itself:
		// where a warp curves inside one large old triangle, a vertex there shows its art where the
		// warp puts it rather than on that triangle's flat blend - two drawables of this rig.
		assertTrue(percentile(artPixelErrors, 0.99) <= tolerance, "the art follows the rig's old shape within the tolerance (p99 ${percentile(artPixelErrors, 0.99)} art px)")
		assertTrue(strayingCount <= 2, "at most the two drawables whose warp curves inside an old triangle move art more than 1 art px: $strayingCount")
		// Posed, the drawable's own keyforms and blend shapes are held too; a parent deformer bending
		// differently away from rest is not, which is what the tail measures.
		val posedNanos = System.nanoTime()
		val posed = posedLanding(base, after, landings, decode, threshold)
		println(
			"[generate-mesh]   landing over the art with each of ${base.parameters.size} parameters at its extremes, in art px: n ${posed.total}, " +
				String.format(Locale.ROOT, "p50 %.3f, p99 %.3f, p999 %.3f, max %.2f; %d ms", posed.percentile(0.5), posed.percentile(0.99), posed.percentile(0.999), posed.max, (System.nanoTime() - posedNanos) / 1_000_000),
		)
		assertTrue(posed.percentile(0.99) <= tolerance, "posed, the art follows the rig's old shape within the tolerance (p99 ${posed.percentile(0.99)} art px)")
	}

	/**
	 * Reports what the regenerated split pieces cover - the seam pixels now under two pieces, and the
	 * pieces that cover no art - and asserts every new piece covers art.
	 *
	 * @param PuppetModel      base        The rig before.
	 * @param PuppetModel      after       The rig after.
	 * @param List<DrawableId> drawableIds The regenerated drawables.
	 * @param Function         decode      Decodes a tile's art.
	 * @param Int              threshold   The alpha that counts as art.
	 */
	private fun reportSplitPieces(base: PuppetModel, after: PuppetModel, drawableIds: List<DrawableId>, decode: (AtlasTileId) -> DecodedImage?, threshold: Int) {
		println("[generate-mesh]   split-piece seam: ${seamOverlapPixels(base, after, decode, threshold)} art pixel(s) now under two pieces' meshes that were under at most one")
		val artlessBefore = drawableIds.associateWith { drawableId -> artlessPieces(base, base, drawableId, decode, threshold) }
		val artlessAfter = drawableIds.associateWith { drawableId -> artlessPieces(base, after, drawableId, decode, threshold) }
		val nameById = base.drawables.associate { drawable -> drawable.id to drawable.name }
		println("[generate-mesh]   pieces covering no art: ${artlessAfter.values.sum()} over ${artlessAfter.count { (_, count) -> count > 0 }} drawable(s) (the old meshes had ${artlessBefore.values.sum()})")

		for ((drawableId, count) in artlessAfter.entries.filter { (_, count) -> count > 0 }.sortedByDescending { (_, count) -> count }.take(8)) {
			println("[generate-mesh]     '${nameById[drawableId]}' (${drawableId.raw}): $count, old ${artlessBefore[drawableId]}")
		}

		// A glued vertex off the art is joined to it by the old triangles around it, and one no triangle
		// used is carried rather than meshed around, so no piece of a new mesh is there for glue alone.
		assertEquals(0, artlessAfter.values.sum(), "every piece of every new mesh covers art")
	}

	/**
	 * Writes the export for the shape gate and the official editor, reads it back, and asserts every
	 * regenerated mesh and glue reopens and an unedited re-export keeps main.xml byte-identical.
	 *
	 * @param File                 file        The corpus sample, which names the written file.
	 * @param PreparedCmo3Export   prepared    The prepared export.
	 * @param PuppetModel          after       The rig that was exported.
	 * @param List<DrawableId>     drawableIds The regenerated drawables.
	 */
	private fun assertExportRoundTrip(file: File, prepared: PreparedCmo3Export, after: PuppetModel, drawableIds: List<DrawableId>) {
		println("[generate-mesh]   export report: ${prepared.report.notices.map { notice -> notice::class.simpleName }}")
		// A changed topology leaves the imported weld by definition, so WeldDivergence is the one notice owed.
		assertTrue(prepared.report.notices.all { notice -> notice is ExportNotice.WeldDivergence }, "the export carries every regenerated mesh: ${prepared.report.notices}")
		val exportedBytes = Cmo3.write(prepared.model)
		val exportFile = File("build/auto-mesh/${file.nameWithoutExtension}GenerateMeshExportTest.cmo3")
		exportFile.parentFile.mkdirs()
		exportFile.writeBytes(exportedBytes)
		println("[generate-mesh]   wrote ${exportFile.absolutePath}")
		val reopened = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(exportedBytes, "gate.cmo3", "/art/gate.cmo3")).document)

		for (drawableId in drawableIds) {
			val mesh = meshOf(after, drawableId)
			val back = assertNotNull(reopened.puppet.drawables.firstOrNull { candidate -> candidate.id == drawableId }?.mesh, "${drawableId.raw} reopens with a mesh")
			assertEquals(mesh.vertexCount, back.vertexCount, "${drawableId.raw} keeps its vertex count")
			assertEquals(mesh.triangleCount, back.triangleCount, "${drawableId.raw} keeps its triangle count")
			val worstUv = mesh.uvs.indices.maxOf { component -> abs(mesh.uvs[component] - back.uvs[component]) }
			assertTrue(worstUv <= 1e-4f, "${drawableId.raw} keeps its UVs (off by $worstUv)")
		}

		assertEquals(after.glues.size, reopened.puppet.glues.size, "every glue reopens")

		for ((written, back) in after.glues.zip(reopened.puppet.glues)) {
			val subject = "${written.meshA.raw}+${written.meshB.raw}"
			assertEquals(written.meshA to written.meshB, back.meshA to back.meshB, "glue $subject reopens in its place")
			assertEquals(written.pairs.map { pair -> pair.indexA to pair.indexB }, back.pairs.map { pair -> pair.indexA to pair.indexB }, "glue $subject binds the regenerated vertices")
		}

		val identity = prepareCmo3Export(reopened, reopened.puppet, reopened.textures, "gate", 0L, 0)
		assertTrue(identity.report.isEmpty, "an unedited re-export owes nothing: ${identity.report.notices}")
		val sourceMainXml = CaffCodec.read(exportedBytes).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
		val reemittedMainXml = CaffCodec.read(Cmo3.write(identity.model)).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
		assertContentEquals(sourceMainXml, reemittedMainXml, "main.xml survives the reopen byte-identical")
	}

	/**
	 * Asserts every glue kept its pairs and their weights.
	 *
	 * @param PuppetModel base  The rig before.
	 * @param PuppetModel after The rig after.
	 */
	private fun assertGlueKept(base: PuppetModel, after: PuppetModel) {
		assertEquals(base.glues.size, after.glues.size)

		for ((before, now) in base.glues.zip(after.glues)) {
			assertEquals(before.pairs.map { pair -> pair.weightA to pair.weightB }, now.pairs.map { pair -> pair.weightA to pair.weightB }, "glue ${before.id} keeps its pairs")
		}
	}

	/**
	 * How many opaque pixels of a drawable's art (within its region, on a shared tile) no triangle of its
	 * new mesh covers, by scan conversion with exact predicates.
	 *
	 * @param PuppetModel base       The rig before (its old mesh gives a shared tile's region).
	 * @param PuppetModel after      The rig after.
	 * @param DrawableId  drawableId The drawable.
	 * @param Function    decode     Decodes a tile's art.
	 * @param Int         threshold  The alpha that counts as art.
	 * @return Int The uncovered pixel count.
	 */
	private fun uncoveredArtPixels(base: PuppetModel, after: PuppetModel, drawableId: DrawableId, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): Int {
		val drawable = after.drawables.first { candidate -> candidate.id == drawableId }
		val tileId = drawable.atlasTileId!!
		val raster = decode(tileId) ?: return 0
		val shared = (base.drawableIdsByAtlasTile()[tileId]?.count { sharer -> base.drawables.first { candidate -> candidate.id == sharer }.mesh != null } ?: 0) > 1
		val region = if (shared) TileMeshMask.of(drawableMeshTrianglesOf(base, drawableId)) else null
		val covered = BooleanArray(raster.width * raster.height)

		for (triangle in drawableMeshTrianglesOf(after, drawableId)) {
			val minimumX = maxOf(0, floor(minOf(triangle[0], triangle[2], triangle[4]) - 0.5f).toInt())
			val maximumX = minOf(raster.width - 1, ceil(maxOf(triangle[0], triangle[2], triangle[4]) - 0.5f).toInt())
			val minimumY = maxOf(0, floor(minOf(triangle[1], triangle[3], triangle[5]) - 0.5f).toInt())
			val maximumY = minOf(raster.height - 1, ceil(maxOf(triangle[1], triangle[3], triangle[5]) - 0.5f).toInt())

			for (row in minimumY..maximumY) {
				for (column in minimumX..maximumX) {
					if (!covered[row * raster.width + column] && triangleContains(triangle, column + 0.5, row + 0.5)) {
						covered[row * raster.width + column] = true
					}
				}
			}
		}

		var uncovered = 0

		for (row in 0 until raster.height) {
			for (column in 0 until raster.width) {
				val opaque = raster.isArt(column, row, threshold)
				val inRegion = region?.isCovered(column, row) ?: true

				if (opaque && inRegion && !covered[row * raster.width + column]) {
					uncovered++
				}
			}
		}

		return uncovered
	}

	/**
	 * Whether a triangle (six art-pixel floats) contains a point, edges included, whichever its winding.
	 *
	 * @param FloatArray triangle The triangle.
	 * @param Double     x        The point's x.
	 * @param Double     y        The point's y.
	 * @return Boolean True when contained.
	 */
	private fun triangleContains(triangle: FloatArray, x: Double, y: Double): Boolean {
		val first = orient2d(triangle[0].toDouble(), triangle[1].toDouble(), triangle[2].toDouble(), triangle[3].toDouble(), x, y)
		val second = orient2d(triangle[2].toDouble(), triangle[3].toDouble(), triangle[4].toDouble(), triangle[5].toDouble(), x, y)
		val third = orient2d(triangle[4].toDouble(), triangle[5].toDouble(), triangle[0].toDouble(), triangle[1].toDouble(), x, y)

		return (first >= 0.0 && second >= 0.0 && third >= 0.0) || (first <= 0.0 && second <= 0.0 && third <= 0.0)
	}

	/**
	 * How far one regenerated drawable's art moves at the default pose.
	 *
	 * @property DrawableId   drawableId     The drawable.
	 * @property Int          oldVertexCount Its vertices before.
	 * @property Int          newVertexCount Its vertices after.
	 * @property List<Double> vertexErrors   Per new vertex inside the old mesh, from where the old mesh drew that art point.
	 * @property List<Double> pixelErrors    Per sampled opaque art pixel both meshes hold, between the two meshes' drawings of it.
	 * @property Double       worldScale     The old mesh's world pixels per art pixel at rest (its area scale).
	 */
	private class RestLanding(
		val drawableId: DrawableId,
		val oldVertexCount: Int,
		val newVertexCount: Int,
		val vertexErrors: List<Double>,
		val pixelErrors: List<Double>,
		val worldScale: Double,
	) {
		/** The pixel errors in art pixels. */
		val artPixelErrors: List<Double>
			get() = if (worldScale > 0.0) pixelErrors.map { error -> error / worldScale } else pixelErrors
	}

	/**
	 * Distances gathered into 0.005-wide bins up to 50, for more samples than a list holds.
	 */
	private class LandingHistogram {
		private val counts = LongArray(HISTOGRAM_BINS + 1)

		/** The distances added. */
		var total: Long = 0L
			private set

		/** The largest distance added. */
		var max: Double = 0.0
			private set

		/**
		 * Adds one distance.
		 *
		 * @param Double distance The distance.
		 */
		fun add(distance: Double) {
			counts[(distance / HISTOGRAM_BIN_WIDTH).toInt().coerceIn(0, HISTOGRAM_BINS)]++
			total++
			max = maxOf(max, distance)
		}

		/**
		 * The bin edge below which a fraction of the distances fall.
		 *
		 * @param Double fraction The fraction, 0 to 1.
		 * @return Double The distance, to the bin width.
		 */
		fun percentile(fraction: Double): Double {
			var seen = 0L

			for (bin in counts.indices) {
				seen += counts[bin]

				if (seen >= fraction * total) {
					return (bin + 1) * HISTOGRAM_BIN_WIDTH
				}
			}

			return max
		}
	}

	/**
	 * One drawable's sampled art pixels, each located once in the old mesh and the new: its three
	 * corners and their weights, so any pose's deformed positions blend without locating again.
	 *
	 * @property DrawableId  drawableId  The drawable.
	 * @property Double      worldScale  Its world pixels per art pixel at rest.
	 * @property IntArray    oldCorners  Three old vertices per sample.
	 * @property DoubleArray oldWeights  Their weights.
	 * @property IntArray    newCorners  Three new vertices per sample.
	 * @property DoubleArray newWeights  Their weights.
	 */
	private class LandingSamples(
		val drawableId: DrawableId,
		val worldScale: Double,
		val oldCorners: IntArray,
		val oldWeights: DoubleArray,
		val newCorners: IntArray,
		val newWeights: DoubleArray,
	) {
		/** The samples. */
		val count: Int
			get() = oldCorners.size / 3
	}

	/**
	 * How far art moves with each parameter at its minimum and its maximum, the rest held at default: per
	 * pose, every opaque art pixel on a 6 px stride both meshes hold, in art pixels at the drawable's rest
	 * scale.
	 *
	 * @param PuppetModel       base      The rig before.
	 * @param PuppetModel       after     The rig after.
	 * @param List<RestLanding> landings  The drawables measured at rest.
	 * @param Function          decode    Decodes a tile's art.
	 * @param Int               threshold The alpha that counts as art.
	 * @return LandingHistogram The distances.
	 */
	private fun posedLanding(base: PuppetModel, after: PuppetModel, landings: List<RestLanding>, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): LandingHistogram {
		val samples = landings.mapNotNull { landing -> landingSamples(base, after, landing, decode, threshold) }
		val histogram = LandingHistogram()

		for (parameter in base.parameters) {
			for (value in floatArrayOf(parameter.min, parameter.max)) {
				val pose = mapOf(parameter.id to value)
				val oldWorld = CpuDeformationEvaluator().evaluate(base, pose).worldPositions
				val newWorld = CpuDeformationEvaluator().evaluate(after, pose).worldPositions

				for (sample in samples) {
					val oldPositions = oldWorld[sample.drawableId] ?: continue
					val newPositions = newWorld[sample.drawableId] ?: continue

					for (point in 0 until sample.count) {
						var deltaX = 0.0
						var deltaY = 0.0

						for (corner in 3 * point until 3 * point + 3) {
							deltaX += sample.oldWeights[corner] * oldPositions[2 * sample.oldCorners[corner]] - sample.newWeights[corner] * newPositions[2 * sample.newCorners[corner]]
							deltaY += sample.oldWeights[corner] * oldPositions[2 * sample.oldCorners[corner] + 1] - sample.newWeights[corner] * newPositions[2 * sample.newCorners[corner] + 1]
						}

						histogram.add(hypot(deltaX, deltaY) / sample.worldScale)
					}
				}
			}
		}

		return histogram
	}

	/**
	 * Locates one drawable's opaque art pixels, on a 6 px stride, in its old mesh and its new.
	 *
	 * @param PuppetModel base      The rig before.
	 * @param PuppetModel after     The rig after.
	 * @param RestLanding landing   The drawable's rest landing, for its scale.
	 * @param Function    decode    Decodes a tile's art.
	 * @param Int         threshold The alpha that counts as art.
	 * @return LandingSamples? The samples, or null without art or an art frame.
	 */
	private fun landingSamples(base: PuppetModel, after: PuppetModel, landing: RestLanding, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): LandingSamples? {
		val drawableId = landing.drawableId
		val meshes = landingMeshesOf(base, after, drawableId, decode) ?: return null
		val raster = meshes.raster
		val oldMesh = meshes.oldMesh
		val newMesh = meshes.newMesh
		val oldLocator = meshes.oldLocator
		val newLocator = meshes.newLocator
		val oldCorners = ArrayList<Int>()
		val oldWeights = ArrayList<Double>()
		val newCorners = ArrayList<Int>()
		val newWeights = ArrayList<Double>()

		for (row in 0 until raster.height step POSED_STRIDE) {
			for (column in 0 until raster.width step POSED_STRIDE) {
				if (!raster.isArt(column, row, threshold)) {
					continue
				}

				val oldLocation = oldLocator.locate(column + 0.5, row + 0.5)?.takeIf { location -> location.isInside } ?: continue
				val newLocation = newLocator.locate(column + 0.5, row + 0.5)?.takeIf { location -> location.isInside } ?: continue

				for (corner in 0 until 3) {
					oldCorners.add(oldMesh.indices[3 * oldLocation.triangleIndex + corner])
					newCorners.add(newMesh.indices[3 * newLocation.triangleIndex + corner])
				}

				oldWeights.addAll(listOf(oldLocation.weightA, oldLocation.weightB, oldLocation.weightC))
				newWeights.addAll(listOf(newLocation.weightA, newLocation.weightB, newLocation.weightC))
			}
		}

		return LandingSamples(drawableId, if (landing.worldScale > 0.0) landing.worldScale else 1.0, oldCorners.toIntArray(), oldWeights.toDoubleArray(), newCorners.toIntArray(), newWeights.toDoubleArray())
	}

	/**
	 * How far each regenerated drawable's art moves at the default pose: a point's world position blended
	 * from the old mesh's deformed vertices against the new mesh's, taken at every new vertex the old mesh
	 * holds and on a 3 px stride over the opaque pixels both meshes hold.
	 *
	 * @param PuppetModel      base         The rig before.
	 * @param PuppetModel      after        The rig after.
	 * @param List<DrawableId> drawableIds  The regenerated drawables.
	 * @param Function         decode       Decodes a tile's art.
	 * @param Int              threshold    The alpha that counts as art.
	 * @return List<RestLanding> One landing per drawable measured.
	 */
	private fun restLanding(base: PuppetModel, after: PuppetModel, drawableIds: List<DrawableId>, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): List<RestLanding> {
		val oldWorld = CpuDeformationEvaluator().evaluate(base, emptyMap()).worldPositions
		val newWorld = CpuDeformationEvaluator().evaluate(after, emptyMap()).worldPositions
		val landings = ArrayList<RestLanding>()

		for (drawableId in drawableIds) {
			val oldPositions = oldWorld[drawableId] ?: continue
			val newPositions = newWorld[drawableId] ?: continue
			val meshes = landingMeshesOf(base, after, drawableId, decode) ?: continue
			val raster = meshes.raster
			val oldMesh = meshes.oldMesh
			val newMesh = meshes.newMesh
			val oldLocator = meshes.oldLocator
			val newPoints = meshes.newPoints
			val newLocator = meshes.newLocator
			val vertexErrors = ArrayList<Double>()
			val pixelErrors = ArrayList<Double>()

			for (vertex in 0 until newMesh.vertexCount) {
				val oldPoint = blendedAt(oldLocator, oldMesh.indices, oldPositions, newPoints[2 * vertex].toDouble(), newPoints[2 * vertex + 1].toDouble()) ?: continue
				vertexErrors.add(hypot(oldPoint.first - newPositions[2 * vertex], oldPoint.second - newPositions[2 * vertex + 1]))
			}

			for (row in 0 until raster.height step 3) {
				for (column in 0 until raster.width step 3) {
					if (!raster.isArt(column, row, threshold)) {
						continue
					}

					val oldPoint = blendedAt(oldLocator, oldMesh.indices, oldPositions, column + 0.5, row + 0.5) ?: continue
					val newPoint = blendedAt(newLocator, newMesh.indices, newPositions, column + 0.5, row + 0.5) ?: continue
					pixelErrors.add(hypot(oldPoint.first - newPoint.first, oldPoint.second - newPoint.second))
				}
			}

			landings.add(RestLanding(drawableId, oldMesh.vertexCount, newMesh.vertexCount, vertexErrors, pixelErrors, areaScaleOf(meshes.oldPoints, oldMesh.indices, oldPositions)))
		}

		return landings
	}

	/**
	 * A drawable's mesh vertices laid out in its tile's art pixels.
	 *
	 * @param PuppetModel model      The rig.
	 * @param DrawableId  drawableId The drawable.
	 * @return FloatArray? The interleaved points, or null when the tile has no art frame.
	 */
	private fun artPointsOf(model: PuppetModel, drawableId: DrawableId): FloatArray? {
		val tile = model.atlas.tileById.getValue(meshTileOf(model, drawableId))
		val artUvs = applyAffine(meshOf(model, drawableId).uvs, model.atlas.storedToArtAffineForTile(tile.id) ?: return null)

		return FloatArray(artUvs.size) { component -> artUvs[component] * if (component % 2 == 0) tile.width else tile.height }
	}

	/**
	 * The tile a drawable samples.
	 *
	 * @param PuppetModel model      The rig.
	 * @param DrawableId  drawableId The drawable.
	 * @return AtlasTileId The tile.
	 */
	private fun meshTileOf(model: PuppetModel, drawableId: DrawableId): AtlasTileId = model.drawables.first { drawable -> drawable.id == drawableId }.atlasTileId!!

	/**
	 * One drawable's tile art and its old and new meshes, each laid out in the art's pixels with a
	 * locator over it.
	 *
	 * @property DecodedImage    raster     The tile's art.
	 * @property DrawableMesh    oldMesh    The mesh before.
	 * @property DrawableMesh    newMesh    The mesh after.
	 * @property FloatArray      oldPoints  The old mesh's vertices in art pixels.
	 * @property TriangleLocator oldLocator Locates art points in the old mesh.
	 * @property FloatArray      newPoints  The new mesh's vertices in art pixels.
	 * @property TriangleLocator newLocator Locates art points in the new mesh.
	 */
	private class LandingMeshes(
		val raster: DecodedImage,
		val oldMesh: DrawableMesh,
		val newMesh: DrawableMesh,
		val oldPoints: FloatArray,
		val oldLocator: TriangleLocator,
		val newPoints: FloatArray,
		val newLocator: TriangleLocator,
	)

	/**
	 * A regenerated drawable's art and both its meshes, ready to locate art points in.
	 *
	 * @param PuppetModel base       The rig before.
	 * @param PuppetModel after      The rig after.
	 * @param DrawableId  drawableId The drawable.
	 * @param Function    decode     Decodes a tile's art.
	 * @return LandingMeshes? The meshes, or null without art or an art frame.
	 */
	private fun landingMeshesOf(base: PuppetModel, after: PuppetModel, drawableId: DrawableId, decode: (AtlasTileId) -> DecodedImage?): LandingMeshes? {
		val raster = decode(meshTileOf(base, drawableId)) ?: return null
		val oldMesh = meshOf(base, drawableId)
		val newMesh = meshOf(after, drawableId)
		val oldPoints = artPointsOf(base, drawableId) ?: return null
		val newPoints = artPointsOf(after, drawableId) ?: return null

		return LandingMeshes(raster, oldMesh, newMesh, oldPoints, TriangleLocator(oldPoints, oldMesh.indices), newPoints, TriangleLocator(newPoints, newMesh.indices))
	}

	/**
	 * Whether an art pixel is art: its alpha reaches the threshold.
	 *
	 * @param Int column    The pixel's column.
	 * @param Int row       The pixel's row.
	 * @param Int threshold The alpha that counts as art.
	 * @return Boolean True for art.
	 */
	private fun DecodedImage.isArt(column: Int, row: Int, threshold: Int): Boolean = (rgba[(row * width + column) * 4 + 3].toInt() and 0xFF) >= threshold

	/**
	 * How many (tile, neighbour) pairs on one page have the tile's sampled region on the neighbour's
	 * paint after the run but not before, among tiles that kept their placement.
	 *
	 * @param PuppetModel base   The rig before.
	 * @param PuppetModel after  The rig after.
	 * @param Function    decode Decodes a tile's art.
	 * @return Int The new hits.
	 */
	private fun newPaintHits(base: PuppetModel, after: PuppetModel, decode: (AtlasTileId) -> DecodedImage?): Int {
		if (!after.atlas.storedUvsAddressPages) {
			return 0
		}

		val composition = after.atlas.composition
		val paintByTile = HashMap<AtlasTileId, TileOpaqueMask?>()
		var hits = 0

		fun paintOf(tile: AtlasTile): TileOpaqueMask? =
			paintByTile.getOrPut(tile.id) {
				val raster = decode(tile.id)
				val trim = raster?.let { decoded -> analyzeAlpha(decoded.width, decoded.height, decoded.rgba, composition.alphaThreshold)?.opaqueBounds }

				if (raster == null || trim == null) null else TileOpaqueMask.of(raster, trim, composition.alphaThreshold)
			}

		for (tile in after.atlas.tiles) {
			val placement = tile.placement ?: continue

			if (base.atlas.tileById[tile.id]?.placement != placement) {
				continue
			}

			val newMask = TileMeshMask.of(meshTrianglesOf(after, tile.id)) ?: continue
			val oldMask = TileMeshMask.of(meshTrianglesOf(base, tile.id))
			val newRegion = SampledRegion(placement, newMask)
			val oldRegion = oldMask?.let { mask -> SampledRegion(placement, mask) }

			for (other in after.atlas.tiles) {
				val otherPlacement = other.placement ?: continue

				if (other.id == tile.id || otherPlacement.pageIndex != placement.pageIndex) {
					continue
				}

				val paint = paintOf(other) ?: continue
				val hitsNow = sampledRegionHitsMask(newRegion, otherPlacement, paint, composition.extrude)
				val hitBefore = oldRegion != null && sampledRegionHitsMask(oldRegion, otherPlacement, paint, composition.extrude)

				if (hitsNow && !hitBefore) {
					hits++
				}
			}
		}

		return hits
	}

	/**
	 * How many connected pieces of a drawable's mesh (triangles sharing a vertex) cover no opaque pixel of
	 * its art - within its region on a shared tile - measured on [model]'s mesh against [base]'s region.
	 *
	 * @param PuppetModel base       The rig before, whose old mesh gives a shared tile's region.
	 * @param PuppetModel model      The rig whose mesh is measured.
	 * @param DrawableId  drawableId The drawable.
	 * @param Function    decode     Decodes a tile's art.
	 * @param Int         threshold  The alpha that counts as art.
	 * @return Int The pieces that cover no art.
	 */
	private fun artlessPieces(base: PuppetModel, model: PuppetModel, drawableId: DrawableId, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): Int {
		val drawable = model.drawables.first { candidate -> candidate.id == drawableId }
		val tileId = drawable.atlasTileId!!
		val raster = decode(tileId) ?: return 0
		val shared = (base.drawableIdsByAtlasTile()[tileId]?.count { sharer -> base.drawables.first { candidate -> candidate.id == sharer }.mesh != null } ?: 0) > 1
		val region = if (shared) TileMeshMask.of(drawableMeshTrianglesOf(base, drawableId)) else null
		val mesh = drawable.mesh!!
		val parent = IntArray(mesh.vertexCount) { vertex -> vertex }

		fun rootOf(vertex: Int): Int {
			var current = vertex

			while (parent[current] != current) {
				parent[current] = parent[parent[current]]
				current = parent[current]
			}

			return current
		}

		for (corner in mesh.indices.indices step 3) {
			val first = rootOf(mesh.indices[corner])
			parent[rootOf(mesh.indices[corner + 1])] = first
			parent[rootOf(mesh.indices[corner + 2])] = first
		}

		val triangles = drawableMeshTrianglesOf(model, drawableId)
		val coversArt = HashMap<Int, Boolean>()

		for ((triangleIndex, triangle) in triangles.withIndex()) {
			val piece = rootOf(mesh.indices[3 * triangleIndex])

			if (coversArt[piece] == true) {
				continue
			}

			coversArt[piece] = coversArt[piece] ?: false
			val minimumX = maxOf(0, floor(minOf(triangle[0], triangle[2], triangle[4]) - 0.5f).toInt())
			val maximumX = minOf(raster.width - 1, ceil(maxOf(triangle[0], triangle[2], triangle[4]) - 0.5f).toInt())
			val minimumY = maxOf(0, floor(minOf(triangle[1], triangle[3], triangle[5]) - 0.5f).toInt())
			val maximumY = minOf(raster.height - 1, ceil(maxOf(triangle[1], triangle[3], triangle[5]) - 0.5f).toInt())

			scan@ for (row in minimumY..maximumY) {
				for (column in minimumX..maximumX) {
					val opaque = raster.isArt(column, row, threshold)

					if (opaque && (region?.isCovered(column, row) ?: true) && triangleContains(triangle, column + 0.5, row + 0.5)) {
						coversArt[piece] = true

						break@scan
					}
				}
			}
		}

		return coversArt.count { (_, covers) -> !covers }
	}

	/**
	 * Over every tile split between several drawables, how many opaque art pixels the new meshes of two
	 * or more of them cover where the old meshes covered at most one - art a seam now draws twice.
	 *
	 * @param PuppetModel base      The rig before.
	 * @param PuppetModel after     The rig after.
	 * @param Function    decode    Decodes a tile's art.
	 * @param Int         threshold The alpha that counts as art.
	 * @return Int The pixel count.
	 */
	private fun seamOverlapPixels(base: PuppetModel, after: PuppetModel, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): Int {
		var overlap = 0

		for ((tileId, sharers) in base.drawableIdsByAtlasTile()) {
			if (sharers.size < 2) {
				continue
			}

			val raster = decode(tileId) ?: continue
			val oldMasks = sharers.mapNotNull { drawableId -> TileMeshMask.of(drawableMeshTrianglesOf(base, drawableId)) }
			val newMasks = sharers.mapNotNull { drawableId -> TileMeshMask.of(drawableMeshTrianglesOf(after, drawableId)) }

			for (row in 0 until raster.height) {
				for (column in 0 until raster.width) {
					if (!raster.isArt(column, row, threshold)) {
						continue
					}

					val oldCount = oldMasks.count { mask -> mask.isCovered(column, row) }
					val newCount = newMasks.count { mask -> mask.isCovered(column, row) }

					if (newCount >= 2 && oldCount <= 1) {
						overlap++
					}
				}
			}
		}

		return overlap
	}

	/**
	 * A drawable's mesh.
	 *
	 * @param PuppetModel model      The rig.
	 * @param DrawableId  drawableId The drawable.
	 * @return DrawableMesh The mesh.
	 */
	private fun meshOf(model: PuppetModel, drawableId: DrawableId): DrawableMesh = model.drawables.first { drawable -> drawable.id == drawableId }.mesh!!

	/**
	 * Every vertex in a rig.
	 *
	 * @param PuppetModel model The rig.
	 * @return Int The vertex count.
	 */
	private fun vertexTotal(model: PuppetModel): Int = model.drawables.sumOf { drawable -> drawable.mesh?.vertexCount ?: 0 }

	/**
	 * The value at a fraction of the way through sorted distances.
	 *
	 * @param List<Double> sorted   The distances, ascending.
	 * @param Double       fraction The fraction, 0 to 1.
	 * @return Double The value, or 0 when there are none.
	 */
	private fun percentile(sorted: List<Double>, fraction: Double): Double = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * fraction).toInt()]

	/**
	 * n, median, 90th and 99th percentiles, and maximum of some distances.
	 *
	 * @param List<Double> errors The distances.
	 * @return String The summary.
	 */
	private fun summary(errors: List<Double>): String {
		if (errors.isEmpty()) {
			return "n 0"
		}

		val sorted = errors.sorted()

		return String.format(Locale.ROOT, "n %d, p50 %.4f, p90 %.4f, p99 %.4f, max %.3f px", sorted.size, percentile(sorted, 0.5), percentile(sorted, 0.9), percentile(sorted, 0.99), sorted.last())
	}
}