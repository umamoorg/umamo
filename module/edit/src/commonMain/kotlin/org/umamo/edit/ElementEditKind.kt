package org.umamo.edit

/**
 * What reshaped a set of points in one step, which names the step in the history: one of the modal operators, or To
 * Shape, the one-shot wrap of the selection onto a cylinder or a sphere.  The mesh, rest-lattice, and posed-lattice
 * changes all read it, so every way of moving points is labelled through one rule.
 */
enum class ElementEditKind {
	Grab,
	Scale,
	Rotate,
	VertexSlide,
	ToShape,
}

/** The edit kind a finished modal operator files its step under. */
val MeshOperatorKind.editKind: ElementEditKind
	get() =
		when (this) {
			MeshOperatorKind.Grab -> ElementEditKind.Grab
			MeshOperatorKind.Scale -> ElementEditKind.Scale
			MeshOperatorKind.Rotate -> ElementEditKind.Rotate
			MeshOperatorKind.VertexSlide -> ElementEditKind.VertexSlide
		}