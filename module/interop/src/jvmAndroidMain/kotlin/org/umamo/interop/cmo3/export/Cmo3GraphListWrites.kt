package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.cmo3.mutableGraphListOf

/*
 * Writing a list field of the graph an export reconciles onto.  A list the document carries is
 * changed in place, so its recorded slot replays and an unchanged list re-emits as it was read.  A
 * list the document omitted is created, stored on its owner, and given its slot, because the writer
 * drops an assignment it has no slot for.
 */

/**
 * The class a created graph list is, which decides the tag the writer gives it.
 *
 * The official editor requires the exact tag per field: its deserializers cast a carray_list field
 * to CArrayList, so the wrong tag is a ClassCastException on load.  Every corpus model agrees on the
 * tag per field, so each call names the kind the editor writes there.
 */
internal enum class GraphListKind {
	/** A plain ArrayList, written as array_list. */
	ArrayList,

	/** A CArrayList, written as carray_list. */
	CArrayList,
}

/**
 * Replaces the elements of a list field.
 *
 * @param Any           owner          The graph object owning the field.
 * @param String        tag            The owner's serial tag, for the slot record.
 * @param String        property       The field's name.
 * @param String?       beforeProperty The sibling field a created list's slot precedes, or null for last.
 * @param Any?          current        The field's current value.
 * @param List          newElements    The elements to write.
 * @param GraphListKind kind           The class of the list when it has to be created.
 * @param Function      assign         Stores a created list on the owner.
 */
internal fun Cmo3GraphEditor.writeGraphList(
	owner: Any,
	tag: String,
	property: String,
	beforeProperty: String?,
	current: Any?,
	newElements: List<Any?>,
	kind: GraphListKind,
	assign: (MutableList<Any?>) -> Unit,
) {
	val mutable = mutableGraphListOf(current)

	if (mutable != null) {
		mutable.clear()
		mutable.addAll(newElements)

		return
	}

	createGraphList(owner, tag, property, beforeProperty, newElements, kind, assign)
}

/**
 * Appends one element to a list field.
 *
 * @param Any           owner    The graph object owning the field.
 * @param String        tag      The owner's serial tag, for the slot record.
 * @param String        property The field's name.
 * @param Any?          current  The field's current value.
 * @param Any           element  The element to append.
 * @param GraphListKind kind     The class of the list when it has to be created.
 * @param Function      assign   Stores a created list on the owner.
 */
internal fun Cmo3GraphEditor.appendToGraphList(
	owner: Any,
	tag: String,
	property: String,
	current: Any?,
	element: Any,
	kind: GraphListKind,
	assign: (MutableList<Any?>) -> Unit,
) {
	val mutable = mutableGraphListOf(current)

	if (mutable != null) {
		mutable.add(element)

		return
	}

	createGraphList(owner, tag, property, null, listOf(element), kind, assign)
}

/**
 * A list field as the list to mutate, created empty when the document omitted it.
 *
 * @param Any           owner    The graph object owning the field.
 * @param String        tag      The owner's serial tag, for the slot record.
 * @param String        property The field's name.
 * @param Any?          current  The field's current value.
 * @param GraphListKind kind     The class of the list when it has to be created.
 * @param Function      assign   Stores a created list on the owner.
 * @return MutableList The field's list.
 */
internal fun Cmo3GraphEditor.graphListFor(
	owner: Any,
	tag: String,
	property: String,
	current: Any?,
	kind: GraphListKind,
	assign: (MutableList<Any?>) -> Unit,
): MutableList<Any?> = mutableGraphListOf(current) ?: createGraphList(owner, tag, property, null, emptyList(), kind, assign)

/**
 * Creates a list field the document omitted: the list is filled, stored on its owner, and then given
 * its slot, in that order.
 *
 * @param Any           owner          The graph object owning the field.
 * @param String        tag            The owner's serial tag, for the slot record.
 * @param String        property       The field's name.
 * @param String?       beforeProperty The sibling field the slot precedes, or null for last.
 * @param List          elements       The list's first elements.
 * @param GraphListKind kind           The list's class.
 * @param Function      assign         Stores the list on the owner.
 * @return MutableList The created list.
 */
private fun Cmo3GraphEditor.createGraphList(
	owner: Any,
	tag: String,
	property: String,
	beforeProperty: String?,
	elements: List<Any?>,
	kind: GraphListKind,
	assign: (MutableList<Any?>) -> Unit,
): MutableList<Any?> {
	val fresh: MutableList<Any?> =
		when (kind) {
			GraphListKind.ArrayList -> ArrayList()
			GraphListKind.CArrayList -> CArrayList()
		}
	fresh.addAll(elements)
	assign(fresh)
	ensureChildSlot(owner, tag, property, beforeProperty)

	return fresh
}