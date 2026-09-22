package org.umamo.interop.uma

import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm

/*
 * The structural comparer the UMA round-trip tests share: every field the puppet entry carries - structure,
 * meshes, keyform grids, channel tracks, blend shapes, glue - and the derived render root, compared exactly:
 * floats by their bits, since a shortest-round-trip decimal and a buffer both give back the same bits and
 * anything less is a bug.
 */

/**
 * Collects differences between an expected and an actual value by path.
 *
 * @property MutableList differences One line per difference.
 */
private class DifferenceCollector(val differences: MutableList<String> = ArrayList()) {
	/**
	 * Records a difference when two values are not equal.
	 *
	 * @param String path     Where the values sit.
	 * @param Any?   expected The expected value.
	 * @param Any?   actual   The actual value.
	 */
	fun same(path: String, expected: Any?, actual: Any?) {
		if (expected != actual) {
			differences += "$path: expected $expected, got $actual"
		}
	}

	/**
	 * Records a difference when two floats differ in any bit.
	 *
	 * @param String path     Where the values sit.
	 * @param Float  expected The expected value.
	 * @param Float  actual   The actual value.
	 */
	fun bits(path: String, expected: Float, actual: Float) {
		if (expected.toRawBits() != actual.toRawBits()) {
			differences += "$path: expected $expected (0x${expected.toRawBits().toUInt().toString(16)}), got $actual (0x${actual.toRawBits().toUInt().toString(16)})"
		}
	}

	/**
	 * Records a difference when two colors differ in any channel's bits.
	 *
	 * @param String   path     Where the values sit.
	 * @param ColorRgb expected The expected color.
	 * @param ColorRgb actual   The actual color.
	 */
	fun color(path: String, expected: ColorRgb, actual: ColorRgb) {
		bits("$path.red", expected.red, actual.red)
		bits("$path.green", expected.green, actual.green)
		bits("$path.blue", expected.blue, actual.blue)
	}

	/**
	 * Records a difference when two float arrays differ in length or in any bit.
	 *
	 * @param String     path     Where the arrays sit.
	 * @param FloatArray expected The expected array.
	 * @param FloatArray actual   The actual array.
	 */
	fun floats(path: String, expected: FloatArray, actual: FloatArray) {
		if (expected.size != actual.size) {
			differences += "$path: expected ${expected.size} floats, got ${actual.size}"
			return
		}
		val first = expected.indices.firstOrNull { valueIndex -> expected[valueIndex].toRawBits() != actual[valueIndex].toRawBits() } ?: return
		differences += "$path[$first]: expected ${expected[first]}, got ${actual[first]}"
	}

	/**
	 * Records a difference when two int arrays differ.
	 *
	 * @param String   path     Where the arrays sit.
	 * @param IntArray expected The expected array.
	 * @param IntArray actual   The actual array.
	 */
	fun ints(path: String, expected: IntArray, actual: IntArray) {
		if (!expected.contentEquals(actual)) {
			differences += "$path: the arrays differ"
		}
	}

	/**
	 * Compares two optional meshes.
	 *
	 * @param String        path     Where the meshes sit.
	 * @param DrawableMesh? expected The expected mesh.
	 * @param DrawableMesh? actual   The actual mesh.
	 */
	fun mesh(path: String, expected: DrawableMesh?, actual: DrawableMesh?) {
		if (expected == null || actual == null) {
			same("$path present", expected != null, actual != null)
			return
		}
		floats("$path.positions", expected.positions, actual.positions)
		floats("$path.uvs", expected.uvs, actual.uvs)
		ints("$path.indices", expected.indices, actual.indices)
	}

	/**
	 * Compares two optional keyform grids: axes, cell order, coordinates, and each cell's form.
	 *
	 * @param String       path        Where the grids sit.
	 * @param KeyformGrid? expected    The expected grid.
	 * @param KeyformGrid? actual      The actual grid.
	 * @param Function     compareForm Compares one pair of forms given its path.
	 */
	fun <T> grid(path: String, expected: KeyformGrid<T>?, actual: KeyformGrid<T>?, compareForm: (String, T, T) -> Unit) {
		if (expected == null || actual == null) {
			same("$path present", expected != null, actual != null)
			return
		}
		each("$path.axes", expected.axes, actual.axes) { axisPath, left, right ->
			same("$axisPath.parameter", left.parameterId, right.parameterId)
			floats("$axisPath.keys", left.keys, right.keys)
		}
		each("$path.cells", expected.cells, actual.cells) { cellPath, left, right ->
			ints("$cellPath.coordinate", left.coordinate, right.coordinate)
			compareForm(cellPath, left.form, right.form)
		}
	}

	/**
	 * Compares two owners' channel tracks, channel by channel.
	 *
	 * @param String       path     Where the tracks sit.
	 * @param ChannelGrids expected The expected tracks.
	 * @param ChannelGrids actual   The actual tracks.
	 */
	fun channels(path: String, expected: ChannelGrids, actual: ChannelGrids) {
		same("$path.channels keys", expected.gridsByChannel.keys, actual.gridsByChannel.keys)
		for ((channel, expectedGrid) in expected.gridsByChannel) {
			val actualGrid = actual.gridsByChannel[channel] ?: continue
			grid("$path.channels.${channel.name}", expectedGrid, actualGrid) { valuePath, left, right -> channelValue("$valuePath.value", left, right) }
		}
	}

	/**
	 * Compares two channel values by kind and bits.
	 *
	 * @param String       path     Where the values sit.
	 * @param ChannelValue expected The expected value.
	 * @param ChannelValue actual   The actual value.
	 */
	fun channelValue(path: String, expected: ChannelValue, actual: ChannelValue) {
		when {
			expected is ChannelValue.Scalar && actual is ChannelValue.Scalar -> bits(path, expected.value, actual.value)
			expected is ChannelValue.Color && actual is ChannelValue.Color -> color(path, expected.color, actual.color)
			expected is ChannelValue.Flag && actual is ChannelValue.Flag -> same(path, expected.flag, actual.flag)
			else -> differences += "$path: expected a ${expected::class.simpleName}, got a ${actual::class.simpleName}"
		}
	}

	/**
	 * Compares two owners' blend-shape bindings: parameters, keys, neutrals, forms, and limits.
	 *
	 * @param String   path        Where the bindings sit.
	 * @param List     expected    The expected bindings.
	 * @param List     actual      The actual bindings.
	 * @param Function compareForm Compares one pair of non-null forms given its path.
	 */
	fun <T : Any> blendShapes(path: String, expected: List<BlendShapeBinding<T>>, actual: List<BlendShapeBinding<T>>, compareForm: (String, T, T) -> Unit) {
		each("$path.blendShapes", expected, actual) { bindingPath, left, right ->
			same("$bindingPath.parameter", left.parameterId, right.parameterId)
			floats("$bindingPath.keys", left.keys, right.keys)
			same("$bindingPath.neutralIndex", left.neutralIndex, right.neutralIndex)
			each("$bindingPath.forms", left.forms, right.forms) { formPath, leftForm, rightForm ->
				if (leftForm == null || rightForm == null) {
					same("$formPath present", leftForm != null, rightForm != null)
				} else {
					compareForm(formPath, leftForm, rightForm)
				}
			}
			each("$bindingPath.limits", left.limits, right.limits) { limitPath, leftLimit, rightLimit ->
				same("$limitPath.parameter", leftLimit.parameterId, rightLimit.parameterId)
				each("$limitPath.points", leftLimit.points, rightLimit.points) { pointPath, leftPoint, rightPoint ->
					bits("$pointPath.value", leftPoint.value, rightPoint.value)
					bits("$pointPath.weight", leftPoint.weight, rightPoint.weight)
				}
			}
		}
	}

	/**
	 * Compares two render-tree nodes and their subtrees.
	 *
	 * @param String     path     Where the nodes sit.
	 * @param RenderNode expected The expected node.
	 * @param RenderNode actual   The actual node.
	 */
	fun renderNode(path: String, expected: RenderNode, actual: RenderNode) {
		when {
			expected is RenderDrawable && actual is RenderDrawable -> same("$path.id", expected.id, actual.id)
			expected is RenderGroup && actual is RenderGroup -> {
				same("$path.partId", expected.partId, actual.partId)
				same("$path.drawOrder", expected.drawOrder, actual.drawOrder)
				same("$path.composite", expected.composite, actual.composite)
				channels(path, expected.channelGrids, actual.channelGrids)
				each("$path.children", expected.children, actual.children) { childPath, left, right -> renderNode(childPath, left, right) }
			}

			else -> differences += "$path: expected a ${expected::class.simpleName}, got a ${actual::class.simpleName}"
		}
	}

	/**
	 * Compares two lists element by element after checking their sizes.
	 *
	 * @param String   path     Where the lists sit.
	 * @param List     expected The expected list.
	 * @param List     actual   The actual list.
	 * @param Function compare  Compares one pair of elements given its path.
	 */
	fun <T> each(path: String, expected: List<T>, actual: List<T>, compare: (String, T, T) -> Unit) {
		if (expected.size != actual.size) {
			differences += "$path: expected ${expected.size} elements, got ${actual.size}"
			return
		}
		for (elementIndex in expected.indices) {
			compare("$path[$elementIndex]", expected[elementIndex], actual[elementIndex])
		}
	}
}

/**
 * Every difference between two models across the structure the puppet entry carries.
 *
 * @param PuppetModel expected The model before the round trip.
 * @param PuppetModel actual   The model after it.
 * @return List<String> One line per difference; empty when the structure is identical.
 */
internal fun puppetStructureDifferences(expected: PuppetModel, actual: PuppetModel): List<String> {
	val collector = DifferenceCollector()
	with(collector) {
		bits("canvasWidth", expected.canvasWidth, actual.canvasWidth)
		bits("canvasHeight", expected.canvasHeight, actual.canvasHeight)
		bits("worldOriginX", expected.worldOriginX, actual.worldOriginX)
		bits("worldOriginY", expected.worldOriginY, actual.worldOriginY)
		same("pixelsPerUnit bits", expected.pixelsPerUnit?.toRawBits(), actual.pixelsPerUnit?.toRawBits())
		same("runtimeTarget", expected.runtimeTarget, actual.runtimeTarget)
		same("rendersFromSourceLayers", expected.rendersFromSourceLayers, actual.rendersFromSourceLayers)
		same("rootPartId", expected.rootPartId, actual.rootPartId)
		same("rootChildren", expected.rootChildren, actual.rootChildren)
		same("parameterLinks", expected.parameterLinks, actual.parameterLinks)
		same("parameterTree", expected.parameterTree, actual.parameterTree)

		each("parameters", expected.parameters, actual.parameters) { path, left, right ->
			same("$path.id", left.id, right.id)
			same("$path.name", left.name, right.name)
			bits("$path.min", left.min, right.min)
			bits("$path.max", left.max, right.max)
			bits("$path.default", left.default, right.default)
			same("$path.kind", left.kind, right.kind)
			same("$path.repeat", left.repeat, right.repeat)
		}

		each("parts", expected.parts, actual.parts) { path, left, right ->
			same("$path.id", left.id, right.id)
			same("$path.name", left.name, right.name)
			same("$path.children", left.children, right.children)
			same("$path.isVisible", left.isVisible, right.isVisible)
			same("$path.isSketch", left.isSketch, right.isSketch)
			same("$path.isSelectable", left.isSelectable, right.isSelectable)
			same("$path.groupMode", left.groupMode, right.groupMode)
			same("$path.drawOrder", left.drawOrder, right.drawOrder)
			val leftComposite = left.composite
			val rightComposite = right.composite
			same("$path.composite.blendMode", leftComposite.blendMode, rightComposite.blendMode)
			same("$path.composite.alphaBlendMode", leftComposite.alphaBlendMode, rightComposite.alphaBlendMode)
			same("$path.composite.maskedBy", leftComposite.maskedBy, rightComposite.maskedBy)
			same("$path.composite.maskedByParts", leftComposite.maskedByParts, rightComposite.maskedByParts)
			same("$path.composite.invertMask", leftComposite.invertMask, rightComposite.invertMask)
			bits("$path.composite.opacity", leftComposite.opacity, rightComposite.opacity)
			color("$path.composite.multiplyColor", leftComposite.multiplyColor, rightComposite.multiplyColor)
			color("$path.composite.screenColor", leftComposite.screenColor, rightComposite.screenColor)
			channels(path, left.channelGrids, right.channelGrids)
			blendShapes(path, left.blendShapes, right.blendShapes) { formPath, leftForm: PartForm, rightForm -> partForm(formPath, leftForm, rightForm) }
		}

		each("deformers", expected.deformers, actual.deformers) { path, left, right ->
			same("$path.id", left.id, right.id)
			same("$path.name", left.name, right.name)
			same("$path.parent", left.parent, right.parent)
			same("$path.partId", left.partId, right.partId)
			same("$path.isVisible", left.isVisible, right.isVisible)
			same("$path.isEnabled", left.isEnabled, right.isEnabled)
			same("$path.isSelectable", left.isSelectable, right.isSelectable)
			when {
				left is Deformer.Warp && right is Deformer.Warp -> {
					same("$path.rows", left.rows, right.rows)
					same("$path.columns", left.columns, right.columns)
					same("$path.isQuadTransform", left.isQuadTransform, right.isQuadTransform)
					bits("$path.opacity", left.opacity, right.opacity)
					color("$path.multiplyColor", left.multiplyColor, right.multiplyColor)
					color("$path.screenColor", left.screenColor, right.screenColor)
					grid("$path.geometry", left.geometryGrid, right.geometryGrid) { formPath, leftForm, rightForm -> floats("$formPath.controlPoints", leftForm.controlPoints, rightForm.controlPoints) }
					blendShapes(path, left.blendShapes, right.blendShapes) { formPath, leftForm: WarpForm, rightForm -> warpForm(formPath, leftForm, rightForm) }
				}

				left is Deformer.Rotation && right is Deformer.Rotation -> {
					bits("$path.baseAngle", left.baseAngle, right.baseAngle)
					same("$path.flipX", left.flipX, right.flipX)
					same("$path.flipY", left.flipY, right.flipY)
					bits("$path.opacity", left.opacity, right.opacity)
					color("$path.multiplyColor", left.multiplyColor, right.multiplyColor)
					color("$path.screenColor", left.screenColor, right.screenColor)
					grid("$path.geometry", left.geometryGrid, right.geometryGrid) { formPath, leftForm, rightForm ->
						bits("$formPath.originX", leftForm.originX, rightForm.originX)
						bits("$formPath.originY", leftForm.originY, rightForm.originY)
						bits("$formPath.angle", leftForm.angle, rightForm.angle)
						bits("$formPath.scale", leftForm.scale, rightForm.scale)
					}
					blendShapes(path, left.blendShapes, right.blendShapes) { formPath, leftForm: RotationForm, rightForm -> rotationForm(formPath, leftForm, rightForm) }
				}

				else -> differences += "$path: expected a ${left::class.simpleName}, got a ${right::class.simpleName}"
			}
			channels(path, left.channelGrids, right.channelGrids)
		}

		each("drawables", expected.drawables, actual.drawables) { path, left, right ->
			same("$path.id", left.id, right.id)
			same("$path.name", left.name, right.name)
			same("$path.parentDeformerId", left.parentDeformerId, right.parentDeformerId)
			same("$path.blendMode", left.blendMode, right.blendMode)
			same("$path.maskedBy", left.maskedBy, right.maskedBy)
			same("$path.invertMask", left.invertMask, right.invertMask)
			bits("$path.drawOrder", left.drawOrder, right.drawOrder)
			bits("$path.opacity", left.opacity, right.opacity)
			color("$path.multiplyColor", left.multiplyColor, right.multiplyColor)
			color("$path.screenColor", left.screenColor, right.screenColor)
			same("$path.alphaBlendMode", left.alphaBlendMode, right.alphaBlendMode)
			same("$path.culling", left.culling, right.culling)
			same("$path.isVisible", left.isVisible, right.isVisible)
			same("$path.isSelectable", left.isSelectable, right.isSelectable)
			same("$path.textureSourceId", left.textureSourceId, right.textureSourceId)
			same("$path.texturePage", left.texturePage, right.texturePage)
			same("$path.atlasTileId", left.atlasTileId, right.atlasTileId)
			mesh("$path.mesh", left.mesh, right.mesh)
			grid("$path.geometry", left.geometryGrid, right.geometryGrid) { formPath, leftForm, rightForm -> floats("$formPath.positionDeltas", leftForm.positionDeltas, rightForm.positionDeltas) }
			channels(path, left.channelGrids, right.channelGrids)
			blendShapes(path, left.blendShapes, right.blendShapes) { formPath, leftForm: MeshForm, rightForm -> meshForm(formPath, leftForm, rightForm) }
		}

		each("glues", expected.glues, actual.glues) { path, left, right ->
			same("$path.meshA", left.meshA, right.meshA)
			same("$path.meshB", left.meshB, right.meshB)
			same("$path.id", left.id, right.id)
			bits("$path.intensity", left.intensity, right.intensity)
			each("$path.pairs", left.pairs, right.pairs) { pairPath, leftPair, rightPair ->
				same("$pairPath.indexA", leftPair.indexA, rightPair.indexA)
				same("$pairPath.indexB", leftPair.indexB, rightPair.indexB)
				bits("$pairPath.weightA", leftPair.weightA, rightPair.weightA)
				bits("$pairPath.weightB", leftPair.weightB, rightPair.weightB)
			}
			channels(path, left.channelGrids, right.channelGrids)
		}

		renderNode("renderRoot", expected.renderRoot, actual.renderRoot)
	}
	return collector.differences
}

/**
 * Every difference between two models' atlases: page sizes, the page addressing and composition, and every tile
 * with its placement compared by bits, its binding, pin, and lineage.
 *
 * @param PuppetModel expected The model before the round trip.
 * @param PuppetModel actual   The model after it.
 * @return List<String> One line per difference; empty when the atlases are identical.
 */
internal fun atlasDifferences(expected: PuppetModel, actual: PuppetModel): List<String> {
	val collector = DifferenceCollector()
	with(collector) {
		val left = expected.atlas
		val right = actual.atlas
		same("atlas.pages", left.pages, right.pages)
		same("atlas.storedUvsAddressPages", left.storedUvsAddressPages, right.storedUvsAddressPages)
		same("atlas.composition", left.composition, right.composition)
		each("atlas.tiles", left.tiles, right.tiles) { path, leftTile, rightTile ->
			same("$path.id", leftTile.id, rightTile.id)
			same("$path.name", leftTile.name, rightTile.name)
			same("$path.width", leftTile.width, rightTile.width)
			same("$path.height", leftTile.height, rightTile.height)
			same("$path.source", leftTile.source, rightTile.source)
			same("$path.pinned", leftTile.pinned, rightTile.pinned)
			same("$path.replaces", leftTile.replaces, rightTile.replaces)
			val leftPlacement = leftTile.placement
			val rightPlacement = rightTile.placement
			if (leftPlacement == null || rightPlacement == null) {
				same("$path.placement present", leftPlacement != null, rightPlacement != null)
			} else {
				same("$path.placement.pageIndex", leftPlacement.pageIndex, rightPlacement.pageIndex)
				bits("$path.placement.positionX", leftPlacement.positionX, rightPlacement.positionX)
				bits("$path.placement.positionY", leftPlacement.positionY, rightPlacement.positionY)
				bits("$path.placement.scaleX", leftPlacement.scaleX, rightPlacement.scaleX)
				bits("$path.placement.scaleY", leftPlacement.scaleY, rightPlacement.scaleY)
				bits("$path.placement.rotationDegrees", leftPlacement.rotationDegrees, rightPlacement.rotationDegrees)
			}
		}
	}
	return collector.differences
}

/**
 * Every difference between two models' linked source art: each source's record and every inventory row with every
 * flag and hash.
 *
 * @param PuppetModel expected The model before the round trip.
 * @param PuppetModel actual   The model after it.
 * @return List<String> One line per difference; empty when the sources are identical.
 */
internal fun sourcesDifferences(expected: PuppetModel, actual: PuppetModel): List<String> {
	val collector = DifferenceCollector()
	with(collector) {
		each("sources", expected.sources, actual.sources) { path, left, right ->
			same("$path.id", left.id, right.id)
			same("$path.name", left.name, right.name)
			same("$path.path", left.path, right.path)
			same("$path.format", left.format, right.format)
			same("$path.contentHash", left.contentHash, right.contentHash)
			same("$path.lastModified", left.lastModified, right.lastModified)
			same("$path.offsetX", left.offsetX, right.offsetX)
			same("$path.offsetZ", left.offsetZ, right.offsetZ)
			each("$path.layers", left.layers, right.layers) { layerPath, leftLayer, rightLayer -> same(layerPath, leftLayer, rightLayer) }
		}
	}
	return collector.differences
}

/**
 * Every difference between two models across everything a UMA document carries: the puppet entry's structure and
 * geometry, the atlas, and the linked source art.
 *
 * @param PuppetModel expected The model before the round trip.
 * @param PuppetModel actual   The model after it.
 * @return List<String> One line per difference; empty when the documents are identical.
 */
internal fun documentDifferences(expected: PuppetModel, actual: PuppetModel): List<String> =
	puppetStructureDifferences(expected, actual) + atlasDifferences(expected, actual) + sourcesDifferences(expected, actual)

/**
 * Compares two drawable blend forms.
 *
 * @param String   path     Where the forms sit.
 * @param MeshForm expected The expected form.
 * @param MeshForm actual   The actual form.
 */
private fun DifferenceCollector.meshForm(path: String, expected: MeshForm, actual: MeshForm) {
	floats("$path.positionDeltas", expected.positionDeltas, actual.positionDeltas)
	bits("$path.drawOrder", expected.drawOrder, actual.drawOrder)
	bits("$path.opacity", expected.opacity, actual.opacity)
	color("$path.multiplyColor", expected.multiplyColor, actual.multiplyColor)
	color("$path.screenColor", expected.screenColor, actual.screenColor)
}

/**
 * Compares two warp blend forms.
 *
 * @param String   path     Where the forms sit.
 * @param WarpForm expected The expected form.
 * @param WarpForm actual   The actual form.
 */
private fun DifferenceCollector.warpForm(path: String, expected: WarpForm, actual: WarpForm) {
	floats("$path.controlPoints", expected.controlPoints, actual.controlPoints)
	bits("$path.opacity", expected.opacity, actual.opacity)
	color("$path.multiplyColor", expected.multiplyColor, actual.multiplyColor)
	color("$path.screenColor", expected.screenColor, actual.screenColor)
}

/**
 * Compares two rotation blend forms.
 *
 * @param String       path     Where the forms sit.
 * @param RotationForm expected The expected form.
 * @param RotationForm actual   The actual form.
 */
private fun DifferenceCollector.rotationForm(path: String, expected: RotationForm, actual: RotationForm) {
	bits("$path.originX", expected.originX, actual.originX)
	bits("$path.originY", expected.originY, actual.originY)
	bits("$path.angle", expected.angle, actual.angle)
	bits("$path.scale", expected.scale, actual.scale)
	same("$path.flipX", expected.flipX, actual.flipX)
	same("$path.flipY", expected.flipY, actual.flipY)
	bits("$path.opacity", expected.opacity, actual.opacity)
	color("$path.multiplyColor", expected.multiplyColor, actual.multiplyColor)
	color("$path.screenColor", expected.screenColor, actual.screenColor)
}

/**
 * Compares two part blend forms.
 *
 * @param String   path     Where the forms sit.
 * @param PartForm expected The expected form.
 * @param PartForm actual   The actual form.
 */
private fun DifferenceCollector.partForm(path: String, expected: PartForm, actual: PartForm) {
	bits("$path.drawOrder", expected.drawOrder, actual.drawOrder)
	bits("$path.opacity", expected.opacity, actual.opacity)
	color("$path.multiplyColor", expected.multiplyColor, actual.multiplyColor)
	color("$path.screenColor", expected.screenColor, actual.screenColor)
}