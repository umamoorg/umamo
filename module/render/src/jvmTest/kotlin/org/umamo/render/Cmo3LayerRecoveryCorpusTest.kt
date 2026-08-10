package org.umamo.render

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.GVector2
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Validates the atlas-placement recovery convention against real files, which is what keeps a sign or
 * ordering error from passing unnoticed: a wrong rotation sign still round-trips (the synthetic tests
 * in LayerTexturesTest cannot catch it), and it is invisible on the axis-aligned majority - only the
 * corpus's genuinely rotated packings expose it.
 *
 * The check is the format's own documented composition (docs/format/CMO3.md §6): a model image is
 * upright canvas-space art, so `atlasLocalToCanvasTransform` composed with
 * `materialLocalToAtlasTransform` must reproduce `_materialLocalToCanvasTransform` exactly.  Reading
 * our AtlasPlacement out of the entry and pushing a point through [atlasPixelOf] must therefore land
 * where the file's own two affines say it lands.
 *
 * Gated on `-Dcmo3.probe` (the whole corpus, comma-separated); self-skips without it.
 */
class Cmo3LayerRecoveryCorpusTest {
	private companion object {
		/**
		 * How much of a drawable's recovered mapping must land on its layer's frame.  Generous, because
		 * an authored mesh reaching well past its art is normal; a mis-wired recovery overlaps by nothing
		 * at all, so anything short of total displacement still fails this decisively.
		 */
		const val MINIMUM_FRAME_OVERLAP = 0.25f

		/**
		 * How far past its art a mesh may reach before that counts against the overlap, in layer pixels.
		 * The auto-mesh rings outside the opaque region (CMO3.md §6 measures ~20px per side on Erica) and
		 * authored meshes extend further for deformation coverage.  A pixel quantity, deliberately: as a
		 * fraction the same margin is nothing on a 1021x1405 layer and everything on a 14x10 one.
		 */
		const val MESH_MARGIN_PIXELS = 32f
	}

	/**
	 * The fraction of a mapping's own bounding box that falls inside the layer's frame, that frame
	 * widened by [MESH_MARGIN_PIXELS] so a normal mesh margin does not read as a miss.
	 *
	 * @param FloatArray layerUvs The recovered layer-frame texture coordinates, interleaved (u, v).
	 * @param Int layerWidth The layer's width in pixels (converts the margin into this uv frame).
	 * @param Int layerHeight The layer's height in pixels.
	 * @return Float The overlapping fraction, 0 when the mapping misses the frame entirely.
	 */
	private fun frameOverlapFractionOf(layerUvs: FloatArray, layerWidth: Int, layerHeight: Int): Float {
		var minU = Float.MAX_VALUE
		var maxU = -Float.MAX_VALUE
		var minV = Float.MAX_VALUE
		var maxV = -Float.MAX_VALUE
		var componentIndex = 0
		while (componentIndex + 1 < layerUvs.size) {
			minU = minOf(minU, layerUvs[componentIndex])
			maxU = maxOf(maxU, layerUvs[componentIndex])
			minV = minOf(minV, layerUvs[componentIndex + 1])
			maxV = maxOf(maxV, layerUvs[componentIndex + 1])
			componentIndex += 2
		}
		val spanU = maxU - minU
		val spanV = maxV - minV
		if (spanU <= 0f || spanV <= 0f) {
			return 0f
		}
		val marginU = MESH_MARGIN_PIXELS / layerWidth
		val marginV = MESH_MARGIN_PIXELS / layerHeight
		val insideU = minOf(maxU, 1f + marginU) - maxOf(minU, -marginU)
		val insideV = minOf(maxV, 1f + marginV) - maxOf(minV, -marginV)
		if (insideU <= 0f || insideV <= 0f) {
			return 0f
		}
		return (insideU * insideV) / (spanU * spanV)
	}

	private fun elements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	private fun corpusFiles(): List<File> =
		(System.getProperty("cmo3.probe") ?: "")
			.split(",")
			.map { path -> path.trim() }
			.filter { path -> path.isNotEmpty() }
			.map { path -> File(path) }
			.filter { file -> file.isFile }

	/** Applies a CMO3 affine (m00 m01 m02 / m10 m11 m12) to a point. */
	private fun applyAffine(affine: CAffine, x: Float, y: Float): FloatArray =
		floatArrayOf(
			affine.m00 * x + affine.m01 * y + affine.m02,
			affine.m10 * x + affine.m11 * y + affine.m12,
		)

	/** Our AtlasPlacement as read out of one atlas entry, matching cmo3LayerTextures' own reading. */
	private fun placementOf(entry: ModelImageEntry, pageIndex: Int): AtlasPlacement? {
		// CMO3: ModelImageEntry field materialLocalToAtlasTransform
		val transform = entry.materialLocalToAtlasTransform as? GTransform2 ?: return null
		val position = transform.position as? GVector2
		val scale = transform.scale as? GVector2
		return AtlasPlacement(
			pageIndex = pageIndex,
			positionX = position?.x ?: 0f,
			positionY = position?.y ?: 0f,
			scaleX = scale?.x ?: 1f,
			scaleY = scale?.y ?: 1f,
			rotationDegrees = transform.eulerAngle,
		)
	}

	/**
	 * The store builder end to end on real documents: an art-bearing model must surface an inventory,
	 * bind its drawables to it, and land each bound drawable's recovered mapping ON its layer's art.
	 *
	 * Overlap, not containment, is the invariant.  A mesh legitimately reaches past the art it samples -
	 * the auto-mesh rings outside the opaque region and an authored mesh often extends further still so
	 * deformation has coverage (the corpus's forearms reach ~140px past a 576x646 layer) - and the UV
	 * editor draws unclipped for exactly that reason.  What a mis-wired recovery does instead is land the
	 * mesh somewhere else entirely: applying a page-space placement to uvs that already address their art
	 * threw modelA's meshes thousands of pixels off, overlapping their layer not at all.
	 */
	@Test
	fun storeBindsCorpusDrawablesToArtTheirMappingsLandOn() {
		val files = corpusFiles()
		Assume.assumeTrue("[layer-store] no cmo3.probe corpus models", files.isNotEmpty())

		var filesWithLayers = 0
		var boundDrawables = 0
		var checkedMappings = 0
		var worstOverlap = 1f
		var missedMappings = 0
		val failures = mutableListOf<String>()
		for (file in files) {
			val model = Cmo3.read(file)
			val root = model.root as? CModelSource ?: continue
			val store = cmo3LayerTextures(root, model::extractLayerPng)
			if (store.isEmpty) {
				continue
			}
			filesWithLayers++
			val puppet = org.umamo.interop.cmo3.Cmo3Import.fromModelSource(root)
			for (drawable in puppet.drawables) {
				val binding = store.bindingsByDrawableId[drawable.id.raw] ?: continue
				boundDrawables++
				val layer = store.layerFor(binding.layerKey) ?: continue
				val uvs = drawable.mesh?.uvs ?: continue
				if (uvs.isEmpty()) {
					continue
				}
				val layerUvs = layerUvsFromAtlasUvs(uvs, binding, layer.width, layer.height) ?: continue
				checkedMappings++
				val overlap = frameOverlapFractionOf(layerUvs, layer.width, layer.height)
				worstOverlap = minOf(worstOverlap, overlap)
				if (overlap < MINIMUM_FRAME_OVERLAP) {
					missedMappings++
				}
				if (overlap < MINIMUM_FRAME_OVERLAP && failures.size < 10) {
					failures.add(
						"${file.name} ${drawable.id.raw} -> ${layer.name}: only $overlap of its mapping lands on the " +
							"layer (${layer.width}x${layer.height}) on a ${binding.pageWidth}x${binding.pageHeight} " +
							"page, placement=${binding.placement}",
					)
				}
			}
		}

		println(
			"[layer-store] $filesWithLayers art-bearing files, $boundDrawables bound drawables, " +
				"$checkedMappings mappings checked, $missedMappings below the $MINIMUM_FRAME_OVERLAP overlap bound, " +
				"least overlap $worstOverlap",
		)
		assertTrue(filesWithLayers > 0, "no corpus file surfaced any source layers; the builder walk is broken")
		assertTrue(boundDrawables > 0, "no drawable bound to a source layer; the drawable join is broken")
		assertTrue(checkedMappings > 0, "no uv mapping was checked; the recovery is untested against real meshes")
		assertTrue(failures.isEmpty(), "recovered mappings do not land on their layer:\n" + failures.joinToString("\n"))
	}

	@Test
	fun placementForwardMatchesTheFormatsOwnComposition() {
		val files = corpusFiles()
		Assume.assumeTrue("[layer-recovery] no cmo3.probe corpus models", files.isNotEmpty())

		// Sample points spanning a plausible layer extent, so a rotation error shows up as a mismatch
		// rather than cancelling at the origin.
		val samplePoints = listOf(0f to 0f, 137f to 0f, 0f to 251f, 137f to 251f)
		val sampleExtent = 251f
		// The composition cannot be exact: the entry's scale and the atlas-to-canvas fit are separately
		// rounded floats, so their product misses identity by a small relative epsilon that the art's own
		// extent multiplies up (modelA's worst case measures ~1e-3 relative).  A model image is upright
		// canvas-space art - its material-to-canvas is a pure translation - so layer units ARE canvas
		// units here and the bound is simply proportional to the sample extent.  This stays a razor-sharp
		// convention check regardless: a wrong rotation sign or operand order displaces a sample by the
		// extent itself (hundreds of units), three orders of magnitude past this.
		val tolerance = 0.05f + 2e-3f * sampleExtent
		var checkedEntries = 0
		var checkedRotated = 0
		var checkedScaled = 0
		var worstResidual = 0f
		var worstRotatedResidual = 0f
		val failures = mutableListOf<String>()

		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			val textureManager = root.textureManager as? CTextureManager ?: continue
			val modelImagesByGuid =
				elements(textureManager._modelImageGroups)
					.filterIsInstance<CModelImageGroup>()
					.flatMap { group -> elements(group._modelImages).filterIsInstance<CModelImage>() }
					.mapNotNull { image -> (image.guid as? Guid)?.uuid?.let { uuid -> uuid to image } }
					.toMap()

			for ((pageIndex, atlas) in elements(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>().withIndex()) {
				for (entry in elements(atlas.modelImages).filterIsInstance<ModelImageEntry>()) {
					val guid = (entry.modelImageGuid as? Guid)?.uuid ?: continue
					val modelImage = modelImagesByGuid[guid] ?: continue
					// CMO3: ModelImageEntry field atlasLocalToCanvasTransform / CModelImage field
					// _materialLocalToCanvasTransform - the two halves of the documented composition.
					val atlasToCanvas = entry.atlasLocalToCanvasTransform as? CAffine ?: continue
					val materialToCanvas = modelImage._materialLocalToCanvasTransform as? CAffine ?: continue
					val placement = placementOf(entry, pageIndex) ?: continue

					checkedEntries++
					if (placement.rotationDegrees != 0f) {
						checkedRotated++
					}
					if (placement.scaleX != 1f || placement.scaleY != 1f) {
						checkedScaled++
					}
					for ((layerX, layerY) in samplePoints) {
						val viaPlacement = atlasPixelOf(placement, layerX, layerY)
						val throughFile = applyAffine(atlasToCanvas, viaPlacement[0], viaPlacement[1])
						val expected = applyAffine(materialToCanvas, layerX, layerY)
						val residual =
							maxOf(
								kotlin.math.abs(throughFile[0] - expected[0]),
								kotlin.math.abs(throughFile[1] - expected[1]),
							)
						worstResidual = maxOf(worstResidual, residual)
						if (placement.rotationDegrees != 0f) {
							worstRotatedResidual = maxOf(worstRotatedResidual, residual)
						}
						if (residual > tolerance && failures.size < 10) {
							failures.add(
								"${file.name} guid=$guid angle=${placement.rotationDegrees} " +
									"scale=(${placement.scaleX}, ${placement.scaleY}) at ($layerX, $layerY): " +
									"placement->file (${throughFile[0]}, ${throughFile[1]}) vs " +
									"declared (${expected[0]}, ${expected[1]}), residual $residual",
							)
						}
					}
				}
			}
		}

		println(
			"[layer-recovery] composed $checkedEntries placements ($checkedRotated rotated, $checkedScaled scaled) " +
				"across ${files.size} corpus files; worst residual $worstResidual (rotated: $worstRotatedResidual), " +
				"tolerance $tolerance",
		)
		assertTrue(checkedEntries > 0, "no atlas placements were checked; the corpus walk is broken")
		// Non-vacuity: an axis-aligned-only run would pass under EITHER rotation sign, so the rotated
		// packings must actually be present for this test to mean anything.
		assertTrue(checkedRotated > 0, "no rotated placement was checked; the rotation convention is unverified")
		assertTrue(
			failures.isEmpty(),
			"placement composition disagrees with the file's own affines:\n" + failures.joinToString("\n"),
		)
		// The rotated packings are what the convention actually hinges on, so hold them to the same bound
		// explicitly rather than letting them hide inside an aggregate that the axis-aligned majority passes.
		assertTrue(
			worstRotatedResidual <= tolerance,
			"a rotated placement composes to $worstRotatedResidual, past the $tolerance bound",
		)
	}
}