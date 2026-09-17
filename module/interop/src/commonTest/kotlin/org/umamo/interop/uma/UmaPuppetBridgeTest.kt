package org.umamo.interop.uma

import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.format.uma.puppet.UmaDeformer
import org.umamo.format.uma.puppet.UmaDeformerKind
import org.umamo.format.uma.puppet.UmaDrawable
import org.umamo.format.uma.puppet.UmaOrgRef
import org.umamo.format.uma.puppet.UmaParameter
import org.umamo.format.uma.puppet.UmaParameterNode
import org.umamo.format.uma.puppet.UmaPart
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
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
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.deriveRenderRoot
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the puppet entry's bridge in both directions (docs/format/UMA.md §4) on synthetic models: every
 * structure field away from its default and every enum value survives a save exactly, defaults stay out of
 * the file, and the shapes the flat schema cannot enforce fail as malformed.
 */
class UmaPuppetBridgeTest {
	private val writer = UmaWriterInfo("Umamo", "test")

	/**
	 * [model] saved as UMA and read back.
	 *
	 * @param PuppetModel model The model.
	 * @return PuppetModel The model the file describes.
	 */
	private fun roundTrip(model: PuppetModel): PuppetModel {
		val bytes = Uma.write(UmaModel.create(writer).withPuppet(UmaPuppetExport.puppetOf(model)))
		return UmaPuppetImport.modelOf(Uma.read(bytes).puppet!!)
	}

	/**
	 * A model with every structure field away from its default, every enum value used somewhere, and the
	 * floats a decimal round trip is most likely to bend.
	 *
	 * @param RuntimeTarget runtimeTarget The document's target.
	 * @return PuppetModel The model.
	 */
	private fun fullModel(runtimeTarget: RuntimeTarget): PuppetModel {
		val drawables =
			BlendMode.entries.mapIndexed { modeIndex, mode ->
				val alphaMode = AlphaBlendMode.entries[modeIndex % AlphaBlendMode.entries.size]
				if (modeIndex == 0) {
					Drawable(
						id = DrawableId("D0"),
						name = "Eye L",
						parentDeformerId = DeformerId("R1"),
						blendMode = mode,
						maskedBy = listOf(DrawableId("D1")),
						mesh = null,
						geometryGrid = null,
						drawOrder = 499.5f,
						opacity = -0.0f,
						multiplyColor = ColorRgb(0.5f, -0.0f, Float.MIN_VALUE),
						screenColor = ColorRgb(0.1f, 0.2f, 0.3f),
						invertMask = true,
						alphaBlendMode = alphaMode,
						culling = true,
						isVisible = false,
						isSelectable = false,
						textureSourceId = DrawableId("D1"),
						texturePage = 2,
						atlasTileId = AtlasTileId("art-0/lyid:12~1"),
					)
				} else {
					Drawable(DrawableId("D$modeIndex"), "Drawable $modeIndex", null, mode, emptyList(), null, null, alphaBlendMode = alphaMode)
				}
			}
		return PuppetModel(
			parameters =
				listOf(
					Parameter(ParameterId("P0"), "Angle", -0.0f, 30f, Float.MIN_VALUE),
					Parameter(ParameterId("P1"), "Morph", -1f, 1f, 1.4e-44f, ParameterKind.BLEND_SHAPE, repeat = true),
				),
			parts =
				listOf(
					Part(
						id = PartId("Head"),
						name = "Head",
						children = listOf(OrgChild.Drawable(DrawableId("D1")), OrgChild.Part(PartId("Arm"))),
						isVisible = false,
						isSketch = true,
						isSelectable = false,
						groupMode = PartGroupMode.Grouped,
						drawOrder = 700,
						composite =
							PartComposite(
								blendMode = BlendMode.Multiply,
								alphaBlendMode = AlphaBlendMode.Disjoint,
								maskedBy = listOf(DrawableId("D0")),
								maskedByParts = listOf(PartId("Body")),
								invertMask = true,
								opacity = 0.25f,
								multiplyColor = ColorRgb(0.5f, 0.25f, 1f),
								screenColor = ColorRgb(0.125f, 0f, 0f),
							),
					),
					Part(PartId("Body"), "Body", listOf(OrgChild.Drawable(DrawableId("D2"))), groupMode = PartGroupMode.Isolated),
					Part(PartId("Arm"), "Arm", emptyList()),
				),
			deformers =
				listOf(
					Deformer.Warp(
						id = DeformerId("W1"),
						name = "Warp",
						parent = null,
						partId = PartId("Head"),
						rows = 3,
						columns = 4,
						isQuadTransform = false,
						geometryGrid = null,
						opacity = 0.5f,
						multiplyColor = ColorRgb(0.9f, 0.8f, 0.7f),
						screenColor = ColorRgb(0.01f, 0.02f, 0.03f),
						isSelectable = false,
						isVisible = false,
						isEnabled = false,
					),
					Deformer.Rotation(
						id = DeformerId("R1"),
						name = "Rotation",
						parent = DeformerId("W1"),
						partId = PartId("Body"),
						baseAngle = -0.0f,
						geometryGrid = null,
						flipX = true,
						flipY = true,
					),
				),
			drawables = drawables,
			rootChildren = listOf(OrgChild.Part(PartId("Head")), OrgChild.Part(PartId("Body")), OrgChild.Drawable(DrawableId("D0"))),
			rootPartId = PartId("__RootPart__"),
			parameterLinks = listOf(ParameterLink(ParameterId("P0"), ParameterId("P1"))),
			parameterTree =
				listOf(
					ParameterNode.Group(
						ParameterGroupId("G1"),
						"Face",
						initiallyOpen = true,
						children = listOf(ParameterNode.Param(ParameterId("P0")), ParameterNode.Group(ParameterGroupId("G2"), "", false, listOf(ParameterNode.Param(ParameterId("P1"))))),
					),
				),
			canvasWidth = 2400f,
			canvasHeight = 3000f,
			worldOriginX = 1200f,
			worldOriginY = -0.0f,
			pixelsPerUnit = 1f,
			runtimeTarget = runtimeTarget,
			rendersFromSourceLayers = true,
		).withDerivedRenderRoot()
	}

	/**
	 * Every structure field survives a save exactly, under every runtime target.
	 */
	@Test
	fun everyStructureFieldRoundTripsExactly() {
		for (target in RuntimeTarget.entries) {
			val model = fullModel(target)
			val differences = puppetStructureDifferences(model, roundTrip(model))
			assertTrue(differences.isEmpty(), "$target: ${differences.take(20)}")
		}
	}

	/**
	 * The comparer the round trips rest on does see a single flipped bit, a renamed object, and a reordered
	 * list, so an empty result means something.
	 */
	@Test
	fun comparerSeesSmallDifferences() {
		val model = fullModel(RuntimeTarget.NoTarget)
		val signFlip = model.copy(drawables = model.drawables.map { drawable -> if (drawable.id == DrawableId("D0")) drawable.copy(opacity = 0.0f) else drawable })
		assertEquals(listOf("drawables[0].opacity"), puppetStructureDifferences(model, signFlip).map { difference -> difference.substringBefore(':') }, "-0.0 against 0.0")
		val renamed = model.copy(parts = model.parts.map { part -> if (part.id == PartId("Arm")) part.copy(name = "Arm L") else part })
		assertEquals(listOf("parts[2].name"), puppetStructureDifferences(model, renamed).map { difference -> difference.substringBefore(':') }, "a rename")
		assertTrue(puppetStructureDifferences(model, model.copy(parameters = model.parameters.reversed())).isNotEmpty(), "a reorder")
	}

	/**
	 * The render root is never read from the file; the model always carries the organizational tree's own
	 * derivation.
	 */
	@Test
	fun renderRootIsDerived() {
		val reopened = roundTrip(fullModel(RuntimeTarget.NoTarget))
		assertEquals(reopened.deriveRenderRoot(), reopened.renderRoot, "the render root is the derivation")
		assertTrue(reopened.renderRoot.children.any { node -> node is RenderGroup && node.partId == PartId("Head") }, "and a grouped part forms its group")
	}

	/**
	 * A model at every default writes only what the schema requires.
	 */
	@Test
	fun defaultsStayOutOfTheFile() {
		val model =
			PuppetModel(
				parameters = listOf(Parameter(ParameterId("P"), "P", 0f, 1f, 0f)),
				parts = listOf(Part(PartId("Part"), "Part", emptyList())),
				deformers =
					listOf(
						Deformer.Warp(DeformerId("W"), "W", null, null, 2, 2, true, null),
						Deformer.Rotation(DeformerId("R"), "R", null, null, 0f, null),
					),
				drawables = listOf(Drawable(DrawableId("D"), "D", null, BlendMode.Normal, emptyList(), null, null)),
				rootChildren = emptyList(),
				rootPartId = null,
			).withDerivedRenderRoot()
		val expected =
			UmaPuppet(
				parameters = listOf(UmaParameter("P", "P", 0f, 1f, 0f)),
				parts = listOf(UmaPart("Part", "Part")),
				deformers =
					listOf(
						UmaDeformer("W", UmaDeformerKind.Warp, "W", rows = 2, columns = 2, isQuadTransform = true),
						UmaDeformer("R", UmaDeformerKind.Rotation, "R", baseAngle = 0f),
					),
				drawables = listOf(UmaDrawable("D", "D")),
			)
		assertEquals(expected, UmaPuppetExport.puppetOf(model))
		assertTrue(puppetStructureDifferences(model, roundTrip(model)).isEmpty(), "and restores every default on read")
	}

	/**
	 * A non-finite value refuses the save and names where it sits.
	 */
	@Test
	fun nonFiniteValueRefusesTheSaveByPath() {
		val model = fullModel(RuntimeTarget.NoTarget).let { base -> base.copy(drawables = base.drawables.map { drawable -> if (drawable.id == DrawableId("D3")) drawable.copy(opacity = Float.NaN) else drawable }) }
		val failure = assertFailsWith<UmaWriteException> { UmaPuppetExport.puppetOf(model) }
		assertTrue(failure.message.orEmpty().contains("drawables[D3].opacity"), "the failure names the value: ${failure.message}")
	}

	/**
	 * Each shape the flat schema allows but the model does not fails as a malformed entry.
	 */
	@Test
	fun impossibleShapesAreMalformed() {
		/**
		 * Asserts that bridging [puppet] fails as a malformed entry.
		 *
		 * @param UmaPuppet puppet The entry.
		 * @param String    label  What is wrong with it.
		 */
		fun assertMalformed(puppet: UmaPuppet, label: String) {
			val failure = assertFailsWith<UmaFormatException>(label) { UmaPuppetImport.modelOf(puppet) }.failure
			assertIs<UmaReadFailure.MalformedEntry>(failure, label)
		}
		assertMalformed(UmaPuppet(rootChildren = listOf(UmaOrgRef())), "an org reference naming nothing")
		assertMalformed(UmaPuppet(rootChildren = listOf(UmaOrgRef(part = "P", drawable = "D"))), "an org reference naming both")
		assertMalformed(UmaPuppet(parameterTree = listOf(UmaParameterNode(parameter = "P", group = "G"))), "a tree node that is both")
		assertMalformed(UmaPuppet(parameterTree = listOf(UmaParameterNode(group = "G"))), "a group without a name")
		assertMalformed(UmaPuppet(parameterTree = listOf(UmaParameterNode(parameter = "P", name = "P"))), "a leaf with a group's name")
		assertMalformed(UmaPuppet(deformers = listOf(UmaDeformer("W", UmaDeformerKind.Warp, "W", columns = 2, isQuadTransform = true))), "a warp without rows")
		assertMalformed(UmaPuppet(deformers = listOf(UmaDeformer("W", UmaDeformerKind.Warp, "W", rows = 2, columns = 2, isQuadTransform = true, baseAngle = 1f))), "a warp with a rotation field")
		assertMalformed(UmaPuppet(deformers = listOf(UmaDeformer("R", UmaDeformerKind.Rotation, "R"))), "a rotation without baseAngle")
		assertMalformed(UmaPuppet(deformers = listOf(UmaDeformer("R", UmaDeformerKind.Rotation, "R", baseAngle = 0f, rows = 2))), "a rotation with a warp field")
		assertMalformed(UmaPuppet(drawables = listOf(UmaDrawable("D", "D", multiplyColor = listOf(1f, 1f)))), "a color with two channels")
	}
}