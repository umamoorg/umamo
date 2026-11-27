package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the positions-only classification a preview push relies on: a model that differs from the
 * previous one in nothing but some drawables' mesh positions (the shape withMeshPositions produces)
 * is positions-only, and every other difference, however small, is structural.
 */
class PositionsOnlyEditTest {
	private val parameterId = ParameterId("P")
	private val deformerId = DeformerId("R")
	private val partId = PartId("face")
	private val firstId = DrawableId("first")
	private val secondId = DrawableId("second")

	@Test
	fun theSameInstanceDiffersInNothing() {
		val model = model()
		assertTrue(model.differsOnlyInMeshPositions(model))
	}

	@Test
	fun movedPositionsAreAPositionsOnlyEdit() {
		val previous = model()
		val oneMoved = moved(previous, firstId)
		assertTrue(oneMoved.differsOnlyInMeshPositions(previous), "one drawable's positions replaced")
		assertTrue(moved(oneMoved, secondId).differsOnlyInMeshPositions(previous), "two drawables' positions replaced")
	}

	@Test
	fun aNewUvsArrayIsStructural() {
		val previous = model()
		val mesh = previous.drawables.first().mesh!!
		val sameUvsNewArray = withMesh(previous, firstId, DrawableMesh.withLocalEqualToCanvas(mesh.positions, mesh.uvs.copyOf(), mesh.indices))
		assertFalse(sameUvsNewArray.differsOnlyInMeshPositions(previous), "a UV edit is decided by array identity, not content")
	}

	@Test
	fun newIndicesAreStructural() {
		val previous = model()
		val mesh = previous.drawables.first().mesh!!
		val newIndices = withMesh(previous, firstId, DrawableMesh.withLocalEqualToCanvas(mesh.positions, mesh.uvs, mesh.indices.copyOf()))
		assertFalse(newIndices.differsOnlyInMeshPositions(previous))
	}

	@Test
	fun aDifferentVertexCountIsStructural() {
		val previous = model()
		val mesh = previous.drawables.first().mesh!!
		val longer = withMesh(previous, firstId, DrawableMesh.withLocalEqualToCanvas(FloatArray(mesh.positions.size + 2), mesh.uvs, mesh.indices))
		assertFalse(longer.differsOnlyInMeshPositions(previous))
	}

	@Test
	fun aDrawableFieldEditIsStructural() {
		val previous = model()
		val blended = replaceDrawable(previous, firstId) { drawable -> drawable.copy(blendMode = BlendMode.AdditivePremultiplied) }
		assertFalse(blended.differsOnlyInMeshPositions(previous), "a composite-only edit")
		val hidden = replaceDrawable(previous, firstId) { drawable -> drawable.copy(isVisible = false) }
		assertFalse(hidden.differsOnlyInMeshPositions(previous), "a visibility edit")
		val movedAndBlended = replaceDrawable(moved(previous, firstId), firstId) { drawable -> drawable.copy(culling = true) }
		assertFalse(movedAndBlended.differsOnlyInMeshPositions(previous), "positions plus a field edit")
	}

	@Test
	fun aModelFieldEditIsStructural() {
		val previous = model()
		val parameterEdit = previous.copy(parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0.5f)))
		assertFalse(parameterEdit.differsOnlyInMeshPositions(previous), "a parameter default")
		val deformerEdit = previous.copy(deformers = listOf(rotation(baseAngle = 45f)))
		assertFalse(deformerEdit.differsOnlyInMeshPositions(previous), "a deformer replaced")
		val partEdit = previous.copy(parts = listOf(Part(partId, "Renamed", children = listOf(OrgChild.Drawable(firstId)))))
		assertFalse(partEdit.differsOnlyInMeshPositions(previous), "a part renamed")
		val reordered = previous.copy(rootChildren = previous.rootChildren.asReversed())
		assertFalse(reordered.differsOnlyInMeshPositions(previous), "the org tree reordered")
	}

	@Test
	fun aChangedDrawableSetIsStructural() {
		val previous = model()
		val added = previous.copy(drawables = previous.drawables + drawable(DrawableId("third"), mesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))))
		assertFalse(added.differsOnlyInMeshPositions(previous), "a drawable added")
		val meshless = replaceDrawable(previous, firstId) { drawable -> drawable.copy(mesh = null) }
		assertFalse(meshless.differsOnlyInMeshPositions(previous), "a mesh removed")
		val swapped = previous.copy(drawables = previous.drawables.asReversed())
		assertFalse(swapped.differsOnlyInMeshPositions(previous), "the drawables reordered")
	}

	/**
	 * A one-triangle mesh over [positions].
	 *
	 * @param FloatArray positions The rest positions.
	 * @return DrawableMesh The mesh.
	 */
	private fun mesh(positions: FloatArray): DrawableMesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), intArrayOf(0, 1, 2))

	/**
	 * A direct, unkeyed drawable.
	 *
	 * @param DrawableId id The drawable's id.
	 * @param DrawableMesh? mesh Its mesh.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: DrawableId, mesh: DrawableMesh?): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = mesh,
			geometryGrid = null,
		)

	/**
	 * A rotation deformer, so the model carries one of each entity kind.
	 *
	 * @param Float baseAngle The deformer's base angle.
	 * @return Deformer.Rotation The deformer.
	 */
	private fun rotation(baseAngle: Float = 0f): Deformer.Rotation =
		Deformer.Rotation(
			id = deformerId,
			name = "R",
			parent = null,
			partId = null,
			baseAngle = baseAngle,
			geometryGrid = KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), RotationPivotForm(0f, 0f, 0f, 1f)))),
		)

	/**
	 * The fixture: two meshed drawables, one under a part, plus a parameter and a deformer.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0f)),
			parts = listOf(Part(partId, "Face", children = listOf(OrgChild.Drawable(firstId)))),
			deformers = listOf(rotation()),
			drawables = listOf(drawable(firstId, mesh(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f))), drawable(secondId, mesh(floatArrayOf(20f, 0f, 30f, 0f, 20f, 10f)))),
			rootChildren = listOf(OrgChild.Part(partId), OrgChild.Drawable(secondId)),
			rootPartId = null,
		)

	/**
	 * The model with one drawable's positions replaced by a shifted copy, sharing its uvs and indices:
	 * the exact shape withMeshPositions produces.
	 *
	 * @param PuppetModel source The model to edit.
	 * @param DrawableId id The drawable to move.
	 * @return PuppetModel The edited model.
	 */
	private fun moved(source: PuppetModel, id: DrawableId): PuppetModel =
		replaceDrawable(source, id) { drawable ->
			val mesh = drawable.mesh!!
			val shifted = FloatArray(mesh.positions.size) { coordinateIndex -> mesh.positions[coordinateIndex] + 5f }
			drawable.copy(mesh = DrawableMesh.withLocalEqualToCanvas(shifted, mesh.uvs, mesh.indices))
		}

	/**
	 * The model with one drawable's mesh replaced.
	 *
	 * @param PuppetModel source The model to edit.
	 * @param DrawableId id The drawable to edit.
	 * @param DrawableMesh mesh Its new mesh.
	 * @return PuppetModel The edited model.
	 */
	private fun withMesh(source: PuppetModel, id: DrawableId, mesh: DrawableMesh): PuppetModel = replaceDrawable(source, id) { drawable -> drawable.copy(mesh = mesh) }

	/**
	 * The model with one drawable replaced by [edit]'s result, every other drawable shared.
	 *
	 * @param PuppetModel source The model to edit.
	 * @param DrawableId id The drawable to replace.
	 * @param Function edit Builds the replacement from the current drawable.
	 * @return PuppetModel The edited model.
	 */
	private fun replaceDrawable(source: PuppetModel, id: DrawableId, edit: (Drawable) -> Drawable): PuppetModel =
		source.copy(drawables = source.drawables.map { drawable -> if (drawable.id == id) edit(drawable) else drawable })
}