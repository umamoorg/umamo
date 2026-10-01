package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsInput
import org.umamo.format.cmo3.model.gen.CPhysicsOutput
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSource
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the CMO3 export face of the dangling-reference rule (docs/plan/uma-format.md D38): deleting a
 * parameter that physics the file retained still drives writes those settings back as they were - Umamo does
 * not model physics - and reports them by name, so the rigger learns the exported file's physics now names a
 * parameter it lacks.
 *
 * Corpus-gated on `cmo3.sample` (EricaTamamo carries 44 physics settings); self-skips without it.
 */
class Cmo3PhysicsDeletedParameterExportTest {
	@Test
	fun deletingAPhysicsDrivenParameterReportsTheSettings() {
		val sample = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { file -> file.isFile }
		if (sample == null) {
			println("cmo3.sample not present; skipping the deleted-parameter physics notice")
			return
		}
		val cmo3 = Cmo3.read(sample)
		val root = cmo3.root as CModelSource
		// CMO3: CModelSource field physicsSettingsSourceSet -> CPhysicsSettingsSourceSet field _sourceCubismPhysics.
		val settings = Cmo3Import.elementsOf((root.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet)?._sourceCubismPhysics).filterIsInstance<CPhysicsSettingsSource>()
		val driven = assertNotNull(settings.firstOrNull { setting -> Cmo3Import.elementsOf(setting.outputs).isNotEmpty() }, "${sample.name} carries physics that drives a parameter")
		// CMO3: CPhysicsOutput field destination - the driven parameter source's own guid.
		val destination = Cmo3Import.elementsOf(driven.outputs).filterIsInstance<CPhysicsOutput>().first().destination
		val parameterSource =
			Cmo3Import.elementsOf((root.parameterSourceSet as CParameterSourceSet)._sources).filterIsInstance<CParameterSource>().first { source -> source.guid === destination }
		val deletedId = ParameterId(assertNotNull(Cmo3Import.idStrOf(parameterSource.id)))
		val expectedNames =
			settings.filter { setting ->
				Cmo3Import.elementsOf(setting.inputs).any { input -> (input as? CPhysicsInput)?.source === destination } ||
					Cmo3Import.elementsOf(setting.outputs).any { output -> (output as? CPhysicsOutput)?.destination === destination }
			}.map { setting -> setting.name?.takeIf { name -> name.isNotBlank() } ?: Cmo3Import.idStrOf(setting.id).orEmpty() }

		val imported = Cmo3Import.fromModelSource(root)
		val edited = imported.copy(parameters = imported.parameters.filterNot { parameter -> parameter.id == deletedId })
		val report = Cmo3Export.apply(edited, Cmo3.read(cmo3.archive))

		val reported =
			report.notices.filterIsInstance<ExportNotice.UnsupportedChange>().filter { notice -> notice.subject == deletedId.raw }.map { notice -> notice.reason }.filterIsInstance<ExportNoticeReason.PhysicsNamesDeletedParameter>()
		assertEquals(listOf(ExportNoticeReason.PhysicsNamesDeletedParameter(expectedNames)), reported, "the settings naming ${deletedId.raw} are reported by name")
		assertTrue(expectedNames.isNotEmpty())
	}
}