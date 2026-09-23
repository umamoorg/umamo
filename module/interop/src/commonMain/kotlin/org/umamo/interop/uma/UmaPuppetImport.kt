package org.umamo.interop.uma

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import org.umamo.format.uma.puppet.UmaAxis
import org.umamo.format.uma.puppet.UmaBlendLimit
import org.umamo.format.uma.puppet.UmaChannelGrid
import org.umamo.format.uma.puppet.UmaDeformer
import org.umamo.format.uma.puppet.UmaDeformerBlendShape
import org.umamo.format.uma.puppet.UmaDeformerGrid
import org.umamo.format.uma.puppet.UmaDeformerKind
import org.umamo.format.uma.puppet.UmaDrawable
import org.umamo.format.uma.puppet.UmaFormChannel
import org.umamo.format.uma.puppet.UmaGlue
import org.umamo.format.uma.puppet.UmaMeshBlendShape
import org.umamo.format.uma.puppet.UmaMeshGrid
import org.umamo.format.uma.puppet.UmaOrgRef
import org.umamo.format.uma.puppet.UmaParameter
import org.umamo.format.uma.puppet.UmaParameterNode
import org.umamo.format.uma.puppet.UmaPart
import org.umamo.format.uma.puppet.UmaPartBlendShape
import org.umamo.format.uma.puppet.UmaPartComposite
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ChannelValueKind
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
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * Builds a [PuppetModel] from the puppet entry (docs/format/UMA.md §4): restores each absent value to the
 * model's default and derives the render root from the organizational tree (UMA §4.2).
 *
 * The entry comes from the codec, which has already refused every shape the model cannot hold (UMA §4.8): a field
 * that belongs to another kind of object, arrays that disagree in size, and indices or keys out of range or order.
 * Ids are taken verbatim and references are not resolved: a reference to an object the entry does not hold is
 * carried as it is, never dropped.
 */
object UmaPuppetImport {
	/**
	 * The model [puppet] describes.
	 *
	 * @param UmaPuppet puppet The puppet entry's content, as the codec decoded it (`UmaModel.puppet`).
	 * @return PuppetModel The model.
	 */
	fun modelOf(puppet: UmaPuppet): PuppetModel {
		val model =
			PuppetModel(
				parameters = puppet.parameters.orEmpty().map(::parameterOf),
				parts = puppet.parts.orEmpty().map(::partOf),
				deformers = puppet.deformers.orEmpty().map(::deformerOf),
				drawables = puppet.drawables.orEmpty().map(::drawableOf),
				glues = puppet.glues.orEmpty().map(::glueOf),
				rootChildren = puppet.rootChildren.orEmpty().map(::orgChildOf),
				rootPartId = puppet.rootPart?.let(::PartId),
				parameterLinks = puppet.parameterLinks.orEmpty().map { link -> ParameterLink(ParameterId(link.horizontal), ParameterId(link.vertical)) },
				parameterTree = puppet.parameterTree.orEmpty().map(::parameterNodeOf),
				canvasWidth = puppet.canvasWidth ?: 0f,
				canvasHeight = puppet.canvasHeight ?: 0f,
				worldOriginX = puppet.worldOriginX ?: 0f,
				worldOriginZ = puppet.worldOriginZ ?: 0f,
				pixelsPerUnit = puppet.pixelsPerUnit,
				runtimeTarget = puppet.runtimeTarget?.toRuntime() ?: RuntimeTarget.NoTarget,
				rendersFromSourceLayers = puppet.rendersFromSourceLayers ?: false,
			)
		// UMA §4.2: the render root is never written; it is always the organizational tree's derivation.
		return model.withDerivedRenderRoot()
	}

	/**
	 * UMA §4.3: one parameter.
	 *
	 * @param UmaParameter record The record.
	 * @return Parameter The parameter.
	 */
	private fun parameterOf(record: UmaParameter): Parameter =
		Parameter(
			id = ParameterId(record.id),
			name = record.name,
			min = record.min,
			max = record.max,
			default = record.default,
			kind = record.kind?.toRuntime() ?: ParameterKind.NORMAL,
			repeat = record.repeat ?: false,
		)

	/**
	 * UMA §4.3: one parameter-tree node, which the codec has checked is exactly one of a leaf and a named group.
	 *
	 * @param UmaParameterNode record The record.
	 * @return ParameterNode The node.
	 */
	private fun parameterNodeOf(record: UmaParameterNode): ParameterNode {
		val leaf = record.parameter
		if (leaf != null) {
			return ParameterNode.Param(ParameterId(leaf))
		}
		return ParameterNode.Group(
			id = ParameterGroupId(checkNotNull(record.group) { "the codec checked a tree node names a parameter or a group" }),
			name = checkNotNull(record.name) { "the codec checked a group has a name" },
			initiallyOpen = record.initiallyOpen ?: false,
			children = record.children.orEmpty().map(::parameterNodeOf),
		)
	}

	/**
	 * UMA §4.2: one organizational child, which the codec has checked is exactly one of a part and a drawable.
	 *
	 * @param UmaOrgRef record The record.
	 * @return OrgChild The child.
	 */
	private fun orgChildOf(record: UmaOrgRef): OrgChild {
		val part = record.part
		if (part != null) {
			return OrgChild.Part(PartId(part))
		}
		return OrgChild.Drawable(DrawableId(checkNotNull(record.drawable) { "the codec checked an org reference names a part or a drawable" }))
	}

	/**
	 * UMA §4.4: one part.
	 *
	 * @param UmaPart record The record.
	 * @return Part The part.
	 */
	private fun partOf(record: UmaPart): Part =
		Part(
			id = PartId(record.id),
			name = record.name,
			children = record.children.orEmpty().map(::orgChildOf),
			isVisible = record.isVisible ?: true,
			isSketch = record.isSketch ?: false,
			isSelectable = record.isSelectable ?: true,
			groupMode = record.groupMode?.toRuntime() ?: PartGroupMode.PassThrough,
			drawOrder = record.drawOrder ?: DEFAULT_DRAW_ORDER,
			composite = compositeOf(record.composite ?: UmaPartComposite()),
			channelGrids = channelsOf(record.channels),
			blendShapes = record.blendShapes.orEmpty().map(::partBlendShapeOf),
		)

	/**
	 * UMA §4.4: a part's composite.
	 *
	 * @param UmaPartComposite record The record.
	 * @return PartComposite The composite.
	 */
	private fun compositeOf(record: UmaPartComposite): PartComposite =
		PartComposite(
			blendMode = record.blendMode?.toRuntime() ?: BlendMode.Normal,
			alphaBlendMode = record.alphaBlendMode?.toRuntime() ?: AlphaBlendMode.Over,
			maskedBy = record.maskedBy.orEmpty().map(::DrawableId),
			maskedByParts = record.maskedByParts.orEmpty().map(::PartId),
			invertMask = record.invertMask ?: false,
			opacity = record.opacity ?: 1f,
			multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity),
			screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity),
		)

	/**
	 * UMA §4.5: one deformer, whose kind decides which kind-specific fields it carries.
	 *
	 * @param UmaDeformer record The record.
	 * @return Deformer The deformer.
	 */
	private fun deformerOf(record: UmaDeformer): Deformer {
		val multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity)
		val screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity)
		return when (record.kind) {
			UmaDeformerKind.Warp ->
				Deformer.Warp(
					id = DeformerId(record.id),
					name = record.name,
					parent = record.parent?.let(::DeformerId),
					partId = record.part?.let(::PartId),
					rows = checkNotNull(record.rows) { "the codec checked a warp has rows" },
					columns = checkNotNull(record.columns) { "the codec checked a warp has columns" },
					isQuadTransform = checkNotNull(record.isQuadTransform) { "the codec checked a warp has isQuadTransform" },
					geometryGrid = record.geometry?.let(::warpGridOf),
					channelGrids = channelsOf(record.channels),
					blendShapes = record.blendShapes.orEmpty().map(::warpBlendShapeOf),
					opacity = record.opacity ?: 1f,
					multiplyColor = multiplyColor,
					screenColor = screenColor,
					isSelectable = record.isSelectable ?: true,
					isVisible = record.isVisible ?: true,
					isEnabled = record.isEnabled ?: true,
				)

			UmaDeformerKind.Rotation ->
				Deformer.Rotation(
					id = DeformerId(record.id),
					name = record.name,
					parent = record.parent?.let(::DeformerId),
					partId = record.part?.let(::PartId),
					baseAngle = checkNotNull(record.baseAngle) { "the codec checked a rotation has baseAngle" },
					geometryGrid = record.geometry?.let(::rotationGridOf),
					channelGrids = channelsOf(record.channels),
					blendShapes = record.blendShapes.orEmpty().map(::rotationBlendShapeOf),
					opacity = record.opacity ?: 1f,
					multiplyColor = multiplyColor,
					screenColor = screenColor,
					flipX = record.flipX ?: false,
					flipY = record.flipY ?: false,
					isSelectable = record.isSelectable ?: true,
					isVisible = record.isVisible ?: true,
					isEnabled = record.isEnabled ?: true,
				)
		}
	}

	/**
	 * UMA §4.6: one drawable.
	 *
	 * @param UmaDrawable record The record.
	 * @return Drawable The drawable.
	 */
	private fun drawableOf(record: UmaDrawable): Drawable =
		Drawable(
			id = DrawableId(record.id),
			name = record.name,
			parentDeformerId = record.parentDeformer?.let(::DeformerId),
			blendMode = record.blendMode?.toRuntime() ?: BlendMode.Normal,
			maskedBy = record.maskedBy.orEmpty().map(::DrawableId),
			mesh = record.mesh?.let { mesh -> DrawableMesh(mesh.positions, mesh.uvs, mesh.indices) },
			geometryGrid = record.geometry?.let(::meshGridOf),
			channelGrids = channelsOf(record.channels),
			drawOrder = record.drawOrder ?: DEFAULT_DRAW_ORDER.toFloat(),
			opacity = record.opacity ?: 1f,
			multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity),
			screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity),
			invertMask = record.invertMask ?: false,
			alphaBlendMode = record.alphaBlendMode?.toRuntime() ?: AlphaBlendMode.Over,
			culling = record.culling ?: false,
			isVisible = record.isVisible ?: true,
			isSelectable = record.isSelectable ?: true,
			textureSourceId = record.textureSource?.let(::DrawableId),
			texturePage = record.texturePage ?: -1,
			atlasTileId = record.atlasTile?.let(::AtlasTileId),
			blendShapes = record.blendShapes.orEmpty().map(::meshBlendShapeOf),
		)

	/**
	 * UMA §4.14: one glue affecter.
	 *
	 * @param UmaGlue record The record.
	 * @return Glue The glue.
	 */
	private fun glueOf(record: UmaGlue): Glue {
		val pairs = record.pairs
		return Glue(
			meshA = DrawableId(record.meshA),
			meshB = DrawableId(record.meshB),
			pairs = List(pairs.indicesA.size) { pairIndex -> GluePair(pairs.indicesA[pairIndex], pairs.indicesB[pairIndex], pairs.weightsA[pairIndex], pairs.weightsB[pairIndex]) },
			channelGrids = channelsOf(record.channels),
			intensity = record.intensity ?: 1f,
			id = record.id,
		)
	}

	/**
	 * UMA §4.11: a grid's axes.
	 *
	 * @param List<UmaAxis> records The records.
	 * @return List<KeyformAxis> The axes.
	 */
	private fun axesOf(records: List<UmaAxis>): List<KeyformAxis> = records.map { axis -> KeyformAxis(ParameterId(axis.parameter), axis.keys.toFloatArray()) }

	/**
	 * UMA §4.11: a drawable's geometry grid.
	 *
	 * @param UmaMeshGrid record The record.
	 * @return KeyformGrid The grid.
	 */
	private fun meshGridOf(record: UmaMeshGrid): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(axesOf(record.axes), record.cells.map { cell -> KeyformCell(cell.coordinate.toIntArray(), MeshDeltaForm(cell.positionDeltas)) })

	/**
	 * UMA §4.11: a warp's geometry grid, whose cells carry a whole lattice.
	 *
	 * @param UmaDeformerGrid record The record.
	 * @return KeyformGrid The grid.
	 */
	private fun warpGridOf(record: UmaDeformerGrid): KeyformGrid<WarpLatticeForm> =
		KeyformGrid(
			axesOf(record.axes),
			record.cells.map { cell -> KeyformCell(cell.coordinate.toIntArray(), WarpLatticeForm(checkNotNull(cell.controlPoints) { "the codec checked a warp cell has controlPoints" })) },
		)

	/**
	 * UMA §4.11: a rotation's geometry grid, whose cells carry a whole pivot.
	 *
	 * @param UmaDeformerGrid record The record.
	 * @return KeyformGrid The grid.
	 */
	private fun rotationGridOf(record: UmaDeformerGrid): KeyformGrid<RotationPivotForm> =
		KeyformGrid(
			axesOf(record.axes),
			record.cells.map { cell ->
				KeyformCell(
					cell.coordinate.toIntArray(),
					RotationPivotForm(
						originX = checkNotNull(cell.originX) { PIVOT_CHECKED },
						originY = checkNotNull(cell.originY) { PIVOT_CHECKED },
						angle = checkNotNull(cell.angle) { PIVOT_CHECKED },
						scale = checkNotNull(cell.scale) { PIVOT_CHECKED },
					),
				)
			},
		)

	/**
	 * UMA §4.12: an owner's channel tracks.
	 *
	 * @param Map? records The records, or null when the owner keys none.
	 * @return ChannelGrids The tracks.
	 */
	private fun channelsOf(records: Map<UmaFormChannel, UmaChannelGrid>?): ChannelGrids {
		if (records.isNullOrEmpty()) {
			return ChannelGrids.Empty
		}
		val tracks = LinkedHashMap<FormChannel, KeyformGrid<ChannelValue>>()
		for ((umaChannel, record) in records) {
			val channel = umaChannel.toRuntime()
			tracks[channel] = KeyformGrid(axesOf(record.axes), record.cells.map { cell -> KeyformCell(cell.coordinate.toIntArray(), channelValueOf(cell.value, channel.valueKind)) })
		}
		return ChannelGrids(tracks)
	}

	/**
	 * UMA §4.12: one channel value - a number, a color, or a boolean, as the channel's kind requires, which the codec
	 * has checked.
	 *
	 * @param JsonElement      value The JSON.
	 * @param ChannelValueKind kind  The channel's kind.
	 * @return ChannelValue The value.
	 */
	private fun channelValueOf(value: JsonElement, kind: ChannelValueKind): ChannelValue =
		when (kind) {
			ChannelValueKind.SCALAR -> ChannelValue.Scalar(numberOf(value))
			ChannelValueKind.COLOR -> {
				val channels = (value as JsonArray).map(::numberOf)
				ChannelValue.Color(ColorRgb(channels[0], channels[1], channels[2]))
			}

			ChannelValueKind.FLAG -> ChannelValue.Flag(checkNotNull((value as JsonPrimitive).booleanOrNull) { "the codec checked a flag value is a boolean" })
		}

	/**
	 * [value] as the float the codec has checked it holds.
	 *
	 * @param JsonElement value The JSON.
	 * @return Float The number.
	 */
	private fun numberOf(value: JsonElement): Float = checkNotNull((value as JsonPrimitive).floatOrNull) { "the codec checked a channel value is a number" }

	/**
	 * UMA §4.13: a binding's limits.
	 *
	 * @param List<UmaBlendLimit>? records The records.
	 * @return List<BlendWeightLimit> The limits.
	 */
	private fun limitsOf(records: List<UmaBlendLimit>?): List<BlendWeightLimit> =
		records.orEmpty().map { limit -> BlendWeightLimit(ParameterId(limit.parameter), limit.points.map { point -> BlendWeightLimitPoint(point.value, point.weight) }) }

	/**
	 * UMA §4.13: a drawable's blend-shape binding.
	 *
	 * @param UmaMeshBlendShape record The record.
	 * @return BlendShapeBinding The binding.
	 */
	private fun meshBlendShapeOf(record: UmaMeshBlendShape): BlendShapeBinding<MeshForm> =
		BlendShapeBinding(
			parameterId = ParameterId(record.parameter),
			keys = record.keys.toFloatArray(),
			neutralIndex = record.neutralIndex,
			forms =
				record.forms.map { form ->
					form?.let {
						MeshForm(
							positionDeltas = form.positionDeltas,
							drawOrder = form.drawOrder ?: DEFAULT_DRAW_ORDER.toFloat(),
							opacity = form.opacity ?: 1f,
							multiplyColor = colorOf(form.multiplyColor, ColorRgb.MultiplyIdentity),
							screenColor = colorOf(form.screenColor, ColorRgb.ScreenIdentity),
						)
					}
				},
			limits = limitsOf(record.limits),
		)

	/**
	 * UMA §4.13: a warp's blend-shape binding, whose forms carry a whole lattice.
	 *
	 * @param UmaDeformerBlendShape record The record.
	 * @return BlendShapeBinding The binding.
	 */
	private fun warpBlendShapeOf(record: UmaDeformerBlendShape): BlendShapeBinding<WarpForm> =
		BlendShapeBinding(
			parameterId = ParameterId(record.parameter),
			keys = record.keys.toFloatArray(),
			neutralIndex = record.neutralIndex,
			forms =
				record.forms.map { form ->
					form?.let {
						WarpForm(
							controlPoints = checkNotNull(form.controlPoints) { "the codec checked a warp form has controlPoints" },
							opacity = form.opacity ?: 1f,
							multiplyColor = colorOf(form.multiplyColor, ColorRgb.MultiplyIdentity),
							screenColor = colorOf(form.screenColor, ColorRgb.ScreenIdentity),
						)
					}
				},
			limits = limitsOf(record.limits),
		)

	/**
	 * UMA §4.13: a rotation's blend-shape binding, whose forms carry a whole pivot.
	 *
	 * @param UmaDeformerBlendShape record The record.
	 * @return BlendShapeBinding The binding.
	 */
	private fun rotationBlendShapeOf(record: UmaDeformerBlendShape): BlendShapeBinding<RotationForm> =
		BlendShapeBinding(
			parameterId = ParameterId(record.parameter),
			keys = record.keys.toFloatArray(),
			neutralIndex = record.neutralIndex,
			forms =
				record.forms.map { form ->
					form?.let {
						RotationForm(
							originX = checkNotNull(form.originX) { PIVOT_CHECKED },
							originY = checkNotNull(form.originY) { PIVOT_CHECKED },
							angle = checkNotNull(form.angle) { PIVOT_CHECKED },
							scale = checkNotNull(form.scale) { PIVOT_CHECKED },
							flipX = form.flipX ?: false,
							flipY = form.flipY ?: false,
							opacity = form.opacity ?: 1f,
							multiplyColor = colorOf(form.multiplyColor, ColorRgb.MultiplyIdentity),
							screenColor = colorOf(form.screenColor, ColorRgb.ScreenIdentity),
						)
					}
				},
			limits = limitsOf(record.limits),
		)

	/**
	 * UMA §4.13: a part's blend-shape binding.
	 *
	 * @param UmaPartBlendShape record The record.
	 * @return BlendShapeBinding The binding.
	 */
	private fun partBlendShapeOf(record: UmaPartBlendShape): BlendShapeBinding<PartForm> =
		BlendShapeBinding(
			parameterId = ParameterId(record.parameter),
			keys = record.keys.toFloatArray(),
			neutralIndex = record.neutralIndex,
			forms =
				record.forms.map { form ->
					form?.let {
						PartForm(
							drawOrder = form.drawOrder,
							opacity = form.opacity ?: 1f,
							multiplyColor = colorOf(form.multiplyColor, ColorRgb.MultiplyIdentity),
							screenColor = colorOf(form.screenColor, ColorRgb.ScreenIdentity),
						)
					}
				},
			limits = limitsOf(record.limits),
		)

	/**
	 * UMA §4.1: a color, which the codec has checked is exactly three channels, or [identity] when absent.
	 *
	 * @param List<Float>? channels The record's channels.
	 * @param ColorRgb     identity The channel's identity color.
	 * @return ColorRgb The color.
	 */
	private fun colorOf(channels: List<Float>?, identity: ColorRgb): ColorRgb = channels?.let { ColorRgb(channels[0], channels[1], channels[2]) } ?: identity

	/** The failure a rotation form without its whole pivot reports, which the codec has already refused. */
	private const val PIVOT_CHECKED = "the codec checked a rotation form carries its whole pivot"
}