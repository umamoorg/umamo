package org.umamo.ui.document

import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An UNKEYED drawable under a deformer survives the export, through the injected space seam.
 *
 * This is the one geometry case a moc export cannot solve on its own.  A keyed drawable stores
 * parent-local values that base + delta reconstructs; an unkeyed one has only its rest mesh, and the
 * editor keeps that in CANVAS space - so writing it verbatim under a warp would store canvas
 * coordinates where the runtime expects lattice UVs, off by the whole deformer transform.  Inverting
 * the chain needs `:render`'s warp inverse, which `:interop` cannot reach, hence the seam.
 *
 * Both halves are pinned: with the seam the drawable is written and comes back where it started, and
 * WITHOUT it the drawable is dropped with a notice rather than written wrong.  The second half is the
 * one that would rot quietly - a future refactor that silently wrote the canvas-space mesh would look
 * fine in every count-based test.
 *
 * Gated on `-Dmoc3.sample`; self-skips without it.
 */
class Moc3UnkeyedDrawableExportTest {
	private val sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }

	/**
	 * The sample rig with one deformer-parented drawable's keyform grid removed.
	 *
	 * @return Pair The rig and the un-keyed drawable, or null when the sample is absent or has none.
	 */
	private fun rigWithAnUnkeyedChild(): Pair<PuppetModel, Drawable>? {
		val sampleFile = sample ?: return null
		val directory = sampleFile.parentFile
		val loaded =
			assertIs<DocumentLoad.Loaded>(
				buildMoc3Document(sampleFile.path, sampleFile.name, sampleFile.readBytes()) { reference ->
					File(directory, reference).takeIf { it.isFile }?.readBytes()
				},
			).document as Moc3Document
		// A warp child specifically: the rotation inverse is closed-form, so the warp's damped Newton is
		// the half worth exercising.
		val warpIds = loaded.puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().map { it.id }
		val target =
			loaded.puppet.drawables.firstOrNull { drawable ->
				drawable.parentDeformerId in warpIds && drawable.geometryGrid != null && drawable.mesh != null
			} ?: return null
		val unkeyed = target.copy(geometryGrid = null)
		val rig =
			loaded.puppet.copy(
				drawables = loaded.puppet.drawables.map { drawable -> if (drawable.id == target.id) unkeyed else drawable },
			)
		return rig to unkeyed
	}

	@Test
	fun theSeamKeepsTheDrawableAndItsPlace() {
		val (rig, unkeyed) = rigWithAnUnkeyedChild() ?: return
		val lowered = Moc3Export.toMocDocument(rig, canvasToParentSpace = canvasToParentSpaceFor(rig))
		assertTrue(
			lowered.document.artMeshes.any { mesh -> mesh.id == unkeyed.id.raw },
			"the unkeyed drawable must be written, not dropped",
		)
		assertTrue(
			lowered.report.notices.none { notice ->
				notice is ExportNotice.UnsupportedChange && notice.subject == unkeyed.id.raw
			},
			"nothing about the drawable should be unsupported once the seam is supplied",
		)

		// Re-import and put the rest meshes back into canvas space - the same pass the loader runs - so
		// the comparison is against the geometry the editor started with.
		val reimported = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(lowered.document, displayInfo = null))
		val original = assertNotNull(unkeyed.mesh).positions
		val roundTripped = assertNotNull(reimported.drawables.first { it.id == unkeyed.id }.mesh).positions
		assertEquals(original.size, roundTripped.size, "vertex count")
		var worstIndex = 0
		var worstError = 0f
		for (coordinate in original.indices) {
			// RELATIVE: a canvas coordinate is a model unit and runs into the thousands, so an absolute
			// bound would be a precision test on the magnitude rather than on the inverse.  The warp
			// inverse is iterative, so this is a convergence bound, not an equality - and a wrong SPACE
			// would miss by the whole deformer transform, orders of magnitude past it.
			val error = abs(original[coordinate] - roundTripped[coordinate]) / max(1f, abs(original[coordinate]))
			if (error > worstError) {
				worstError = error
				worstIndex = coordinate
			}
		}
		assertTrue(
			worstError < 1e-4f,
			"the rest mesh moved: worst relative error = $worstError at coordinate $worstIndex " +
				"(${original[worstIndex]} -> ${roundTripped[worstIndex]})",
		)
	}

	@Test
	fun withoutTheSeamTheDrawableIsDroppedRatherThanWrittenWrong() {
		val (rig, unkeyed) = rigWithAnUnkeyedChild() ?: return
		val lowered = Moc3Export.toMocDocument(rig)
		assertTrue(
			lowered.document.artMeshes.none { mesh -> mesh.id == unkeyed.id.raw },
			"without the inverse the drawable must not be written at all",
		)
		assertTrue(
			lowered.report.notices.any { notice ->
				notice is ExportNotice.UnsupportedChange && notice.subject == unkeyed.id.raw
			},
			"and the drop must be reported",
		)
	}
}