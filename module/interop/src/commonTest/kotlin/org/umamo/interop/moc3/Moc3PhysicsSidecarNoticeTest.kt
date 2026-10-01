package org.umamo.interop.moc3

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that a MOC3 export reports a physics sidecar it carried through verbatim when the sidecar names
 * parameters the moc does not contain - the export face of the dangling-reference rule (docs/plan/
 * uma-format.md D38): the file is written as it was, and the report says which ids now find nothing.
 */
class Moc3PhysicsSidecarNoticeTest {
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

	/** A rig of one parameter, [ParamAngleX]. */
	private val puppet =
		PuppetModel(
			parameters = listOf(Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)

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
}