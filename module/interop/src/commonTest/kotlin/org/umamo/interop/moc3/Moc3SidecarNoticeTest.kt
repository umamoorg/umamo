package org.umamo.interop.moc3

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that a MOC3 export reports a sidecar it carried through verbatim when the sidecar names parameters or
 * parts the moc does not contain - the export face of the dangling-reference rule (docs/format/UMA.md §3.6):
 * the file is written as it was, and the report says which ids now find nothing.  Physics reads and drives
 * parameters, an expression sets them, a motion curves parameters and part opacities, and a pose switches
 * parts; a file that does not parse raises nothing and is still carried.
 */
class Moc3SidecarNoticeTest {
	/**
	 * A physics3 file with one setting reading [inputId] and driving [outputId].
	 *
	 * @param String inputId  The parameter the setting reads.
	 * @param String outputId The parameter it drives.
	 * @return String The file's text.
	 */
	private fun physics3(inputId: String, outputId: String): String =
		"""
		{
			"Version": 3,
			"Meta": {
				"PhysicsSettingCount": 1, "TotalInputCount": 1, "TotalOutputCount": 1, "VertexCount": 2,
				"EffectiveForces": { "Gravity": { "X": 0, "Y": -1 }, "Wind": { "X": 0, "Y": 0 } },
				"PhysicsDictionary": [{ "Id": "PhysicsSetting1", "Name": "Hair" }]
			},
			"PhysicsSettings": [{
				"Id": "PhysicsSetting1",
				"Input": [{ "Source": { "Target": "Parameter", "Id": "$inputId" }, "Weight": 100, "Type": "X", "Reflect": false }],
				"Output": [{ "Destination": { "Target": "Parameter", "Id": "$outputId" }, "VertexIndex": 1, "Scale": 1, "Weight": 100, "Type": "Angle", "Reflect": false }],
				"Vertices": [
					{ "Position": { "X": 0, "Y": 0 }, "Mobility": 1, "Delay": 1, "Acceleration": 1, "Radius": 0 },
					{ "Position": { "X": 0, "Y": 10 }, "Mobility": 0.9, "Delay": 0.9, "Acceleration": 1, "Radius": 10 }
				],
				"Normalization": {
					"Position": { "Minimum": -10, "Default": 0, "Maximum": 10 },
					"Angle": { "Minimum": -10, "Default": 0, "Maximum": 10 }
				}
			}]
		}
		""".trimIndent()

	/**
	 * An expression setting [ids].
	 *
	 * @param List ids The parameters it sets.
	 * @return String The file's text.
	 */
	private fun exp3(ids: List<String>): String =
		"""
		{
			"Type": "Live2D Expression",
			"FadeInTime": 0.5,
			"FadeOutTime": 0.5,
			"Parameters": [${ids.joinToString(", ") { id -> "{ \"Id\": \"$id\", \"Value\": 1, \"Blend\": \"Add\" }" }}]
		}
		""".trimIndent()

	/**
	 * A motion with one curve per (target, id) pair in [curves], plus a model-level opacity curve.
	 *
	 * @param List curves The curves' targets and ids.
	 * @return String The file's text.
	 */
	private fun motion3(curves: List<Pair<String, String>>): String =
		"""
		{
			"Version": 3,
			"Meta": { "Duration": 1, "Fps": 30, "Loop": true, "AreBeziersRestricted": true, "CurveCount": ${curves.size + 1}, "TotalSegmentCount": ${curves.size + 1}, "TotalPointCount": ${2 * (curves.size + 1)}, "UserDataCount": 0, "TotalUserDataSize": 0 },
			"Curves": [
				${curves.joinToString(",\n\t\t\t") { (target, id) -> "{ \"Target\": \"$target\", \"Id\": \"$id\", \"Segments\": [0, 0, 0, 1, 0] }" }},
				{ "Target": "Model", "Id": "Opacity", "Segments": [0, 1, 0, 1, 1] }
			]
		}
		""".trimIndent()

	/**
	 * A pose of one group holding [id] with [links] following it.
	 *
	 * @param String id    The group's part.
	 * @param List   links The parts linked to it.
	 * @return String The file's text.
	 */
	private fun pose3(id: String, links: List<String>): String =
		"""
		{
			"Type": "Live2D Pose",
			"FadeInTime": 0.5,
			"Groups": [[{ "Id": "$id", "Link": [${links.joinToString(", ") { link -> "\"$link\"" }}] }]]
		}
		""".trimIndent()

	/** A rig of one parameter, `ParamAngleX`, and one part, `PartArm`. */
	private val puppet =
		PuppetModel(
			parameters = listOf(Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f)),
			parts = listOf(Part(PartId("PartArm"), "Arm", children = emptyList())),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = listOf(OrgChild.Part(PartId("PartArm"))),
			rootPartId = null,
		)

	/**
	 * The sidecar reasons of a bundle of [sidecar] over the rig.
	 *
	 * @param Moc3Sidecars.PassThroughSidecar sidecar The sidecar to carry.
	 * @return List<ExportNoticeReason> The reasons, in report order.
	 */
	private fun sidecarReasonsOf(sidecar: Moc3Sidecars.PassThroughSidecar): List<ExportNoticeReason> {
		val bundle = Moc3Sidecars.bundle(puppet, basename = "rig", version = MocVersion.V50, pages = emptyList(), sidecars = listOf(sidecar))
		assertEquals(sidecar.text, bundle.files.single { file -> file.name == sidecar.fileName }.bytes.decodeToString(), "the sidecar is written as it was")
		return bundle.report.notices.filterIsInstance<ExportNotice.UnsupportedChange>().map { notice -> notice.reason }.filter { reason -> reason is ExportNoticeReason.SidecarNamesUnwrittenParameters || reason is ExportNoticeReason.SidecarNamesUnwrittenParts }
	}

	@Test
	fun aPhysicsSidecarNamingAParameterTheMocLacksIsReported() {
		val sidecar = Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Physics, "rig.physics3.json", physics3("ParamAngleX", "ParamHairGone"))
		val bundle = Moc3Sidecars.bundle(puppet, basename = "rig", version = MocVersion.V50, pages = emptyList(), sidecars = listOf(sidecar))

		val reported = bundle.report.notices.filterIsInstance<ExportNotice.UnsupportedChange>().map { notice -> notice.reason }.filterIsInstance<ExportNoticeReason.SidecarNamesUnwrittenParameters>()
		assertEquals(listOf(ExportNoticeReason.SidecarNamesUnwrittenParameters("rig.physics3.json", listOf("ParamHairGone"))), reported, "only the id the moc lacks is named")
		assertEquals(sidecar.text, bundle.files.single { file -> file.name == "rig.physics3.json" }.bytes.decodeToString(), "and the sidecar is still written as it was")
	}

	@Test
	fun aPhysicsSidecarNamingOnlyWrittenParametersIsSilent() {
		val sidecar = Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Physics, "rig.physics3.json", physics3("ParamAngleX", "ParamAngleX"))
		val bundle = Moc3Sidecars.bundle(puppet, basename = "rig", version = MocVersion.V50, pages = emptyList(), sidecars = listOf(sidecar))
		assertTrue(bundle.report.notices.none { notice -> notice is ExportNotice.UnsupportedChange && notice.reason is ExportNoticeReason.SidecarNamesUnwrittenParameters })
	}

	@Test
	fun anExpressionSettingAParameterTheMocLacksIsReported() {
		val sidecar = Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Expression, "smile.exp3.json", exp3(listOf("ParamAngleX", "ParamSmileGone")))
		assertEquals(listOf<ExportNoticeReason>(ExportNoticeReason.SidecarNamesUnwrittenParameters("smile.exp3.json", listOf("ParamSmileGone"))), sidecarReasonsOf(sidecar))
	}

	@Test
	fun aMotionCurvingAParameterOrAPartTheMocLacksIsReportedPerKind() {
		val sidecar =
			Moc3Sidecars.PassThroughSidecar(
				Moc3Sidecars.SidecarKind.Motion,
				"idle.motion3.json",
				motion3(listOf("Parameter" to "ParamAngleX", "Parameter" to "ParamGone", "PartOpacity" to "PartArm", "PartOpacity" to "PartGone")),
			)
		assertEquals(
			listOf(
				ExportNoticeReason.SidecarNamesUnwrittenParameters("idle.motion3.json", listOf("ParamGone")),
				ExportNoticeReason.SidecarNamesUnwrittenParts("idle.motion3.json", listOf("PartGone")),
			),
			sidecarReasonsOf(sidecar),
			"the model-level opacity curve names no object and is not reported",
		)
	}

	@Test
	fun aPoseSwitchingAPartTheMocLacksIsReported() {
		val sidecar = Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Pose, "rig.pose3.json", pose3("PartArm", listOf("PartGone")))
		assertEquals(listOf<ExportNoticeReason>(ExportNoticeReason.SidecarNamesUnwrittenParts("rig.pose3.json", listOf("PartGone"))), sidecarReasonsOf(sidecar), "a linked part counts like the group's own")
		assertTrue(sidecarReasonsOf(Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Pose, "rig.pose3.json", pose3("PartArm", emptyList()))).isEmpty(), "a pose over written parts is silent")
	}

	@Test
	fun aSidecarThatDoesNotParseIsCarriedWithoutANotice() {
		val sidecar = Moc3Sidecars.PassThroughSidecar(Moc3Sidecars.SidecarKind.Motion, "broken.motion3.json", "{ \"Version\": 3 }")
		assertTrue(sidecarReasonsOf(sidecar).isEmpty(), "its runtime reader has the last word on it")
	}
}