package org.umamo.render.puppet

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [diffModel]'s four tiers by DECISION.
 *
 * The three GL reconcile tests cover this ground too, but they test the diff AND the GL apply together
 * and assert on pixels - so they need a display, and they cover maybe half the cases. These enumerate the
 * decisions themselves and run anywhere.
 */
class ModelDiffTest {
	private val paramA = ParameterId("A")

	private fun grid(positions: FloatArray): KeyformGrid<MeshForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size)))),
		)

	private fun drawable(
		id: String,
		positions: FloatArray,
		uvs: FloatArray = FloatArray(positions.size),
		indices: IntArray = intArrayOf(0, 1, 2),
		keyforms: KeyformGrid<MeshForm>? = null,
	): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, uvs, indices),
			keyforms = keyforms ?: grid(positions),
		)

	private fun model(vararg drawables: Drawable): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables.toList(),
			rootChildren = drawables.map { OrgChild.Drawable(it.id) },
			rootPartId = null,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		)

	private fun resident(vararg pairs: Pair<String, Int>): Map<DrawableId, Int> = pairs.associate { DrawableId(it.first) to it.second }

	@Test
	fun diffModelKeepsAnUntouchedDrawableWithNoWork() {
		// Copy-on-write means an untouched drawable keeps its array instances, so both tiers are null and
		// the backend does nothing at all - the case that must stay cheap, since it is every frame's.
		val source = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val diff = diffModel(model(source), model(source), resident("a" to 3))
		val keep = assertIs<DrawableAction.Keep>(diff.actions.single())
		assertNull(keep.positions, "an untouched mesh re-uploads no positions")
		assertNull(keep.uvs, "and no UVs")
		assertTrue(diff.removed.isEmpty())
	}

	@Test
	fun diffModelUploadsADrawableTheBackendHasNeverSeen() {
		val existing = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val added = drawable("copy", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		// "copy" is absent from the resident map - an Object-mode duplicate, or one skipped at load.
		val diff = diffModel(model(existing), model(existing, added), resident("a" to 3))
		val upload = assertIs<DrawableAction.Upload>(diff.actions[1])
		assertEquals(DrawableId("copy"), upload.drawableId)
		assertTrue(diff.removed.isEmpty())
	}

	@Test
	fun diffModelRemovesAResidentTheEditDropped() {
		val kept = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val dropped = drawable("gone", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val diff = diffModel(model(kept, dropped), model(kept), resident("a" to 3, "gone" to 3))
		assertEquals(listOf(DrawableId("gone")), diff.removed)
	}

	@Test
	fun diffModelReuploadsOnEachTopologyTrigger() {
		val positions = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
		val before = drawable("a", positions)

		// 1. The vertex count changed against what the backend uploaded.
		val grown = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 2f, 2f))
		assertIs<DrawableAction.Reupload>(diffModel(model(before), model(grown), resident("a" to 3)).actions.single(), "vertex count")

		// 2. The indices array instance changed (a merge / rip / connect).
		val reindexed = drawable("a", positions, indices = intArrayOf(0, 2, 1))
		assertIs<DrawableAction.Reupload>(diffModel(model(before), model(reindexed), resident("a" to 3)).actions.single(), "indices")

		// 3. The keyform grid instance changed, so the delta texture's striding is stale.
		val rekeyed = drawable("a", positions, keyforms = grid(positions))
		assertIs<DrawableAction.Reupload>(diffModel(model(before), model(rekeyed), resident("a" to 3)).actions.single(), "keyforms")
	}

	@Test
	fun diffModelReuploadsPositionsOnlyWhenTheArrayInstanceChanged() {
		val before = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val movedPositions = floatArrayOf(5f, 5f, 6f, 5f, 6f, 6f)
		// withMeshPositions shares the uvs array by reference; only positions is a new instance.
		val after = drawable("a", movedPositions, uvs = before.mesh!!.uvs, indices = before.mesh!!.indices, keyforms = before.keyforms)
		val keep = assertIs<DrawableAction.Keep>(diffModel(model(before), model(after), resident("a" to 3)).actions.single())
		assertSame(movedPositions, keep.positions, "the moved positions re-upload")
		assertNull(keep.uvs, "the shared uvs array does not")
	}

	@Test
	fun diffModelReuploadsUvsOnlyWhenTheArrayInstanceChanged() {
		val before = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val movedUvs = floatArrayOf(0.5f, 0.5f, 1f, 0.5f, 1f, 1f)
		val after =
			drawable("a", before.mesh!!.positions, uvs = movedUvs, indices = before.mesh!!.indices, keyforms = before.keyforms)
		val keep = assertIs<DrawableAction.Keep>(diffModel(model(before), model(after), resident("a" to 3)).actions.single())
		assertNull(keep.positions)
		assertSame(movedUvs, keep.uvs, "a UV edit re-uploads the UV buffer only")
	}

	@Test
	fun diffModelSkipsUvsWhoseLengthDisagreesWithTheResident() {
		// Defensive: the copy-on-write UV edit never changes the length, so a mismatch means the backend
		// padded this mesh's UVs at upload. Re-uploading a short array would leave the tail stale.
		val before = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), uvs = FloatArray(6))
		val after = drawable("a", before.mesh!!.positions, uvs = FloatArray(2), indices = before.mesh!!.indices, keyforms = before.keyforms)
		val keep = assertIs<DrawableAction.Keep>(diffModel(model(before), model(after), resident("a" to 3)).actions.single())
		assertNull(keep.uvs, "a length mismatch leaves the resident's UVs alone")
	}

	@Test
	fun diffModelNeverBothActionsAndRemovesTheSameDrawable() {
		// The double-free guard. A remesh frees the resident and re-uploads; if that upload yields nothing
		// (a remesh to zero triangles), the drawable must NOT also appear in `removed` - freeing the same GL
		// names twice can hit a name the driver recycled for a later upload in the same pass, which would
		// free a live buffer belonging to a different drawable.
		val before = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val emptied = drawable("a", floatArrayOf(0f, 0f), indices = IntArray(0))
		val diff = diffModel(model(before), model(emptied), resident("a" to 3))
		assertIs<DrawableAction.Reupload>(diff.actions.single())
		assertTrue(diff.removed.isEmpty(), "a re-uploaded drawable is never also removed")
	}

	@Test
	fun diffModelKeepsResidentWhoseMeshWentNull() {
		// Reachable: a kept resident whose new mesh is null. The resident vertex count must come from the
		// backend, not the model, because the model no longer carries what the buffers were built at.
		val before = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val meshless =
			Drawable(DrawableId("a"), "a", null, BlendMode.Normal, emptyList(), mesh = null, keyforms = before.keyforms)
		val diff = diffModel(model(before), model(meshless), resident("a" to 3))
		val keep = assertIs<DrawableAction.Keep>(diff.actions.single())
		assertNull(keep.positions)
		assertNull(keep.uvs)
		assertTrue(diff.removed.isEmpty(), "it stays resident rather than being freed")
	}

	@Test
	fun diffModelActionsFollowNewModelOrderThroughAReorder() {
		// Keyed by id, so a simultaneous reorder is handled; the actions follow the NEW order, which is what
		// rebuilds residency in that order.
		val first = drawable("a", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val second = drawable("b", floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f))
		val diff = diffModel(model(first, second), model(second, first), resident("a" to 3, "b" to 3))
		assertEquals(listOf(DrawableId("b"), DrawableId("a")), diff.actions.map { it.drawableId })
		assertTrue(diff.actions.all { it is DrawableAction.Keep }, "a reorder needs no buffer work")
		assertTrue(diff.removed.isEmpty())
	}
}
