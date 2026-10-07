package org.umamo.render.puppet

/**
 * How a pushed model relates to the one a consumer holds - what every consumer of a model push acts
 * on: the renderer deciding whether its pose survives, the desktop viewport service deciding whether
 * its picker lookups do.
 */
enum class ModelUpdateKind {
	/**
	 * Only some drawables' mesh positions changed, so the consumer may keep everything of its own that
	 * reads no positions.  The renderer reports it only once it holds a pose: before one there is
	 * nothing to keep.
	 */
	PositionsOnly,

	/** Anything else changed, or nothing the consumer could keep exists yet: rebuild as for any edit. */
	Structural,
}