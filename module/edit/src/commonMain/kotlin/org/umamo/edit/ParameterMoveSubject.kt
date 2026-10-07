package org.umamo.edit

import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId

/**
 * What a parameter-tree move relocates: a run of one or two adjacent parameter leaves (a slider is one
 * leaf, a linked 2D pad is two, moved contiguously so they stay adjacent for combined CMO3 export), or a
 * whole group.
 */
sealed interface ParameterMoveSubject {
	/** One or two adjacent parameter leaves, in order (a pad is horizontal then vertical). */
	data class Leaves(val ids: List<ParameterId>) : ParameterMoveSubject

	/** A whole group node, moved with its children intact. */
	data class Group(val id: ParameterGroupId) : ParameterMoveSubject
}