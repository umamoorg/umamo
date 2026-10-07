package org.umamo.ui.document

import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
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
 * An UNKEYED drawable under a deformer survives the export and comes back where it rests.
 *
 * An unkeyed drawable has only its base, and the base lives in its parent deformer's space - the space a moc
 * stores every keyform in - so the export writes it as it is.  The drawable is pinned under a warp, where a base
 * in any other space would miss by the whole lattice transform: written with nothing about it reported as
 * unsupported, its base read back bit for bit, and its canvas mesh - derived again at load by the rest pass -
 * where it started.
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
		// A warp child specifically: a base in any space but the lattice's would miss by the whole lattice
		// transform there.
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
	fun anUnkeyedWarpChildKeepsItsPlace() {
		val (rig, unkeyed) = rigWithAnUnkeyedChild() ?: return
		val lowered = Moc3Export.toMocDocument(rig)
		assertTrue(
			lowered.document.artMeshes.any { mesh -> mesh.id == unkeyed.id.raw },
			"the unkeyed drawable must be written, not dropped",
		)
		assertTrue(
			lowered.report.notices.none { notice ->
				notice is ExportNotice.UnsupportedChange && notice.subject == unkeyed.id.raw
			},
			"nothing about the drawable is unsupported",
		)

		// Re-import and put the rest meshes back into canvas space - the same pass the loader runs - so
		// the comparison is against the geometry the editor started with.
		val reimported = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(lowered.document, displayInfo = null))
		val reimportedMesh = assertNotNull(reimported.drawables.first { it.id == unkeyed.id }.mesh)
		assertEquals(
			assertNotNull(unkeyed.mesh).localPositions.map(Float::toRawBits),
			reimportedMesh.localPositions.map(Float::toRawBits),
			"the base comes back bit for bit",
		)
		val original = assertNotNull(unkeyed.mesh).positions
		val roundTripped = reimportedMesh.positions
		assertEquals(original.size, roundTripped.size, "vertex count")
		var worstIndex = 0
		var worstError = 0f
		for (coordinate in original.indices) {
			// RELATIVE: a canvas coordinate is a model unit and runs into the thousands, so an absolute
			// bound would be a precision test on the magnitude.  The canvas mesh is evaluated through the
			// lattice at load, so this is a float bound, not an equality - and a base in the wrong SPACE
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
}