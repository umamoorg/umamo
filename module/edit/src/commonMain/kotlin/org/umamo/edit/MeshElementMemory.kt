package org.umamo.edit

import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel

/**
 * The session's remembered-selection memory: which owner was last active (so entering Edit mode
 * with an empty selection lands somewhere sensible - Blender's remembered selection), and each
 * owner's stashed element selection (stashed when Edit mode is left or the session switches meshes,
 * restored per owner on re-entry when the elements still fit - Blender remembers the mesh
 * selection across mode switches per mesh).  An owner is a drawable's mesh or a warp's lattice.
 * Transient UI convenience, deliberately NOT part of [EditorSnapshot]: undo restores whatever the
 * snapshot holds, this only seeds fresh entries.
 *
 * セッションの選択記憶。最後にアクティブだった描画対象と、メッシュごとの要素選択スタッシュを保持
 * する（Blender のモード切替をまたぐ選択記憶に相当）。スナップショットには含めない。
 */
internal class MeshElementMemory {
	/** The last owner that was active - a drawable or a warp - or null before any; Edit-mode entry falls back to it. */
	var lastActiveOwner: ElementOwner? = null

	// Per-owner element-selection memory, plus the select mode remembered alongside.
	private val rememberedElementsByOwner = HashMap<ElementOwner, Set<MeshElement>>()
	private val rememberedActiveElementByOwner = HashMap<ElementOwner, MeshElement>()
	private var rememberedSelectMode: MeshSelectMode? = null

	/**
	 * Stashes each session owner's element selection into the per-owner memory, so re-entering Edit
	 * mode - or switching back to the mesh with Alt+Q - restores it.  An emptied owner clears its slot;
	 * the select mode is remembered alongside.
	 *
	 * @param MeshSelection current The element selection to stash.
	 */
	fun stash(current: MeshSelection) {
		if (current.owners.isEmpty()) {
			return
		}

		rememberedSelectMode = current.selectMode

		for (owner in current.owners) {
			val elements = current.elementsOf(owner)

			if (elements.isEmpty()) {
				rememberedElementsByOwner.remove(owner)
				rememberedActiveElementByOwner.remove(owner)
			} else {
				rememberedElementsByOwner[owner] = elements
			}
		}

		current.activeElement?.let { active -> rememberedActiveElementByOwner[active.owner] = active.element }
	}

	/**
	 * Forgets the remembered elements of owners whose vertices were all replaced.  A restore checks only
	 * that the remembered indices exist, so after a regenerated mesh a stale memory would select
	 * different vertices under the same numbers.  The remembered select mode and the last active
	 * owner stay.
	 *
	 * @param Collection<ElementOwner> owners The owners to forget.
	 */
	fun forget(owners: Collection<ElementOwner>) {
		for (owner in owners) {
			rememberedElementsByOwner.remove(owner)
			rememberedActiveElementByOwner.remove(owner)
		}
	}

	/**
	 * Restores the per-owner remembered elements into [seeded]: each seeded owner whose stored
	 * elements still fit its current topology gets them back (validated per owner, so one stale owner
	 * never discards the others' memory); the remembered select mode returns when anything restored.
	 *
	 * @param MeshSelection seeded The freshly seeded session selection.
	 * @param PuppetModel model The current model (element fit is validated against its meshes and lattices).
	 * @return MeshSelection The seeded selection with the remembered elements folded in.
	 */
	fun restore(seeded: MeshSelection, model: PuppetModel): MeshSelection {
		val restored =
			seeded.owners.mapNotNull { owner ->
				val stored = rememberedElementsByOwner[owner]?.takeIf { elements -> elements.isNotEmpty() } ?: return@mapNotNull null
				val probe = seeded.copy(selectMode = rememberedSelectMode ?: seeded.selectMode, elementsByOwner = mapOf(owner to stored))
				val fits = MeshSelectionOps.fitsWithinMeshes(probe) { candidate -> model.editTopologyOf(candidate) }
				if (fits) owner to stored else null
			}.toMap()

		if (restored.isEmpty()) {
			return seeded
		}

		// The active owner's remembered active element returns with it (when it is still selected there).
		val restoredActive =
			seeded.activeOwner?.let { activeOwner ->
				rememberedActiveElementByOwner[activeOwner]
					?.takeIf { element -> restored[activeOwner]?.contains(element) == true }
					?.let { element -> ActiveMeshElement(activeOwner, element) }
			}

		return seeded.copy(
			selectMode = rememberedSelectMode ?: seeded.selectMode,
			elementsByOwner = restored,
			activeElement = restoredActive,
		)
	}
}