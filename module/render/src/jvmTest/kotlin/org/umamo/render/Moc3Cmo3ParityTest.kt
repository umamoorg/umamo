package org.umamo.render

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.ingest.Moc3Import
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// CMO3 stores keyform coordinates lightly quantized (dyadic fractions, ~1e-4) where the moc keeps
// full precision, so parity is bounded, not exact.  Canvas-pixel values agree within a pixel: the
// quantization amplifies through a warp lattice spanning thousands of pixels (corpus max observed
// 0.68px over 30k vertices; a wrong space conversion is off by hundreds).  Warp-lattice (u, v)
// values compare pre-amplification, within ~1e-4 of the unit lattice.
private const val CANVAS_TOLERANCE_PX = 1.0f
private const val LATTICE_TOLERANCE = 0.001f
private const val ANGLE_TOLERANCE_DEGREES = 0.05f

/**
 * Imports the same model from its `.cmo3` source and its baked `.moc3` (with the rest-mesh post-pass
 * the document loader applies) and asserts the two `PuppetModel`s agree - the gate that pins the MOC3
 * coordinate conversion (CanvasInfo root transform, rotation-local scaling, the rotation angle sign,
 * and the canvas-space rest-mesh rewrite) against the CMO3 numbers, which the differential-oracle
 * test already validates end-to-end.
 *
 * Gated on BOTH `-Dcmo3.sample` and `-Dmoc3.sample` naming the same model (the build supplies the
 * local corpus Erica for both by default).  Comparison runs over the drawable-id intersection: the
 * bake drops sketch ("Guide Image") parts and their drawables, so the sets are not equal.
 */
class Moc3Cmo3ParityTest {
	private val cmo3Sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }
	private val moc3Sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun mocImportMatchesCmo3Import() {
		val cmo3File = cmo3Sample
		val moc3File = moc3Sample
		if (cmo3File == null || moc3File == null) {
			println("cmo3.sample/moc3.sample not present; skipping parity test")
			return
		}
		val cmo3Root = Cmo3.read(cmo3File).root as? CModelSource ?: error("root is not a CModelSource")
		// The rest-mesh comparison normalizes BOTH sides through the same default-pose rewrite: CMO3's
		// stored base is authored editing geometry that may drift a few pixels from the evaluated default
		// pose (a moc has no way to recover the authored value), so comparing raw cmo3 bases against the
		// moc's evaluated ones would measure that drift, not the conversion under test.
		val fromCmo3 = restMeshesToCanvasSpace(Cmo3Import.fromModelSource(cmo3Root))
		val fromMoc3 =
			restMeshesToCanvasSpace(
				Moc3Import.fromMocDocument(Moc3.decode(moc3File.readBytes()), siblingDisplayInfo(moc3File)),
			)

		// Canvas and world origin agree (CanvasInfo origin == CMO3 canvas center).
		assertEquals(fromCmo3.canvasWidth, fromMoc3.canvasWidth, "canvas width")
		assertEquals(fromCmo3.canvasHeight, fromMoc3.canvasHeight, "canvas height")
		assertEquals(fromCmo3.worldOriginX, fromMoc3.worldOriginX, "world origin x")
		assertEquals(fromCmo3.worldOriginY, fromMoc3.worldOriginY, "world origin y")

		// Parameters: identical axes (the bake preserves every parameter).
		val cmo3Parameters = fromCmo3.parameters.associateBy { it.id }
		for (mocParameter in fromMoc3.parameters) {
			val cmo3Parameter = cmo3Parameters[mocParameter.id] ?: error("moc parameter ${mocParameter.id.raw} not in cmo3")
			assertEquals(cmo3Parameter.min, mocParameter.min, "min of ${mocParameter.id.raw}")
			assertEquals(cmo3Parameter.max, mocParameter.max, "max of ${mocParameter.id.raw}")
			assertEquals(cmo3Parameter.default, mocParameter.default, "default of ${mocParameter.id.raw}")
		}
		assertEquals(fromCmo3.parameterLinks, fromMoc3.parameterLinks, "parameter links")

		val cmo3Drawables = fromCmo3.drawables.associateBy { it.id }
		val moc3Drawables = fromMoc3.drawables.associateBy { it.id }
		val sharedIds = cmo3Drawables.keys.intersect(moc3Drawables.keys)
		println("[Umamo][parity] drawables cmo3=${cmo3Drawables.size} moc3=${moc3Drawables.size} shared=${sharedIds.size}")
		assertTrue(sharedIds.size >= moc3Drawables.size, "every moc3 drawable should exist in the cmo3")

		// Keyform-space tolerance per drawable: warp children live on the unit lattice, everything else
		// in canvas pixels.
		val moc3DeformerById = fromMoc3.deformers.associateBy { it.id }

		fun keyformTolerance(parentDeformerId: DeformerId?): Float =
			if (parentDeformerId != null && moc3DeformerById[parentDeformerId] is Deformer.Warp) LATTICE_TOLERANCE else CANVAS_TOLERANCE_PX

		var comparedVertices = 0
		var maxRestDelta = 0f
		val restOffenders = ArrayList<String>()
		for (drawableId in sharedIds) {
			val cmo3Drawable = cmo3Drawables.getValue(drawableId)
			val moc3Drawable = moc3Drawables.getValue(drawableId)
			assertEquals(cmo3Drawable.blendMode, moc3Drawable.blendMode, "blend mode of ${drawableId.raw}")
			assertEquals(cmo3Drawable.maskedBy.toSet(), moc3Drawable.maskedBy.toSet(), "masks of ${drawableId.raw}")
			assertEquals(cmo3Drawable.invertMask, moc3Drawable.invertMask, "invert mask of ${drawableId.raw}")

			val cmo3Mesh = cmo3Drawable.mesh ?: continue
			val moc3Mesh = moc3Drawable.mesh ?: error("moc3 ${drawableId.raw} has no mesh but the cmo3 does")
			assertEquals(cmo3Mesh.vertexCount, moc3Mesh.vertexCount, "vertex count of ${drawableId.raw}")
			assertTrue(cmo3Mesh.indices.contentEquals(moc3Mesh.indices), "triangle indices of ${drawableId.raw}")
			// UVs are static in both formats and untouched by the coordinate conversion.
			assertTrue(
				floatsClose(cmo3Mesh.uvs, moc3Mesh.uvs, 0.0005f),
				"uvs of ${drawableId.raw}" + flipDiagnostic(cmo3Mesh.uvs, moc3Mesh.uvs),
			)
			// Rest positions: canvas space on both sides (the moc side through the default-pose rewrite),
			// so this pins the whole conversion + cascade path.  Collected first (worst offender per
			// drawable) so a failure reports the model-wide picture, not the first vertex hit.
			assertEquals(cmo3Mesh.positions.size, moc3Mesh.positions.size, "position array of ${drawableId.raw}")
			var drawableWorstDelta = 0f
			var drawableWorstIndex = -1
			for (coordIndex in cmo3Mesh.positions.indices) {
				val delta = abs(cmo3Mesh.positions[coordIndex] - moc3Mesh.positions[coordIndex])
				if (delta > drawableWorstDelta) {
					drawableWorstDelta = delta
					drawableWorstIndex = coordIndex
				}
			}
			if (drawableWorstDelta > maxRestDelta) {
				maxRestDelta = drawableWorstDelta
			}
			if (drawableWorstDelta > CANVAS_TOLERANCE_PX) {
				restOffenders.add(
					"${drawableId.raw}[$drawableWorstIndex] delta=$drawableWorstDelta " +
						"cmo3=${cmo3Mesh.positions[drawableWorstIndex]} moc3=${moc3Mesh.positions[drawableWorstIndex]}",
				)
			}
			comparedVertices += cmo3Mesh.vertexCount

			compareMeshKeyforms(
				drawableId,
				cmo3Drawable.keyforms,
				cmo3Mesh.positions,
				moc3Drawable.keyforms,
				moc3Mesh.positions,
				keyformTolerance(moc3Drawable.parentDeformerId),
			)
		}
		println("[Umamo][parity] compared $comparedVertices rest vertices, max delta $maxRestDelta px")
		assertTrue(
			restOffenders.isEmpty(),
			"rest positions beyond ${CANVAS_TOLERANCE_PX}px on ${restOffenders.size} drawables:\n" +
				restOffenders.take(20).joinToString("\n"),
		)

		compareDeformerChains(fromCmo3, fromMoc3, sharedIds)
		compareRenderOrder(fromCmo3, fromMoc3, sharedIds)
	}

	/**
	 * Compares two mesh keyform grids cell by cell as parent-space absolutes (base + delta), matching
	 * cells across the grids by their axis coordinate.  Skips silently when either side is unkeyed.
	 *
	 * @param DrawableId   drawableId The drawable under comparison (for failure messages).
	 * @param KeyformGrid? cmo3Grid   The CMO3 grid (deltas vs [cmo3Base]).
	 * @param FloatArray   cmo3Base   The CMO3 rest positions.
	 * @param KeyformGrid? moc3Grid   The MOC3 grid (deltas vs [moc3Base]).
	 * @param FloatArray   moc3Base   The MOC3 rest positions.
	 * @param Float        tolerance  The per-coordinate tolerance in the drawable's parent space.
	 */
	private fun compareMeshKeyforms(
		drawableId: DrawableId,
		cmo3Grid: KeyformGrid<MeshForm>?,
		cmo3Base: FloatArray,
		moc3Grid: KeyformGrid<MeshForm>?,
		moc3Base: FloatArray,
		tolerance: Float,
	) {
		if (cmo3Grid == null || moc3Grid == null) {
			return
		}
		val cmo3Axes = cmo3Grid.axes.map { axis -> axis.parameterId to axis.keys.toList() }
		val moc3Axes = moc3Grid.axes.map { axis -> axis.parameterId to axis.keys.toList() }
		assertEquals(cmo3Axes, moc3Axes, "keyform axes of ${drawableId.raw}")
		val moc3Cells = moc3Grid.cells.associateBy { cell -> cell.coordinate.toList() }
		for (cmo3Cell in cmo3Grid.cells) {
			val moc3Cell = moc3Cells[cmo3Cell.coordinate.toList()] ?: error("moc3 ${drawableId.raw} misses cell ${cmo3Cell.coordinate.toList()}")
			assertEquals(cmo3Cell.form.drawOrder, moc3Cell.form.drawOrder, "draw order of ${drawableId.raw}@${cmo3Cell.coordinate.toList()}")
			assertTrue(
				abs(cmo3Cell.form.opacity - moc3Cell.form.opacity) <= 0.001f,
				"opacity of ${drawableId.raw}@${cmo3Cell.coordinate.toList()}",
			)
			val cmo3Deltas = cmo3Cell.form.positionDeltas
			val moc3Deltas = moc3Cell.form.positionDeltas
			assertEquals(cmo3Deltas.size, moc3Deltas.size, "delta array of ${drawableId.raw}")
			for (coordIndex in cmo3Deltas.indices) {
				val cmo3Absolute = cmo3Base[coordIndex] + cmo3Deltas[coordIndex]
				val moc3Absolute = moc3Base[coordIndex] + moc3Deltas[coordIndex]
				assertTrue(
					abs(cmo3Absolute - moc3Absolute) <= tolerance,
					"keyform position of ${drawableId.raw}@${cmo3Cell.coordinate.toList()}[$coordIndex]: " +
						"cmo3=$cmo3Absolute moc3=$moc3Absolute",
				)
			}
		}
	}

	/**
	 * Compares the deformer chains above each shared drawable.  MOC3 deformers carry synthesized ids,
	 * so they are matched positionally along the parent chain from each drawable, then compared by
	 * kind, lattice size, keyform axes, and keyform values - warp control points in the parent's space
	 * (lattice for a nested warp, canvas pixels at the root), rotation origin/scale/reflection
	 * likewise, and the total angle within [ANGLE_TOLERANCE_DEGREES] (this is what pins the angle-sign
	 * conversion).
	 *
	 * @param PuppetModel     fromCmo3  The CMO3-imported model.
	 * @param PuppetModel     fromMoc3  The MOC3-imported model.
	 * @param Set<DrawableId> sharedIds The drawable-id intersection.
	 */
	private fun compareDeformerChains(fromCmo3: PuppetModel, fromMoc3: PuppetModel, sharedIds: Set<DrawableId>) {
		val cmo3Deformers = fromCmo3.deformers.associateBy { it.id }
		val moc3Deformers = fromMoc3.deformers.associateBy { it.id }
		val cmo3Drawables = fromCmo3.drawables.associateBy { it.id }
		val moc3Drawables = fromMoc3.drawables.associateBy { it.id }
		val comparedPairs = HashSet<Pair<DeformerId, DeformerId>>()
		var maxAngleDelta = 0f
		for (drawableId in sharedIds) {
			var cmo3Parent = cmo3Drawables.getValue(drawableId).parentDeformerId
			var moc3Parent = moc3Drawables.getValue(drawableId).parentDeformerId
			while (cmo3Parent != null || moc3Parent != null) {
				assertTrue(
					cmo3Parent != null && moc3Parent != null,
					"deformer chain of ${drawableId.raw} diverges: cmo3=${cmo3Parent?.raw} moc3=${moc3Parent?.raw}",
				)
				val cmo3Deformer = cmo3Deformers.getValue(cmo3Parent!!)
				val moc3Deformer = moc3Deformers.getValue(moc3Parent!!)
				if (!comparedPairs.add(cmo3Deformer.id to moc3Deformer.id)) {
					break
				}
				// A nested deformer's own keyform space is ITS parent's space.
				val spaceTolerance =
					if (moc3Deformer.parent != null && moc3Deformers[moc3Deformer.parent] is Deformer.Warp) LATTICE_TOLERANCE else CANVAS_TOLERANCE_PX
				when (cmo3Deformer) {
					is Deformer.Warp -> {
						val mocWarp = moc3Deformer as? Deformer.Warp ?: error("kind mismatch above ${drawableId.raw}")
						assertEquals(cmo3Deformer.rows, mocWarp.rows, "warp rows above ${drawableId.raw}")
						assertEquals(cmo3Deformer.columns, mocWarp.columns, "warp columns above ${drawableId.raw}")
						assertEquals(cmo3Deformer.isQuadTransform, mocWarp.isQuadTransform, "warp mode above ${drawableId.raw}")
						compareWarpGrids(drawableId, cmo3Deformer.keyforms, mocWarp.keyforms, spaceTolerance)
					}
					is Deformer.Rotation -> {
						val mocRotation = moc3Deformer as? Deformer.Rotation ?: error("kind mismatch above ${drawableId.raw}")
						val angleDelta = compareRotationGrids(drawableId, cmo3Deformer, mocRotation, spaceTolerance)
						if (angleDelta > maxAngleDelta) {
							maxAngleDelta = angleDelta
						}
					}
				}
				cmo3Parent = cmo3Deformer.parent
				moc3Parent = moc3Deformer.parent
			}
		}
		println("[Umamo][parity] compared ${comparedPairs.size} deformer pairs, max angle delta $maxAngleDelta deg")
	}

	/**
	 * Compares two warp keyform grids cell by cell (control points in the warp's parent space).
	 *
	 * @param DrawableId   drawableId The drawable whose chain is under comparison (for messages).
	 * @param KeyformGrid? cmo3Grid   The CMO3 warp grid.
	 * @param KeyformGrid? moc3Grid   The MOC3 warp grid.
	 * @param Float        tolerance  The per-coordinate tolerance in the warp's parent space.
	 */
	private fun compareWarpGrids(
		drawableId: DrawableId,
		cmo3Grid: KeyformGrid<org.umamo.runtime.model.WarpForm>?,
		moc3Grid: KeyformGrid<org.umamo.runtime.model.WarpForm>?,
		tolerance: Float,
	) {
		if (cmo3Grid == null || moc3Grid == null) {
			return
		}
		// Hard-gate the grid shapes like compareMeshKeyforms does: silently skipping unmatched cells
		// would let a systematic axis-order or coordinate regression pass this comparison vacuously.
		assertEquals(
			cmo3Grid.axes.map { axis -> axis.parameterId to axis.keys.toList() },
			moc3Grid.axes.map { axis -> axis.parameterId to axis.keys.toList() },
			"warp keyform axes above ${drawableId.raw}",
		)
		val moc3Cells = moc3Grid.cells.associateBy { cell -> cell.coordinate.toList() }
		for (cmo3Cell in cmo3Grid.cells) {
			val moc3Cell =
				moc3Cells[cmo3Cell.coordinate.toList()]
					?: error("moc3 warp above ${drawableId.raw} misses cell ${cmo3Cell.coordinate.toList()}")
			val cmo3Points = cmo3Cell.form.controlPoints
			val moc3Points = moc3Cell.form.controlPoints
			assertEquals(cmo3Points.size, moc3Points.size, "warp control-point count above ${drawableId.raw}")
			for (coordIndex in cmo3Points.indices) {
				assertTrue(
					abs(cmo3Points[coordIndex] - moc3Points[coordIndex]) <= tolerance,
					"warp control point above ${drawableId.raw}@${cmo3Cell.coordinate.toList()}[$coordIndex]: " +
						"cmo3=${cmo3Points[coordIndex]} moc3=${moc3Points[coordIndex]}",
				)
			}
		}
	}

	/**
	 * Compares two rotation keyform grids cell by cell and returns the largest total-angle delta seen.
	 * CMO3 keyform angles are absolute where the moc splits baseAngle + delta, so totals are compared.
	 *
	 * @param DrawableId        drawableId   The drawable whose chain is under comparison (for messages).
	 * @param Deformer.Rotation cmo3Rotation The CMO3 rotation deformer.
	 * @param Deformer.Rotation moc3Rotation The MOC3 rotation deformer.
	 * @param Float             tolerance    The origin tolerance in the rotation's parent space.
	 * @return Float The largest absolute total-angle difference in degrees.
	 */
	private fun compareRotationGrids(
		drawableId: DrawableId,
		cmo3Rotation: Deformer.Rotation,
		moc3Rotation: Deformer.Rotation,
		tolerance: Float,
	): Float {
		val cmo3Grid = cmo3Rotation.keyforms ?: return 0f
		val moc3Grid = moc3Rotation.keyforms ?: return 0f
		// Hard-gate the grid shapes like compareMeshKeyforms does: silently skipping unmatched cells
		// would report "max angle delta 0.0" for a systematically-wrong conversion.
		assertEquals(
			cmo3Grid.axes.map { axis -> axis.parameterId to axis.keys.toList() },
			moc3Grid.axes.map { axis -> axis.parameterId to axis.keys.toList() },
			"rotation keyform axes above ${drawableId.raw}",
		)
		val moc3Cells = moc3Grid.cells.associateBy { cell -> cell.coordinate.toList() }
		var maxAngleDelta = 0f
		for (cmo3Cell in cmo3Grid.cells) {
			val moc3Cell =
				moc3Cells[cmo3Cell.coordinate.toList()]
					?: error("moc3 rotation above ${drawableId.raw} misses cell ${cmo3Cell.coordinate.toList()}")
			assertTrue(
				abs(cmo3Cell.form.originX - moc3Cell.form.originX) <= tolerance &&
					abs(cmo3Cell.form.originY - moc3Cell.form.originY) <= tolerance,
				"rotation origin above ${drawableId.raw}@${cmo3Cell.coordinate.toList()}: " +
					"cmo3=(${cmo3Cell.form.originX}, ${cmo3Cell.form.originY}) " +
					"moc3=(${moc3Cell.form.originX}, ${moc3Cell.form.originY})",
			)
			val cmo3TotalAngle = cmo3Rotation.baseAngle + cmo3Cell.form.angle
			val moc3TotalAngle = moc3Rotation.baseAngle + moc3Cell.form.angle
			val angleDelta = abs(cmo3TotalAngle - moc3TotalAngle)
			if (angleDelta > maxAngleDelta) {
				maxAngleDelta = angleDelta
			}
			assertTrue(
				angleDelta <= ANGLE_TOLERANCE_DEGREES,
				"rotation angle above ${drawableId.raw}@${cmo3Cell.coordinate.toList()}: " +
					"cmo3=$cmo3TotalAngle moc3=$moc3TotalAngle (sign convention?)",
			)
			assertTrue(abs(cmo3Cell.form.scale - moc3Cell.form.scale) <= 0.001f, "rotation scale above ${drawableId.raw}")
			assertEquals(cmo3Cell.form.flipX, moc3Cell.form.flipX, "rotation flipX above ${drawableId.raw}")
			assertEquals(cmo3Cell.form.flipY, moc3Cell.form.flipY, "rotation flipY above ${drawableId.raw}")
		}
		return maxAngleDelta
	}

	/**
	 * Compares the render-order leaf sequence: the MOC3 import takes the baked tree verbatim, the CMO3
	 * import derives it from the org tree; restricted to the shared drawables the two must agree.
	 *
	 * @param PuppetModel     fromCmo3  The CMO3-imported model.
	 * @param PuppetModel     fromMoc3  The MOC3-imported model.
	 * @param Set<DrawableId> sharedIds The drawable-id intersection.
	 */
	private fun compareRenderOrder(fromCmo3: PuppetModel, fromMoc3: PuppetModel, sharedIds: Set<DrawableId>) {
		fun leaves(node: RenderNode, into: ArrayList<DrawableId>) {
			when (node) {
				is RenderDrawable -> into.add(node.id)
				is RenderGroup -> node.children.forEach { child -> leaves(child, into) }
			}
		}

		val cmo3Leaves = ArrayList<DrawableId>().also { leaves(fromCmo3.renderRoot, it) }.filter { it in sharedIds }
		val moc3Leaves = ArrayList<DrawableId>().also { leaves(fromMoc3.renderRoot, it) }.filter { it in sharedIds }
		assertEquals(
			cmo3Leaves.map { it.raw },
			moc3Leaves.map { it.raw },
			"render-order leaf sequence over shared drawables",
		)
	}

	/**
	 * Whether two float arrays agree element-wise within [tolerance].
	 *
	 * @param FloatArray left      One array.
	 * @param FloatArray right     The other.
	 * @param Float      tolerance The per-element tolerance.
	 * @return Boolean True when equal within tolerance.
	 */
	private fun floatsClose(left: FloatArray, right: FloatArray, tolerance: Float): Boolean {
		if (left.size != right.size) {
			return false
		}
		for (valueIndex in left.indices) {
			if (abs(left[valueIndex] - right[valueIndex]) > tolerance) {
				return false
			}
		}
		return true
	}

	/**
	 * A diagnostic suffix for a UV mismatch: reports whether flipping V (v -> 1 - v) would make the
	 * arrays agree, so a failed assertion names the orientation problem directly.
	 *
	 * @param FloatArray cmo3Uvs The CMO3 UVs.
	 * @param FloatArray moc3Uvs The MOC3 UVs.
	 * @return String The diagnostic, or an empty string when nothing conclusive can be said.
	 */
	private fun flipDiagnostic(cmo3Uvs: FloatArray, moc3Uvs: FloatArray): String {
		if (cmo3Uvs.size != moc3Uvs.size) {
			return " (size mismatch)"
		}
		val flipped =
			FloatArray(moc3Uvs.size) { coordIndex ->
				if (coordIndex % 2 == 1) 1f - moc3Uvs[coordIndex] else moc3Uvs[coordIndex]
			}
		return if (floatsClose(cmo3Uvs, flipped, 0.0005f)) " (moc3 V is flipped: import needs v -> 1 - v)" else ""
	}

	/**
	 * Reads the sample's sibling `<basename>.cdi3.json`, or null when absent.
	 *
	 * @param File mocFile The `.moc3` sample.
	 * @return Cdi3Json? The parsed display info, or null.
	 */
	private fun siblingDisplayInfo(mocFile: File): Cdi3Json? {
		// Case-insensitive strip, matching the document loader's basename rule.
		val basename = if (mocFile.name.endsWith(".moc3", ignoreCase = true)) mocFile.name.dropLast(".moc3".length) else mocFile.name
		val cdi3File = mocFile.parentFile.resolve("$basename.cdi3.json")
		if (!cdi3File.isFile) {
			return null
		}
		return Moc3.readCdi3(cdi3File.readText())
	}
}
