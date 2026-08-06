package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.format.moc3.model.WarpKeyform
import java.io.File
import kotlin.test.Test

/**
 * TEMPORARY refactor harness - delete once the MocDecoder decomposition is finished.
 *
 * Writes an exhaustive textual dump of every corpus model's decoded [MocDocument] so a refactor can
 * be proven output-identical rather than merely test-green.  The existing bake/round-trip gates are
 * self-consistency checks (decode -> encode -> decode), so a decode change that moves both sides
 * together passes them; this dump is an absolute record instead.  Floats are dumped as raw bits so
 * no value is lost to formatting, and NaN compares equal to itself.
 */
class DecodeDumpOracleTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	/**
	 * Renders a float as its raw bit pattern, the only lossless textual form.
	 *
	 * @param Float value The value to render.
	 * @return String The bits in hex.
	 */
	private fun bits(value: Float): String = value.toRawBits().toUInt().toString(16)

	/**
	 * Renders a float array as a bracketed list of raw bit patterns.
	 *
	 * @param FloatArray values The values to render.
	 * @return String The rendered array.
	 */
	private fun bits(values: FloatArray): String = values.joinToString(",") { bits(it) }

	/**
	 * Renders an optional color, distinguishing absent from any present value.
	 *
	 * @param Rgb? color The color to render, or null.
	 * @return String The rendered color.
	 */
	private fun render(color: Rgb?): String =
		if (color == null) {
			"none"
		} else {
			"${bits(color.r)}/${bits(color.g)}/${bits(color.b)}"
		}

	/**
	 * Renders one art-mesh keyform, colors included.
	 *
	 * @param ArtMeshKeyform form The keyform to render.
	 * @return String The rendered keyform.
	 */
	private fun render(form: ArtMeshKeyform): String =
		"pos=[${bits(form.vertexPositions)}] op=${bits(form.opacity)} draw=${bits(form.drawOrder)} " +
			"mul=${render(form.multiplyColor)} scr=${render(form.screenColor)}"

	/**
	 * Renders one warp keyform, colors included.
	 *
	 * @param WarpKeyform form The keyform to render.
	 * @return String The rendered keyform.
	 */
	private fun render(form: WarpKeyform): String =
		"cp=[${bits(form.controlPoints)}] op=${bits(form.opacity)} " +
			"mul=${render(form.multiplyColor)} scr=${render(form.screenColor)}"

	/**
	 * Renders one rotation keyform, colors included.
	 *
	 * @param RotationKeyform form The keyform to render.
	 * @return String The rendered keyform.
	 */
	private fun render(form: RotationKeyform): String =
		"o=${bits(form.originX)},${bits(form.originY)} ang=${bits(form.angle)} sc=${bits(form.scale)} " +
			"rx=${form.reflectX} ry=${form.reflectY} op=${bits(form.opacity)} " +
			"mul=${render(form.multiplyColor)} scr=${render(form.screenColor)}"

	@Test
	fun dumpsEveryCorpusModel() {
		val directory = samplesDir
		if (directory == null) {
			println("moc3.samples not present; skipping decode dump")
			return
		}
		val files = directory.walkTopDown().filter { it.isFile && it.extension == "moc3" }.sortedBy { it.name }.toList()
		check(files.isNotEmpty()) { "moc3.samples resolved to $directory but held no .moc3 files" }

		val dump = StringBuilder()
		for (file in files) {
			val document = Moc3.decode(MocCodec.read(file.readBytes()))
			dump.appendLine("##### ${file.name} v${document.version} unionFlag=${document.keyPositionsHasParameterUnion}")

			val canvas = document.canvas
			dump.appendLine(
				"canvas ${canvas?.let {
					"${bits(it.pixelsPerUnit)} ${bits(it.originX)} ${bits(it.originY)} ${bits(it.width)} ${bits(it.height)}"
				}}",
			)

			for ((parameterIndex, parameter) in document.parameters.withIndex()) {
				dump.appendLine(
					"param $parameterIndex ${parameter.id} ${bits(parameter.minimumValue)} " +
						"${bits(parameter.defaultValue)} ${bits(parameter.maximumValue)}",
				)
			}
			for (binding in document.bindings) {
				val axes = binding.axes.joinToString(";") { "p${it.parameterIndex}:[${bits(it.keyPositions)}]" }
				dump.appendLine("binding ${binding.index} grid=${binding.gridSize} $axes")
			}
			for ((partIndex, part) in document.parts.withIndex()) {
				dump.appendLine(
					"part $partIndex ${part.id} parent=${part.parentPartIndex} bind=${part.keyformBindingIndex} " +
						"vis=${part.isVisible} draw=[${bits(part.drawOrderKeyforms)}]",
				)
			}
			for ((deformerIndex, deformer) in document.deformers.withIndex()) {
				val head =
					"deformer $deformerIndex ${deformer.id} bind=${deformer.keyformBindingIndex} " +
						"vis=${deformer.isVisible} en=${deformer.isEnabled} " +
						"part=${deformer.parentPartIndex} parent=${deformer.parentDeformerIndex}"
				when (deformer) {
					is WarpDeformer -> {
						dump.appendLine("$head WARP rows=${deformer.rows} cols=${deformer.columns} mode=${deformer.mode}")
						for ((formIndex, form) in deformer.keyforms.withIndex()) {
							dump.appendLine("  wf $formIndex ${render(form)}")
						}
					}
					is RotationDeformer -> {
						dump.appendLine("$head ROT base=${bits(deformer.baseAngle)}")
						for ((formIndex, form) in deformer.keyforms.withIndex()) {
							dump.appendLine("  rf $formIndex ${render(form)}")
						}
					}
				}
			}
			for ((meshIndex, mesh) in document.artMeshes.withIndex()) {
				dump.appendLine(
					"mesh $meshIndex ${mesh.id} tex=${mesh.textureIndex} flags=${mesh.constantFlags} " +
						"xblend=${mesh.extendedBlend} vis=${mesh.isVisible} en=${mesh.isEnabled} " +
						"part=${mesh.parentPartIndex} def=${mesh.parentDeformerIndex} bind=${mesh.keyformBindingIndex} " +
						"uv=[${bits(mesh.vertexUvs)}] idx=[${mesh.triangleIndices.joinToString(",")}] " +
						"mask=[${mesh.maskDrawableIndices.joinToString(",")}]",
				)
				for ((formIndex, form) in mesh.keyforms.withIndex()) {
					dump.appendLine("  mf $formIndex ${render(form)}")
				}
			}
			for ((glueIndex, glue) in document.glues.withIndex()) {
				val pairs = glue.pairs.joinToString(";") { "${it.vertexA}/${it.vertexB}/${bits(it.weightA)}/${bits(it.weightB)}" }
				dump.appendLine(
					"glue $glueIndex ${glue.id} a=${glue.meshAIndex} b=${glue.meshBIndex} " +
						"bind=${glue.keyformBindingIndex} intensity=[${bits(glue.intensityKeyforms)}] pairs=$pairs",
				)
			}
			for ((groupIndex, group) in document.renderOrderGroups.withIndex()) {
				val children = group.children.joinToString(";") { "${it.kind}/${it.index}/${it.groupIndex}" }
				dump.appendLine("group $groupIndex $children")
			}
			for ((shapeIndex, shape) in document.blendShapes.withIndex()) {
				dump.appendLine(
					"blend $shapeIndex ${shape.target} target=${shape.targetIndex} param=${shape.parameterIndex} " +
						"keys=[${bits(shape.keyPositions)}] neutral=${shape.neutralKeyIndex} base=${shape.recordBase}",
				)
				for ((limitIndex, limit) in shape.limits.withIndex()) {
					dump.appendLine(
						"  limit $limitIndex p${limit.parameterIndex} keys=[${bits(limit.keyPositions)}] " +
							"w=[${bits(limit.weights)}]",
					)
				}
				for ((formIndex, form) in shape.keyforms.withIndex()) {
					val rendered =
						when (form) {
							is BlendShapeKeyform.Mesh -> render(form.form)
							is BlendShapeKeyform.Warp -> render(form.form)
							is BlendShapeKeyform.Rotation -> render(form.form)
							is BlendShapeKeyform.Part -> "partDelta=${bits(form.drawOrderDelta)}"
						}
					dump.appendLine("  bf $formIndex $rendered")
				}
			}
			for ((offscreenIndex, offscreen) in document.offscreens.withIndex()) {
				dump.appendLine(
					"offscreen $offscreenIndex owner=${offscreen.ownerPartIndex} flags=${offscreen.constantFlags} " +
						"blend=${offscreen.blendMode} maskCount=${offscreen.maskCount} " +
						"masks=[${offscreen.maskIndices.joinToString(",")}]",
				)
				for ((formIndex, form) in offscreen.keyforms.withIndex()) {
					dump.appendLine(
						"  of $formIndex op=${bits(form.opacity)} mul=${render(form.multiplyColor)} " +
							"scr=${render(form.screenColor)}",
					)
				}
			}
		}

		val target = File("build/decode-dump.txt")
		target.parentFile.mkdirs()
		target.writeText(dump.toString())
		println("decode dump: ${files.size} models, ${dump.length} chars -> ${target.absolutePath}")
	}
}
