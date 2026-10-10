package org.umamo.geometry.triangulation

/** Which list a constraint came from. */
public enum class ConstraintKind {
	/** A closed boundary ring: constrained, and it decides inside from outside. */
	Boundary,

	/** An interior segment polyline: constrained, but neutral to inside and outside. */
	Segment,
}

/** Why a constraint edge did not make it into the triangulation. */
public enum class ConstraintRejectionReason {
	/** It crosses an edge an earlier constraint already holds; the earlier one wins. */
	CrossesConstraint,

	/** The points span no triangle (fewer than three distinct, or all collinear), so there is nothing to constrain. */
	NoTriangulation,
}

/**
 * One constraint edge the triangulation could not honor.  The rest of its polyline is still inserted;
 * a rejected BOUNDARY edge leaves its ring open, which is what
 * [Triangulation.classificationConsistent] reports.
 *
 * @property ConstraintKind            kind              The list the edge came from.
 * @property Int                       polylineIndex     The ring's or segment polyline's position in its list.
 * @property Int                       edgeIndex         The edge's position in its polyline: edge k runs from entry k to entry k + 1 (a ring's last edge wraps to entry 0).
 * @property Int                       startPoint        The edge's start, as the polyline gave it.
 * @property Int                       endPoint          The edge's end, as the polyline gave it.
 * @property ConstraintRejectionReason reason            Why it was rejected.
 * @property Int                       crossedStartPoint For [ConstraintRejectionReason.CrossesConstraint], one end of the constrained edge it crosses (a canonical index); otherwise -1.
 * @property Int                       crossedEndPoint   The crossed edge's other end, or -1.
 */
public data class RejectedConstraint(
	val kind: ConstraintKind,
	val polylineIndex: Int,
	val edgeIndex: Int,
	val startPoint: Int,
	val endPoint: Int,
	val reason: ConstraintRejectionReason,
	val crossedStartPoint: Int,
	val crossedEndPoint: Int,
)