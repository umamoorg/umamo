package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.triangulation.HalfEdgeTriangulation

/** Backstop operations allowed per vertex slot, for refinement and for the guide alike. */
private const val BACKSTOP_OPERATIONS_PER_VERTEX: Long = 64L

/** Backstop operations allowed regardless of size. */
private const val BACKSTOP_BASE_OPERATIONS: Long = 4096L

/**
 * Counts one run's operations against a limit only a defect reaches, so a run that would never end
 * stops instead.
 *
 * @param HalfEdgeTriangulation store The store, whose vertex capacity sets the limit.
 */
internal class OperationBackstop(store: HalfEdgeTriangulation) {
	private val limit = BACKSTOP_OPERATIONS_PER_VERTEX * store.vertexCapacity + BACKSTOP_BASE_OPERATIONS
	private var operations = 0L

	/** Whether the run went past the limit. */
	var tripped: Boolean = false
		private set

	/**
	 * Spends one operation.
	 *
	 * @return Boolean True while the run may go on; false once it is past the limit.
	 */
	fun spend(): Boolean {
		operations++

		if (operations > limit) {
			tripped = true

			return false
		}

		return true
	}
}