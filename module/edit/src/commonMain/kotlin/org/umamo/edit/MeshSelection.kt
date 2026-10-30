package org.umamo.edit

import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.deformerId
import org.umamo.runtime.model.drawableId

/**
 * The Edit-mode selection domain, mirroring Blender's vertex / edge / face select modes: which kind of
 * mesh element the pointer picks and the selection stores.  Only the current mode's domain is stored in
 * [MeshSelection.elementsByOwner]; highlights in the other domains are derived for display, and
 * switching modes converts the stored sets (see [MeshSelectionOps.changeSelectMode]).
 *
 * 編集モードの選択対象の種類。Blender の頂点・辺・面選択モードに対応する。
 */
enum class MeshSelectMode {
	Vertex,
	Edge,
	Face,
}

/**
 * One selectable mesh element: a vertex, an undirected edge, or a face.  Element identity is LOCAL to
 * its owner - a drawable's mesh or a warp's lattice (a vertex index means nothing without knowing whose), which
 * is why [MeshSelection] stores elements per owner and the active element carries its owner alongside.
 * The mesh topology is immutable while editing (copy-on-write replaces only positions), so vertex
 * indices, canonical (low, high) edge pairs, and face ordinals are stable identities across every
 * gesture.
 *
 * メッシュの選択可能要素。頂点・無向辺・面のいずれか。要素の同一性はメッシュ内ローカル。
 */
sealed interface MeshElement {
	/**
	 * A single mesh vertex.
	 *
	 * @property Int index The vertex index (into the mesh's vertex list).
	 */
	data class Vertex(val index: Int) : MeshElement

	/**
	 * An undirected mesh edge, canonicalized so (a, b) and (b, a) are one identity.  Construct via [of].
	 *
	 * @property Int endpointLow The smaller endpoint vertex index.
	 * @property Int endpointHigh The larger endpoint vertex index.
	 */
	data class Edge(val endpointLow: Int, val endpointHigh: Int) : MeshElement {
		companion object {
			/**
			 * Builds the canonical undirected edge for two endpoint vertex indices, given in either order.
			 *
			 * @param Int vertexA One endpoint vertex index.
			 * @param Int vertexB The other endpoint vertex index.
			 * @return Edge The canonical (low, high) edge.
			 */
			fun of(vertexA: Int, vertexB: Int): Edge =
				if (vertexA <= vertexB) {
					Edge(vertexA, vertexB)
				} else {
					Edge(vertexB, vertexA)
				}
		}
	}

	/**
	 * A single face: its ordinal in the owner's [ElementTopology] - one triangle of a drawable's index list, or one
	 * cell of a lattice.
	 *
	 * @property Int index The face ordinal.
	 */
	data class Face(val index: Int) : MeshElement
}

/**
 * The active (primary) element of an Edit session: the last-touched element together with the owner whose
 * mesh or lattice it lives in - element indices are local to their owner, so the pair is the element's full
 * identity.
 *
 * @property ElementOwner owner The drawable or warp whose elements hold the element.
 * @property MeshElement element The last-touched element.
 */
data class ActiveMeshElement(val owner: ElementOwner, val element: MeshElement) {
	/** The drawable the element lives on, or null when it lives on a lattice. */
	val drawableId: DrawableId? get() = owner.drawableId
}

/**
 * An immutable Edit-mode sub-object selection over one or more owners' elements - drawables' meshes, or
 * warps' lattices: which owners are in the edit session, the select mode (vertex / edge / face), and the
 * selected elements per owner with the active (primary) one.  A session edits one kind of owner, the active
 * item's (Blender's rule), so its owners are all drawables or all warps; this type does not enforce it.  This is
 * the sub-object analog of [Selection] - where object-mode selects whole
 * entities, Edit mode selects elements inside the session meshes - and the multi-mesh shape is what lets
 * a rigger edit several art meshes at once (entering Edit with a multi-selection, and later the glue
 * workflows that need two meshes live together).
 *
 * Only the current [selectMode]'s domain is ever stored in [elementsByOwner] (a vertex-mode selection
 * holds only [MeshElement.Vertex] members, and so on).  Cross-domain highlights - an edge lighting up
 * because both endpoints are selected in vertex mode - are derived for display via [MeshTopology], never
 * stored, which is exactly Blender's model.
 *
 * [owners] is empty when the session is inert (a Pose mode with no warp the pose draws); the overlay
 * then draws nothing and the transform operators no-op.  [activeOwner] is Blender's active object
 * within the session - the owner a click last landed on, which anchors per-owner actions (select-mode
 * conversion display, the status bar's denominators).  [activeElement] is the element analog of
 * [Selection.active]: the last-touched element with its owner; it is always a member of its
 * owner's element set, or null when nothing is selected.
 *
 * @property List<ElementOwner> owners The owners in the edit session, in selection order.
 * @property ElementOwner? activeOwner The session's active owner, or null when the session is inert.
 * @property MeshSelectMode selectMode The element domain clicks select (vertex / edge / face).  Session-wide: it is
 *   carried across owner switches and mode switches, and held by the empty session of Object mode so the next Edit or
 *   Pose entry selects in it.
 * @property Map<ElementOwner, Set<MeshElement>> elementsByOwner The selected elements per owner, all of
 *   the current mode's domain; an owner with no selection may be absent.
 * @property ActiveMeshElement? activeElement The primary element (last touched), or null when none.
 */
data class MeshSelection(
	val owners: List<ElementOwner> = emptyList(),
	val activeOwner: ElementOwner? = null,
	val selectMode: MeshSelectMode = MeshSelectMode.Vertex,
	val elementsByOwner: Map<ElementOwner, Set<MeshElement>> = emptyMap(),
	val activeElement: ActiveMeshElement? = null,
) {
	/** True when no elements are selected on any owner (the session may still hold owners). */
	val isEmpty: Boolean get() = elementsByOwner.values.all { elements -> elements.isEmpty() }

	/** The number of selected elements across every session owner. */
	val size: Int get() = elementsByOwner.values.sumOf { elements -> elements.size }

	/** The session's drawables, in session order: its owners that are meshes, for a surface that edits only those. */
	val drawableIds: List<DrawableId> get() = owners.mapNotNull { owner -> owner.drawableId }

	/** The session's warps, in session order: its owners that are lattices. */
	val deformerIds: List<DeformerId> get() = owners.mapNotNull { owner -> owner.deformerId }

	/** Whether the session edits lattices alone: it holds owners, and every one is a warp's lattice. */
	val editsLattices: Boolean get() = owners.isNotEmpty() && owners.all { owner -> owner is ElementOwner.Deformer }

	/** The active owner when it is a drawable, or null when the session is inert or edits lattices. */
	val activeDrawableId: DrawableId? get() = activeOwner?.drawableId

	/**
	 * The selected elements of one owner (empty when it has no selection or is not in the session).
	 *
	 * @param ElementOwner owner The owner to read.
	 * @return Set<MeshElement> That owner's selected elements.
	 */
	fun elementsOf(owner: ElementOwner): Set<MeshElement> = elementsByOwner[owner] ?: emptySet()

	/**
	 * The selected elements of one drawable's mesh (empty when it has no selection or is not in the session).
	 *
	 * @param DrawableId drawableId The drawable to read.
	 * @return Set<MeshElement> That mesh's selected elements.
	 */
	fun elementsOf(drawableId: DrawableId): Set<MeshElement> = elementsOf(ElementOwner.Drawable(drawableId))

	/**
	 * Reports whether the given element is selected on the given owner.
	 *
	 * @param ElementOwner owner The owner the element lives on.
	 * @param MeshElement element The element to test.
	 * @return Boolean True when selected.
	 */
	fun contains(owner: ElementOwner, element: MeshElement): Boolean = element in elementsOf(owner)

	companion object {
		/**
		 * An empty Edit session over the given owners: nothing selected, the first (or given) owner active.
		 *
		 * @param List<ElementOwner> owners The session owners, in selection order.
		 * @param ElementOwner? activeOwner The active owner; defaults to the first.
		 * @return MeshSelection The seeded session selection.
		 */
		fun editing(owners: List<ElementOwner>, activeOwner: ElementOwner? = owners.firstOrNull()): MeshSelection =
			MeshSelection(owners = owners, activeOwner = activeOwner)

		/**
		 * An empty Edit session over the given drawables' meshes: [editing] over their owners.
		 *
		 * @param List<DrawableId> drawableIds The session meshes, in selection order.
		 * @param DrawableId? activeDrawableId The active mesh; defaults to the first.
		 * @return MeshSelection The seeded session selection.
		 */
		fun editingDrawables(drawableIds: List<DrawableId>, activeDrawableId: DrawableId? = drawableIds.firstOrNull()): MeshSelection =
			editing(drawableIds.map { drawableId -> ElementOwner.Drawable(drawableId) }, activeDrawableId?.let { activeId -> ElementOwner.Drawable(activeId) })
	}
}

/**
 * Pure transformations over a [MeshSelection], one per selection gesture (mirroring [SelectionOps]).
 * Element gestures name the owner they land on (element identity is owner-local); whole-session
 * gestures (select all, invert, mode conversion) take a topology provider so each session owner converts
 * against its own topology.  Each function returns a new immutable [MeshSelection] over the same
 * session and never mutates its input, so they are unit-testable without Compose.  The active element
 * is maintained per the [MeshSelection] invariant: always a member of its owner's element set, or
 * null when nothing remains.
 */
object MeshSelectionOps {
	/**
	 * Rebuilds the per-owner element map with one owner's set replaced, dropping empty sets so the
	 * map never accumulates stale keys.
	 *
	 * @param MeshSelection selection The selection whose map to rebuild.
	 * @param ElementOwner owner The owner whose set changes.
	 * @param Set<MeshElement> elements That owner's new element set.
	 * @return Map<ElementOwner, Set<MeshElement>> The rebuilt map.
	 */
	private fun withElements(
		selection: MeshSelection,
		owner: ElementOwner,
		elements: Set<MeshElement>,
	): Map<ElementOwner, Set<MeshElement>> =
		if (elements.isEmpty()) {
			selection.elementsByOwner - owner
		} else {
			selection.elementsByOwner + (owner to elements)
		}

	/**
	 * Replaces the whole selection with a single element (a plain click): every session mesh clears and
	 * only [element] on [owner] remains, active, with its mesh becoming the session's active mesh.
	 *
	 * @param MeshSelection selection The selection to modify.
	 * @param ElementOwner owner The owner the click landed on.
	 * @param MeshElement element The sole element to select.
	 * @return MeshSelection A selection holding only [element], with it active.
	 */
	fun replace(selection: MeshSelection, owner: ElementOwner, element: MeshElement): MeshSelection =
		selection.copy(
			activeOwner = owner,
			elementsByOwner = mapOf(owner to setOf(element)),
			activeElement = ActiveMeshElement(owner, element),
		)

	/**
	 * Toggles an element's membership on its mesh (a Shift or Ctrl / Cmd click, Blender-style): removes
	 * it when present, adds it otherwise.  When added it becomes active (and its mesh the active mesh);
	 * when removed the active element falls back to another member of the same mesh, or null.
	 *
	 * @param MeshSelection selection The selection to modify.
	 * @param ElementOwner owner The owner the click landed on.
	 * @param MeshElement element The element to toggle.
	 * @return MeshSelection The resulting selection.
	 */
	fun toggle(selection: MeshSelection, owner: ElementOwner, element: MeshElement): MeshSelection {
		val current = selection.elementsOf(owner)

		return if (element in current) {
			val remaining = current - element
			val fallback = remaining.lastOrNull()?.let { survivor -> ActiveMeshElement(owner, survivor) }
			selection.copy(
				activeOwner = owner,
				elementsByOwner = withElements(selection, owner, remaining),
				activeElement = if (selection.activeElement?.owner == owner && selection.activeElement.element == element) fallback else selection.activeElement,
			)
		} else {
			selection.copy(
				activeOwner = owner,
				elementsByOwner = withElements(selection, owner, current + element),
				activeElement = ActiveMeshElement(owner, element),
			)
		}
	}

	/**
	 * Adds an element without removing anything (an additive gesture), making it active and its mesh the
	 * session's active mesh.
	 *
	 * @param MeshSelection selection The selection to extend.
	 * @param ElementOwner owner The owner the element lives on.
	 * @param MeshElement element The element to add.
	 * @return MeshSelection The extended selection.
	 */
	fun add(selection: MeshSelection, owner: ElementOwner, element: MeshElement): MeshSelection =
		selection.copy(
			activeOwner = owner,
			elementsByOwner = withElements(selection, owner, selection.elementsOf(owner) + element),
			activeElement = ActiveMeshElement(owner, element),
		)

	/**
	 * [replace] on a drawable's mesh.
	 *
	 * @param MeshSelection selection The selection to modify.
	 * @param DrawableId drawableId The mesh the click landed on.
	 * @param MeshElement element The sole element to select.
	 * @return MeshSelection A selection holding only [element], with it active.
	 */
	fun replace(selection: MeshSelection, drawableId: DrawableId, element: MeshElement): MeshSelection = replace(selection, ElementOwner.Drawable(drawableId), element)

	/**
	 * [toggle] on a drawable's mesh.
	 *
	 * @param MeshSelection selection The selection to modify.
	 * @param DrawableId drawableId The mesh the click landed on.
	 * @param MeshElement element The element to toggle.
	 * @return MeshSelection The resulting selection.
	 */
	fun toggle(selection: MeshSelection, drawableId: DrawableId, element: MeshElement): MeshSelection = toggle(selection, ElementOwner.Drawable(drawableId), element)

	/**
	 * [add] on a drawable's mesh.
	 *
	 * @param MeshSelection selection The selection to extend.
	 * @param DrawableId drawableId The mesh the element lives in.
	 * @param MeshElement element The element to add.
	 * @return MeshSelection The extended selection.
	 */
	fun add(selection: MeshSelection, drawableId: DrawableId, element: MeshElement): MeshSelection = add(selection, ElementOwner.Drawable(drawableId), element)

	/**
	 * Applies a box (rubber-band) selection across the session meshes: replaces every mesh's element set
	 * with its entry in [elementsByOwner], or unions them in when [additive].  The active element is
	 * kept when it remains selected, otherwise it drops to null (a box has no single "last touched"
	 * element).  An additive union that adds nothing to a mesh keeps that mesh's set instance, so a
	 * consumer keyed on the set's identity (the GPU mesh overlay) sees the mesh as untouched; a brush
	 * stroke passes every session mesh on every stamp.
	 *
	 * @param MeshSelection selection The selection to modify.
	 * @param Map<ElementOwner, Set<MeshElement>> elementsByOwner The enclosed elements per session owner.
	 * @param Boolean additive True to union with the current sets (Shift box), false to replace them.
	 * @return MeshSelection The resulting selection.
	 */
	fun box(selection: MeshSelection, elementsByOwner: Map<ElementOwner, Set<MeshElement>>, additive: Boolean): MeshSelection {
		val resulting = HashMap<ElementOwner, Set<MeshElement>>()

		if (additive) {
			selection.elementsByOwner.forEach { (owner, elements) -> resulting[owner] = elements }
			elementsByOwner.forEach { (owner, elements) ->
				val current = resulting[owner] ?: emptySet()

				if (!current.containsAll(elements)) {
					resulting[owner] = current + elements
				}
			}
		} else {
			elementsByOwner.forEach { (owner, elements) ->
				if (elements.isNotEmpty()) {
					resulting[owner] = elements
				}
			}
		}

		resulting.values.removeAll { elements -> elements.isEmpty() }
		val active = selection.activeElement?.takeIf { active -> active.element in (resulting[active.owner] ?: emptySet()) }

		return selection.copy(elementsByOwner = resulting, activeElement = active)
	}

	/**
	 * Removes elements per mesh (the erase half of a Circle-select stroke), keeping the active element
	 * only when it survives.  The mirror of [box]'s additive union, and like it, a mesh that loses nothing
	 * keeps its set instance.
	 *
	 * @param MeshSelection selection The selection to shrink.
	 * @param Map<ElementOwner, Set<MeshElement>> elementsByOwner The elements to deselect per owner.
	 * @return MeshSelection The selection with those elements removed.
	 */
	fun remove(selection: MeshSelection, elementsByOwner: Map<ElementOwner, Set<MeshElement>>): MeshSelection {
		val resulting = HashMap<ElementOwner, Set<MeshElement>>(selection.elementsByOwner)
		elementsByOwner.forEach { (owner, elements) ->
			val current = resulting[owner] ?: emptySet()

			if (elements.none { element -> element in current }) {
				return@forEach
			}

			val remaining = current - elements

			if (remaining.isEmpty()) {
				resulting.remove(owner)
			} else {
				resulting[owner] = remaining
			}
		}
		val active = selection.activeElement?.takeIf { active -> active.element in (resulting[active.owner] ?: emptySet()) }

		return selection.copy(elementsByOwner = resulting, activeElement = active)
	}

	/**
	 * Clears the selection, keeping the session meshes and mode (the Edit session stays open, nothing
	 * selected).
	 *
	 * @param MeshSelection selection The selection to clear.
	 * @return MeshSelection The selection with empty element sets.
	 */
	fun clear(selection: MeshSelection): MeshSelection =
		selection.copy(elementsByOwner = emptyMap(), activeElement = null)

	/**
	 * Clears the elements of some owners only, keeping the session owners, the active owner, the
	 * select mode, and every other mesh's elements.  The active element goes when it lies on one of them.
	 *
	 * @param MeshSelection selection The selection to clear from.
	 * @param Set<ElementOwner> owners The owners whose elements to clear.
	 * @return MeshSelection The selection without those owners' elements.
	 */
	fun clearElementsOf(selection: MeshSelection, owners: Set<ElementOwner>): MeshSelection {
		val activeElement = selection.activeElement?.takeIf { active -> active.owner !in owners }

		return selection.copy(elementsByOwner = selection.elementsByOwner - owners, activeElement = activeElement)
	}

	/**
	 * Switches the select mode, converting every session mesh's stored elements into the new domain with
	 * Blender's flush-down / derive-up rules.  Flushing down (to vertex) expands every element to the
	 * vertices it covers, so selection is never lost.  Deriving up is strict per Blender: an edge
	 * qualifies only when both endpoints were selected, and a face only when all of its own vertices
	 * (from vertex mode) or all of its own edges (from edge mode) were selected.  The active
	 * element drops to null on conversion (no single "last touched" element survives a domain change);
	 * a same-mode call returns the selection unchanged.  A session mesh whose topology the provider
	 * cannot resolve converts to empty.
	 *
	 * @param MeshSelection selection The selection to convert.
	 * @param MeshSelectMode newMode The target select mode.
	 * @param Function topologyOf Resolves a session owner's topology, or null when absent.
	 * @return MeshSelection The selection in [newMode] with the converted element sets.
	 */
	fun changeSelectMode(
		selection: MeshSelection,
		newMode: MeshSelectMode,
		topologyOf: (ElementOwner) -> ElementTopology?,
	): MeshSelection {
		if (newMode == selection.selectMode) {
			return selection
		}

		val converted = HashMap<ElementOwner, Set<MeshElement>>()

		for ((owner, elements) in selection.elementsByOwner) {
			val topology = topologyOf(owner) ?: continue
			val convertedElements = convertElements(elements, selection.selectMode, newMode, topology)

			if (convertedElements.isNotEmpty()) {
				converted[owner] = convertedElements
			}
		}

		return selection.copy(selectMode = newMode, elementsByOwner = converted, activeElement = null)
	}

	/**
	 * Converts one mesh's element set between select-mode domains (the single-mesh core of
	 * [changeSelectMode]; see its docblock for the flush / derive rules).
	 *
	 * @param Set<MeshElement> elements The stored elements, all of [fromMode]'s domain.
	 * @param MeshSelectMode fromMode The current domain.
	 * @param MeshSelectMode newMode The target domain.
	 * @param ElementTopology topology The owner's topology.
	 * @return Set<MeshElement> The converted set in [newMode]'s domain.
	 */
	private fun convertElements(
		elements: Set<MeshElement>,
		fromMode: MeshSelectMode,
		newMode: MeshSelectMode,
		topology: ElementTopology,
	): Set<MeshElement> =
		when (newMode) {
			MeshSelectMode.Vertex -> {
				MeshTopology.coveredVertexIndices(elements, topology)
					.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }
					.toSet()
			}

			MeshSelectMode.Edge -> {
				when (fromMode) {
					MeshSelectMode.Vertex -> {
						MeshTopology.edgesWithBothEndpointsSelected(
							topology,
							MeshTopology.coveredVertexIndices(elements, topology),
						)
					}

					MeshSelectMode.Face -> {
						elements
							.filterIsInstance<MeshElement.Face>()
							.flatMap { face -> topology.edgesOfFace(face.index) }
							.toSet()
					}

					// Unreachable: the same-mode case returned above; kept for exhaustiveness.
					MeshSelectMode.Edge -> {
						elements
					}
				}
			}

			MeshSelectMode.Face -> {
				when (fromMode) {
					MeshSelectMode.Vertex -> {
						MeshTopology.facesWithAllVerticesSelected(
							topology,
							MeshTopology.coveredVertexIndices(elements, topology),
						)
					}

					MeshSelectMode.Edge -> {
						MeshTopology.facesWithAllEdgesSelected(
							topology,
							elements.filterIsInstance<MeshElement.Edge>().toSet(),
						)
					}

					// Unreachable: the same-mode case returned above; kept for exhaustiveness.
					MeshSelectMode.Face -> {
						elements
					}
				}
			}
		}

	/**
	 * Every element of a select mode's domain over a mesh: all vertices, all unique edges, or all faces.
	 * The full set [selectAll] fills and [invert] complements against.  The vertex count comes from the
	 * topology because an isolated vertex (one on no face) is still a valid element that the faces alone
	 * would miss.
	 *
	 * @param MeshSelectMode mode The domain to enumerate.
	 * @param ElementTopology topology The topology whose domain to enumerate.
	 * @return Set<MeshElement> Every element of [mode]'s domain.
	 */
	private fun allElements(mode: MeshSelectMode, topology: ElementTopology): Set<MeshElement> =
		when (mode) {
			MeshSelectMode.Vertex -> (0 until topology.vertexCount).map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet<MeshElement>()
			MeshSelectMode.Edge -> topology.edges.toSet<MeshElement>()
			MeshSelectMode.Face -> (0 until topology.faceCount).map { faceIndex -> MeshElement.Face(faceIndex) }.toSet<MeshElement>()
		}

	/**
	 * Selects every element of the current select mode's domain on every session mesh (Blender's Select
	 * All).  The active element is kept (it is necessarily still selected); from an empty selection it
	 * stays null.  A session mesh the provider cannot resolve contributes nothing.
	 *
	 * @param MeshSelection selection The selection to fill.
	 * @param Function topologyOf Resolves a session owner's topology, or null when absent.
	 * @return MeshSelection The selection holding every element of its domain on every session mesh.
	 */
	fun selectAll(selection: MeshSelection, topologyOf: (ElementOwner) -> ElementTopology?): MeshSelection {
		val filled = HashMap<ElementOwner, Set<MeshElement>>()

		for (owner in selection.owners) {
			val topology = topologyOf(owner) ?: continue
			val all = allElements(selection.selectMode, topology)

			if (all.isNotEmpty()) {
				filled[owner] = all
			}
		}

		val active = selection.activeElement?.takeIf { active -> active.element in (filled[active.owner] ?: emptySet()) }

		return selection.copy(elementsByOwner = filled, activeElement = active)
	}

	/**
	 * Inverts the selection within the current select mode's domain on every session mesh (Blender's
	 * Ctrl+I): every element not currently selected becomes selected and vice versa.  The active element
	 * drops unless it happens to remain selected (in practice it drops - it was selected before).
	 *
	 * @param MeshSelection selection The selection to invert.
	 * @param Function topologyOf Resolves a session owner's topology, or null when absent.
	 * @return MeshSelection The inverted selection.
	 */
	fun invert(selection: MeshSelection, topologyOf: (ElementOwner) -> ElementTopology?): MeshSelection {
		val inverted = HashMap<ElementOwner, Set<MeshElement>>()

		for (owner in selection.owners) {
			val topology = topologyOf(owner) ?: continue
			val complement = allElements(selection.selectMode, topology) - selection.elementsOf(owner)

			if (complement.isNotEmpty()) {
				inverted[owner] = complement
			}
		}

		val active = selection.activeElement?.takeIf { active -> active.element in (inverted[active.owner] ?: emptySet()) }

		return selection.copy(elementsByOwner = inverted, activeElement = active)
	}

	/**
	 * Unions a connectivity island's elements into one mesh's selection, in the CURRENT select mode's
	 * domain - Blender's Select Linked (L): every vertex of the island in vertex mode, every edge with
	 * both endpoints in the island in edge mode, every face with all vertices in the island in face
	 * mode.  Additive by design (repeated L grows the selection island by island); the active element
	 * is left untouched (still a member - the union only adds).
	 *
	 * @param MeshSelection selection The selection to extend.
	 * @param ElementOwner owner The owner the island lives on.
	 * @param Set<Int> islandVertices The island's vertex indices (a [MeshTopology.connectedVertices] result).
	 * @param ElementTopology topology The owner's topology.
	 * @return MeshSelection The selection with the island's elements added.
	 */
	fun selectLinked(
		selection: MeshSelection,
		owner: ElementOwner,
		islandVertices: Set<Int>,
		topology: ElementTopology,
	): MeshSelection {
		val islandElements: Set<MeshElement> =
			when (selection.selectMode) {
				MeshSelectMode.Vertex -> islandVertices.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet()
				MeshSelectMode.Edge -> MeshTopology.edgesWithBothEndpointsSelected(topology, islandVertices)
				MeshSelectMode.Face -> MeshTopology.facesWithAllVerticesSelected(topology, islandVertices)
			}

		if (islandElements.isEmpty()) {
			return selection
		}

		return selection.copy(
			elementsByOwner = withElements(selection, owner, selection.elementsOf(owner) + islandElements),
		)
	}

	/**
	 * Reports whether every selected element still exists in its mesh: vertex indices within the vertex
	 * count, edges present in the mesh's edge set, and face ordinals within the face count - across
	 * every session mesh.  Used to decide whether a stashed selection may be restored (re-entering Edit
	 * mode) after the meshes may have changed underneath it.  A session mesh the provider cannot resolve
	 * fails the check.
	 *
	 * @param MeshSelection selection The selection to validate.
	 * @param Function topologyOf Resolves a session owner's topology, or null when absent.
	 * @return Boolean True when every element still fits its mesh.
	 */
	fun fitsWithinMeshes(selection: MeshSelection, topologyOf: (ElementOwner) -> ElementTopology?): Boolean {
		for ((owner, elements) in selection.elementsByOwner) {
			val topology = topologyOf(owner) ?: return false
			// Built lazily: vertex- and face-only selections never pay for the edge scan.
			val meshEdges by lazy { topology.edges.toSet() }

			for (element in elements) {
				val fits =
					when (element) {
						is MeshElement.Vertex -> element.index in 0 until topology.vertexCount
						is MeshElement.Edge -> element in meshEdges
						is MeshElement.Face -> topology.hasFace(element.index)
					}

				if (!fits) {
					return false
				}
			}
		}

		return true
	}
}