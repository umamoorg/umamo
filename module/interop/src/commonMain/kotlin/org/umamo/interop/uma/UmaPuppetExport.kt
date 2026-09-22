package org.umamo.interop.uma

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.puppet.UmaAxis
import org.umamo.format.uma.puppet.UmaBlendLimit
import org.umamo.format.uma.puppet.UmaBlendLimitPoint
import org.umamo.format.uma.puppet.UmaChannelCell
import org.umamo.format.uma.puppet.UmaChannelGrid
import org.umamo.format.uma.puppet.UmaDeformer
import org.umamo.format.uma.puppet.UmaDeformerBlendShape
import org.umamo.format.uma.puppet.UmaDeformerCell
import org.umamo.format.uma.puppet.UmaDeformerForm
import org.umamo.format.uma.puppet.UmaDeformerGrid
import org.umamo.format.uma.puppet.UmaDeformerKind
import org.umamo.format.uma.puppet.UmaDrawable
import org.umamo.format.uma.puppet.UmaFormChannel
import org.umamo.format.uma.puppet.UmaGlue
import org.umamo.format.uma.puppet.UmaGluePairs
import org.umamo.format.uma.puppet.UmaMesh
import org.umamo.format.uma.puppet.UmaMeshBlendShape
import org.umamo.format.uma.puppet.UmaMeshCell
import org.umamo.format.uma.puppet.UmaMeshForm
import org.umamo.format.uma.puppet.UmaMeshGrid
import org.umamo.format.uma.puppet.UmaOrgRef
import org.umamo.format.uma.puppet.UmaParameter
import org.umamo.format.uma.puppet.UmaParameterLink
import org.umamo.format.uma.puppet.UmaParameterNode
import org.umamo.format.uma.puppet.UmaPart
import org.umamo.format.uma.puppet.UmaPartBlendShape
import org.umamo.format.uma.puppet.UmaPartComposite
import org.umamo.format.uma.puppet.UmaPartForm
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm

/**
 * Lowers a [PuppetModel] onto the puppet entry's schema (docs/format/UMA.md §4).
 *
 * A value is left out of the file only when it is bit-for-bit the model's default, so `-0.0` and `0.0`
 * stay distinct; every float is checked to be finite first, because JSON cannot hold anything else and a
 * silently dropped value would be worse than a refused save.
 *
 * Bulk arrays (meshes, position deltas, lattice points, glue pairs) are handed over as they are; they travel
 * in the entry's buffer, which holds any float bit for bit.  Inline floats (axis keys, pivots, channel values,
 * form scalars) must be finite.
 */
object UmaPuppetExport {
	/**
	 * The puppet entry for [model].
	 *
	 * @param PuppetModel model The puppet.
	 * @return UmaPuppet The entry's content.
	 * @throws UmaWriteException When a float the entry holds is NaN or infinite.
	 */
	fun puppetOf(model: PuppetModel): UmaPuppet =
		UmaPuppet(
			canvasWidth = optionalFloat(model.canvasWidth, 0f, "canvasWidth"),
			canvasHeight = optionalFloat(model.canvasHeight, 0f, "canvasHeight"),
			worldOriginX = optionalFloat(model.worldOriginX, 0f, "worldOriginX"),
			worldOriginZ = optionalFloat(model.worldOriginZ, 0f, "worldOriginZ"),
			pixelsPerUnit = model.pixelsPerUnit?.let { scale -> finite(scale, "pixelsPerUnit") },
			runtimeTarget = model.runtimeTarget.takeIf { target -> target != RuntimeTarget.NoTarget }?.toUma(),
			rendersFromSourceLayers = optionalBoolean(model.rendersFromSourceLayers, false),
			parameters = model.parameters.map(::parameterOf).ifEmpty { null },
			parameterLinks = model.parameterLinks.map { link -> UmaParameterLink(link.horizontal.raw, link.vertical.raw) }.ifEmpty { null },
			parameterTree = model.parameterTree.map(::parameterNodeOf).ifEmpty { null },
			rootPart = model.rootPartId?.raw,
			rootChildren = model.rootChildren.map(::orgRefOf).ifEmpty { null },
			parts = model.parts.map(::partOf).ifEmpty { null },
			deformers = model.deformers.map(::deformerOf).ifEmpty { null },
			drawables = model.drawables.map(::drawableOf).ifEmpty { null },
			glues = model.glues.map(::glueOf).ifEmpty { null },
		)

	/**
	 * UMA §4.3: one parameter.
	 *
	 * @param Parameter parameter The parameter.
	 * @return UmaParameter The record.
	 */
	private fun parameterOf(parameter: Parameter): UmaParameter {
		val path = "parameters[${parameter.id.raw}]"
		return UmaParameter(
			id = parameter.id.raw,
			name = parameter.name,
			min = finite(parameter.min, "$path.min"),
			max = finite(parameter.max, "$path.max"),
			default = finite(parameter.default, "$path.default"),
			kind = parameter.kind.takeIf { kind -> kind != ParameterKind.NORMAL }?.toUma(),
			repeat = optionalBoolean(parameter.repeat, false),
		)
	}

	/**
	 * UMA §4.3: one parameter-tree node.
	 *
	 * @param ParameterNode node The node.
	 * @return UmaParameterNode The record.
	 */
	private fun parameterNodeOf(node: ParameterNode): UmaParameterNode =
		when (node) {
			is ParameterNode.Param -> UmaParameterNode(parameter = node.id.raw)
			is ParameterNode.Group ->
				UmaParameterNode(
					group = node.id.raw,
					name = node.name,
					initiallyOpen = optionalBoolean(node.initiallyOpen, false),
					children = node.children.map(::parameterNodeOf).ifEmpty { null },
				)
		}

	/**
	 * UMA §4.2: one organizational child.
	 *
	 * @param OrgChild child The child.
	 * @return UmaOrgRef The record.
	 */
	private fun orgRefOf(child: OrgChild): UmaOrgRef =
		when (child) {
			is OrgChild.Part -> UmaOrgRef(part = child.id.raw)
			is OrgChild.Drawable -> UmaOrgRef(drawable = child.id.raw)
		}

	/**
	 * UMA §4.4: one part.
	 *
	 * @param Part part The part.
	 * @return UmaPart The record.
	 */
	private fun partOf(part: Part): UmaPart {
		val path = "parts[${part.id.raw}]"
		return UmaPart(
			id = part.id.raw,
			name = part.name,
			children = part.children.map(::orgRefOf).ifEmpty { null },
			isVisible = optionalBoolean(part.isVisible, true),
			isSketch = optionalBoolean(part.isSketch, false),
			isSelectable = optionalBoolean(part.isSelectable, true),
			groupMode = part.groupMode.takeIf { mode -> mode != PartGroupMode.PassThrough }?.toUma(),
			drawOrder = part.drawOrder.takeIf { order -> order != DEFAULT_DRAW_ORDER },
			composite = compositeOf(part.composite, "$path.composite"),
			channels = channelsOf(part.channelGrids, path),
			blendShapes = part.blendShapes.mapIndexed { bindingIndex, binding -> partBlendShapeOf(binding, "$path.blendShapes[$bindingIndex]") }.ifEmpty { null },
		)
	}

	/**
	 * UMA §4.4: a part's composite, or null when every setting is at its default.
	 *
	 * @param PartComposite composite The composite.
	 * @param String        path      The composite's position, for a failure.
	 * @return UmaPartComposite? The record.
	 */
	private fun compositeOf(composite: PartComposite, path: String): UmaPartComposite? {
		val record =
			UmaPartComposite(
				blendMode = composite.blendMode.takeIf { mode -> mode != BlendMode.Normal }?.toUma(),
				alphaBlendMode = composite.alphaBlendMode.takeIf { mode -> mode != AlphaBlendMode.Over }?.toUma(),
				maskedBy = composite.maskedBy.map { id -> id.raw }.ifEmpty { null },
				maskedByParts = composite.maskedByParts.map { id -> id.raw }.ifEmpty { null },
				invertMask = optionalBoolean(composite.invertMask, false),
				opacity = optionalFloat(composite.opacity, 1f, "$path.opacity"),
				multiplyColor = optionalColor(composite.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor"),
				screenColor = optionalColor(composite.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor"),
			)
		return record.takeIf { candidate -> candidate != UmaPartComposite() }
	}

	/**
	 * UMA §4.5: one deformer.
	 *
	 * @param Deformer deformer The deformer.
	 * @return UmaDeformer The record.
	 */
	private fun deformerOf(deformer: Deformer): UmaDeformer {
		val path = "deformers[${deformer.id.raw}]"
		return when (deformer) {
			is Deformer.Warp ->
				UmaDeformer(
					id = deformer.id.raw,
					kind = UmaDeformerKind.Warp,
					name = deformer.name,
					parent = deformer.parent?.raw,
					part = deformer.partId?.raw,
					isVisible = optionalBoolean(deformer.isVisible, true),
					isEnabled = optionalBoolean(deformer.isEnabled, true),
					isSelectable = optionalBoolean(deformer.isSelectable, true),
					opacity = optionalFloat(deformer.opacity, 1f, "$path.opacity"),
					multiplyColor = optionalColor(deformer.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor"),
					screenColor = optionalColor(deformer.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor"),
					rows = deformer.rows,
					columns = deformer.columns,
					isQuadTransform = deformer.isQuadTransform,
					geometry = deformer.geometryGrid?.let { grid -> warpGridOf(grid, "$path.geometry") },
					channels = channelsOf(deformer.channelGrids, path),
					blendShapes = deformer.blendShapes.mapIndexed { bindingIndex, binding -> warpBlendShapeOf(binding, "$path.blendShapes[$bindingIndex]") }.ifEmpty { null },
				)

			is Deformer.Rotation ->
				UmaDeformer(
					id = deformer.id.raw,
					kind = UmaDeformerKind.Rotation,
					name = deformer.name,
					parent = deformer.parent?.raw,
					part = deformer.partId?.raw,
					isVisible = optionalBoolean(deformer.isVisible, true),
					isEnabled = optionalBoolean(deformer.isEnabled, true),
					isSelectable = optionalBoolean(deformer.isSelectable, true),
					opacity = optionalFloat(deformer.opacity, 1f, "$path.opacity"),
					multiplyColor = optionalColor(deformer.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor"),
					screenColor = optionalColor(deformer.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor"),
					baseAngle = finite(deformer.baseAngle, "$path.baseAngle"),
					flipX = optionalBoolean(deformer.flipX, false),
					flipY = optionalBoolean(deformer.flipY, false),
					geometry = deformer.geometryGrid?.let { grid -> rotationGridOf(grid, "$path.geometry") },
					channels = channelsOf(deformer.channelGrids, path),
					blendShapes = deformer.blendShapes.mapIndexed { bindingIndex, binding -> rotationBlendShapeOf(binding, "$path.blendShapes[$bindingIndex]") }.ifEmpty { null },
				)
		}
	}

	/**
	 * UMA §4.6: one drawable's structure.
	 *
	 * @param Drawable drawable The drawable.
	 * @return UmaDrawable The record.
	 */
	private fun drawableOf(drawable: Drawable): UmaDrawable {
		val path = "drawables[${drawable.id.raw}]"
		return UmaDrawable(
			id = drawable.id.raw,
			name = drawable.name,
			parentDeformer = drawable.parentDeformerId?.raw,
			blendMode = drawable.blendMode.takeIf { mode -> mode != BlendMode.Normal }?.toUma(),
			maskedBy = drawable.maskedBy.map { id -> id.raw }.ifEmpty { null },
			invertMask = optionalBoolean(drawable.invertMask, false),
			drawOrder = optionalFloat(drawable.drawOrder, DEFAULT_DRAW_ORDER.toFloat(), "$path.drawOrder"),
			opacity = optionalFloat(drawable.opacity, 1f, "$path.opacity"),
			multiplyColor = optionalColor(drawable.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor"),
			screenColor = optionalColor(drawable.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor"),
			alphaBlendMode = drawable.alphaBlendMode.takeIf { mode -> mode != AlphaBlendMode.Over }?.toUma(),
			culling = optionalBoolean(drawable.culling, false),
			isVisible = optionalBoolean(drawable.isVisible, true),
			isSelectable = optionalBoolean(drawable.isSelectable, true),
			textureSource = drawable.textureSourceId?.raw,
			texturePage = drawable.texturePage.takeIf { page -> page != -1 },
			atlasTile = drawable.atlasTileId?.raw,
			mesh = drawable.mesh?.let { mesh -> UmaMesh(mesh.positions, mesh.uvs, mesh.indices) },
			geometry = drawable.geometryGrid?.let { grid -> meshGridOf(grid, "$path.geometry") },
			channels = channelsOf(drawable.channelGrids, path),
			blendShapes = drawable.blendShapes.mapIndexed { bindingIndex, binding -> meshBlendShapeOf(binding, "$path.blendShapes[$bindingIndex]") }.ifEmpty { null },
		)
	}

	/**
	 * UMA §4.14: one glue affecter.
	 *
	 * @param Glue glue The glue.
	 * @return UmaGlue The record.
	 */
	private fun glueOf(glue: Glue): UmaGlue {
		val path = "glues[${glue.meshA.raw},${glue.meshB.raw}]"
		return UmaGlue(
			meshA = glue.meshA.raw,
			meshB = glue.meshB.raw,
			pairs =
				UmaGluePairs(
					indicesA = IntArray(glue.pairs.size) { pairIndex -> glue.pairs[pairIndex].indexA },
					indicesB = IntArray(glue.pairs.size) { pairIndex -> glue.pairs[pairIndex].indexB },
					weightsA = FloatArray(glue.pairs.size) { pairIndex -> glue.pairs[pairIndex].weightA },
					weightsB = FloatArray(glue.pairs.size) { pairIndex -> glue.pairs[pairIndex].weightB },
				),
			channels = channelsOf(glue.channelGrids, path),
			intensity = optionalFloat(glue.intensity, 1f, "$path.intensity"),
			id = glue.id,
		)
	}

	/**
	 * UMA §4.11: a grid's axes.
	 *
	 * @param List<KeyformAxis> axes The axes.
	 * @param String            path The grid's position, for a failure.
	 * @return List<UmaAxis> The records.
	 */
	private fun axesOf(axes: List<KeyformAxis>, path: String): List<UmaAxis> =
		axes.mapIndexed { axisIndex, axis -> UmaAxis(axis.parameterId.raw, keysOf(axis.keys, "$path.axes[$axisIndex].keys")) }

	/**
	 * Inline key values, each checked to be finite.
	 *
	 * @param FloatArray keys The keys.
	 * @param String     path Where they sit, for a failure.
	 * @return List<Float> The keys.
	 */
	private fun keysOf(keys: FloatArray, path: String): List<Float> = keys.mapIndexed { keyIndex, key -> finite(key, "$path[$keyIndex]") }

	/**
	 * UMA §4.11: a drawable's geometry grid.
	 *
	 * @param KeyformGrid grid The grid.
	 * @param String      path The grid's position, for a failure.
	 * @return UmaMeshGrid The record.
	 */
	private fun meshGridOf(grid: KeyformGrid<MeshDeltaForm>, path: String): UmaMeshGrid =
		UmaMeshGrid(axesOf(grid.axes, path), grid.cells.map { cell -> UmaMeshCell(cell.coordinate.toList(), cell.form.positionDeltas) })

	/**
	 * UMA §4.11: a warp's geometry grid.
	 *
	 * @param KeyformGrid grid The grid.
	 * @param String      path The grid's position, for a failure.
	 * @return UmaDeformerGrid The record.
	 */
	private fun warpGridOf(grid: KeyformGrid<WarpLatticeForm>, path: String): UmaDeformerGrid =
		UmaDeformerGrid(axesOf(grid.axes, path), grid.cells.map { cell -> UmaDeformerCell(cell.coordinate.toList(), controlPoints = cell.form.controlPoints) })

	/**
	 * UMA §4.11: a rotation's geometry grid.
	 *
	 * @param KeyformGrid grid The grid.
	 * @param String      path The grid's position, for a failure.
	 * @return UmaDeformerGrid The record.
	 */
	private fun rotationGridOf(grid: KeyformGrid<RotationPivotForm>, path: String): UmaDeformerGrid =
		UmaDeformerGrid(
			axesOf(grid.axes, path),
			grid.cells.mapIndexed { cellIndex, cell ->
				val cellPath = "$path.cells[$cellIndex]"
				UmaDeformerCell(
					coordinate = cell.coordinate.toList(),
					originX = finite(cell.form.originX, "$cellPath.originX"),
					originY = finite(cell.form.originY, "$cellPath.originY"),
					angle = finite(cell.form.angle, "$cellPath.angle"),
					scale = finite(cell.form.scale, "$cellPath.scale"),
				)
			},
		)

	/**
	 * UMA §4.12: an owner's channel tracks, in channel order, or null when it keys none.
	 *
	 * @param ChannelGrids channelGrids The tracks.
	 * @param String       path         The owner's position, for a failure.
	 * @return Map? The records.
	 */
	private fun channelsOf(channelGrids: ChannelGrids, path: String): Map<UmaFormChannel, UmaChannelGrid>? {
		if (channelGrids.isEmpty) {
			return null
		}
		val tracks = LinkedHashMap<UmaFormChannel, UmaChannelGrid>()
		for (channel in FormChannel.entries) {
			val grid = channelGrids[channel] ?: continue
			val trackPath = "$path.channels.${channel.toUma().wireName()}"
			tracks[channel.toUma()] =
				UmaChannelGrid(
					axesOf(grid.axes, trackPath),
					grid.cells.mapIndexed { cellIndex, cell -> UmaChannelCell(cell.coordinate.toList(), channelValueOf(cell.form, "$trackPath.cells[$cellIndex].value")) },
				)
		}
		return tracks
	}

	/**
	 * UMA §4.12: one channel value as JSON - a number, a color, or a boolean.
	 *
	 * @param ChannelValue value The value.
	 * @param String       path  Where it sits, for a failure.
	 * @return JsonElement The JSON.
	 */
	private fun channelValueOf(value: ChannelValue, path: String): JsonElement =
		when (value) {
			is ChannelValue.Scalar -> JsonPrimitive(finite(value.value, path))
			is ChannelValue.Color ->
				JsonArray(
					listOf(
						JsonPrimitive(finite(value.color.red, "$path[0]")),
						JsonPrimitive(finite(value.color.green, "$path[1]")),
						JsonPrimitive(finite(value.color.blue, "$path[2]")),
					),
				)

			is ChannelValue.Flag -> JsonPrimitive(value.flag)
		}

	/**
	 * UMA §4.13: a binding's limits, or null when it has none.
	 *
	 * @param List<BlendWeightLimit> limits The limits.
	 * @param String                 path   The binding's position, for a failure.
	 * @return List<UmaBlendLimit>? The records.
	 */
	private fun limitsOf(limits: List<BlendWeightLimit>, path: String): List<UmaBlendLimit>? =
		limits.mapIndexed { limitIndex, limit ->
			UmaBlendLimit(
				limit.parameterId.raw,
				limit.points.mapIndexed { pointIndex, point ->
					val pointPath = "$path.limits[$limitIndex].points[$pointIndex]"
					UmaBlendLimitPoint(finite(point.value, "$pointPath.value"), finite(point.weight, "$pointPath.weight"))
				},
			)
		}.ifEmpty { null }

	/**
	 * UMA §4.13: a drawable's blend-shape binding.
	 *
	 * @param BlendShapeBinding binding The binding.
	 * @param String            path    Its position, for a failure.
	 * @return UmaMeshBlendShape The record.
	 */
	private fun meshBlendShapeOf(binding: BlendShapeBinding<MeshForm>, path: String): UmaMeshBlendShape =
		UmaMeshBlendShape(
			parameter = binding.parameterId.raw,
			keys = keysOf(binding.keys, "$path.keys"),
			neutralIndex = binding.neutralIndex,
			forms =
				binding.forms.mapIndexed { formIndex, form ->
					form?.let {
						val formPath = "$path.forms[$formIndex]"
						UmaMeshForm(
							positionDeltas = form.positionDeltas,
							drawOrder = optionalFloat(form.drawOrder, DEFAULT_DRAW_ORDER.toFloat(), "$formPath.drawOrder"),
							opacity = optionalFloat(form.opacity, 1f, "$formPath.opacity"),
							multiplyColor = optionalColor(form.multiplyColor, ColorRgb.MultiplyIdentity, "$formPath.multiplyColor"),
							screenColor = optionalColor(form.screenColor, ColorRgb.ScreenIdentity, "$formPath.screenColor"),
						)
					}
				},
			limits = limitsOf(binding.limits, path),
		)

	/**
	 * UMA §4.13: a warp's blend-shape binding.
	 *
	 * @param BlendShapeBinding binding The binding.
	 * @param String            path    Its position, for a failure.
	 * @return UmaDeformerBlendShape The record.
	 */
	private fun warpBlendShapeOf(binding: BlendShapeBinding<WarpForm>, path: String): UmaDeformerBlendShape =
		UmaDeformerBlendShape(
			parameter = binding.parameterId.raw,
			keys = keysOf(binding.keys, "$path.keys"),
			neutralIndex = binding.neutralIndex,
			forms =
				binding.forms.mapIndexed { formIndex, form ->
					form?.let {
						val formPath = "$path.forms[$formIndex]"
						UmaDeformerForm(
							controlPoints = form.controlPoints,
							opacity = optionalFloat(form.opacity, 1f, "$formPath.opacity"),
							multiplyColor = optionalColor(form.multiplyColor, ColorRgb.MultiplyIdentity, "$formPath.multiplyColor"),
							screenColor = optionalColor(form.screenColor, ColorRgb.ScreenIdentity, "$formPath.screenColor"),
						)
					}
				},
			limits = limitsOf(binding.limits, path),
		)

	/**
	 * UMA §4.13: a rotation's blend-shape binding.
	 *
	 * @param BlendShapeBinding binding The binding.
	 * @param String            path    Its position, for a failure.
	 * @return UmaDeformerBlendShape The record.
	 */
	private fun rotationBlendShapeOf(binding: BlendShapeBinding<RotationForm>, path: String): UmaDeformerBlendShape =
		UmaDeformerBlendShape(
			parameter = binding.parameterId.raw,
			keys = keysOf(binding.keys, "$path.keys"),
			neutralIndex = binding.neutralIndex,
			forms =
				binding.forms.mapIndexed { formIndex, form ->
					form?.let {
						val formPath = "$path.forms[$formIndex]"
						UmaDeformerForm(
							originX = finite(form.originX, "$formPath.originX"),
							originY = finite(form.originY, "$formPath.originY"),
							angle = finite(form.angle, "$formPath.angle"),
							scale = finite(form.scale, "$formPath.scale"),
							flipX = optionalBoolean(form.flipX, false),
							flipY = optionalBoolean(form.flipY, false),
							opacity = optionalFloat(form.opacity, 1f, "$formPath.opacity"),
							multiplyColor = optionalColor(form.multiplyColor, ColorRgb.MultiplyIdentity, "$formPath.multiplyColor"),
							screenColor = optionalColor(form.screenColor, ColorRgb.ScreenIdentity, "$formPath.screenColor"),
						)
					}
				},
			limits = limitsOf(binding.limits, path),
		)

	/**
	 * UMA §4.13: a part's blend-shape binding.
	 *
	 * @param BlendShapeBinding binding The binding.
	 * @param String            path    Its position, for a failure.
	 * @return UmaPartBlendShape The record.
	 */
	private fun partBlendShapeOf(binding: BlendShapeBinding<PartForm>, path: String): UmaPartBlendShape =
		UmaPartBlendShape(
			parameter = binding.parameterId.raw,
			keys = keysOf(binding.keys, "$path.keys"),
			neutralIndex = binding.neutralIndex,
			forms =
				binding.forms.mapIndexed { formIndex, form ->
					form?.let {
						val formPath = "$path.forms[$formIndex]"
						UmaPartForm(
							drawOrder = finite(form.drawOrder, "$formPath.drawOrder"),
							opacity = optionalFloat(form.opacity, 1f, "$formPath.opacity"),
							multiplyColor = optionalColor(form.multiplyColor, ColorRgb.MultiplyIdentity, "$formPath.multiplyColor"),
							screenColor = optionalColor(form.screenColor, ColorRgb.ScreenIdentity, "$formPath.screenColor"),
						)
					}
				},
			limits = limitsOf(binding.limits, path),
		)

	/**
	 * [value], refusing a NaN or infinity.
	 *
	 * @param Float  value The value.
	 * @param String path  Where the value sits in the entry, for the failure.
	 * @return Float The value.
	 * @throws UmaWriteException When the value is not finite.
	 */
	private fun finite(value: Float, path: String): Float = finiteInline(value, UmaEntryKind.Puppet.defaultPath, path)

	/**
	 * [value], or null when it is bit-for-bit [default].
	 *
	 * @param Float  value   The value.
	 * @param Float  default The model's default.
	 * @param String path    Where the value sits, for a failure.
	 * @return Float? The value to write.
	 * @throws UmaWriteException When the value is not finite.
	 */
	private fun optionalFloat(value: Float, default: Float, path: String): Float? =
		finite(value, path).takeIf { candidate -> candidate.toRawBits() != default.toRawBits() }

	/**
	 * [color] as red, green, blue, or null when every channel is bit-for-bit [identity]'s.
	 *
	 * @param ColorRgb color    The color.
	 * @param ColorRgb identity The channel's identity color.
	 * @param String   path     Where the color sits, for a failure.
	 * @return List<Float>? The channels to write.
	 * @throws UmaWriteException When a channel is not finite.
	 */
	private fun optionalColor(color: ColorRgb, identity: ColorRgb, path: String): List<Float>? {
		val channels = listOf(finite(color.red, "$path[0]"), finite(color.green, "$path[1]"), finite(color.blue, "$path[2]"))
		val identityChannels = listOf(identity.red, identity.green, identity.blue)
		val isIdentity = channels.indices.all { channelIndex -> channels[channelIndex].toRawBits() == identityChannels[channelIndex].toRawBits() }
		return if (isIdentity) null else channels
	}

	/**
	 * [value], or null when it is [default].
	 *
	 * @param Boolean value   The value.
	 * @param Boolean default The model's default.
	 * @return Boolean? The value to write.
	 */
	private fun optionalBoolean(value: Boolean, default: Boolean): Boolean? = value.takeIf { candidate -> candidate != default }
}