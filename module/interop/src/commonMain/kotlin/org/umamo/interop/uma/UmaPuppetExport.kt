package org.umamo.interop.uma

import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.puppet.UmaDeformer
import org.umamo.format.uma.puppet.UmaDeformerKind
import org.umamo.format.uma.puppet.UmaDrawable
import org.umamo.format.uma.puppet.UmaOrgRef
import org.umamo.format.uma.puppet.UmaParameter
import org.umamo.format.uma.puppet.UmaParameterLink
import org.umamo.format.uma.puppet.UmaParameterNode
import org.umamo.format.uma.puppet.UmaPart
import org.umamo.format.uma.puppet.UmaPartComposite
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget

/**
 * Lowers a [PuppetModel] onto the puppet entry's schema (docs/format/UMA.md §4).
 *
 * A value is left out of the file only when it is bit-for-bit the model's default, so `-0.0` and `0.0`
 * stay distinct; every float is checked to be finite first, because JSON cannot hold anything else and a
 * silently dropped value would be worse than a refused save.
 *
 * The puppet entry's structure: the document fields, parameters, the organizational tree, parts, deformers,
 * and drawables.  Meshes, keyform tracks, blend shapes, and glue are not lowered yet.
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
			worldOriginY = optionalFloat(model.worldOriginY, 0f, "worldOriginY"),
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
		)
	}

	/**
	 * [value], refusing a NaN or infinity.
	 *
	 * @param Float  value The value.
	 * @param String path  Where the value sits in the entry, for the failure.
	 * @return Float The value.
	 * @throws UmaWriteException When the value is not finite.
	 */
	private fun finite(value: Float, path: String): Float {
		if (!value.isFinite()) {
			// UMA §4.1: JSON has no NaN or infinity.
			throw UmaWriteException("${UmaEntryKind.Puppet.defaultPath}: $path", "$value cannot be written as JSON")
		}
		return value
	}

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