package org.umamo.interop.uma

import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.PuppetModel

/*
 * The structural comparer the UMA round-trip tests share: every structure field the puppet entry carries,
 * compared exactly - floats by their bits, since a shortest-round-trip decimal parses back to the same bits
 * and anything less is a bug.  Meshes, keyform tracks, blend shapes, glue, and the render root are not
 * compared yet.
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
				}

				left is Deformer.Rotation && right is Deformer.Rotation -> {
					bits("$path.baseAngle", left.baseAngle, right.baseAngle)
					same("$path.flipX", left.flipX, right.flipX)
					same("$path.flipY", left.flipY, right.flipY)
					bits("$path.opacity", left.opacity, right.opacity)
					color("$path.multiplyColor", left.multiplyColor, right.multiplyColor)
					color("$path.screenColor", left.screenColor, right.screenColor)
				}

				else -> differences += "$path: expected a ${left::class.simpleName}, got a ${right::class.simpleName}"
			}
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
		}
	}
	return collector.differences
}