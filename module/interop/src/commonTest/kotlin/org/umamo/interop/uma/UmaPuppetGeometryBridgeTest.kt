package org.umamo.interop.uma

import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.format.uma.puppet.UmaAxis
import org.umamo.format.uma.puppet.UmaBlendLimit
import org.umamo.format.uma.puppet.UmaBlendLimitPoint
import org.umamo.format.uma.puppet.UmaChannelCell
import org.umamo.format.uma.puppet.UmaChannelGrid
import org.umamo.format.uma.puppet.UmaDeformer
import org.umamo.format.uma.puppet.UmaDeformerCell
import org.umamo.format.uma.puppet.UmaDeformerGrid
import org.umamo.format.uma.puppet.UmaDeformerKind
import org.umamo.format.uma.puppet.UmaDrawable
import org.umamo.format.uma.puppet.UmaFormChannel
import org.umamo.format.uma.puppet.UmaGlue
import org.umamo.format.uma.puppet.UmaGluePairs
import org.umamo.format.uma.puppet.UmaMesh
import org.umamo.format.uma.puppet.UmaMeshBlendShape
import org.umamo.format.uma.puppet.UmaMeshCell
import org.umamo.format.uma.puppet.UmaMeshForm
import org.umamo.format.uma.puppet.UmaMeshGrid
import org.umamo.format.uma.puppet.UmaPuppet
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the puppet entry's geometry half in both directions (docs/format/UMA.md §4.9-§4.14) on synthetic
 * models: meshes, every grid kind (a sparse one included), every channel, every blend form, and glue survive a
 * save bit for bit; the buffer carries even a NaN payload while inline JSON refuses one; and each geometry
 * invariant the renderer relies on fails as a malformed entry.
 */
class UmaPuppetGeometryBridgeTest {
	private val writer = UmaWriterInfo("Umamo", "test")

	/**
	 * [model] saved as UMA and read back.
	 *
	 * @param PuppetModel model The model.
	 * @return PuppetModel The model the file describes.
	 */
	private fun roundTrip(model: PuppetModel): PuppetModel {
		val bytes = Uma.write(UmaModel.create(writer).withPuppet(UmaPuppetExport.puppetOf(model)))
		return UmaPuppetImport.modelOf(Uma.read(bytes).puppet!!)
	}

	/**
	 * Eight distinct floats for a quad's four vertices, starting at [start].
	 *
	 * @param Float start The first value.
	 * @return FloatArray The values.
	 */
	private fun quadFloats(start: Float): FloatArray = FloatArray(8) { valueIndex -> start + valueIndex * 0.125f }

	/**
	 * A one-axis channel track over P0 with one value per key.
	 *
	 * @param List<ChannelValue> values The three values.
	 * @return KeyformGrid The track.
	 */
	private fun track(values: List<ChannelValue>): KeyformGrid<ChannelValue> =
		KeyformGrid(listOf(KeyformAxis(ParameterId("P0"), floatArrayOf(-30f, 0f, 30f))), values.mapIndexed { keyIndex, value -> KeyformCell(intArrayOf(keyIndex), value) })

	/**
	 * A model exercising every geometry field.
	 *
	 * @return PuppetModel The model.
	 */
	private fun geometryModel(): PuppetModel {
		val axes = listOf(KeyformAxis(ParameterId("P0"), floatArrayOf(-30f, 0f, 30f)), KeyformAxis(ParameterId("P1"), floatArrayOf(-0.0f, 1f)))
		val denseCells = (0 until 2).flatMap { secondKey -> (0 until 3).map { firstKey -> KeyformCell(intArrayOf(firstKey, secondKey), MeshDeltaForm(quadFloats(firstKey + secondKey * 3f))) } }
		val quad = DrawableMesh(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))
		val colorTracks =
			ChannelGrids(
				linkedMapOf(
					FormChannel.DRAW_ORDER to track(listOf(ChannelValue.Scalar(490f), ChannelValue.Scalar(-0.0f), ChannelValue.Scalar(510.5f))),
					FormChannel.OPACITY to track(listOf(ChannelValue.Scalar(0f), ChannelValue.Scalar(0.5f), ChannelValue.Scalar(1f))),
					FormChannel.MULTIPLY_COLOR to track(listOf(ChannelValue.Color(ColorRgb(1f, 0.5f, 0.25f)), ChannelValue.Color(ColorRgb.MultiplyIdentity), ChannelValue.Color(ColorRgb(0f, 0f, Float.MIN_VALUE)))),
					FormChannel.SCREEN_COLOR to track(listOf(ChannelValue.Color(ColorRgb(0.1f, 0.2f, 0.3f)), ChannelValue.Color(ColorRgb.ScreenIdentity), ChannelValue.Color(ColorRgb.ScreenIdentity))),
				),
			)
		val limits = listOf(BlendWeightLimit(ParameterId("P0"), listOf(BlendWeightLimitPoint(-30f, 0f), BlendWeightLimitPoint(30f, 1f))))
		return PuppetModel(
			parameters = listOf(Parameter(ParameterId("P0"), "Angle", -30f, 30f, 0f), Parameter(ParameterId("P1"), "Morph", 0f, 1f, 0f)),
			parts =
				listOf(
					Part(
						id = PartId("Head"),
						name = "Head",
						children = listOf(OrgChild.Drawable(DrawableId("D0")), OrgChild.Drawable(DrawableId("D1"))),
						groupMode = PartGroupMode.Isolated,
						channelGrids = ChannelGrids(linkedMapOf(FormChannel.DRAW_ORDER to track(listOf(ChannelValue.Scalar(400f), ChannelValue.Scalar(500f), ChannelValue.Scalar(600f))))),
						blendShapes = listOf(BlendShapeBinding(ParameterId("P1"), floatArrayOf(0f, 1f), 0, listOf(null, PartForm(600f, 0.75f, ColorRgb(0.5f, 0.5f, 0.5f))), limits)),
					),
				),
			deformers =
				listOf(
					Deformer.Warp(
						id = DeformerId("W1"),
						name = "Warp",
						parent = null,
						partId = PartId("Head"),
						rows = 1,
						columns = 1,
						isQuadTransform = true,
						geometryGrid = KeyformGrid(axes.take(1), (0 until 3).map { keyIndex -> KeyformCell(intArrayOf(keyIndex), WarpLatticeForm(quadFloats(keyIndex * 10f))) }),
						channelGrids = ChannelGrids(linkedMapOf(FormChannel.OPACITY to track(listOf(ChannelValue.Scalar(1f), ChannelValue.Scalar(0.25f), ChannelValue.Scalar(1f))))),
						blendShapes = listOf(BlendShapeBinding(ParameterId("P1"), floatArrayOf(0f, 1f), 0, listOf(null, WarpForm(quadFloats(-5f), 0.5f)))),
					),
					Deformer.Rotation(
						id = DeformerId("R1"),
						name = "Rotation",
						parent = DeformerId("W1"),
						partId = PartId("Head"),
						baseAngle = 15f,
						geometryGrid = KeyformGrid(axes.take(1), (0 until 3).map { keyIndex -> KeyformCell(intArrayOf(keyIndex), RotationPivotForm(0.5f, -0.0f, keyIndex * 15f, 1f + keyIndex)) }),
						channelGrids =
							ChannelGrids(
								linkedMapOf(
									FormChannel.FLIP_X to track(listOf(ChannelValue.Flag(false), ChannelValue.Flag(true), ChannelValue.Flag(false))),
									FormChannel.FLIP_Y to track(listOf(ChannelValue.Flag(true), ChannelValue.Flag(true), ChannelValue.Flag(false))),
								),
							),
						blendShapes = listOf(BlendShapeBinding(ParameterId("P1"), floatArrayOf(0f, 1f), 0, listOf(null, RotationForm(1f, 2f, 45f, 0.5f, flipX = true, flipY = false, opacity = 0.5f)), limits)),
					),
				),
			drawables =
				listOf(
					Drawable(
						id = DrawableId("D0"),
						name = "Eye",
						parentDeformerId = DeformerId("R1"),
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = quad,
						geometryGrid = KeyformGrid(axes, denseCells),
						channelGrids = colorTracks,
						blendShapes = listOf(BlendShapeBinding(ParameterId("P1"), floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(quadFloats(9f), 510f, 0.5f, ColorRgb(0.9f, 0.9f, 0.9f), ColorRgb(0.1f, 0f, 0f))), limits)),
					),
					Drawable(
						id = DrawableId("D1"),
						name = "Brow",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh(quad.positions.copyOf(), quad.uvs.copyOf(), quad.indices.copyOf()),
						// A sparse grid: one of the six cells is missing, and the others keep their order.
						geometryGrid = KeyformGrid(axes, denseCells.filterIndexed { cellIndex, _ -> cellIndex != 4 }.reversed()),
					),
				),
			rootChildren = listOf(OrgChild.Part(PartId("Head"))),
			rootPartId = null,
			glues =
				listOf(
					Glue(
						meshA = DrawableId("D0"),
						meshB = DrawableId("D1"),
						pairs = listOf(GluePair(0, 3, 0.5f, 0.25f), GluePair(1, 2, 1f, -0.0f)),
						channelGrids = ChannelGrids(linkedMapOf(FormChannel.GLUE_INTENSITY to track(listOf(ChannelValue.Scalar(0f), ChannelValue.Scalar(1f), ChannelValue.Scalar(0.5f))))),
						intensity = 0.75f,
						id = "Glue__D0__D1",
					),
				),
		).withDerivedRenderRoot()
	}

	/**
	 * Every geometry field survives a save bit for bit.
	 */
	@Test
	fun everyGeometryFieldRoundTripsExactly() {
		val model = geometryModel()
		val differences = puppetStructureDifferences(model, roundTrip(model))
		assertTrue(differences.isEmpty(), differences.take(20).toString())
	}

	/**
	 * The comparer does see a single flipped bit deep inside a delta buffer.
	 */
	@Test
	fun comparerSeesADeltaBit() {
		val model = geometryModel()
		val drawable = model.drawables.first()
		val grid = drawable.geometryGrid!!
		val flipped = grid.cells.first().form.positionDeltas.copyOf().also { deltas -> deltas[5] = Float.fromBits(deltas[5].toRawBits() xor 1) }
		val changedGrid = KeyformGrid(grid.axes, listOf(KeyformCell(grid.cells.first().coordinate, MeshDeltaForm(flipped))) + grid.cells.drop(1))
		val changed = model.copy(drawables = listOf(drawable.copy(geometryGrid = changedGrid)) + model.drawables.drop(1))
		assertEquals(listOf("drawables[0].geometry.cells[0].positionDeltas[5]"), puppetStructureDifferences(model, changed).map { difference -> difference.substringBefore(':') })
	}

	/**
	 * A buffer carries any float bit for bit, a NaN payload included; the same value inline refuses the save.
	 */
	@Test
	fun bufferCarriesNaNWhileInlineRefuses() {
		val base = geometryModel()
		val payloadNaN = Float.fromBits(0x7FC00001)
		val withNaN = base.copy(drawables = base.drawables.map { drawable -> if (drawable.id == DrawableId("D1")) drawable.copy(mesh = DrawableMesh(floatArrayOf(payloadNaN, 0f, 10f, 0f, 10f, 10f, 0f, 10f), drawable.mesh!!.uvs, drawable.mesh!!.indices)) else drawable })
		val reopened = roundTrip(withNaN)
		assertEquals(payloadNaN.toRawBits(), reopened.drawables.first { drawable -> drawable.id == DrawableId("D1") }.mesh!!.positions[0].toRawBits(), "the payload survives")

		val inlineNaN = base.copy(parameters = base.parameters, parts = base.parts, deformers = base.deformers.map { deformer -> if (deformer is Deformer.Rotation) deformer.copy(geometryGrid = KeyformGrid(deformer.geometryGrid!!.axes, listOf(KeyformCell(intArrayOf(0), RotationPivotForm(Float.NaN, 0f, 0f, 1f))))) else deformer })
		val failure = assertFailsWith<UmaWriteException> { UmaPuppetExport.puppetOf(inlineNaN) }
		assertTrue(failure.message.orEmpty().contains("deformers[R1].geometry.cells[0].originX"), "the failure names the value: ${failure.message}")
	}

	/**
	 * Each geometry invariant the renderer relies on fails as a malformed entry.
	 */
	@Test
	fun brokenGeometryIsMalformed() {
		/**
		 * Asserts that bridging [puppet] fails as a malformed entry whose detail names [where].
		 *
		 * @param UmaPuppet puppet The entry.
		 * @param String    where  A fragment of the path the failure must name.
		 */
		fun assertMalformed(puppet: UmaPuppet, where: String) {
			val failure = assertFailsWith<UmaFormatException>(where) { UmaPuppetImport.modelOf(puppet) }.failure
			val detail = assertIs<UmaReadFailure.MalformedEntry>(failure, where).detail
			assertTrue(detail.contains(where), "'$detail' names $where")
		}
		val positions = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
		val mesh = UmaMesh(positions, positions.copyOf(), intArrayOf(0, 1, 2))
		val axis = UmaAxis("P0", listOf(0f, 1f))

		/**
		 * A puppet holding one drawable.
		 *
		 * @param UmaDrawable drawable The drawable.
		 * @return UmaPuppet The puppet.
		 */
		fun withDrawable(drawable: UmaDrawable): UmaPuppet = UmaPuppet(drawables = listOf(drawable))

		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = UmaMesh(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, 1f), intArrayOf()))), "drawables[D].mesh.positions")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = UmaMesh(positions, floatArrayOf(0f, 0f), intArrayOf(0, 1, 2)))), "drawables[D].mesh.uvs")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = UmaMesh(positions, positions.copyOf(), intArrayOf(0, 1)))), "drawables[D].mesh.indices")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = UmaMesh(positions, positions.copyOf(), intArrayOf(0, 1, 3)))), "drawables[D].mesh.indices[2]")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = mesh, geometry = UmaMeshGrid(listOf(axis), listOf(UmaMeshCell(listOf(0), FloatArray(4)))))), "drawables[D].geometry.cells[0].positionDeltas")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = mesh, geometry = UmaMeshGrid(listOf(axis), listOf(UmaMeshCell(listOf(2), FloatArray(6)))))), "drawables[D].geometry.cells[0].coordinate[0]")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = mesh, geometry = UmaMeshGrid(listOf(axis), listOf(UmaMeshCell(listOf(0, 0), FloatArray(6)))))), "drawables[D].geometry.cells[0].coordinate")
		assertMalformed(
			withDrawable(UmaDrawable("D", "D", channels = mapOf(UmaFormChannel.Opacity to UmaChannelGrid(listOf(axis), listOf(UmaChannelCell(listOf(0), JsonPrimitive(true))))))),
			"drawables[D].channels.opacity.cells[0].value",
		)
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = mesh, blendShapes = listOf(UmaMeshBlendShape("P1", listOf(0f, 1f), 0, listOf(null))))), "drawables[D].blendShapes[0]")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = mesh, blendShapes = listOf(UmaMeshBlendShape("P1", listOf(0f, 1f), 2, listOf(null, UmaMeshForm(FloatArray(6))))))), "drawables[D].blendShapes[0].neutralIndex")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = mesh, geometry = UmaMeshGrid(listOf(UmaAxis("P0", listOf(1f, 0f))), listOf(UmaMeshCell(listOf(0), FloatArray(6)))))), "drawables[D].geometry.axes[0].keys[1]")
		assertMalformed(withDrawable(UmaDrawable("D", "D", mesh = mesh, blendShapes = listOf(UmaMeshBlendShape("P1", listOf(1f, 0f), 0, listOf(null, UmaMeshForm(FloatArray(6))))))), "drawables[D].blendShapes[0].keys[1]")
		val descendingLimit = UmaBlendLimit("P0", listOf(UmaBlendLimitPoint(1f, 1f), UmaBlendLimitPoint(0f, 0f)))
		assertMalformed(
			withDrawable(UmaDrawable("D", "D", mesh = mesh, blendShapes = listOf(UmaMeshBlendShape("P1", listOf(0f, 1f), 0, listOf(null, UmaMeshForm(FloatArray(6))), listOf(descendingLimit))))),
			"drawables[D].blendShapes[0].limits[0].points[1]",
		)

		val warp = UmaDeformer("W", UmaDeformerKind.Warp, "W", rows = 1, columns = 1, isQuadTransform = true)
		assertMalformed(UmaPuppet(deformers = listOf(warp.copy(geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), controlPoints = FloatArray(6))))))), "deformers[W].geometry.cells[0].controlPoints")
		assertMalformed(UmaPuppet(deformers = listOf(warp.copy(geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), controlPoints = FloatArray(8), angle = 1f)))))), "deformers[W].geometry.cells[0]")
		// 65536 x 65536 x 2 floats wraps to zero in 32 bits, which an empty lattice would then match.
		val hugeWarp = warp.copy(rows = 65535, columns = 65535, geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), controlPoints = FloatArray(0)))))
		assertMalformed(UmaPuppet(deformers = listOf(hugeWarp)), "deformers[W] has a 65535 x 65535 lattice")
		val rotation = UmaDeformer("R", UmaDeformerKind.Rotation, "R", baseAngle = 0f)
		assertMalformed(UmaPuppet(deformers = listOf(rotation.copy(geometry = UmaDeformerGrid(listOf(axis), listOf(UmaDeformerCell(listOf(0), originX = 0f, originY = 0f, angle = 0f)))))), "deformers[R].geometry.cells[0]")

		val glueMeshes = listOf(UmaDrawable("A", "A", mesh = mesh), UmaDrawable("B", "B", mesh = mesh))
		assertMalformed(UmaPuppet(drawables = glueMeshes, glues = listOf(UmaGlue("A", "B", UmaGluePairs(intArrayOf(0, 1), intArrayOf(0), FloatArray(2), FloatArray(2))))), "glues[A,B].pairs")
		assertMalformed(UmaPuppet(drawables = glueMeshes, glues = listOf(UmaGlue("A", "B", UmaGluePairs(intArrayOf(0), intArrayOf(5), FloatArray(1), FloatArray(1))))), "glues[A,B].pairs.indicesB[0]")
	}
}