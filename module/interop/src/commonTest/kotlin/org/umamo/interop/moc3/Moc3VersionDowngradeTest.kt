package org.umamo.interop.moc3

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportNotice
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.unsupportedFeaturesInUse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The version strip removes exactly what the target cannot load, and says what it removed.
 *
 * Built on one kitchen-sink rig rather than a model per feature, because the interesting property is
 * that the strips COMPOSE: a Cubism 3.0 export runs eight of them over the same model in sequence,
 * and a strip that rebuilt a list from the original instead of from the running copy would silently
 * undo whichever strip ran before it.
 *
 * The corpus-backed half of this lives in `Moc3DowngradeOracleTest`, which proves the stripped files
 * actually load; this half proves the model-level rules without needing a corpus or a C core.
 */
class Moc3VersionDowngradeTest {
	private val morphParameter = ParameterId("ParamMorph")

	/** A rig using every restricted feature that [PuppetModel] can express. */
	private fun kitchenSink(): PuppetModel {
		val warp =
			Deformer.Warp(
				id = DeformerId("Warp1"),
				name = "Warp1",
				parent = null,
				partId = PartId("Part1"),
				rows = 2,
				columns = 2,
				isQuadTransform = true,
				geometryGrid = null,
				multiplyColor = ColorRgb(0.5f, 0.5f, 0.5f),
				blendShapes =
					listOf(
						BlendShapeBinding(
							parameterId = morphParameter,
							keys = floatArrayOf(0f, 1f),
							neutralIndex = 0,
							forms = listOf(null, WarpForm(FloatArray(8))),
						),
					),
			)
		val rotation =
			Deformer.Rotation(
				id = DeformerId("Rotation1"),
				name = "Rotation1",
				parent = null,
				partId = PartId("Part1"),
				baseAngle = 0f,
				geometryGrid = null,
				screenColor = ColorRgb(0.25f, 0.25f, 0.25f),
				blendShapes =
					listOf(
						BlendShapeBinding(
							parameterId = morphParameter,
							keys = floatArrayOf(0f, 1f),
							neutralIndex = 0,
							forms = listOf(null, null),
						),
					),
			)
		val drawable =
			Drawable(
				id = DrawableId("ArtMesh1"),
				name = "ArtMesh1",
				parentDeformerId = null,
				blendMode = BlendMode.Overlay,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
				alphaBlendMode = AlphaBlendMode.Atop,
				invertMask = true,
				blendShapes =
					listOf(
						BlendShapeBinding(
							parameterId = morphParameter,
							keys = floatArrayOf(0f, 1f),
							neutralIndex = 0,
							forms = listOf<MeshForm?>(null, MeshForm(FloatArray(0))),
						),
					),
			)
		val part =
			Part(
				id = PartId("Part1"),
				name = "Part1",
				children = listOf(OrgChild.Drawable(drawable.id)),
				groupMode = PartGroupMode.Isolated,
				composite =
					PartComposite(
						blendMode = BlendMode.Screen,
						alphaBlendMode = AlphaBlendMode.Out,
						invertMask = true,
						multiplyColor = ColorRgb(0.5f, 0.5f, 0.5f),
					),
			)
		return PuppetModel(
			parameters =
				listOf(
					Parameter(morphParameter, "Morph", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE),
					Parameter(ParameterId("ParamWheel"), "Wheel", 0f, 360f, 0f, repeat = true),
				),
			parts = listOf(part),
			deformers = listOf(warp, rotation),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Part(part.id)),
			rootPartId = null,
		)
	}

	/**
	 * The features named by notices, for comparing against the scan.
	 *
	 * @param List notices The strip's notices.
	 * @return Set The features reported.
	 */
	private fun strippedFeatures(notices: List<ExportNotice>): Set<RuntimeFeature> =
		notices.filterIsInstance<ExportNotice.FeatureStripped>().map { it.feature }.toSet()

	@Test
	fun theOldestTargetStripsEveryRepresentableFeature() {
		val original = kitchenSink()
		val stripped = Moc3VersionDowngrade.strip(original, MocVersion.V30)
		val puppet = stripped.puppet

		val warp = puppet.deformers.first { it is Deformer.Warp } as Deformer.Warp
		val rotation = puppet.deformers.first { it is Deformer.Rotation } as Deformer.Rotation
		val drawable = puppet.drawables.single()
		val part = puppet.parts.single()

		assertFalse(warp.isQuadTransform, "the 3.3 warp method must be gone")
		assertEquals(emptyList(), warp.blendShapes, "warp blend shapes must be gone")
		assertEquals(emptyList(), rotation.blendShapes, "rotation blend shapes must be gone")
		assertEquals(emptyList(), drawable.blendShapes, "mesh blend shapes must be gone")
		// NOT stripped at v1: the mask bit and the repeat section exist in every moc version, whatever
		// the editor's target dialog says about when a rigger may author them.
		assertTrue(drawable.invertMask, "the reversed mask is carried by every version")
		assertTrue(puppet.parameters.any { it.repeat }, "the repeat flag is carried by every version")
		assertEquals(BlendMode.Normal, drawable.blendMode, "Overlay has no pre-5.3 ancestor")
		assertEquals(AlphaBlendMode.Over, drawable.alphaBlendMode, "the alpha mode must be back to Over")
		assertEquals(ColorRgb.MultiplyIdentity, warp.multiplyColor, "the warp tint must be gone")
		assertEquals(ColorRgb.ScreenIdentity, rotation.screenColor, "the rotation tint must be gone")
		assertEquals(PartGroupMode.Grouped, part.groupMode, "an isolated part must demote to grouped")
		assertEquals(
			ParameterKind.NORMAL,
			puppet.parameters.first { it.id == morphParameter }.kind,
			"a blend-shape parameter must demote to normal",
		)

		// The pre-export scan and the strip differ by exactly the two features the editor gates later
		// than the file format does - the scan over-warns about them, and pinning that here is what keeps
		// the difference deliberate instead of a drift nobody notices.
		assertEquals(
			original.unsupportedFeaturesInUse(RuntimeTarget.Cubism30) -
				setOf(RuntimeFeature.ReversedMask, RuntimeFeature.ParameterRepeat),
			strippedFeatures(stripped.notices) -
				setOf(RuntimeFeature.ReversedMask, RuntimeFeature.ParameterRepeat),
			"the strip and the scan disagree about what a 3.0 export loses",
		)
	}

	@Test
	fun theSourceModelIsNeverMutated() {
		val original = kitchenSink()
		val before = original.unsupportedFeaturesInUse(RuntimeTarget.Cubism30)
		Moc3VersionDowngrade.strip(original, MocVersion.V30)
		assertEquals(
			before,
			original.unsupportedFeaturesInUse(RuntimeTarget.Cubism30),
			"the caller's model must still use everything it started with",
		)
		assertTrue(before.isNotEmpty(), "the fixture must actually use restricted features")
	}

	@Test
	fun eachTargetStripsOnlyWhatItsVersionPredates() {
		val original = kitchenSink()
		// 5.0 keeps blend shapes and colour, and loses only the two 5.3 features in use.
		val toFiveZero = Moc3VersionDowngrade.strip(original, MocVersion.V50)
		assertEquals(
			setOf(RuntimeFeature.ExtendedBlendModes, RuntimeFeature.PartComposite),
			strippedFeatures(toFiveZero.notices),
		)
		assertEquals(
			1,
			(toFiveZero.puppet.deformers.first { it is Deformer.Warp } as Deformer.Warp).blendShapes.size,
			"5.0 carries warp blend shapes",
		)
		assertEquals(
			ColorRgb(0.5f, 0.5f, 0.5f),
			(toFiveZero.puppet.deformers.first { it is Deformer.Warp } as Deformer.Warp).multiplyColor,
			"5.0 carries deformer colour",
		)

		// 4.2 additionally loses the rotation-target blend record, but keeps the mesh and warp ones.
		val toFourTwo = Moc3VersionDowngrade.strip(original, MocVersion.V42)
		assertTrue(RuntimeFeature.ExtendedBlendShapes in strippedFeatures(toFourTwo.notices))
		assertEquals(
			1,
			toFourTwo.puppet.drawables.single().blendShapes.size,
			"4.2 carries mesh blend shapes",
		)

		// 5.3 is the ceiling: nothing is stripped and the model comes back unchanged.
		val toFiveThree = Moc3VersionDowngrade.strip(original, MocVersion.V53)
		assertEquals(emptyList(), toFiveThree.notices)
		assertTrue(toFiveThree.puppet === original, "an unrestricted target must not copy the model")
	}

	@Test
	fun aColourTrackIsDroppedAlongWithTheStatic() {
		val original = kitchenSink()
		val tracked =
			original.copy(
				drawables =
					original.drawables.map { drawable ->
						drawable.copy(
							multiplyColor = ColorRgb(0.5f, 0.5f, 0.5f),
							channelGrids = ChannelGrids(mapOf(FormChannel.MULTIPLY_COLOR to colourTrack())),
						)
					},
			)
		val stripped = Moc3VersionDowngrade.strip(tracked, MocVersion.V40).puppet.drawables.single()
		assertEquals(ColorRgb.MultiplyIdentity, stripped.multiplyColor, "the static must reset")
		assertEquals(null, stripped.channelGrids[FormChannel.MULTIPLY_COLOR], "the track must go with it")
	}

	/**
	 * A one-cell multiply track, enough to make the channel count as in use.
	 *
	 * @return KeyformGrid The track.
	 */
	private fun colourTrack(): KeyformGrid<ChannelValue> =
		KeyformGrid(
			axes = emptyList(),
			cells = listOf(KeyformCell(intArrayOf(), ChannelValue.Color(ColorRgb(0.5f, 0.5f, 0.5f)))),
		)
}
