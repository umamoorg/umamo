package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CRotationDeformerForm
import org.umamo.format.cmo3.model.gen.ACDeformerForm
import org.umamo.format.cmo3.model.gen.ACDrawableForm
import org.umamo.format.cmo3.model.gen.ACForm
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CGlueForm
import org.umamo.format.cmo3.model.gen.CGlueSource
import org.umamo.format.cmo3.model.gen.CPartForm
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerForm
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.KeyformBundle
import org.umamo.interop.KeyformBundleCell
import org.umamo.interop.KeyformBundleResult
import org.umamo.interop.OutOfSpanPolicy
import org.umamo.interop.buildKeyformBundle
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.deltaReaching

/*
 * The keyform half of the CMO3 export reconcile: re-bundling Umamo's split representation (a
 * geometry grid plus independent per-channel tracks plus statics) back into CMO3's all-or-nothing
 * keyform cells, one owner kind at a time.  The grid web that encodes the cells is
 * [Cmo3KeyformGridWeb]'s to write, the morph-target set [Cmo3BlendShapeLowering]'s, and the forms'
 * coordinate-space tags [Cmo3FormSpaces]'.
 *
 * The re-bundling itself is the format-neutral buildKeyformBundle (KeyformBundle.kt), shared with the
 * MOC3 export so both formats union the axes and re-interpolate through the same grid algebra.  What
 * stays here is CMO3's half: the RejectOwner policy and the graph writing.  So a track whose own span
 * does not cover the union of its parameter's keys cannot be represented in a bundled cell without
 * inventing out-of-span values, and its owner is left untouched and reported rather than partially
 * written.
 *
 * Object reuse is the byte-diff minimizer: surviving bindings, forms, and set objects are mutated
 * in place (their recorded child slots replay), so an untouched value re-emits byte-identically;
 * fresh objects are created only for genuinely new cells/axes, cloning conventions (guid kind,
 * coordType, backrefs) from a surviving sibling.
 */
internal class Cmo3KeyformLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
) {
	private val editor = context.editor
	private val baselineDrawableById = context.baselineDrawableById

	/** The forms' coordinate-space tags; the property lowering retags a re-parented owner's forms through this same instance. */
	val formSpaces = Cmo3FormSpaces(context, index)

	private val gridWeb = Cmo3KeyformGridWeb(context, index, notices)
	private val blendShapes = Cmo3BlendShapeLowering(context, index, notices)

	/**
	 * [buildKeyformBundle] with CMO3's policy applied: an owner whose split state cannot be expressed
	 * as one grid is REJECTED and reported, rather than partially written, and an owner with no keys
	 * anywhere gets ONE default cell rather than no grid.
	 *
	 * The default cell is the editor's own requirement, not a convenience.  Every
	 * ACParameterControllableSource in the official editor carries a KeyformGridSource with at least
	 * the default form: its setKeyformGridSource rejects null and CArtMeshSource.getDefaultKeyForm
	 * throws "no KeyForms" on an empty pool, and a model with either fails to open (Cubism 5.4 alpha1,
	 * checked 2026-09-04 against a file whose meshes had no grid).  The corpus agrees - every unkeyed
	 * source, part, and glue holds an axis-less grid with one form.  A geometry owner (warp, rotation)
	 * with no grid has no rest geometry to put in that cell, so its empty bundle stands.
	 *
	 * CMO3 can decline to write an object's grid over a split state; MOC3 cannot (every object needs a
	 * keyform), which is why the shared bundler takes the policy as a parameter instead of assuming one.
	 *
	 * @param ExportEntityCategory category    The notice category.
	 * @param String           subject          The owner's id for notices.
	 * @param KeyformGrid?     geometryGrid     The geometry grid, or null for a channel-only owner.
	 * @param FormInterpolator geometryBlend    The geometry interpolator (unused when no geometry).
	 * @param ChannelGrids     channels         The owner's channel tracks.
	 * @param Map              statics          Fallback value per relevant channel.
	 * @param Boolean          requireGeometry  Whether a geometry-less bundle is an error (warp/rotation).
	 * @return KeyformBundle? The bundle, or null when unrepresentable (reported).
	 */
	private fun <TGeometry> buildBundle(
		category: ExportEntityCategory,
		subject: String,
		geometryGrid: KeyformGrid<TGeometry>?,
		geometryBlend: FormInterpolator<TGeometry>,
		channels: ChannelGrids,
		statics: Map<FormChannel, ChannelValue>,
		requireGeometry: Boolean,
	): KeyformBundle? =
		when (
			val result =
				buildKeyformBundle(
					geometryGrid,
					geometryBlend,
					channels,
					statics,
					requireGeometry,
					OutOfSpanPolicy.RejectOwner,
				)
		) {
			is KeyformBundleResult.Bundled -> {
				if (!requireGeometry && result.bundle.axes.isEmpty() && result.bundle.cells.isEmpty()) {
					KeyformBundle(emptyList(), listOf(KeyformBundleCell(IntArray(0), emptyMap(), null, statics)))
				} else {
					result.bundle
				}
			}

			is KeyformBundleResult.Unrepresentable -> {
				notices.unsupported(category, subject, ExportNoticeReason.KeyformCannotBundle(result.rejection))
				null
			}
		}

	/**
	 * A template form of [TFormClass] from [ownForms], falling back to any owner of the same kind.
	 *
	 * @param Any? ownForms The owner's own forms pool, as stored.
	 * @param List<Any?> fallbackPools Other owners' pools to search next.
	 * @return TFormClass? The first form of the wanted class, or null.
	 */
	private inline fun <reified TFormClass : ACForm> templateForm(ownForms: Any?, fallbackPools: List<Any?>): TFormClass? {
		elementsOf(ownForms).filterIsInstance<TFormClass>().firstOrNull()?.let { return it }

		for (pool in fallbackPools) {
			elementsOf(pool).filterIsInstance<TFormClass>().firstOrNull()?.let { return it }
		}

		return null
	}

	/**
	 * Writes a color channel value into a form's CFloatColor field, creating one only when needed.
	 *
	 * @param ACForm   form           The form owning the color field.
	 * @param String   formTag        The form's serial tag for the child-slot record.
	 * @param String   property       The color field's name.
	 * @param String?  beforeProperty The sibling field the slot precedes, or null for last.
	 * @param Any?     current        The current field value.
	 * @param Function assign         Assigns a fresh color to the field.
	 * @param ColorRgb color          The value to write.
	 * @param ColorRgb identity       The channel's no-tint value, which an absent field already reads as.
	 */
	private fun writeFormColor(
		form: ACForm,
		formTag: String,
		property: String,
		beforeProperty: String?,
		current: Any?,
		assign: (Any?) -> Unit,
		color: ColorRgb,
		identity: ColorRgb,
	) {
		val existing = current as? CFloatColor

		if (existing != null) {
			existing.red = color.red
			existing.green = color.green
			existing.blue = color.blue

			return
		}

		if (color == identity) {
			// An absent color already reads as the identity (the pre-5.3 convention); keep it absent.
			return
		}

		assign(
			CFloatColor().apply {
				red = color.red
				green = color.green
				blue = color.blue
				alpha = 1f
			},
		)
		editor.ensureChildSlot(form, formTag, property, beforeProperty)
	}

	/**
	 * The scalar a channel value holds.
	 *
	 * @param ChannelValue? value The channel value, or null.
	 * @return Float? The scalar, or null when the value is absent or of another kind.
	 */
	private fun scalarOf(value: ChannelValue?): Float? = (value as? ChannelValue.Scalar)?.value

	/**
	 * The color a channel value holds.
	 *
	 * @param ChannelValue? value The channel value, or null.
	 * @return ColorRgb? The color, or null when the value is absent or of another kind.
	 */
	private fun colorOf(value: ChannelValue?): ColorRgb? = (value as? ChannelValue.Color)?.color

	/**
	 * The flag a channel value holds.
	 *
	 * @param ChannelValue? value The channel value, or null.
	 * @return Boolean? The flag, or null when the value is absent or of another kind.
	 */
	private fun flagOf(value: ChannelValue?): Boolean? = (value as? ChannelValue.Flag)?.flag

	/**
	 * True when every DRAW_ORDER value in [bundle] is integral (CMO3 stores draw order as Int).
	 *
	 * @param KeyformBundle bundle The bundled grid.
	 * @return Boolean True when no cell carries a fractional draw order.
	 */
	private fun drawOrdersAreIntegral(bundle: KeyformBundle): Boolean =
		bundle.cells.all { cell ->
			val drawOrder = scalarOf(cell.channels[FormChannel.DRAW_ORDER]) ?: return@all true
			drawOrder == drawOrder.toInt().toFloat()
		}

	/**
	 * Lowers a drawable's keyforms and/or blend shapes.
	 *
	 * @param CArtMeshSource source        The drawable's graph source.
	 * @param Drawable       editedDrawable The edited drawable.
	 * @param Boolean        rebuildGrid   Whether the grid/channel/static state changed.
	 * @param Boolean        rebuildMorphs Whether the blend shapes changed.
	 */
	fun lowerDrawable(
		source: CArtMeshSource,
		editedDrawable: Drawable,
		rebuildGrid: Boolean,
		rebuildMorphs: Boolean,
	) {
		val subject = editedDrawable.id.raw
		// The keyform-space base the deltas are measured from (DrawableMesh.localPositions); the canvas mesh is
		// CArtMeshSource.positions, written by the property lowering.
		val editedBase = editedDrawable.mesh?.localPositions

		if (editedBase == null) {
			notices.unsupported(ExportEntityCategory.Drawable, subject, ExportNoticeReason.KeyformsWithoutBaseMesh)

			return
		}

		val baselineBase = baselineDrawableById[editedDrawable.id]?.mesh?.localPositions
		val statics =
			mapOf<FormChannel, ChannelValue>(
				FormChannel.DRAW_ORDER to ChannelValue.Scalar(editedDrawable.drawOrder),
				FormChannel.OPACITY to ChannelValue.Scalar(editedDrawable.opacity),
				FormChannel.MULTIPLY_COLOR to ChannelValue.Color(editedDrawable.multiplyColor),
				FormChannel.SCREEN_COLOR to ChannelValue.Color(editedDrawable.screenColor),
			)
		val bundle =
			buildBundle(
				ExportEntityCategory.Drawable,
				subject,
				editedDrawable.geometryGrid,
				MeshDeltaInterpolator,
				editedDrawable.channelGrids,
				statics,
				requireGeometry = false,
			) ?: return

		if (!drawOrdersAreIntegral(bundle)) {
			notices.unsupported(ExportEntityCategory.Drawable, subject, ExportNoticeReason.FractionalDrawOrderNotStorable)

			return
		}

		val existingForms = gridWeb.existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template =
			templateForm<CArtMeshForm>(source.keyforms, index.drawableSources.map { it.keyforms })

		/**
		 * Writes one grid cell's art-mesh form, onto the existing form or a fresh one built from the template.
		 *
		 * @param CArtMeshForm? existing The form already at this cell, or null to create one.
		 * @param FloatArray? deltas The cell's mesh deltas, or null for none.
		 * @param Map<FormChannel, ChannelValue> channels The cell's channel values.
		 * @return CArtMeshForm The written form.
		 */
		fun writeMeshForm(existing: CArtMeshForm?, deltas: FloatArray?, channels: Map<FormChannel, ChannelValue>): CArtMeshForm {
			val form =
				existing
					?: CArtMeshForm().apply {
						guid = freshGuidLike((template?.guid ?: existingForms.values.firstOrNull()?.let { (it as ACForm).guid }) as? Guid, "CFormGuid")
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
						// CMO3: ACForm field notes - non-null in the editor's model (its setter rejects
						// null), and every corpus form writes the empty string.
						notes = template?.notes ?: ""
						// CMO3: form multiply/screen colors - modern-era forms always carry identity
						// values; the channel lowering overwrites them when a color channel exists.
						multiplyColor = identityMultiplyColor()
						screenColor = identityScreenColor()
						coordType = formSpaces.freshFormCoordType(template, editedDrawable.parentDeformerId != null)
					}
			val origAbsolute = (existing?.positions as? FloatArray)?.takeIf { it.size == editedBase.size }
			// The keyform is rebuilt from the keyform-space base as `local + Δ`.  A component whose base and delta
			// are what the import derived from the stored float (Cmo3Import's deltaReaching) is unchanged, and keeps
			// that float bit for bit - a -0.0 included, which the rebuild would turn into +0.0.
			val absolute =
				FloatArray(editedBase.size) { component ->
					val delta = deltas?.getOrNull(component) ?: 0f
					val reusable =
						origAbsolute != null &&
							baselineBase != null &&
							baselineBase.size == editedBase.size &&
							editedBase[component].toRawBits() == baselineBase[component].toRawBits() &&
							delta.toRawBits() == deltaReaching(baselineBase[component], origAbsolute[component]).toRawBits()
					if (reusable) origAbsolute[component] else editedBase[component] + delta
				}
			// CMO3: CArtMeshForm field positions (absolute), ACDrawableForm fields drawOrder /
			// opacity / multiplyColor / screenColor.
			form.positions = absolute
			editor.ensureChildSlot(form, "CArtMeshForm", "positions")
			scalarOf(channels[FormChannel.DRAW_ORDER])?.let { drawOrder ->
				form.drawOrder = drawOrder.toInt()
				editor.ensureChildSlot(form, "ACDrawableForm", "drawOrder", "opacity")
			}
			scalarOf(channels[FormChannel.OPACITY])?.let { opacity ->
				form.opacity = opacity
				editor.ensureChildSlot(form, "ACDrawableForm", "opacity", "multiplyColor")
			}
			colorOf(channels[FormChannel.MULTIPLY_COLOR])?.let { color ->
				writeFormColor(form, "ACDrawableForm", "multiplyColor", "screenColor", form.multiplyColor, { form.multiplyColor = it }, color, ColorRgb.MultiplyIdentity)
			}
			colorOf(channels[FormChannel.SCREEN_COLOR])?.let { color ->
				writeFormColor(form, "ACDrawableForm", "screenColor", "coordType", form.screenColor, { form.screenColor = it }, color, ColorRgb.ScreenIdentity)
			}

			return form
		}

		val gridForms = ArrayList<ACForm>(bundle.cells.size)

		if (rebuildGrid) {
			for (cell in bundle.cells) {
				val existing = existingForms[valueKey(cell.values)] as? CArtMeshForm
				gridForms.add(writeMeshForm(existing, (cell.geometry as? MeshDeltaForm)?.positionDeltas, cell.channels))
			}

			if (!gridWeb.writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
				return
			}
		} else {
			// Grid untouched: keep its existing forms for the pool assembly below.
			val gridGuids = gridWeb.gridFormGuids(source.keyformGridSource)
			elementsOf(source.keyforms).filterIsInstance<ACForm>()
				.filterTo(gridForms) { form -> uuidOf(form.guid) in gridGuids }
		}

		val morphForms =
			if (rebuildMorphs) {
				blendShapes.rebuildMorphTargets(
					ownerSource = source,
					subject = subject,
					currentSet = source.keyformMorphTargetSet,
					assignSet = { source.keyformMorphTargetSet = it },
					formsField = source.keyforms,
					bindings = editedDrawable.blendShapes,
				) { existing, payload: MeshForm ->
					writeMeshForm(
						existing as? CArtMeshForm,
						payload.positionDeltas,
						mapOf(
							FormChannel.DRAW_ORDER to ChannelValue.Scalar(payload.drawOrder),
							FormChannel.OPACITY to ChannelValue.Scalar(payload.opacity),
							FormChannel.MULTIPLY_COLOR to ChannelValue.Color(payload.multiplyColor),
							FormChannel.SCREEN_COLOR to ChannelValue.Color(payload.screenColor),
						),
					)
				} ?: return
			} else {
				blendShapes.existingMorphForms(source.keyformMorphTargetSet, source.keyforms)
			}
		gridWeb.writePool(source, "CArtMeshSource", "keyforms", "positions", source.keyforms, gridForms, morphForms) {
			source.keyforms = it
		}
	}

	/**
	 * Lowers a warp deformer's keyforms and/or blend shapes.
	 *
	 * @param CWarpDeformerSource source The warp's graph source.
	 * @param Deformer.Warp editedWarp The edited warp deformer.
	 * @param Boolean rebuildGrid Whether to rebuild the keyform grid.
	 * @param Boolean rebuildMorphs Whether to rebuild the blend shapes.
	 */
	fun lowerWarp(source: CWarpDeformerSource, editedWarp: Deformer.Warp, rebuildGrid: Boolean, rebuildMorphs: Boolean) {
		val subject = editedWarp.id.raw
		val statics =
			mapOf<FormChannel, ChannelValue>(
				FormChannel.OPACITY to ChannelValue.Scalar(editedWarp.opacity),
				FormChannel.MULTIPLY_COLOR to ChannelValue.Color(editedWarp.multiplyColor),
				FormChannel.SCREEN_COLOR to ChannelValue.Color(editedWarp.screenColor),
			)
		val bundle =
			buildBundle(ExportEntityCategory.Deformer, subject, editedWarp.geometryGrid, WarpLatticeInterpolator, editedWarp.channelGrids, statics, requireGeometry = true)
				?: return
		val existingForms = gridWeb.existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template = templateForm<CWarpDeformerForm>(source.keyforms, index.deformerSources.filterIsInstance<CWarpDeformerSource>().map { it.keyforms })

		/**
		 * Writes one grid cell's warp form, onto the existing form or a fresh one built from the template.
		 *
		 * @param CWarpDeformerForm? existing The form already at this cell, or null to create one.
		 * @param WarpLatticeForm? payload The cell's lattice, or null to leave the stored control points alone.
		 * @param Map<FormChannel, ChannelValue> channels The cell's channel values.
		 * @return CWarpDeformerForm The written form.
		 */
		fun writeWarpForm(existing: CWarpDeformerForm?, payload: WarpLatticeForm?, channels: Map<FormChannel, ChannelValue>): CWarpDeformerForm {
			val form =
				existing
					?: CWarpDeformerForm().apply {
						guid = freshGuidLike(template?.guid as? Guid, "CFormGuid")
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
						// CMO3: ACForm field notes - non-null in the editor's model (its setter rejects
						// null), and every corpus form writes the empty string.
						notes = template?.notes ?: ""
						// CMO3: form multiply/screen colors - modern-era forms always carry identity
						// values; the channel lowering overwrites them when a color channel exists.
						multiplyColor = identityMultiplyColor()
						screenColor = identityScreenColor()
						coordType = formSpaces.freshFormCoordType(template, editedWarp.parent != null)
					}
			val newPoints = payload?.controlPoints

			if (newPoints != null) {
				val origPoints = existing?.positions as? FloatArray
				// CMO3: CWarpDeformerForm field positions - absolute FFD control points.
				form.positions = if (origPoints != null && origPoints.contentEquals(newPoints)) origPoints else newPoints.copyOf()
				editor.ensureChildSlot(form, "CWarpDeformerForm", "positions")
			}

			scalarOf(channels[FormChannel.OPACITY])?.let { opacity ->
				// CMO3: ACDeformerForm field opacity.
				form.opacity = opacity
				editor.ensureChildSlot(form, "ACDeformerForm", "opacity", "multiplyColor")
			}
			colorOf(channels[FormChannel.MULTIPLY_COLOR])?.let { color ->
				writeFormColor(form, "ACDeformerForm", "multiplyColor", "screenColor", form.multiplyColor, { form.multiplyColor = it }, color, ColorRgb.MultiplyIdentity)
			}
			colorOf(channels[FormChannel.SCREEN_COLOR])?.let { color ->
				writeFormColor(form, "ACDeformerForm", "screenColor", "coordType", form.screenColor, { form.screenColor = it }, color, ColorRgb.ScreenIdentity)
			}

			return form
		}

		val gridForms = ArrayList<ACForm>(bundle.cells.size)

		if (rebuildGrid) {
			for (cell in bundle.cells) {
				gridForms.add(writeWarpForm(existingForms[valueKey(cell.values)] as? CWarpDeformerForm, cell.geometry as? WarpLatticeForm, cell.channels))
			}

			if (!gridWeb.writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
				return
			}
		} else {
			val gridGuids = gridWeb.gridFormGuids(source.keyformGridSource)
			elementsOf(source.keyforms).filterIsInstance<ACForm>()
				.filterTo(gridForms) { form -> uuidOf(form.guid) in gridGuids }
		}

		val morphForms =
			if (rebuildMorphs) {
				blendShapes.rebuildMorphTargets(source, subject, source.keyformMorphTargetSet, { source.keyformMorphTargetSet = it }, source.keyforms, editedWarp.blendShapes) { existing, payload: WarpForm ->
					writeWarpForm(
						existing as? CWarpDeformerForm,
						WarpLatticeForm(payload.controlPoints),
						mapOf(
							FormChannel.OPACITY to ChannelValue.Scalar(payload.opacity),
							FormChannel.MULTIPLY_COLOR to ChannelValue.Color(payload.multiplyColor),
							FormChannel.SCREEN_COLOR to ChannelValue.Color(payload.screenColor),
						),
					)
				} ?: return
			} else {
				blendShapes.existingMorphForms(source.keyformMorphTargetSet, source.keyforms)
			}
		gridWeb.writePool(source, "CWarpDeformerSource", "keyforms", null, source.keyforms, gridForms, morphForms) {
			source.keyforms = it
		}
	}

	/**
	 * Lowers a rotation deformer's keyforms and/or blend shapes.
	 *
	 * @param CRotationDeformerSource source The rotation deformer's graph source.
	 * @param Deformer.Rotation editedRotation The edited rotation deformer.
	 * @param Boolean rebuildGrid Whether to rebuild the keyform grid.
	 * @param Boolean rebuildMorphs Whether to rebuild the blend shapes.
	 */
	fun lowerRotation(source: CRotationDeformerSource, editedRotation: Deformer.Rotation, rebuildGrid: Boolean, rebuildMorphs: Boolean) {
		val subject = editedRotation.id.raw
		val statics =
			mapOf<FormChannel, ChannelValue>(
				FormChannel.OPACITY to ChannelValue.Scalar(editedRotation.opacity),
				FormChannel.MULTIPLY_COLOR to ChannelValue.Color(editedRotation.multiplyColor),
				FormChannel.SCREEN_COLOR to ChannelValue.Color(editedRotation.screenColor),
				FormChannel.FLIP_X to ChannelValue.Flag(editedRotation.flipX),
				FormChannel.FLIP_Y to ChannelValue.Flag(editedRotation.flipY),
			)
		val bundle =
			buildBundle(ExportEntityCategory.Deformer, subject, editedRotation.geometryGrid, RotationPivotInterpolator, editedRotation.channelGrids, statics, requireGeometry = true)
				?: return
		val existingForms = gridWeb.existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template = templateForm<CRotationDeformerForm>(source.keyforms, index.deformerSources.filterIsInstance<CRotationDeformerSource>().map { it.keyforms })

		/**
		 * Writes one grid cell's rotation form, onto the existing form or a fresh one built from the template.
		 *
		 * @param CRotationDeformerForm? existing The form already at this cell, or null to create one.
		 * @param RotationPivotForm? payload The cell's pivot form, or null to leave the stored pivot alone.
		 * @param Map<FormChannel, ChannelValue> channels The cell's channel values.
		 * @return CRotationDeformerForm The written form.
		 */
		fun writeRotationForm(existing: CRotationDeformerForm?, payload: RotationPivotForm?, channels: Map<FormChannel, ChannelValue>): CRotationDeformerForm {
			val form =
				existing
					?: CRotationDeformerForm().apply {
						guid = freshGuidLike(template?.guid as? Guid, "CFormGuid")
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
						// CMO3: ACForm field notes - non-null in the editor's model (its setter rejects
						// null), and every corpus form writes the empty string.
						notes = template?.notes ?: ""
						// CMO3: form multiply/screen colors - modern-era forms always carry identity
						// values; the channel lowering overwrites them when a color channel exists.
						multiplyColor = identityMultiplyColor()
						screenColor = identityScreenColor()
						coordType = formSpaces.freshFormCoordType(template, editedRotation.parent != null)
					}

			if (payload != null) {
				// CMO3: CRotationDeformerForm attributes originX / originY / angle / scale.
				form.originX = payload.originX
				form.originY = payload.originY
				form.angle = payload.angle
				form.scale = payload.scale

				for (attr in listOf("originX", "originY", "angle", "scale")) {
					editor.ensurePresentAttr(form, "CRotationDeformerForm", attr)
				}
			}

			flagOf(channels[FormChannel.FLIP_X])?.let { flip ->
				// CMO3: CRotationDeformerForm attribute isReflectX.
				form.isReflectX = flip
				editor.ensurePresentAttr(form, "CRotationDeformerForm", "isReflectX")
			}
			flagOf(channels[FormChannel.FLIP_Y])?.let { flip ->
				// CMO3: CRotationDeformerForm attribute isReflectY.
				form.isReflectY = flip
				editor.ensurePresentAttr(form, "CRotationDeformerForm", "isReflectY")
			}
			scalarOf(channels[FormChannel.OPACITY])?.let { opacity ->
				form.opacity = opacity
				editor.ensureChildSlot(form, "ACDeformerForm", "opacity", "multiplyColor")
			}
			colorOf(channels[FormChannel.MULTIPLY_COLOR])?.let { color ->
				writeFormColor(form, "ACDeformerForm", "multiplyColor", "screenColor", form.multiplyColor, { form.multiplyColor = it }, color, ColorRgb.MultiplyIdentity)
			}
			colorOf(channels[FormChannel.SCREEN_COLOR])?.let { color ->
				writeFormColor(form, "ACDeformerForm", "screenColor", "coordType", form.screenColor, { form.screenColor = it }, color, ColorRgb.ScreenIdentity)
			}

			return form
		}

		val gridForms = ArrayList<ACForm>(bundle.cells.size)

		if (rebuildGrid) {
			for (cell in bundle.cells) {
				gridForms.add(writeRotationForm(existingForms[valueKey(cell.values)] as? CRotationDeformerForm, cell.geometry as? RotationPivotForm, cell.channels))
			}

			if (!gridWeb.writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
				return
			}
		} else {
			val gridGuids = gridWeb.gridFormGuids(source.keyformGridSource)
			elementsOf(source.keyforms).filterIsInstance<ACForm>()
				.filterTo(gridForms) { form -> uuidOf(form.guid) in gridGuids }
		}

		val morphForms =
			if (rebuildMorphs) {
				blendShapes.rebuildMorphTargets(source, subject, source.keyformMorphTargetSet, { source.keyformMorphTargetSet = it }, source.keyforms, editedRotation.blendShapes) { existing, payload: RotationForm ->
					writeRotationForm(
						existing as? CRotationDeformerForm,
						RotationPivotForm(payload.originX, payload.originY, payload.angle, payload.scale),
						mapOf(
							FormChannel.OPACITY to ChannelValue.Scalar(payload.opacity),
							FormChannel.MULTIPLY_COLOR to ChannelValue.Color(payload.multiplyColor),
							FormChannel.SCREEN_COLOR to ChannelValue.Color(payload.screenColor),
							FormChannel.FLIP_X to ChannelValue.Flag(payload.flipX),
							FormChannel.FLIP_Y to ChannelValue.Flag(payload.flipY),
						),
					)
				} ?: return
			} else {
				blendShapes.existingMorphForms(source.keyformMorphTargetSet, source.keyforms)
			}
		gridWeb.writePool(source, "CRotationDeformerSource", "keyforms", "handleLengthOnCanvas", source.keyforms, gridForms, morphForms) {
			source.keyforms = it
		}
	}

	/**
	 * Lowers a part's channel tracks (the CPartForm grid).
	 *
	 * @param CPartSource source The part's graph source.
	 * @param Part editedPart The edited part.
	 */
	fun lowerPart(source: CPartSource, editedPart: Part) {
		val subject = editedPart.id.raw
		val isPre53 = source.colorComposition == null
		val statics =
			mapOf<FormChannel, ChannelValue>(
				FormChannel.DRAW_ORDER to ChannelValue.Scalar(editedPart.drawOrder.toFloat()),
				FormChannel.OPACITY to ChannelValue.Scalar(editedPart.composite.opacity),
				FormChannel.MULTIPLY_COLOR to ChannelValue.Color(editedPart.composite.multiplyColor),
				FormChannel.SCREEN_COLOR to ChannelValue.Color(editedPart.composite.screenColor),
			)
		val bundle =
			buildBundle(ExportEntityCategory.Part, subject, null as KeyformGrid<Unit>?, UnitInterpolator, editedPart.channelGrids, statics, requireGeometry = false)
				?: return

		if (!drawOrdersAreIntegral(bundle)) {
			notices.unsupported(ExportEntityCategory.Part, subject, ExportNoticeReason.FractionalDrawOrderNotStorable)

			return
		}

		if (bundle.axes.isEmpty()) {
			// No tracks: the part keeps its existing (axis-less) grid; statics are written by the
			// composite/draw-order lowerings, not here.
			return
		}

		val existingForms = gridWeb.existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template = templateForm<CPartForm>(source.keyforms, index.userPartSources.map { it.keyforms })
		val gridForms = ArrayList<ACForm>(bundle.cells.size)

		for (cell in bundle.cells) {
			val existing = existingForms[valueKey(cell.values)] as? CPartForm
			val form =
				existing
					?: CPartForm().apply {
						guid = freshGuidLike(template?.guid as? Guid, "CFormGuid")
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
						// CMO3: ACForm field notes - a non-null String in the editor's model (its
						// setter rejects null); every corpus form writes "" when unannotated.
						notes = template?.notes ?: ""
						// CMO3: CPartForm multiply/screen colors - a modern-era part form carries all
						// of drawOrder/opacity/multiplyColor/screenColor, and CPartForm.deserialize
						// dereferences the colors unconditionally (a form with drawOrder + opacity but
						// no colors NPEs at load).  Corpus signatures are all-five or the pre-5.3
						// drawOrder-only shape, never the partial middle; the channel lowering
						// overwrites these when a color channel exists.
						multiplyColor = identityMultiplyColor()
						screenColor = identityScreenColor()
					}
			scalarOf(cell.channels[FormChannel.DRAW_ORDER])?.let { drawOrder ->
				// CMO3: CPartForm field drawOrder.
				form.drawOrder = drawOrder.toInt()
				editor.ensureChildSlot(form, "CPartForm", "drawOrder", "opacity")
			}
			scalarOf(cell.channels[FormChannel.OPACITY])?.let { opacity ->
				// CMO3: CPartForm field opacity - a pre-5.3 part's unset 0f means opaque, so an
				// opaque value on a pre-5.3 form is left as stored rather than authored anew.
				if (!(isPre53 && opacity == 1f && existing != null)) {
					form.opacity = opacity
					editor.ensureChildSlot(form, "CPartForm", "opacity", "multiplyColor")
				}
			}
			colorOf(cell.channels[FormChannel.MULTIPLY_COLOR])?.let { color ->
				writeFormColor(form, "CPartForm", "multiplyColor", "screenColor", form.multiplyColor, { form.multiplyColor = it }, color, ColorRgb.MultiplyIdentity)
			}
			colorOf(cell.channels[FormChannel.SCREEN_COLOR])?.let { color ->
				writeFormColor(form, "CPartForm", "screenColor", null, form.screenColor, { form.screenColor = it }, color, ColorRgb.ScreenIdentity)
			}
			gridForms.add(form)
		}

		if (!gridWeb.writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
			return
		}

		gridWeb.writePool(source, "CPartSource", "keyforms", "enableDrawOrderGroup", source.keyforms, gridForms, emptyList()) {
			source.keyforms = it
		}
	}

	/**
	 * Writes a part's composite statics (opacity / colors) into every existing CPartForm - the home
	 * CMO3 gives them when the part has no composite channel tracks.  A pre-5.3 form's unset 0f
	 * opacity means opaque, so an opaque value leaves the stored raw untouched.
	 *
	 * @param CPartSource source     The part's graph source.
	 * @param Part        editedPart The edited part.
	 */
	fun writePartCompositeStatics(source: CPartSource, editedPart: Part) {
		val isPre53 = source.colorComposition == null

		for (form in elementsOf(source.keyforms).filterIsInstance<CPartForm>()) {
			val opacity = editedPart.composite.opacity

			if (!(isPre53 && opacity == 1f)) {
				// CMO3: CPartForm field opacity.
				form.opacity = opacity
				editor.ensureChildSlot(form, "CPartForm", "opacity", "multiplyColor")
			}

			writeFormColor(form, "CPartForm", "multiplyColor", "screenColor", form.multiplyColor, { form.multiplyColor = it }, editedPart.composite.multiplyColor, ColorRgb.MultiplyIdentity)
			writeFormColor(form, "CPartForm", "screenColor", null, form.screenColor, { form.screenColor = it }, editedPart.composite.screenColor, ColorRgb.ScreenIdentity)
		}
	}

	/**
	 * Lowers a glue's intensity track (the CGlueForm grid) and its static intensity.
	 *
	 * @param CGlueSource source The glue's graph source.
	 * @param Glue editedGlue The edited glue.
	 */
	fun lowerGlue(source: CGlueSource, editedGlue: Glue) {
		val subject = "${editedGlue.meshA.raw}+${editedGlue.meshB.raw}"
		val statics = mapOf<FormChannel, ChannelValue>(FormChannel.GLUE_INTENSITY to ChannelValue.Scalar(editedGlue.intensity))
		val bundle =
			buildBundle(ExportEntityCategory.Glue, subject, null as KeyformGrid<Unit>?, UnitInterpolator, editedGlue.channelGrids, statics, requireGeometry = false)
				?: return

		if (bundle.axes.isEmpty() && bundle.cells.isEmpty()) {
			// No intensity track: write the static into whatever forms exist (an unkeyed glue welds
			// fully, so a non-1 static without forms has no CMO3 home) - never clear a glue's grid.
			val forms = elementsOf(source.keyforms).filterIsInstance<CGlueForm>()

			if (forms.isEmpty() && editedGlue.intensity != 1f) {
				notices.unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.StaticGlueIntensityWithoutKeyforms)

				return
			}

			for (form in forms) {
				form.intensity = editedGlue.intensity
				editor.ensureChildSlot(form, "CGlueForm", "intensity")
			}

			return
		}

		val existingForms = gridWeb.existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template = templateForm<CGlueForm>(source.keyforms, index.glueSources.map { it.keyforms })
		val gridForms = ArrayList<ACForm>(bundle.cells.size)

		for (cell in bundle.cells) {
			val form =
				existingForms[valueKey(cell.values)] as? CGlueForm
					?: CGlueForm().apply {
						guid = freshGuidLike(template?.guid as? Guid, "CFormGuid")
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
						// CMO3: ACForm field notes - non-null in the editor's model.
						notes = template?.notes ?: ""
					}
			scalarOf(cell.channels[FormChannel.GLUE_INTENSITY])?.let { intensity ->
				// CMO3: CGlueForm field intensity - the weld strength at this cell.
				form.intensity = intensity
				editor.ensureChildSlot(form, "CGlueForm", "intensity")
			}
			gridForms.add(form)
		}

		if (!gridWeb.writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
			return
		}

		gridWeb.writePool(source, "CGlueSource", "keyforms", "targetArtMeshA_guid", source.keyforms, gridForms, emptyList()) {
			source.keyforms = it
		}
	}

	/** The no-op interpolator for channel-only owners (parts, glues) whose bundles carry no geometry. */
	private object UnitInterpolator : FormInterpolator<Unit> {
		override fun interpolate(lower: Unit, upper: Unit, fraction: Float): Unit = Unit

		override fun isExactlyEqual(left: Unit, right: Unit): Boolean = true
	}
}