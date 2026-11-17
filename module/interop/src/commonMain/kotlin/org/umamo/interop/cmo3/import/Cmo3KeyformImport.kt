package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.model.gen.ACForm
import org.umamo.format.cmo3.model.gen.KeyOnParameter
import org.umamo.format.cmo3.model.gen.KeyformBindingSource
import org.umamo.format.cmo3.model.gen.KeyformGridAccessKey
import org.umamo.format.cmo3.model.gen.KeyformGridSource
import org.umamo.format.cmo3.model.gen.KeyformOnGrid
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId

/*
 * The keyform-grid reader of the CMO3 import, shared by every producer whose sources carry a grid:
 * parts, deformers, drawables, and glues.
 */

/**
 * Builds the keyform grid for a controllable source.  CMO3: `keyformGridSource` holds the axes
 * (`keyformBindings`: parameterGuid + key values) and the cells (`keyformsOnGrid`: an accessKey
 * coordinate + a `keyformGuid` referencing one of the source's `keyforms` form objects).
 *
 * @param Any?     gridSource    The source's `keyformGridSource`.
 * @param Any?     forms         The source's `keyforms` collection (the form objects).
 * @param Map      paramIdByUuid uuid → ParameterId, from pass 1.
 * @param Function cellPayload   Builds the typed form payload from a raw form object.
 * @return KeyformGrid<TForm>? The grid, or null when the source carries no grid.
 */
internal fun <TForm : Any> buildGrid(
	gridSource: Any?,
	forms: Any?,
	paramIdByUuid: Map<String, ParameterId>,
	cellPayload: (Any) -> TForm?,
): KeyformGrid<TForm>? {
	val grid = gridSource as? KeyformGridSource ?: return null
	val bindings = elementsOf(grid.keyformBindings).filterIsInstance<KeyformBindingSource>()
	val axes =
		bindings.map { binding ->
			KeyformAxis(
				parameterId = paramIdByUuid[uuidOf(binding.parameterGuid)] ?: ParameterId(""),
				keys = floatKeys(binding.keys),
			)
		}
	// accessKey entries point at a binding object (resolved by identity), giving each cell's coord.
	val axisOfBinding = bindings.withIndex().associate { (index, binding) -> binding to index }
	val formByUuid = formPool(forms)
	val cells =
		elementsOf(grid.keyformsOnGrid).filterIsInstance<KeyformOnGrid>().mapNotNull { cell ->
			val form = formByUuid[uuidOf(cell.keyformGuid)] ?: return@mapNotNull null
			val payload = cellPayload(form) ?: return@mapNotNull null
			val coordinate = IntArray(axes.size)
			val keyList = (cell.accessKey as? KeyformGridAccessKey)?._keyOnParameterList

			for (keyOnParameter in elementsOf(keyList).filterIsInstance<KeyOnParameter>()) {
				val axisIndex = axisOfBinding[keyOnParameter.binding] ?: continue
				coordinate[axisIndex] = keyOnParameter.keyIndex
			}

			KeyformCell(coordinate, payload)
		}

	return KeyformGrid(axes, cells)
}

/**
 * Decodes a binding's `keys` (an `ArrayList<Float>` of parameter values) to a FloatArray.
 *
 * @param Any? keys The binding's `keys` field.
 * @return FloatArray The key parameter values (empty when absent).
 */
private fun floatKeys(keys: Any?): FloatArray {
	val values = elementsOf(keys)

	return FloatArray(values.size) { (values[it] as? Number)?.toFloat() ?: 0f }
}

/**
 * Reads a form's own guid (`ACForm.guid`), used to resolve a cell's `keyformGuid` reference.
 *
 * @param Any? form A form object (CArtMeshForm / CWarpDeformerForm / CRotationDeformerForm).
 * @return Any? The form's `guid` (a `Guid`), or null.
 */
private fun formGuid(form: Any?): Any? = (form as? ACForm)?.guid

/**
 * Indexes a source's `keyforms` pool by each form's own guid uuid.  Grid cells and morph
 * targets both resolve their form references through this pool.
 *
 * @param Any? forms The source's `keyforms` collection (the form objects).
 * @return Map The uuid → form object index.
 */
internal fun formPool(forms: Any?): Map<String?, Any?> = elementsOf(forms).associateBy { uuidOf(formGuid(it)) }