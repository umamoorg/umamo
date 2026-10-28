package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel

/**
 * Co-locates the canvas meshes of baked atlas twins before fresh-graph synthesis.
 *
 * A .moc3 bake packs mirrored duplicates as ONE atlas slot - the same page, uvs, and triangles -
 * sampled by drawables that the deformer chain shows at different places.  The MOC3 import derives
 * each canvas mesh from the default pose, so such a twin arrives with a canvas mesh at its displayed
 * place (a mirrored twin's is the exact mirror of its partner's).  The CMO3 model-image web cannot
 * carry that: a CModelImage has a single canvas placement, so a shared material at two placements
 * loses every twin but one in the editor's source-image mode.
 *
 * Official documents never contain the shape.  Their twins share ONE canvas mesh - every uv-identical
 * group across the corpus (160 of 160 over Erica, modelA-E, and miku, root-level twins included) has
 * bit-identical CArtMeshSource.positions - and the keyforms alone carry the twin to its displayed
 * place.  The canvas mesh is editing geometry: the lowering writes it to CArtMeshSource.positions and
 * GEditableMesh2.point, while every keyform is rebuilt from DrawableMesh.localPositions, so replacing
 * it changes nothing the editor renders.  This prepass restores the official shape: every further
 * placement of a shared slot takes the representative placement's canvas mesh, the slot then carries
 * a single placement, and the image chain shares one material over the bake's own pixels.
 */
internal object Cmo3TwinColocation {
	/** The co-located inputs for the fresh-graph pipeline. */
	internal class Result(
		val puppet: PuppetModel,
		/** The drawables whose canvas mesh was replaced by their slot representative's, for tests and reports. */
		val colocatedDrawableIds: List<String>,
	)

	/** One atlas slot shared by several drawables: page + the mesh's uv and index arrays. */
	private class SlotKey(
		val pageIndex: Int,
		private val uvs: FloatArray,
		private val indices: IntArray,
	) {
		override fun equals(other: Any?): Boolean =
			other is SlotKey &&
				pageIndex == other.pageIndex &&
				uvs.contentEquals(other.uvs) &&
				indices.contentEquals(other.indices)

		override fun hashCode(): Int {
			var result = pageIndex
			result = 31 * result + uvs.contentHashCode()
			result = 31 * result + indices.contentHashCode()

			return result
		}
	}

	/** The drawables sampling a slot from one canvas placement, with the fit that placement has. */
	private class Placement(val fit: CAffine) {
		val drawables = ArrayList<Drawable>()
	}

	/**
	 * Gives every further canvas placement of a shared atlas slot the representative placement's canvas
	 * mesh, so each slot reaches the image chain with a single placement.
	 *
	 * The representative is the slot's first placement, in drawable order, whose fit keeps orientation,
	 * so the shared material holds the art upright; when every placement mirrors, it is the first
	 * placement.  A drawable's keyform base, uvs, and indices are never touched.
	 *
	 * @param PuppetModel puppet The model to convert; mesh.positions is the canvas editable mesh.
	 * @param List        pages  The atlas pages, in model3 texture order; only their sizes are read.
	 * @param Map         pageIndexByDrawableId Each drawable id's atlas page index.
	 * @return Result The (possibly) co-located puppet and the drawables whose canvas mesh moved.
	 */
	fun colocate(
		puppet: PuppetModel,
		pages: List<Cmo3Conversion.AtlasPage>,
		pageIndexByDrawableId: Map<String, Int>,
	): Result {
		// Group drawables by slot, then by quantized placement within the slot.
		val placementsBySlot = LinkedHashMap<SlotKey, LinkedHashMap<List<Long>, Placement>>()

		for (drawable in puppet.drawables) {
			val pageIndex = pageIndexByDrawableId[drawable.id.raw] ?: continue
			val page = pages.getOrNull(pageIndex) ?: continue
			val mesh = drawable.mesh ?: continue
			Cmo3ImageChainBuilder.patchRectOf(mesh.uvs, page.width, page.height) ?: continue
			val fit = fitAtlasPageToCanvasTransform(mesh.uvs, mesh.positions, page.width, page.height)
			val placementKey =
				listOf(
					Math.round(fit.m00.toDouble() * 1024),
					Math.round(fit.m01.toDouble() * 1024),
					Math.round(fit.m10.toDouble() * 1024),
					Math.round(fit.m11.toDouble() * 1024),
					Math.round(fit.m02.toDouble()),
					Math.round(fit.m12.toDouble()),
				)
			placementsBySlot
				.getOrPut(SlotKey(pageIndex, mesh.uvs, mesh.indices)) { LinkedHashMap() }
				.getOrPut(placementKey) { Placement(fit) }
				.drawables
				.add(drawable)
		}

		// Every drawable of a non-representative placement takes the representative's canvas mesh.
		val canvasByDrawableId = HashMap<String, FloatArray>()
		val colocatedIds = ArrayList<String>()

		for (placements in placementsBySlot.values) {
			if (placements.size < 2) {
				continue
			}

			val representative =
				placements.values.firstOrNull { placement -> placement.fit.m00 * placement.fit.m11 - placement.fit.m01 * placement.fit.m10 > 0f }
					?: placements.values.first()
			val representativeCanvas = checkNotNull(representative.drawables.first().mesh) { "a grouped drawable has a mesh" }.positions

			for (placement in placements.values) {
				if (placement === representative) {
					continue
				}

				for (drawable in placement.drawables) {
					canvasByDrawableId[drawable.id.raw] = representativeCanvas
					colocatedIds.add(drawable.id.raw)
				}
			}
		}

		if (canvasByDrawableId.isEmpty()) {
			return Result(puppet, emptyList())
		}

		val colocatedDrawables =
			puppet.drawables.map { drawable ->
				val canvas = canvasByDrawableId[drawable.id.raw] ?: return@map drawable
				val mesh = drawable.mesh ?: return@map drawable

				drawable.copy(mesh = DrawableMesh(positions = canvas, localPositions = mesh.localPositions, uvs = mesh.uvs, indices = mesh.indices))
			}

		return Result(puppet.copy(drawables = colocatedDrawables), colocatedIds)
	}
}