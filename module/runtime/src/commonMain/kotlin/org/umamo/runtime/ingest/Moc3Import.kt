package org.umamo.runtime.ingest

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.CUBISM_DEFAULT_PART_DRAW_ORDER
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GlueForm
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartDrawOrderForm
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.deriveRenderRoot
import kotlin.math.abs
import org.umamo.format.moc3.model.Part as MocPart

/**
 * Maps a decoded MOC3 document (`:format`) plus its optional cdi3 display info into the concrete
 * [PuppetModel] (`:runtime`) - the baked-runtime counterpart of [Cmo3Import].
 *
 * MOC3 references everything by index (list position), so the mapping is a single pass over each
 * list with index → id tables built up front.  Three format gaps shape the result:
 *
 *  - Coordinate space.  MOC3 keyform positions are absolute values in the OWNING OBJECT'S PARENT
 *    SPACE, and every parent space but the root matches the runtime's convention VERBATIM (verified
 *    value-for-value against the CMO3 corpus twin): normalized lattice (u, v) under a warp parent and
 *    the pixel-scale local frame under a rotation parent, angles in degrees with the same sign.  Only
 *    the root space differs - the moc stores model units around CanvasInfo's origin, same Y-down
 *    orientation - so root-space values map through the affine canvas = origin + ppu·model (see
 *    [pointSpaceOf]).  The one unit seam: a rotation parented to the root or a warp carries the
 *    px→model factor in its keyform scale (the official runtime's accY accumulator propagates it), so
 *    those scales multiply by ppu to land in the runtime's pixel world; rotation-parented rotations
 *    keep their scale verbatim.  One caveat: the runtime's rest mesh (Drawable.mesh.positions) is
 *    canvas-space EDITING geometry in the CMO3 convention, which a moc does not store - this import
 *    leaves the rest mesh in parent space (exact for evaluation, since the base cancels out of the
 *    keyform blend), and `:render`'s restMeshesToCanvasSpace finishes the job by evaluating the
 *    default pose (the document loader applies it).
 *  - Names.  The binary stores no display names (and no deformer ids at all); parameter/part names
 *    come from cdi3.json when present, everything else falls back to the format id, and deformer
 *    ids/names are synthesized deterministically from the deformer index.
 *  - Blend shapes.  Records are not evaluated anywhere in :runtime/:render yet, so they are ignored
 *    here; BLEND_SHAPE-typed parameters import as ordinary (no-op) sliders.  Callers can inspect
 *    [MocDocument.blendShapes] to warn.  Offscreens are equally unhandled (parity with the CMO3 path).
 *
 * MOC3: インデックス参照の焼き込み済みモデルを PuppetModel へ変換する。名前は cdi3、座標系は CanvasInfo で解決。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5</a>
 */
object Moc3Import {
	/**
	 * Builds a [PuppetModel] from a decoded [MocDocument] (e.g. from `Moc3.decode(bytes)`).
	 *
	 * @param MocDocument mocDocument The decoded semantic model.
	 * @param Cdi3Json?   displayInfo The sibling cdi3.json (display names, parameter groups, combined
	 *                                parameters), or null to fall back to raw format ids everywhere.
	 * @return PuppetModel The concrete runtime puppet.
	 */
	fun fromMocDocument(mocDocument: MocDocument, displayInfo: Cdi3Json?): PuppetModel {
		// MOC3 §5.3 CanvasInfo: pixelsPerUnit + origin place stored model space onto the canvas as a plain
		// affine, same Y-down orientation: canvasX = originX + ppu·modelX, canvasY = originY + ppu·modelY
		// (corpus-verified against the CMO3 twin; the official core's Y-up presentation happens at eval
		// time, not in the stored tables).  A canvas-less model keeps the identity mapping so import still
		// succeeds (degenerate, like CMO3's 0×0 default).
		val canvas = mocDocument.canvas
		val pixelsPerUnit = canvas?.pixelsPerUnit ?: 1f
		val canvasOriginX = canvas?.originX ?: 0f
		val canvasOriginY = canvas?.originY ?: 0f

		val parameterNameById = displayInfo?.parameters?.associate { it.id to it.name } ?: emptyMap()
		val partNameById = displayInfo?.parts?.associate { it.id to it.name } ?: emptyMap()

		// Index → runtime id tables, all in FILE order (every cross-reference in the moc is a file-order
		// index). Deformers carry no id in moc3 (MOC3 §5.6: referenced purely by index), so synthesize a
		// deterministic, file-stable one from the index; DeformerId is its own namespace, so it cannot
		// collide with drawable/part ids.
		val parameterIds = mocDocument.parameters.map { ParameterId(it.id) }
		val partIds = mocDocument.parts.map { PartId(it.id) }
		val drawableIdsByFileIndex = mocDocument.artMeshes.map { DrawableId(it.id) }
		val deformerIds = List(mocDocument.deformers.size) { deformerIndex -> DeformerId("Deformer$deformerIndex") }

		val parameters =
			mocDocument.parameters.map { source ->
				Parameter(
					id = ParameterId(source.id),
					// cdi3: DisplayParameter.name is the display label; fall back to the id (ParamAngleX).
					name = parameterNameById[source.id] ?: source.id,
					min = source.minimumValue,
					max = source.maximumValue,
					default = source.defaultValue,
				)
			}
		val knownParameterIds = parameterIds.toSet()

		// cdi3: CombinedParameters is an array of [horizontal, vertical] id pairs - the editor's LINKED
		// parameter pads. Entries that are not a 2-pair or name an unknown parameter are skipped.
		val parameterLinks =
			displayInfo?.combinedParameters.orEmpty().mapNotNull { pair ->
				val horizontalId = pair.getOrNull(0)?.let(::ParameterId) ?: return@mapNotNull null
				val verticalId = pair.getOrNull(1)?.let(::ParameterId) ?: return@mapNotNull null
				if (pair.size == 2 && horizontalId in knownParameterIds && verticalId in knownParameterIds) {
					ParameterLink(horizontalId, verticalId)
				} else {
					null
				}
			}

		val parameterTree = buildParameterTree(displayInfo, parameterIds)

		/**
		 * Resolves the keyform binding for [bindingIndex], or null when the document carries none.
		 *
		 * @param Int bindingIndex A `keyformBindingIndex` from a moc object.
		 * @return KeyformBinding? The binding (a static object resolves to a zero-axis binding).
		 */
		fun bindingOf(bindingIndex: Int): KeyformBinding? = mocDocument.keyformBinding(bindingIndex)

		/**
		 * Builds a runtime keyform grid over [binding], one cell per grid index.  A static object (a
		 * zero-axis binding) becomes a zero-axis single-cell grid, which the evaluator resolves to a
		 * single full-weight corner - keeping the baked draw order/opacity that a null grid would lose.
		 *
		 * @param KeyformBinding? binding The object's keyform binding.
		 * @param Function        formAt  The typed form payload at a grid index, or null to skip the cell.
		 * @return KeyformGrid<TForm>? The grid, or null when there is no binding.
		 */
		fun <TForm : Any> gridOf(binding: KeyformBinding?, formAt: (gridIndex: Int) -> TForm?): KeyformGrid<TForm>? {
			if (binding == null) {
				return null
			}
			// MOC3 §5.6: axes are in stride order (first = fastest varying), matching the runtime grid's
			// stride folding, so keyIndices(gridIndex) is the cell coordinate as-is.
			val axes =
				binding.axes.map { axis ->
					KeyformAxis(
						parameterId = parameterIds.getOrElse(axis.parameterIndex) { ParameterId("") },
						keys = axis.keyPositions.copyOf(),
					)
				}
			val cells =
				(0 until binding.gridSize).mapNotNull { gridIndex ->
					formAt(gridIndex)?.let { form -> KeyformCell(binding.keyIndices(gridIndex), form) }
				}
			return KeyformGrid(axes, cells)
		}

		/**
		 * The grid index of the default-pose cell of [binding]: per axis, the key nearest the driving
		 * parameter's default value, stride-folded.  This cell's baked values serve as the editor's rest
		 * state (rest mesh, static draw order); the multilinear blend is base-independent, so the choice
		 * never changes evaluated output.
		 *
		 * @param KeyformBinding? binding The object's keyform binding, or null for a static object.
		 * @return Int The default-pose grid index (0 when static or axis data is degenerate).
		 */
		fun defaultCellIndexOf(binding: KeyformBinding?): Int {
			if (binding == null) {
				return 0
			}
			var linearIndex = 0
			var stride = 1
			for (axis in binding.axes) {
				val defaultValue = mocDocument.parameters.getOrNull(axis.parameterIndex)?.defaultValue ?: 0f
				var nearestKey = 0
				for (keyIndex in axis.keyPositions.indices) {
					if (abs(axis.keyPositions[keyIndex] - defaultValue) < abs(axis.keyPositions[nearestKey] - defaultValue)) {
						nearestKey = keyIndex
					}
				}
				linearIndex += nearestKey * stride
				stride *= axis.keyCount
			}
			return linearIndex
		}

		/**
		 * The coordinate space a child object of [parentDeformerIndex] stores its positions in.  Any
		 * unresolvable index (negative OR out of range) is root space - the same normalization the id
		 * mapping applies via deformerIds.getOrNull, so a malformed parent index cannot leave an object
		 * treated as root-parented but converted as rotation-local.
		 *
		 * @param Int parentDeformerIndex The owning object's parent deformer index (-1 at the root).
		 * @return PointSpace The stored space.
		 */
		fun pointSpaceOf(parentDeformerIndex: Int): PointSpace =
			when (mocDocument.deformers.getOrNull(parentDeformerIndex)) {
				is WarpDeformer -> PointSpace.WarpLattice
				is RotationDeformer -> PointSpace.RotationLocal
				null -> PointSpace.ModelRoot
			}

		/**
		 * Converts interleaved x,y [points] from the moc's stored [space] to the runtime's convention
		 * (CMO3 canvas pixels at the root; parent-local elsewhere).  Warp-lattice and rotation-local
		 * values are stored in the runtime's own convention already (corpus-verified) and pass through
		 * untouched; only root-space values map through CanvasInfo's affine.
		 *
		 * @param PointSpace space  The stored space (from [pointSpaceOf]).
		 * @param FloatArray points Interleaved x,y positions as stored in the moc.
		 * @return FloatArray The converted positions (always a fresh array).
		 */
		fun convertPoints(space: PointSpace, points: FloatArray): FloatArray {
			val converted = FloatArray(points.size)
			var coordIndex = 0
			while (coordIndex + 1 < points.size) {
				when (space) {
					// MOC3 §5.3 CanvasInfo: canvas px = origin + ppu·model, same Y-down orientation.
					PointSpace.ModelRoot -> {
						converted[coordIndex] = canvasOriginX + pixelsPerUnit * points[coordIndex]
						converted[coordIndex + 1] = canvasOriginY + pixelsPerUnit * points[coordIndex + 1]
					}
					// Warp-lattice (u, v) and pixel-scale rotation-local frames match the runtime verbatim.
					PointSpace.WarpLattice, PointSpace.RotationLocal -> {
						converted[coordIndex] = points[coordIndex]
						converted[coordIndex + 1] = points[coordIndex + 1]
					}
				}
				coordIndex += 2
			}
			return converted
		}

		// Whether a rotation deformer sits anywhere on each deformer's ancestor chain.  This locates
		// the px->model unit seam: along every root path, the FIRST rotation is where the accumulated
		// scale chain converts pixel-scale local space into model units, so only that rotation's
		// stored scale carries the 1/ppu factor.  Every rotation below it (whatever its direct parent's
		// kind) inherits the factor through the accumulator and stores its scale verbatim.
		val hasRotationAncestor =
			BooleanArray(mocDocument.deformers.size) { deformerIndex ->
				var currentIndex = mocDocument.deformers[deformerIndex].parentDeformerIndex
				var found = false
				var chainSteps = 0
				while (currentIndex >= 0 && currentIndex < mocDocument.deformers.size && chainSteps <= mocDocument.deformers.size) {
					if (mocDocument.deformers[currentIndex] is RotationDeformer) {
						found = true
						break
					}
					currentIndex = mocDocument.deformers[currentIndex].parentDeformerIndex
					chainSteps++
				}
				found
			}

		var warpOrdinal = 0
		var rotationOrdinal = 0
		val deformers =
			mocDocument.deformers.mapIndexed { deformerIndex, source ->
				val id = deformerIds[deformerIndex]
				val parent = deformerIds.getOrNull(source.parentDeformerIndex)
				val keyformSpace = pointSpaceOf(source.parentDeformerIndex)
				val binding = bindingOf(source.keyformBindingIndex)
				when (source) {
					is WarpDeformer -> {
						warpOrdinal++
						Deformer.Warp(
							id = id,
							// No name survives the bake; a deterministic synthesized label keeps the outliner readable.
							name = "Warp Deformer $warpOrdinal",
							parent = parent,
							// MOC3 stores no deformer → part binding (the org tree only places parts and drawables).
							partId = null,
							rows = source.rows,
							columns = source.columns,
							// MOC3 §5.6 warp mode: 0 = triangle split, non-zero = bilinear (quad).
							isQuadTransform = source.mode != 0,
							keyforms =
								gridOf(binding) { gridIndex ->
									source.keyforms.getOrNull(gridIndex)?.let { keyform ->
										WarpForm(convertPoints(keyformSpace, keyform.controlPoints))
									}
								},
						)
					}
					is RotationDeformer -> {
						rotationOrdinal++
						val scaleFactor = if (hasRotationAncestor[deformerIndex]) 1f else pixelsPerUnit
						Deformer.Rotation(
							id = id,
							name = "Rotation Deformer $rotationOrdinal",
							parent = parent,
							partId = null,
							baseAngle = source.baseAngle,
							keyforms =
								gridOf(binding) { gridIndex ->
									source.keyforms.getOrNull(gridIndex)?.let { keyform ->
										val origin = convertPoints(keyformSpace, floatArrayOf(keyform.originX, keyform.originY))
										RotationForm(
											originX = origin[0],
											originY = origin[1],
											angle = keyform.angle,
											scale = keyform.scale * scaleFactor,
											flipX = keyform.reflectX,
											flipY = keyform.reflectY,
										)
									}
								},
						)
					}
				}
			}

		val drawables =
			mocDocument.artMeshes.map { source ->
				val space = pointSpaceOf(source.parentDeformerIndex)
				val binding = bindingOf(source.keyformBindingIndex)
				// MOC3 keyforms are absolute; the default-pose cell serves as the rest mesh and every cell
				// re-expresses as a delta against it (the multilinear blend is base-independent, so evaluated
				// geometry is unaffected by the choice).
				val basePositions =
					source.keyforms.getOrNull(defaultCellIndexOf(binding))?.let { keyform ->
						convertPoints(space, keyform.vertexPositions)
					}
				val mesh =
					basePositions?.let { positions ->
						DrawableMesh(
							positions = positions,
							uvs = source.vertexUvs.copyOf(),
							// MOC3 §5.6 INDEX_DATA is u16; widen unsigned so meshes past 32767 vertices survive.
							indices = IntArray(source.triangleIndices.size) { indexIndex -> source.triangleIndices[indexIndex].toInt() and 0xFFFF },
						)
					}
				Drawable(
					id = DrawableId(source.id),
					// cdi3 carries no drawable names; the format id is all a baked model has.
					name = source.id,
					parentDeformerId = deformerIds.getOrNull(source.parentDeformerIndex),
					blendMode = blendModeOf(source.constantFlags),
					// MOC3 §5.6 MASK_INDEX_DATA: mask sources are drawable file indices.
					maskedBy = source.maskDrawableIndices.toList().mapNotNull { maskIndex -> drawableIdsByFileIndex.getOrNull(maskIndex) },
					invertMask = source.constantFlags and ConstantFlag.IS_INVERTED_MASK != 0,
					// Visibility/lock are editor-only authoring state; a baked model shows everything.
					isVisible = true,
					isSelectable = true,
					mesh = mesh,
					keyforms =
						gridOf(binding) { gridIndex ->
							source.keyforms.getOrNull(gridIndex)?.let { keyform ->
								MeshForm(
									positionDeltas = deltaVsBase(basePositions, convertPoints(space, keyform.vertexPositions)),
									drawOrder = keyform.drawOrder,
									opacity = keyform.opacity,
								)
							}
						},
				)
			}

		val glues =
			mocDocument.glues.mapNotNull { source ->
				val meshA = drawableIdsByFileIndex.getOrNull(source.meshAIndex) ?: return@mapNotNull null
				val meshB = drawableIdsByFileIndex.getOrNull(source.meshBIndex) ?: return@mapNotNull null
				// MOC3 §5.6 glue: vertex indices are already mesh-local (no UID indirection, unlike CMO3).
				// Pairs whose indices fall outside either mesh are dropped, mirroring Cmo3Import's
				// UID-resolution behavior - the glue layout planner indexes vertex arrays directly, so an
				// unvalidated index from a malformed moc would throw on the render thread after a
				// nominally successful import.
				val vertexCountA = mocDocument.artMeshes.getOrNull(source.meshAIndex)?.vertexCount ?: 0
				val vertexCountB = mocDocument.artMeshes.getOrNull(source.meshBIndex)?.vertexCount ?: 0
				val pairs =
					source.pairs.mapNotNull { pair ->
						if (pair.vertexA in 0 until vertexCountA && pair.vertexB in 0 until vertexCountB) {
							GluePair(pair.vertexA, pair.vertexB, pair.weightA, pair.weightB)
						} else {
							null
						}
					}
				val intensity =
					gridOf(bindingOf(source.keyformBindingIndex)) { gridIndex ->
						GlueForm(source.intensityKeyforms.getOrElse(gridIndex) { source.intensityKeyforms.lastOrNull() ?: 1f })
					}
				Glue(meshA, meshB, pairs, intensity)
			}

		// The draw-order tree: moc3 stores it explicitly (MOC3 §5.6 render-order groups, group 0 = root),
		// so it is taken as the baked authority rather than re-derived from the reconstructed org tree.
		// Parts referenced as kind-1 children are the "Group by Draw Order" parts.
		val drawOrderGroupPartIndices =
			buildSet {
				for (group in mocDocument.renderOrderGroups) {
					for (child in group.children) {
						if (child.kind == 1) {
							add(child.index)
						}
					}
				}
			}

		/**
		 * The static (default-pose) draw order of a moc part - the sort key of its render-order slot.
		 *
		 * @param MocPart source The moc part.
		 * @return Int The quantised draw order (Cubism default 500 when the part carries no keyforms).
		 */
		fun partStaticDrawOrder(source: MocPart): Int {
			// MOC3: PART_KEYFORM_BINDING 0 means static for parts (a single draw-order value), unlike
			// meshes/deformers where 0 is a real binding.
			val binding = if (source.keyformBindingIndex > 0) bindingOf(source.keyformBindingIndex) else null
			val defaultCell = defaultCellIndexOf(binding)
			val drawOrder = source.drawOrderKeyforms.getOrElse(defaultCell) { source.drawOrderKeyforms.firstOrNull() ?: CUBISM_DEFAULT_PART_DRAW_ORDER.toFloat() }
			return (drawOrder + 0.001f).toInt()
		}

		/**
		 * The parameter-driven draw-order grid of a moc part, or null when the part is static.
		 *
		 * @param MocPart source The moc part.
		 * @return KeyformGrid<PartDrawOrderForm>? The grid, or null.
		 */
		fun partDrawOrderGridOf(source: MocPart): KeyformGrid<PartDrawOrderForm>? {
			if (source.keyformBindingIndex <= 0) {
				return null
			}
			return gridOf(bindingOf(source.keyformBindingIndex)) { gridIndex ->
				source.drawOrderKeyforms.getOrNull(gridIndex)?.let { drawOrder -> PartDrawOrderForm(drawOrder) }
			}
		}

		val renderRoot =
			buildRenderRoot(
				mocDocument,
				drawableIdsByFileIndex,
				partIds,
				::partStaticDrawOrder,
				::partDrawOrderGridOf,
			)

		// Panel order (top = front) is not stored in moc3; reconstruct it from the render tree - render
		// order is back-to-front, so the reversed leaf sequence is the panel order. Drawables the render
		// tree never places sort last, keeping file order among themselves (stable sort).
		val panelIndexByDrawable =
			buildMap {
				if (renderRoot != null) {
					val leaves = ArrayList<DrawableId>()
					collectRenderLeaves(renderRoot, leaves)
					leaves.asReversed().forEachIndexed { panelIndex, drawableId ->
						if (drawableId !in this) {
							put(drawableId, panelIndex)
						}
					}
				}
			}

		val (parts, rootChildren) =
			buildOrgTree(
				mocDocument,
				partIds,
				drawableIdsByFileIndex,
				partNameById,
				panelIndexByDrawable,
				drawOrderGroupPartIndices,
				::partStaticDrawOrder,
				::partDrawOrderGridOf,
			)

		// The flat drawables list is kept back-to-front (the storage/base order, mirroring Cmo3Import's
		// panel-derived ordering); unplaced drawables keep file order at the back.
		val orderedDrawables = drawables.sortedByDescending { drawable -> panelIndexByDrawable[drawable.id] ?: Int.MAX_VALUE }

		val model =
			PuppetModel(
				parameters = parameters,
				parts = parts,
				deformers = deformers,
				drawables = orderedDrawables,
				rootChildren = rootChildren,
				// MOC3 has no synthetic root part; entities at the root simply carry parentPartIndex -1.
				rootPartId = null,
				glues = glues,
				parameterLinks = parameterLinks,
				parameterTree = parameterTree,
				// MOC3 §5.3 CanvasInfo: width/height are the canvas size in pixels; the world origin is the
				// canvas-space origin with Y negated into world space (same convention as Cmo3Import).
				canvasWidth = canvas?.width ?: 0f,
				canvasHeight = canvas?.height ?: 0f,
				worldOriginX = canvasOriginX,
				worldOriginY = -canvasOriginY,
			)
		return if (renderRoot != null) {
			model.copy(renderRoot = renderRoot)
		} else {
			model.copy(renderRoot = model.deriveRenderRoot())
		}
	}

	/** The coordinate space a moc object's positions are stored in, selected by its parent deformer. */
	private enum class PointSpace {
		/** MOC3 model space (a root object): canvas = CanvasInfo origin + ppu·model, Y-down like the canvas. */
		ModelRoot,

		/** A warp parent's normalized lattice (u, v) - identical in both conventions. */
		WarpLattice,

		/** A rotation parent's pixel-scale local frame - identical in both conventions. */
		RotationLocal,
	}

	/**
	 * Builds the runtime render-order tree from the moc's explicit render-order groups (group 0 is the
	 * root), or null when the document carries none (degenerate; the caller derives from the org tree).
	 * A kind-0 child is a drawable leaf; a kind-1 child is a "Group by Draw Order" part whose sub-group
	 * record is [org.umamo.format.moc3.model.RenderOrderChild.groupIndex].  A visited set guards a
	 * malformed cyclic group reference, and drawables the stored tree never places are appended at the
	 * root (the renderer draws exclusively from this tree, so a missing leaf would never render).
	 *
	 * @param MocDocument mocDocument            The decoded document.
	 * @param List        drawableIdsByFileIndex Drawable file index → runtime id.
	 * @param List        partIds                Part file index → runtime id.
	 * @param Function    partStaticDrawOrder    Static draw order of a moc part.
	 * @param Function    partDrawOrderGridOf    Draw-order grid of a moc part (null when static).
	 * @return RenderGroup? The render root, or null when the moc has no render-order groups.
	 */
	private fun buildRenderRoot(
		mocDocument: MocDocument,
		drawableIdsByFileIndex: List<DrawableId>,
		partIds: List<PartId>,
		partStaticDrawOrder: (MocPart) -> Int,
		partDrawOrderGridOf: (MocPart) -> KeyformGrid<PartDrawOrderForm>?,
	): RenderGroup? {
		if (mocDocument.renderOrderGroups.isEmpty()) {
			return null
		}
		val visitedGroups = HashSet<Int>()

		fun childrenOf(groupIndex: Int): List<RenderNode> {
			val group = mocDocument.renderOrderGroups.getOrNull(groupIndex) ?: return emptyList()
			if (!visitedGroups.add(groupIndex)) {
				return emptyList()
			}
			return group.children.mapNotNull { child ->
				when (child.kind) {
					// MOC3 §5.6 render-order child kind 0: a drawable leaf.
					0 -> drawableIdsByFileIndex.getOrNull(child.index)?.let(::RenderDrawable)
					// Kind 1: a draw-order group part; its members live in the referenced sub-group record.
					1 -> {
						val part = mocDocument.parts.getOrNull(child.index) ?: return@mapNotNull null
						val partId = partIds.getOrNull(child.index) ?: return@mapNotNull null
						RenderGroup(
							partId = partId,
							drawOrder = partStaticDrawOrder(part),
							children = childrenOf(child.groupIndex),
							drawOrderGrid = partDrawOrderGridOf(part),
						)
					}
					else -> null
				}
			}
		}

		val root = RenderGroup(null, CUBISM_DEFAULT_PART_DRAW_ORDER, childrenOf(0))
		// Safety net, mirroring deriveRenderRoot's: the renderer draws EXCLUSIVELY from this tree, so
		// a drawable the stored groups never place (out-of-range child index, an unknown future child
		// kind, or a truncated tree) would silently never render.  Append the missing leaves at the
		// root, where they sort by their own draw order like any other root-level drawable.
		val placedLeaves = ArrayList<DrawableId>()
		collectRenderLeaves(root, placedLeaves)
		val placedDrawableIds = placedLeaves.toHashSet()
		val missingLeaves = drawableIdsByFileIndex.filter { drawableId -> drawableId !in placedDrawableIds }.map(::RenderDrawable)
		return if (missingLeaves.isEmpty()) {
			root
		} else {
			root.copy(children = root.children + missingLeaves)
		}
	}

	/**
	 * Collects a render tree's drawable leaves depth-first into [into] (back-to-front order).
	 *
	 * @param RenderNode           node The subtree root.
	 * @param ArrayList<DrawableId> into The destination leaf list.
	 */
	private fun collectRenderLeaves(node: RenderNode, into: ArrayList<DrawableId>) {
		when (node) {
			is RenderDrawable -> into.add(node.id)
			is RenderGroup -> node.children.forEach { child -> collectRenderLeaves(child, into) }
		}
	}

	/**
	 * Builds the runtime parts list and the root child list from the moc's part hierarchy.  MOC3 stores
	 * parent indices but no interleaved panel order, so each parent's sub-parts and drawables are sorted
	 * by the panel index reconstructed from the render tree (a part takes the minimum over its
	 * descendants); ties keep file order.  Malformed parent links are normalized so nothing is dropped
	 * from the outliner: an out-of-range parent index goes to the root, and every member of a parent
	 * cycle is re-parented to the root (breaking the cycle's edges keeps the forest acyclic and every
	 * part reachable).
	 *
	 * @param MocDocument mocDocument               The decoded document.
	 * @param List        partIds                   Part file index → runtime id.
	 * @param List        drawableIdsByFileIndex    Drawable file index → runtime id.
	 * @param Map         partNameById              cdi3 display names by part id.
	 * @param Map         panelIndexByDrawable      Reconstructed panel index per drawable.
	 * @param Set         drawOrderGroupPartIndices Part file indices referenced as render-order groups.
	 * @param Function    partStaticDrawOrder       Static draw order of a moc part.
	 * @param Function    partDrawOrderGridOf       Draw-order grid of a moc part (null when static).
	 * @return Pair<List<Part>, List<OrgChild>> The runtime parts (file order) and the root children.
	 */
	private fun buildOrgTree(
		mocDocument: MocDocument,
		partIds: List<PartId>,
		drawableIdsByFileIndex: List<DrawableId>,
		partNameById: Map<String, String>,
		panelIndexByDrawable: Map<DrawableId, Int>,
		drawOrderGroupPartIndices: Set<Int>,
		partStaticDrawOrder: (MocPart) -> Int,
		partDrawOrderGridOf: (MocPart) -> KeyformGrid<PartDrawOrderForm>?,
	): Pair<List<Part>, List<OrgChild>> {
		val partCount = mocDocument.parts.size
		// Normalize part parents in two steps: an out-of-range index goes to the root, and any part
		// whose ancestor chain never reaches the root (a malformed parent CYCLE - every member
		// in-range, so the range check alone misses it) is re-parented to the root too.  Without this
		// the whole cycle cluster is unreachable from childrenOf(-1), and since both the outliner and
		// the renderer's visibility gate walk the org tree from the root, its parts AND drawables
		// silently vanish.  Re-parenting every cycle member breaks all cycle edges, so the resulting
		// forest is acyclic and complete.
		val rangedParentIndices =
			IntArray(partCount) { partIndex ->
				val parentIndex = mocDocument.parts[partIndex].parentPartIndex
				if (parentIndex in 0 until partCount && parentIndex != partIndex) parentIndex else -1
			}

		fun reachesRoot(startIndex: Int): Boolean {
			var currentIndex = rangedParentIndices[startIndex]
			var steps = 0
			while (currentIndex != -1) {
				if (steps > partCount) {
					return false
				}
				steps++
				currentIndex = rangedParentIndices[currentIndex]
			}
			return true
		}

		val normalizedParentIndices =
			IntArray(partCount) { partIndex ->
				if (reachesRoot(partIndex)) rangedParentIndices[partIndex] else -1
			}

		val childPartIndices = HashMap<Int, MutableList<Int>>()
		val childDrawableIndices = HashMap<Int, MutableList<Int>>()
		mocDocument.parts.forEachIndexed { partIndex, _ ->
			childPartIndices.getOrPut(normalizedParentIndices[partIndex], ::mutableListOf).add(partIndex)
		}
		mocDocument.artMeshes.forEachIndexed { drawableIndex, artMesh ->
			val parentIndex = if (artMesh.parentPartIndex in 0 until partCount) artMesh.parentPartIndex else -1
			childDrawableIndices.getOrPut(parentIndex, ::mutableListOf).add(drawableIndex)
		}

		// A part's panel index is the minimum over its descendants' reconstructed indices, memoized over
		// the parent-index tree.  The cache is seeded before recursing so a malformed parent cycle
		// terminates instead of overflowing the stack.
		val partPanelIndexCache = HashMap<Int, Int>()

		fun partPanelIndex(partIndex: Int): Int {
			partPanelIndexCache[partIndex]?.let { cachedIndex ->
				return cachedIndex
			}
			partPanelIndexCache[partIndex] = Int.MAX_VALUE
			var minimumIndex = Int.MAX_VALUE
			for (drawableIndex in childDrawableIndices[partIndex].orEmpty()) {
				val panelIndex = panelIndexByDrawable[drawableIdsByFileIndex[drawableIndex]] ?: Int.MAX_VALUE
				if (panelIndex < minimumIndex) {
					minimumIndex = panelIndex
				}
			}
			for (childPartIndex in childPartIndices[partIndex].orEmpty()) {
				val panelIndex = partPanelIndex(childPartIndex)
				if (panelIndex < minimumIndex) {
					minimumIndex = panelIndex
				}
			}
			partPanelIndexCache[partIndex] = minimumIndex
			return minimumIndex
		}

		fun childrenOf(parentIndex: Int): List<OrgChild> {
			data class ChildEntry(val child: OrgChild, val panelIndex: Int)

			val entries = ArrayList<ChildEntry>()
			for (childPartIndex in childPartIndices[parentIndex].orEmpty()) {
				entries.add(ChildEntry(OrgChild.Part(partIds[childPartIndex]), partPanelIndex(childPartIndex)))
			}
			for (drawableIndex in childDrawableIndices[parentIndex].orEmpty()) {
				val drawableId = drawableIdsByFileIndex[drawableIndex]
				entries.add(ChildEntry(OrgChild.Drawable(drawableId), panelIndexByDrawable[drawableId] ?: Int.MAX_VALUE))
			}
			return entries.sortedBy { entry -> entry.panelIndex }.map { entry -> entry.child }
		}

		val parts =
			mocDocument.parts.mapIndexed { partIndex, source ->
				Part(
					id = partIds[partIndex],
					// cdi3: DisplayPart.name is the display label; fall back to the id.
					name = partNameById[source.id] ?: source.id,
					children = childrenOf(partIndex),
					// Visibility/lock/sketch are editor authoring state the bake drops; import everything shown.
					isVisible = true,
					isSketch = false,
					isSelectable = true,
					isDrawOrderGroup = partIndex in drawOrderGroupPartIndices,
					drawOrder = partStaticDrawOrder(source),
					drawOrderGrid = partDrawOrderGridOf(source),
				)
			}
		return parts to childrenOf(-1)
	}

	/**
	 * Builds the parameter-panel group tree from cdi3 display info.  cdi3 stores two flat lists
	 * (parameters and groups, each naming an owning groupId; "" = root), so within each group the
	 * leaf parameters come first (cdi3 order) followed by sub-groups (cdi3 order) - the original
	 * interleaving is not recorded in a baked export.  Parameters cdi3 never places (or placed under
	 * an unknown group) are appended at the root so every axis stays reachable in the panel.
	 *
	 * @param Cdi3Json?         displayInfo  The cdi3 display info, or null for no tree.
	 * @param List<ParameterId> parameterIds The moc's parameters in file order.
	 * @return List<ParameterNode> The root children (empty when cdi3 is absent).
	 */
	private fun buildParameterTree(displayInfo: Cdi3Json?, parameterIds: List<ParameterId>): List<ParameterNode> {
		if (displayInfo == null) {
			return emptyList()
		}
		val knownParameterIds = parameterIds.toSet()
		val groupsByParent = displayInfo.parameterGroups.groupBy { group -> group.groupId }
		val groupIds = displayInfo.parameterGroups.mapTo(HashSet()) { group -> group.id }
		val parametersByGroup =
			displayInfo.parameters
				.filter { parameter -> ParameterId(parameter.id) in knownParameterIds }
				.groupBy { parameter -> if (parameter.groupId in groupIds) parameter.groupId else "" }
		val visited = HashSet<String>()

		fun childrenOf(ownerGroupId: String): List<ParameterNode> =
			buildList {
				for (parameter in parametersByGroup[ownerGroupId].orEmpty()) {
					add(ParameterNode.Param(ParameterId(parameter.id)))
				}
				for (group in groupsByParent[ownerGroupId].orEmpty()) {
					if (!visited.add(group.id)) {
						continue
					}
					add(
						ParameterNode.Group(
							id = ParameterGroupId(group.id),
							name = group.name,
							// cdi3 records no fold state; open reads better than a wall of collapsed rows.
							initiallyOpen = true,
							children = childrenOf(group.id),
						),
					)
				}
			}

		val tree = childrenOf("")

		// Safety net: any moc parameter cdi3 never mentions still gets a root leaf, so the panel tree
		// covers every axis (the tree replaces the flat list when non-empty).
		val placedParameterIds =
			buildSet {
				fun walk(nodes: List<ParameterNode>) {
					for (node in nodes) {
						when (node) {
							is ParameterNode.Param -> add(node.id)
							is ParameterNode.Group -> walk(node.children)
						}
					}
				}
				walk(tree)
			}
		val unplaced = parameterIds.filter { parameterId -> parameterId !in placedParameterIds }
		return tree + unplaced.map { parameterId -> ParameterNode.Param(parameterId) }
	}

	/**
	 * Maps a moc drawable's constant-flag bitmask to the runtime [BlendMode].
	 *
	 * @param Int constantFlags The [ConstantFlag] bitmask (MOC3 §5.5).
	 * @return BlendMode The runtime blend mode (defaults to Normal).
	 */
	private fun blendModeOf(constantFlags: Int): BlendMode =
		when {
			constantFlags and ConstantFlag.BLEND_ADDITIVE != 0 -> BlendMode.Additive
			constantFlags and ConstantFlag.BLEND_MULTIPLICATIVE != 0 -> BlendMode.Multiply
			else -> BlendMode.Normal
		}

	/**
	 * Per-vertex deltas of [positions] vs [base] (`positions − base`), or a copy of positions when
	 * there is no size-matching base, so the form is kept absolute rather than dropped (matching
	 * [Cmo3Import]'s convention).
	 *
	 * @param FloatArray? base      The rest-mesh positions.
	 * @param FloatArray  positions The keyform's absolute positions.
	 * @return FloatArray The deltas, or a copy of positions.
	 */
	private fun deltaVsBase(base: FloatArray?, positions: FloatArray): FloatArray {
		if (base == null || base.size != positions.size) {
			return positions.copyOf()
		}
		return FloatArray(positions.size) { coordIndex -> positions[coordIndex] - base[coordIndex] }
	}
}
