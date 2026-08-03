package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CRotationDeformerForm
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
import org.umamo.format.cmo3.model.gen.KeyFormMorphTarget
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.KeyOnParameter
import org.umamo.format.cmo3.model.gen.KeyformBindingSource
import org.umamo.format.cmo3.model.gen.KeyformGridAccessKey
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.model.gen.KeyformOnGrid
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraint
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraintSet
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.ExportNotice
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.keyform.ChannelValueInterpolator
import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.keyform.refinedToUnion
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm

/*
 * The keyform half of the CMO3 export reconcile: re-bundling Umamo's split representation (a
 * geometry grid plus independent per-channel tracks plus statics) back into CMO3's all-or-nothing
 * keyform cells, and rebuilding the grid web (KeyformGridSource / KeyformBindingSource /
 * KeyformOnGrid / KeyOnParameter / the forms pool) that encodes it.
 *
 * The bundle axes are the union of the geometry axes and every channel track's axes; geometry and
 * channels refine onto that union through the SAME grid algebra compaction verifies itself against
 * (refinedToUnion), so a track that came from an import compaction refines back bit-identically.
 * A track whose own span does not cover the union of its parameter's keys cannot be represented in
 * a bundled cell without inventing out-of-span values, so the owner is left untouched and reported.
 *
 * Object reuse is the byte-diff minimizer: surviving bindings, forms, and set objects are mutated
 * in place (their recorded child slots replay), so an untouched value re-emits byte-identically;
 * fresh objects are created only for genuinely new cells/axes, cloning conventions (guid kind,
 * coordType, backrefs) from a surviving sibling.
 */
internal class Cmo3KeyformLowering(
	private val index: Cmo3GraphIndex,
	private val editor: Cmo3GraphEditor,
	private val baseline: PuppetModel,
	private val edited: PuppetModel,
	private val notices: MutableList<ExportNotice>,
) {
	private val parameterRanges: Map<ParameterId, ClosedFloatingPointRange<Float>> =
		edited.parameters.associate { parameter ->
			parameter.id to (minOf(parameter.min, parameter.max)..maxOf(parameter.min, parameter.max))
		}
	private val parameterIdByUuid: Map<String, ParameterId> =
		buildMap {
			for (source in index.parameterSources) {
				val uuid = Cmo3Import.uuidOf(source.guid) ?: continue
				val idStr = Cmo3Import.idStrOf(source.id) ?: continue
				put(uuid, ParameterId(idStr))
			}
		}
	private val baselineDrawableById = baseline.drawables.associateBy(Drawable::id)

	private fun unsupported(category: String, subject: String, detail: String) {
		notices.add(ExportNotice.UnsupportedChange(category, subject, detail))
	}

	/** One re-bundled cell: its coordinate, per-parameter key values, geometry, and channel values. */
	private class BundleCell(
		val coordinate: IntArray,
		val values: Map<ParameterId, Float>,
		val geometry: Any?,
		val channels: Map<FormChannel, ChannelValue>,
	)

	private class Bundle(
		val axes: List<KeyformAxis>,
		val cells: List<BundleCell>,
	)

	/**
	 * Re-bundles a split representation onto union axes, or null (with a notice) when the split
	 * state cannot be expressed as one grid.
	 *
	 * @param String           category         The notice category.
	 * @param String           subject          The owner's id for notices.
	 * @param KeyformGrid?     geometryGrid     The geometry grid, or null for a channel-only owner.
	 * @param FormInterpolator geometryBlend    The geometry interpolator (unused when no geometry).
	 * @param ChannelGrids     channels         The owner's channel tracks.
	 * @param Map              statics          Fallback value per relevant channel.
	 * @param Boolean          requireGeometry  Whether a geometry-less bundle is an error (warp/rotation).
	 * @return Bundle? The bundle, or null when unrepresentable (reported) or fully unkeyed (empty axes).
	 */
	private fun <TGeometry> buildBundle(
		category: String,
		subject: String,
		geometryGrid: KeyformGrid<TGeometry>?,
		geometryBlend: FormInterpolator<TGeometry>,
		channels: ChannelGrids,
		statics: Map<FormChannel, ChannelValue>,
		requireGeometry: Boolean,
	): Bundle? {
		// Union keys per parameter, geometry axes first so the bundled axis order follows the
		// geometry grid (the order the import's fan-out preserved).
		val unionKeys = LinkedHashMap<ParameterId, FloatArray>()

		fun mergeAxis(axis: KeyformAxis) {
			val existing = unionKeys[axis.parameterId]
			unionKeys[axis.parameterId] =
				if (existing == null) axis.keys.copyOf() else mergedKeys(existing, axis.keys)
		}
		geometryGrid?.axes?.forEach(::mergeAxis)
		for (grid in channels.gridsByChannel.values) {
			grid.axes.forEach(::mergeAxis)
		}
		if (unionKeys.isEmpty()) {
			// No parameter keys anywhere.  An axis-less grid (one static cell) is a real CMO3 shape
			// and keeps its single cell; a grid-less, channel-less owner is fully unkeyed (empty).
			val staticCell = geometryGrid?.cells?.firstOrNull()
			if (staticCell != null) {
				return Bundle(emptyList(), listOf(BundleCell(IntArray(0), emptyMap(), staticCell.form, statics)))
			}
			return Bundle(emptyList(), emptyList())
		}

		// Refine geometry over its own axes only (empty ranges suppresses the append path); every
		// axis must then carry exactly the union keys - a span that does not cover them means an
		// out-of-span key the bundle cannot represent without inventing values.
		var axes: List<KeyformAxis>
		var geometryByCoordinate: Map<List<Int>, TGeometry>
		if (geometryGrid != null) {
			val refined = geometryGrid.refinedToUnion(unionKeys, emptyMap(), geometryBlend)
			for (axis in refined.axes) {
				if (!axis.keys.contentEquals(unionKeys.getValue(axis.parameterId))) {
					unsupported(category, subject, "keys outside the geometry span cannot bundle into CMO3")
					return null
				}
			}
			axes = refined.axes
			geometryByCoordinate = refined.cells.associate { cell -> cell.coordinate.toList() to cell.form }
			// Replicate across parameters only channels key (the value is constant along them).
			for ((parameterId, keys) in unionKeys) {
				if (axes.none { it.parameterId == parameterId }) {
					axes = axes + KeyformAxis(parameterId, keys)
					geometryByCoordinate =
						buildMap {
							for (keyIndex in keys.indices) {
								for ((coordinate, form) in geometryByCoordinate) {
									put(coordinate + keyIndex, form)
								}
							}
						}
				}
			}
		} else {
			if (requireGeometry) {
				unsupported(category, subject, "channel keys without geometry cannot bundle into CMO3")
				return null
			}
			axes = unionKeys.map { (parameterId, keys) -> KeyformAxis(parameterId, keys) }
			geometryByCoordinate = emptyMap()
		}

		// Refine each channel over its own axes; a final-coordinate lookup then projects onto the
		// channel's axis subset (the channel is constant along axes it does not key).
		val channelLookups = HashMap<FormChannel, (IntArray) -> ChannelValue?>()
		val finalAxisPosition = axes.mapIndexed { position, axis -> axis.parameterId to position }.toMap()
		for ((channel, track) in channels.gridsByChannel) {
			val refined = track.refinedToUnion(unionKeys, emptyMap(), ChannelValueInterpolator)
			for (axis in refined.axes) {
				if (!axis.keys.contentEquals(unionKeys.getValue(axis.parameterId))) {
					unsupported(category, subject, "keys outside the $channel track span cannot bundle into CMO3")
					return null
				}
			}
			val subPositions =
				refined.axes.map { axis -> finalAxisPosition.getValue(axis.parameterId) }.toIntArray()
			channelLookups[channel] =
				{ coordinate ->
					val subCoordinate = IntArray(subPositions.size) { axisIndex -> coordinate[subPositions[axisIndex]] }
					refined.cellsByLinearIndex[refined.linearIndexOf(subCoordinate)]?.form
				}
		}

		// Assemble every cell of the dense final grid.
		val keyCounts = axes.map { it.keys.size }
		val totalCells = keyCounts.fold(1) { product, count -> product * count }
		val cells = ArrayList<BundleCell>(totalCells)
		val coordinate = IntArray(axes.size)
		for (cellOrdinal in 0 until totalCells) {
			var remainder = cellOrdinal
			for (axisIndex in axes.indices) {
				coordinate[axisIndex] = remainder % keyCounts[axisIndex]
				remainder /= keyCounts[axisIndex]
			}
			val cellCoordinate = coordinate.copyOf()
			val values =
				buildMap {
					for (axisIndex in axes.indices) {
						put(axes[axisIndex].parameterId, axes[axisIndex].keys[cellCoordinate[axisIndex]])
					}
				}
			val channelValues =
				buildMap {
					for ((channel, staticValue) in statics) {
						put(channel, channelLookups[channel]?.invoke(cellCoordinate) ?: staticValue)
					}
				}
			cells.add(BundleCell(cellCoordinate, values, geometryByCoordinate[cellCoordinate.toList()], channelValues))
		}
		return Bundle(axes, cells)
	}

	/** The two key arrays merged ascending with evaluator-tolerance duplicates dropped. */
	private fun mergedKeys(first: FloatArray, second: FloatArray): FloatArray {
		val sorted = (first + second).sortedArray()
		val kept = ArrayList<Float>(sorted.size)
		for (candidate in sorted) {
			// The evaluator's own key-snap tolerance: two keys it cannot tell apart stay one key.
			if (kept.isEmpty() || candidate - kept.last() >= EPS_KEY) {
				kept.add(candidate)
			}
		}
		return kept.toFloatArray()
	}

	/**
	 * The forms of the CURRENT graph grid keyed by their per-parameter key values, for reuse.
	 *
	 * @param Any? gridSourceField The owner's keyformGridSource.
	 * @param Any? formsField      The owner's keyforms pool.
	 * @return Map Value-coordinate key to form object.
	 */
	private fun existingFormsByValues(gridSourceField: Any?, formsField: Any?): Map<String, Any> {
		val gridSource = gridSourceField as? KeyformGridSource ?: return emptyMap()
		val bindings =
			Cmo3Import.elementsOf(gridSource.keyformBindings).filterIsInstance<KeyformBindingSource>()
		val keysByBinding =
			bindings.associateWith { binding ->
				Cmo3Import.elementsOf(binding.keys).mapNotNull { (it as? Number)?.toFloat() }
			}
		val parameterByBinding =
			bindings.associateWith { binding -> parameterIdByUuid[Cmo3Import.uuidOf(binding.parameterGuid)] }
		val formByUuid =
			Cmo3Import.elementsOf(formsField).filterIsInstance<ACForm>()
				.associateBy { form -> Cmo3Import.uuidOf(form.guid) }
		val byValues = HashMap<String, Any>()
		for (cell in Cmo3Import.elementsOf(gridSource.keyformsOnGrid).filterIsInstance<KeyformOnGrid>()) {
			val form = formByUuid[Cmo3Import.uuidOf(cell.keyformGuid)] ?: continue
			val values = HashMap<ParameterId, Float>()
			val keyList = (cell.accessKey as? KeyformGridAccessKey)?._keyOnParameterList
			var resolvable = true
			for (keyOnParameter in Cmo3Import.elementsOf(keyList).filterIsInstance<KeyOnParameter>()) {
				val binding = keyOnParameter.binding as? KeyformBindingSource ?: continue
				val parameterId = parameterByBinding[binding] ?: continue
				val keys = keysByBinding[binding] ?: continue
				val key = keys.getOrNull(keyOnParameter.keyIndex)
				if (key == null) {
					resolvable = false
					break
				}
				values[parameterId] = key
			}
			if (resolvable) {
				byValues[valueKey(values)] = form
			}
		}
		return byValues
	}

	private fun valueKey(values: Map<ParameterId, Float>): String =
		values.entries
			.sortedBy { entry -> entry.key.raw }
			.joinToString("|") { entry -> "${entry.key.raw}=${entry.value.toRawBits()}" }

	/** A fresh Guid cloning [template]'s kind (the tag the identity subsystem writes). */
	private fun freshGuidLike(template: Guid?): Guid =
		Guid(template?.kind ?: "CFormGuid").apply {
			uuid = java.util.UUID.randomUUID().toString()
			note = template?.note ?: "(no debug info)"
		}

	/** A template form of [TFormClass] from [ownForms], falling back to any owner of the same kind. */
	private inline fun <reified TFormClass : ACForm> templateForm(ownForms: Any?, fallbackPools: List<Any?>): TFormClass? {
		Cmo3Import.elementsOf(ownForms).filterIsInstance<TFormClass>().firstOrNull()?.let { return it }
		for (pool in fallbackPools) {
			Cmo3Import.elementsOf(pool).filterIsInstance<TFormClass>().firstOrNull()?.let { return it }
		}
		return null
	}

	/**
	 * Rebuilds the grid web from [bundle]: bindings reused per parameter (keys rewritten), fresh
	 * cell records, and the given per-cell forms.
	 *
	 * @param Any      owner           The source object owning keyformGridSource.
	 * @param String   subject         The owner's id for notices.
	 * @param Any?     currentGrid     The current keyformGridSource.
	 * @param Function assignGrid      Assigns a fresh grid source.
	 * @param Bundle   bundle          The re-bundled grid.
	 * @param List     cellForms       One form object per bundle cell, in cell order.
	 * @return Boolean True on success.
	 */
	private fun writeGridWeb(
		owner: Any,
		subject: String,
		currentGrid: Any?,
		assignGrid: (Any?) -> Unit,
		bundle: Bundle,
		cellForms: List<ACForm>,
	): Boolean {
		if (bundle.axes.isEmpty() && bundle.cells.isEmpty()) {
			// Fully unkeyed: the grid goes away entirely (an unkeyed source has a null grid).
			assignGrid(null)
			editor.ensureChildSlot(owner, "ACParameterControllableSource", "keyformGridSource", "keyformMorphTargetSet")
			return true
		}
		val gridSource =
			currentGrid as? KeyformGridSource
				?: KeyformGridSource().also {
					assignGrid(it)
					editor.ensureChildSlot(owner, "ACParameterControllableSource", "keyformGridSource", "keyformMorphTargetSet")
				}
		val existingBindings =
			Cmo3Import.elementsOf(gridSource.keyformBindings).filterIsInstance<KeyformBindingSource>()
				.associateBy { binding -> Cmo3Import.uuidOf(binding.parameterGuid) }
		val bindingPerAxis = ArrayList<KeyformBindingSource>(bundle.axes.size)
		for (axis in bundle.axes) {
			val parameterSource = index.parameterByIdStr[axis.parameterId.raw]
			if (parameterSource == null) {
				unsupported("keyform", subject, "axis parameter ${axis.parameterId.raw} has no CMO3 source")
				return false
			}
			val binding =
				existingBindings[Cmo3Import.uuidOf(parameterSource.guid)]
					?: KeyformBindingSource().apply {
						_gridSource = gridSource
						parameterGuid = parameterSource.guid
						keys = ArrayList<Any?>()
					}
			val keysList = binding.keys as? MutableList<Any?>
			if (keysList != null) {
				keysList.clear()
				axis.keys.forEach { key -> keysList.add(key) }
			} else {
				binding.keys = ArrayList<Any?>(axis.keys.map { it })
				editor.ensureChildSlot(binding, "KeyformBindingSource", "keys", "interpolationType")
			}
			bindingPerAxis.add(binding)
		}
		writeCollection(gridSource, "KeyformGridSource", "keyformBindings", "keyformsOnGrid", gridSource.keyformBindings, bindingPerAxis) {
			gridSource.keyformBindings = it
		}
		val cellRecords = ArrayList<Any?>(bundle.cells.size)
		for (cellIndex in bundle.cells.indices) {
			val cell = bundle.cells[cellIndex]
			val record =
				KeyformOnGrid().apply {
					keyformGuid = cellForms[cellIndex].guid
					accessKey =
						KeyformGridAccessKey().apply {
							_keyOnParameterList =
								CArrayList<Any?>().apply {
									for (axisIndex in bundle.axes.indices) {
										add(
											KeyOnParameter().apply {
												binding = bindingPerAxis[axisIndex]
												keyIndex = cell.coordinate[axisIndex]
											},
										)
									}
								}
						}
				}
			cellRecords.add(record)
		}
		writeCollection(gridSource, "KeyformGridSource", "keyformsOnGrid", null, gridSource.keyformsOnGrid, cellRecords) {
			gridSource.keyformsOnGrid = it
		}
		return true
	}

	/** Rewrites a collection field in place, or creates a CArrayList and records its slot. */
	private fun writeCollection(
		owner: Any,
		tag: String,
		property: String,
		beforeProperty: String?,
		current: Any?,
		newElements: List<Any?>,
		assign: (MutableList<Any?>) -> Unit,
	) {
		val mutable = current as? MutableList<Any?>
		if (mutable != null) {
			mutable.clear()
			mutable.addAll(newElements)
			return
		}
		val fresh: MutableList<Any?> = CArrayList()
		fresh.addAll(newElements)
		assign(fresh)
		editor.ensureChildSlot(owner, tag, property, beforeProperty)
	}

	/** Writes a color channel value into a form's CFloatColor field, creating one only when needed. */
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

	private fun scalarOf(value: ChannelValue?): Float? = (value as? ChannelValue.Scalar)?.value

	private fun colorOf(value: ChannelValue?): ColorRgb? = (value as? ChannelValue.Color)?.color

	private fun flagOf(value: ChannelValue?): Boolean? = (value as? ChannelValue.Flag)?.flag

	/** True when every DRAW_ORDER value in [bundle] is integral (CMO3 stores draw order as Int). */
	private fun drawOrdersAreIntegral(bundle: Bundle): Boolean =
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
	 * @param Boolean        alsoWriteBase Whether the base mesh moved too (positions rewritten here).
	 */
	fun lowerDrawable(
		source: CArtMeshSource,
		editedDrawable: Drawable,
		rebuildGrid: Boolean,
		rebuildMorphs: Boolean,
		alsoWriteBase: Boolean,
	) {
		val subject = editedDrawable.id.raw
		val editedBase = editedDrawable.mesh?.positions
		if (editedBase == null) {
			unsupported("drawable", subject, "keyforms without a base mesh cannot bundle into CMO3")
			return
		}
		val baselineBase = baselineDrawableById[editedDrawable.id]?.mesh?.positions
		val statics =
			mapOf<FormChannel, ChannelValue>(
				FormChannel.DRAW_ORDER to ChannelValue.Scalar(editedDrawable.drawOrder),
				FormChannel.OPACITY to ChannelValue.Scalar(editedDrawable.opacity),
				FormChannel.MULTIPLY_COLOR to ChannelValue.Color(editedDrawable.multiplyColor),
				FormChannel.SCREEN_COLOR to ChannelValue.Color(editedDrawable.screenColor),
			)
		val bundle =
			buildBundle(
				"drawable",
				subject,
				editedDrawable.geometryGrid,
				MeshDeltaInterpolator,
				editedDrawable.channelGrids,
				statics,
				requireGeometry = false,
			) ?: return
		if (!drawOrdersAreIntegral(bundle)) {
			unsupported("drawable", subject, "fractional draw order cannot be stored in CMO3 (integer field)")
			return
		}
		val existingForms = existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template =
			templateForm<CArtMeshForm>(source.keyforms, index.drawableSources.map { it.keyforms })

		fun writeMeshForm(existing: CArtMeshForm?, deltas: FloatArray?, channels: Map<FormChannel, ChannelValue>): CArtMeshForm {
			val form =
				existing
					?: CArtMeshForm().apply {
						guid = freshGuidLike((template?.guid ?: existingForms.values.firstOrNull()?.let { (it as ACForm).guid }) as? Guid)
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
						coordType = template?.coordType
					}
			val origAbsolute = (existing?.positions as? FloatArray)?.takeIf { it.size == editedBase.size }
			val absolute =
				FloatArray(editedBase.size) { component ->
					val delta = deltas?.getOrNull(component) ?: 0f
					val reusable =
						origAbsolute != null &&
							baselineBase != null &&
							baselineBase.size == editedBase.size &&
							editedBase[component].toRawBits() == baselineBase[component].toRawBits() &&
							delta.toRawBits() == (origAbsolute[component] - baselineBase[component]).toRawBits()
					if (reusable) origAbsolute!![component] else editedBase[component] + delta
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
			if (alsoWriteBase) {
				source.positions = editedBase.copyOf()
				editor.ensureChildSlot(source, "CArtMeshSource", "positions", "uvs")
				val editableMesh = Cmo3Import.editableMeshOf(source)
				val pointArray = editableMesh?.point as? FloatArray
				if (editableMesh != null && (pointArray == null || pointArray.size == editedBase.size)) {
					editableMesh.point = editedBase.copyOf()
					editor.ensureChildSlot(editableMesh, "GEditableMesh2", "point", "pointPriority")
				}
			}
			if (!writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
				return
			}
		} else {
			// Grid untouched: keep its existing forms for the pool assembly below.
			val gridGuids = gridFormGuids(source.keyformGridSource)
			Cmo3Import.elementsOf(source.keyforms).filterIsInstance<ACForm>()
				.filterTo(gridForms) { form -> Cmo3Import.uuidOf(form.guid) in gridGuids }
		}

		val morphForms =
			if (rebuildMorphs) {
				rebuildMorphTargets(
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
				existingMorphForms(source.keyformMorphTargetSet, source.keyforms)
			}
		writePool(source, "CArtMeshSource", "keyforms", "positions", source.keyforms, gridForms, morphForms) {
			source.keyforms = it
		}
	}

	/** Lowers a warp deformer's keyforms and/or blend shapes. */
	fun lowerWarp(source: CWarpDeformerSource, editedWarp: Deformer.Warp, rebuildGrid: Boolean, rebuildMorphs: Boolean) {
		val subject = editedWarp.id.raw
		val statics =
			mapOf<FormChannel, ChannelValue>(
				FormChannel.OPACITY to ChannelValue.Scalar(editedWarp.opacity),
				FormChannel.MULTIPLY_COLOR to ChannelValue.Color(editedWarp.multiplyColor),
				FormChannel.SCREEN_COLOR to ChannelValue.Color(editedWarp.screenColor),
			)
		val bundle =
			buildBundle("deformer", subject, editedWarp.geometryGrid, WarpLatticeInterpolator, editedWarp.channelGrids, statics, requireGeometry = true)
				?: return
		val existingForms = existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template = templateForm<CWarpDeformerForm>(source.keyforms, index.deformerSources.filterIsInstance<CWarpDeformerSource>().map { it.keyforms })

		fun writeWarpForm(existing: CWarpDeformerForm?, payload: WarpLatticeForm?, channels: Map<FormChannel, ChannelValue>): CWarpDeformerForm {
			val form =
				existing
					?: CWarpDeformerForm().apply {
						guid = freshGuidLike(template?.guid as? Guid)
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
						coordType = template?.coordType
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
			if (!writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
				return
			}
		} else {
			val gridGuids = gridFormGuids(source.keyformGridSource)
			Cmo3Import.elementsOf(source.keyforms).filterIsInstance<ACForm>()
				.filterTo(gridForms) { form -> Cmo3Import.uuidOf(form.guid) in gridGuids }
		}
		val morphForms =
			if (rebuildMorphs) {
				rebuildMorphTargets(source, subject, source.keyformMorphTargetSet, { source.keyformMorphTargetSet = it }, source.keyforms, editedWarp.blendShapes) { existing, payload: WarpForm ->
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
				existingMorphForms(source.keyformMorphTargetSet, source.keyforms)
			}
		writePool(source, "CWarpDeformerSource", "keyforms", null, source.keyforms, gridForms, morphForms) {
			source.keyforms = it
		}
	}

	/** Lowers a rotation deformer's keyforms and/or blend shapes. */
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
			buildBundle("deformer", subject, editedRotation.geometryGrid, RotationPivotInterpolator, editedRotation.channelGrids, statics, requireGeometry = true)
				?: return
		val existingForms = existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template = templateForm<CRotationDeformerForm>(source.keyforms, index.deformerSources.filterIsInstance<CRotationDeformerSource>().map { it.keyforms })

		fun writeRotationForm(existing: CRotationDeformerForm?, payload: RotationPivotForm?, channels: Map<FormChannel, ChannelValue>): CRotationDeformerForm {
			val form =
				existing
					?: CRotationDeformerForm().apply {
						guid = freshGuidLike(template?.guid as? Guid)
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
						coordType = template?.coordType
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
			if (!writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
				return
			}
		} else {
			val gridGuids = gridFormGuids(source.keyformGridSource)
			Cmo3Import.elementsOf(source.keyforms).filterIsInstance<ACForm>()
				.filterTo(gridForms) { form -> Cmo3Import.uuidOf(form.guid) in gridGuids }
		}
		val morphForms =
			if (rebuildMorphs) {
				rebuildMorphTargets(source, subject, source.keyformMorphTargetSet, { source.keyformMorphTargetSet = it }, source.keyforms, editedRotation.blendShapes) { existing, payload: RotationForm ->
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
				existingMorphForms(source.keyformMorphTargetSet, source.keyforms)
			}
		writePool(source, "CRotationDeformerSource", "keyforms", "handleLengthOnCanvas", source.keyforms, gridForms, morphForms) {
			source.keyforms = it
		}
	}

	/** Lowers a part's channel tracks (the CPartForm grid). */
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
			buildBundle("part", subject, null as KeyformGrid<Unit>?, UnitInterpolator, editedPart.channelGrids, statics, requireGeometry = false)
				?: return
		if (!drawOrdersAreIntegral(bundle)) {
			unsupported("part", subject, "fractional draw order cannot be stored in CMO3 (integer field)")
			return
		}
		if (bundle.axes.isEmpty()) {
			// No tracks: the part keeps its existing (axis-less) grid; statics are written by the
			// composite/draw-order lowerings, not here.
			return
		}
		val existingForms = existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template = templateForm<CPartForm>(source.keyforms, index.userPartSources.map { it.keyforms })
		val gridForms = ArrayList<ACForm>(bundle.cells.size)
		for (cell in bundle.cells) {
			val existing = existingForms[valueKey(cell.values)] as? CPartForm
			val form =
				existing
					?: CPartForm().apply {
						guid = freshGuidLike(template?.guid as? Guid)
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
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
		if (!writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
			return
		}
		writePool(source, "CPartSource", "keyforms", "enableDrawOrderGroup", source.keyforms, gridForms, emptyList()) {
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
		for (form in Cmo3Import.elementsOf(source.keyforms).filterIsInstance<CPartForm>()) {
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

	/** Lowers a glue's intensity track (the CGlueForm grid) and its static intensity. */
	fun lowerGlue(source: CGlueSource, editedGlue: Glue) {
		val subject = "${editedGlue.meshA.raw}+${editedGlue.meshB.raw}"
		val statics = mapOf<FormChannel, ChannelValue>(FormChannel.GLUE_INTENSITY to ChannelValue.Scalar(editedGlue.intensity))
		val bundle =
			buildBundle("glue", subject, null as KeyformGrid<Unit>?, UnitInterpolator, editedGlue.channelGrids, statics, requireGeometry = false)
				?: return
		if (bundle.axes.isEmpty() && bundle.cells.isEmpty()) {
			// No intensity track: write the static into whatever forms exist (an unkeyed glue welds
			// fully, so a non-1 static without forms has no CMO3 home) - never clear a glue's grid.
			val forms = Cmo3Import.elementsOf(source.keyforms).filterIsInstance<CGlueForm>()
			if (forms.isEmpty() && editedGlue.intensity != 1f) {
				unsupported("glue", subject, "a static intensity without keyforms has no CMO3 home")
				return
			}
			for (form in forms) {
				form.intensity = editedGlue.intensity
				editor.ensureChildSlot(form, "CGlueForm", "intensity")
			}
			return
		}
		val existingForms = existingFormsByValues(source.keyformGridSource, source.keyforms)
		val template = templateForm<CGlueForm>(source.keyforms, index.glueSources.map { it.keyforms })
		val gridForms = ArrayList<ACForm>(bundle.cells.size)
		for (cell in bundle.cells) {
			val form =
				existingForms[valueKey(cell.values)] as? CGlueForm
					?: CGlueForm().apply {
						guid = freshGuidLike(template?.guid as? Guid)
						isAnimatedForm = template?.isAnimatedForm ?: false
						isLocalAnimatedForm = template?.isLocalAnimatedForm ?: false
						_source = source
					}
			scalarOf(cell.channels[FormChannel.GLUE_INTENSITY])?.let { intensity ->
				// CMO3: CGlueForm field intensity - the weld strength at this cell.
				form.intensity = intensity
				editor.ensureChildSlot(form, "CGlueForm", "intensity")
			}
			gridForms.add(form)
		}
		if (!writeGridWeb(source, subject, source.keyformGridSource, { source.keyformGridSource = it }, bundle, gridForms)) {
			return
		}
		writePool(source, "CGlueSource", "keyforms", "targetArtMeshA_guid", source.keyforms, gridForms, emptyList()) {
			source.keyforms = it
		}
	}

	/** The guids of the forms the current grid's cells reference. */
	private fun gridFormGuids(gridSourceField: Any?): Set<String> {
		val gridSource = gridSourceField as? KeyformGridSource ?: return emptySet()
		return Cmo3Import.elementsOf(gridSource.keyformsOnGrid).filterIsInstance<KeyformOnGrid>()
			.mapNotNull { cell -> Cmo3Import.uuidOf(cell.keyformGuid) }
			.toSet()
	}

	/** The morph-target form objects the current morph set references, for pool assembly. */
	private fun existingMorphForms(morphSetField: Any?, formsField: Any?): List<ACForm> {
		val set = morphSetField as? KeyFormMorphTargetSet ?: return emptyList()
		val formByUuid =
			Cmo3Import.elementsOf(formsField).filterIsInstance<ACForm>()
				.associateBy { form -> Cmo3Import.uuidOf(form.guid) }
		return Cmo3Import.elementsOf(set._morphTargets).filterIsInstance<KeyFormMorphTarget>()
			.mapNotNull { record -> formByUuid[Cmo3Import.uuidOf(record.keyformGuid)] }
	}

	/**
	 * Rebuilds an owner's morph-target set from the edited blend-shape bindings, reusing records
	 * and forms matched by (parameter, key value).  The inserted neutral key (null form at value 0)
	 * is dropped again - CMO3 stores no record for it.
	 *
	 * @return List? The morph form objects for the pool, or null when a parameter is unresolvable.
	 */
	private fun <TForm : Any> rebuildMorphTargets(
		ownerSource: Any,
		subject: String,
		currentSet: Any?,
		assignSet: (Any?) -> Unit,
		formsField: Any?,
		bindings: List<BlendShapeBinding<TForm>>,
		writeForm: (existing: Any?, payload: TForm) -> ACForm,
	): List<ACForm>? {
		val set = currentSet as? KeyFormMorphTargetSet
		if (bindings.isEmpty()) {
			if (set != null) {
				writeCollection(set, "KeyFormMorphTargetSet", "_morphTargets", "blendWeightConstraintSet", set._morphTargets, emptyList()) {
					set._morphTargets = it
				}
				(set.blendWeightConstraintSet as? MorphTargetBlendWeightConstraintSet)?.let { constraintSet ->
					writeCollection(constraintSet, "MorphTargetBlendWeightConstraintSet", "_constraints", null, constraintSet._constraints, emptyList()) {
						constraintSet._constraints = it
					}
				}
			}
			return emptyList()
		}
		val targetSet =
			set
				?: KeyFormMorphTargetSet().also {
					it.blendWeightConstraintSet = MorphTargetBlendWeightConstraintSet().apply { _constraints = CArrayList<Any?>() }
					assignSet(it)
					editor.ensureChildSlot(ownerSource, "ACParameterControllableSource", "keyformMorphTargetSet", "_extensions")
				}
		val formByUuid =
			Cmo3Import.elementsOf(formsField).filterIsInstance<ACForm>()
				.associateBy { form -> Cmo3Import.uuidOf(form.guid) }
		val existingRecords =
			Cmo3Import.elementsOf(targetSet._morphTargets).filterIsInstance<KeyFormMorphTarget>()
				.associateBy { record -> "${Cmo3Import.uuidOf(record.parameterGuid)}@${record.keyValue.toRawBits()}" }
		val records = ArrayList<Any?>()
		val morphForms = ArrayList<ACForm>()
		val constraints = ArrayList<Any?>()
		for (binding in bindings) {
			val parameterSource = index.parameterByIdStr[binding.parameterId.raw]
			if (parameterSource == null) {
				unsupported("keyform", subject, "blend shape parameter ${binding.parameterId.raw} has no CMO3 source")
				return null
			}
			val parameterUuid = Cmo3Import.uuidOf(parameterSource.guid)
			for (keyIndex in binding.keys.indices) {
				val payload = binding.forms[keyIndex] ?: continue
				val keyValue = binding.keys[keyIndex]
				val existingRecord = existingRecords["$parameterUuid@${keyValue.toRawBits()}"]
				val existingForm = existingRecord?.let { record -> formByUuid[Cmo3Import.uuidOf(record.keyformGuid)] }
				val form = writeForm(existingForm, payload)
				morphForms.add(form)
				val record =
					existingRecord
						?: KeyFormMorphTarget().apply {
							parameterGuid = parameterSource.guid
							owner = ownerSource
							editBaseParameterMap = null
						}
				record.keyValue = keyValue
				record.keyformGuid = form.guid
				records.add(record)
			}
			for (limit in binding.limits) {
				val constraintSource = index.parameterByIdStr[limit.parameterId.raw] ?: continue
				for (point in limit.points) {
					constraints.add(
						MorphTargetBlendWeightConstraint().apply {
							morphTargetParameterGuid = parameterSource.guid
							constraintParameterGuid = constraintSource.guid
							constraintParameterValue = point.value
							blendWeight = point.weight
						},
					)
				}
			}
		}
		writeCollection(targetSet, "KeyFormMorphTargetSet", "_morphTargets", "blendWeightConstraintSet", targetSet._morphTargets, records) {
			targetSet._morphTargets = it
		}
		val constraintSet =
			targetSet.blendWeightConstraintSet as? MorphTargetBlendWeightConstraintSet
				?: MorphTargetBlendWeightConstraintSet().also {
					targetSet.blendWeightConstraintSet = it
					editor.ensureChildSlot(targetSet, "KeyFormMorphTargetSet", "blendWeightConstraintSet")
				}
		writeCollection(constraintSet, "MorphTargetBlendWeightConstraintSet", "_constraints", null, constraintSet._constraints, constraints) {
			constraintSet._constraints = it
		}
		return morphForms
	}

	/** Writes the owner's keyforms pool: the grid forms in cell order, then the morph forms. */
	private fun writePool(
		owner: Any,
		ownerTag: String,
		property: String,
		beforeProperty: String?,
		current: Any?,
		gridForms: List<ACForm>,
		morphForms: List<ACForm>,
		assign: (MutableList<Any?>) -> Unit,
	) {
		val gridIdentity = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
		gridForms.forEach { gridIdentity.add(it) }
		val pool = ArrayList<Any?>(gridForms.size + morphForms.size)
		pool.addAll(gridForms)
		for (form in morphForms) {
			if (form !in gridIdentity) {
				pool.add(form)
				gridIdentity.add(form)
			}
		}
		writeCollection(owner, ownerTag, property, beforeProperty, current, pool, assign)
	}

	/** The no-op interpolator for channel-only owners (parts, glues) whose bundles carry no geometry. */
	private object UnitInterpolator : FormInterpolator<Unit> {
		override fun interpolate(lower: Unit, upper: Unit, fraction: Float): Unit = Unit

		override fun isExactlyEqual(left: Unit, right: Unit): Boolean = true
	}
}
