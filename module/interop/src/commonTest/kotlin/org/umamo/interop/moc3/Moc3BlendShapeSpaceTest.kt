package org.umamo.interop.moc3

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A blend-shape owner's positional deltas are un-converted from ITS OWN parent's space.
 *
 * The blend pass is the third caller of the context's `spaceOfParent`, alongside the art-mesh and
 * deformer lowerings, and the three must agree.  Root space is the only one carrying the px→model
 * divide, so resolving a parented owner as root scales its whole blend shape by the pixels-per-unit -
 * a rig-scale error rather than a rounding one, and invisible in a document whose ppu happens to be 1.
 * This rig therefore uses a ppu of 4 so the two arms cannot produce the same numbers.
 */
class Moc3BlendShapeSpaceTest {
	private val morphParameter = ParameterId("ParamMorph")
	private val poseParameter = ParameterId("ParamPose")
	private val pixelsPerUnit = 4f

	/** The authored delta, chosen so dividing by [pixelsPerUnit] lands on exact binary fractions. */
	private val authoredDeltas = floatArrayOf(8f, -16f, 32f, 64f)

	/**
	 * A blend-shape binding carrying [authoredDeltas] at its non-neutral key.
	 *
	 * @return BlendShapeBinding The binding to hang on an owner.
	 */
	private fun morphBinding(): BlendShapeBinding<MeshForm> =
		BlendShapeBinding(
			parameterId = morphParameter,
			keys = floatArrayOf(0f, 1f),
			neutralIndex = 0,
			forms = listOf(null, MeshForm(authoredDeltas.copyOf())),
		)

	/**
	 * A drawable with a two-vertex mesh and one blend shape, under [parentDeformerId].
	 *
	 * The mesh is present because the pass sizes its delta row from the vertex count.  The geometry grid
	 * is all zeros rather than absent: a parented drawable with no grid has no parent-local form to
	 * write and export eligibility drops it, and zeros keep the subtracted default-pose reference at
	 * zero so the stored row is the authored delta converted, with nothing else folded in.
	 *
	 * @param String      id               The drawable id.
	 * @param DeformerId? parentDeformerId The owning deformer, null at the root.
	 * @return Drawable The drawable.
	 */
	private fun blendDrawable(id: String, parentDeformerId: DeformerId?): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = parentDeformerId,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh =
				DrawableMesh(
					positions = FloatArray(authoredDeltas.size),
					uvs = FloatArray(authoredDeltas.size),
					indices = intArrayOf(0, 1, 0),
				),
			geometryGrid =
				KeyformGrid(
					axes = listOf(KeyformAxis(poseParameter, floatArrayOf(0f, 1f))),
					cells =
						listOf(
							KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(authoredDeltas.size))),
							KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(authoredDeltas.size))),
						),
				),
			blendShapes = listOf(morphBinding()),
		)

	/**
	 * The stored art-mesh delta row of the named drawable's single blend shape at its non-neutral key.
	 *
	 * @param PuppetModel puppet The rig to export.
	 * @param String      id     The owning drawable's id.
	 * @return FloatArray The stored vertex-position row.
	 */
	private fun storedMeshRow(puppet: PuppetModel, id: String): FloatArray {
		val document = Moc3Export.toMocDocument(puppet, MocVersion.V50).document
		val drawableIndex = document.artMeshes.indexOfFirst { artMesh -> artMesh.id == id }
		val record =
			document.blendShapes.single { blendShape ->
				blendShape.target == BlendShapeTarget.ART_MESH && blendShape.targetIndex == drawableIndex
			}
		return (record.keyforms[1] as BlendShapeKeyform.Mesh).form.vertexPositions
	}

	@Test
	fun aParentedOwnerStoresVerbatimWhileARootOwnerTakesThePpuDivide() {
		val warp =
			Deformer.Warp(
				id = DeformerId("Warp1"),
				name = "Warp1",
				parent = null,
				partId = PartId("Part1"),
				rows = 1,
				columns = 1,
				isQuadTransform = false,
				geometryGrid = null,
			)
		val underWarp = blendDrawable("UnderWarp", warp.id)
		val atRoot = blendDrawable("AtRoot", null)
		val part =
			Part(
				id = PartId("Part1"),
				name = "Part1",
				children = listOf(OrgChild.Drawable(underWarp.id), OrgChild.Drawable(atRoot.id)),
			)
		val puppet =
			PuppetModel(
				parameters =
					listOf(
						Parameter(morphParameter, "Morph", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE),
						Parameter(poseParameter, "Pose", 0f, 1f, 0f),
					),
				parts = listOf(part),
				deformers = listOf(warp),
				drawables = listOf(underWarp, atRoot),
				rootChildren = listOf(OrgChild.Part(part.id)),
				rootPartId = null,
				canvasWidth = 100f,
				canvasHeight = 100f,
				pixelsPerUnit = pixelsPerUnit,
			)

		// A warp parent's lattice is the same in both conventions, so the authored delta survives as-is.
		assertEquals(
			authoredDeltas.toList(),
			storedMeshRow(puppet, "UnderWarp").toList(),
			"a warp-parented blend shape must store its deltas verbatim",
		)
		// Root space is the one that carries the affine, so the same delta divides by the ppu.
		assertEquals(
			authoredDeltas.map { delta -> delta / pixelsPerUnit },
			storedMeshRow(puppet, "AtRoot").toList(),
			"a root blend shape must store its deltas in model units",
		)
	}
}
