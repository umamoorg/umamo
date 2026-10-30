package org.umamo.edit

import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel

/**
 * The session's remembered-selection memory: which owner was last active (so entering Edit or Pose mode
 * with an empty selection lands somewhere sensible - Blender's remembered selection), and each
 * owner's stashed element selection (stashed when Edit or Pose mode is left or the session switches owners,
 * restored per owner on re-entry when the elements still fit - Blender remembers the mesh
 * selection across mode switches per mesh).  An owner is a drawable's mesh or a warp's lattice.
 * The select mode is not remembered here: the session carries it through every switch, and each owner's
 * elements keep the domain they were stashed in so a restore converts them into whatever mode is current.
 * Transient UI convenience, deliberately NOT part of [EditorSnapshot]: undo restores whatever the
 * snapshot holds, this only seeds fresh entries.
 */
internal class MeshElementMemory {
	/** The last owner that was active - a drawable or a warp - or null before any; Edit- and Pose-mode entry fall back to it. */
	var lastActiveOwner: ElementOwner? = null

	/**
	 * Remembers [selection]'s active item as the last active owner when it is one (a drawable or a warp); a part, or
	 * no active item, leaves the memory as it was.  The one rule every path that shows a selection keeps.
	 *
	 * @param Selection selection The selection now shown.
	 */
	fun rememberActiveOwnerOf(selection: Selection) {
		editOwnerOf(selection.active)?.let { owner -> lastActiveOwner = owner }
	}

	/**
	 * One owner's stashed elements and the select mode they belong to.
	 *
	 * @property MeshSelectMode selectMode The domain [elements] are in.
	 * @property Set<MeshElement> elements The stashed elements.
	 */
	private class RememberedElements(
		val selectMode: MeshSelectMode,
		val elements: Set<MeshElement>,
	)

	// Per-owner element-selection memory, each set kept in the domain it was stashed in.
	private val rememberedElementsByOwner = HashMap<ElementOwner, RememberedElements>()
	private val rememberedActiveElementByOwner = HashMap<ElementOwner, MeshElement>()

	/**
	 * Stashes each session owner's element selection into the per-owner memory, so re-entering Edit
	 * or Pose mode - or switching back to the owner with Alt+Q - restores it.  An emptied owner clears its slot;
	 * each set keeps the select mode it was made in.
	 *
	 * @param MeshSelection current The element selection to stash.
	 */
	fun stash(current: MeshSelection) {
		for (owner in current.owners) {
			val elements = current.elementsOf(owner)

			if (elements.isEmpty()) {
				rememberedElementsByOwner.remove(owner)
				rememberedActiveElementByOwner.remove(owner)
			} else {
				rememberedElementsByOwner[owner] = RememberedElements(current.selectMode, elements)
			}
		}

		current.activeElement?.let { active -> rememberedActiveElementByOwner[active.owner] = active.element }
	}

	/**
	 * Forgets the remembered elements of owners whose vertices were all replaced.  A restore checks only
	 * that the remembered indices exist, so after a regenerated mesh a stale memory would select
	 * different vertices under the same numbers.  The last active owner stays.
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
	 * Restores the per-owner remembered elements into [seeded], in [seeded]'s select mode: each seeded owner whose
	 * stored elements still fit its current topology gets them back (validated per owner, so one stale owner never
	 * discards the others' memory), converted from the domain they were stashed in with Blender's select-mode rules
	 * ([MeshSelectionOps.changeSelectMode]).  The select mode itself is never changed.
	 *
	 * @param MeshSelection seeded The freshly seeded session selection, in the session's select mode.
	 * @param PuppetModel model The current model (element fit is validated against its meshes and lattices).
	 * @return MeshSelection The seeded selection with the remembered elements folded in.
	 */
	fun restore(seeded: MeshSelection, model: PuppetModel): MeshSelection {
		val topologyOf = { candidate: ElementOwner -> model.editTopologyOf(candidate) }
		val restored =
			seeded.owners.mapNotNull { owner ->
				val stored = rememberedElementsByOwner[owner]?.takeIf { remembered -> remembered.elements.isNotEmpty() } ?: return@mapNotNull null
				// Fit is checked in the stored domain before converting, since the conversion reads the topology by index.
				val probe = MeshSelection(owners = listOf(owner), activeOwner = owner, selectMode = stored.selectMode, elementsByOwner = mapOf(owner to stored.elements))

				if (!MeshSelectionOps.fitsWithinMeshes(probe, topologyOf)) {
					return@mapNotNull null
				}

				MeshSelectionOps.changeSelectMode(probe, seeded.selectMode, topologyOf)
					.elementsOf(owner)
					.takeIf { elements -> elements.isNotEmpty() }
					?.let { elements -> owner to elements }
			}.toMap()

		if (restored.isEmpty()) {
			return seeded
		}

		// The active owner's remembered active element returns with it (when it is still selected there, which an
		// element of another domain never is).
		val restoredActive =
			seeded.activeOwner?.let { activeOwner ->
				rememberedActiveElementByOwner[activeOwner]
					?.takeIf { element -> restored[activeOwner]?.contains(element) == true }
					?.let { element -> ActiveMeshElement(activeOwner, element) }
			}

		return seeded.copy(
			elementsByOwner = restored,
			activeElement = restoredActive,
		)
	}
}