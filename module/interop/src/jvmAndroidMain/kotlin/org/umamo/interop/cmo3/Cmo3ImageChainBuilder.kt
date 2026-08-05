package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CSize
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.custom.FilterInstance
import org.umamo.format.cmo3.model.gen.Anisotropy
import org.umamo.format.cmo3.model.gen.CBlend_Normal
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.CachedImageType
import org.umamo.format.cmo3.model.gen.EnvConnection
import org.umamo.format.cmo3.model.gen.EnvValueConnector
import org.umamo.format.cmo3.model.gen.EnvValueSet
import org.umamo.format.cmo3.model.gen.FilterMode
import org.umamo.format.cmo3.model.gen.FilterOutputValueConnector
import org.umamo.format.cmo3.model.gen.FilterValue
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayerSet
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.MagFilter
import org.umamo.format.cmo3.model.gen.MinFilter
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.gen.ModelImageFilterEnv
import org.umamo.format.cmo3.model.gen.ModelImageFilterSet
import org.umamo.format.cmo3.model.gen.WrapMode
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.cmo3.type.CHashMap
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage

/*
 * WHY THIS FILE EXISTS, AND WHY IT CUTS UP THE ATLAS
 *
 * A CMO3 is built around SOURCE ART: the editor decomposes an imported PSD/CLIP/KRA into a
 * CLayeredImage tree of per-layer images, and everything else (model images, atlas entries,
 * placements) hangs off that.  A .moc3 carries none of it - only the packed atlas pages and UVs.
 * So a MOC3-origin export has to FABRICATE a source document, and the only raw material available
 * is the packed page.  That is the whole reason this builder slices each drawable's uv bounding
 * box back out of the atlas and presents the crop as if it were an imported layer.  None of that
 * cutting is inherent to the format; it is reconstruction of information the bake threw away.
 *
 * The cutting is NOT what makes a file render.  A third-party converter's CMO3 renders in the
 * official editor with NO source document at all (zero CLayeredImage / CLayer / CModelImage /
 * ModelImageEntry, drawables carrying only a CTextureInput_TextureAtlasRegion) - and it renders
 * despite geometry that is offset by a constant from Cubism's own golden file for the same model.
 * The web here exists for the editor's texture-atlas and mesh-edit VIEWS, which derive
 * mesh-over-texture placement from the model images; a single whole-page image cannot place
 * hundreds of drawables and drew every mesh at its assembled canvas position instead.
 *
 * KNOWN GAP (2026-08-03): a MOC3-origin export LOADS cleanly in the official editor - correct
 * hierarchy, parameters, and atlas - but the puppet does NOT render.  This is a documented
 * functionality gap, not an open defect: the real fix is the art-sourcing pipeline
 * (docs/plan/art-sourcing-pipeline.md Phase H), where an imported MOC3 reconciles its ORIGINAL
 * layered art into Umamo and this synthetic web is replaced by real source images.  Before
 * re-opening the hunt, read CMO3.md section Fresh-Graph Synthesis: geometry (ours matches the
 * golden to the digit), coordinate-frame sign, element shape, null coverage, and the presence of
 * the source-art web are ALL ruled out by differential testing.  Do not "fix" this by deleting
 * the web either - that shape was tested and the editor errors with an empty atlas.
 *
 * WHAT IT BUILDS, mirroring the official ATLAS-MODE shape (EricaTamamo,
 * isTextureInputModelImageMode=false): one CTextureAtlas + ONE SHARED GTexture2D per page that
 * every packed drawable on the page samples with atlas-frame UVs, plus a PER-DRAWABLE model-image
 * web - each drawable's texture patch cropped out of the page as its own CLayer + CModelImage +
 * ModelImageEntry, the entry carrying the patch's packing origin (materialLocalToAtlasTransform)
 * and the drawable's fitted atlas-to-canvas placement (atlasLocalToCanvasTransform).
 *
 * Every CModelImage carries the editor's layer-filter web (a CLayerSelector feeding a
 * CLayerFilter, connected through per-document shared FilterValue definitions) plus a filtered
 * image, a cached-image manager, and icon thumbnails - the official reader's custom deserializers
 * dereference these, so a null is a hard failure, not a cosmetic gap.  The filter definition
 * guids are editor-static constants (identical uuids in every corpus file).
 *
 * The synthetic source doc is the ATLAS PAGE's own frame, mirroring how an official document's
 * CLayeredImage carries the source PSD's size rather than the canvas
 * (ModelWithOffscreenPartClipping: a 500x500 doc inside a 1000x2000 canvas - Erica's doc merely
 * happens to equal its canvas).  A layer's placement is NOT in boundsOnImageDoc, which is all
 * zero on every corpus layer (883 of 883, every file and era); it lives on the owning
 * CModelImage's _materialLocalToCanvasTransform.
 *
 * A CModelImage IS UPRIGHT CANVAS-SPACE ART, and that is a hard invariant of the format, not a
 * stylistic choice: across every atlas-bearing corpus file, all 1046 model images carry a PURE
 * TRANSLATION in _materialLocalToCanvasTransform - including the ones the packer rotated or scaled.
 * The packing lives entirely on the ModelImageEntry (materialLocalToAtlasTransform's position,
 * scale, and eulerAngle in DEGREES), and atlasLocalToCanvasTransform is its inverse, so the two
 * compose back to that pure translation.  Verified to the digit on haruto's "Shin R": a 90 degree
 * packing whose 143x274 crop is UPRIGHT while its atlas footprint is 294x172 (swapped), whose
 * atlas-to-canvas is [0,1;-1,0], and whose entry position (292.0, 409.0863) composes to exactly the
 * written canvas origin (554.0, 1668.0).
 *
 * So this builder RESAMPLES rather than copies.  The crop is the mesh's canvas bounding box, its
 * pixels pulled back through the packing (resampleCrop) so the art comes out upright at canvas
 * resolution; an axis-aligned unit-scale packing still lands on exact page pixel centers, as does a
 * quarter turn, so the common cases stay lossless.  Handing the editor an atlas-shaped crop instead
 * - the atlas uv bounding box, with the rotation left in _materialLocalToCanvasTransform - is a
 * shape no Cubism-authored file has, and it costs the drawable its texture.
 *
 * The crop is also masked to the mesh's own triangles (coverageMaskOf).  A bounding box catches
 * whatever the packer nested against the silhouette, and un-masked the editor takes those foreign
 * pixels for this drawable's artwork: they follow the patch when the atlas is rearranged, and a
 * repack stamps them back into the page.
 *
 * Remaining deliberate simplifications, validated by the official-editor gate: icon thumbnails
 * are transparent placeholders (the editor regenerates thumbnails on edit), and the cached
 * images are the raw resources themselves at identity (SCALE_1, nothing prerendered).
 */
internal object Cmo3ImageChainBuilder {
	/** One retained atlas page: the original PNG bytes plus its pixel dimensions. */
	internal class AtlasPage(val pngBytes: ByteArray, val width: Int, val height: Int)

	/**
	 * One drawable's geometry on its page: id, interleaved atlas-frame uvs and base positions, plus
	 * the triangle indices that say which page pixels inside the uv bounding box are actually this
	 * drawable's (see [coverageMaskOf]).
	 */
	internal class DrawableRegion(
		val drawableIdStr: String,
		val uvs: FloatArray,
		val positions: FloatArray,
		val indices: IntArray,
	)

	/** The populated chain: the PNG entries to embed and the texture bindings. */
	internal class BuiltImageChain(
		val pngEntries: List<Cmo3FreshFile.PngEntry>,
		val bindingByDrawableId: Map<String, Cmo3DrawableTextureBinding>,
		val pageFallbackBindings: List<Cmo3DrawableTextureBinding>,
	)

	/**
	 * What makes two drawables share one CModelImage: the same crop rect, the same fitted placement,
	 * and the same mesh.  The mesh is part of the identity because it masks the crop - two drawables
	 * over one rect with different topology no longer produce the same pixels.
	 */
	private class PatchWebKey(
		private val patchRect: IntArray,
		private val placementBits: IntArray,
		private val uvs: FloatArray,
		private val indices: IntArray,
	) {
		override fun equals(other: Any?): Boolean =
			other is PatchWebKey &&
				patchRect.contentEquals(other.patchRect) &&
				placementBits.contentEquals(other.placementBits) &&
				uvs.contentEquals(other.uvs) &&
				indices.contentEquals(other.indices)

		override fun hashCode(): Int {
			var result = patchRect.contentHashCode()
			result = 31 * result + placementBits.contentHashCode()
			result = 31 * result + uvs.contentHashCode()
			result = 31 * result + indices.contentHashCode()
			return result
		}
	}

	/** CMO3: FilterInstance filterDefGuid for "CLayerSelector" - fixed uuid in every corpus file. */
	private const val LAYER_SELECTOR_DEF_UUID = "5e9fe1ea-0ec3-4d68-a5fa-018fc7abe301"

	/** CMO3: FilterInstance filterDefGuid for "CLayerFilter" - fixed uuid in every corpus file. */
	private const val LAYER_FILTER_DEF_UUID = "4083cd1f-40ba-4eda-8400-379019d55ed8"

	/** The per-document singletons of the filter web, shared by every page's ModelImageFilterSet. */
	private class FilterCommons {
		fun valueId(idStr: String): Id = Id("FilterValueId").apply { idstr = idStr }

		val inputLayerData: Id = valueId("mi_input_layerInputData")
		val currentImageGuid: Id = valueId("mi_currentImageGuid")
		val outputImage: Id = valueId("mi_output_image")
		val outputTransform: Id = valueId("mi_output_transform")
		val selectorInputLayerData: Id = valueId("ilf_inputLayerData")
		val selectorCurrentImageGuid: Id = valueId("ilf_currentImageGuid")
		val selectorOutputLayerData: Id = valueId("ilf_outputLayerData")
		val filterInputLayer: Id = valueId("ilf_inputLayer")

		fun value(displayName: String, id: Id): FilterValue =
			FilterValue().apply {
				name = displayName
				this.id = id
			}

		// CMO3: the FilterValue definitions - names and value-id wiring transcribed from the
		// corpus (MultiplyScreenColors.cmo3, identical across files).
		val selectLayer: FilterValue = value("Select Layer", selectorOutputLayerData)
		val importLayer: FilterValue = value("Import Layer", inputLayerData)
		val importLayerSelection: FilterValue = value("Import Layer selection", selectorInputLayerData)
		val currentGuid: FilterValue = value("Current GUID", currentImageGuid)
		val selectedSourceGuid: FilterValue = value("GUID of Selected Source Image", selectorCurrentImageGuid)
		val outputImageValue: FilterValue = value("Output image", outputImage)
		val outputImageResource: FilterValue = value("Output Image (Resource Format)", valueId("ilf_outputImageRes"))
		val layerToCanvasEnv: FilterValue = value("LayerToCanvas変換", outputTransform)
		val layerToCanvasFilter: FilterValue = value("LayerToCanvas変換", valueId("ilf_outputTransform"))

		val selectorDefGuid: Guid =
			Guid("StaticFilterDefGuid").apply {
				uuid = LAYER_SELECTOR_DEF_UUID
				note = "(no debug info)"
			}
		val layerFilterDefGuid: Guid =
			Guid("StaticFilterDefGuid").apply {
				uuid = LAYER_FILTER_DEF_UUID
				note = "(no debug info)"
			}
	}

	/**
	 * Builds one page's ModelImageFilterSet: a CLayerSelector instance feeding a CLayerFilter,
	 * with the external input/output connections the editor's model-image pipeline expects.
	 *
	 * @param FilterCommons commons The per-document shared definitions.
	 * @return ModelImageFilterSet The fresh filter set.
	 */
	private fun buildFilterSet(commons: FilterCommons): ModelImageFilterSet {
		val filterSet = ModelImageFilterSet()
		val selectorId = Id("FilterInstanceId").apply { idstr = "filter0" }
		val layerFilterId = Id("FilterInstanceId").apply { idstr = "filter1" }
		val selector =
			FilterInstance().apply {
				filterName = "CLayerSelector"
				filterDefGuid = commons.selectorDefGuid
				filterId = selectorId
				ownerFilterSet = filterSet
			}
		val selectorOutput =
			FilterOutputValueConnector().apply {
				instance = selector
				id = commons.selectorOutputLayerData
				valueDef = commons.selectLayer
			}
		selector.inputConnectors =
			CHashMap<Any?, Any?>().apply {
				put(commons.selectorInputLayerData, EnvValueConnector().apply { envValueId = commons.inputLayerData })
				put(commons.selectorCurrentImageGuid, EnvValueConnector().apply { envValueId = commons.currentImageGuid })
			}
		selector.outputConnectors = CHashMap<Any?, Any?>().apply { put(commons.selectorOutputLayerData, selectorOutput) }
		val layerFilter =
			FilterInstance().apply {
				filterName = "CLayerFilter"
				filterDefGuid = commons.layerFilterDefGuid
				filterId = layerFilterId
				inputConnectors = CHashMap<Any?, Any?>().apply { put(commons.filterInputLayer, selectorOutput) }
				outputConnectors = CHashMap<Any?, Any?>()
				ownerFilterSet = filterSet
			}
		filterSet.filterMap =
			LinkedHashMap<Any?, Any?>().apply {
				put(selectorId, selector)
				put(layerFilterId, layerFilter)
			}
		filterSet._externalInputs =
			LinkedHashMap<Any?, Any?>().apply {
				put(
					commons.inputLayerData,
					EnvConnection().apply {
						_envValueDef = commons.importLayer
						filter = selector
						filterValueDef = commons.importLayerSelection
					},
				)
				put(
					commons.currentImageGuid,
					EnvConnection().apply {
						_envValueDef = commons.currentGuid
						filter = selector
						filterValueDef = commons.selectedSourceGuid
					},
				)
			}
		filterSet._externalOutputs =
			LinkedHashMap<Any?, Any?>().apply {
				put(
					commons.outputImage,
					EnvConnection().apply {
						_envValueDef = commons.outputImageValue
						filter = layerFilter
						filterValueDef = commons.outputImageResource
					},
				)
				put(
					commons.outputTransform,
					EnvConnection().apply {
						_envValueDef = commons.layerToCanvasEnv
						filter = layerFilter
						filterValueDef = commons.layerToCanvasFilter
					},
				)
			}
		return filterSet
	}

	/**
	 * The cached-image manager the editor expects on every model image and texture atlas: the raw
	 * page cached as itself at SCALE_1 (nothing prerendered).
	 *
	 * @param CImageResource pageResource The page image resource.
	 * @param Int            width        The page width in pixels.
	 * @param Int            height       The page height in pixels.
	 * @return CCachedImageManager The fresh manager.
	 */
	private fun identityCacheManager(pageResource: CImageResource, width: Int, height: Int): CCachedImageManager =
		CCachedImageManager().apply {
			// CMO3: CCachedImageManager fields defaultCacheType / rawImage / cachedImages /
			// requiredMipmapLevel (corpus flat imports cache the raw resource itself).
			defaultCacheType = CachedImageType.SCALE_1
			rawImage = pageResource
			cachedImages =
				ArrayList<Any?>(
					mutableListOf(
						CCachedImage().apply {
							_cachedImageResource = pageResource
							isSharedImage = true
							rawImageSize =
								CSize().apply {
									this.width = width
									this.height = height
								}
							reductionRatio = 1
							mipmapLevel = 64
							hasMargin = false
							isCleaned = false
							transformRawImageToCachedImage = CAffine()
						},
					),
				)
			requiredMipmapLevel = 64
		}

	/**
	 * A transparent square icon plus its PNG entry.
	 *
	 * @param Int    size The square pixel size.
	 * @param String path The unique archive path for the PNG entry.
	 * @param MutableList entries The PNG entry collector.
	 * @return CImageIcon The fresh icon.
	 */
	private fun placeholderIcon(size: Int, path: String, entries: MutableList<Cmo3FreshFile.PngEntry>): CImageIcon {
		entries.add(Cmo3FreshFile.PngEntry(path, Cmo3SkeletonBuilder.blankPng(size)))
		return CImageIcon().apply {
			image =
				CWritableImage().apply {
					width = size
					height = size
					type = "INT_ARGB"
					image = FileRef().apply { archivePath = path }
				}
		}
	}

	/**
	 * A drawable's UPRIGHT layer-art rect, in canvas pixels, from its base mesh bounds.
	 *
	 * This is the frame a CModelImage lives in.  Every corpus model image places itself on the
	 * canvas by PURE TRANSLATION - 1046 of 1046 across every atlas-bearing file, including the ones
	 * whose packings rotate and scale - because the image IS upright canvas-resolution art and the
	 * transform is just its canvas origin.  So the image's own extent is the mesh's canvas bounding
	 * box, never the atlas footprint, which for a rotated packing is a different shape entirely.
	 *
	 * @param FloatArray positions Interleaved base canvas positions.
	 * @return IntArray? [x0, y0, width, height], or null when the mesh has no finite extent.
	 */
	private fun canvasRectOf(positions: FloatArray): IntArray? {
		if (positions.size < 2) {
			return null
		}
		var minX = Float.POSITIVE_INFINITY
		var minY = Float.POSITIVE_INFINITY
		var maxX = Float.NEGATIVE_INFINITY
		var maxY = Float.NEGATIVE_INFINITY
		var componentIndex = 0
		while (componentIndex + 1 < positions.size) {
			minX = minOf(minX, positions[componentIndex])
			maxX = maxOf(maxX, positions[componentIndex])
			minY = minOf(minY, positions[componentIndex + 1])
			maxY = maxOf(maxY, positions[componentIndex + 1])
			componentIndex += 2
		}
		if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) {
			return null
		}
		val x0 = kotlin.math.floor(minX.toDouble()).toInt()
		val y0 = kotlin.math.floor(minY.toDouble()).toInt()
		val width = (kotlin.math.ceil(maxX.toDouble()).toInt() - x0).coerceAtLeast(1)
		val height = (kotlin.math.ceil(maxY.toDouble()).toInt() - y0).coerceAtLeast(1)
		if (width > MAX_CROP_EXTENT || height > MAX_CROP_EXTENT) {
			return null
		}
		return intArrayOf(x0, y0, width, height)
	}

	/** Guards against a runaway mesh extent turning one crop into a multi-gigabyte allocation. */
	private const val MAX_CROP_EXTENT = 32768

	/**
	 * The packing transform: upright layer pixels to atlas page pixels, as [a, b, tx, c, d, ty].
	 *
	 * CMO3: ModelImageEntry field materialLocalToAtlasTransform.  The corpus pins the algebra
	 * exactly - `atlasLocalToCanvasTransform . materialLocalToAtlasTransform` reproduces the model
	 * image's `_materialLocalToCanvasTransform`, whose linear part is the identity.  So the packing
	 * is the page fit INVERTED, re-anchored on the layer's canvas origin (verified on haruto's
	 * "Shin R": a 90 degree packing whose atlas-to-canvas is [0,1;-1,0] and whose entry position
	 * (292.0, 409.0863) composes to exactly the written canvas origin (554.0, 1668.0)).
	 *
	 * @param CAffine pageFit The atlas-page-to-canvas fit.
	 * @param Int     canvasX0 The layer's canvas origin x.
	 * @param Int     canvasY0 The layer's canvas origin y.
	 * @return DoubleArray? The packing, or null when the fit is not invertible.
	 */
	private fun packingOf(pageFit: CAffine, canvasX0: Int, canvasY0: Int): DoubleArray? {
		val a = pageFit.m00.toDouble()
		val b = pageFit.m01.toDouble()
		val c = pageFit.m10.toDouble()
		val d = pageFit.m11.toDouble()
		val determinant = a * d - b * c
		if (!determinant.isFinite() || kotlin.math.abs(determinant) < 1e-12) {
			return null
		}
		val inverseA = d / determinant
		val inverseB = -b / determinant
		val inverseC = -c / determinant
		val inverseD = a / determinant
		val offsetX = canvasX0 - pageFit.m02.toDouble()
		val offsetY = canvasY0 - pageFit.m12.toDouble()
		return doubleArrayOf(
			inverseA,
			inverseB,
			inverseA * offsetX + inverseB * offsetY,
			inverseC,
			inverseD,
			inverseC * offsetX + inverseD * offsetY,
		)
	}

	/**
	 * Splits a packing's linear part into the rotation and scale a GTransform2 can hold.
	 *
	 * CMO3: GTransform2 fields eulerAngle (DEGREES - haruto's -1.0002398 matches its atlas-to-canvas
	 * cosine 0.9998477) and scale.  A GTransform2 has no shear term, so a sheared fit loses that
	 * component; the fits this builder produces are rotation-and-scale to well under a pixel.
	 *
	 * @param DoubleArray packing The packing as [a, b, tx, c, d, ty].
	 * @return DoubleArray [angleDegrees, scaleX, scaleY].
	 */
	private fun decomposePacking(packing: DoubleArray): DoubleArray {
		val a = packing[0]
		val b = packing[1]
		val c = packing[3]
		val d = packing[4]
		val scaleX = kotlin.math.sqrt(a * a + c * c)
		if (scaleX < 1e-12) {
			return doubleArrayOf(0.0, 1.0, 1.0)
		}
		// Gram-Schmidt: the first column fixes the rotation and its own length; the determinant then
		// gives the second column's signed length, so a mirrored packing keeps a negative scale
		// rather than folding into a bogus rotation.
		val angleDegrees = kotlin.math.atan2(c, a) * 180.0 / kotlin.math.PI
		val scaleY = (a * d - b * c) / scaleX
		return doubleArrayOf(angleDegrees, scaleX, scaleY)
	}

	/**
	 * An independent copy of an affine (the writer must not hoist one shared instance across the
	 * entry, the region input, and the model image - the editor writes separate elements).
	 *
	 * @param CAffine source The affine to copy.
	 * @return CAffine The copy.
	 */
	private fun copyAffine(source: CAffine): CAffine =
		CAffine().apply {
			m00 = source.m00
			m01 = source.m01
			m02 = source.m02
			m10 = source.m10
			m11 = source.m11
			m12 = source.m12
		}

	/**
	 * The model image's material-local-to-canvas placement: a PURE TRANSLATION to the layer's canvas
	 * origin.
	 *
	 * CMO3: CModelImage field _materialLocalToCanvasTransform (official layers carry their canvas
	 * origin here - translate(144, 222) in ModelWithOffscreenPartClipping).  Pure translation is not
	 * a simplification, it is the format's invariant: every one of the corpus's 1046 model images
	 * has an identity linear part, INCLUDING those whose packing rotates or scales, because whatever
	 * the packer did lives on the entry and is undone by atlasLocalToCanvasTransform.
	 *
	 * @param Int x0 The layer's canvas origin x.
	 * @param Int y0 The layer's canvas origin y.
	 * @return CAffine The material-local placement.
	 */
	private fun materialLocalToCanvas(x0: Int, y0: Int): CAffine =
		CAffine().apply {
			m02 = x0.toFloat()
			m12 = y0.toFloat()
		}

	/**
	 * How far kept coverage grows past the mesh's own triangles, in page pixels.
	 *
	 * Cutting exactly on the outermost edge would fringe the silhouette: the drawable's UVs reach
	 * that edge, so a bilinear tap there blends against the transparent pixel immediately outside.
	 * Two pixels covers a bilinear tap plus a mip level while staying well inside the gutter a
	 * packer leaves between neighbouring patches.
	 */
	private const val COVERAGE_BLEED_MARGIN = 2

	/**
	 * Which pixels of a patch rect the drawable's own mesh covers, grown by [COVERAGE_BLEED_MARGIN].
	 *
	 * A patch rect is the mesh's axis-aligned uv bounding box, so for any mesh that is not itself a
	 * page-aligned rectangle the rect also spans page pixels belonging to whatever the packer nested
	 * into the leftover corners.  Copying the rect verbatim hands those foreign pixels to this
	 * drawable's CModelImage, which the editor treats as the drawable's own artwork - it travels
	 * with the patch when the atlas is rearranged and gets stamped back into the page on a repack.
	 * Masking to the triangles keeps the crop rect (so every placement transform is unchanged) while
	 * dropping pixels the mesh never samples.
	 *
	 * Rasterization is CONSERVATIVE - a pixel counts as covered when its square overlaps a triangle
	 * at all, not when its center happens to land inside one.  Cubism meshes carry long sliver
	 * triangles along a silhouette, and center sampling drops the ones thinner than a pixel.
	 *
	 * @param FloatArray positions Interleaved base canvas positions.
	 * @param IntArray   indices   Triangle indices, three per triangle.
	 * @param Int        canvasX0  The layer's canvas origin x.
	 * @param Int        canvasY0  The layer's canvas origin y.
	 * @param Int        cropWidth  The layer rect's width.
	 * @param Int        cropHeight The layer rect's height.
	 * @return BooleanArray One flag per crop pixel in row-major order, or null when the mesh carries
	 *         no triangles to mask with (the whole rect is then kept).
	 */
	private fun coverageMaskOf(
		positions: FloatArray,
		indices: IntArray,
		canvasX0: Int,
		canvasY0: Int,
		cropWidth: Int,
		cropHeight: Int,
	): BooleanArray? {
		if (indices.size < 3) {
			return null
		}
		val vertexCount = positions.size / 2
		val covered = BooleanArray(cropWidth * cropHeight)
		var triangleStart = 0
		while (triangleStart + 2 < indices.size) {
			val indexA = indices[triangleStart]
			val indexB = indices[triangleStart + 1]
			val indexC = indices[triangleStart + 2]
			triangleStart += 3
			if (indexA !in 0 until vertexCount || indexB !in 0 until vertexCount || indexC !in 0 until vertexCount) {
				continue
			}
			// Material-local pixels are canvas pixels shifted to the layer origin, which is exactly
			// what makes the placement a pure translation.
			val cornerAx = positions[2 * indexA].toDouble() - canvasX0
			val cornerAy = positions[2 * indexA + 1].toDouble() - canvasY0
			val cornerBx = positions[2 * indexB].toDouble() - canvasX0
			val cornerBy = positions[2 * indexB + 1].toDouble() - canvasY0
			val cornerCx = positions[2 * indexC].toDouble() - canvasX0
			val cornerCy = positions[2 * indexC + 1].toDouble() - canvasY0
			val doubledArea = (cornerBx - cornerAx) * (cornerCy - cornerAy) - (cornerCx - cornerAx) * (cornerBy - cornerAy)
			if (!doubledArea.isFinite() || doubledArea == 0.0) {
				continue
			}
			// Normalize the winding so "inside" is uniformly a non-negative edge value.
			val winding = if (doubledArea > 0.0) 1.0 else -1.0
			// One extra pixel each way: the conservative test below reaches half a pixel past the
			// triangle, and a bounding box rounded inward would clip that reach off.
			val firstColumn = kotlin.math.floor(minOf(cornerAx, cornerBx, cornerCx)).toInt().coerceIn(0, cropWidth - 1) - 1
			val lastColumn = kotlin.math.ceil(maxOf(cornerAx, cornerBx, cornerCx)).toInt().coerceIn(0, cropWidth - 1) + 1
			val firstRow = kotlin.math.floor(minOf(cornerAy, cornerBy, cornerCy)).toInt().coerceIn(0, cropHeight - 1) - 1
			val lastRow = kotlin.math.ceil(maxOf(cornerAy, cornerBy, cornerCy)).toInt().coerceIn(0, cropHeight - 1) + 1
			for (rowIndex in maxOf(0, firstRow)..minOf(cropHeight - 1, lastRow)) {
				val pointY = rowIndex + 0.5
				for (columnIndex in maxOf(0, firstColumn)..minOf(cropWidth - 1, lastColumn)) {
					val flatIndex = rowIndex * cropWidth + columnIndex
					if (covered[flatIndex]) {
						continue
					}
					val pointX = columnIndex + 0.5
					if (overlapsEdge(cornerAx, cornerAy, cornerBx, cornerBy, pointX, pointY, winding) &&
						overlapsEdge(cornerBx, cornerBy, cornerCx, cornerCy, pointX, pointY, winding) &&
						overlapsEdge(cornerCx, cornerCy, cornerAx, cornerAy, pointX, pointY, winding)
					) {
						covered[flatIndex] = true
					}
				}
			}
		}
		return dilate(covered, cropWidth, cropHeight, COVERAGE_BLEED_MARGIN)
	}

	/**
	 * Whether a pixel's unit square reaches the inside half-plane of one triangle edge.
	 *
	 * The edge value at the pixel center varies by at most half the sum of the edge normal's
	 * components across the square, so adding that slack turns a center test into a square-overlap
	 * test - which is what keeps sub-pixel slivers from vanishing.
	 *
	 * @param Double fromX   Edge start x, in crop-local pixels.
	 * @param Double fromY   Edge start y.
	 * @param Double toX     Edge end x.
	 * @param Double toY     Edge end y.
	 * @param Double pointX  The pixel center's x.
	 * @param Double pointY  The pixel center's y.
	 * @param Double winding +1 when the triangle winds counter-clockwise, -1 when clockwise.
	 * @return Boolean True when the pixel square is not fully outside this edge.
	 */
	private fun overlapsEdge(
		fromX: Double,
		fromY: Double,
		toX: Double,
		toY: Double,
		pointX: Double,
		pointY: Double,
		winding: Double,
	): Boolean {
		val edgeX = toX - fromX
		val edgeY = toY - fromY
		val edgeValue = winding * (edgeX * (pointY - fromY) - edgeY * (pointX - fromX))
		return edgeValue + 0.5 * (kotlin.math.abs(edgeX) + kotlin.math.abs(edgeY)) >= 0.0
	}

	/**
	 * Grows a coverage mask by [margin] pixels, as two linear-time passes over a sliding window.
	 *
	 * @param BooleanArray covered The raw per-pixel coverage, row-major.
	 * @param Int          width   The mask width.
	 * @param Int          height  The mask height.
	 * @param Int          margin  How many pixels to grow by.
	 * @return BooleanArray The grown mask ([covered] itself when the margin is zero).
	 */
	private fun dilate(covered: BooleanArray, width: Int, height: Int, margin: Int): BooleanArray {
		if (margin <= 0) {
			return covered
		}
		val grownAcross = BooleanArray(covered.size)
		for (rowIndex in 0 until height) {
			val rowStart = rowIndex * width
			var windowCount = 0
			for (columnIndex in 0..minOf(margin, width - 1)) {
				if (covered[rowStart + columnIndex]) {
					windowCount += 1
				}
			}
			for (columnIndex in 0 until width) {
				grownAcross[rowStart + columnIndex] = windowCount > 0
				val leavingColumn = columnIndex - margin
				val enteringColumn = columnIndex + margin + 1
				if (leavingColumn >= 0 && covered[rowStart + leavingColumn]) {
					windowCount -= 1
				}
				if (enteringColumn < width && covered[rowStart + enteringColumn]) {
					windowCount += 1
				}
			}
		}
		val grown = BooleanArray(covered.size)
		for (columnIndex in 0 until width) {
			var windowCount = 0
			for (rowIndex in 0..minOf(margin, height - 1)) {
				if (grownAcross[rowIndex * width + columnIndex]) {
					windowCount += 1
				}
			}
			for (rowIndex in 0 until height) {
				grown[rowIndex * width + columnIndex] = windowCount > 0
				val leavingRow = rowIndex - margin
				val enteringRow = rowIndex + margin + 1
				if (leavingRow >= 0 && grownAcross[leavingRow * width + columnIndex]) {
					windowCount -= 1
				}
				if (enteringRow < height && grownAcross[enteringRow * width + columnIndex]) {
					windowCount += 1
				}
			}
		}
		return grown
	}

	/**
	 * Resamples the page through [packing] into UPRIGHT layer art, clearing pixels outside
	 * [coverage].
	 *
	 * The packing carries the rotation and scale the packer applied, so this un-does it: crop pixel
	 * centers map back into the page and sample bilinearly.  An axis-aligned unit-scale packing -
	 * the common case - lands exactly on page pixel centers, so it stays a lossless copy; a quarter
	 * turn is likewise exact.  Only an oblique or non-unit packing actually interpolates, which is
	 * the price of handing the editor art in the frame it expects.
	 *
	 * @param RasterImage  decodedPage The decoded page pixels.
	 * @param DoubleArray  packing     Material-local to page, as [a, b, tx, c, d, ty].
	 * @param Int          cropWidth   Layer rect width.
	 * @param Int          cropHeight  Layer rect height.
	 * @param BooleanArray coverage    The mesh coverage mask, or null to keep the whole rect.
	 * @return ByteArray The encoded PNG bytes.
	 */
	private fun resampleCrop(
		decodedPage: RasterImage,
		packing: DoubleArray,
		cropWidth: Int,
		cropHeight: Int,
		coverage: BooleanArray?,
	): ByteArray {
		val cropRgba = ByteArray(cropWidth * cropHeight * 4)
		for (rowIndex in 0 until cropHeight) {
			val materialY = rowIndex + 0.5
			val rowOffset = rowIndex * cropWidth * 4
			for (columnIndex in 0 until cropWidth) {
				if (coverage != null && !coverage[rowIndex * cropWidth + columnIndex]) {
					continue
				}
				val materialX = columnIndex + 0.5
				val pageX = packing[0] * materialX + packing[1] * materialY + packing[2]
				val pageY = packing[3] * materialX + packing[4] * materialY + packing[5]
				sampleBilinear(decodedPage, pageX, pageY, cropRgba, rowOffset + columnIndex * 4)
			}
		}
		return PngCodec.write(RasterImage(cropWidth, cropHeight, cropRgba))
	}

	/**
	 * Samples a page bilinearly at a continuous pixel-center coordinate, edge-clamped.
	 *
	 * @param RasterImage page    The decoded page.
	 * @param Double      pageX   Sample x, in pixel-center coordinates.
	 * @param Double      pageY   Sample y.
	 * @param ByteArray   target  Destination RGBA buffer.
	 * @param Int         offset  Destination byte offset.
	 */
	private fun sampleBilinear(page: RasterImage, pageX: Double, pageY: Double, target: ByteArray, offset: Int) {
		if (!pageX.isFinite() || !pageY.isFinite()) {
			return
		}
		val leftEdge = pageX - 0.5
		val topEdge = pageY - 0.5
		val leftColumn = kotlin.math.floor(leftEdge).toInt()
		val topRow = kotlin.math.floor(topEdge).toInt()
		val fractionX = leftEdge - leftColumn
		val fractionY = topEdge - topRow
		for (channel in 0 until 4) {
			var accumulated = 0.0
			for (cornerRow in 0 until 2) {
				val row = (topRow + cornerRow).coerceIn(0, page.height - 1)
				val rowWeight = if (cornerRow == 0) 1.0 - fractionY else fractionY
				for (cornerColumn in 0 until 2) {
					val column = (leftColumn + cornerColumn).coerceIn(0, page.width - 1)
					val columnWeight = if (cornerColumn == 0) 1.0 - fractionX else fractionX
					val sample = page.rgba[(row * page.width + column) * 4 + channel].toInt() and 0xFF
					accumulated += sample * rowWeight * columnWeight
				}
			}
			target[offset + channel] = (accumulated + 0.5).toInt().coerceIn(0, 255).toByte()
		}
	}

	/**
	 * Populates [root]'s texture manager with one page chain per atlas page plus the per-drawable
	 * model-image webs.
	 *
	 * @param CModelSource root          The fresh skeleton root (its textureManager must exist).
	 * @param List         pages         The atlas pages, in model3 texture order.
	 * @param List         regionsByPage Each page's drawable regions (geometry for the patch crop
	 *                                   and the placement fit), indexed like [pages].
	 * @param Long         nowMillis     The import timestamp the wrappers record (caller-supplied
	 *                                   so tests stay deterministic).
	 * @return BuiltImageChain The PNG entries plus the texture bindings.
	 */
	internal fun populate(
		root: CModelSource,
		pages: List<AtlasPage>,
		regionsByPage: List<List<DrawableRegion>>,
		nowMillis: Long,
	): BuiltImageChain {
		val textureManager = root.textureManager as? CTextureManager ?: error("skeleton has no texture manager")
		val sharedBlend = CBlend_Normal()
		val sharedOptions = CHashMap<String, Any?>()
		val filterCommons = FilterCommons()
		val groupLinkedRawImageGuids = CArrayList<Any?>()
		val groupModelImages = CArrayList<Any?>()
		val group =
			CModelImageGroup().apply {
				memo = ""
				groupName = "Textures"
				_linkedRawImageGuids = groupLinkedRawImageGuids
				_modelImages = groupModelImages
			}
		val rawImages =
			mutableGraphListOf(textureManager._rawImages) ?: error("skeleton texture manager has no raw-image list")
		val textureAtlases =
			mutableGraphListOf(textureManager._textureAtlases) ?: error("skeleton texture manager has no texture-atlas list")
		val modelImageGroups =
			mutableGraphListOf(textureManager._modelImageGroups) ?: error("skeleton texture manager has no model-image-group list")
		val pngEntries = ArrayList<Cmo3FreshFile.PngEntry>()
		val bindingByDrawableId = HashMap<String, Cmo3DrawableTextureBinding>()
		val pageFallbackBindings = ArrayList<Cmo3DrawableTextureBinding>(pages.size)
		// The skeleton's three model icons take image.png / image_0.png / image_1.png, so page
		// icon entries continue the editor's image_N naming from suffix 2.
		var iconSuffix = 2
		// CMO3: the imageFileBuf de-dupe naming convention (imageFileBuf, imageFileBuf_0, ...);
		// pages claim the first indices and patch crops continue the sequence.
		var imageFileBufIndex = 0

		fun nextImageFileBufPath(): String {
			val path = if (imageFileBufIndex == 0) "imageFileBuf.png" else "imageFileBuf_${imageFileBufIndex - 1}.png"
			imageFileBufIndex += 1
			return path
		}
		for ((pageIndex, page) in pages.withIndex()) {
			val pagePath = nextImageFileBufPath()
			val pageName = "Texture_$pageIndex.png"
			pngEntries.add(Cmo3FreshFile.PngEntry(pagePath, page.pngBytes))
			val pageResource =
				CImageResource().apply {
					// CMO3: CImageResource attrs width/height/type + the imageFileBuf file child.
					width = page.width
					height = page.height
					type = "INT_ARGB"
					imageFileBuf = FileRef().apply { archivePath = pagePath }
					imageFileBuf_size = page.pngBytes.size
				}
			val layeredImage = CLayeredImage()
			val patchLayers = CArrayList<Any?>()
			val rootLayerGroup =
				CLayerGroup().apply {
					name = "root"
					memo = ""
					isVisible = true
					blend = sharedBlend
					guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
					opacity255 = 255
					_optionOfIOption = sharedOptions
					_layeredImage = layeredImage
					_children = patchLayers
				}
			val layerEntryList = CArrayList<Any?>(mutableListOf<Any?>(rootLayerGroup))
			layeredImage.apply {
				name = pageName
				memo = ""
				// CMO3: CLayeredImage width/height - the SOURCE document's own frame, unrelated to
				// the canvas (ModelWithOffscreenPartClipping: a 500x500 doc in a 1000x2000 canvas).
				// Our synthetic source document IS the atlas page, so the page dims are its frame.
				width = page.width
				height = page.height
				// A rendered page has no source file on anyone's disk; the bare name is the honest
				// breadcrumb (the editor stores the importing machine's absolute path here).
				psdFile = FileRef().apply { textPath = pageName }
				description = ""
				guid = Cmo3SkeletonBuilder.freshGuid("CLayeredImageGuid")
				psdFileLastModified = nowMillis
				_rootLayer = rootLayerGroup
				layerSet =
					LayerSet().apply {
						_layeredImage = layeredImage
						_layerEntryList = layerEntryList
					}
			}
			val wrapper =
				LayeredImageWrapper().apply {
					image = layeredImage
					importedTimeMSec = nowMillis
					lastModifiedTimeMSec = nowMillis
				}
			val atlasEntries = CArrayList<Any?>()
			val atlas = CTextureAtlas()
			atlas.apply {
				name = "TextureAtlas${pageIndex + 1}"
				width = page.width
				height = page.height
				cachedAtlasImage = pageResource
				guid = Cmo3SkeletonBuilder.freshGuid("CTextureAtlasGuid")
				modelImages = atlasEntries
				cachedImageManager = identityCacheManager(pageResource, page.width, page.height)
			}
			val texture =
				GTexture2D().apply {
					// CMO3: GTexture2D - the page's ONE shared texture; every drawable on the page
					// references this instance (the writer hoists it), like the editor's own atlas.
					name = atlas.name
					// CMO3: GTexture fields wrapMode / filterMode / anisotropy - the editor's fixed
					// sampling setup on every corpus texture.
					wrapMode = WrapMode.CLAMP_TO_BORDER
					guid = Cmo3SkeletonBuilder.freshGuid("GTextureGuid")
					anisotropy = Anisotropy.ON
					srcImageResource = pageResource
					transformImageResource01toLogical01 = CAffine()
					mipmapLevel = 64
					// CMO3: GTexture2D field isPremultiplied - true on EVERY corpus texture (178 of
					// 178, every era), including the many whose embedded PNG bytes are straight
					// alpha.  The flag records the editor's texture-render/upload convention, not
					// the byte storage (see the Premultiplied section), so a MOC3 sidecar page -
					// straight alpha like the corpus ones - writes true as well.
					isPremultiplied = true
				}
			texture.filterMode =
				FilterMode().apply {
					minFilter = MinFilter.LINEAR_MIPMAP_LINEAR
					magFilter = MagFilter.LINEAR
					owner = texture
				}
			// Per-drawable patch webs, deduped by exact web identity (a mirror duplicate with a
			// different placement keeps its own web - a ModelImageEntry carries only one).
			val regions = regionsByPage.getOrNull(pageIndex).orEmpty()
			val decodedPage = if (regions.isNotEmpty()) PngCodec.read(page.pngBytes) else null
			val imageGuidByWebKey = HashMap<PatchWebKey, Guid>()
			for (region in regions) {
				val pageFit = fitAtlasPageToCanvasTransform(region.uvs, region.positions, page.width, page.height)
				val layerRect = canvasRectOf(region.positions)
				val packing = layerRect?.let { rect -> packingOf(pageFit, rect[0], rect[1]) }
				if (layerRect == null || packing == null || decodedPage == null) {
					bindingByDrawableId[region.drawableIdStr] =
						Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, null, pageFit)
					continue
				}
				val canvasX0 = layerRect[0]
				val canvasY0 = layerRect[1]
				val cropWidth = layerRect[2]
				val cropHeight = layerRect[3]
				val webKey =
					PatchWebKey(
						layerRect,
						intArrayOf(
							pageFit.m00.toRawBits(),
							pageFit.m01.toRawBits(),
							pageFit.m02.toRawBits(),
							pageFit.m10.toRawBits(),
							pageFit.m11.toRawBits(),
							pageFit.m12.toRawBits(),
						),
						region.uvs,
						region.indices,
					)
				val imageGuid =
					imageGuidByWebKey.getOrPut(webKey) {
						val coverage = coverageMaskOf(region.positions, region.indices, canvasX0, canvasY0, cropWidth, cropHeight)
						val cropBytes = resampleCrop(decodedPage, packing, cropWidth, cropHeight, coverage)
						val cropPath = nextImageFileBufPath()
						pngEntries.add(Cmo3FreshFile.PngEntry(cropPath, cropBytes))
						val cropResource =
							CImageResource().apply {
								// CMO3: CImageResource - the drawable's patch cropped out of the page.
								width = cropWidth
								height = cropHeight
								type = "INT_ARGB"
								imageFileBuf = FileRef().apply { archivePath = cropPath }
								imageFileBuf_size = cropBytes.size
							}
						val patchLayer =
							CLayer().apply {
								// CMO3: CLayer - the patch as its own layer on the canvas-frame doc,
								// layerId null (no PSD layer identity exists for a rendered atlas).
								name = region.drawableIdStr
								memo = ""
								isVisible = true
								blend = sharedBlend
								guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
								opacity255 = 255
								_optionOfIOption = sharedOptions
								_layeredImage = layeredImage
								imageResource = cropResource
								// CMO3: CLayer field boundsOnImageDoc - the layer's rect within its source
								// doc.  Corpus-wide its width/height ALWAYS equal the layer image's own
								// dimensions (905 of 905 layers across 20 files, none all-zero) and its
								// origin repeats the CModelImage's _materialLocalToCanvasTransform
								// translation - Erica's layer "1" is 309x439 at bounds (2197, 535, 309, 439)
								// with the same (2197, 535) placement.  A zero rect describes a zero-size
								// layer, so the composite the editor builds from it comes out empty.
								boundsOnImageDoc =
									CRect().apply {
										x = canvasX0
										y = canvasY0
										width = cropWidth
										height = cropHeight
									}
								layerIdentifier =
									CLayerIdentifier().apply {
										layerName = region.drawableIdStr
										layerIdValue_testImpl = -1
									}
								// CMO3: CLayer fields icon16 / icon64 - thumbnails on every corpus layer.
								icon16 = placeholderIcon(16, "image_${iconSuffix++}.png", pngEntries)
								icon64 = placeholderIcon(64, "image_${iconSuffix++}.png", pngEntries)
								layerInfo = LinkedHashMap<String, Any?>()
								this.group = rootLayerGroup
							}
						patchLayers.add(patchLayer)
						layerEntryList.add(patchLayer)
						val patchImage =
							CModelImage().apply {
								guid = Cmo3SkeletonBuilder.freshGuid("CModelImageGuid")
								name = region.drawableIdStr
								// CMO3: CModelImage fields inputFilter / inputFilterEnv - the layer-filter
								// web selecting this patch's layer; the deserializer dereferences both.
								inputFilter = buildFilterSet(filterCommons)
								inputFilterEnv =
									ModelImageFilterEnv().apply {
										envValues =
											CHashMap<Any?, Any?>().apply {
												put(
													filterCommons.currentImageGuid,
													EnvValueSet().apply {
														id = filterCommons.currentImageGuid
														value = layeredImage.guid
														updateTimeMs = nowMillis
													},
												)
												put(
													filterCommons.inputLayerData,
													EnvValueSet().apply {
														id = filterCommons.inputLayerData
														value =
															CLayerSelectorMap().apply {
																_imageToLayerInput =
																	LinkedHashMap<Any?, Any?>().apply {
																		put(
																			layeredImage.guid,
																			ArrayList<Any?>(
																				mutableListOf(
																					CLayerInputData().apply {
																						layer = patchLayer
																						affine = CAffine()
																					},
																				),
																			),
																		)
																	}
															}
														updateTimeMs = nowMillis
													},
												)
											}
									}
								_filteredImage = cropResource
								// CMO3: CModelImage fields icon16 / cachedImageManager - present on every
								// corpus model image; the icon is a placeholder the editor regenerates.
								icon16 = placeholderIcon(16, "image_${iconSuffix++}.png", pngEntries)
								// CMO3: CModelImage field _materialLocalToCanvasTransform - the patch's
								// canvas placement (official layers carry their canvas origin here).
								_materialLocalToCanvasTransform = materialLocalToCanvas(canvasX0, canvasY0)
								_group = group
								linkedRawImageGuids = CArrayList<Any?>(mutableListOf(layeredImage.guid))
								cachedImageManager = identityCacheManager(cropResource, cropWidth, cropHeight)
								memo = ""
							}
						groupModelImages.add(patchImage)
						atlasEntries.add(
							ModelImageEntry().apply {
								// CMO3: ModelImageEntry - the patch's packing origin on the page plus the
								// drawable's fitted atlas-to-canvas placement; the editor's atlas and
								// mesh-edit views derive mesh-over-texture placement from these.
								this.atlas = atlas
								modelImageGuid = patchImage.guid
								// CMO3: ModelImageEntry field autoLayoutLock - an AutoLayoutLock enum
								// (v="NONE"); the editor's field is enum-typed and class-casts a boolean.
								autoLayoutLock = org.umamo.format.cmo3.model.gen.AutoLayoutLock.NONE
								atlasLocalToCanvasTransform = copyAffine(pageFit)
								materialLocalToAtlasTransform =
									GTransform2().apply {
										val decomposed = decomposePacking(packing)
										position =
											GVector2().apply {
												x = packing[2].toFloat()
												y = packing[5].toFloat()
											}
										scale =
											GVector2().apply {
												x = decomposed[1].toFloat()
												y = decomposed[2].toFloat()
											}
										eulerAngle = decomposed[0].toFloat()
									}
							},
						)
						patchImage.guid as Guid
					}
				bindingByDrawableId[region.drawableIdStr] =
					Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, imageGuid, copyAffine(pageFit))
			}
			pageFallbackBindings.add(Cmo3DrawableTextureBinding(texture, atlas.guid as Guid, null, CAffine()))
			groupLinkedRawImageGuids.add(layeredImage.guid)
			rawImages.add(wrapper)
			textureAtlases.add(atlas)
		}
		modelImageGroups.add(group)
		// CMO3: CTextureManager field isTextureInputModelImageMode - false = atlas display mode,
		// matching the atlas-region inputs the drawables carry.
		textureManager.isTextureInputModelImageMode = false
		return BuiltImageChain(pngEntries, bindingByDrawableId, pageFallbackBindings)
	}
}
