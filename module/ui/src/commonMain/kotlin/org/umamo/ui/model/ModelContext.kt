package org.umamo.ui.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.viewport.PuppetViewportService

/*
 * The open document as the panels see it: the composition locals and handle interfaces the host
 * provides (this file), the session-backed selection and mode handles (SessionEditorState.kt), and the
 * resolver from the session's atlas to page pixels (SessionAtlasPages.kt).  This package root imports
 * none of its subpackages: repack and thumbnails build on it, and artwork builds on repack.
 */

/**
 * The open document's runtime [PuppetModel] for the composition, or null when nothing is open. Panels
 * (Outliner, Properties, Parameters) read `LocalPuppet.current` to display parts/parameters; the host
 * app provides it. Kept in `:ui` commonMain (PuppetModel is in `:runtime`, a shared dependency) so the
 * panels stay common and Android-sharable while only the GL viewport is platform code.
 *
 * A tracked local, unlike its neighbors: its value is a new model after every committed edit and every
 * undo, and a tracked local's change runs the scopes that READ it and no others.  So a commit reaches
 * each space that shows the model, and each space decides what of itself the commit changed: a row handed
 * what it was handed before skips.  Read it where the model is used, as low as that is; a composable that
 * reads it only to pass it down runs on every commit for nothing.  Asking whether a document is open at
 * all is [documentIsOpen]'s business.
 *
 * Compared by reference.  The session publishes a model only when it differs from the last, and two rigs
 * compared by value are walked whole.
 */
val LocalPuppet = compositionLocalOf<PuppetModel?>(referentialEqualityPolicy()) { null }

/**
 * The open document's [EditorSession] for the composition, or null when nothing is open. The session is
 * the single mutable owner of the document model, the editor state (selection, mode), and the undo
 * history; mutation sites (a visibility toggle, a future rename / reparent) resolve it and call its
 * mutation API, while [LocalPuppet] is the read-only model projection panels display. The host provides
 * both from the same session, so a mutation republishes the model, and whatever reads the model runs.
 *
 * One session for as long as the document is open, so this local changes when a document opens, closes,
 * or gives way to another, and at no edit.
 */
val LocalEditorSession = staticCompositionLocalOf<EditorSession?> { null }

/**
 * Whether a document is open.  What a control asks when all it needs of the document is that there is
 * one: a header control that shows only with a document open.
 *
 * Asked of the session, which is the document's for as long as it is open, and not of [LocalPuppet],
 * whose value is a new model after every edit: a control that read the model to ask this would run
 * again on each edit, to learn what it knew.  The host provides the two together, so they agree.
 *
 * @return Boolean True when a document is open.
 */
@Composable
@ReadOnlyComposable
internal fun documentIsOpen(): Boolean = LocalEditorSession.current != null

/**
 * A thin, platform-neutral handle for streaming transient preview models to the puppet renderer,
 * mirroring [LiveParamsHandle]'s preview / commit split for model-shaped edits: [previewModel] pushes
 * an uncommitted model straight to the render thread every pointer frame (no undo step, no session
 * write), and the gesture boundary commits through the session as usual - whose model bridge then
 * republishes the committed model.  [resync] restores the renderer to the session's committed model
 * after a cancelled or torn-down gesture.  The UV editor drives its modal G / S / R previews through
 * this; the viewport's own overlays reach the service directly and do not need it.
 *
 * The preview is published to the COMPOSITION as well as to the render thread, through [preview].  It
 * has to be: a gesture's own area draws from its local preview and the 2D viewport follows the pushes,
 * but every other read-only surface derives from [LocalPuppet] and would otherwise sit on the committed
 * model until the gesture confirmed - a second UV area showing the atlas page must move with the mesh
 * the viewport beside it is moving.
 */
interface PuppetRenderSync {
	/**
	 * The uncommitted model a gesture is currently previewing, or null when none is in flight.
	 *
	 * Compose state rather than a flow: it is written from the pointer loop on the main thread and read
	 * during composition, so a surface that reads it recomposes with the gesture and needs no collector.
	 * It is NOT session state - nothing here touches history, dirty, or the committed model - so a
	 * surface that wants the document as SAVED keeps reading [LocalPuppet].
	 */
	val preview: State<PuppetModel?>

	/**
	 * Pushes an uncommitted preview model to the renderer (transient; no undo step).
	 *
	 * @param PuppetModel model The preview model to render.
	 */
	fun previewModel(model: PuppetModel)

	/**
	 * Previews warps at pending lattices at the pose (transient; no undo step), each standing in for its warp's grid
	 * blend so the art follows - what a Pose-mode lattice edit outside the viewport shows while it is in flight.
	 * [resync] clears it.  A surface that cannot preview lattices ignores it.
	 *
	 * @param Map<DeformerId, WarpLatticeForm> forms Each previewed warp's lattice in its parent's space, before blend
	 *   shapes.
	 */
	fun previewLattices(forms: Map<DeformerId, WarpLatticeForm>) {}

	/** Restores the renderer to the session's committed model and lattices (a cancelled / torn-down gesture). */
	fun resync()
}

/**
 * The render-sync handle for the composition, or null when no puppet renderer is present (no document,
 * or a platform without the offscreen service).  Preview pushes no-op when it is null - the UV editor
 * still edits, just without a live GPU preview.
 */
val LocalPuppetRenderSync = staticCompositionLocalOf<PuppetRenderSync?> { null }

/**
 * The platform render service for the composition, or null when no puppet renderer is present (no
 * document, or a platform without the offscreen GL engine - Android until the GLES sibling lands).  The
 * UV editor requires it: it registers a UV scene - an atlas page or a source layer - to render its
 * underlay through the same engine the 2D viewport uses, and owns its camera through it; with no service
 * the UV editor shows the grid placeholder, exactly like the 2D viewport's body.
 */
val LocalPuppetViewportService = staticCompositionLocalOf<PuppetViewportService?> { null }

/**
 * The open document's decoded texture atlas pages for the composition, or null when nothing is open.
 * The UV editor reads the full page a drawable samples to draw under its wireframe; the thumbnail
 * provider below serves the cropped-preview case.  Provided by the host from the SESSION's effective
 * page set ([SessionAtlasPages]), which is what the renderer shows - so the value changes when a
 * repack (or its undo) swaps the pages, and the whole subtree recomposes onto the new set.  A repack
 * is rare, so the static local's wholesale recomposition is the right price for its simplicity.
 */
val LocalPuppetTextures = staticCompositionLocalOf<PuppetTextures?> { null }

/**
 * The session's atlas-page resolver, or null when nothing is open (or the desync guard built a
 * fallback session).  The repack command reads it to pre-warm the page cache with the pages it
 * composed; everything else reads the resolved pages through [LocalPuppetTextures].
 */
val LocalSessionAtlasPages = staticCompositionLocalOf<SessionAtlasPages?> { null }

/**
 * The open document's source artwork - the layers the puppet was authored against, before packing -
 * or null when nothing is open.  The UV editor's layer view shows one of these under a drawable's
 * mapping, which is the pre-atlas counterpart of what [LocalPuppetTextures] serves.
 *
 * A document whose format retains no source art (a MOC3, the packed endpoint) provides an EMPTY
 * store rather than null, so a surface distinguishes "no document" from "this document has no source
 * art" without a second signal.  Rasters decode on first request inside the store, so the value
 * handed here stays stable for the document's life - which matters, because a static local's change
 * recomposes the whole subtree.
 */
val LocalSourceArtRasters = staticCompositionLocalOf<SourceArtRasters?> { null }

/**
 * A platform-neutral source of small art-mesh previews, mirroring [SelectionHandle] / [LiveParamsHandle].
 * The Outliner and the Sources space ask for a drawable's thumbnail on hover; the host backs it with the same crop-and-downsample
 * machinery the viewport's overlap picker uses (the atlas region under the mesh UV bounds). Kept an interface
 * in `:ui` commonMain so the panels stay common - the desktop wraps its Skiko rasteriser, Android will wrap
 * its own. A null provider (or a null result) means no preview, so callers simply show nothing.
 */
interface DrawableThumbnailProvider {
	/**
	 * The cropped art preview for a drawable, or null when it is untextured, mesh-less, or unknown.
	 *
	 * @param DrawableId id The drawable to preview.
	 * @return ImageBitmap? The preview bitmap, or null when none is available.
	 */
	fun thumbnailFor(id: DrawableId): ImageBitmap?

	/**
	 * A combined preview of every art mesh under a part (its own drawables and those of its sub-parts),
	 * assembled in rest-pose model space so it reads like the part's art, or null when the part holds no
	 * textured drawable. Built by placing each drawable's cached crop at its model-space bounding box and
	 * compositing back-to-front, so it reuses the per-drawable previews rather than re-rendering geometry.
	 *
	 * @param PartId id The part to preview.
	 * @return ImageBitmap? The composited preview, or null when the part has no previewable art.
	 */
	fun partThumbnailFor(id: PartId): ImageBitmap?
}

/**
 * The drawable-thumbnail provider for the composition, or null when none is wired (e.g. no document open,
 * or a platform without the renderer). The Outliner and Sources hover previews no-op when it is null.
 */
val LocalDrawableThumbnails = staticCompositionLocalOf<DrawableThumbnailProvider?> { null }

/**
 * A thin, platform-neutral handle for reading and writing live parameter values (the pose) from common
 * UI (the Parameters sliders) without `:ui` knowing how the value reaches the GL render thread or how it
 * is recorded for undo. Scrubbing is a two-phase gesture: [preview] streams the in-progress value
 * straight to the renderer every frame (transient — no undo step, no recompose churn), and [commit]
 * records one undo step at the gesture boundary (drag release, a typed value, a reset). So a whole slider
 * drag is a single undo step. The desktop implementation writes its volatile LiveParams hand-off on
 * preview and routes commit through the EditorSession; Android will wrap its own.
 *
 * Both writes are refused while Edit mode pins the pose, so a control that scrubs needs no lock of its
 * own to be safe.  A control that also SHOWS the value it writes still has to know, or it would show a
 * value the write never took.
 */
interface LiveParamsHandle {
	/** The current parameter values (parameter id → value). */
	val values: Map<ParameterId, Float>

	/**
	 * The same values, read as Compose state so whatever reads them recomposes when the pose moves -
	 * including mid-scrub, where [values] is a plain volatile read that no observer ever sees change.
	 *
	 * Deliberately a second property rather than making [values] observable.  The scrub path writes on
	 * every pointer move, so a caller that only needs the pose once (seeding a control, constructing a
	 * session) must be able to read it WITHOUT taking a per-frame recomposition dependency on it.
	 */
	val observedValues: Map<ParameterId, Float>

	/**
	 * Previews one parameter's live value toward the renderer without recording an undo step. Called every
	 * frame of a scrub gesture; the matching [commit] records the single step on release.
	 *
	 * @param ParameterId id The parameter to set.
	 * @param Float value The new value.
	 */
	fun preview(id: ParameterId, value: Float)

	/**
	 * Records the current pose as one undo step, ending a scrub gesture. The values previewed since the
	 * gesture began are captured as a single step labelled by which parameters [changedIds] moved.
	 *
	 * @param Set<ParameterId> changedIds The parameters this gesture moved (for the history-panel label).
	 */
	fun commit(changedIds: Set<ParameterId>)
}

/**
 * The live-parameter handle for the composition, or null when no posable document is open. The
 * Parameters space writes slider changes here; the host wires it to the renderer.
 */
val LocalLiveParams = staticCompositionLocalOf<LiveParamsHandle?> { null }

/**
 * A thin, platform-neutral handle for reading and writing the current object-mode [Selection] from
 * common UI, mirroring [LiveParamsHandle]. The Outliner and viewport write gestures through it; the
 * Properties panel and the highlight bridge read it. The desktop implementation backs it with Compose state
 * so panels recompose on change; Android will wrap its own.
 */
interface SelectionHandle {
	/** The current selection. */
	val selection: Selection

	/**
	 * Replaces the current selection.
	 *
	 * @param Selection selection The new selection.
	 */
	fun set(selection: Selection)

	/**
	 * The item the one-item panels show (see [shownTargetOf]).  This default knows no item made active
	 * before the current selection; the session-backed handle remembers one across a deselect.
	 *
	 * @param PuppetModel model The open document's model.
	 * @return SelectionTarget? The item to show, or null when there is none.
	 */
	fun shownTarget(model: PuppetModel): SelectionTarget? = shownTargetOf(selection, null, model)
}

/**
 * The selection handle for the composition, or null when no document is open. Panels read
 * `LocalSelection.current?.selection`; gesture sites call `set`.
 */
val LocalSelection = staticCompositionLocalOf<SelectionHandle?> { null }

/**
 * A platform-neutral handle for reading and setting the current [EditorMode], mirroring
 * [SelectionHandle]. The mode-toggle command and, later, the radial menu write it; the viewport pick
 * router and the Properties panel read it.
 */
interface EditorModeHandle {
	/** The current editor mode. */
	val mode: EditorMode

	/**
	 * Sets the editor mode.
	 *
	 * @param EditorMode mode The new mode.
	 */
	fun set(mode: EditorMode)

	/**
	 * Toggles Edit mode: enters it, or leaves it for the mode it was entered from (Blender's Tab).
	 */
	fun toggleEdit()

	/**
	 * Toggles Pose mode: enters it, or leaves it for Object mode.
	 */
	fun togglePose()
}

/**
 * The editor-mode handle for the composition, or null when no document is open. A null handle is
 * treated as [EditorMode.Object] by callers.
 */
val LocalEditorMode = staticCompositionLocalOf<EditorModeHandle?> { null }