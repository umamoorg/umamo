package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACTextureInput
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.GVector2
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Surveys the CMO3 layered-art web across the whole corpus, so the layer-view data model is pinned
 * against counted reality rather than assumption (see docs/format/CMO3.md §4).  Every quantitative
 * claim §4 and §6 make came from probes that no longer live in the tree; this is the one that stays.
 *
 * What it answers, per corpus file: how many drawables join a model image at all, how many model
 * images were ever packed, how many packings are rotated / scaled / mirrored (the cases a
 * rect-shaped placement type would silently lose), how many model images composite MORE than one
 * source layer (where "which layer is this drawable bound to" has no unique answer), and how many
 * texture inputs carry an ACTextureInput.optionalTransformOnCanvas at all (it is modeled but read
 * nowhere, and the corpus answer is all of them - whether any is non-identity, an unaccounted third
 * transform in the mapping chain, is what [characterizesTheOptionalCanvasTransform] settles).
 *
 * Corpus-gated: self-skips when no corpus is present.  The counts print; the assertions only guard
 * against a silently broken walk (a file with an atlas whose joins all resolve to nothing).
 */
class Cmo3LayerWebProbeTest {
	/** One file's survey. */
	private data class LayerWebSurvey(
		val fileName: String,
		val drawableCount: Int,
		val drawablesWithModelImage: Int,
		val modelImageCount: Int,
		val packedModelImages: Int,
		val atlasCount: Int,
		val rotatedPlacements: Int,
		val scaledPlacements: Int,
		val mirroredPlacements: Int,
		val fractionalPlacements: Int,
		val multiLayerModelImages: Int,
		val modelImagesWithoutPixels: Int,
		val optionalTransformsOnCanvas: Int,
	)

	private fun corpusDirectory(): File? {
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return corpus
			}
			directory = directory.parentFile
		}
		return null
	}

	private fun elements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	private fun artMeshes(root: CModelSource): List<CArtMeshSource> =
		elements((root.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()

	/** The model images pooled under the texture manager's groups. */
	private fun modelImagesOf(textureManager: CTextureManager?): List<CModelImage> =
		// CMO3: CTextureManager field _modelImageGroups -> CModelImageGroup field _modelImages
		elements(textureManager?._modelImageGroups)
			.filterIsInstance<CModelImageGroup>()
			.flatMap { group -> elements(group._modelImages).filterIsInstance<CModelImage>() }

	/** Every atlas placement record in the document, in atlas order. */
	private fun placementEntriesOf(textureManager: CTextureManager?): List<ModelImageEntry> =
		// CMO3: CTextureManager field _textureAtlases -> CTextureAtlas field modelImages
		elements(textureManager?._textureAtlases)
			.filterIsInstance<CTextureAtlas>()
			.flatMap { atlas -> elements(atlas.modelImages).filterIsInstance<ModelImageEntry>() }

	/** The layer inputs a model image composites; more than one means no unique source layer. */
	private fun layerInputsOf(modelImage: CModelImage): List<CLayerInputData> {
		// CMO3: CModelImage field inputFilterEnv -> CLayerSelectorMap field _imageToLayerInput
		val selectorMap = modelImage.inputFilterEnv as? CLayerSelectorMap ?: return emptyList()
		return elements(selectorMap._imageToLayerInput).flatMap { perImage ->
			elements(perImage).filterIsInstance<CLayerInputData>()
		}
	}

	private fun surveyOf(fileName: String, root: CModelSource): LayerWebSurvey {
		// CMO3: CModelSource field textureManager
		val textureManager = root.textureManager as? CTextureManager
		val modelImages = modelImagesOf(textureManager)
		val placements = placementEntriesOf(textureManager)
		val packedGuids =
			placements
				// CMO3: ModelImageEntry field modelImageGuid
				.mapNotNull { entry -> (entry.modelImageGuid as? Guid)?.uuid }
				.toSet()

		var drawablesWithModelImage = 0
		var optionalTransformsOnCanvas = 0
		for (mesh in artMeshes(root)) {
			// CMO3: CArtMeshSource field _extensions -> CTextureInputExtension field _textureInputs
			val extension = elements(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull()
			val inputs = elements(extension?._textureInputs)
			if (inputs.any { input -> input is CTextureInput_ModelImage }) {
				drawablesWithModelImage++
			}
			// CMO3: ACTextureInput field optionalTransformOnCanvas
			optionalTransformsOnCanvas +=
				inputs.count { input -> (input as? org.umamo.format.cmo3.model.gen.ACTextureInput)?.optionalTransformOnCanvas != null }
		}

		var rotated = 0
		var scaled = 0
		var mirrored = 0
		var fractional = 0
		for (entry in placements) {
			// CMO3: ModelImageEntry field materialLocalToAtlasTransform (GTransform2: position, scale, eulerAngle)
			val placement = entry.materialLocalToAtlasTransform as? GTransform2 ?: continue
			if (placement.eulerAngle != 0f) {
				rotated++
			}
			val scale = placement.scale as? GVector2
			if (scale != null) {
				if (scale.x < 0f || scale.y < 0f) {
					mirrored++
				}
				if (kotlin.math.abs(kotlin.math.abs(scale.x) - 1f) > 1e-4f || kotlin.math.abs(kotlin.math.abs(scale.y) - 1f) > 1e-4f) {
					scaled++
				}
			}
			val position = placement.position as? GVector2
			if (position != null && (position.x != kotlin.math.floor(position.x) || position.y != kotlin.math.floor(position.y))) {
				fractional++
			}
		}

		return LayerWebSurvey(
			fileName = fileName,
			drawableCount = artMeshes(root).size,
			drawablesWithModelImage = drawablesWithModelImage,
			modelImageCount = modelImages.size,
			// CMO3: CModelImage field guid joins the drawable's input to the atlas entry
			packedModelImages = modelImages.count { image -> (image.guid as? Guid)?.uuid in packedGuids },
			atlasCount = elements(textureManager?._textureAtlases).filterIsInstance<CTextureAtlas>().size,
			rotatedPlacements = rotated,
			scaledPlacements = scaled,
			mirroredPlacements = mirrored,
			fractionalPlacements = fractional,
			multiLayerModelImages = modelImages.count { image -> layerInputsOf(image).size > 1 },
			// CMO3: CModelImage field _filteredImage - the baked composite the layer view samples
			modelImagesWithoutPixels = modelImages.count { image -> image._filteredImage !is CImageResource },
			optionalTransformsOnCanvas = optionalTransformsOnCanvas,
		)
	}

	@Test
	fun surveysTheLayeredArtWebAcrossTheCorpus() {
		val corpus =
			corpusDirectory() ?: run {
				println("no test/corpus directory; skipping the layered-art web probe")
				return
			}
		val files = corpus.walkTopDown().filter { it.isFile && it.extension == "cmo3" }.sortedBy { it.name }.toList()
		if (files.isEmpty()) {
			println("no .cmo3 corpus samples; skipping the layered-art web probe")
			return
		}

		val surveys = mutableListOf<LayerWebSurvey>()
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			surveys.add(surveyOf(file.name, root))
		}

		println("CMO3 layered-art web survey (${surveys.size} files)")
		println("file | drawables (w/ model image) | model images (packed) | atlases | rot | scaled | mirrored | fractional | multi-layer | no pixels | optionalTransform")
		for (survey in surveys) {
			println(
				"${survey.fileName} | ${survey.drawableCount} (${survey.drawablesWithModelImage}) | " +
					"${survey.modelImageCount} (${survey.packedModelImages}) | ${survey.atlasCount} | " +
					"${survey.rotatedPlacements} | ${survey.scaledPlacements} | ${survey.mirroredPlacements} | " +
					"${survey.fractionalPlacements} | ${survey.multiLayerModelImages} | " +
					"${survey.modelImagesWithoutPixels} | ${survey.optionalTransformsOnCanvas}",
			)
		}

		// Non-vacuity: a file carrying both an atlas and model images must resolve SOME drawable-to-model-image
		// join, or the walk above is broken rather than the corpus being sparse.
		val webBearing = surveys.filter { survey -> survey.atlasCount > 0 && survey.modelImageCount > 0 }
		assertTrue(webBearing.isNotEmpty(), "no corpus file carries both an atlas and model images; the walk is broken")
		for (survey in webBearing) {
			assertTrue(
				survey.drawablesWithModelImage > 0,
				"${survey.fileName} has ${survey.modelImageCount} model images but no drawable joins one; the walk is broken",
			)
		}
	}

	/**
	 * Characterizes ACTextureInput.optionalTransformOnCanvas, which the survey shows is present on EVERY
	 * texture input rather than being the rare slot its name suggests.  What matters for the mapping chain
	 * is whether it ever carries anything but an identity: a non-identity would be a third transform
	 * between the sampled image and the canvas that the model-image / atlas-entry pair does not account for.
	 */
	@Test
	fun characterizesTheOptionalCanvasTransform() {
		val corpus =
			corpusDirectory() ?: run {
				println("no test/corpus directory; skipping the optional-canvas-transform probe")
				return
			}
		val files = corpus.walkTopDown().filter { it.isFile && it.extension == "cmo3" }.sortedBy { it.name }.toList()
		if (files.isEmpty()) {
			println("no .cmo3 corpus samples; skipping the optional-canvas-transform probe")
			return
		}
		val typeCounts = mutableMapOf<String, Int>()
		var identityCount = 0
		var nonIdentityCount = 0
		val nonIdentityExamples = mutableListOf<String>()
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			for (mesh in artMeshes(root)) {
				val extension = elements(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull()
				for (input in elements(extension?._textureInputs)) {
					// CMO3: ACTextureInput field optionalTransformOnCanvas
					val transform = (input as? ACTextureInput)?.optionalTransformOnCanvas ?: continue
					val typeName = "${input::class.simpleName}.${transform::class.simpleName}"
					typeCounts[typeName] = (typeCounts[typeName] ?: 0) + 1
					val affine = transform as? CAffine
					if (affine != null &&
						affine.m00 == 1f &&
						affine.m01 == 0f &&
						affine.m02 == 0f &&
						affine.m10 == 0f &&
						affine.m11 == 1f &&
						affine.m12 == 0f
					) {
						identityCount++
					} else {
						nonIdentityCount++
						if (nonIdentityExamples.size < 8) {
							nonIdentityExamples.add("${file.name} ${(mesh.id as? Id)?.idstr} $typeName $transform")
						}
					}
				}
			}
		}
		println("optionalTransformOnCanvas: identity=$identityCount nonIdentity=$nonIdentityCount")
		println("  by type: $typeCounts")
		for (example in nonIdentityExamples) {
			println("  non-identity: $example")
		}
		assertTrue(typeCounts.isNotEmpty(), "no optionalTransformOnCanvas values were seen; the walk is broken")
	}

	/** The drawable id every join keys on must survive; a blank id would silently orphan the binding. */
	@Test
	fun everyArtMeshCarriesAJoinableId() {
		val corpus =
			corpusDirectory() ?: run {
				println("no test/corpus directory; skipping the art-mesh id probe")
				return
			}
		val files = corpus.walkTopDown().filter { it.isFile && it.extension == "cmo3" }.sortedBy { it.name }.toList()
		if (files.isEmpty()) {
			println("no .cmo3 corpus samples; skipping the art-mesh id probe")
			return
		}
		var checked = 0
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			for (mesh in artMeshes(root)) {
				// CMO3: CArtMeshSource field id - the DrawableId Cmo3Import mints, and the store's join key.
				val idString = (mesh.id as? Id)?.idstr
				assertTrue(!idString.isNullOrEmpty(), "${file.name}: an art mesh carries no id string")
				checked++
			}
		}
		assertTrue(checked > 0, "no art meshes were checked; the corpus walk is broken")
	}
}