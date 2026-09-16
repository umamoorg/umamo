package org.umamo.interop.uma

import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.puppet.UmaDeformer
import org.umamo.format.uma.puppet.UmaDeformerKind
import org.umamo.format.uma.puppet.UmaDrawable
import org.umamo.format.uma.puppet.UmaOrgRef
import org.umamo.format.uma.puppet.UmaParameter
import org.umamo.format.uma.puppet.UmaParameterNode
import org.umamo.format.uma.puppet.UmaPart
import org.umamo.format.uma.puppet.UmaPartComposite
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * Builds a [PuppetModel] from the puppet entry (docs/format/UMA.md §4): restores each absent value to the
 * model's default, checks the shapes the schema's flat classes cannot enforce on their own, and derives the
 * render root from the organizational tree (D13).
 *
 * Ids are taken verbatim and references are not resolved: a reference to an object the entry does not hold
 * is carried as it is, never dropped.
 *
 * The puppet entry's structure: meshes, keyform tracks, blend shapes, and glue are not read yet, so the
 * model carries none.
 */
object UmaPuppetImport {
	/**
	 * The model [puppet] describes.
	 *
	 * @param UmaPuppet puppet    The puppet entry's content.
	 * @param String    entryPath The entry's path, for a failure.
	 * @return PuppetModel The model.
	 * @throws UmaFormatException When the entry holds a shape the schema does not allow.
	 */
	fun modelOf(puppet: UmaPuppet, entryPath: String = UmaEntryKind.Puppet.defaultPath): PuppetModel {
		val reader = EntryReader(entryPath)
		val model =
			PuppetModel(
				parameters = puppet.parameters.orEmpty().map(reader::parameterOf),
				parts = puppet.parts.orEmpty().map(reader::partOf),
				deformers = puppet.deformers.orEmpty().map(reader::deformerOf),
				drawables = puppet.drawables.orEmpty().map(reader::drawableOf),
				rootChildren = puppet.rootChildren.orEmpty().mapIndexed { childIndex, reference -> reader.orgChildOf(reference, "rootChildren[$childIndex]") },
				rootPartId = puppet.rootPart?.let(::PartId),
				parameterLinks = puppet.parameterLinks.orEmpty().map { link -> ParameterLink(ParameterId(link.horizontal), ParameterId(link.vertical)) },
				parameterTree = puppet.parameterTree.orEmpty().mapIndexed { nodeIndex, node -> reader.parameterNodeOf(node, "parameterTree[$nodeIndex]") },
				canvasWidth = puppet.canvasWidth ?: 0f,
				canvasHeight = puppet.canvasHeight ?: 0f,
				worldOriginX = puppet.worldOriginX ?: 0f,
				worldOriginY = puppet.worldOriginY ?: 0f,
				pixelsPerUnit = puppet.pixelsPerUnit,
				runtimeTarget = puppet.runtimeTarget?.toRuntime() ?: RuntimeTarget.NoTarget,
				rendersFromSourceLayers = puppet.rendersFromSourceLayers ?: false,
			)
		// UMA §4.2 (D13): the render root is never written; it is always the organizational tree's derivation.
		return model.withDerivedRenderRoot()
	}

	/**
	 * Reads the entry's records, reporting a malformed shape against the entry's path.
	 *
	 * @property String entryPath The entry's path.
	 */
	private class EntryReader(private val entryPath: String) {
		/**
		 * Throws the malformed-entry failure for [detail].
		 *
		 * @param String detail What is wrong, and where.
		 * @return Nothing Never returns.
		 */
		fun malformed(detail: String): Nothing = throw UmaFormatException(UmaReadFailure.MalformedEntry(entryPath, detail))

		/**
		 * UMA §4.3: one parameter.
		 *
		 * @param UmaParameter record The record.
		 * @return Parameter The parameter.
		 */
		fun parameterOf(record: UmaParameter): Parameter =
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
		 * UMA §4.3: one parameter-tree node, which is exactly one of a leaf and a group.
		 *
		 * @param UmaParameterNode record The record.
		 * @param String           path   The node's position, for a failure.
		 * @return ParameterNode The node.
		 */
		fun parameterNodeOf(record: UmaParameterNode, path: String): ParameterNode {
			val leaf = record.parameter
			val group = record.group
			return when {
				leaf != null && group == null -> {
					if (record.name != null || record.initiallyOpen != null || record.children != null) {
						malformed("$path is a parameter leaf but carries group fields")
					}
					ParameterNode.Param(ParameterId(leaf))
				}

				group != null && leaf == null ->
					ParameterNode.Group(
						id = ParameterGroupId(group),
						name = record.name ?: malformed("$path is a group without a name"),
						initiallyOpen = record.initiallyOpen ?: false,
						children = record.children.orEmpty().mapIndexed { childIndex, child -> parameterNodeOf(child, "$path.children[$childIndex]") },
					)

				else -> malformed("$path must name exactly one of a parameter and a group")
			}
		}

		/**
		 * UMA §4.2: one organizational child, which is exactly one of a part and a drawable.
		 *
		 * @param UmaOrgRef record The record.
		 * @param String    path   The child's position, for a failure.
		 * @return OrgChild The child.
		 */
		fun orgChildOf(record: UmaOrgRef, path: String): OrgChild {
			val part = record.part
			val drawable = record.drawable
			return when {
				part != null && drawable == null -> OrgChild.Part(PartId(part))
				drawable != null && part == null -> OrgChild.Drawable(DrawableId(drawable))
				else -> malformed("$path must name exactly one of a part and a drawable")
			}
		}

		/**
		 * UMA §4.4: one part.
		 *
		 * @param UmaPart record The record.
		 * @return Part The part.
		 */
		fun partOf(record: UmaPart): Part {
			val path = "parts[${record.id}]"
			return Part(
				id = PartId(record.id),
				name = record.name,
				children = record.children.orEmpty().mapIndexed { childIndex, child -> orgChildOf(child, "$path.children[$childIndex]") },
				isVisible = record.isVisible ?: true,
				isSketch = record.isSketch ?: false,
				isSelectable = record.isSelectable ?: true,
				groupMode = record.groupMode?.toRuntime() ?: PartGroupMode.PassThrough,
				drawOrder = record.drawOrder ?: DEFAULT_DRAW_ORDER,
				composite = compositeOf(record.composite ?: UmaPartComposite(), "$path.composite"),
			)
		}

		/**
		 * UMA §4.4: a part's composite.
		 *
		 * @param UmaPartComposite record The record.
		 * @param String           path   The composite's position, for a failure.
		 * @return PartComposite The composite.
		 */
		fun compositeOf(record: UmaPartComposite, path: String): PartComposite =
			PartComposite(
				blendMode = record.blendMode?.toRuntime() ?: BlendMode.Normal,
				alphaBlendMode = record.alphaBlendMode?.toRuntime() ?: AlphaBlendMode.Over,
				maskedBy = record.maskedBy.orEmpty().map(::DrawableId),
				maskedByParts = record.maskedByParts.orEmpty().map(::PartId),
				invertMask = record.invertMask ?: false,
				opacity = record.opacity ?: 1f,
				multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor"),
				screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor"),
			)

		/**
		 * UMA §4.5: one deformer, whose kind decides which kind-specific fields it must and must not carry.
		 *
		 * @param UmaDeformer record The record.
		 * @return Deformer The deformer.
		 */
		fun deformerOf(record: UmaDeformer): Deformer {
			val path = "deformers[${record.id}]"
			val multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor")
			val screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor")
			return when (record.kind) {
				UmaDeformerKind.Warp -> {
					if (record.baseAngle != null || record.flipX != null || record.flipY != null) {
						malformed("$path is a warp but carries rotation fields")
					}
					Deformer.Warp(
						id = DeformerId(record.id),
						name = record.name,
						parent = record.parent?.let(::DeformerId),
						partId = record.part?.let(::PartId),
						rows = record.rows ?: malformed("$path is a warp without rows"),
						columns = record.columns ?: malformed("$path is a warp without columns"),
						isQuadTransform = record.isQuadTransform ?: malformed("$path is a warp without isQuadTransform"),
						geometryGrid = null,
						opacity = record.opacity ?: 1f,
						multiplyColor = multiplyColor,
						screenColor = screenColor,
						isSelectable = record.isSelectable ?: true,
						isVisible = record.isVisible ?: true,
						isEnabled = record.isEnabled ?: true,
					)
				}

				UmaDeformerKind.Rotation -> {
					if (record.rows != null || record.columns != null || record.isQuadTransform != null) {
						malformed("$path is a rotation but carries warp fields")
					}
					Deformer.Rotation(
						id = DeformerId(record.id),
						name = record.name,
						parent = record.parent?.let(::DeformerId),
						partId = record.part?.let(::PartId),
						baseAngle = record.baseAngle ?: malformed("$path is a rotation without baseAngle"),
						geometryGrid = null,
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
		}

		/**
		 * UMA §4.6: one drawable's structure.
		 *
		 * @param UmaDrawable record The record.
		 * @return Drawable The drawable, with no mesh or keyforms.
		 */
		fun drawableOf(record: UmaDrawable): Drawable {
			val path = "drawables[${record.id}]"
			return Drawable(
				id = DrawableId(record.id),
				name = record.name,
				parentDeformerId = record.parentDeformer?.let(::DeformerId),
				blendMode = record.blendMode?.toRuntime() ?: BlendMode.Normal,
				maskedBy = record.maskedBy.orEmpty().map(::DrawableId),
				mesh = null,
				geometryGrid = null,
				drawOrder = record.drawOrder ?: DEFAULT_DRAW_ORDER.toFloat(),
				opacity = record.opacity ?: 1f,
				multiplyColor = colorOf(record.multiplyColor, ColorRgb.MultiplyIdentity, "$path.multiplyColor"),
				screenColor = colorOf(record.screenColor, ColorRgb.ScreenIdentity, "$path.screenColor"),
				invertMask = record.invertMask ?: false,
				alphaBlendMode = record.alphaBlendMode?.toRuntime() ?: AlphaBlendMode.Over,
				culling = record.culling ?: false,
				isVisible = record.isVisible ?: true,
				isSelectable = record.isSelectable ?: true,
				textureSourceId = record.textureSource?.let(::DrawableId),
				texturePage = record.texturePage ?: -1,
				atlasTileId = record.atlasTile?.let(::AtlasTileId),
			)
		}

		/**
		 * UMA §4.1: a color, which is exactly three channels, or [identity] when absent.
		 *
		 * @param List<Float>? channels The record's channels.
		 * @param ColorRgb     identity The channel's identity color.
		 * @param String       path     The color's position, for a failure.
		 * @return ColorRgb The color.
		 */
		fun colorOf(channels: List<Float>?, identity: ColorRgb, path: String): ColorRgb {
			if (channels == null) {
				return identity
			}
			if (channels.size != 3) {
				malformed("$path has ${channels.size} channels instead of red, green, and blue")
			}
			return ColorRgb(channels[0], channels[1], channels[2])
		}
	}
}