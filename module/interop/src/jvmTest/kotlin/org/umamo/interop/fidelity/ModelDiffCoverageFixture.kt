package org.umamo.interop.fidelity

import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
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
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.deriveRenderRoot

/**
 * Where the fixture model holds one instance of a class, as a reader and a writer.
 *
 * A writer replaces the instance at the place the reader found it, by position rather than by id, so an edit of an
 * id lands where the original stood.
 *
 * @property Function read Finds the instance in a model.
 * @property Function write Puts an edited instance in its place.
 */
internal class FixtureRoute<T : Any>(val read: (PuppetModel) -> T, val write: (PuppetModel, T) -> PuppetModel) {
	/**
	 * The route one step further in, to a value the instance holds.
	 *
	 * @param Function get Reads the inner value from the instance.
	 * @param Function put Rebuilds the instance around an edited inner value.
	 * @return FixtureRoute The inner route.
	 */
	fun <TInner : Any> into(get: (T) -> TInner, put: (T, TInner) -> T): FixtureRoute<TInner> =
		FixtureRoute({ model -> get(read(model)) }, { model, inner -> write(model, put(read(model), inner)) })
}

/**
 * A list with one element replaced.
 *
 * @param Int index Where.
 * @param TElement element The replacement.
 * @return List The new list.
 */
private fun <TElement> List<TElement>.replacingAt(index: Int, element: TElement): List<TElement> = mapIndexed { elementIndex, existing -> if (elementIndex == index) element else existing }

/**
 * The second cell of a two-key grid, the one away from the rest, as a route to its form.
 *
 * @return FixtureRoute The route to the keyed form.
 */
private fun <TForm : Any> FixtureRoute<KeyformGrid<TForm>>.keyedForm(): FixtureRoute<TForm> =
	into({ grid -> grid.cells[1] }, { grid, cell -> KeyformGrid(grid.axes, grid.cells.replacingAt(1, cell)) })
		.into({ cell -> cell.form }, { cell, form -> KeyformCell(cell.coordinate, form) })

/**
 * The authored form of a two-key blend shape, the one away from its neutral key.
 *
 * @return FixtureRoute The route to the form.
 */
private fun <TForm : Any> FixtureRoute<BlendShapeBinding<TForm>>.authoredForm(): FixtureRoute<TForm> =
	into({ binding -> checkNotNull(binding.forms[1]) }, { binding, form -> binding.copy(forms = binding.forms.replacingAt(1, form)) })

/**
 * The model every coverage edit starts from, and where it holds each class the tables cover.
 *
 * It is synthetic, so the gate runs without the corpus.  Each class has one instance the diff compares: a
 * value type is reached through an owner the diff walks, never through one it skips, so that an edit of the
 * value type is judged by the comparison that owns it.
 */
internal object CoverageFixture {
	private val paramX = ParameterId("ParamX")
	private val paramY = ParameterId("ParamY")
	private val paramBlend = ParameterId("ParamBlend")
	private val partId = PartId("PartA")
	private val warpId = DeformerId("WarpA")
	private val rotationId = DeformerId("RotationA")
	private val meshA = DrawableId("MeshA")
	private val meshB = DrawableId("MeshB")
	private val tileId = AtlasTileId("TileA")
	private val sourceId = ArtSourceId("SourceA")

	/**
	 * A grid keyed at 0 and 1 on one parameter.
	 *
	 * @param TForm rest The form at 0.
	 * @param TForm keyed The form at 1.
	 * @return KeyformGrid The grid.
	 */
	private fun <TForm> twoKeyGrid(rest: TForm, keyed: TForm): KeyformGrid<TForm> =
		KeyformGrid(listOf(KeyformAxis(paramX, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), rest), KeyformCell(intArrayOf(1), keyed)))

	/**
	 * A blend shape on the blend parameter, neutral at 0 and authored at 1.
	 *
	 * @param TForm form The authored form.
	 * @return BlendShapeBinding The binding.
	 */
	private fun <TForm : Any> blendShape(form: TForm): BlendShapeBinding<TForm> = BlendShapeBinding(paramBlend, floatArrayOf(0f, 1f), neutralIndex = 0, forms = listOf(null, form))

	/**
	 * A builds-fresh copy of the fixture model: two builds share no instance and diff to nothing.
	 *
	 * @return PuppetModel The model.
	 */
	fun model(): PuppetModel {
		val opacityTrack = ChannelGrids(mapOf(FormChannel.OPACITY to twoKeyGrid<ChannelValue>(ChannelValue.Scalar(1f), ChannelValue.Scalar(0.5f))))
		val warp =
			Deformer.Warp(
				id = warpId,
				name = "WarpA",
				parent = null,
				partId = partId,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				geometryGrid =
					twoKeyGrid(
						WarpLatticeForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)),
						WarpLatticeForm(floatArrayOf(0.1f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)),
					),
				blendShapes = listOf(blendShape(WarpForm(floatArrayOf(0f, 0.1f, 0f, 0f, 0f, 0f, 0f, 0f)))),
			)
		val rotation =
			Deformer.Rotation(
				id = rotationId,
				name = "RotationA",
				parent = warpId,
				partId = partId,
				baseAngle = 0f,
				geometryGrid = twoKeyGrid(RotationPivotForm(0.5f, 0.5f, 0f, 1f), RotationPivotForm(0.5f, 0.5f, 30f, 1f)),
				blendShapes = listOf(blendShape(RotationForm(0f, 0f, 10f, 1f, flipX = false, flipY = false))),
			)
		val keyedMesh =
			Drawable(
				id = meshA,
				name = "MeshA",
				parentDeformerId = rotationId,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh =
					DrawableMesh(
						positions = floatArrayOf(10f, 10f, 20f, 10f, 10f, 20f),
						localPositions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f),
						uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
						indices = intArrayOf(0, 1, 2),
					),
				geometryGrid = twoKeyGrid(MeshDeltaForm(FloatArray(6)), MeshDeltaForm(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f))),
				channelGrids = opacityTrack,
				atlasTileId = tileId,
				blendShapes = listOf(blendShape(MeshForm(floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f)))),
			)
		val plainMesh =
			Drawable(
				id = meshB,
				name = "MeshB",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh =
					DrawableMesh.withLocalEqualToCanvas(
						positions = floatArrayOf(30f, 30f, 40f, 30f, 30f, 40f),
						uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
						indices = intArrayOf(0, 1, 2),
					),
				geometryGrid = null,
			)
		val model =
			PuppetModel(
				parameters =
					listOf(
						Parameter(paramX, "ParamX", min = -1f, max = 1f, default = 0f),
						Parameter(paramY, "ParamY", min = -1f, max = 1f, default = 0f),
						Parameter(paramBlend, "ParamBlend", min = 0f, max = 1f, default = 0f, kind = ParameterKind.BLEND_SHAPE),
					),
				parts =
					listOf(
						Part(
							id = partId,
							name = "PartA",
							children = listOf(OrgChild.Drawable(meshA), OrgChild.Drawable(meshB)),
							blendShapes = listOf(blendShape(PartForm(drawOrder = 600f))),
						),
					),
				deformers = listOf(warp, rotation),
				drawables = listOf(keyedMesh, plainMesh),
				rootChildren = listOf(OrgChild.Part(partId)),
				rootPartId = null,
				glues = listOf(Glue(meshA, meshB, listOf(GluePair(0, 0, 0.5f, 0.5f)), id = "Glue__MeshA__MeshB")),
				parameterLinks = listOf(ParameterLink(paramX, paramY)),
				parameterTree =
					listOf(
						ParameterNode.Group(ParameterGroupId("GroupA"), "GroupA", initiallyOpen = true, children = listOf(ParameterNode.Param(paramX), ParameterNode.Param(paramY))),
						ParameterNode.Param(paramBlend),
					),
				canvasWidth = 100f,
				canvasHeight = 100f,
				worldOriginX = 50f,
				worldOriginZ = -50f,
				atlas =
					PuppetAtlas(
						pages = listOf(AtlasPage(64, 64)),
						tiles = listOf(AtlasTile(tileId, "TileA", 16, 16, AtlasPlacement(0, 4f, 8f, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f), SourceLayerRef(sourceId, "lyid:1", stableKey = true))),
					),
				sources = listOf(ArtSource(sourceId, "a.psd", "/art/a.psd", "psd", listOf(ArtSourceLayer("lyid:1", "Layer", "", 0, 0, 16, 16, true)))),
			)

		return model.copy(renderRoot = model.deriveRenderRoot())
	}

	val modelRoute = FixtureRoute<PuppetModel>({ model -> model }, { _, edited -> edited })
	val parameterRoute = modelRoute.into({ model -> model.parameters[0] }, { model, parameter -> model.copy(parameters = model.parameters.replacingAt(0, parameter)) })
	val partRoute = modelRoute.into({ model -> model.parts[0] }, { model, part -> model.copy(parts = model.parts.replacingAt(0, part)) })
	val warpRoute = modelRoute.into({ model -> model.deformers[0] as Deformer.Warp }, { model, warp -> model.copy(deformers = model.deformers.replacingAt(0, warp)) })
	val rotationRoute = modelRoute.into({ model -> model.deformers[1] as Deformer.Rotation }, { model, rotation -> model.copy(deformers = model.deformers.replacingAt(1, rotation)) })
	val drawableRoute = modelRoute.into({ model -> model.drawables[0] }, { model, drawable -> model.copy(drawables = model.drawables.replacingAt(0, drawable)) })
	val glueRoute = modelRoute.into({ model -> model.glues[0] }, { model, glue -> model.copy(glues = model.glues.replacingAt(0, glue)) })
	val meshRoute = drawableRoute.into({ drawable -> checkNotNull(drawable.mesh) }, { drawable, mesh -> drawable.copy(mesh = mesh) })
	val channelGridsRoute = drawableRoute.into({ drawable -> drawable.channelGrids }, { drawable, channelGrids -> drawable.copy(channelGrids = channelGrids) })
	val meshGridRoute = drawableRoute.into({ drawable -> checkNotNull(drawable.geometryGrid) }, { drawable, grid -> drawable.copy(geometryGrid = grid) })
	val meshAxisRoute = meshGridRoute.into({ grid -> grid.axes[0] }, { grid, axis -> KeyformGrid(grid.axes.replacingAt(0, axis), grid.cells) })
	val meshCellRoute = meshGridRoute.into({ grid -> grid.cells[1] }, { grid, cell -> KeyformGrid(grid.axes, grid.cells.replacingAt(1, cell)) })
	val meshDeltaFormRoute = meshGridRoute.keyedForm()
	val warpLatticeFormRoute = warpRoute.into({ warp -> checkNotNull(warp.geometryGrid) }, { warp, grid -> warp.copy(geometryGrid = grid) }).keyedForm()
	val rotationPivotFormRoute = rotationRoute.into({ rotation -> checkNotNull(rotation.geometryGrid) }, { rotation, grid -> rotation.copy(geometryGrid = grid) }).keyedForm()
	val meshBlendShapeRoute = drawableRoute.into({ drawable -> drawable.blendShapes[0] }, { drawable, binding -> drawable.copy(blendShapes = drawable.blendShapes.replacingAt(0, binding)) })
	val meshFormRoute = meshBlendShapeRoute.authoredForm()
	val warpFormRoute = warpRoute.into({ warp -> warp.blendShapes[0] }, { warp, binding -> warp.copy(blendShapes = warp.blendShapes.replacingAt(0, binding)) }).authoredForm()
	val rotationFormRoute = rotationRoute.into({ rotation -> rotation.blendShapes[0] }, { rotation, binding -> rotation.copy(blendShapes = rotation.blendShapes.replacingAt(0, binding)) }).authoredForm()
	val partFormRoute = partRoute.into({ part -> part.blendShapes[0] }, { part, binding -> part.copy(blendShapes = part.blendShapes.replacingAt(0, binding)) }).authoredForm()
	val gluePairRoute = glueRoute.into({ glue -> glue.pairs[0] }, { glue, pair -> glue.copy(pairs = glue.pairs.replacingAt(0, pair)) })
	val groupRoute = modelRoute.into({ model -> model.parameterTree[0] as ParameterNode.Group }, { model, group -> model.copy(parameterTree = model.parameterTree.replacingAt(0, group)) })
	val paramNodeRoute = groupRoute.into({ group -> group.children[0] as ParameterNode.Param }, { group, param -> group.copy(children = group.children.replacingAt(0, param)) })
}