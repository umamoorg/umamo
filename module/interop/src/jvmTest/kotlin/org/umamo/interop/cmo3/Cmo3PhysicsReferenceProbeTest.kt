package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsInput
import org.umamo.format.cmo3.model.gen.CPhysicsOutput
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSource
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Surveys how a CMO3's physics settings name the parameters they read and drive, so the export can tell when
 * a parameter it deletes is still named by retained physics (docs/format/CMO3.md §3).
 *
 * What it tallies: the class of each `CPhysicsInput.source` and `CPhysicsOutput.destination` value, whether
 * that value is the very guid object a `CParameterSource` carries (the writer's shared-reference form) or
 * merely the same uuid, and which containers hold the settings (`_sourceCubismPhysics`,
 * `selectedCubismPhysics`).
 *
 * Corpus-gated: self-skips when `cmo3.probe` names no sample, and fails when the samples it read hold no
 * physics at all, since the survey would then prove nothing.
 */
class Cmo3PhysicsReferenceProbeTest {
	/**
	 * The corpus samples this probe runs over, from the `cmo3.probe` property the `umamo.test-corpus`
	 * plugin forwards (comma-separated, resolved against the repo root).
	 *
	 * @return List The readable samples, empty when the property names none.
	 */
	private fun corpusFiles(): List<File> =
		System.getProperty("cmo3.probe")
			?.split(',')
			?.map { entry -> File(entry.trim()) }
			?.filter { file -> file.isFile }
			?.sortedBy { file -> file.name }
			.orEmpty()

	@Test
	fun surveysHowPhysicsNamesItsParameters() {
		val files = corpusFiles()
		if (files.isEmpty()) {
			println("cmo3.probe lists no readable samples; skipping the physics-reference probe")
			return
		}
		val referenceShapes = sortedMapOf<String, Int>()
		val containerShapes = sortedMapOf<String, Int>()
		var settingCount = 0
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			// CMO3: CModelSource field parameterSourceSet -> CParameterSourceSet field _sources.
			val parameterSources =
				Cmo3Import.elementsOf((root.parameterSourceSet as? CParameterSourceSet)?._sources).filterIsInstance<CParameterSource>()
			val parameterGuids = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
			parameterSources.mapNotNullTo(parameterGuids) { source -> source.guid }
			val parameterUuids = parameterSources.mapNotNull { source -> Cmo3Import.uuidOf(source.guid) }.toSet()
			// CMO3: CModelSource field physicsSettingsSourceSet -> CPhysicsSettingsSourceSet fields
			// _sourceCubismPhysics / selectedCubismPhysics.
			val physicsSet = root.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet ?: continue
			val sourceList = physicsSet._sourceCubismPhysics
			val selected = physicsSet.selectedCubismPhysics
			val containerShape =
				"list=${sourceList?.let { it::class.simpleName }} of ${Cmo3Import.elementsOf(sourceList).map { element -> element?.let { it::class.simpleName } }.toSet()} " +
					"selected=${selected?.let { it::class.simpleName }}"
			containerShapes[containerShape] = (containerShapes[containerShape] ?: 0) + 1
			val settings = Cmo3Import.elementsOf(sourceList).filterIsInstance<CPhysicsSettingsSource>()
			settingCount += settings.size

			fun tally(role: String, reference: Any?) {
				val shape =
					"$role ${reference?.let { it::class.simpleName }} " +
						"sharedGuidObject=${reference != null && reference in parameterGuids} " +
						"uuidResolves=${Cmo3Import.uuidOf(reference) in parameterUuids}"
				referenceShapes[shape] = (referenceShapes[shape] ?: 0) + 1
			}
			for (setting in settings) {
				// CMO3: CPhysicsSettingsSource fields inputs / outputs -> CPhysicsInput field source,
				// CPhysicsOutput field destination.
				Cmo3Import.elementsOf(setting.inputs).filterIsInstance<CPhysicsInput>().forEach { input -> tally("input", input.source) }
				Cmo3Import.elementsOf(setting.outputs).filterIsInstance<CPhysicsOutput>().forEach { output -> tally("output", output.destination) }
			}
			println("physics references: ${file.name} -> ${settings.size} settings")
		}
		println("physics references: $settingCount settings across ${files.size} files")
		containerShapes.forEach { (shape, count) -> println("  $count x container $shape") }
		referenceShapes.forEach { (shape, count) -> println("  $count x $shape") }
		assertTrue(settingCount > 0, "the corpus held no physics setting, so the survey proved nothing")
	}
}