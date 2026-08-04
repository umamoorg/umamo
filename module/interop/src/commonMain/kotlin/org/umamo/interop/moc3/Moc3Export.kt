package org.umamo.interop.moc3

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.GlueVertexPair
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.format.moc3.model.WarpKeyform
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.legacyBlendFlagOf
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable
import org.umamo.format.moc3.moc.MocParameter as MocParameter
import org.umamo.format.moc3.model.Glue as MocGlue
import org.umamo.format.moc3.model.Part as MocPart

/**
 * Lowers a [PuppetModel] into a [MocDocument] - the semantic half of writing a `.moc3` from a rig
 * that may never have been one.
 *
 * This is a FULL SYNTHESIS, deliberately unlike the CMO3 export's state-based reconcile.  That
 * reconcile exists to preserve unmodeled XML the writer does not understand; a MOC3 has no such
 * payload once every section index is modeled, so there is nothing to carry and a reference
 * container would only constrain the output.  A CMO3-origin or future UMA-origin document
 * therefore exports exactly like a MOC3-origin one.
 *
 * THE LOAD-BEARING INVARIANT, which every geometry path here depends on:
 * `drawable.mesh.positions[i] + cell.positionDeltas[i]` is the drawable's ABSOLUTE position in its
 * parent-deformer space, for every document origin.  `restMeshesToCanvasSpace` rewrites the base
 * and compensates the deltas so the sum is untouched, and CMO3 stores the same mixed-space
 * convention natively - which is what lets one lowering serve both.
 *
 * An export ALWAYS writes.  Anything it cannot express becomes an [ExportNotice] rather than a
 * silent drop, including hidden objects, which are CARRIED with their flag clear rather than
 * deleted the way the official editor's bake deletes them.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
object Moc3Export {
	/** The Cubism draw-order default, used when a drawable or part carries no track. */
	private const val DEFAULT_DRAW_ORDER: Float = 500f

	/**
	 * The lowered document plus whatever the lowering could not express.
	 *
	 * @property MocDocument document The document to bake.
	 * @property ExportReport report  The advisory findings; empty for a fully-lowered export.
	 */
	class Lowered(val document: MocDocument, val report: ExportReport)

	/**
	 * Lowers [puppet] into a [MocDocument] at [version].
	 *
	 * @param PuppetModel puppet  The rig to export.
	 * @param MocVersion  version The moc version to target.
	 * @return Lowered The document and its notices.
	 */
	fun toMocDocument(puppet: PuppetModel, version: MocVersion): Lowered {
		val notices = ArrayList<ExportNotice>()
		val plan = Moc3IndexPlan.of(puppet)
		val canvas = MocCanvasMapping(puppet.pixelsPerUnit, puppet.worldOriginX, -puppet.worldOriginY)
		val pool = Moc3KeyformPool { parameterId -> plan.parameterIndex(parameterId) }
		val rotationAncestors = rotationAncestorsById(plan.deformers)

		/**
		 * Records a notice for something the lowering could not express.
		 *
		 * @param String category The entity category.
		 * @param String subject  The entity's id.
		 * @param String detail   What was not lowered.
		 */
		fun unsupported(category: String, subject: String, detail: String) {
			notices.add(ExportNotice.UnsupportedChange(category, subject, detail))
		}

		/**
		 * Reports every channel a bundle had to drop to its static.
		 *
		 * @param String              category The entity category.
		 * @param String              subject  The entity's id.
		 * @param Moc3ObjectKeyforms? keyforms The lowered keyforms, or null when unrepresentable.
		 */
		fun reportDemotions(category: String, subject: String, keyforms: Moc3ObjectKeyforms?) {
			for (channel in keyforms?.demotedChannels.orEmpty()) {
				unsupported(
					category,
					subject,
					"$channel is keyed over a narrower span than the object's grid, so it was written " +
						"as a constant (MOC3 stores one grid per object)",
				)
			}
		}

		// ---- parameters ----
		val parameters =
			plan.parameters.map { parameter ->
				MocParameter(
					id = parameter.id.raw,
					minimumValue = parameter.min,
					maximumValue = parameter.max,
					defaultValue = parameter.default,
					// Parameter.Types is moc 4+; below that every parameter is normal and the section is absent.
					type =
						when {
							version.byteValue < 4 -> null
							parameter.kind == ParameterKind.BLEND_SHAPE -> ParameterType.BLEND_SHAPE
							else -> ParameterType.NORMAL
						},
					repeats = parameter.repeat,
				)
			}

		// ---- parts ----
		val parts =
			plan.parts.map { part ->
				val keyforms =
					lowerObjectKeyforms(
						pool,
						null as org.umamo.runtime.model.KeyformGrid<Unit>?,
						UnitInterpolator,
						part.channelGrids.onlyChannels(FormChannel.DRAW_ORDER),
						mapOf(FormChannel.DRAW_ORDER to ChannelValue.Scalar(part.drawOrder.toFloat())),
						requireGeometry = false,
					)
				reportDemotions("part", part.id.raw, keyforms)
				val bundle = keyforms?.bundle
				val cellCount = bundle?.cells?.size ?: 0
				MocPart(
					id = part.id.raw,
					parentPartIndex = plan.partIndex(partParentOf(puppet, part.id)),
					// A static part points at binding 0, which is what the import's `> 0` static test expects.
					keyformBindingIndex = if (cellCount > 1) keyforms!!.bindingIndex else 0,
					drawOrderKeyforms =
						FloatArray(maxOf(cellCount, 1)) { cellIndex ->
							bundle?.let { scalarOf(it, cellIndex, FormChannel.DRAW_ORDER, part.drawOrder.toFloat()) }
								?: part.drawOrder.toFloat()
						},
					isVisible = part.isVisible,
				)
			}

		// ---- deformers ----
		val deformers =
			plan.deformers.map { deformer ->
				val parentPartIndex = plan.partIndex(deformer.partId)
				val parentDeformerIndex = plan.deformerIndex(deformer.parent)
				val space = spaceOfParent(plan, deformer.parent)
				when (deformer) {
					is Deformer.Warp -> {
						val keyforms =
							lowerObjectKeyforms(
								pool,
								deformer.geometryGrid,
								WarpLatticeInterpolator,
								deformer.channelGrids.onlyChannels(FormChannel.OPACITY),
								mapOf(FormChannel.OPACITY to ChannelValue.Scalar(deformer.opacity)),
								requireGeometry = true,
							)
						reportDemotions("deformer", deformer.id.raw, keyforms)
						if (keyforms == null) {
							unsupported(
								"deformer",
								deformer.id.raw,
								"a warp deformer with no control-point grid has no lattice to write",
							)
						}
						val bundle = keyforms?.bundle
						WarpDeformer(
							id = deformer.id.raw,
							keyformBindingIndex = keyforms?.bindingIndex ?: 0,
							isVisible = deformer.isVisible,
							isEnabled = deformer.isEnabled,
							parentPartIndex = parentPartIndex,
							parentDeformerIndex = parentDeformerIndex,
							rows = deformer.rows,
							columns = deformer.columns,
							mode = if (deformer.isQuadTransform) 1 else 0,
							keyforms =
								(0 until (bundle?.cells?.size ?: 0)).map { cellIndex ->
									val lattice =
										bundle!!.cells[cellIndex].geometry as? org.umamo.runtime.model.WarpLatticeForm
									WarpKeyform(
										convertPointsToMoc(space, lattice?.controlPoints ?: FloatArray(0), canvas),
										scalarOf(bundle, cellIndex, FormChannel.OPACITY, deformer.opacity),
										// Colours are moc 4+ and out of scope here; the tables stay absent.
										null,
										null,
									)
								},
						)
					}
					is Deformer.Rotation -> {
						val keyforms =
							lowerObjectKeyforms(
								pool,
								deformer.geometryGrid,
								RotationPivotInterpolator,
								deformer.channelGrids.onlyChannels(
									FormChannel.OPACITY,
									FormChannel.FLIP_X,
									FormChannel.FLIP_Y,
								),
								mapOf(
									FormChannel.OPACITY to ChannelValue.Scalar(deformer.opacity),
									FormChannel.FLIP_X to ChannelValue.Flag(deformer.flipX),
									FormChannel.FLIP_Y to ChannelValue.Flag(deformer.flipY),
								),
								requireGeometry = true,
							)
						reportDemotions("deformer", deformer.id.raw, keyforms)
						if (keyforms == null) {
							unsupported(
								"deformer",
								deformer.id.raw,
								"a rotation deformer with no pivot grid has no transform to write",
							)
						}
						val bundle = keyforms?.bundle
						// Only the FIRST rotation on each root path carries the px->model factor.
						val scaleFactor =
							rotationScaleFactor(rotationAncestors[deformer.id] ?: false, canvas)
						RotationDeformer(
							id = deformer.id.raw,
							keyformBindingIndex = keyforms?.bindingIndex ?: 0,
							isVisible = deformer.isVisible,
							isEnabled = deformer.isEnabled,
							parentPartIndex = parentPartIndex,
							parentDeformerIndex = parentDeformerIndex,
							baseAngle = deformer.baseAngle,
							keyforms =
								(0 until (bundle?.cells?.size ?: 0)).map { cellIndex ->
									val pivot =
										bundle!!.cells[cellIndex].geometry as? org.umamo.runtime.model.RotationPivotForm
									val origin =
										convertPointsToMoc(
											space,
											floatArrayOf(pivot?.originX ?: 0f, pivot?.originY ?: 0f),
											canvas,
										)
									RotationKeyform(
										originX = origin[0],
										originY = origin[1],
										angle = pivot?.angle ?: 0f,
										scale = (pivot?.scale ?: 1f) / (if (scaleFactor != 0f) scaleFactor else 1f),
										reflectX = flagOf(bundle, cellIndex, FormChannel.FLIP_X, deformer.flipX),
										reflectY = flagOf(bundle, cellIndex, FormChannel.FLIP_Y, deformer.flipY),
										opacity = scalarOf(bundle, cellIndex, FormChannel.OPACITY, deformer.opacity),
										multiplyColor = null,
										screenColor = null,
									)
								},
						)
					}
				}
			}

		// ---- art meshes ----
		val artMeshes =
			plan.drawables.mapNotNull { drawable ->
				val mesh = drawable.mesh
				if (mesh == null) {
					unsupported("drawable", drawable.id.raw, "a drawable with no mesh cannot be written")
					return@mapNotNull null
				}
				val space = spaceOfParent(plan, drawable.parentDeformerId)
				if (drawable.geometryGrid == null && drawable.parentDeformerId != null) {
					// The rest mesh is CANVAS-space while a parented drawable stores parent-local values, and
					// with no grid there are no deltas to recover the parent-local form from.  Inverting the
					// deformer chain needs :render's damped-Newton warp inverse, which :interop cannot reach.
					unsupported(
						"drawable",
						drawable.id.raw,
						"an unkeyed drawable under a deformer has no parent-space geometry to write",
					)
					return@mapNotNull null
				}
				val keyforms =
					lowerObjectKeyforms(
						pool,
						drawable.geometryGrid,
						MeshDeltaInterpolator,
						drawable.channelGrids.onlyChannels(FormChannel.DRAW_ORDER, FormChannel.OPACITY),
						mapOf(
							FormChannel.DRAW_ORDER to ChannelValue.Scalar(drawable.drawOrder),
							FormChannel.OPACITY to ChannelValue.Scalar(drawable.opacity),
						),
						requireGeometry = false,
					)
				reportDemotions("drawable", drawable.id.raw, keyforms)
				val bundle = keyforms?.bundle
				val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
				val triangleIndices =
					ShortArray(mesh.indices.size) { index -> mesh.indices[index].toShort() }
				ArtMesh(
					id = drawable.id.raw,
					textureIndex = maxOf(drawable.texturePage, 0),
					constantFlags = constantFlagsOf(drawable),
					extendedBlend = 0,
					isVisible = drawable.isVisible,
					isEnabled = true,
					parentPartIndex = plan.partIndex(drawablePartOf(puppet, drawable.id)),
					parentDeformerIndex = plan.deformerIndex(drawable.parentDeformerId),
					vertexUvs = mesh.uvs.copyOf(),
					triangleIndices = triangleIndices,
					maskDrawableIndices =
						drawable.maskedBy.map { maskId -> plan.drawableIndex(maskId) }.filter { it >= 0 }.toIntArray(),
					keyformBindingIndex = keyforms?.bindingIndex ?: 0,
					keyforms =
						(0 until cellCount).map { cellIndex ->
							// THE load-bearing invariant: base + delta is the absolute parent-space position.
							val deltas =
								(bundle?.cells?.getOrNull(cellIndex)?.geometry as? org.umamo.runtime.model.MeshDeltaForm)
									?.positionDeltas
							val absolute =
								FloatArray(mesh.positions.size) { coordinate ->
									mesh.positions[coordinate] + (deltas?.getOrNull(coordinate) ?: 0f)
								}
							ArtMeshKeyform(
								vertexPositions = convertPointsToMoc(space, absolute, canvas),
								opacity =
									bundle?.let { scalarOf(it, cellIndex, FormChannel.OPACITY, drawable.opacity) }
										?: drawable.opacity,
								drawOrder =
									bundle?.let { scalarOf(it, cellIndex, FormChannel.DRAW_ORDER, drawable.drawOrder) }
										?: drawable.drawOrder,
								multiplyColor = null,
								screenColor = null,
							)
						},
				)
			}

		// ---- glues ----
		val glues =
			puppet.glues.mapIndexedNotNull { glueIndex, glue ->
				val meshA = plan.drawableIndex(glue.meshA)
				val meshB = plan.drawableIndex(glue.meshB)
				if (meshA < 0 || meshB < 0) {
					unsupported("glue", glue.id ?: "Glue$glueIndex", "a glue naming an unknown drawable is dropped")
					return@mapIndexedNotNull null
				}
				val keyforms =
					lowerObjectKeyforms(
						pool,
						null as org.umamo.runtime.model.KeyformGrid<Unit>?,
						UnitInterpolator,
						glue.channelGrids.onlyChannels(FormChannel.GLUE_INTENSITY),
						mapOf(FormChannel.GLUE_INTENSITY to ChannelValue.Scalar(glue.intensity)),
						requireGeometry = false,
					)
				val subject = glue.id ?: "Glue$glueIndex"
				reportDemotions("glue", subject, keyforms)
				val bundle = keyforms?.bundle
				val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
				MocGlue(
					id = glue.id ?: "Glue_${meshA}_${meshB}_",
					meshAIndex = meshA,
					meshBIndex = meshB,
					keyformBindingIndex = keyforms?.bindingIndex ?: 0,
					pairs =
						glue.pairs.map { pair ->
							GlueVertexPair(pair.indexA, pair.indexB, pair.weightA, pair.weightB)
						},
					intensityKeyforms =
						FloatArray(cellCount) { cellIndex ->
							bundle?.let { scalarOf(it, cellIndex, FormChannel.GLUE_INTENSITY, glue.intensity) }
								?: glue.intensity
						},
				)
			}

		val document =
			MocDocument(
				version = version,
				canvas =
					CanvasInfo(
						pixelsPerUnit = puppet.pixelsPerUnit,
						originX = puppet.worldOriginX,
						// The runtime negates the canvas y into world space; storing it re-negates.
						originY = -puppet.worldOriginY,
						width = puppet.canvasWidth,
						height = puppet.canvasHeight,
					),
				parameters = parameters,
				keyformBindings = pool.bindings().associateBy { binding -> binding.index },
				parts = parts,
				deformers = deformers,
				artMeshes = artMeshes,
				glues = glues,
				renderOrderGroups = lowerRenderOrder(puppet, plan),
			)
		return Lowered(document, ExportReport(notices))
	}

	/**
	 * Lowers [puppet] and bakes it to `.moc3` bytes.
	 *
	 * @param PuppetModel puppet  The rig to export.
	 * @param MocVersion  version The moc version to target.
	 * @return Pair The bytes and the advisory report.
	 */
	fun write(puppet: PuppetModel, version: MocVersion): Pair<ByteArray, ExportReport> {
		val lowered = toMocDocument(puppet, version)
		return MocEncoder.bakeFresh(version, lowered.document) to lowered.report
	}

	/**
	 * The space a child of [parentId] stores its positions in - the export's mirror of the import's
	 * `pointSpaceOf`, resolved through the plan so an unknown parent normalizes to root exactly as the
	 * import normalizes an unresolvable index.
	 *
	 * @param Moc3IndexPlan plan     The index plan.
	 * @param DeformerId?   parentId The owning object's parent deformer.
	 * @return PointSpace The space to store in.
	 */
	private fun spaceOfParent(plan: Moc3IndexPlan, parentId: org.umamo.runtime.model.DeformerId?): PointSpace {
		val index = plan.deformerIndex(parentId)
		return when (plan.deformers.getOrNull(index)) {
			is Deformer.Warp -> PointSpace.WarpLattice
			is Deformer.Rotation -> PointSpace.RotationLocal
			null -> PointSpace.ModelRoot
		}
	}

	/**
	 * The drawable's constant-flag byte.
	 *
	 * Note bit 2 is the INVERSE of culling: the flag means "double sided", so a culled drawable clears
	 * it.  Getting that backwards silently double-draws every back face.
	 *
	 * @param org.umamo.runtime.model.Drawable drawable The drawable.
	 * @return Int The flag bits.
	 */
	private fun constantFlagsOf(drawable: org.umamo.runtime.model.Drawable): Int {
		var flags = legacyBlendFlagOf(drawable.blendMode)
		if (!drawable.culling) {
			flags = flags or ConstantFlag.IS_DOUBLE_SIDED
		}
		if (drawable.invertMask) {
			flags = flags or ConstantFlag.IS_INVERTED_MASK
		}
		return flags
	}

	/**
	 * The part a drawable belongs to, from the org tree.
	 *
	 * @param PuppetModel puppet     The rig.
	 * @param DrawableId  drawableId The drawable.
	 * @return PartId? The owning part, or null at the root.
	 */
	private fun drawablePartOf(
		puppet: PuppetModel,
		drawableId: org.umamo.runtime.model.DrawableId,
	): org.umamo.runtime.model.PartId? = puppet.partByDrawable()[drawableId]

	/**
	 * The parent of a part, from the org tree.
	 *
	 * @param PuppetModel puppet The rig.
	 * @param PartId      partId The part.
	 * @return PartId? The parent, or null at the root.
	 */
	private fun partParentOf(
		puppet: PuppetModel,
		partId: org.umamo.runtime.model.PartId,
	): org.umamo.runtime.model.PartId? =
		puppet.parts.firstOrNull { candidate ->
			candidate.children.any { child -> child is org.umamo.runtime.model.OrgChild.Part && child.id == partId }
		}?.id
}

/** A geometry interpolator for owners that have no geometry at all (parts, glues). */
internal object UnitInterpolator : org.umamo.runtime.keyform.FormInterpolator<Unit> {
	override fun interpolate(lower: Unit, upper: Unit, fraction: Float) = Unit

	override fun isExactlyEqual(left: Unit, right: Unit): Boolean = true
}

/**
 * This channel set restricted to [channels] - the tracks the owner's moc block can actually store.
 *
 * A runtime entity may carry tracks a given moc version has no field for (a colour track on a v3
 * export), and bundling those in would widen the grid with axes nothing reads.
 *
 * @param FormChannel channels The channels to keep.
 * @return ChannelGrids The restricted set.
 */
internal fun org.umamo.runtime.model.ChannelGrids.onlyChannels(
	vararg channels: FormChannel,
): org.umamo.runtime.model.ChannelGrids {
	val keep = channels.toSet()
	return org.umamo.runtime.model.ChannelGrids(
		gridsByChannel.filterKeys { channel -> channel in keep },
	)
}
