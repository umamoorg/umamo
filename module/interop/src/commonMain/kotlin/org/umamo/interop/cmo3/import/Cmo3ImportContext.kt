package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CPartSourceSet
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.idStrOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId

/**
 * What every CMO3 import producer reads: the model root, its source lists, and the first pass over them.
 *
 * The raw graph is loosely typed and encodes its structure as GUID strings (`targetDeformerGuid`,
 * `parentGuid`, `clipGuidList`), not object links.  So the first pass indexes every source by its GUID
 * uuid -> runtime id, per category, and each producer resolves its cross-references through those
 * indexes.  Immutable and derived once, so every producer reads the same lists and the same indexes.
 *
 * @property CModelSource modelSource The parsed model root.
 */
internal class Cmo3ImportContext(val modelSource: CModelSource) {
	val parameterSources =
		elementsOf((modelSource.parameterSourceSet as? CParameterSourceSet)?._sources)
			.filterIsInstance<CParameterSource>()
	val partSources =
		elementsOf((modelSource.partSourceSet as? CPartSourceSet)?._sources)
			.filterIsInstance<CPartSource>()
	val deformerSources =
		elementsOf((modelSource.deformerSourceSet as? CDeformerSourceSet)?._sources)
			.filterIsInstance<ACDeformerSource>()
	val drawableSources =
		elementsOf((modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources)
			.filterIsInstance<CArtMeshSource>()

	// CMO3: rootPart is the synthetic __RootPart__ tree anchor - the editor excludes it from its
	// part count, and entities directly under it are top-level (partId == null).  Drop it from the
	// user-facing parts so the model matches the editor and null cleanly means "at the root".
	val rootPart = modelSource.rootPart
	val rootPartUuid =
		when (rootPart) {
			is CPartSource -> uuidOf(rootPart.guid)
			else -> uuidOf(rootPart)
		}
	val userPartSources = partSources.filter { uuidOf(it.guid) != rootPartUuid }

	// Pass 1 - GUID uuid → runtime id, per category.  Cross-references resolve through these.
	val partIdByUuid =
		buildMap {
			for (part in userPartSources) {
				val uuid = uuidOf(part.guid) ?: continue
				val id = idStrOf(part.id) ?: continue
				put(uuid, PartId(id))
			}
		}
	val deformerIdByUuid =
		buildMap {
			for (deformer in deformerSources) {
				val uuid = uuidOf(deformer.guid) ?: continue
				val id = idStrOf(deformer.id) ?: continue
				put(uuid, DeformerId(id))
			}
		}
	val drawableIdByUuid =
		buildMap {
			for (drawable in drawableSources) {
				val uuid = uuidOf(drawable.guid) ?: continue
				val id = idStrOf(drawable.id) ?: continue
				put(uuid, DrawableId(id))
			}
		}
	val paramIdByUuid =
		buildMap {
			for (param in parameterSources) {
				val uuid = uuidOf(param.guid) ?: continue
				val id = idStrOf(param.id) ?: continue
				put(uuid, ParameterId(id))
			}
		}

	// Parts-panel pre-order index over the part tree, used to order the flat drawables list back-to-front
	// (the empty-renderRoot fallback / storage order; the derived renderRoot is the draw-order authority).
	val renderIndexByUuid = partsTreeRenderOrder(modelSource, partSources)

	/**
	 * Resolves a part's child guids into org children, in parts-panel order.
	 *
	 * CMO3: CPartSource._childGuids is the parts-panel order - sub-parts and drawables interleaved (plus
	 * deformers, which we route to the Armature instead).  Resolve each child guid to a part or drawable
	 * org child, skipping anything else; this ordered list is the org tree's single source of truth.
	 *
	 * @param Any? childGuids The part's child-guid list, as stored.
	 * @return List<OrgChild> The sub-parts and drawables, in panel order.
	 */
	fun orgChildrenOf(childGuids: Any?): List<OrgChild> =
		elementsOf(childGuids).mapNotNull { childGuid ->
			val childUuid = uuidOf(childGuid) ?: return@mapNotNull null
			drawableIdByUuid[childUuid]?.let { return@mapNotNull OrgChild.Drawable(it) }
			partIdByUuid[childUuid]?.let { return@mapNotNull OrgChild.Part(it) }
			null
		}
}

/**
 * Assigns each entity a pre-order index from a depth-first walk of the parts tree, following every
 * part's ordered `_childGuids`.  Drawables sorted by their index render in the editor's panel
 * stacking order.  Non-part children (deformers, meshes) are leaves and get an index without
 * recursing; the gaps they leave do not matter since only relative order is used.
 *
 * @param CModelSource      modelSource The model root (for `rootPart`).
 * @param List<CPartSource> partSources All part sources (including the synthetic root).
 * @return Map<String,Int> uuid → render-order index.
 */
private fun partsTreeRenderOrder(modelSource: CModelSource, partSources: List<CPartSource>): Map<String, Int> {
	val partByUuid = partSources.mapNotNull { part -> uuidOf(part.guid)?.let { it to part } }.toMap()
	val rootPart = modelSource.rootPart
	val rootUuid = (rootPart as? CPartSource)?.let { uuidOf(it.guid) } ?: uuidOf(rootPart)
	val order = HashMap<String, Int>()
	val visited = HashSet<String>()
	var nextIndex = 0

	/**
	 * Numbers a panel entry and, when it is a part, everything under it, in parts-panel order.
	 *
	 * @param String uuid The entry's guid.
	 */
	fun walk(uuid: String) {
		if (!visited.add(uuid)) {
			return
		}

		order[uuid] = nextIndex++
		val part = partByUuid[uuid] ?: return

		for (child in elementsOf(part._childGuids)) {
			val childUuid = uuidOf(child) ?: continue
			walk(childUuid)
		}
	}

	if (rootUuid != null) {
		walk(rootUuid)
	}

	return order
}

/**
 * Resolves a CMO3 GUID list (e.g. `_childGuids`, `clipGuidList`) to runtime ids via [index].
 *
 * @param Any?         guidList A field holding a collection of `Guid` objects.
 * @param Map<String,T> index   uuid → runtime id, from pass 1.
 * @return List<T> The resolved ids, skipping any that do not resolve.
 */
internal fun <T> resolveGuids(guidList: Any?, index: Map<String, T>): List<T> =
	elementsOf(guidList).mapNotNull { index[uuidOf(it)] }

/**
 * Converts a CMO3 `CFloatColor` field to the runtime [ColorRgb].
 *
 * @param Any? value A field expected to hold a `CFloatColor` (channels = editor hex / 255).
 * @return ColorRgb? The color, or null when absent (pre-5.3 forms carry no color elements).
 */
internal fun colorRgbOf(value: Any?): ColorRgb? =
	// CMO3: CFloatColor red/green/blue attributes; the alpha attribute is 1.0 in every observed
	// sample and is not modeled.
	(value as? CFloatColor)?.let { ColorRgb(it.red, it.green, it.blue) }