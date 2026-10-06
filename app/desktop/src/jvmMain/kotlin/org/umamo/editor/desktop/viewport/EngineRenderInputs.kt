package org.umamo.editor.desktop.viewport

import org.umamo.edit.GridConfig
import org.umamo.render.GridColors
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.ModelUpdateKind
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.differsOnlyInMeshPositions
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.ui.viewport.AtlasPageBinding
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The render inputs the UI thread pushes and the render thread reads each frame: the selection, the
 * shown set, the model, the atlas pages, the source artwork, the grid, the highlight colors, the mesh
 * overlay and its palette, and the supersample policy.  Each is a volatile publish of an immutable value or a plain scalar.  A change
 * bumps a render-version counter the loop folds into per-area freshness, so a state-only change (no
 * resize / pose / camera change) still forces exactly one redraw: the puppet areas watch
 * [puppetRenderBump]; the UV editor's flat scenes (atlas page, source layer) watch [atlasRenderBump],
 * which bumps separately so a puppet update does not needlessly re-render them.
 *
 * The UI thread owns the writes, with one exception: the render loop bumps the atlas counter itself
 * when it applies a page binding, and a UI-thread bump landing at that moment can collapse into it -
 * both causes are covered by the single re-render that follows either way, so the counters stay plain
 * increments on a volatile.
 *
 * These values mirror fields the renderer keeps for itself on purpose.  The renderer's are
 * render-thread-owned and set before each render; these carry the change detection and the bump
 * pairing that decide whether a render happens at all, and the loop hands them over at a safe moment.
 *
 * @param PuppetModel    puppet   The rig as opened: seeds the model, the shown set, and the atlas binding.
 * @param PuppetTextures textures The pages the renderer uploads at initGl, tagged into [initialAtlasBinding].
 */
internal class EngineRenderInputs(
	puppet: PuppetModel,
	textures: PuppetTextures,
) {
	// The grid backdrop colors, fed from the editor theme; default to the neutral grey grid until the host
	// pushes the themed colors.
	@Volatile
	private var gridColorsBacking: GridColors = GridColors.Classic

	// The per-document grid geometry (major spacing + subdivisions), fed from the session.
	@Volatile
	private var gridConfigBacking: GridConfig = GridConfig()

	// The currently selected drawables, read by the render thread to tint them.
	@Volatile
	private var selectionBacking: Set<DrawableId> = emptySet()

	// The active (last-selected) drawable, tinted apart from the rest of a multi-selection; null when none.
	@Volatile
	private var activeSelectionBacking: DrawableId? = null

	// The drawables actually drawn (the resolved Parts-panel visibility cascade). Seeded from the open
	// model's static cascade.
	@Volatile
	private var shownBacking: Set<DrawableId> = puppet.visibleDrawableIds()

	// Which artwork the puppet's drawables map onto, published whole.  EMPTY is the atlas, which is where
	// every document starts until a plan is prepared for it.  The pixels are NOT here: they arrive
	// through the queue below, in answer to what the renderer asks for.
	@Volatile
	private var layerPlanBacking: LayerDrawPlan = LayerDrawPlan.EMPTY

	// Decoded artwork waiting to be uploaded, drained on the render thread.  A queue rather than a
	// volatile slot because deliveries are chunked - two batches landing between frames must both be
	// taken up, where a slot would silently drop the first.
	private val pendingRasterBatches = ConcurrentLinkedQueue<LayerRasterBatch>()

	// The Edit-mode mesh overlay every puppet area draws over the art, or null for none.  Published whole and
	// compared by identity: the producer hands back the same instance while nothing it shows has changed.
	@Volatile
	private var meshOverlayBacking: MeshOverlay? = null

	// The nine overlay colors, from settings; Classic until the host pushes the user's.
	@Volatile
	private var meshOverlayPaletteBacking: MeshOverlayPalette = MeshOverlayPalette.Classic

	// The latest model, re-pushed on a structural edit (layer reorder / reparent, base-mesh move); seeded
	// with the open model.
	@Volatile
	private var modelBacking: PuppetModel = puppet

	/**
	 * The construction-time page pair: the pages initGl uploads, tagged with the atlas they render.
	 * Seeds both the published slot and the engine's applied state, so the loop's first tick applies
	 * nothing unless a binding was pushed before the thread started.
	 */
	val initialAtlasBinding = AtlasPageBinding(puppet.atlas, textures)

	// The latest page set, published whole with the atlas value it was composed for.  The loop applies
	// it and an atlas-changing model as one pair - see the pairing note in the engine's hand-off step
	// (OffscreenRenderEngine.applyHandoffs).
	@Volatile
	private var atlasBindingBacking: AtlasPageBinding = initialAtlasBinding

	/** The puppet render version: bumped by every input change a puppet area must repaint for. */
	@Volatile
	var puppetRenderBump: Long = 0
		private set

	/**
	 * The atlas render version: the UV editor's flat scenes (atlas page, source layer) bump separately
	 * from the puppet, so a puppet update does not needlessly re-render them.
	 */
	@Volatile
	var atlasRenderBump: Long = 0
		private set

	/** The red of the color selected drawables are tinted toward; 0..1, defaults to the classic blue accent. */
	@Volatile
	var highlightRed: Float = 0.20f
		private set

	/** The green of the selection tint; 0..1. */
	@Volatile
	var highlightGreen: Float = 0.55f
		private set

	/** The blue of the selection tint; 0..1. */
	@Volatile
	var highlightBlue: Float = 1.0f
		private set

	/** The red of the color the active drawable is tinted toward; 0..1, defaults to the edit-mode active green. */
	@Volatile
	var activeHighlightRed: Float = 0.49f
		private set

	/** The green of the active tint; 0..1. */
	@Volatile
	var activeHighlightGreen: Float = 0.89f
		private set

	/** The blue of the active tint; 0..1. */
	@Volatile
	var activeHighlightBlue: Float = 0.0f
		private set

	// The performance settings (viewport.rendering.*): whether settled frames supersample at all, and
	// whether frames rendered while a size is actively changing keep the supersample (false = drop to
	// 1x for a quarter of the fill cost during gutter drags and window resizes).
	@Volatile
	private var supersampleBacking: Boolean = true

	@Volatile
	private var supersampleWhileResizingBacking: Boolean = true

	/** The highlighted drawables (object-mode selection), as last pushed. */
	val selection: Set<DrawableId>
		get() = selectionBacking

	/** The active (last-selected) drawable, as last pushed, or null when none is active. */
	val activeSelection: DrawableId?
		get() = activeSelectionBacking

	/**
	 * The drawables actually drawn (the resolved visibility cascade), as last pushed - what a capture's
	 * framing measures.
	 */
	val shownDrawables: Set<DrawableId>
		get() = shownBacking

	/** Which artwork the puppet's drawables map onto, as last pushed; EMPTY displays from the atlas. */
	val sourceLayerPlan: LayerDrawPlan
		get() = layerPlanBacking

	/** The latest model pushed. */
	val model: PuppetModel
		get() = modelBacking

	/** The latest page binding published; the loop applies it paired with the model it was composed for. */
	val atlasBinding: AtlasPageBinding
		get() = atlasBindingBacking

	/** The mesh overlay as last pushed, or null when none is shown. */
	val meshOverlay: MeshOverlay?
		get() = meshOverlayBacking

	/** The mesh overlay's colors as last pushed. */
	val meshOverlayPalette: MeshOverlayPalette
		get() = meshOverlayPaletteBacking

	/**
	 * Takes the next decoded artwork batch waiting for upload, or null when none is.  The render thread
	 * drains these every frame rather than sampling one, so no chunked delivery is skipped.
	 *
	 * @return LayerRasterBatch? The oldest batch not yet taken up, or null.
	 */
	fun pollRasterBatch(): LayerRasterBatch? = pendingRasterBatches.poll()

	/** Drops every batch still waiting: the render thread is gone, so nothing will upload them. */
	fun clearRasterBatches() {
		pendingRasterBatches.clear()
	}

	/**
	 * The grid backdrop colors (background / major / minor). A change bumps both render passes so a
	 * color-only change repaints without waiting for an unrelated render.
	 */
	var gridColors: GridColors
		get() = gridColorsBacking
		set(value) {
			if (value != gridColorsBacking) {
				gridColorsBacking = value
				doPuppetRenderBump()
				doAtlasRenderBump()
			}
		}

	/**
	 * The per-document grid geometry (major spacing + subdivisions). Like the grid colors, a change bumps
	 * both render passes so a grid-only change repaints without waiting for an unrelated render.
	 */
	var gridConfig: GridConfig
		get() = gridConfigBacking
		set(value) {
			if (value != gridConfigBacking) {
				gridConfigBacking = value
				doPuppetRenderBump()
				doAtlasRenderBump()
			}
		}

	/**
	 * Sets the highlighted drawables (object-mode selection). A change bumps the puppet render version so the
	 * loop re-renders every area once with the new tint; an identical set is a no-op.
	 *
	 * @param Set<DrawableId> ids The selected drawable ids.
	 */
	fun setSelection(ids: Set<DrawableId>) {
		if (ids != selectionBacking) {
			selectionBacking = ids
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets the active (last-selected) drawable, tinted apart from the rest of a multi-selection. A change
	 * bumps the puppet render version; an identical value is a no-op.
	 *
	 * @param DrawableId id The active drawable id, or null when none is active.
	 */
	fun setActiveSelection(id: DrawableId?) {
		if (id != activeSelectionBacking) {
			activeSelectionBacking = id
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets which drawables are drawn (the resolved Parts-panel visibility cascade). A change bumps the puppet
	 * render version so every area re-renders once; the geometry is unchanged, so only the draw filter moves.
	 *
	 * @param Set<DrawableId> ids The drawable ids to draw.
	 */
	fun setShownDrawables(ids: Set<DrawableId>) {
		if (ids != shownBacking) {
			shownBacking = ids
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets which artwork the puppet's drawables map onto; an empty plan displays from the atlas.
	 *
	 * A volatile publish of one immutable value, like every other render input.  The render loop hands
	 * it to the renderer, which is where the GPU work happens - this must not touch the device.
	 *
	 * @param LayerDrawPlan plan Each drawable's mapping into the document's artwork.
	 */
	fun setSourceLayerPlan(plan: LayerDrawPlan) {
		if (plan !== layerPlanBacking) {
			layerPlanBacking = plan
			doPuppetRenderBump()
		}
	}

	/**
	 * Queues decoded artwork for upload on the render thread.
	 *
	 * @param LayerRasterBatch batch The decoded artwork.
	 */
	fun deliverSourceLayerRasters(batch: LayerRasterBatch) {
		pendingRasterBatches.add(batch)
		doPuppetRenderBump()
	}

	/**
	 * Publishes the atlas page set the current model's placements render from.  A pure volatile
	 * publish: the render loop bumps its own freshness when it APPLIES the binding, because applying
	 * can lag the publish by a tick while the matching model arrives - a bump here would let an area
	 * render back to freshness against the outgoing pair and never take the new one up.
	 *
	 * @param AtlasPageBinding binding The pages plus the atlas value they belong to.
	 */
	fun setAtlasPages(binding: AtlasPageBinding) {
		if (binding !== atlasBindingBacking) {
			atlasBindingBacking = binding
		}
	}

	/**
	 * Pushes the latest model so the render thread can reconcile it after an edit (a layer reorder
	 * re-derives the render order; a base-mesh move re-uploads the changed drawables' VBOs). A new (different)
	 * instance bumps the puppet render version so every area re-renders once.
	 *
	 * @param PuppetModel model The current model.
	 * @return ModelUpdateKind? How the model relates to the last one published, or null when it is the
	 *   same instance, so the caller rebuilds nothing.
	 */
	fun setModel(model: PuppetModel): ModelUpdateKind? {
		if (model === modelBacking) {
			return null
		}
		val kind =
			if (model.differsOnlyInMeshPositions(modelBacking)) {
				ModelUpdateKind.PositionsOnly
			} else {
				ModelUpdateKind.Structural
			}
		modelBacking = model
		doPuppetRenderBump()
		return kind
	}

	/**
	 * Sets the color selected drawables are tinted toward (the selection highlight). A change bumps the
	 * puppet render version; an identical color is a no-op.
	 *
	 * @param Float red The tint red, 0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue The tint blue, 0..1.
	 */
	fun setSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		if (red != highlightRed || green != highlightGreen || blue != highlightBlue) {
			highlightRed = red
			highlightGreen = green
			highlightBlue = blue
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets the color the active drawable is tinted toward (the active-selection highlight). A change bumps the
	 * puppet render version; an identical color is a no-op.
	 *
	 * @param Float red The tint red, 0..1.
	 * @param Float green The tint green, 0..1.
	 * @param Float blue The tint blue, 0..1.
	 */
	fun setActiveSelectionHighlightColor(red: Float, green: Float, blue: Float) {
		if (red != activeHighlightRed || green != activeHighlightGreen || blue != activeHighlightBlue) {
			activeHighlightRed = red
			activeHighlightGreen = green
			activeHighlightBlue = blue
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets the mesh overlay every puppet area draws.  A new instance bumps the puppet render version AFTER
	 * the value is stored, and the loop hands the value to the renderer only after it reads the version
	 * for a render, so a publish that lands mid-render always earns that area another one.  The same
	 * instance again is a no-op: the overlay holds no positions, so nothing a gesture's preview moves
	 * re-publishes it.
	 *
	 * @param MeshOverlay? overlay The overlay, or null for none.
	 */
	fun setMeshOverlay(overlay: MeshOverlay?) {
		if (overlay !== meshOverlayBacking) {
			meshOverlayBacking = overlay
			doPuppetRenderBump()
		}
	}

	/**
	 * Sets the mesh overlay's colors.  A change bumps both render versions, since the puppet areas and the
	 * UV areas draw their overlays in the same palette; an equal palette is a no-op, whichever instance
	 * carries it.
	 *
	 * @param MeshOverlayPalette palette The palette.
	 */
	fun setMeshOverlayPalette(palette: MeshOverlayPalette) {
		if (palette != meshOverlayPaletteBacking) {
			meshOverlayPaletteBacking = palette
			doPuppetRenderBump()
			doAtlasRenderBump()
		}
	}

	/**
	 * Whether settled frames render supersampled at all (viewport.rendering.supersample).  Off renders
	 * everything at 1x - the whole-session performance escape hatch for weak GPUs.  A change bumps
	 * both render passes so every area repaints at the new quality.
	 */
	var supersampleEnabled: Boolean
		get() = supersampleBacking
		set(value) {
			if (value != supersampleBacking) {
				supersampleBacking = value
				doPuppetRenderBump()
				doAtlasRenderBump()
			}
		}

	/**
	 * Whether frames rendered while an area's size is actively changing keep the supersample
	 * (viewport.rendering.supersampleWhileResizing).  False (the default) drops those frames to 1x;
	 * the settle render restores full quality within the settle window.  No bump on change - the next
	 * resize simply picks up the new policy.
	 */
	var supersampleWhileResizing: Boolean
		get() = supersampleWhileResizingBacking
		set(value) {
			supersampleWhileResizingBacking = value
		}

	/**
	 * Bump the render version for puppets to increase the frame by one.
	 */
	fun doPuppetRenderBump() {
		puppetRenderBump++
	}

	/**
	 * Bump the render version for atlases to increase the frame by one.
	 */
	fun doAtlasRenderBump() {
		atlasRenderBump++
	}
}