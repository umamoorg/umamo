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

/*
 * The image chain a fresh CMO3 needs for its atlas pages: each MOC3 page becomes a flat-PNG
 * "import" exactly like the editor's own flat-image imports (docs/format/CMO3.md §4 - a
 * CLayeredImage whose root group wraps ONE CLayer with a null layerId), plus one CTextureAtlas
 * whose single ModelImageEntry maps the whole page at identity, and one SHARED GTexture2D every
 * drawable on the page references.  The chain is what makes UVs stay in the atlas frame
 * (hasAtlasRegion) and what gives the official editor a source-art panel to show.
 *
 * Every CModelImage carries the editor's layer-filter web (a CLayerSelector feeding a
 * CLayerFilter, connected through per-document shared FilterValue definitions) plus a filtered
 * image, a cached-image manager, and icon thumbnails - the official reader's custom deserializers
 * dereference these, so a null is a hard failure, not a cosmetic gap.  The filter definition
 * guids are editor-static constants (identical uuids in every corpus file).
 *
 * Remaining deliberate simplifications, validated by the official-editor gate: icon thumbnails
 * are transparent placeholders (the editor regenerates thumbnails on edit), the cached image is
 * the raw page itself at identity (SCALE_1, nothing prerendered), and page placement transforms
 * are identity (the page already IS the packed atlas).
 */
internal object Cmo3ImageChainBuilder {
	/** One retained atlas page: the original PNG bytes plus its pixel dimensions. */
	internal class AtlasPage(val pngBytes: ByteArray, val width: Int, val height: Int)

	/** The populated chain: the PNG entries to embed and the per-page drawable bindings. */
	internal class BuiltImageChain(
		val pngEntries: List<Cmo3FreshFile.PngEntry>,
		val pageBindings: List<Cmo3DrawableTextureBinding>,
	)

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
	 * Populates [root]'s texture manager with one page chain per atlas page.
	 *
	 * @param CModelSource root      The fresh skeleton root (its textureManager must exist).
	 * @param List         pages     The atlas pages, in model3 texture order.
	 * @param Long         nowMillis The import timestamp the wrappers record (caller-supplied so
	 *                               tests stay deterministic).
	 * @return BuiltImageChain The PNG entries plus per-page bindings, indexed like [pages].
	 */
	internal fun populate(root: CModelSource, pages: List<AtlasPage>, nowMillis: Long): BuiltImageChain {
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
		val pngEntries = ArrayList<Cmo3FreshFile.PngEntry>(pages.size)
		val pageBindings = ArrayList<Cmo3DrawableTextureBinding>(pages.size)
		// The skeleton's three model icons take image.png / image_0.png / image_1.png, so page
		// icon entries continue the editor's image_N naming from suffix 2.
		var iconSuffix = 2
		for ((pageIndex, page) in pages.withIndex()) {
			// CMO3: the imageFileBuf de-dupe naming convention (imageFileBuf, imageFileBuf_0, ...).
			val pagePath = if (pageIndex == 0) "imageFileBuf.png" else "imageFileBuf_${pageIndex - 1}.png"
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
			val pageLayer =
				CLayer().apply {
					// CMO3: the flat-image import shape - a single CLayer, layerId null (no PSD
					// layer identity exists for a rendered atlas page).
					name = pageName
					memo = ""
					isVisible = true
					blend = sharedBlend
					guid = Cmo3SkeletonBuilder.freshGuid("CLayerGuid")
					opacity255 = 255
					_optionOfIOption = sharedOptions
					_layeredImage = layeredImage
					imageResource = pageResource
					boundsOnImageDoc =
						CRect().apply {
							width = page.width
							height = page.height
						}
					layerIdentifier =
						CLayerIdentifier().apply {
							layerName = pageName
							layerIdValue_testImpl = -1
						}
					// CMO3: CLayer fields icon16 / icon64 - thumbnail icons on every corpus layer.
					icon16 = placeholderIcon(16, "image_${iconSuffix++}.png", pngEntries)
					icon64 = placeholderIcon(64, "image_${iconSuffix++}.png", pngEntries)
					layerInfo = LinkedHashMap<String, Any?>()
				}
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
					_children = CArrayList<Any?>(mutableListOf(pageLayer))
				}
			pageLayer.group = rootLayerGroup
			layeredImage.apply {
				name = pageName
				memo = ""
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
						_layerEntryList = CArrayList<Any?>(mutableListOf(rootLayerGroup, pageLayer))
					}
			}
			val wrapper =
				LayeredImageWrapper().apply {
					image = layeredImage
					importedTimeMSec = nowMillis
					lastModifiedTimeMSec = nowMillis
				}
			val modelImage =
				CModelImage().apply {
					guid = Cmo3SkeletonBuilder.freshGuid("CModelImageGuid")
					name = pageName
					// CMO3: CModelImage fields inputFilter / inputFilterEnv - the layer-filter web
					// selecting this page's single layer; the editor's deserializer dereferences both.
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
																			layer = pageLayer
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
					_filteredImage = pageResource
					// CMO3: CModelImage fields icon16 / cachedImageManager - present on every corpus
					// model image; the icon is a placeholder the editor regenerates.
					icon16 = placeholderIcon(16, "image_${iconSuffix++}.png", pngEntries)
					_materialLocalToCanvasTransform = CAffine()
					_group = group
					linkedRawImageGuids = CArrayList<Any?>(mutableListOf(layeredImage.guid))
					cachedImageManager = identityCacheManager(pageResource, page.width, page.height)
					memo = ""
				}
			val atlas = CTextureAtlas()
			atlas.apply {
				name = "TextureAtlas${pageIndex + 1}"
				width = page.width
				height = page.height
				cachedAtlasImage = pageResource
				guid = Cmo3SkeletonBuilder.freshGuid("CTextureAtlasGuid")
				modelImages =
					CArrayList<Any?>(
						mutableListOf(
							ModelImageEntry().apply {
								// CMO3: ModelImageEntry - the whole page mapped at identity (the page
								// already IS the packed atlas; there is nothing to place).
								this.atlas = atlas
								modelImageGuid = modelImage.guid
								// CMO3: ModelImageEntry field autoLayoutLock - modern-era entries
								// always carry an AutoLayoutLock enum (v="NONE"); the editor's field
								// is enum-typed and class-casts a boolean.
								autoLayoutLock = org.umamo.format.cmo3.model.gen.AutoLayoutLock.NONE
								atlasLocalToCanvasTransform = CAffine()
								materialLocalToAtlasTransform =
									GTransform2().apply {
										position = GVector2()
										scale =
											GVector2().apply {
												x = 1f
												y = 1f
											}
									}
							},
						),
					)
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
					// MOC3 sidecar pages are straight-alpha PNGs, and the flag must describe the
					// actual bytes (the editor's own rendered atlas is premultiplied and says true).
					isPremultiplied = false
				}
			texture.filterMode =
				FilterMode().apply {
					minFilter = MinFilter.LINEAR_MIPMAP_LINEAR
					magFilter = MagFilter.LINEAR
					owner = texture
				}
			groupLinkedRawImageGuids.add(layeredImage.guid)
			groupModelImages.add(modelImage)
			rawImages.add(wrapper)
			textureAtlases.add(atlas)
			pageBindings.add(
				Cmo3DrawableTextureBinding(
					texture = texture,
					textureAtlasGuid = atlas.guid as Guid,
					modelImageGuid = modelImage.guid as Guid,
					inputImageLocalToCanvasTransform = CAffine(),
				),
			)
		}
		modelImageGroups.add(group)
		// CMO3: CTextureManager field isTextureInputModelImageMode - false = atlas display mode,
		// matching the atlas-region inputs the drawables carry.
		textureManager.isTextureInputModelImageMode = false
		return BuiltImageChain(pngEntries, pageBindings)
	}
}
