package org.umamo.interop.moc3

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.interop.alphaBlendOfPacked
import org.umamo.interop.colorBlendOfPacked
import org.umamo.interop.runtimeTargetOfMocVersion
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.flagAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.keyform.asChannelTrack
import org.umamo.runtime.keyform.channelGridsOf
import org.umamo.runtime.keyform.fanOutMesh
import org.umamo.runtime.keyform.fanOutRotation
import org.umamo.runtime.keyform.fanOutWarp
import org.umamo.runtime.keyform.withChannelsCompacted
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DEFAULT_DRAW_ORDER
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
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
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.RenderGroup
import org.umamo.runtime.model.RenderNode
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.deriveRenderRoot
import org.umamo.runtime.model.partByDrawable
import kotlin.math.abs
import org.umamo.format.moc3.model.BlendShape as MocBlendShape
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
 *    the root space differs - the MOC stores model units around CanvasInfo's origin, same Y-down
 *    orientation - so root-space values map through the affine canvas = origin + ppu·model (see
 *    [pointSpaceOf]).  The one unit seam: a rotation parented to the root or a warp carries the
 *    px→model factor in its keyform scale, so
 *    those scales multiply by ppu to land in the runtime's pixel world; rotation-parented rotations
 *    keep their scale verbatim.  One caveat: the runtime's rest mesh (Drawable.mesh.positions) is
 *    canvas-space EDITING geometry in the CMO3 convention, which a MOC does not store - this import
 *    leaves the rest mesh in parent space (exact for evaluation, since the base cancels out of the
 *    keyform blend), and `:render`'s restMeshesToCanvasSpace finishes the job by evaluating the
 *    default pose (the document loader applies it).
 *  - Names.  The binary stores ids (deformers included, §5.6 s11) but no display names; parameter/part
 *    names come from cdi3.json when present, and everything else falls back to the format id - the same
 *    rule [Cmo3Import] uses for an unnamed source.  A deformer's authored label is lost for good (the
 *    bake drops it and cdi3 carries no deformer entries), so its name is the id, plus the drawable it
 *    deforms when exactly one is in reach: "Warp40 (ArtMesh5)".
 *  - Blend shapes.  MOC3 records store per-key DELTAS relative to the object's grid form at the
 *    DEFAULT pose (MOC3.md §5.6), while the runtime [BlendShapeBinding] keeps grid-convention
 *    forms (MeshForm rest-relative; Warp/RotationForm absolute) and the evaluator re-subtracts
 *    that same grid-at-default reference.  The mapping therefore ADDS the reference back when
 *    synthesizing each form - computed with the shared org.umamo.runtime.eval sampling helpers,
 *    the exact functions the evaluator later calls, so the round trip cancels to ULP.  Delta
 *    geometry converts like the grid keyforms minus the origin term (a delta in root space scales
 *    by ppu only; lattice/rotation-local deltas pass through; the rotation-scale ppu seam applies
 *    to scale deltas too).  Neutral form slots import as null (the stored neutral row is all-zero).
 *    PART-owned records carry only a draw-order delta (a part has no other blendable channel) and
 *    ingest onto [org.umamo.runtime.model.Part.blendShapes].  Offscreens ingest into
 *    [org.umamo.runtime.model.PartComposite] per owner part (packed blend int, flags, mask indices)
 *    - the part's group mode becomes Isolated - with the keyformed opacity/color channels merged
 *    into the part's [PartForm] grid (they ride the same cells, MOC3 §5.6).
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
	 * @param Boolean compactChannels Whether to run the post-import channel compaction (on by default);
	 *   see Cmo3Import.fromModelSource.
	 * @return PuppetModel The concrete runtime puppet.
	 */
	fun fromMocDocument(
		mocDocument: MocDocument,
		displayInfo: Cdi3Json?,
		compactChannels: Boolean = true,
	): PuppetModel {
		// MOC3 §5.3 CanvasInfo: pixelsPerUnit + origin place stored model space onto the canvas as a plain
		// affine, same Y-down orientation: canvasX = originX + ppu·modelX, canvasY = originY + ppu·modelY
		// (corpus-verified against the CMO3 twin; the Umamo C++ runtime's Y-up presentation happens at eval
		// time, not in the stored tables).  A canvas-less model keeps the identity mapping so import still
		// succeeds (degenerate, like CMO3's 0×0 default).
		val canvas = mocDocument.canvas
		val pixelsPerUnit = canvas?.pixelsPerUnit ?: 1f
		val canvasOriginX = canvas?.originX ?: 0f
		val canvasOriginY = canvas?.originY ?: 0f

		val canvasMapping = MocCanvasMapping(pixelsPerUnit, canvasOriginX, canvasOriginY)

		val parameterNameById = displayInfo?.parameters?.associate { it.id to it.name } ?: emptyMap()
		val partNameById = displayInfo?.parts?.associate { it.id to it.name } ?: emptyMap()
		// The Umamo cdi3 extension: art-mesh display names, which a moc alone cannot carry.
		val drawableNameById = displayInfo?.drawables?.associate { it.id to it.name } ?: emptyMap()

		// Index → runtime id tables, all in FILE order (every cross-reference in the MOC3 is a file-order
		// index).  Deformer ids come from MOC3 §5.6 s11 - the editor's own identifiers, the same ones the
		// CMO3 side carries, so a MOC3-origin export writes back the ids the model was authored with.
		// A blank or duplicated slot (a hand-built document, or a MOC3 written without s11) falls back to
		// a synthesized id.
		val parameterIds = mocDocument.parameters.map { ParameterId(it.id) }
		val partIds = mocDocument.parts.map { PartId(it.id) }
		val drawableIdsByFileIndex = mocDocument.artMeshes.map { DrawableId(it.id) }
		val claimedDeformerIds = mocDocument.deformers.filter { it.id.isNotEmpty() }.mapTo(HashSet()) { it.id }
		val usedDeformerIds = HashSet<String>()
		val deformerIds =
			mocDocument.deformers.mapIndexed { deformerIndex, source ->
				val fileId = source.id.takeIf { it.isNotEmpty() && usedDeformerIds.add(it) }
				DeformerId(fileId ?: synthesizedDeformerId(deformerIndex, claimedDeformerIds))
			}

		val parameters =
			mocDocument.parameters.map { source ->
				Parameter(
					id = ParameterId(source.id),
					// cdi3: DisplayParameter.name is the display label; fall back to the id (ParamAngleX).
					name = parameterNameById[source.id] ?: source.id,
					min = source.minimumValue,
					max = source.maximumValue,
					default = source.defaultValue,
					// MOC3 v4+ section 114 Parameter types (null on moc < 4 = all normal).
					kind = if (source.type == ParameterType.BLEND_SHAPE) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL,
					// MOC3 §5.5 s54: wrap rather than clamp at the limits.
					repeat = source.repeats,
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
		fun convertPoints(space: PointSpace, points: FloatArray): FloatArray =
			convertPointsToRuntime(space, points, canvasMapping)

		// The px->model unit seam; see Moc3SpaceSeam for why only the first rotation on a path carries it.
		val hasRotationAncestor =
			rotationAncestorFlags(
				mocDocument.deformers.size,
				{ index -> mocDocument.deformers[index].parentDeformerIndex },
				{ index -> mocDocument.deformers[index] is RotationDeformer },
			)

		// ---- blend shapes (MOC3 v4+ §5.6) ----
		// Records pre-indexed per target object; targetIndex is a deformer index for WARP/ROTATION
		// (already remapped by the decoder), a drawable file index for ART_MESH, and a part file index
		// for PART.
		val blendRecordsByTarget = mocDocument.blendShapes.groupBy { record -> record.target to record.targetIndex }
		val defaultByParameterId = parameters.associate { parameter -> parameter.id to parameter.default }
		val defaultValue: (ParameterId) -> Float = { parameterId -> defaultByParameterId[parameterId] ?: 0f }

		/**
		 * Converts interleaved delta components from the moc's stored [space] to the runtime's
		 * convention.  Unlike [convertPoints] the canvas ORIGIN does not apply - it cancels out of a
		 * difference - so a root-space delta scales by ppu only; the other spaces pass through.
		 *
		 * @param PointSpace space  The stored space (from [pointSpaceOf]).
		 * @param FloatArray deltas Interleaved x,y deltas as stored in the moc.
		 * @return FloatArray The converted deltas (always a fresh array).
		 */
		fun convertDeltas(space: PointSpace, deltas: FloatArray): FloatArray =
			convertDeltasToRuntime(space, deltas, canvasMapping)

		/**
		 * Elementwise sum of [reference] and [deltas] (sized like [deltas]; a size-mismatched
		 * reference contributes only its overlapping prefix, mirroring the evaluator's guards).
		 *
		 * @param FloatArray reference The grid-at-default reference components.
		 * @param FloatArray deltas    The converted delta components.
		 * @return FloatArray The synthesized absolute/rest-relative components.
		 */
		fun addReference(reference: FloatArray, deltas: FloatArray): FloatArray =
			FloatArray(deltas.size) { componentIndex ->
				deltas[componentIndex] + (reference.getOrNull(componentIndex) ?: 0f)
			}

		/**
		 * Adds a color delta row onto its grid-at-default reference, the color analogue of
		 * [addReference].
		 *
		 * A blend record's color rows are ADDITIVE, so a zero row is the identity and a null row (a
		 * model whose color tables are absent entirely, pre-4.2) contributes nothing.  Not clamped
		 * here: the evaluator subtracts this same reference back out and clamps only after summing every
		 * contribution, so clamping now would bias a record whose neighbours pull the other way.
		 *
		 * @param ColorRgb reference The channel's value at the default pose.
		 * @param Rgb?     delta     The record's stored delta row, or null when the model has no colours.
		 * @return ColorRgb The referenced color this key blends toward.
		 */
		fun addColorDelta(reference: ColorRgb, delta: Rgb?): ColorRgb =
			if (delta == null) {
				reference
			} else {
				ColorRgb(reference.red + delta.r, reference.green + delta.g, reference.blue + delta.b)
			}

		/**
		 * Maps a record's limit curves to the runtime's min-combined [BlendWeightLimit] list.
		 *
		 * @param MocBlendShape record The record whose limits to map.
		 * @return List<BlendWeightLimit> The runtime limit curves.
		 */
		fun blendLimitsOf(record: MocBlendShape): List<BlendWeightLimit> =
			record.limits.map { limit ->
				BlendWeightLimit(
					parameterId = parameterIds.getOrElse(limit.parameterIndex) { ParameterId("") },
					points =
						limit.keyPositions.indices.map { pointIndex ->
							BlendWeightLimitPoint(limit.keyPositions[pointIndex], limit.weights[pointIndex])
						},
				)
			}

		/**
		 * Assembles one runtime binding from a record: the moc keys already include the inserted
		 * neutral, whose form slot imports as null (the stored neutral delta row is all-zero).
		 *
		 * @param MocBlendShape record The record to map.
		 * @param Function      formAt Builds the synthesized runtime form for a non-neutral key.
		 * @return BlendShapeBinding<TForm> The runtime binding.
		 */
		fun <TForm : Any> bindingOfRecord(
			record: MocBlendShape,
			formAt: (keyIndex: Int) -> TForm?,
		): BlendShapeBinding<TForm> =
			BlendShapeBinding(
				parameterId = parameterIds.getOrElse(record.parameterIndex) { ParameterId("") },
				keys = record.keyPositions.copyOf(),
				neutralIndex = record.neutralKeyIndex,
				forms =
					record.keyPositions.indices.map { keyIndex ->
						if (keyIndex == record.neutralKeyIndex) null else formAt(keyIndex)
					},
				limits = blendLimitsOf(record),
			)

		/**
		 * Maps [records] onto [drawable] as mesh blend bindings: each stored delta row plus the
		 * grid-at-default reference (positions, draw order, opacity), converted to runtime space.
		 *
		 * @param Drawable            drawable The constructed runtime drawable (its grid is the reference source).
		 * @param PointSpace          space    The drawable's stored point space.
		 * @param List<MocBlendShape> records  The drawable's records.
		 * @return List<BlendShapeBinding<MeshForm>> The runtime bindings.
		 */
		fun meshBlendShapesOf(
			drawable: Drawable,
			space: PointSpace,
			records: List<MocBlendShape>,
		): List<BlendShapeBinding<MeshForm>> {
			val referenceDeltas = meshGridDefaultDeltas(drawable, defaultValue) ?: FloatArray(0)
			// The scalar reference is each channel's own value at the DEFAULT pose. An untracked or
			// out-of-range channel falls back to the drawable's static, which for an imported drawable is
			// Cubism's 500 / full opacity - the same fallback meshBlendState uses, so the evaluator's
			// subtraction cancels exactly even for an ungridded drawable.
			val referenceDrawOrder =
				drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, defaultValue)
			val referenceOpacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, defaultValue)
			val referenceMultiply =
				drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, defaultValue)
			val referenceScreen =
				drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, defaultValue)
			return records.mapNotNull { record ->
				val payloads =
					record.keyforms.map { keyform ->
						(keyform as? BlendShapeKeyform.Mesh)?.form ?: return@mapNotNull null
					}
				if (payloads.size != record.keyPositions.size) {
					return@mapNotNull null
				}
				bindingOfRecord(record) { keyIndex ->
					MeshForm(
						positionDeltas =
							addReference(
								referenceDeltas,
								convertDeltas(space, payloads[keyIndex].vertexPositions),
							),
						drawOrder = referenceDrawOrder + payloads[keyIndex].drawOrder,
						opacity = referenceOpacity + payloads[keyIndex].opacity,
						// Colour delta rows are ADDITIVE like the scalars, so their identity is zero rather
						// than Cubism's white multiply / black screen; a record without color tables has no
						// row at all and contributes nothing.
						multiplyColor = addColorDelta(referenceMultiply, payloads[keyIndex].multiplyColor),
						screenColor = addColorDelta(referenceScreen, payloads[keyIndex].screenColor),
					)
				}
			}
		}

		/**
		 * Maps [records] onto [warp] as lattice blend bindings: each stored control-point delta row
		 * plus the lattice's grid-at-default reference, and the same treatment for the deformer's own
		 * render channels (opacity, multiply / screen color), which CASCADE onto every drawable
		 * underneath.
		 *
		 * @param Deformer.Warp       warp    The constructed runtime warp (its grid is the reference source).
		 * @param PointSpace          space   The warp's stored point space.
		 * @param List<MocBlendShape> records The warp's records.
		 * @return List<BlendShapeBinding<WarpForm>> The runtime bindings.
		 */
		fun warpBlendShapesOf(
			warp: Deformer.Warp,
			space: PointSpace,
			records: List<MocBlendShape>,
		): List<BlendShapeBinding<WarpForm>> {
			val reference = warpControlPointsAt(warp.geometryGrid, defaultValue) ?: FloatArray(0)
			val referenceOpacity = warp.channelGrids.scalarAt(FormChannel.OPACITY, warp.opacity, defaultValue)
			val referenceMultiply =
				warp.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, warp.multiplyColor, defaultValue)
			val referenceScreen = warp.channelGrids.colorAt(FormChannel.SCREEN_COLOR, warp.screenColor, defaultValue)
			return records.mapNotNull { record ->
				val payloads =
					record.keyforms.map { keyform ->
						(keyform as? BlendShapeKeyform.Warp)?.form ?: return@mapNotNull null
					}
				if (payloads.size != record.keyPositions.size) {
					return@mapNotNull null
				}
				bindingOfRecord(record) { keyIndex ->
					WarpForm(
						addReference(reference, convertDeltas(space, payloads[keyIndex].controlPoints)),
						opacity = referenceOpacity + payloads[keyIndex].opacity,
						multiplyColor = addColorDelta(referenceMultiply, payloads[keyIndex].multiplyColor),
						screenColor = addColorDelta(referenceScreen, payloads[keyIndex].screenColor),
					)
				}
			}
		}

		/**
		 * Maps [records] onto [rotation] as affine blend bindings: origin/angle/scale delta rows plus
		 * the grid-at-default reference.  The scale delta carries the same px→model seam factor as
		 * the grid keyforms; flips are not blendable, so the FLIP tracks' value at the default pose
		 * fills the form.  The deformer's own opacity/color rows get the same reference treatment as
		 * the geometry and CASCADE onto every drawable underneath (see `DeformerCascade`).
		 *
		 * @param Deformer.Rotation   rotation    The constructed runtime rotation (reference source).
		 * @param PointSpace          space       The rotation's stored point space.
		 * @param Float               scaleFactor The px→model seam factor (1 under a rotation ancestor, else ppu).
		 * @param List<MocBlendShape> records     The rotation's records.
		 * @return List<BlendShapeBinding<RotationForm>> The runtime bindings.
		 */
		fun rotationBlendShapesOf(
			rotation: Deformer.Rotation,
			space: PointSpace,
			scaleFactor: Float,
			records: List<MocBlendShape>,
		): List<BlendShapeBinding<RotationForm>> {
			// The fallback mirrors rotationBlendDeltas' (identity transform, scale 1) so the
			// evaluator's subtraction cancels exactly even for an unkeyed rotation.
			val reference =
				rotationFormAt(rotation.geometryGrid, defaultValue)
					?: RotationPivotForm(0f, 0f, 0f, 1f)
			val referenceOpacity = rotation.channelGrids.scalarAt(FormChannel.OPACITY, rotation.opacity, defaultValue)
			val referenceMultiply =
				rotation.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, rotation.multiplyColor, defaultValue)
			val referenceScreen =
				rotation.channelGrids.colorAt(FormChannel.SCREEN_COLOR, rotation.screenColor, defaultValue)
			return records.mapNotNull { record ->
				val payloads =
					record.keyforms.map { keyform ->
						(keyform as? BlendShapeKeyform.Rotation)?.form ?: return@mapNotNull null
					}
				if (payloads.size != record.keyPositions.size) {
					return@mapNotNull null
				}
				bindingOfRecord(record) { keyIndex ->
					val originDelta =
						convertDeltas(space, floatArrayOf(payloads[keyIndex].originX, payloads[keyIndex].originY))
					RotationForm(
						originX = reference.originX + originDelta[0],
						originY = reference.originY + originDelta[1],
						angle = reference.angle + payloads[keyIndex].angle,
						scale = reference.scale + payloads[keyIndex].scale * scaleFactor,
						// The reference pivot carries no flips - reflections are FLAG channels on the deformer,
						// and a blend shape never varies them (MOC3 stores no flip delta rows).  Sampled from
						// the FLIP tracks at the default pose: at this point in the import the statics are still
						// their constructor defaults (compaction lifts constant flip tracks only at the end),
						// so reading rotation.flipX here would be constant false and drop the reflection.
						flipX = rotation.channelGrids.flagAt(FormChannel.FLIP_X, rotation.flipX, defaultValue),
						flipY = rotation.channelGrids.flagAt(FormChannel.FLIP_Y, rotation.flipY, defaultValue),
						opacity = referenceOpacity + payloads[keyIndex].opacity,
						multiplyColor = addColorDelta(referenceMultiply, payloads[keyIndex].multiplyColor),
						screenColor = addColorDelta(referenceScreen, payloads[keyIndex].screenColor),
					)
				}
			}
		}

		// A bake stores no deformer display name, so the label is the id - readable, but it says nothing
		// about what the deformer moves.  Where the answer is unambiguous, the drawable it deforms is
		// appended as an anchor: "Warp40 (ArtMesh5)".  See [soleDrawableByDeformer] for what counts.
		val soleDrawable = soleDrawableByDeformer(mocDocument)

		/**
		 * The display name of the deformer at [deformerIndex]: its id, plus the drawable it deforms
		 * when exactly one is in reach.
		 *
		 * @param Int        deformerIndex The deformer's file index.
		 * @param DeformerId id            The deformer's resolved runtime id.
		 * @return String The display name.
		 */
		fun deformerNameOf(deformerIndex: Int, id: DeformerId): String {
			val anchorIndex = soleDrawable[deformerIndex] ?: return id.raw
			val anchorName = mocDocument.artMeshes.getOrNull(anchorIndex)?.id ?: return id.raw
			return "${id.raw} ($anchorName)"
		}

		val deformers =
			mocDocument.deformers.mapIndexed { deformerIndex, source ->
				val id = deformerIds[deformerIndex]
				val parent = deformerIds.getOrNull(source.parentDeformerIndex)
				val keyformSpace = pointSpaceOf(source.parentDeformerIndex)
				val binding = bindingOf(source.keyformBindingIndex)
				when (source) {
					is WarpDeformer -> {
						// One bundled grid, then split into lattice geometry and the render tracks that cascade
						// down onto every drawable under this deformer.
						val fannedWarp =
							gridOf(binding) { gridIndex ->
								source.keyforms.getOrNull(gridIndex)?.let { keyform ->
									WarpForm(
										convertPoints(keyformSpace, keyform.controlPoints),
										opacity = keyform.opacity,
										multiplyColor = colorRgbOf(keyform.multiplyColor) ?: ColorRgb.MultiplyIdentity,
										screenColor = colorRgbOf(keyform.screenColor) ?: ColorRgb.ScreenIdentity,
									)
								}
							}?.fanOutWarp()
						val warp =
							Deformer.Warp(
								id = id,
								name = deformerNameOf(deformerIndex, id),
								parent = parent,
								// MOC3 §5.6 s15: the deformer's own org-tree part; -1 (→ null) at the root.
								partId = partIds.getOrNull(source.parentPartIndex),
								// MOC3 §5.6 s13/s14: the editor's eye toggle and its unpinned partner.
								isVisible = source.isVisible,
								isEnabled = source.isEnabled,
								rows = source.rows,
								columns = source.columns,
								// MOC3 §5.6 warp mode: 0 = triangle split, non-zero = bilinear (quad).
								isQuadTransform = source.mode != 0,
								geometryGrid = fannedWarp?.geometry,
								channelGrids = fannedWarp?.channels ?: ChannelGrids.Empty,
							)
						val warpRecords = blendRecordsByTarget[BlendShapeTarget.WARP to deformerIndex].orEmpty()
						if (warpRecords.isEmpty()) {
							warp
						} else {
							warp.copy(blendShapes = warpBlendShapesOf(warp, keyformSpace, warpRecords))
						}
					}

					is RotationDeformer -> {
						val scaleFactor = rotationScaleFactor(hasRotationAncestor[deformerIndex], canvasMapping)
						// One bundled grid, then split into the pivot geometry, the render tracks that cascade
						// down onto every drawable under this deformer, and the two reflection flags.
						val fannedRotation =
							gridOf(binding) { gridIndex ->
								source.keyforms.getOrNull(gridIndex)?.let { keyform ->
									val origin =
										convertPoints(keyformSpace, floatArrayOf(keyform.originX, keyform.originY))
									RotationForm(
										originX = origin[0],
										originY = origin[1],
										angle = keyform.angle,
										scale = keyform.scale * scaleFactor,
										flipX = keyform.reflectX,
										flipY = keyform.reflectY,
										opacity = keyform.opacity,
										multiplyColor = colorRgbOf(keyform.multiplyColor) ?: ColorRgb.MultiplyIdentity,
										screenColor = colorRgbOf(keyform.screenColor) ?: ColorRgb.ScreenIdentity,
									)
								}
							}?.fanOutRotation()
						val rotation =
							Deformer.Rotation(
								id = id,
								name = deformerNameOf(deformerIndex, id),
								parent = parent,
								partId = partIds.getOrNull(source.parentPartIndex),
								isVisible = source.isVisible,
								isEnabled = source.isEnabled,
								baseAngle = source.baseAngle,
								geometryGrid = fannedRotation?.geometry,
								channelGrids = fannedRotation?.channels ?: ChannelGrids.Empty,
							)
						val rotationRecords = blendRecordsByTarget[BlendShapeTarget.ROTATION to deformerIndex].orEmpty()
						if (rotationRecords.isEmpty()) {
							rotation
						} else {
							rotation.copy(
								blendShapes =
									rotationBlendShapesOf(
										rotation,
										keyformSpace,
										scaleFactor,
										rotationRecords,
									),
							)
						}
					}
				}
			}

		val drawables =
			mocDocument.artMeshes.mapIndexed { drawableIndex, source ->
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
				// One bundled grid, then split into per-vertex deltas and the render channels.
				val fannedMesh =
					gridOf(binding) { gridIndex ->
						source.keyforms.getOrNull(gridIndex)?.let { keyform ->
							MeshForm(
								positionDeltas =
									deltaVsBase(
										basePositions,
										convertPoints(space, keyform.vertexPositions),
									),
								drawOrder = keyform.drawOrder,
								opacity = keyform.opacity,
								// MOC3 color-table rows 108-113: the 5.3 per-art-mesh multiply/screen color; null
								// (pre-5.3, no color table) falls back to the tint identities.
								multiplyColor = colorRgbOf(keyform.multiplyColor) ?: ColorRgb.MultiplyIdentity,
								screenColor = colorRgbOf(keyform.screenColor) ?: ColorRgb.ScreenIdentity,
							)
						}
					}?.fanOutMesh()
				val drawable =
					Drawable(
						id = DrawableId(source.id),
						// The MOC3 itself carries no drawable names; only the cdi3 Meshes extension does, so a
						// file the official editor wrote falls back to the format id.
						name = drawableNameById[source.id] ?: source.id,
						parentDeformerId = deformerIds.getOrNull(source.parentDeformerIndex),
						// MOC3 v6 §5.6 s153: a nonzero packed extended blend overrides the legacy 2-bit
						// constant-flags field (which then only carries the old-runtime approximation).
						blendMode =
							if (source.extendedBlend != 0) {
								colorBlendOfPacked(source.extendedBlend)
							} else {
								blendModeOf(source.constantFlags)
							},
						alphaBlendMode = alphaBlendOfPacked(source.extendedBlend),
						// MOC3 §5.6 MASK_INDEX_DATA: mask sources are drawable file indices.
						maskedBy =
							source.maskDrawableIndices.toList()
								.mapNotNull { maskIndex -> drawableIdsByFileIndex.getOrNull(maskIndex) },
						invertMask = source.constantFlags and ConstantFlag.IS_INVERTED_MASK != 0,
						// MOC3 §5.5: constant-flags bit 2 is IS_DOUBLE_SIDED; culling is its inverse.
						culling = source.constantFlags and ConstantFlag.IS_DOUBLE_SIDED == 0,
						// MOC3 §5.6 s37: the editor's eye toggle.  A bake normally deletes what is hidden, so
						// this is true for almost every imported drawable - but a file exported with hidden
						// meshes kept carries the flag, and Umamo's own export always does.
						isVisible = source.isVisible,
						// Lock IS editor-only authoring state the bake drops, so everything imports unlocked.
						isSelectable = true,
						// MOC3 §5.6 s41: the atlas page this mesh samples, so a detached model can still say.
						texturePage = source.textureIndex,
						mesh = mesh,
						geometryGrid = fannedMesh?.geometry,
						channelGrids = fannedMesh?.channels ?: ChannelGrids.Empty,
					)
				val meshRecords = blendRecordsByTarget[BlendShapeTarget.ART_MESH to drawableIndex].orEmpty()
				if (meshRecords.isEmpty()) {
					drawable
				} else {
					drawable.copy(blendShapes = meshBlendShapesOf(drawable, space, meshRecords))
				}
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
				val intensityTrack =
					gridOf(bindingOf(source.keyformBindingIndex)) { gridIndex ->
						GlueForm(
							source.intensityKeyforms.getOrElse(gridIndex) {
								source.intensityKeyforms.lastOrNull() ?: 1f
							},
						)
					}?.asChannelTrack { form -> ChannelValue.Scalar(form.intensity) }
				// A glue with no keyed intensity welds fully, which is the runtime's long-standing fallback.
				Glue(
					meshA,
					meshB,
					pairs,
					channelGridsOf(FormChannel.GLUE_INTENSITY to intensityTrack),
					intensity = 1f,
					// MOC3 §5.6 s90: the authored name, so a round trip does not synthesize a new one.
					id = source.id.takeIf { it.isNotEmpty() },
				)
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
			val drawOrder =
				source.drawOrderKeyforms.getOrElse(defaultCell) {
					source.drawOrderKeyforms.firstOrNull() ?: DEFAULT_DRAW_ORDER.toFloat()
				}
			return (drawOrder + 0.001f).toInt()
		}

		// MOC3 v6 §5.6 s155: each offscreen names its owner part; index the offscreens by the owner's
		// id so the part builders below can look their own up (part ids are unique in the moc).
		val offscreenByPartId =
			mocDocument.offscreens
				.filter { offscreen -> offscreen.ownerPartIndex in mocDocument.parts.indices }
				.associateBy { offscreen -> mocDocument.parts[offscreen.ownerPartIndex].id }

		/**
		 * The compositing settings of a moc part, or null when the part owns no offscreen (i.e. its
		 * group mode is not Isolated).
		 *
		 * @param MocPart source The moc part.
		 * @return PartComposite? The runtime compositing settings, or null.
		 */
		fun partCompositeOf(source: MocPart): PartComposite? {
			val offscreen = offscreenByPartId[source.id] ?: return null
			// The first keyform doubles as the static fallback (a static part stores exactly one row).
			val staticKeyform = offscreen.keyforms.firstOrNull()
			return PartComposite(
				// MOC3 v6 §5.6 s157: packed colorMode | (alphaMode shl 8).
				blendMode = colorBlendOfPacked(offscreen.blendMode),
				alphaBlendMode = alphaBlendOfPacked(offscreen.blendMode),
				// MOC3 §5.6: offscreen mask sources are drawable file indices (the MASK_INDEX_DATA prefix).
				maskedBy =
					offscreen.maskIndices.toList()
						.mapNotNull { maskIndex -> drawableIdsByFileIndex.getOrNull(maskIndex) },
				// MOC3 v6 §5.6 s156 bit 3: invert clipping mask (same bit position as the drawable flag).
				invertMask = offscreen.constantFlags and ConstantFlag.IS_INVERTED_MASK != 0,
				opacity = staticKeyform?.opacity ?: 1f,
				multiplyColor = colorRgbOf(staticKeyform?.multiplyColor) ?: ColorRgb.MultiplyIdentity,
				screenColor = colorRgbOf(staticKeyform?.screenColor) ?: ColorRgb.ScreenIdentity,
			)
		}

		/**
		 * Maps [records] onto a part as draw-order blend bindings.
		 *
		 * A part's only blendable channel is its draw order, so a record carries a single scalar delta
		 * per key.  It gets the same reference treatment as every other blend payload - the stored delta
		 * plus the channel's value at the DEFAULT pose - so the evaluator's subtraction cancels exactly.
		 *
		 * @param MocPart             source       The moc part.
		 * @param Float               staticOrder  The part's static draw order.
		 * @param ChannelGrids        channelGrids The part's own keyform tracks (the reference source).
		 * @param List<MocBlendShape> records      The part's records.
		 * @return List<BlendShapeBinding<PartForm>> The runtime bindings.
		 */
		fun partBlendShapesOf(
			source: MocPart,
			staticOrder: Float,
			channelGrids: ChannelGrids,
			records: List<MocBlendShape>,
		): List<BlendShapeBinding<PartForm>> {
			val referenceDrawOrder = channelGrids.scalarAt(FormChannel.DRAW_ORDER, staticOrder, defaultValue)
			return records.mapNotNull { record ->
				val payloads =
					record.keyforms.map { keyform ->
						(keyform as? BlendShapeKeyform.Part)?.drawOrderDelta ?: return@mapNotNull null
					}
				if (payloads.size != record.keyPositions.size) {
					return@mapNotNull null
				}
				bindingOfRecord(record) { keyIndex ->
					PartForm(drawOrder = referenceDrawOrder + payloads[keyIndex])
				}
			}
		}

		/**
		 * [partBlendShapesOf] with this part's records looked up, the shape [buildOrgTree] consumes.
		 *
		 * Takes the part's file index rather than searching for it by id.  A moc addresses blend records
		 * by index and nothing guarantees the id strings are unique, so resolving the index from the id
		 * would give two same-named parts the SAME records - the first part's, applied twice, with the
		 * second part's own records never read.  The caller is iterating by index already.
		 *
		 * @param MocPart      source       The moc part.
		 * @param Int          partIndex    The part's file index.
		 * @param ChannelGrids channelGrids The part's tracks, already built by the caller.
		 * @return List<BlendShapeBinding<PartForm>> The runtime bindings, empty when the part has none.
		 */
		fun partBlendShapesOfBound(
			source: MocPart,
			partIndex: Int,
			channelGrids: ChannelGrids,
		): List<BlendShapeBinding<PartForm>> {
			val records = blendRecordsByTarget[BlendShapeTarget.PART to partIndex].orEmpty()
			if (records.isEmpty()) {
				return emptyList()
			}
			return partBlendShapesOf(source, partStaticDrawOrder(source).toFloat(), channelGrids, records)
		}

		/**
		 * The parameter-driven per-channel tracks of a moc part, empty when the part is static.  Carries
		 * the draw order always; for an isolated part the offscreen's keyformed opacity/color
		 * channels merge in, riding the same grid cells (MOC3 §5.6: Σ owner grid == CountInfo 36).
		 *
		 * @param MocPart source The moc part.
		 * @return ChannelGrids The part's per-channel tracks, empty when it is unbound.
		 */
		fun partChannelsOf(source: MocPart): ChannelGrids {
			if (source.keyformBindingIndex <= 0) {
				return ChannelGrids.Empty
			}
			val offscreenKeyforms = offscreenByPartId[source.id]?.keyforms
			val bundled =
				gridOf(bindingOf(source.keyformBindingIndex)) { gridIndex ->
					source.drawOrderKeyforms.getOrNull(gridIndex)?.let { drawOrder ->
						val offscreenKeyform = offscreenKeyforms?.getOrNull(gridIndex)
						PartForm(
							drawOrder = drawOrder,
							opacity = offscreenKeyform?.opacity ?: 1f,
							multiplyColor = colorRgbOf(offscreenKeyform?.multiplyColor) ?: ColorRgb.MultiplyIdentity,
							screenColor = colorRgbOf(offscreenKeyform?.screenColor) ?: ColorRgb.ScreenIdentity,
						)
					}
				} ?: return ChannelGrids.Empty
			// Fan the one bundled grid out into per-channel tracks sharing its axes: a pure re-shape, so the
			// blended values are bit-identical to what the bundled cell produced.
			return channelGridsOf(
				FormChannel.DRAW_ORDER to bundled.asChannelTrack { form -> ChannelValue.Scalar(form.drawOrder) },
				FormChannel.OPACITY to bundled.asChannelTrack { form -> ChannelValue.Scalar(form.opacity) },
				FormChannel.MULTIPLY_COLOR to bundled.asChannelTrack { form -> ChannelValue.Color(form.multiplyColor) },
				FormChannel.SCREEN_COLOR to bundled.asChannelTrack { form -> ChannelValue.Color(form.screenColor) },
			)
		}

		val renderRoot =
			buildRenderRoot(
				mocDocument,
				drawableIdsByFileIndex,
				partIds,
				::partStaticDrawOrder,
				::partChannelsOf,
				::partCompositeOf,
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
				::partChannelsOf,
				::partBlendShapesOfBound,
				::partCompositeOf,
			)

		// The flat drawables list is kept back-to-front (the storage/base order, mirroring Cmo3Import's
		// panel-derived ordering); unplaced drawables keep file order at the back.
		val orderedDrawables =
			drawables.sortedByDescending { drawable -> panelIndexByDrawable[drawable.id] ?: Int.MAX_VALUE }

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
				// Retained purely so an export can invert this import's space conversions; the evaluator and
				// the renderer never read it.  The CANVAS's own value, not the 1f identity a canvas-less
				// model falls back to above - an export has to be able to tell "this moc baked at 1" from
				// "this document never had a bake scale" and pick its own default for the second.
				pixelsPerUnit = canvas?.pixelsPerUnit,
				// MOC3 §3 Version Gating: the version byte is a hard fact of the baked file, so the import
				// starts at the matching Cubism target rather than NoTarget.
				runtimeTarget = runtimeTargetOfMocVersion(mocDocument.version),
			)
		// Section 15 is authoritative when it places anything.  When it places NOTHING - a stripped or
		// synthesized MOC3 that omits or zeroes the section, which MocDecoder reads defensively for - the
		// org tree would be a flat root, so fall back to inferring membership from the drawables.
		val withDeformerParts =
			if (deformers.isNotEmpty() && deformers.all { deformer -> deformer.partId == null }) {
				model.copy(deformers = inferDeformerParts(deformers, orderedDrawables, model.partByDrawable()))
			} else {
				model
			}
		val withRenderRoot =
			if (renderRoot != null) {
				withDeformerParts.copy(renderRoot = renderRoot)
			} else {
				withDeformerParts.copy(renderRoot = withDeformerParts.deriveRenderRoot())
			}
		return if (compactChannels) withRenderRoot.withChannelsCompacted() else withRenderRoot
	}

	/**
	 * Synthesizes an id for a deformer slot the file leaves blank (or duplicates).
	 *
	 * The result joins [claimedIds], so it collides neither with an id the file already uses nor with
	 * another synthesized one.
	 *
	 * @param Int        deformerIndex The deformer's file index.
	 * @param MutableSet claimedIds    Every id already spoken for; the returned id is added to it.
	 * @return String The synthesized id.
	 */
	private fun synthesizedDeformerId(deformerIndex: Int, claimedIds: MutableSet<String>): String {
		var candidate = "Deformer$deformerIndex"
		var disambiguator = 2
		while (!claimedIds.add(candidate)) {
			candidate = "Deformer$deformerIndex-$disambiguator"
			disambiguator++
		}
		return candidate
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
	 * @param Function    partChannelsOf         Per-channel keyform tracks of a moc part.
	 * @param Function    partCompositeOf        Compositing settings of a moc part (null when not isolated).
	 * @return RenderGroup? The render root, or null when the moc has no render-order groups.
	 */
	private fun buildRenderRoot(
		mocDocument: MocDocument,
		drawableIdsByFileIndex: List<DrawableId>,
		partIds: List<PartId>,
		partStaticDrawOrder: (MocPart) -> Int,
		partChannelsOf: (MocPart) -> ChannelGrids,
		partCompositeOf: (MocPart) -> PartComposite?,
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
							channelGrids = partChannelsOf(part),
							composite = partCompositeOf(part),
						)
					}

					else -> null
				}
			}
		}

		val root = RenderGroup(null, DEFAULT_DRAW_ORDER, childrenOf(0))
		// Safety net, mirroring deriveRenderRoot's: the renderer draws EXCLUSIVELY from this tree, so
		// a drawable the stored groups never place (out-of-range child index, an unknown future child
		// kind, or a truncated tree) would silently never render.  Append the missing leaves at the
		// root, where they sort by their own draw order like any other root-level drawable.
		val placedLeaves = ArrayList<DrawableId>()
		collectRenderLeaves(root, placedLeaves)
		val placedDrawableIds = placedLeaves.toHashSet()
		val missingLeaves =
			drawableIdsByFileIndex.filter { drawableId -> drawableId !in placedDrawableIds }.map(::RenderDrawable)
		return if (missingLeaves.isEmpty()) {
			root
		} else {
			root.copy(children = root.children + missingLeaves)
		}
	}

	/**
	 * The one drawable each deformer deforms, for the deformers where "the one" is unambiguous.
	 *
	 * Used only to label a deformer, whose authored name a bake drops.  A deformer's own drawables
	 * decide it when it has any; otherwise its whole descendant subtree does, so a rotation that
	 * only drives a warp still names the mesh at the bottom of the chain.  Either way the answer is
	 * a CARDINALITY, not a vote: a deformer over several drawables has no single answer and is left
	 * out, because drawables - unlike parts - are distinct entities rather than a category several
	 * of them can agree on, so picking a winner would just be naming the deformer after an arbitrary
	 * one of its meshes.  Being an anchor rather than data, an absent entry costs a label, nothing more.
	 *
	 * @param MocDocument mocDocument The decoded document (file indices throughout).
	 * @return Map Deformer file index → its sole drawable's file index, for the unambiguous ones.
	 */
	private fun soleDrawableByDeformer(mocDocument: MocDocument): Map<Int, Int> {
		val deformerCount = mocDocument.deformers.size
		if (deformerCount == 0) {
			return emptyMap()
		}
		val ambiguous = -1
		val direct = HashMap<Int, Int>()
		val subtree = HashMap<Int, Int>()

		/**
		 * Records [drawableIndex] against [deformerIndex], marking the slot ambiguous on a second hit.
		 *
		 * @param HashMap into          The tally to record into.
		 * @param Int     deformerIndex The owning deformer's file index.
		 * @param Int     drawableIndex The drawable's file index.
		 */
		fun record(into: HashMap<Int, Int>, deformerIndex: Int, drawableIndex: Int) {
			into[deformerIndex] = if (deformerIndex in into) ambiguous else drawableIndex
		}
		for ((drawableIndex, artMesh) in mocDocument.artMeshes.withIndex()) {
			var ancestorIndex = artMesh.parentDeformerIndex
			if (ancestorIndex !in 0 until deformerCount) {
				continue
			}
			record(direct, ancestorIndex, drawableIndex)
			// Walk to the root so an ancestor that deforms no drawable directly still sees the meshes
			// below it; the visited set guards a malformed cyclic chain.
			val visited = HashSet<Int>()
			while (ancestorIndex in 0 until deformerCount && visited.add(ancestorIndex)) {
				record(subtree, ancestorIndex, drawableIndex)
				ancestorIndex = mocDocument.deformers[ancestorIndex].parentDeformerIndex
			}
		}
		val resolved = HashMap<Int, Int>(deformerCount)
		for (deformerIndex in 0 until deformerCount) {
			val drawableIndex = direct[deformerIndex] ?: subtree[deformerIndex] ?: continue
			if (drawableIndex != ambiguous) {
				resolved[deformerIndex] = drawableIndex
			}
		}
		return resolved
	}

	/**
	 * Infers each deformer's organisational part from the drawables it deforms.  A FALLBACK only: MOC3
	 * §5.6 s15 carries real deformer→part membership, and this runs solely when that section placed
	 * nothing at all.
	 *
	 * A MOC3 whose s15 is absent or entirely -1 - a stripped or synthesized file, or one that
	 * genuinely puts every deformer at the root - converts to a flat org tree with an unusable
	 * parts panel.  The two cases are indistinguishable in a bake, and inference is the better
	 * outcome for both, so this reconstructs the grouping from the one signal that is still
	 * present: a deformer's drawables almost always live in the deformer's own part.  The rule is
	 * the plurality part of the DIRECTLY deformed drawables, falling back to the whole descendant
	 * subtree for a deformer that only deforms other deformers.
	 *
	 * This is INFERENCE, not recovery.  Measured against the corpus twins (each model's
	 * editor-written CMO3 against its own bake) it reproduced 81-93% of the original placements,
	 * and a deformer whose drawables span several parts can land in any of them - which is why it
	 * never overrides s15.  A CMO3-origin document never reaches here: it carries the real
	 * membership on ACParameterControllableSource.parentGuid.
	 *
	 * @param List deformers      The imported deformers, none of which resolved a part.
	 * @param List drawables      The imported drawables.
	 * @param Map  partByDrawable Each drawable's org-tree part (null at the root).
	 * @return List The deformers with inferred [Deformer.partId] values.
	 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6 s15</a>
	 */
	private fun inferDeformerParts(
		deformers: List<Deformer>,
		drawables: List<Drawable>,
		partByDrawable: Map<DrawableId, PartId?>,
	): List<Deformer> {
		val parentById = deformers.associate { deformer -> deformer.id to deformer.parent }
		val directParts = HashMap<DeformerId, MutableList<PartId>>()
		val subtreeParts = HashMap<DeformerId, MutableList<PartId>>()
		for (drawable in drawables) {
			val partId = partByDrawable[drawable.id] ?: continue
			val ownerId = drawable.parentDeformerId ?: continue
			directParts.getOrPut(ownerId) { ArrayList() }.add(partId)
			// Walk the deformer chain so an ancestor that deforms no drawable directly still sees the
			// parts below it; the visited set guards a malformed cyclic chain.
			var ancestorId: DeformerId? = ownerId
			val visited = HashSet<DeformerId>()
			while (ancestorId != null && visited.add(ancestorId)) {
				subtreeParts.getOrPut(ancestorId) { ArrayList() }.add(partId)
				ancestorId = parentById[ancestorId]
			}
		}

		/**
		 * The plurality part id of a candidate list, or null when empty.
		 *
		 * @param List? candidates The observed part ids.
		 * @return PartId? The most frequent id.
		 */
		fun plurality(candidates: List<PartId>?): PartId? =
			candidates?.groupingBy { partId -> partId }?.eachCount()?.maxByOrNull { entry -> entry.value }?.key

		return deformers.map { deformer ->
			val inferred = plurality(directParts[deformer.id]) ?: plurality(subtreeParts[deformer.id])
			when {
				inferred == null -> deformer
				deformer is Deformer.Warp -> deformer.copy(partId = inferred)
				deformer is Deformer.Rotation -> deformer.copy(partId = inferred)
				else -> deformer
			}
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
	 * @param Function    partChannelsOf            Per-channel keyform tracks of a moc part.
	 * @param Function    partBlendShapesOf         Draw-order blend bindings of a moc part, given its file
	 *                                              index and its tracks.
	 * @param Function    partCompositeOf           Compositing settings of a moc part (null when not isolated).
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
		partChannelsOf: (MocPart) -> ChannelGrids,
		partBlendShapesOf: (MocPart, Int, ChannelGrids) -> List<BlendShapeBinding<PartForm>>,
		partCompositeOf: (MocPart) -> PartComposite?,
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
				entries.add(
					ChildEntry(
						OrgChild.Drawable(drawableId),
						panelIndexByDrawable[drawableId] ?: Int.MAX_VALUE,
					),
				)
			}
			return entries.sortedBy { entry -> entry.panelIndex }.map { entry -> entry.child }
		}

		val parts =
			mocDocument.parts.mapIndexed { partIndex, source ->
				// MOC3 (runtime format) only records composite data for offscreen parts, so this is null for
				// the rest; the composite is stored latently and applied only while the part is Isolated.
				val offscreenComposite = partCompositeOf(source)
				val partChannels = partChannelsOf(source)
				Part(
					id = partIds[partIndex],
					// cdi3: DisplayPart.name is the display label; fall back to the id.
					name = partNameById[source.id] ?: source.id,
					children = childrenOf(partIndex),
					// MOC3 §5.6 s7/s8 carry the part's visibility (both flags, split unpinned).  Sketch and
					// lock ARE editor-only state the bake drops, so those still default to shown/unlocked.
					isVisible = source.isVisible,
					isSketch = false,
					isSelectable = true,
					// An owned offscreen wins over render-order-group membership (an isolated part is
					// always grouped; the bake records both).
					groupMode =
						when {
							offscreenComposite != null -> PartGroupMode.Isolated
							partIndex in drawOrderGroupPartIndices -> PartGroupMode.Grouped
							else -> PartGroupMode.PassThrough
						},
					drawOrder = partStaticDrawOrder(source),
					channelGrids = partChannels,
					composite = offscreenComposite ?: PartComposite(),
					// MOC3 v5+ §5.6: a part-target blend record, whose only channel is the draw order.
					blendShapes = partBlendShapesOf(source, partIndex, partChannels),
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
			constantFlags and ConstantFlag.BLEND_ADDITIVE != 0 -> BlendMode.AdditivePremultiplied
			constantFlags and ConstantFlag.BLEND_MULTIPLICATIVE != 0 -> BlendMode.MultiplyPremultiplied
			else -> BlendMode.Normal
		}

	/**
	 * Converts a decoded moc3 [Rgb] to the runtime [ColorRgb].
	 *
	 * @param Rgb? color The decoded color row (MOC3 color tables 108-113), or null when absent.
	 * @return ColorRgb? The runtime color, or null.
	 */
	private fun colorRgbOf(color: Rgb?): ColorRgb? = color?.let { ColorRgb(it.r, it.g, it.b) }

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
