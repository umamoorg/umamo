package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.ExportNotice
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The parameter panel survives the fresh-graph synthesis: a converted CMO3 carries the group tree and the
 * linked pairs back, and a group-less model's parameters land in the root group rather than outside the
 * hierarchy the editor walks.
 */
class Cmo3ParameterPanelConversionTest {
	@Test
	fun nestedGroupsAndLinkedPairsSurviveTheConversion() {
		val faceGroup =
			ParameterNode.Group(
				id = ParameterGroupId("ParamGroupFace"),
				name = "Face",
				initiallyOpen = true,
				children =
					listOf(
						ParameterNode.Param(ParameterId("ParamAngleX")),
						ParameterNode.Param(ParameterId("ParamAngleY")),
						ParameterNode.Group(
							id = ParameterGroupId("ParamGroupEyes"),
							name = "Eyes",
							initiallyOpen = false,
							children = listOf(ParameterNode.Param(ParameterId("ParamEyeOpen"))),
						),
					),
			)
		val puppet =
			parameterPuppet(
				parameterTree = listOf(ParameterNode.Param(ParameterId("ParamBreath")), faceGroup),
				parameterLinks = listOf(ParameterLink(ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))),
			)

		val converted = Cmo3Conversion.freshCmo3(puppet, emptyList(), emptyMap(), "panel", nowMillis = 0L, obfuscateKey = 0)
		val reimported = reimport(converted)

		assertTrue(converted.report.notices.all { notice -> notice is ExportNotice.MissingSourceArt }, "nothing about the panel goes unlowered: ${converted.report.notices}")
		assertEquals(puppet.parameterTree, reimported.parameterTree, "the group tree comes back, nesting, names, and open state included")
		assertEquals(puppet.parameterLinks, reimported.parameterLinks, "the linked pair comes back")
	}

	@Test
	fun aGrouplessModelListsEveryParameterInTheRootGroup() {
		val puppet = parameterPuppet(parameterTree = emptyList(), parameterLinks = emptyList())

		val converted = Cmo3Conversion.freshCmo3(puppet, emptyList(), emptyMap(), "flat", nowMillis = 0L, obfuscateKey = 0)
		val reimported = reimport(converted)
		val flatTree = puppet.parameters.map { parameter -> ParameterNode.Param(parameter.id) }

		assertEquals(flatTree, converted.puppet.parameterTree, "the encoded puppet records the tree it wrote")
		assertEquals(flatTree, reimported.parameterTree, "every parameter is a child of the root group, in list order")
	}

	/**
	 * Writes a conversion out and imports the file it reads back as.
	 *
	 * @param Cmo3Conversion.Result converted The conversion.
	 * @return PuppetModel The re-imported puppet.
	 */
	private fun reimport(converted: Cmo3Conversion.Result): PuppetModel {
		val reread = Cmo3.read(Cmo3.write(converted.model))

		return Cmo3Import.fromModelSource(assertIs<CModelSource>(reread.root))
	}

	/**
	 * A drawable-less puppet with four parameters, the pair to link adjacent in list order.
	 *
	 * @param List parameterTree  The panel tree.
	 * @param List parameterLinks The linked pairs.
	 * @return PuppetModel The puppet.
	 */
	private fun parameterPuppet(parameterTree: List<ParameterNode>, parameterLinks: List<ParameterLink>): PuppetModel =
		PuppetModel(
			parameters =
				listOf("ParamAngleX", "ParamAngleY", "ParamEyeOpen", "ParamBreath").map { id ->
					Parameter(ParameterId(id), name = id.removePrefix("Param"), min = -30f, max = 30f, default = 0f)
				},
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = 64f,
			canvasHeight = 64f,
			worldOriginX = 32f,
			worldOriginZ = -32f,
			runtimeTarget = RuntimeTarget.Cubism53,
			parameterLinks = parameterLinks,
			parameterTree = parameterTree,
		)
}