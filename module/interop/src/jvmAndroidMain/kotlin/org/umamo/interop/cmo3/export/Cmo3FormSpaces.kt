package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.drawable.CoordType
import org.umamo.format.cmo3.model.gen.ACDeformerForm
import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.ACDrawableForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CRotationDeformerSource
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.interop.cmo3.elementsOf

/**
 * The coordinate-space tag every keyform carries, kept to one shared CoordType per name for a whole
 * field pass, so a fresh form and a re-parented owner's retagged forms name the same instance.
 */
internal class Cmo3FormSpaces(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
) {
	private val editor = context.editor

	/**
	 * Per-name CoordType instances shared across every form the field pass tags, seeded from the forms
	 * the graph already carries so a fresh or re-tagged form references the file's own shared def for
	 * that name (the writer hoists one def per name, the editor's own shape; it never writes a null
	 * coordType).
	 */
	private val coordTypes: HashMap<String, CoordType> by lazy {
		val byName = HashMap<String, CoordType>()
		val pools = index.drawableSources.map { source -> source.keyforms } + index.deformerSources.map { source -> keyformsOf(source) }

		for (pool in pools) {
			for (form in elementsOf(pool)) {
				val coordType = coordTypeOf(form) ?: continue
				byName.putIfAbsent(coordType.coordName, coordType)
			}
		}

		byName
	}

	/**
	 * The CoordType a form gets for its owner's binding.
	 *
	 * CMO3: ACDrawableForm / ACDeformerForm field coordType - corpus forms carry "DeformerLocal"
	 * under a parent deformer and "Canvas" at the deformer-tree root (the space the stored positions
	 * are expressed in).
	 *
	 * @param Boolean hasParentDeformer Whether the owning item deforms under a parent deformer.
	 * @return CoordType The shared per-export instance for that name.
	 */
	private fun formCoordType(hasParentDeformer: Boolean): CoordType =
		coordTypes.getOrPut(if (hasParentDeformer) "DeformerLocal" else "Canvas") {
			CoordType().apply {
				coordName = if (hasParentDeformer) "DeformerLocal" else "Canvas"
			}
		}

	/**
	 * The CoordType a fresh form takes: the [template]'s own instance when the sibling is tagged for
	 * the same binding (the shared def the editor writes), else the shared instance for the owner's
	 * binding, since a sibling from another owner may sit on the other side of the deformer tree.
	 *
	 * @param Any?    template          The sibling form conventions are cloned from, or null.
	 * @param Boolean hasParentDeformer Whether the owning item deforms under a parent deformer.
	 * @return CoordType The instance to tag the fresh form with.
	 */
	fun freshFormCoordType(template: Any?, hasParentDeformer: Boolean): CoordType {
		val wanted = formCoordType(hasParentDeformer)

		return coordTypeOf(template)?.takeIf { templateCoordType -> templateCoordType.coordName == wanted.coordName } ?: wanted
	}

	/**
	 * Tags every form of [source] with the space an owner under [hasParentDeformer] stores its
	 * positions in, so after a rebind the editor reads the positions in the new parent's space.  A form
	 * already tagged with that name is left as it is, so an unchanged file re-emits its bytes.
	 *
	 * CMO3: ACDrawableForm / ACDeformerForm field coordType.
	 *
	 * @param Any     source            The drawable or deformer source whose forms are re-tagged.
	 * @param Boolean hasParentDeformer Whether the owner now deforms under a parent deformer.
	 */
	fun retagFormSpaces(source: Any, hasParentDeformer: Boolean) {
		val pool =
			when (source) {
				is CArtMeshSource -> source.keyforms
				is ACDeformerSource -> keyformsOf(source)
				else -> return
			}
		val coordType = formCoordType(hasParentDeformer)

		for (form in elementsOf(pool)) {
			if (coordTypeOf(form)?.coordName == coordType.coordName) {
				continue
			}

			when (form) {
				is ACDrawableForm -> {
					form.coordType = coordType
					editor.ensureChildSlot(form, "ACDrawableForm", "coordType")
				}

				is ACDeformerForm -> {
					form.coordType = coordType
					editor.ensureChildSlot(form, "ACDeformerForm", "coordType")
				}
			}
		}
	}

	/**
	 * The keyforms pool of a deformer source, declared per concrete class rather than on
	 * ACDeformerSource.
	 *
	 * @param ACDeformerSource source The deformer source.
	 * @return Any? Its keyforms, or null for a kind without one.
	 */
	private fun keyformsOf(source: ACDeformerSource): Any? =
		when (source) {
			is CWarpDeformerSource -> source.keyforms
			is CRotationDeformerSource -> source.keyforms
			else -> null
		}

	/**
	 * The CoordType a form carries, or null for a form of another kind or one tagged with nothing.
	 *
	 * @param Any? form The form.
	 * @return CoordType? Its coordType.
	 */
	private fun coordTypeOf(form: Any?): CoordType? =
		when (form) {
			is ACDrawableForm -> form.coordType
			is ACDeformerForm -> form.coordType
			else -> null
		} as? CoordType
}