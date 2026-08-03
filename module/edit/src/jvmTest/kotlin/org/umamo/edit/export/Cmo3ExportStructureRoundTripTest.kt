package org.umamo.edit.export

import org.umamo.edit.MergeTarget
import org.umamo.edit.MeshTopologyOps
import org.umamo.edit.withDeformerDeleted
import org.umamo.edit.withDrawableDeleted
import org.umamo.edit.withDrawableDuplicated
import org.umamo.edit.withMeshTopologyEdit
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterDeleted
import org.umamo.edit.withParameterGroupCreated
import org.umamo.edit.withPartDeleted
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.Cmo3ExportReport
import org.umamo.interop.DrawableField
import org.umamo.interop.EntityDiff
import org.umamo.interop.cmo3.Cmo3Export
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The structural-synthesis gate: created and deleted parameters/groups, duplicated and deleted
 * drawables, deleted parts/deformers, and mesh topology edits reconcile onto the CMO3 graph and
 * survive an export/re-import.  A duplicated drawable's textureSourceId is editor-only state (the
 * re-imported file binds the atlas by the drawable's own id), and rebuilt/duplicated forms follow
 * the delta-vs-absolute bounded-ULP tier, so those two residues are tolerated where noted.
 */
class Cmo3ExportStructureRoundTripTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private class RoundTrip(
		val edited: PuppetModel,
		val reimported: PuppetModel,
		val report: Cmo3ExportReport,
	)

	private fun roundTrip(file: File, edit: (PuppetModel) -> PuppetModel): RoundTrip {
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val edited = edit(Cmo3Import.fromModelSource(modelSource))
		val report = Cmo3Export.apply(edited, cmo3)
		val reimportedSource = Cmo3.read(Cmo3.write(cmo3)).root as? CModelSource ?: error("re-read root is not a CModelSource")
		return RoundTrip(edited, Cmo3Import.fromModelSource(reimportedSource), report)
	}

	private fun assertLossless(result: RoundTrip, label: String) {
		assertTrue(result.report.isEmpty, "$label: expected no notices, got ${result.report.notices}")
		val residual = diffPuppetModels(result.reimported, result.edited)
		assertTrue(residual.isEmpty, "$label: edits lost through export/import: $residual")
	}

	/**
	 * Asserts a clean round trip except the tolerated per-drawable residues: TEXTURE_SOURCE
	 * (editor-only state) and bounded-ULP GEOMETRY/BLEND_SHAPES drift on [drawableId].
	 */
	private fun assertLosslessExceptDrawableResidue(result: RoundTrip, drawableId: DrawableId, label: String) {
		val residual = diffPuppetModels(result.reimported, result.edited)
		if (residual.isEmpty) {
			return
		}
		val tolerated = setOf(DrawableField.TEXTURE_SOURCE, DrawableField.GEOMETRY, DrawableField.BLEND_SHAPES)
		val onlyToleratedResidue =
			residual.parameters.isEmpty() &&
				residual.parameterGroups.isEmpty() &&
				residual.parts.isEmpty() &&
				residual.deformers.isEmpty() &&
				residual.glues.isEmpty() &&
				residual.document.isEmpty() &&
				residual.drawables.all { entityDiff ->
					entityDiff is EntityDiff.Changed && entityDiff.id == drawableId && tolerated.containsAll(entityDiff.fields)
				}
		assertTrue(onlyToleratedResidue, "$label: unexpected residual $residual")
		val editedGrid = result.edited.drawables.first { it.id == drawableId }.geometryGrid
		val reimportedGrid = result.reimported.drawables.first { it.id == drawableId }.geometryGrid
		if (editedGrid != null && reimportedGrid != null) {
			val reimportedByCoordinate =
				reimportedGrid.cells.associate { cell -> cell.coordinate.toList() to cell.form.positionDeltas }
			var maxComponentDifference = 0f
			for (cell in editedGrid.cells) {
				val reimportedDeltas = reimportedByCoordinate[cell.coordinate.toList()] ?: continue
				for (component in cell.form.positionDeltas.indices) {
					maxComponentDifference =
						maxOf(maxComponentDifference, abs(cell.form.positionDeltas[component] - reimportedDeltas[component]))
				}
			}
			assertTrue(maxComponentDifference < 1e-3f, "$label: geometry drifted by $maxComponentDifference")
		}
	}

	private fun skipMessageOrNull(): File? {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping structure round-trip test")
		}
		return file
	}

	@Test
	fun parameterCreateAndDeleteSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				var model = puppet.withParameterCreated(ParameterId("UmamoTestParam"), "Umamo Test Param")
				// Delete a parameter nothing keys on, so the delete is a pure set-membership change.
				val referenced =
					buildSet {
						for (drawable in model.drawables) {
							drawable.geometryGrid?.axes?.forEach { add(it.parameterId) }
							drawable.channelGrids.gridsByChannel.values.forEach { grid -> grid.axes.forEach { add(it.parameterId) } }
							drawable.blendShapes.forEach { add(it.parameterId) }
						}
						for (deformer in model.deformers) {
							deformer.channelGrids.gridsByChannel.values.forEach { grid -> grid.axes.forEach { add(it.parameterId) } }
						}
						model.parameterLinks.forEach {
							add(it.horizontal)
							add(it.vertical)
						}
					}
				val victim = model.parameters.firstOrNull { it.id !in referenced && it.id.raw != "UmamoTestParam" }
				if (victim != null) {
					model = model.withParameterDeleted(victim.id)
				}
				model
			}
		assertLossless(result, "parameter create/delete")
	}

	@Test
	fun parameterGroupCreateSurvivesRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				puppet.withParameterGroupCreated(ParameterGroupId("UmamoTestGroup"), "Umamo Test Group")
			}
		assertLossless(result, "parameter group create")
	}

	@Test
	fun drawableDuplicateSurvivesRoundTrip() {
		val file = skipMessageOrNull() ?: return
		var duplicateId: DrawableId? = null
		val result =
			roundTrip(file) { puppet ->
				val sourceDrawable = puppet.drawables.first { it.mesh != null }
				val (duplicated, newId) =
					puppet.withDrawableDuplicated(sourceDrawable.id) ?: error("duplicate refused")
				duplicateId = newId
				duplicated
			}
		assertLosslessExceptDrawableResidue(result, duplicateId!!, "drawable duplicate")
	}

	@Test
	fun drawableDeleteSurvivesRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				puppet.withDrawableDeleted(puppet.drawables.first().id)
			}
		assertLossless(result, "drawable delete")
	}

	@Test
	fun partAndDeformerDeleteSurviveRoundTrip() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				// A leaf part (no sub-parts) keeps the cascade small; any deformer exercises the
				// transform-tree removal.
				val leafPart =
					puppet.parts.firstOrNull { part -> part.children.none { it is OrgChild.Part } }
				var model = puppet
				if (leafPart != null) {
					model = model.withPartDeleted(leafPart.id, cascade = true)
				}
				model.deformers.firstOrNull()?.let { deformer ->
					model = model.withDeformerDeleted(deformer.id)
				}
				model
			}
		assertLossless(result, "part/deformer delete")
	}

	@Test
	fun topologyMergeSurvivesRoundTrip() {
		val file = skipMessageOrNull() ?: return
		var editedId: DrawableId? = null
		val result =
			roundTrip(file) { puppet ->
				val drawable =
					puppet.drawables.first { it.mesh != null && it.mesh!!.vertexCount >= 4 && it.mesh!!.indices.isNotEmpty() }
				editedId = drawable.id
				val mesh = drawable.mesh!!
				// Merge the first triangle's first two vertices - a real topology reduction.
				val topologyResult =
					MeshTopologyOps.mergeVertices(mesh, listOf(mesh.indices[0], mesh.indices[1]), MergeTarget.AtCenter)
						?: error("merge refused")
				puppet.withMeshTopologyEdit(drawable.id, topologyResult.edit)
			}
		assertLosslessExceptDrawableResidue(result, editedId!!, "topology merge")
	}
}
