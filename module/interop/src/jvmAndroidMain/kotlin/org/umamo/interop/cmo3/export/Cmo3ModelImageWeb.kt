package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.FilterInstance
import org.umamo.format.cmo3.model.gen.CLayerInputData
import org.umamo.format.cmo3.model.gen.CLayerSelectorMap
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.EnvConnection
import org.umamo.format.cmo3.model.gen.EnvValueConnector
import org.umamo.format.cmo3.model.gen.EnvValueSet
import org.umamo.format.cmo3.model.gen.FilterOutputValueConnector
import org.umamo.format.cmo3.model.gen.FilterValue
import org.umamo.format.cmo3.model.gen.ModelImageFilterEnv
import org.umamo.format.cmo3.model.gen.ModelImageFilterSet
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.format.cmo3.type.CHashMap
import org.umamo.format.raster.RasterImage

/**
 * The model image around one layer, with the layer-filter web the editor expects on it.
 *
 * Every CModelImage carries a CLayerSelector feeding a CLayerFilter, connected through per-document
 * shared FilterValue definitions, plus a filtered image, a cached-image manager, and icon thumbnails.
 * The official reader's custom deserializers dereference these, so a null is a hard failure.  Shared by
 * the real-layer web, the crop web, and a layer minted into a retained graph.
 */
internal object Cmo3ModelImageWeb {
	/** CMO3: FilterInstance filterDefGuid for "CLayerSelector" - fixed uuid in every corpus file. */
	private const val LAYER_SELECTOR_DEF_UUID = "5e9fe1ea-0ec3-4d68-a5fa-018fc7abe301"

	/** CMO3: FilterInstance filterDefGuid for "CLayerFilter" - fixed uuid in every corpus file. */
	private const val LAYER_FILTER_DEF_UUID = "4083cd1f-40ba-4eda-8400-379019d55ed8"

	/**
	 * The per-document singletons of the filter web, shared by every model image's ModelImageFilterSet:
	 * the value ids the connectors are keyed by, the FilterValue definitions they name, and the two
	 * filter-definition guids.  Minted fresh for a new graph, or recovered from a model image a
	 * retained graph already holds so a minted image shares the file's own objects.
	 *
	 * @param Map  idByIdStr          Each value id by its idstr.
	 * @param Map  valueByIdStr       Each FilterValue definition by its own id's idstr.
	 * @param Guid selectorDefGuid    The CLayerSelector definition guid.
	 * @param Guid layerFilterDefGuid The CLayerFilter definition guid.
	 */
	internal class FilterCommons private constructor(
		private val idByIdStr: Map<String, Id>,
		private val valueByIdStr: Map<String, FilterValue>,
		val selectorDefGuid: Guid,
		val layerFilterDefGuid: Guid,
	) {
		val inputLayerData: Id get() = idByIdStr.getValue(INPUT_LAYER_DATA)
		val currentImageGuid: Id get() = idByIdStr.getValue(CURRENT_IMAGE_GUID)
		val outputImage: Id get() = idByIdStr.getValue(OUTPUT_IMAGE)
		val outputTransform: Id get() = idByIdStr.getValue(OUTPUT_TRANSFORM)
		val selectorInputLayerData: Id get() = idByIdStr.getValue(SELECTOR_INPUT_LAYER_DATA)
		val selectorCurrentImageGuid: Id get() = idByIdStr.getValue(SELECTOR_CURRENT_IMAGE_GUID)
		val selectorOutputLayerData: Id get() = idByIdStr.getValue(SELECTOR_OUTPUT_LAYER_DATA)
		val filterInputLayer: Id get() = idByIdStr.getValue(FILTER_INPUT_LAYER)

		val selectLayer: FilterValue get() = valueByIdStr.getValue(SELECTOR_OUTPUT_LAYER_DATA)
		val importLayer: FilterValue get() = valueByIdStr.getValue(INPUT_LAYER_DATA)
		val importLayerSelection: FilterValue get() = valueByIdStr.getValue(SELECTOR_INPUT_LAYER_DATA)
		val currentGuid: FilterValue get() = valueByIdStr.getValue(CURRENT_IMAGE_GUID)
		val selectedSourceGuid: FilterValue get() = valueByIdStr.getValue(SELECTOR_CURRENT_IMAGE_GUID)
		val outputImageValue: FilterValue get() = valueByIdStr.getValue(OUTPUT_IMAGE)
		val outputImageResource: FilterValue get() = valueByIdStr.getValue(SELECTOR_OUTPUT_IMAGE_RESOURCE)
		val layerToCanvasEnv: FilterValue get() = valueByIdStr.getValue(OUTPUT_TRANSFORM)
		val layerToCanvasFilter: FilterValue get() = valueByIdStr.getValue(SELECTOR_OUTPUT_TRANSFORM)

		companion object {
			// CMO3: the FilterValueId idstrs of the model-image filter web, transcribed from the corpus
			// (MultiplyScreenColors.cmo3, identical across files).  "mi_" ids are the env side, "ilf_"
			// ids the filter side.
			private const val INPUT_LAYER_DATA = "mi_input_layerInputData"
			private const val CURRENT_IMAGE_GUID = "mi_currentImageGuid"
			private const val OUTPUT_IMAGE = "mi_output_image"
			private const val OUTPUT_TRANSFORM = "mi_output_transform"
			private const val SELECTOR_INPUT_LAYER_DATA = "ilf_inputLayerData"
			private const val SELECTOR_CURRENT_IMAGE_GUID = "ilf_currentImageGuid"
			private const val SELECTOR_OUTPUT_LAYER_DATA = "ilf_outputLayerData"
			private const val FILTER_INPUT_LAYER = "ilf_inputLayer"
			private const val SELECTOR_OUTPUT_IMAGE_RESOURCE = "ilf_outputImageRes"
			private const val SELECTOR_OUTPUT_TRANSFORM = "ilf_outputTransform"

			/** The ids the connectors are keyed by, every one of which a recovery must find. */
			private val KEY_ID_STRS =
				listOf(
					INPUT_LAYER_DATA,
					CURRENT_IMAGE_GUID,
					OUTPUT_IMAGE,
					OUTPUT_TRANSFORM,
					SELECTOR_INPUT_LAYER_DATA,
					SELECTOR_CURRENT_IMAGE_GUID,
					SELECTOR_OUTPUT_LAYER_DATA,
					FILTER_INPUT_LAYER,
				)

			/** The definitions' own ids, every one of which a recovery must find. */
			private val VALUE_ID_STRS =
				listOf(
					SELECTOR_OUTPUT_LAYER_DATA,
					INPUT_LAYER_DATA,
					SELECTOR_INPUT_LAYER_DATA,
					CURRENT_IMAGE_GUID,
					SELECTOR_CURRENT_IMAGE_GUID,
					OUTPUT_IMAGE,
					SELECTOR_OUTPUT_IMAGE_RESOURCE,
					OUTPUT_TRANSFORM,
					SELECTOR_OUTPUT_TRANSFORM,
				)

			/**
			 * Fresh singletons for a new graph.
			 *
			 * @return FilterCommons The definitions.
			 */
			fun fresh(): FilterCommons {
				val idByIdStr = HashMap<String, Id>()

				/**
				 * The one Id per idstr.
				 *
				 * @param String idStr The idstr.
				 * @return Id The id.
				 */
				fun valueId(idStr: String): Id = idByIdStr.getOrPut(idStr) { Id("FilterValueId").apply { idstr = idStr } }

				/**
				 * A FilterValue definition.
				 *
				 * @param String displayName The editor's display name for the value.
				 * @param String idStr       The value's own id.
				 * @return FilterValue The definition.
				 */
				fun value(displayName: String, idStr: String): FilterValue =
					FilterValue().apply {
						name = displayName
						id = valueId(idStr)
					}
				// CMO3: the FilterValue definitions - names and value-id wiring transcribed from the
				// corpus (MultiplyScreenColors.cmo3, identical across files).
				val values =
					listOf(
						value("Select Layer", SELECTOR_OUTPUT_LAYER_DATA),
						value("Import Layer", INPUT_LAYER_DATA),
						value("Import Layer selection", SELECTOR_INPUT_LAYER_DATA),
						value("Current GUID", CURRENT_IMAGE_GUID),
						value("GUID of Selected Source Image", SELECTOR_CURRENT_IMAGE_GUID),
						value("Output image", OUTPUT_IMAGE),
						value("Output Image (Resource Format)", SELECTOR_OUTPUT_IMAGE_RESOURCE),
						value("LayerToCanvas変換", OUTPUT_TRANSFORM),
						value("LayerToCanvas変換", SELECTOR_OUTPUT_TRANSFORM),
					)

				for (idStr in KEY_ID_STRS) {
					valueId(idStr)
				}

				return FilterCommons(
					idByIdStr,
					values.associateBy { value -> (value.id as Id).idstr },
					Guid("StaticFilterDefGuid").apply {
						uuid = LAYER_SELECTOR_DEF_UUID
						note = "(no debug info)"
					},
					Guid("StaticFilterDefGuid").apply {
						uuid = LAYER_FILTER_DEF_UUID
						note = "(no debug info)"
					},
				)
			}

			/**
			 * The singletons a retained graph's model image already wires, so an image minted beside it
			 * shares the same objects; null when the set does not carry the full web (a graph another
			 * writer produced), in which case the caller falls back to [fresh].
			 *
			 * @param ModelImageFilterSet filterSet A retained model image's inputFilter.
			 * @return FilterCommons? The recovered definitions, or null.
			 */
			fun fromFilterSet(filterSet: ModelImageFilterSet): FilterCommons? {
				val idByIdStr = HashMap<String, Id>()
				val valueByIdStr = HashMap<String, FilterValue>()
				var selectorDefGuid: Guid? = null
				var layerFilterDefGuid: Guid? = null

				/**
				 * Records an id the web references.
				 *
				 * @param Any? candidate A key or a connector's id slot.
				 */
				fun noteId(candidate: Any?) {
					val id = candidate as? Id ?: return
					idByIdStr.putIfAbsent(id.idstr, id)
				}

				/**
				 * Records a definition the web references, and its own id.
				 *
				 * @param Any? candidate A connector's definition slot.
				 */
				fun noteValue(candidate: Any?) {
					val value = candidate as? FilterValue ?: return
					val id = value.id as? Id ?: return
					noteId(id)
					valueByIdStr.putIfAbsent(id.idstr, value)
				}

				// CMO3: ModelImageFilterSet field filterMap -> FilterInstance fields filterName /
				// filterDefGuid / inputConnectors / outputConnectors, the connectors keyed by value id.
				for (instance in (filterSet.filterMap as? Map<*, *>)?.values.orEmpty().filterIsInstance<FilterInstance>()) {
					when (instance.filterName) {
						"CLayerSelector" -> selectorDefGuid = instance.filterDefGuid as? Guid
						"CLayerFilter" -> layerFilterDefGuid = instance.filterDefGuid as? Guid
					}

					for ((key, connector) in (instance.inputConnectors as? Map<*, *>).orEmpty()) {
						noteId(key)
						noteId((connector as? EnvValueConnector)?.envValueId)
					}

					for ((key, connector) in (instance.outputConnectors as? Map<*, *>).orEmpty()) {
						noteId(key)
						val output = connector as? FilterOutputValueConnector ?: continue
						noteId(output.id)
						noteValue(output.valueDef)
					}
				}

				// CMO3: ModelImageFilterSet fields _externalInputs / _externalOutputs -> EnvConnection
				// fields _envValueDef / filterValueDef.
				for (external in listOf(filterSet._externalInputs, filterSet._externalOutputs)) {
					for ((key, connection) in (external as? Map<*, *>).orEmpty()) {
						noteId(key)
						val envConnection = connection as? EnvConnection ?: continue
						noteValue(envConnection._envValueDef)
						noteValue(envConnection.filterValueDef)
					}
				}

				val selector = selectorDefGuid ?: return null
				val layerFilter = layerFilterDefGuid ?: return null

				if (KEY_ID_STRS.any { idStr -> idStr !in idByIdStr } || VALUE_ID_STRS.any { idStr -> idStr !in valueByIdStr }) {
					return null
				}

				return FilterCommons(idByIdStr, valueByIdStr, selector, layerFilter)
			}
		}
	}

	/**
	 * Builds one model image's ModelImageFilterSet: a CLayerSelector instance feeding a CLayerFilter,
	 * with the external input/output connections the editor's model-image pipeline expects.
	 *
	 * @param FilterCommons commons The per-document shared definitions.
	 * @return ModelImageFilterSet The fresh filter set.
	 */
	internal fun buildFilterSet(commons: FilterCommons): ModelImageFilterSet {
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
	 * One model image over one layer: the editor's layer-filter web selecting [layer] at identity,
	 * the layer's own resource as the filtered image, the padded cache manager over it, the icon of
	 * its pixels, and the canvas placement.  Shared by the crop web and the real-layer web, so a crop
	 * and a real layer carry field-for-field the same shape around their pixels.
	 *
	 * @param String           name         The model image's name (the drawable's, as the editor writes it).
	 * @param CLayeredImage    layeredImage The layered image the layer belongs to.
	 * @param CLayer           layer        The layer the image composites.
	 * @param CImageResource   resource     The layer's own resource, which is also the filtered image.
	 * @param RasterImage      raster       The resource's pixels, which the icon shows fitted.
	 * @param CAffine          placement    The material-local-to-canvas placement, an independent instance.
	 * @param CModelImageGroup group        The group the image belongs to.
	 * @param Cmo3FreshChainNames names     The document's shared filter definitions and naming counters.
	 * @param MutableList      pngEntries   The PNG entry collector, for the icon.
	 * @param Long             nowMillis    The timestamp the env values record.
	 * @return CModelImage The model image.
	 */
	internal fun modelImageOver(
		name: String,
		layeredImage: CLayeredImage,
		layer: CLayer,
		resource: CImageResource,
		raster: RasterImage,
		placement: CAffine,
		group: CModelImageGroup,
		names: Cmo3FreshChainNames,
		pngEntries: MutableList<Cmo3FreshFile.PngEntry>,
		nowMillis: Long,
	): CModelImage =
		CModelImage().apply {
			guid = Cmo3SkeletonBuilder.freshGuid("CModelImageGuid")
			this.name = name
			// CMO3: CModelImage fields inputFilter / inputFilterEnv - the layer-filter web selecting
			// this layer; the deserializer dereferences both.
			inputFilter = buildFilterSet(names.filters)
			inputFilterEnv =
				ModelImageFilterEnv().apply {
					envValues =
						CHashMap<Any?, Any?>().apply {
							put(
								names.filters.currentImageGuid,
								EnvValueSet().apply {
									id = names.filters.currentImageGuid
									value = layeredImage.guid
									updateTimeMs = nowMillis
								},
							)
							put(
								names.filters.inputLayerData,
								EnvValueSet().apply {
									id = names.filters.inputLayerData
									value =
										CLayerSelectorMap().apply {
											_imageToLayerInput =
												LinkedHashMap<Any?, Any?>().apply {
													put(
														layeredImage.guid,
														ArrayList<Any?>(
															mutableListOf(
																CLayerInputData().apply {
																	this.layer = layer
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
			_filteredImage = resource
			// CMO3: CModelImage fields icon16 / cachedImageManager - present on every corpus model
			// image; the icon is the filtered image fitted into its square.
			icon16 = Cmo3Icons.iconOf(raster, 16, names.nextIconPath(), pngEntries)
			// CMO3: CModelImage field _materialLocalToCanvasTransform - the layer's canvas placement
			// (official layers carry their canvas origin here), the same numbers the layer's
			// boundsOnImageDoc origin carries.
			_materialLocalToCanvasTransform = placement
			_group = group
			linkedRawImageGuids = CArrayList<Any?>(mutableListOf(layeredImage.guid))
			cachedImageManager = Cmo3PageWeb.paddedCacheManager(resource, resource.width, resource.height)
			memo = ""
		}
}