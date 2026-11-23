package org.umamo.interop.moc3

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.KeyformBundleRejection
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every keyform loss the MOC3 lowering takes is reported, whichever object kind takes it.
 *
 * A MOC3 stores one grid per object, so a geometry grid and channel tracks that cannot share one are
 * written with less than the rig holds: one channel as a constant, or the whole object with a single
 * static form.  The export writes a loadable file either way, so the report is the only place the loss
 * shows.
 *
 * Synthetic rather than corpus-backed on purpose: a rig the official editor wrote already holds one grid
 * per object, so it can only bundle.
 */
class Moc3ExportKeyformNoticeTest {
	private val angleX = ParameterId("ParamAngleX")
	private val partId = PartId("Part1")
	private val drawableSubject = "ArtMesh1"
	private val warpSubject = "Warp1"
	private val rotationSubject = "Rotation1"

	/** A grid keyed on angleX at [keys], holding one form per key. */
	private fun <TForm> gridOn(keys: FloatArray, formAt: (Int) -> TForm): KeyformGrid<TForm> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, keys)),
			keys.indices.map { keyIndex -> KeyformCell(intArrayOf(keyIndex), formAt(keyIndex)) },
		)

	/** An unkeyed grid: no axes, one form. */
	private fun <TForm> unkeyedGrid(form: TForm): KeyformGrid<TForm> = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), form)))

	/** An opacity track keyed on angleX at [keys]. */
	private fun opacityOn(vararg keys: Float): ChannelGrids =
		ChannelGrids(
			mapOf(FormChannel.OPACITY to gridOn<ChannelValue>(keys) { keyIndex -> ChannelValue.Scalar(1f - 0.25f * keyIndex) }),
		)

	/** A one-triangle drawable whose mesh is keyed on angleX at [geometryKeys]. */
	private fun drawable(geometryKeys: FloatArray, channelGrids: ChannelGrids = ChannelGrids.Empty): Drawable =
		Drawable(
			id = DrawableId(drawableSubject),
			name = drawableSubject,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			// Bound to a page, so the only notices a rig here can raise are about its keyforms.
			texturePage = 0,
			mesh =
				DrawableMesh.withLocalEqualToCanvas(
					positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
					uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
					indices = intArrayOf(0, 1, 2),
				),
			geometryGrid = gridOn(geometryKeys) { keyIndex -> MeshDeltaForm(FloatArray(6) { keyIndex.toFloat() }) },
			channelGrids = channelGrids,
		)

	/** The four control points of a 1 x 1 lattice, distinct per [keyIndex]. */
	private fun lattice(keyIndex: Int): WarpLatticeForm = WarpLatticeForm(FloatArray(8) { keyIndex.toFloat() })

	/** A pivot transform, distinct per [keyIndex]. */
	private fun pivot(keyIndex: Int): RotationPivotForm =
		RotationPivotForm(originX = 10f, originY = 20f, angle = 15f * keyIndex, scale = 1f)

	private fun warp(geometryGrid: KeyformGrid<WarpLatticeForm>?, channelGrids: ChannelGrids = ChannelGrids.Empty): Deformer =
		Deformer.Warp(
			id = DeformerId(warpSubject),
			name = warpSubject,
			parent = null,
			partId = partId,
			rows = 1,
			columns = 1,
			isQuadTransform = false,
			geometryGrid = geometryGrid,
			channelGrids = channelGrids,
		)

	private fun rotation(geometryGrid: KeyformGrid<RotationPivotForm>?, channelGrids: ChannelGrids = ChannelGrids.Empty): Deformer =
		Deformer.Rotation(
			id = DeformerId(rotationSubject),
			name = rotationSubject,
			parent = null,
			partId = partId,
			baseAngle = 0f,
			geometryGrid = geometryGrid,
			channelGrids = channelGrids,
		)

	/** A rig of one part holding [drawable], with [deformers] beside it; angleX runs -1..1. */
	private fun puppet(drawable: Drawable, deformers: List<Deformer> = emptyList()): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(angleX, angleX.raw, min = -1f, max = 1f, default = 0f)),
			parts = listOf(Part(id = partId, name = "Part1", children = listOf(OrgChild.Drawable(drawable.id)))),
			deformers = deformers,
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Part(partId)),
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 100f,
		)

	/** The notices exporting [deformers] beside a fully bundling drawable raises. */
	private fun noticesFor(vararg deformers: Deformer): List<ExportNotice> =
		Moc3Export.toMocDocument(puppet(drawable(floatArrayOf(0f, 1f)), deformers.toList()), MocVersion.V50).report.notices

	private fun deformerNotice(subject: String, reason: ExportNoticeReason): ExportNotice =
		ExportNotice.UnsupportedChange(ExportEntityCategory.Deformer, subject, reason)

	/** Geometry and channels keyed alike share one grid, keyed or not, and nothing is reported. */
	@Test
	fun aRigThatBundlesReportsNothing() {
		val keys = floatArrayOf(0f, 1f)
		val lowered =
			Moc3Export.toMocDocument(
				puppet(
					drawable(keys, opacityOn(0f, 1f)),
					listOf(warp(gridOn(keys, ::lattice), opacityOn(0f, 1f)), rotation(unkeyedGrid(pivot(0)))),
				),
				MocVersion.V50,
			)

		assertEquals(emptyList(), lowered.report.notices)
		assertEquals(2, lowered.document.artMeshes.single().keyforms.size, "the drawable kept both keyforms")
	}

	/**
	 * A key pair closer than the evaluator's snap tolerance is written as the rig holds it when the mesh
	 * and its channel are keyed alike, which is how an imported rig carries one.
	 */
	@Test
	fun aNearKeyPairIsWrittenAsAuthored() {
		val lowered =
			Moc3Export.toMocDocument(
				puppet(drawable(floatArrayOf(0f, 0.999f, 1f), opacityOn(0f, 0.999f, 1f))),
				MocVersion.V50,
			)

		assertEquals(emptyList(), lowered.report.notices)

		val artMesh = lowered.document.artMeshes.single()
		val binding = lowered.document.bindings.first { candidate -> candidate.index == artMesh.keyformBindingIndex }
		assertEquals(listOf(0f, 0.999f, 1f), binding.axes.single().keyPositions.toList(), "the binding carries both keys of the pair")
		assertEquals(3, artMesh.keyforms.size, "the mesh kept every keyform")
	}

	/**
	 * A mesh whose keyforms will not bundle is written with one static form, and says so.  The opacity
	 * track here reaches below the mesh's own keys, where the mesh has no shape to hold.
	 */
	@Test
	fun aDrawableWhoseKeyformsWillNotBundleIsReported() {
		val lowered =
			Moc3Export.toMocDocument(puppet(drawable(floatArrayOf(0f, 1f), opacityOn(-1f, 0f, 1f))), MocVersion.V50)

		assertEquals(
			listOf<ExportNotice>(
				ExportNotice.UnsupportedChange(
					ExportEntityCategory.Drawable,
					drawableSubject,
					ExportNoticeReason.KeyformsDemotedToStatic(KeyformBundleRejection.KeysOutsideGeometrySpan),
				),
			),
			lowered.report.notices,
		)

		val artMesh = lowered.document.artMeshes.single()
		assertEquals(0, artMesh.keyformBindingIndex, "the mesh points at the static binding")
		assertEquals(1, artMesh.keyforms.size, "the mesh was written with one form")
	}

	/** A channel keyed over less than its mesh's span is written as a constant; the mesh stays keyed. */
	@Test
	fun aDemotedChannelIsReported() {
		val lowered =
			Moc3Export.toMocDocument(puppet(drawable(floatArrayOf(-1f, 0f, 1f), opacityOn(0f, 1f))), MocVersion.V50)

		assertEquals(
			listOf<ExportNotice>(
				ExportNotice.UnsupportedChange(
					ExportEntityCategory.Drawable,
					drawableSubject,
					ExportNoticeReason.ChannelDemotedToStatic(FormChannel.OPACITY),
				),
			),
			lowered.report.notices,
		)
		assertEquals(3, lowered.document.artMeshes.single().keyforms.size, "the mesh kept its keyforms")
	}

	/**
	 * A warp whose keyforms will not bundle names the bundling rule it broke, ahead of the notice for the
	 * empty lattice written in its place.
	 */
	@Test
	fun aWarpWhoseKeyformsWillNotBundleReportsTheRule() {
		assertEquals(
			listOf(
				deformerNotice(
					warpSubject,
					ExportNoticeReason.KeyformsDemotedToStatic(KeyformBundleRejection.KeysOutsideGeometrySpan),
				),
				deformerNotice(warpSubject, ExportNoticeReason.WarpDeformerHasNoLattice),
			),
			noticesFor(warp(gridOn(floatArrayOf(0f, 1f), ::lattice), opacityOn(-1f, 0f, 1f))),
		)
	}

	/** A warp with no lattice at all is reported whether or not a channel of it is keyed. */
	@Test
	fun aWarpWithNoLatticeIsReported() {
		assertEquals(
			listOf(deformerNotice(warpSubject, ExportNoticeReason.WarpDeformerHasNoLattice)),
			noticesFor(warp(geometryGrid = null)),
			"nothing keyed",
		)
		assertEquals(
			listOf(
				deformerNotice(
					warpSubject,
					ExportNoticeReason.KeyformsDemotedToStatic(KeyformBundleRejection.ChannelKeysWithoutGeometry),
				),
				deformerNotice(warpSubject, ExportNoticeReason.WarpDeformerHasNoLattice),
			),
			noticesFor(warp(geometryGrid = null, channelGrids = opacityOn(0f, 1f))),
			"opacity keyed",
		)
	}

	/** A rotation reports the same two cases as a warp, with the identity transform as its stand-in. */
	@Test
	fun aRotationWithNoTransformToWriteIsReported() {
		assertEquals(
			listOf(
				deformerNotice(
					rotationSubject,
					ExportNoticeReason.KeyformsDemotedToStatic(KeyformBundleRejection.KeysOutsideGeometrySpan),
				),
				deformerNotice(rotationSubject, ExportNoticeReason.RotationDeformerHasNoPivot),
			),
			noticesFor(rotation(gridOn(floatArrayOf(0f, 1f), ::pivot), opacityOn(-1f, 0f, 1f))),
			"keyforms that will not bundle",
		)
		assertEquals(
			listOf(deformerNotice(rotationSubject, ExportNoticeReason.RotationDeformerHasNoPivot)),
			noticesFor(rotation(geometryGrid = null)),
			"no pivot grid",
		)
	}
}