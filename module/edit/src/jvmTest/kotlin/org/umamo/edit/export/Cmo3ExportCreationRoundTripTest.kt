package org.umamo.edit.export

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.ExportReport
import org.umamo.interop.cmo3.Cmo3Export
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Created-path gate: session-created parts, deformers, and glues synthesize identity shells
 * and lower through the ordinary Changed path, surviving an export/re-import with no notices and
 * no residual diff.  (Created parameters, groups, and duplicated drawables are covered by
 * Cmo3ExportStructureRoundTripTest; the fresh-graph drawable binding path is covered by the
 * MOC3-conversion round trip in :interop.)
 */
class Cmo3ExportCreationRoundTripTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private class RoundTrip(
		val edited: PuppetModel,
		val reimported: PuppetModel,
		val report: ExportReport,
	)

	private fun roundTrip(file: File, edit: (PuppetModel) -> PuppetModel): RoundTrip {
		val cmo3 = Cmo3.read(file.readBytes())
		val modelSource = cmo3.root as? CModelSource ?: error("${file.name}: root is not a CModelSource")
		val edited = edit(Cmo3Import.fromModelSource(modelSource))
		val report = Cmo3Export.apply(edited, cmo3)
		val reimportedSource =
			Cmo3.read(Cmo3.write(cmo3)).root as? CModelSource ?: error("re-read root is not a CModelSource")
		return RoundTrip(edited, Cmo3Import.fromModelSource(reimportedSource), report)
	}

	private fun assertLossless(result: RoundTrip, label: String) {
		assertTrue(result.report.isEmpty, "$label: expected no notices, got ${result.report.notices}")
		val residual = diffPuppetModels(result.reimported, result.edited)
		assertTrue(residual.isEmpty, "$label: edits lost through export/import: $residual")
	}

	private fun skipMessageOrNull(): File? {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping creation round-trip test")
		}
		return file
	}

	@Test
	fun createdPartRoundTrips() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				val fresh =
					Part(
						id = PartId("UmamoCreatedPart"),
						name = "Created Part",
						children = emptyList(),
						drawOrder = 480,
					)
				puppet.copy(
					parts = puppet.parts + fresh,
					rootChildren = puppet.rootChildren + OrgChild.Part(fresh.id),
				)
			}
		assertLossless(result, "created part")
	}

	@Test
	fun createdWarpDeformerRoundTrips() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				// Clone the lattice shape from an existing warp so the control-point count matches
				// the dimensions the official format expects for that grid size.
				val template = puppet.deformers.filterIsInstance<Deformer.Warp>().first { it.geometryGrid != null }
				val templateForm = template.geometryGrid!!.cells.first().form
				val axisParameter = puppet.parameters.first { it.max - it.min >= 1f }
				val nudged = templateForm.controlPoints.copyOf().also { points -> points[0] += 5f }
				val fresh =
					Deformer.Warp(
						id = DeformerId("UmamoCreatedWarp"),
						name = "Created Warp",
						parent = null,
						partId = puppet.parts.first().id,
						rows = template.rows,
						columns = template.columns,
						isQuadTransform = template.isQuadTransform,
						geometryGrid =
							KeyformGrid(
								listOf(
									KeyformAxis(
										axisParameter.id,
										floatArrayOf(axisParameter.min, axisParameter.max),
									),
								),
								listOf(
									KeyformCell(intArrayOf(0), WarpLatticeForm(templateForm.controlPoints.copyOf())),
									KeyformCell(intArrayOf(1), WarpLatticeForm(nudged)),
								),
							),
					)
				puppet.copy(deformers = puppet.deformers + fresh)
			}
		assertLossless(result, "created warp deformer")
	}

	@Test
	fun createdRotationDeformerRoundTrips() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				val axisParameter = puppet.parameters.first { it.max - it.min >= 1f }
				val fresh =
					Deformer.Rotation(
						id = DeformerId("UmamoCreatedRotation"),
						name = "Created Rotation",
						parent = null,
						partId = puppet.parts.first().id,
						baseAngle = 15f,
						geometryGrid =
							KeyformGrid(
								listOf(
									KeyformAxis(
										axisParameter.id,
										floatArrayOf(axisParameter.min, axisParameter.max),
									),
								),
								listOf(
									KeyformCell(intArrayOf(0), RotationPivotForm(100f, 200f, 0f, 1f)),
									KeyformCell(intArrayOf(1), RotationPivotForm(100f, 200f, 30f, 1.25f)),
								),
							),
					)
				puppet.copy(deformers = puppet.deformers + fresh)
			}
		assertLossless(result, "created rotation deformer")
	}

	@Test
	fun createdGlueRoundTrips() {
		val file = skipMessageOrNull() ?: return
		val result =
			roundTrip(file) { puppet ->
				// Weld the first two drawables that have enough vertices; the static intensity stays
				// at the 1f default (the only static CMO3 can express without a form).
				val candidates = puppet.drawables.filter { drawable -> (drawable.mesh?.positions?.size ?: 0) >= 6 }
				val meshA = candidates[0]
				val meshB = candidates[1]
				val fresh =
					Glue(
						meshA = meshA.id,
						meshB = meshB.id,
						pairs =
							listOf(
								GluePair(indexA = 0, indexB = 0, weightA = 0.5f, weightB = 0.5f),
								GluePair(indexA = 1, indexB = 1, weightA = 0.25f, weightB = 0.75f),
							),
					)
				puppet.copy(glues = puppet.glues + fresh)
			}
		assertLossless(result, "created glue")
	}
}
