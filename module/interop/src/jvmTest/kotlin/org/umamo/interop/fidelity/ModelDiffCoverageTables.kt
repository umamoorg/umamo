package org.umamo.interop.fidelity

import org.umamo.interop.AtlasTileField
import org.umamo.interop.DeformerField
import org.umamo.interop.DocumentField
import org.umamo.interop.DrawableField
import org.umamo.interop.GlueField
import org.umamo.interop.ParameterField
import org.umamo.interop.ParameterGroupField
import org.umamo.interop.PartField
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm

private const val PART_BLEND_SHAPES_NOT_CARRIED = "the CMO3 import does not ingest a part's morph targets and no CMO3 lowering writes them"

private const val PART_FORM_NOT_CARRIED = "a form of a part's blend shape, which a CMO3 export drops whole"

private const val DEFORMER_VISIBILITY_NOT_CARRIED = "no CMO3 lowering writes ACParameterControllableSource.isVisible, though the import reads it"

private const val DEFORMER_ENABLED_NOT_CARRIED =
	"a MOC3-only flag (MOC3 §5.6 s14) with no CMO3 field; every corpus deformer sets it, and its meaning is unpinned"

/**
 * A copy of the array with its first component moved, for an edit that must change an array's content.
 *
 * @param FloatArray values The array.
 * @return FloatArray The moved copy.
 */
private fun moved(values: FloatArray): FloatArray = values.copyOf().also { copy -> copy[0] += 1f }

/** A one-key opacity track, for an edit that keys a channel. */
private fun opacityTrack(): ChannelGrids =
	ChannelGrids(
		mapOf(FormChannel.OPACITY to KeyformGrid(listOf(KeyformAxis(ParameterId("ParamX"), floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), ChannelValue.Scalar(0.25f) as ChannelValue)))),
	)

/**
 * A deformer with the properties [Deformer] declares replaced, whichever kind it is.  Exhaustive over the sealed
 * type, so a new deformer kind fails to compile here until it has a table.
 *
 * @param DeformerId id The id.
 * @param String name The name.
 * @param DeformerId? parent The parent deformer.
 * @param PartId? partId The owning part.
 * @param ChannelGrids channelGrids The channel tracks.
 * @param Boolean isSelectable The selectable toggle.
 * @param Boolean isVisible The eye toggle.
 * @param Boolean isEnabled The MOC3 enabled flag.
 * @return TDeformer The edited deformer.
 */
@Suppress("UNCHECKED_CAST")
private fun <TDeformer : Deformer> TDeformer.withDeformerFields(
	id: DeformerId = this.id,
	name: String = this.name,
	parent: DeformerId? = this.parent,
	partId: PartId? = this.partId,
	channelGrids: ChannelGrids = this.channelGrids,
	isSelectable: Boolean = this.isSelectable,
	isVisible: Boolean = this.isVisible,
	isEnabled: Boolean = this.isEnabled,
): TDeformer =
	when (val deformer: Deformer = this) {
		is Deformer.Warp -> deformer.copy(id = id, name = name, parent = parent, partId = partId, channelGrids = channelGrids, isSelectable = isSelectable, isVisible = isVisible, isEnabled = isEnabled)
		is Deformer.Rotation -> deformer.copy(id = id, name = name, parent = parent, partId = partId, channelGrids = channelGrids, isSelectable = isSelectable, isVisible = isVisible, isEnabled = isEnabled)
	} as TDeformer

/** Places the properties [Deformer] declares, which every deformer kind carries. */
private fun <TDeformer : Deformer> CoverageTableBuilder<TDeformer>.deformerEntries() {
	compared("id", DiffCategory.DEFORMERS) { withDeformerFields(id = DeformerId("DeformerRenamed")) }
	compared("name", DeformerField.NAME) { withDeformerFields(name = "Renamed") }
	compared("parent", DeformerField.PARENT) { withDeformerFields(parent = DeformerId("DeformerElsewhere")) }
	compared("partId", DeformerField.PART) { withDeformerFields(partId = PartId("PartElsewhere")) }
	compared("channelGrids", DeformerField.CHANNELS) { withDeformerFields(channelGrids = opacityTrack()) }
	compared("isSelectable", DeformerField.SELECTABLE) { withDeformerFields(isSelectable = !isSelectable) }
	notCarried("isVisible", DEFORMER_VISIBILITY_NOT_CARRIED, KnownExportLoss.DEFORMER_VISIBILITY) { withDeformerFields(isVisible = !isVisible) }
	notCarried("isEnabled", DEFORMER_ENABLED_NOT_CARRIED) { withDeformerFields(isEnabled = !isEnabled) }
}

/**
 * Every model class's coverage table: the seven entity classes, then the value types the diff compares member by
 * member.  Types the diff compares with a data class's own equality need no table, since a property added to one is
 * compared without anyone placing it.
 */
internal object ModelDiffCoverageTables {
	val puppetModel =
		coverageOf(PuppetModel::class.java, CoverageFixture.modelRoute) {
			compared("parameters", DiffCategory.PARAMETERS) { copy(parameters = parameters.dropLast(1)) }
			compared("parts", DiffCategory.PARTS) { copy(parts = parts + Part(PartId("PartNew"), "PartNew", emptyList())) }
			compared("deformers", DiffCategory.DEFORMERS) { copy(deformers = deformers.dropLast(1)) }
			compared("drawables", DiffCategory.DRAWABLES) { copy(drawables = drawables.dropLast(1)) }
			compared("rootChildren", DocumentField.ROOT_CHILDREN) { copy(rootChildren = rootChildren + OrgChild.Drawable(DrawableId("MeshB"))) }
			notCarried("rootPartId", "the export keeps the root part its graph holds, and a fresh graph mints its own") { copy(rootPartId = PartId("PartA")) }
			compared("glues", DiffCategory.GLUES) { copy(glues = emptyList()) }
			derived(
				"renderRoot",
				"built from the org tree, the group modes, and the draw orders, which the diff compares, and rebuilt from them by the CMO3 import",
			) { copy(renderRoot = RenderGroup(null, DEFAULT_DRAW_ORDER, emptyList())) }
			compared("parameterLinks", DocumentField.PARAMETER_LINKS) { copy(parameterLinks = emptyList()) }
			compared("parameterTree", DocumentField.PARAMETER_TREE) { copy(parameterTree = parameterTree.drop(1)) }
			compared("canvasWidth", DocumentField.CANVAS_SIZE) { copy(canvasWidth = canvasWidth + 1f) }
			compared("canvasHeight", DocumentField.CANVAS_SIZE) { copy(canvasHeight = canvasHeight + 1f) }
			compared("worldOriginX", DocumentField.WORLD_ORIGIN) { copy(worldOriginX = worldOriginX + 1f) }
			compared("worldOriginZ", DocumentField.WORLD_ORIGIN) { copy(worldOriginZ = worldOriginZ + 1f) }
			notCarried(
				"pixelsPerUnit",
				"CModelInfo.pixelsPerUnit is 1 in every corpus CMO3, so it is not where the editor keeps the export scale",
				KnownExportLoss.PIXELS_PER_UNIT,
			) { copy(pixelsPerUnit = 3000f) }
			compared("runtimeTarget", DocumentField.RUNTIME_TARGET) { copy(runtimeTarget = RuntimeTarget.Cubism42) }
			compared("rendersFromSourceLayers", DocumentField.SOURCE_LAYER_DISPLAY) { copy(rendersFromSourceLayers = !rendersFromSourceLayers) }
			compared("atlas", AtlasTileField.PLACEMENT) {
				copy(atlas = atlas.copy(tiles = atlas.tiles.map { tile -> tile.copy(placement = tile.placement?.copy(positionX = 12f)) }))
			}
			compared("sources", DocumentField.SOURCE_FILES) { copy(sources = sources.map { source -> source.copy(path = "/moved/a.psd") }) }
			derived("partById\$delegate", "a lookup cache over parts")
			derived("deformerById\$delegate", "a lookup cache over deformers")
		}

	val parameter =
		coverageOf(Parameter::class.java, CoverageFixture.parameterRoute) {
			compared("id", DiffCategory.PARAMETERS, DocumentField.PARAMETER_ORDER) { copy(id = ParameterId("ParamRenamed")) }
			compared("name", ParameterField.NAME) { copy(name = "Renamed") }
			compared("min", ParameterField.RANGE) { copy(min = min - 1f) }
			compared("max", ParameterField.RANGE) { copy(max = max + 1f) }
			compared("default", ParameterField.RANGE) { copy(default = 0.5f) }
			compared("kind", ParameterField.KIND) { copy(kind = ParameterKind.BLEND_SHAPE) }
			compared("repeat", ParameterField.REPEAT) { copy(repeat = !repeat) }
		}

	val part =
		coverageOf(Part::class.java, CoverageFixture.partRoute) {
			compared("id", DiffCategory.PARTS) { copy(id = PartId("PartRenamed")) }
			compared("name", PartField.NAME) { copy(name = "Renamed") }
			compared("children", PartField.CHILDREN) { copy(children = children.dropLast(1)) }
			compared("isVisible", PartField.VISIBLE) { copy(isVisible = !isVisible) }
			compared("isSketch", PartField.SKETCH) { copy(isSketch = !isSketch) }
			compared("isSelectable", PartField.SELECTABLE) { copy(isSelectable = !isSelectable) }
			compared("groupMode", PartField.GROUP_MODE) { copy(groupMode = PartGroupMode.Grouped) }
			compared("drawOrder", PartField.DRAW_ORDER) { copy(drawOrder = drawOrder + 1) }
			compared("channelGrids", PartField.CHANNELS) { copy(channelGrids = opacityTrack()) }
			compared("composite", PartField.COMPOSITE) { copy(composite = composite.copy(blendMode = BlendMode.Multiply)) }
			notCarried("blendShapes", PART_BLEND_SHAPES_NOT_CARRIED, KnownExportLoss.PART_BLEND_SHAPES) { copy(blendShapes = emptyList()) }
		}

	val warp =
		coverageOf(Deformer.Warp::class.java, CoverageFixture.warpRoute) {
			deformerEntries()
			compared("rows", DeformerField.LATTICE) { copy(rows = rows + 1) }
			compared("columns", DeformerField.LATTICE) { copy(columns = columns + 1) }
			compared("isQuadTransform", DeformerField.QUAD_TRANSFORM) { copy(isQuadTransform = !isQuadTransform) }
			compared("geometryGrid", DeformerField.GEOMETRY) { copy(geometryGrid = null) }
			compared("opacity", DeformerField.STATICS) { copy(opacity = 0.5f) }
			compared("multiplyColor", DeformerField.STATICS) { copy(multiplyColor = ColorRgb(0.5f, 1f, 1f)) }
			compared("screenColor", DeformerField.STATICS) { copy(screenColor = ColorRgb(0.5f, 0f, 0f)) }
			compared("blendShapes", DeformerField.BLEND_SHAPES) { copy(blendShapes = emptyList()) }
		}

	val rotation =
		coverageOf(Deformer.Rotation::class.java, CoverageFixture.rotationRoute) {
			deformerEntries()
			compared("baseAngle", DeformerField.BASE_ANGLE) { copy(baseAngle = baseAngle + 15f) }
			compared("geometryGrid", DeformerField.GEOMETRY) { copy(geometryGrid = null) }
			compared("opacity", DeformerField.STATICS) { copy(opacity = 0.5f) }
			compared("multiplyColor", DeformerField.STATICS) { copy(multiplyColor = ColorRgb(0.5f, 1f, 1f)) }
			compared("screenColor", DeformerField.STATICS) { copy(screenColor = ColorRgb(0.5f, 0f, 0f)) }
			compared("flipX", DeformerField.STATICS) { copy(flipX = !flipX) }
			compared("flipY", DeformerField.STATICS) { copy(flipY = !flipY) }
			compared("blendShapes", DeformerField.BLEND_SHAPES) { copy(blendShapes = emptyList()) }
		}

	val drawable =
		coverageOf(Drawable::class.java, CoverageFixture.drawableRoute) {
			compared("id", DiffCategory.DRAWABLES) { copy(id = DrawableId("MeshRenamed")) }
			compared("name", DrawableField.NAME) { copy(name = "Renamed") }
			compared("parentDeformerId", DrawableField.PARENT_DEFORMER) { copy(parentDeformerId = null) }
			compared("blendMode", DrawableField.BLEND_MODE) { copy(blendMode = BlendMode.Multiply) }
			compared("maskedBy", DrawableField.MASKED_BY) { copy(maskedBy = listOf(DrawableId("MeshB"))) }
			compared("mesh", DrawableField.MESH_TOPOLOGY) { copy(mesh = null) }
			compared("geometryGrid", DrawableField.GEOMETRY) { copy(geometryGrid = null) }
			compared("channelGrids", DrawableField.CHANNELS) { copy(channelGrids = ChannelGrids.Empty) }
			compared("drawOrder", DrawableField.STATICS) { copy(drawOrder = drawOrder + 1f) }
			compared("opacity", DrawableField.STATICS) { copy(opacity = 0.5f) }
			compared("multiplyColor", DrawableField.STATICS) { copy(multiplyColor = ColorRgb(0.5f, 1f, 1f)) }
			compared("screenColor", DrawableField.STATICS) { copy(screenColor = ColorRgb(0.5f, 0f, 0f)) }
			compared("invertMask", DrawableField.INVERT_MASK) { copy(invertMask = !invertMask) }
			compared("alphaBlendMode", DrawableField.ALPHA_BLEND_MODE) { copy(alphaBlendMode = AlphaBlendMode.Atop) }
			compared("culling", DrawableField.CULLING) { copy(culling = !culling) }
			compared("isVisible", DrawableField.VISIBLE) { copy(isVisible = !isVisible) }
			compared("isSelectable", DrawableField.SELECTABLE) { copy(isSelectable = !isSelectable) }
			compared("textureSourceId", DrawableField.TEXTURE_SOURCE) { copy(textureSourceId = DrawableId("MeshB")) }
			notCarried(
				"texturePage",
				"a CMO3 has no per-drawable page field: it binds art through the layered-art web, so the fidelity gates skip the page too",
			) { copy(texturePage = 1) }
			compared("atlasTileId", DrawableField.ATLAS_TILE) { copy(atlasTileId = AtlasTileId("TileElsewhere")) }
			compared("blendShapes", DrawableField.BLEND_SHAPES) { copy(blendShapes = emptyList()) }
		}

	val glue =
		coverageOf(Glue::class.java, CoverageFixture.glueRoute) {
			compared("meshA", DiffCategory.GLUES) { copy(meshA = DrawableId("MeshElsewhere")) }
			compared("meshB", DiffCategory.GLUES) { copy(meshB = DrawableId("MeshElsewhere")) }
			compared("pairs", GlueField.PAIRS) { copy(pairs = emptyList()) }
			compared("channelGrids", GlueField.CHANNELS) { copy(channelGrids = opacityTrack()) }
			compared("intensity", GlueField.INTENSITY) { copy(intensity = 0.5f) }
			notCarried("id", "the CMO3 import leaves a glue's id unset and no CMO3 lowering writes one", KnownExportLoss.GLUE_IDS) { copy(id = "Glue_0_1_") }
		}

	val drawableMesh =
		coverageOf(DrawableMesh::class.java, CoverageFixture.meshRoute) {
			compared("positions", DrawableField.MESH_POSITIONS) { DrawableMesh(moved(positions), localPositions, uvs, indices) }
			// The keyform-space base is not a mesh field: what moving it changes is the keyforms.
			compared("localPositions", DrawableField.GEOMETRY) { DrawableMesh(positions, moved(localPositions), uvs, indices) }
			compared("uvs", DrawableField.MESH_UVS) { DrawableMesh(positions, localPositions, moved(uvs), indices) }
			compared("indices", DrawableField.MESH_TOPOLOGY) { DrawableMesh(positions, localPositions, uvs, intArrayOf(0, 2, 1)) }
		}

	val keyformGrid =
		coverageOf<KeyformGrid<MeshDeltaForm>>(KeyformGrid::class.java, CoverageFixture.meshGridRoute) {
			compared("axes", DrawableField.GEOMETRY) { KeyformGrid(listOf(KeyformAxis(ParameterId("ParamX"), floatArrayOf(0f, 0.5f))), cells) }
			compared("cells", DrawableField.GEOMETRY) { KeyformGrid(axes, cells.dropLast(1)) }
			derived("cellsByLinearIndex\$delegate", "a lookup cache over cells")
		}

	val keyformAxis =
		coverageOf(KeyformAxis::class.java, CoverageFixture.meshAxisRoute) {
			compared("parameterId", DrawableField.GEOMETRY) { KeyformAxis(ParameterId("ParamY"), keys) }
			compared("keys", DrawableField.GEOMETRY) { KeyformAxis(parameterId, floatArrayOf(0f, 0.5f)) }
		}

	val keyformCell =
		coverageOf<KeyformCell<MeshDeltaForm>>(KeyformCell::class.java, CoverageFixture.meshCellRoute) {
			compared("coordinate", DrawableField.GEOMETRY) { KeyformCell(intArrayOf(0), form) }
			compared("form", DrawableField.GEOMETRY) { KeyformCell(coordinate, MeshDeltaForm(moved(form.positionDeltas))) }
		}

	val meshDeltaForm =
		coverageOf(MeshDeltaForm::class.java, CoverageFixture.meshDeltaFormRoute) {
			compared("positionDeltas", DrawableField.GEOMETRY) { MeshDeltaForm(moved(positionDeltas)) }
		}

	val warpLatticeForm =
		coverageOf(WarpLatticeForm::class.java, CoverageFixture.warpLatticeFormRoute) {
			compared("controlPoints", DeformerField.GEOMETRY) { WarpLatticeForm(moved(controlPoints)) }
		}

	val rotationPivotForm =
		coverageOf(RotationPivotForm::class.java, CoverageFixture.rotationPivotFormRoute) {
			compared("originX", DeformerField.GEOMETRY) { RotationPivotForm(originX + 1f, originY, angle, scale) }
			compared("originY", DeformerField.GEOMETRY) { RotationPivotForm(originX, originY + 1f, angle, scale) }
			compared("angle", DeformerField.GEOMETRY) { RotationPivotForm(originX, originY, angle + 1f, scale) }
			compared("scale", DeformerField.GEOMETRY) { RotationPivotForm(originX, originY, angle, scale + 1f) }
		}

	val meshForm =
		coverageOf(MeshForm::class.java, CoverageFixture.meshFormRoute) {
			compared("positionDeltas", DrawableField.BLEND_SHAPES) { MeshForm(moved(positionDeltas), drawOrder, opacity, multiplyColor, screenColor) }
			compared("drawOrder", DrawableField.BLEND_SHAPES) { MeshForm(positionDeltas, drawOrder + 1f, opacity, multiplyColor, screenColor) }
			compared("opacity", DrawableField.BLEND_SHAPES) { MeshForm(positionDeltas, drawOrder, 0.5f, multiplyColor, screenColor) }
			compared("multiplyColor", DrawableField.BLEND_SHAPES) { MeshForm(positionDeltas, drawOrder, opacity, ColorRgb(0.5f, 1f, 1f), screenColor) }
			compared("screenColor", DrawableField.BLEND_SHAPES) { MeshForm(positionDeltas, drawOrder, opacity, multiplyColor, ColorRgb(0.5f, 0f, 0f)) }
		}

	val warpForm =
		coverageOf(WarpForm::class.java, CoverageFixture.warpFormRoute) {
			compared("controlPoints", DeformerField.BLEND_SHAPES) { WarpForm(moved(controlPoints), opacity, multiplyColor, screenColor) }
			compared("opacity", DeformerField.BLEND_SHAPES) { WarpForm(controlPoints, 0.5f, multiplyColor, screenColor) }
			compared("multiplyColor", DeformerField.BLEND_SHAPES) { WarpForm(controlPoints, opacity, ColorRgb(0.5f, 1f, 1f), screenColor) }
			compared("screenColor", DeformerField.BLEND_SHAPES) { WarpForm(controlPoints, opacity, multiplyColor, ColorRgb(0.5f, 0f, 0f)) }
		}

	val rotationForm =
		coverageOf(RotationForm::class.java, CoverageFixture.rotationFormRoute) {
			compared("originX", DeformerField.BLEND_SHAPES) { RotationForm(originX + 1f, originY, angle, scale, flipX, flipY, opacity, multiplyColor, screenColor) }
			compared("originY", DeformerField.BLEND_SHAPES) { RotationForm(originX, originY + 1f, angle, scale, flipX, flipY, opacity, multiplyColor, screenColor) }
			compared("angle", DeformerField.BLEND_SHAPES) { RotationForm(originX, originY, angle + 1f, scale, flipX, flipY, opacity, multiplyColor, screenColor) }
			compared("scale", DeformerField.BLEND_SHAPES) { RotationForm(originX, originY, angle, scale + 1f, flipX, flipY, opacity, multiplyColor, screenColor) }
			compared("flipX", DeformerField.BLEND_SHAPES) { RotationForm(originX, originY, angle, scale, !flipX, flipY, opacity, multiplyColor, screenColor) }
			compared("flipY", DeformerField.BLEND_SHAPES) { RotationForm(originX, originY, angle, scale, flipX, !flipY, opacity, multiplyColor, screenColor) }
			compared("opacity", DeformerField.BLEND_SHAPES) { RotationForm(originX, originY, angle, scale, flipX, flipY, 0.5f, multiplyColor, screenColor) }
			compared("multiplyColor", DeformerField.BLEND_SHAPES) { RotationForm(originX, originY, angle, scale, flipX, flipY, opacity, ColorRgb(0.5f, 1f, 1f), screenColor) }
			compared("screenColor", DeformerField.BLEND_SHAPES) { RotationForm(originX, originY, angle, scale, flipX, flipY, opacity, multiplyColor, ColorRgb(0.5f, 0f, 0f)) }
		}

	val partForm =
		coverageOf(PartForm::class.java, CoverageFixture.partFormRoute) {
			notCarried("drawOrder", PART_FORM_NOT_CARRIED, KnownExportLoss.PART_BLEND_SHAPES) { PartForm(drawOrder + 1f, opacity, multiplyColor, screenColor) }
			notCarried("opacity", PART_FORM_NOT_CARRIED, KnownExportLoss.PART_BLEND_SHAPES) { PartForm(drawOrder, 0.5f, multiplyColor, screenColor) }
			notCarried("multiplyColor", PART_FORM_NOT_CARRIED, KnownExportLoss.PART_BLEND_SHAPES) { PartForm(drawOrder, opacity, ColorRgb(0.5f, 1f, 1f), screenColor) }
			notCarried("screenColor", PART_FORM_NOT_CARRIED, KnownExportLoss.PART_BLEND_SHAPES) { PartForm(drawOrder, opacity, multiplyColor, ColorRgb(0.5f, 0f, 0f)) }
		}

	val blendShapeBinding =
		coverageOf<BlendShapeBinding<MeshForm>>(BlendShapeBinding::class.java, CoverageFixture.meshBlendShapeRoute) {
			compared("parameterId", DrawableField.BLEND_SHAPES) { copy(parameterId = ParameterId("ParamX")) }
			compared("keys", DrawableField.BLEND_SHAPES) { copy(keys = floatArrayOf(0f, 0.5f)) }
			compared("neutralIndex", DrawableField.BLEND_SHAPES) { copy(neutralIndex = 1) }
			compared("forms", DrawableField.BLEND_SHAPES) { copy(forms = listOf(null, null)) }
			compared("limits", DrawableField.BLEND_SHAPES) {
				copy(limits = listOf(BlendWeightLimit(ParameterId("ParamX"), listOf(BlendWeightLimitPoint(0f, 1f)))))
			}
		}

	val gluePair =
		coverageOf(GluePair::class.java, CoverageFixture.gluePairRoute) {
			compared("indexA", GlueField.PAIRS) { GluePair(indexA + 1, indexB, weightA, weightB) }
			compared("indexB", GlueField.PAIRS) { GluePair(indexA, indexB + 1, weightA, weightB) }
			compared("weightA", GlueField.PAIRS) { GluePair(indexA, indexB, weightA + 0.25f, weightB) }
			compared("weightB", GlueField.PAIRS) { GluePair(indexA, indexB, weightA, weightB + 0.25f) }
		}

	val channelGrids =
		coverageOf(ChannelGrids::class.java, CoverageFixture.channelGridsRoute) {
			compared("gridsByChannel", DrawableField.CHANNELS) { ChannelGrids(emptyMap()) }
		}

	val parameterGroup =
		coverageOf(ParameterNode.Group::class.java, CoverageFixture.groupRoute) {
			compared("id", DiffCategory.PARAMETER_GROUPS, DocumentField.PARAMETER_TREE) { copy(id = ParameterGroupId("GroupRenamed")) }
			compared("name", ParameterGroupField.NAME) { copy(name = "Renamed") }
			compared("initiallyOpen", ParameterGroupField.INITIALLY_OPEN) { copy(initiallyOpen = !initiallyOpen) }
			compared("children", ParameterGroupField.CHILDREN) { copy(children = children.dropLast(1)) }
		}

	val parameterLeaf =
		coverageOf(ParameterNode.Param::class.java, CoverageFixture.paramNodeRoute) {
			compared("id", ParameterGroupField.CHILDREN) { copy(id = ParameterId("ParamElsewhere")) }
		}

	/** Every table, in the order the model nests its classes. */
	val all: List<ClassCoverage<*>> =
		listOf(
			puppetModel,
			parameter,
			part,
			warp,
			rotation,
			drawable,
			glue,
			drawableMesh,
			keyformGrid,
			keyformAxis,
			keyformCell,
			meshDeltaForm,
			warpLatticeForm,
			rotationPivotForm,
			meshForm,
			warpForm,
			rotationForm,
			partForm,
			blendShapeBinding,
			gluePair,
			channelGrids,
			parameterGroup,
			parameterLeaf,
		)

	/**
	 * The table for one deformer kind.  Exhaustive over the sealed type, so a new kind fails to compile until it has one.
	 *
	 * @param Deformer deformer A deformer of the kind.
	 * @return ClassCoverage The kind's table.
	 */
	fun forDeformer(deformer: Deformer): ClassCoverage<*> =
		when (deformer) {
			is Deformer.Warp -> warp
			is Deformer.Rotation -> rotation
		}

	/**
	 * The table for one parameter-tree node kind.  Exhaustive over the sealed type, so a new kind fails to compile
	 * until it has one.
	 *
	 * @param ParameterNode node A node of the kind.
	 * @return ClassCoverage The kind's table.
	 */
	fun forParameterNode(node: ParameterNode): ClassCoverage<*> =
		when (node) {
			is ParameterNode.Param -> parameterLeaf
			is ParameterNode.Group -> parameterGroup
		}
}