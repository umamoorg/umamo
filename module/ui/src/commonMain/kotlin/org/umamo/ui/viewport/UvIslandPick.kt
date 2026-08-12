package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.DecodedImage
import org.umamo.render.ViewportCamera
import org.umamo.render.eval.paintOrder
import org.umamo.render.eval.renderOrder
import org.umamo.render.pick.PickCandidate
import org.umamo.render.pick.pickAllDrawables
import org.umamo.render.pick.pickDrawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * The static rest-pose front rank per drawable: the index of each drawable in the resolved
 * back-to-front paint order (higher = drawn more in front), computed over the STATIC draw orders.
 * The UV editor is a rest-space authoring view, so island stacking deliberately ignores any
 * pose-blended draw-order channel - the rank a rigger sees here is the one the model rests at.
 *
 * The order rule mirrors the renderer's pose resolve exactly: the hierarchical group sort over
 * renderRoot when the model carries a draw-order group tree, else the flat paintOrder sort over the
 * parts-tree drawable order.
 *
 * @param PuppetModel model The model whose drawables to rank.
 * @return Map<DrawableId, Float> The front rank per drawable (higher = more front).
 */
internal fun restFrontRank(model: PuppetModel): Map<DrawableId, Float> {
	val drawOrderById = model.drawables.associate { drawable -> drawable.id to drawable.drawOrder }
	val paintedBackToFront =
		if (model.renderRoot.children.isEmpty()) {
			paintOrder(model.drawables.map { drawable -> drawable.id }, drawOrderById)
		} else {
			renderOrder(model.renderRoot, drawOrderById)
		}
	return paintedBackToFront.withIndex().associate { (paintIndex, drawableId) -> drawableId to paintIndex.toFloat() }
}

/**
 * An alpha sampler over the shown surface - the atlas page or the source layer's artwork - for the
 * shared pick functions: (id, u, v) -> texel alpha 0..1.  v = 0 is the image's TOP row, matching the
 * decoded image's top-row-first byte layout, so the sample needs no flip; out-of-range coordinates
 * clamp to the edge texel.  A null image (the untextured 1x1 fallback) samples 1f everywhere, so
 * untextured islands stay clickable.  The DrawableId parameter is unused - every shown island samples
 * the one shown surface.
 *
 * @param DecodedImage? page The shown surface's decoded pixels, or null for the untextured fallback.
 * @return Function The sampler the pick functions consume.
 */
internal fun pageAlphaSampler(page: DecodedImage?): (DrawableId, Float, Float) -> Float {
	if (page == null) {
		return { _, _, _ -> 1f }
	}
	return { _, u, v ->
		val texelX = (u * page.width).toInt().coerceIn(0, page.width - 1)
		val texelY = (v * page.height).toInt().coerceIn(0, page.height - 1)
		// DecodedImage rgba is row-major, top row first, four bytes per texel; alpha is the fourth.
		(page.rgba[(texelY * page.width + texelX) * 4 + 3].toInt() and 0xFF) / 255f
	}
}

/**
 * The UV editor's island picker: the shared alpha-gated point pick (pickDrawable /
 * pickAllDrawables) bound to the shown surface's display-space islands.  The point is taken in
 * display (texel) space - the caller unprojects a click through the area camera first - and the
 * hit's uv is barycentric-interpolated from the mesh uvs in the SHOWN SURFACE's own frame (the
 * stored coordinates over an atlas page, the layer-frame ones over a source layer), so the alpha
 * gate samples exactly the texel the click lands on.  Front-most wins by [frontRankById]; the stack
 * query is front-to-back with per-candidate centrality for the overlap popup's default row.
 *
 * @property Map<DrawableId, FloatArray> displayPositionsById Interleaved display-space (x, y) vertices per island.
 * @property Map<DrawableId, IntArray> indicesById Triangle index triples per island.
 * @property Map<DrawableId, FloatArray> meshUvsById The shown surface's uvs per island (the alpha-sample space).
 * @property Map<DrawableId, Float> frontRankById The static rest-pose front rank (higher = more front).
 * @property Function sampleAlpha (id, u, v) -> texel alpha over the shown surface.
 * @property Function atlasSizeOf (id) -> the shown image's (width, height), or null for the untextured fallback
 *   (full centrality instead of ray-marching a fake 1x1 page).
 */
internal class UvIslandPick(
	val displayPositionsById: Map<DrawableId, FloatArray>,
	val indicesById: Map<DrawableId, IntArray>,
	val meshUvsById: Map<DrawableId, FloatArray>,
	val frontRankById: Map<DrawableId, Float>,
	val sampleAlpha: (DrawableId, Float, Float) -> Float,
	val atlasSizeOf: (DrawableId) -> Pair<Int, Int>?,
) {
	/**
	 * The front-most island whose mesh contains the point AND whose texel on the shown surface there is
	 * opaque, or null on a miss (including a click on transparent triangle overhang).
	 *
	 * @param Float displayX The point X in display (texel) space.
	 * @param Float displayY The point Y in display (texel) space.
	 * @return DrawableId? The front-most opaque hit, or null.
	 */
	fun topmostAt(displayX: Float, displayY: Float): DrawableId? =
		pickDrawable(displayX, displayY, displayPositionsById, indicesById, meshUvsById, frontRankById, sampleAlpha)

	/**
	 * Every opaque island under the point, front-to-back, each with its centrality - the overlap
	 * popup's candidate list.
	 *
	 * @param Float displayX The point X in display (texel) space.
	 * @param Float displayY The point Y in display (texel) space.
	 * @return List<PickCandidate> The candidates, front-to-back.
	 */
	fun stackAt(displayX: Float, displayY: Float): List<PickCandidate> =
		pickAllDrawables(displayX, displayY, displayPositionsById, indicesById, meshUvsById, frontRankById, atlasSizeOf, sampleAlpha)
}

/**
 * Builds the island picker for the shown surface from what the UV space already resolves: the shown
 * islands' display-space gizmo geometry, their uvs in that surface's own frame, the model's rest
 * front rank, and the surface's decoded pixels.
 *
 * @param List<GizmoMeshGeometry> geometries The islands' display-space gizmo geometry.
 * @param Map<DrawableId, Float> frontRank The model's rest-pose front rank (restFrontRank).
 * @param Map<DrawableId, FloatArray> uvsById Each island's uvs in the shown surface's frame (the alpha-sample space).
 * @param DecodedImage? image The shown surface's decoded pixels, or null for the untextured fallback.
 * @return UvIslandPick The picker.
 */
internal fun uvIslandPick(
	geometries: List<GizmoMeshGeometry>,
	frontRank: Map<DrawableId, Float>,
	uvsById: Map<DrawableId, FloatArray>,
	image: DecodedImage?,
): UvIslandPick =
	UvIslandPick(
		displayPositionsById = geometries.associate { geometry -> geometry.drawableId to geometry.positions },
		indicesById = geometries.associate { geometry -> geometry.drawableId to geometry.indices },
		meshUvsById = uvsById,
		frontRankById = frontRank,
		sampleAlpha = pageAlphaSampler(image),
		atlasSizeOf = { image?.let { decodedImage -> decodedImage.width to decodedImage.height } },
	)

/**
 * The islands a finished box drag encloses: an island is enclosed when ANY of its UV vertices falls
 * inside the box (Blender's UV island box feel - a small box over an island's edge still takes it),
 * tested through the shared vertex box rule.  The result preserves the geometries' list order, so
 * the caller's active-target choice (the last enclosed island) is deterministic.
 *
 * @param List<GizmoMeshGeometry> geometries The islands' display-space gizmo geometry.
 * @param Offset cornerA One box corner in area-local pixels.
 * @param Offset cornerB The opposite box corner in area-local pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return List<DrawableId> The enclosed islands, in geometries order.
 */
internal fun uvIslandsInBox(
	geometries: List<GizmoMeshGeometry>,
	cornerA: Offset,
	cornerB: Offset,
	camera: ViewportCamera,
	size: IntSize,
): List<DrawableId> =
	geometries.mapNotNull { geometry ->
		geometry.drawableId.takeIf { elementsInBox(MeshSelectMode.Vertex, geometry, cornerA, cornerB, camera, size).isNotEmpty() }
	}

/**
 * Resolves a finished island box drag against the object selection: additive (Shift) keeps the
 * current targets and adds the enclosed islands, the last one becoming active (or the current
 * active surviving when the box enclosed nothing); plain replaces the selection with the enclosed
 * set.  The viewport's object box rule, over islands.
 *
 * @param Selection current The committed object selection.
 * @param List<SelectionTarget.Drawable> enclosed The enclosed, selectable islands in enclosure order.
 * @param Boolean additive True when Shift extends the selection.
 * @return Selection The selection the box produces.
 */
internal fun resolveIslandBoxSelection(
	current: Selection,
	enclosed: List<SelectionTarget.Drawable>,
	additive: Boolean,
): Selection =
	if (additive) {
		Selection(current.targets + enclosed, enclosed.lastOrNull() ?: current.active)
	} else {
		Selection(enclosed.toSet(), enclosed.lastOrNull())
	}