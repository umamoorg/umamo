package org.umamo.ui.document

import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.initialLiveParams

/** The canvas a new document starts with, in canvas pixels, until artwork brings a size of its own. */
const val BLANK_DOCUMENT_CANVAS_SIZE = 1200f

/**
 * A rig that came from no file: what the editor opens into, and what File > New makes.
 *
 * Empty in every list, so there is nothing to render but the canvas - the rigger starts by bringing
 * artwork in, which lands through the ordinary import (an add onto the open document) and carries the
 * canvas and the parameter template with it.  A document that has never been written has no [path], and
 * nothing about it is special beyond that: the session, the viewport, and every panel treat it as the
 * puppet document it is.
 */
class BlankDocument(
	override val puppet: PuppetModel,
	override val textures: PuppetTextures,
	override val artRasters: SourceArtRasters,
	override val liveParams: LiveParams,
) : PuppetDocument {
	override val path: String? = null
}

/**
 * A new, empty document.
 *
 * The model is empty rather than seeded: a rigger who made a document by hand has made no parameters
 * yet, and the template belongs to the artwork import that follows (`import.parameterTemplate`, seeded
 * by the first art to land in a rig that has none).  The canvas is the one thing a blank rig cannot do
 * without - the viewport, the properties panel, and an export all read it - so it starts square at
 * [BLANK_DOCUMENT_CANVAS_SIZE] with the world origin at its center, the same centering every import
 * applies, and the first artwork replaces both with its own.
 *
 * @return BlankDocument The new document.
 */
fun newBlankDocument(): BlankDocument {
	val puppet =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = BLANK_DOCUMENT_CANVAS_SIZE,
			canvasHeight = BLANK_DOCUMENT_CANVAS_SIZE,
			// World space is canvas x with canvas y negated, so the canvas center is (w/2, -h/2) - the
			// expression SourceArtImport uses for an imported canvas.
			worldOriginX = BLANK_DOCUMENT_CANVAS_SIZE / 2f,
			worldOriginY = -(BLANK_DOCUMENT_CANVAS_SIZE / 2f),
		)
	return BlankDocument(
		puppet = puppet,
		// No pages, and the convention a pack at open writes (premultipliedAlpha = false), so the first
		// artwork to land composes its pages the way an artwork-opened document's were.
		textures = PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false),
		// Its OWN store, never the shared SourceArtRasters.EMPTY: added artwork decodes into the store it
		// is handed, so a shared one would carry this document's art into the next.
		artRasters = SourceArtRasters { null },
		liveParams = initialLiveParams(puppet),
	)
}