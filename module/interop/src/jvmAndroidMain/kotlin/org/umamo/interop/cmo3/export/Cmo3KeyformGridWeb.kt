package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.ACForm
import org.umamo.format.cmo3.model.gen.ExtendedInterpolationType
import org.umamo.format.cmo3.model.gen.InterpolationType
import org.umamo.format.cmo3.model.gen.KeyOnParameter
import org.umamo.format.cmo3.model.gen.KeyformBindingSource
import org.umamo.format.cmo3.model.gen.KeyformGridAccessKey
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.model.gen.KeyformOnGrid
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.KeyformBundle
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.mutableGraphListOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.ParameterId
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The grid web of a keyed owner: the forms its current grid holds, the grid source a rebuilt bundle is
 * written as (KeyformGridSource / KeyformBindingSource / KeyformOnGrid / KeyOnParameter), and the
 * owner's forms pool.  Surviving bindings and cells are mutated in place, so an untouched grid
 * re-emits as it was read.
 */
internal class Cmo3KeyformGridWeb(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
) {
	private val editor = context.editor
	private val parameterIdByUuid: Map<String, ParameterId> =
		buildMap {
			for (source in index.parameterSources) {
				val uuid = uuidOf(source.guid) ?: continue
				val idStr = idStrOf(source.id) ?: continue
				put(uuid, ParameterId(idStr))
			}
		}

	/**
	 * The forms of the CURRENT graph grid keyed by their per-parameter key values, for reuse.
	 *
	 * @param Any? gridSourceField The owner's keyformGridSource.
	 * @param Any? formsField      The owner's keyforms pool.
	 * @return Map Value-coordinate key to form object.
	 */
	fun existingFormsByValues(gridSourceField: Any?, formsField: Any?): Map<String, Any> {
		val gridSource = gridSourceField as? KeyformGridSource ?: return emptyMap()
		val bindings =
			elementsOf(gridSource.keyformBindings).filterIsInstance<KeyformBindingSource>()
		val keysByBinding =
			bindings.associateWith { binding ->
				elementsOf(binding.keys).mapNotNull { (it as? Number)?.toFloat() }
			}
		val parameterByBinding =
			bindings.associateWith { binding -> parameterIdByUuid[uuidOf(binding.parameterGuid)] }
		val formByUuid =
			elementsOf(formsField).filterIsInstance<ACForm>()
				.associateBy { form -> uuidOf(form.guid) }
		val byValues = HashMap<String, Any>()

		for (cell in elementsOf(gridSource.keyformsOnGrid).filterIsInstance<KeyformOnGrid>()) {
			val form = formByUuid[uuidOf(cell.keyformGuid)] ?: continue
			val values = HashMap<ParameterId, Float>()
			val keyList = (cell.accessKey as? KeyformGridAccessKey)?._keyOnParameterList
			var resolvable = true

			for (keyOnParameter in elementsOf(keyList).filterIsInstance<KeyOnParameter>()) {
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

	/**
	 * Rebuilds the grid web from [bundle]: bindings reused per parameter (keys rewritten), fresh
	 * cell records, and the given per-cell forms.
	 *
	 * @param Any           owner       The source object owning keyformGridSource.
	 * @param String        subject     The owner's id for notices.
	 * @param Any?          currentGrid The current keyformGridSource.
	 * @param Function      assignGrid  Assigns a fresh grid source.
	 * @param KeyformBundle bundle      The re-bundled grid.
	 * @param List          cellForms   One form object per bundle cell, in cell order.
	 * @return Boolean True on success.
	 */
	fun writeGridWeb(
		owner: Any,
		subject: String,
		currentGrid: Any?,
		assignGrid: (Any?) -> Unit,
		bundle: KeyformBundle,
		cellForms: List<ACForm>,
	): Boolean {
		if (bundle.cells.isEmpty()) {
			// Only a geometry owner reaches here (buildBundle gives every other unkeyed owner its default
			// cell): its rest geometry left with its last axis, so there is no form to write and the grid
			// is removed.  The editor wants a default cell there too; keeping an axis-less rest cell when
			// the last axis collapses is the model's job (:edit), not this writer's to invent.
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
			elementsOf(gridSource.keyformBindings).filterIsInstance<KeyformBindingSource>()
				.associateBy { binding -> uuidOf(binding.parameterGuid) }
		val bindingPerAxis = ArrayList<KeyformBindingSource>(bundle.axes.size)

		for (axis in bundle.axes) {
			val parameterSource = index.parameterByIdStr[axis.parameterId.raw]

			if (parameterSource == null) {
				notices.unsupported(ExportEntityCategory.Keyform, subject, ExportNoticeReason.AxisParameterHasNoSource(axis.parameterId.raw))

				return false
			}

			val binding =
				existingBindings[uuidOf(parameterSource.guid)]
					?: KeyformBindingSource().apply {
						_gridSource = gridSource
						parameterGuid = parameterSource.guid
						keys = ArrayList<Any?>()
						// CMO3: KeyformBindingSource fields interpolationType /
						// extendedInterpolationType / insertPointCount / extendedInterpolationScale /
						// description - every corpus binding writes LINEAR / LINEAR / 1 / 1.0 / "".
						interpolationType = InterpolationType.LINEAR
						extendedInterpolationType = ExtendedInterpolationType.LINEAR
						insertPointCount = 1
						extendedInterpolationScale = 1f
						description = ""
					}
			val keysList = mutableGraphListOf(binding.keys)

			if (keysList != null) {
				keysList.clear()
				axis.keys.forEach { key -> keysList.add(key) }
			} else {
				binding.keys = ArrayList<Any?>(axis.keys.map { it })
				editor.ensureChildSlot(binding, "KeyformBindingSource", "keys", "interpolationType")
			}

			bindingPerAxis.add(binding)
		}

		// CMO3: KeyformGridSource fields keyformBindings / keyformsOnGrid - array_list in every
		// corpus model (vs the carray_list keyforms pools).
		editor.writeGraphList(gridSource, "KeyformGridSource", "keyformBindings", "keyformsOnGrid", gridSource.keyformBindings, bindingPerAxis, GraphListKind.ArrayList) {
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
								ArrayList<Any?>().apply {
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

		editor.writeGraphList(gridSource, "KeyformGridSource", "keyformsOnGrid", null, gridSource.keyformsOnGrid, cellRecords, GraphListKind.ArrayList) {
			gridSource.keyformsOnGrid = it
		}

		return true
	}

	/**
	 * The guids of the forms the current grid's cells reference.
	 *
	 * @param Any? gridSourceField The owner's grid-source field, as stored.
	 * @return Set<String> The form guids, empty when there is no grid.
	 */
	fun gridFormGuids(gridSourceField: Any?): Set<String> {
		val gridSource = gridSourceField as? KeyformGridSource ?: return emptySet()

		return elementsOf(gridSource.keyformsOnGrid).filterIsInstance<KeyformOnGrid>()
			.mapNotNull { cell -> uuidOf(cell.keyformGuid) }
			.toSet()
	}

	/**
	 * Writes the owner's keyforms pool: the grid forms in cell order, then the morph forms.
	 *
	 * @param Any owner The graph object that owns the pool.
	 * @param String ownerTag The class level of the owner, by tag.
	 * @param String property The field that holds the pool.
	 * @param String? beforeProperty The field the pool is written before, or null.
	 * @param Any? current The pool as currently stored.
	 * @param List<ACForm> gridForms The grid forms, in cell order.
	 * @param List<ACForm> morphForms The morph-target forms.
	 * @param Function assign Stores a new pool list on the owner.
	 */
	fun writePool(
		owner: Any,
		ownerTag: String,
		property: String,
		beforeProperty: String?,
		current: Any?,
		gridForms: List<ACForm>,
		morphForms: List<ACForm>,
		assign: (MutableList<Any?>) -> Unit,
	) {
		val gridIdentity = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
		gridForms.forEach { gridIdentity.add(it) }
		val pool = ArrayList<Any?>(gridForms.size + morphForms.size)
		pool.addAll(gridForms)

		for (form in morphForms) {
			if (form !in gridIdentity) {
				pool.add(form)
				gridIdentity.add(form)
			}
		}

		// CMO3: ACParameterControllableSource field keyforms - carray_list in every corpus model;
		// the editor's deserializers cast it to CArrayList, so array_list fails its load.
		editor.writeGraphList(owner, ownerTag, property, beforeProperty, current, pool, GraphListKind.CArrayList, assign)
	}
}

/**
 * A map key for a set of parameter values, the same for the same values in any order.
 *
 * @param Map<ParameterId, Float> values The parameter values of one grid cell.
 * @return String The key, built from each value's exact bits.
 */
internal fun valueKey(values: Map<ParameterId, Float>): String =
	values.entries
		.sortedBy { entry -> entry.key.raw }
		.joinToString("|") { entry -> "${entry.key.raw}=${entry.value.toRawBits()}" }