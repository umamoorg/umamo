package org.umamo.ui.transform

import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.booleanValue
import org.umamo.edit.floatValue
import org.umamo.edit.intValue
import org.umamo.edit.mesh.LATTICE_DIVISION_MAX
import org.umamo.edit.structure.WARP_BIRTH_DIVISIONS
import org.umamo.edit.structure.WARP_BIRTH_MARGIN_PERCENT
import org.umamo.edit.structure.WarpCreationParameterKeys
import org.umamo.edit.structure.commitWarpCreation
import org.umamo.edit.structure.freshDeformerId
import org.umamo.edit.structure.freshDeformerName
import org.umamo.edit.structure.warpCreationParameters
import org.umamo.render.ChildrenRehomed
import org.umamo.render.RehomedChildren
import org.umamo.render.childrenRehomed
import org.umamo.render.eval.parentSpaceAt
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.deformerChain
import org.umamo.runtime.model.partByDrawable

/*
 * Create Warp Deformer: a warp wrapped around the selection, born with one keyform, and the selection re-parented
 * under it without moving.  The lattice is the selection's world bounds plus a margin, mapped into the new warp's
 * parent's space, and the wrapped items are kept in place by childrenRehomed - both :render's, which is why this sits
 * in :ui beside the other transform lowerings.
 */

/** The display name and id base a created warp takes, suffixed ".001", ".002", ... when taken. */
private const val WARP_BASE_NAME = "Warp"

/**
 * How a warp is born.
 *
 * @property Int rows The cells along v.
 * @property Int columns The cells along u.
 * @property Float marginPercent The margin around the selection, in percent of its larger side.
 * @property Boolean quad Whether it maps bilinearly rather than by triangle split.
 */
internal data class WarpBirth(
	val rows: Int = WARP_BIRTH_DIVISIONS,
	val columns: Int = WARP_BIRTH_DIVISIONS,
	val marginPercent: Float = WARP_BIRTH_MARGIN_PERCENT,
	val quad: Boolean = true,
)

/**
 * A warp created around a selection, lowered onto the model.
 *
 * @property PuppetModel model The model with the warp created and the wrapped items kept in place under it.
 * @property DeformerId warpId The new warp.
 * @property List<DrawableId> wrappedDrawables The drawables re-parented under it.
 * @property List<DeformerId> wrappedDeformers The deformers re-parented under it.
 * @property ChildrenRehomed rehome How the wrapped items were kept in place, and which could not be.
 */
internal class WarpCreation(
	val model: PuppetModel,
	val warpId: DeformerId,
	val wrappedDrawables: List<DrawableId>,
	val wrappedDeformers: List<DeformerId>,
	val rehome: ChildrenRehomed,
)

/**
 * The outermost wrappable items of [targets]: drawables with a mesh and deformers, without any item that a selected
 * deformer already carries (it follows its ancestor into the new warp).  Parts wrap nothing.
 *
 * @param PuppetModel model The model.
 * @param Collection<SelectionTarget> targets The selection.
 * @return Pair The drawables and the deformers, in model order.
 */
internal fun wrappableTargets(model: PuppetModel, targets: Collection<SelectionTarget>): Pair<List<DrawableId>, List<DeformerId>> {
	val selectedDeformers = targets.filterIsInstance<SelectionTarget.Deformer>().map { target -> target.id }.filter { deformerId -> model.deformerById.containsKey(deformerId) }.toSet()
	val selectedDrawables = targets.filterIsInstance<SelectionTarget.Drawable>().map { target -> target.id }.toSet()
	// A deformer's own entry heads its chain; only its ancestors decide whether a selected one carries it.
	val carried = { parentId: DeformerId? -> parentId != null && model.deformerChain(parentId).any { ancestor -> ancestor.id in selectedDeformers } }
	val drawables = model.drawables.filter { drawable -> drawable.id in selectedDrawables && drawable.mesh != null && !carried(drawable.parentDeformerId) }.map { drawable -> drawable.id }
	val deformers = model.deformers.filter { deformer -> deformer.id in selectedDeformers && !carried(deformer.parent) }.map { deformer -> deformer.id }

	return drawables to deformers
}

/**
 * Lowers Create Warp Deformer onto [model]: a warp around the outermost wrappable items of [targets] ([wrappableTargets])
 * with those items re-parented under it, kept where they rest.
 *
 *  - Its parent is the nearest deformer every wrapped item sits under (their common ancestor), or the root.
 *  - Its lattice is the world box of everything the items carry at rest - drawables' meshes, warps' lattices, and a
 *    rotation's art (its pivot when it carries none) - grown by [WarpBirth.marginPercent] of the box's larger side on
 *    every edge, laid out as a (rows + 1) x (columns + 1) grid with row 0 on top, and mapped point by point into the
 *    parent's space: exact for the root and a rotation, a curved lattice under a warp.
 *  - It is born with one keyform (a grid with no axis) holding that lattice, named "Warp" (suffixed when taken), in
 *    the part of [active] (or of the first wrapped item), just before the first wrapped deformer in the list.
 *
 * @param PuppetModel model The model.
 * @param Collection<SelectionTarget> targets The selection.
 * @param SelectionTarget? active The active item, whose part the warp joins.
 * @param WarpBirth birth How the warp is born.
 * @return WarpCreation? The created warp, or null when nothing wraps or nothing shows at rest.
 */
internal fun warpCreationOf(model: PuppetModel, targets: Collection<SelectionTarget>, active: SelectionTarget?, birth: WarpBirth): WarpCreation? {
	val (drawables, deformers) = wrappableTargets(model, targets)

	if (drawables.isEmpty() && deformers.isEmpty()) {
		return null
	}

	val parentId = commonAncestor(model, drawables.map { drawableId -> model.drawables.first { drawable -> drawable.id == drawableId }.parentDeformerId } + deformers.map { deformerId -> model.deformerById.getValue(deformerId).parent })
	val world = carriedWorldPoints(model, drawables, deformers)

	if (world.isEmpty()) {
		return null
	}

	val parentSpace = parentSpaceAt(model, parentId) ?: return null
	val rows = birth.rows.coerceIn(1, LATTICE_DIVISION_MAX)
	val columns = birth.columns.coerceIn(1, LATTICE_DIVISION_MAX)
	val latticeWorld = boxLattice(world, rows, columns, birth.marginPercent)
	val pointCount = latticeWorld.size / 2
	// A lattice parent's inverse starts from its middle; the root's and a rotation's are closed-form.
	val seed = if (parentSpace.isLattice) FloatArray(latticeWorld.size) { LATTICE_MIDDLE } else latticeWorld
	val latticeLocal = parentSpace.worldToLocalLinearized(latticeWorld, seed, (0 until pointCount).toSet())
	val warpId = model.freshDeformerId(WARP_BASE_NAME)
	val warp =
		Deformer.Warp(
			id = warpId,
			name = model.freshDeformerName(WARP_BASE_NAME),
			parent = parentId,
			partId = partOf(model, active) ?: partOf(model, drawables.firstOrNull()?.let { drawableId -> SelectionTarget.Drawable(drawableId) } ?: SelectionTarget.Deformer(deformers.first())),
			rows = rows,
			columns = columns,
			isQuadTransform = birth.quad,
			geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), WarpLatticeForm(latticeLocal)))),
		)
	val insertAt = model.deformers.indexOfFirst { deformer -> deformer.id in deformers }.takeIf { index -> index >= 0 } ?: model.deformers.size
	val withWarp = model.copy(deformers = model.deformers.toMutableList().apply { add(insertAt, warp) })
	val reparented =
		withWarp.copy(
			drawables = withWarp.drawables.map { drawable -> if (drawable.id in drawables) drawable.copy(parentDeformerId = warpId) else drawable },
			deformers =
				withWarp.deformers.map { deformer ->
					when {
						deformer.id !in deformers -> deformer
						deformer is Deformer.Warp -> deformer.copy(parent = warpId)
						deformer is Deformer.Rotation -> deformer.copy(parent = warpId)
						else -> deformer
					}
				},
		)
	val rehome = childrenRehomed(model, reparented, RehomedChildren(drawables, deformers))

	return WarpCreation(rehome.model, warpId, drawables, deformers, rehome)
}

/**
 * Creates a warp around the session's selection as one undo step ([warpCreationOf]) - born with
 * [WARP_BIRTH_DIVISIONS] cells each way, a [WARP_BIRTH_MARGIN_PERCENT] margin, and bilinear mapping when the runtime
 * target supports it - selects it, and registers it on the operation strip in [areaId] with its Rows, Columns,
 * Margin, and (when the target allows a choice) Bilinear.  A rerun creates the warp again from the step's base and
 * selection.  Refused outside Object mode, or with nothing to wrap.
 *
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.createWarpAdjustable(areaId: String?) {
	if (mode.value != EditorMode.Object) {
		return
	}

	val quadAllowed = model.value.runtimeTarget.supports(RuntimeFeature.WarpQuadTransform)
	val birth = WarpBirth(quad = quadAllowed)
	val chosen = selection.value
	val creation = warpCreationOf(model.value, chosen.targets, chosen.active, birth) ?: return
	val before = model.value

	if (!commitWarpCreation(DeformerChange.Create(creation.warpId, creation.wrappedDrawables, creation.wrappedDeformers), creation.model, creation.warpId)) {
		return
	}

	reportRehome(before, creation.rehome, "created ${creation.warpId.raw}")
	registerAdjustableOperation(model.value, areaId, warpCreationParameters(birth.rows, birth.columns, birth.marginPercent, if (quadAllowed) birth.quad else null)) { record ->
		val base = record.baseSnapshot.model
		val baseSelection: Selection = record.baseSnapshot.selection
		val rerunBirth =
			WarpBirth(
				rows = record.parameters.intValue(WarpCreationParameterKeys.ROWS, birth.rows),
				columns = record.parameters.intValue(WarpCreationParameterKeys.COLUMNS, birth.columns),
				marginPercent = record.parameters.floatValue(WarpCreationParameterKeys.MARGIN, birth.marginPercent),
				quad = quadAllowed && record.parameters.booleanValue(WarpCreationParameterKeys.QUAD, birth.quad),
			)
		val rerun = warpCreationOf(base, baseSelection.targets, baseSelection.active, rerunBirth) ?: return@registerAdjustableOperation

		if (amendLastCommit(record, rerun.model)) {
			reportRehome(base, rerun.rehome, "created ${rerun.warpId.raw}")
		}
	}
}

/**
 * Whether the session's selection has anything Create Warp Deformer would wrap, in Object mode.
 *
 * @return Boolean True when a warp can be created.
 */
internal fun EditorSession.canCreateWarp(): Boolean {
	if (mode.value != EditorMode.Object) {
		return false
	}

	val (drawables, deformers) = wrappableTargets(model.value, selection.value.targets)

	return drawables.isNotEmpty() || deformers.isNotEmpty()
}

/**
 * The nearest deformer every one of [parents] sits under - each parent itself or one of its ancestors - or null for
 * the root.
 *
 * @param PuppetModel model The model.
 * @param List<DeformerId?> parents The wrapped items' parents.
 * @return DeformerId? The common ancestor, or null.
 */
private fun commonAncestor(model: PuppetModel, parents: List<DeformerId?>): DeformerId? {
	// Each chain root first, so the common ancestor is the last entry of the longest shared prefix.
	val chains = parents.map { parentId -> parentId?.let { id -> model.deformerChain(id).map { deformer -> deformer.id }.reversed() }.orEmpty() }
	val shortest = chains.minOfOrNull { chain -> chain.size } ?: 0
	var shared: DeformerId? = null

	for (depth in 0 until shortest) {
		val candidate = chains.first()[depth]

		if (chains.any { chain -> chain[depth] != candidate }) {
			break
		}

		shared = candidate
	}

	return shared
}

/**
 * Every world point at rest of what [drawables] and [deformers] carry: the drawables' meshes, each warp's lattice,
 * and each rotation's art - every drawable under it - or its pivot when it carries none.
 *
 * @param PuppetModel model The model.
 * @param List<DrawableId> drawables The wrapped drawables.
 * @param List<DeformerId> deformers The wrapped deformers.
 * @return FloatArray The world points, interleaved x, z.
 */
private fun carriedWorldPoints(model: PuppetModel, drawables: List<DrawableId>, deformers: List<DeformerId>): FloatArray {
	val rotationArt =
		deformers
			.filter { deformerId -> model.deformerById[deformerId] is Deformer.Rotation }
			.flatMap { rotationId ->
				model.drawables.filter { drawable -> drawable.mesh != null && drawable.parentDeformerId?.let { parentId -> model.deformerChain(parentId).any { ancestor -> ancestor.id == rotationId } } == true }.map { drawable -> drawable.id }
			}
	val meshes = captureDrawableWorlds(model, emptyMap(), (drawables + rotationArt).distinct()).map { geometry -> geometry.world }
	val lattices = deformers.mapNotNull { deformerId -> if (model.deformerById[deformerId] is Deformer.Warp) warpLatticeAt(model, emptyMap(), deformerId)?.world else null }
	val bareRotations =
		deformers
			.filter { deformerId -> model.deformerById[deformerId] is Deformer.Rotation && rotationArt.none { drawableId -> model.drawables.first { drawable -> drawable.id == drawableId }.parentDeformerId?.let { parentId -> model.deformerChain(parentId).any { ancestor -> ancestor.id == deformerId } } == true } }
			.mapNotNull { rotationId -> parentSpaceAt(model, rotationId)?.localToWorld(floatArrayOf(0f, 0f)) }

	return (meshes + lattices + bareRotations).fold(FloatArray(0)) { all, points -> all + points }
}

/**
 * The (rows + 1) x (columns + 1) world points of the box around [world] grown by [marginPercent] of its larger side
 * on every edge, row-major with row 0 on top.
 *
 * @param FloatArray world The points to wrap, interleaved x, z.
 * @param Int rows The cells along v.
 * @param Int columns The cells along u.
 * @param Float marginPercent The margin, in percent of the box's larger side.
 * @return FloatArray The lattice points in world space, interleaved x, z.
 */
private fun boxLattice(world: FloatArray, rows: Int, columns: Int, marginPercent: Float): FloatArray {
	val bounds = WorldBox.of(world)
	// A box with no extent still needs room to hold something; one world unit stands in for its size.
	val larger = maxOf(bounds.maxX - bounds.minX, bounds.maxZ - bounds.minZ).takeIf { size -> size > 0f } ?: 1f
	val margin = larger * marginPercent.coerceAtLeast(0f) / 100f
	val left = bounds.minX - margin
	val top = bounds.maxZ + margin
	val width = bounds.maxX - bounds.minX + margin * 2f
	val height = bounds.maxZ - bounds.minZ + margin * 2f
	val lattice = FloatArray((rows + 1) * (columns + 1) * 2)

	for (row in 0..rows) {
		for (column in 0..columns) {
			val pointIndex = row * (columns + 1) + column
			lattice[pointIndex * 2] = left + width * column / columns
			lattice[pointIndex * 2 + 1] = top - height * row / rows
		}
	}

	return lattice
}

/**
 * The world-axis bounds of a point set.
 *
 * @property Float minX The left edge.
 * @property Float maxX The right edge.
 * @property Float minZ The bottom edge.
 * @property Float maxZ The top edge.
 */
private class WorldBox(val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float) {
	companion object {
		/**
		 * The bounds of [points].
		 *
		 * @param FloatArray points Interleaved x, z.
		 * @return WorldBox The bounds.
		 */
		fun of(points: FloatArray): WorldBox {
			var minX = Float.POSITIVE_INFINITY
			var maxX = Float.NEGATIVE_INFINITY
			var minZ = Float.POSITIVE_INFINITY
			var maxZ = Float.NEGATIVE_INFINITY

			for (pointIndex in 0 until points.size / 2) {
				minX = minOf(minX, points[pointIndex * 2])
				maxX = maxOf(maxX, points[pointIndex * 2])
				minZ = minOf(minZ, points[pointIndex * 2 + 1])
				maxZ = maxOf(maxZ, points[pointIndex * 2 + 1])
			}

			return WorldBox(minX, maxX, minZ, maxZ)
		}
	}
}

/**
 * The part [target] belongs to: a drawable's part in the org tree, a deformer's own part; null for none or a part.
 *
 * @param PuppetModel model The model.
 * @param SelectionTarget? target The item.
 * @return PartId? Its part.
 */
private fun partOf(model: PuppetModel, target: SelectionTarget?): PartId? =
	when (target) {
		is SelectionTarget.Drawable -> model.partByDrawable()[target.id]
		is SelectionTarget.Deformer -> model.deformerById[target.id]?.partId
		is SelectionTarget.Part, null -> null
	}

/** The normalized coordinate of a lattice's middle, the inverse's seed in a lattice parent. */
private const val LATTICE_MIDDLE = 0.5f