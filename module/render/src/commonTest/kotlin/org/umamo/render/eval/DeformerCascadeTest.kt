package org.umamo.render.eval

import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cascade tests on synthetic single-level and nested hierarchies. A mesh's local verts are pushed
 * through its parent deformer's baked world transform and Y-flipped; nesting composes the transforms
 * top-down.
 */
class DeformerCascadeTest {
	private val tol = 1e-4f
	private val paramA = ParameterId("A")

	private fun values(vararg pairs: Pair<ParameterId, Float>): (ParameterId) -> Float {
		val map = pairs.toMap()
		return { map[it] ?: 0f }
	}

	private fun singleKeyAxis() = listOf(KeyformAxis(paramA, floatArrayOf(0f)))

	private fun warp(id: String, parent: String?, cols: Int, rows: Int, bilinear: Boolean, cp: FloatArray): Deformer.Warp {
		val grid = KeyformGrid(singleKeyAxis(), listOf(KeyformCell(intArrayOf(0), WarpForm(cp))))
		return Deformer.Warp(DeformerId(id), id, parent?.let(::DeformerId), null, rows, cols, bilinear, grid)
	}

	private fun rotation(id: String, baseAngle: Float, originX: Float, originY: Float, angle: Float, scale: Float): Deformer.Rotation {
		val grid = KeyformGrid(singleKeyAxis(), listOf(KeyformCell(intArrayOf(0), RotationForm(originX, originY, angle, scale, false, false))))
		return Deformer.Rotation(DeformerId(id), id, null, null, baseAngle, grid)
	}

	private fun zeroDeltaMesh(vertexCount: Int): KeyformGrid<MeshForm> =
		KeyformGrid(singleKeyAxis(), listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(vertexCount * 2)))))

	@Test
	fun meshUnderTranslatedWarp() {
		// 1×1 lattice placed as a 2×4 rect at (10,20).
		val warp = warp("W", null, 1, 1, true, floatArrayOf(10f, 20f, 12f, 20f, 10f, 24f, 12f, 24f))
		val parent = buildDeformerWorlds(listOf(warp), values(paramA to 0f))[DeformerId("W")]!!
		val world = deformMeshThroughParent(zeroDeltaMesh(1), floatArrayOf(0.5f, 0.5f), parent, values(paramA to 0f))!!
		assertEquals(11f, world[0], tol)
		assertEquals(-22f, world[1], tol) // center (11,22), Y-flipped
	}

	@Test
	fun meshUnderRootRotation() {
		val rotation = rotation("R", 0f, 0f, 0f, 90f, 1f)
		val parent = buildDeformerWorlds(listOf(rotation), values(paramA to 0f))[DeformerId("R")]!!
		val world = deformMeshThroughParent(zeroDeltaMesh(1), floatArrayOf(1f, 0f), parent, values(paramA to 0f))!!
		assertEquals(0f, world[0], tol) // (1,0) rotated +90 -> (0,1)
		assertEquals(-1f, world[1], tol) // Y-flipped
	}

	@Test
	fun nestedWarpComposesParentTransform() {
		// Parent translates +100 in x; child is identity -> composed lattice is the parent's.
		val parentWarp = warp("P", null, 1, 1, true, floatArrayOf(100f, 0f, 101f, 0f, 100f, 1f, 101f, 1f))
		val childWarp = warp("C", "P", 1, 1, true, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
		val worlds = buildDeformerWorlds(listOf(parentWarp, childWarp), values(paramA to 0f))
		val child = worlds[DeformerId("C")]!!
		val world = deformMeshThroughParent(zeroDeltaMesh(1), floatArrayOf(0.5f, 0.5f), child, values(paramA to 0f))!!
		assertEquals(100.5f, world[0], tol) // center of the +100-translated lattice
		assertEquals(-0.5f, world[1], tol)
	}

	// --- Render-channel cascade -------------------------------------------------------------------
	// A deformer's opacity and multiply/screen colors apply to every drawable underneath it, composed
	// down the chain. Riggers key the opacity as a subtree show/hide switch, so these compositions are
	// load-bearing rather than cosmetic.

	/** A 1x1 identity-lattice warp carrying render channels, for channel-only assertions. */
	private fun channelWarp(
		id: String,
		parent: String?,
		opacity: Float,
		multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
		screenColor: ColorRgb = ColorRgb.ScreenIdentity,
	): Deformer.Warp {
		val form = WarpForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), opacity, multiplyColor, screenColor)
		val grid = KeyformGrid(singleKeyAxis(), listOf(KeyformCell(intArrayOf(0), form)))
		return Deformer.Warp(DeformerId(id), id, parent?.let(::DeformerId), null, 1, 1, true, grid)
	}

	@Test
	fun rootDeformerChannelsPassThroughUnchanged() {
		val root = channelWarp("W", null, 0.5f, ColorRgb(0.25f, 0.5f, 1f), ColorRgb(0.1f, 0f, 0f))
		val world = buildDeformerWorlds(listOf(root), values(paramA to 0f))[DeformerId("W")]!!
		// Identity parents: multiplying by white and screening with black both return the local value.
		assertEquals(0.5f, world.accumulatedOpacity, tol)
		assertEquals(0.25f, world.accumulatedMultiplyColor.red, tol)
		assertEquals(1f, world.accumulatedMultiplyColor.blue, tol)
		assertEquals(0.1f, world.accumulatedScreenColor.red, tol)
	}

	@Test
	fun nestedDeformerOpacityMultipliesDownTheChain() {
		val parent = channelWarp("P", null, 0.5f)
		val child = channelWarp("C", "P", 0.5f)
		val worlds = buildDeformerWorlds(listOf(parent, child), values(paramA to 0f))
		assertEquals(0.5f, worlds[DeformerId("P")]!!.accumulatedOpacity, tol)
		assertEquals(0.25f, worlds[DeformerId("C")]!!.accumulatedOpacity, tol) // 0.5 * 0.5
	}

	@Test
	fun nestedMultiplyColorComposesComponentwise() {
		val parent = channelWarp("P", null, 1f, multiplyColor = ColorRgb(0.5f, 1f, 0.25f))
		val child = channelWarp("C", "P", 1f, multiplyColor = ColorRgb(0.5f, 0.5f, 1f))
		val accumulated = buildDeformerWorlds(listOf(parent, child), values(paramA to 0f))[DeformerId("C")]!!
		assertEquals(0.25f, accumulated.accumulatedMultiplyColor.red, tol) // 0.5 * 0.5
		assertEquals(0.5f, accumulated.accumulatedMultiplyColor.green, tol) // 0.5 * 1
		assertEquals(0.25f, accumulated.accumulatedMultiplyColor.blue, tol) // 1 * 0.25
	}

	@Test
	fun nestedScreenColorComposesWithTheScreenOperator() {
		// Screen is a + b - a*b, NOT a product: two half-screens make 0.75, not 0.25.
		val parent = channelWarp("P", null, 1f, screenColor = ColorRgb(0.5f, 0f, 1f))
		val child = channelWarp("C", "P", 1f, screenColor = ColorRgb(0.5f, 0.25f, 0.5f))
		val accumulated = buildDeformerWorlds(listOf(parent, child), values(paramA to 0f))[DeformerId("C")]!!
		assertEquals(0.75f, accumulated.accumulatedScreenColor.red, tol) // 0.5+0.5-0.25
		assertEquals(0.25f, accumulated.accumulatedScreenColor.green, tol) // 0.25+0-0
		assertEquals(1f, accumulated.accumulatedScreenColor.blue, tol) // screening with white saturates
	}

	@Test
	fun identityChannelsLeaveADeepChainUntouched() {
		// The common case: nothing authored, so a three-deep chain must not drift off the identities.
		val worlds =
			buildDeformerWorlds(
				listOf(channelWarp("A", null, 1f), channelWarp("B", "A", 1f), channelWarp("C", "B", 1f)),
				values(paramA to 0f),
			)
		val deepest = worlds[DeformerId("C")]!!
		assertEquals(1f, deepest.accumulatedOpacity, tol)
		assertEquals(ColorRgb.MultiplyIdentity, deepest.accumulatedMultiplyColor)
		assertEquals(ColorRgb.ScreenIdentity, deepest.accumulatedScreenColor)
	}

	@Test
	fun channelsBlendAcrossKeysRatherThanSnapping() {
		// A two-key axis keyed 1 -> 0: at the midpoint the subtree must be half faded, not fully on or
		// fully off. Snapping to the nearest key was a real bug in the reference implementation.
		val fadeGrid =
			KeyformGrid(
				listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), WarpForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 1f)),
					KeyformCell(intArrayOf(1), WarpForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 0f)),
				),
			)
		val fading = Deformer.Warp(DeformerId("F"), "F", null, null, 1, 1, true, fadeGrid)
		val atMidpoint = buildDeformerWorlds(listOf(fading), values(paramA to 0.5f))[DeformerId("F")]!!
		assertEquals(0.5f, atMidpoint.accumulatedOpacity, tol)
	}
}
