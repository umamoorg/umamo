package org.umamo.ui.model

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.PuppetViewportService

/**
 * The open document's runtime [PuppetModel] for the composition, or null when nothing is open. Panels
 * (Outliner, Inspector, Parameters) read `LocalPuppet.current` to display parts/parameters; the host
 * app provides it. Kept in `:ui` commonMain (PuppetModel is in `:runtime`, a shared dependency) so the
 * panels stay common and Android-sharable while only the GL viewport is platform code.
 *
 * 開いているドキュメントの PuppetModel。パネルがパーツ／パラメータ表示に読む。アプリが供給する。
 */
val LocalPuppet = staticCompositionLocalOf<PuppetModel?> { null }

/**
 * The open document's [EditorSession] for the composition, or null when nothing is open. The session is
 * the single mutable owner of the document model, the editor state (selection, mode), and the undo
 * history; mutation sites (a visibility toggle, a future rename / reparent) resolve it and call its
 * mutation API, while [LocalPuppet] is the read-only model projection panels display. The host provides
 * both from the same session, so a mutation republishes the model and every panel recomposes.
 *
 * 開いているドキュメントの EditorSession。編集操作はこれを解決して変更を適用する。
 */
val LocalEditorSession = staticCompositionLocalOf<EditorSession?> { null }

/**
 * A thin, platform-neutral handle for streaming transient preview models to the puppet renderer,
 * mirroring [LiveParamsHandle]'s preview / commit split for model-shaped edits: [previewModel] pushes
 * an uncommitted model straight to the render thread every pointer frame (no undo step, no session
 * write), and the gesture boundary commits through the session as usual - whose model bridge then
 * republishes the committed model.  [resync] restores the renderer to the session's committed model
 * after a cancelled or torn-down gesture.  The UV editor drives its modal G / S / R previews through
 * this; the viewport's own overlays reach the service directly and do not need it.
 *
 * 未確定のプレビューモデルをレンダラへ流すプラットフォーム非依存のハンドル。取り消し段は作らず、
 * 確定はセッション経由で行う。resync は確定済みモデルへ戻す。
 */
interface PuppetRenderSync {
	/**
	 * Pushes an uncommitted preview model to the renderer (transient; no undo step).
	 *
	 * @param PuppetModel model The preview model to render.
	 */
	fun previewModel(model: PuppetModel)

	/** Restores the renderer to the session's committed model (a cancelled / torn-down gesture). */
	fun resync()
}

/**
 * The render-sync handle for the composition, or null when no puppet renderer is present (no document,
 * or a platform without the offscreen service).  Preview pushes no-op when it is null - the UV editor
 * still edits, just without a live GPU preview.
 *
 * コンポジションのレンダ同期ハンドル。レンダラが無い場合は null（プレビューだけが無効になる）。
 */
val LocalPuppetRenderSync = staticCompositionLocalOf<PuppetRenderSync?> { null }

/**
 * The platform render service for the composition, or null when no puppet renderer is present (no
 * document, or a platform without the offscreen GL engine - Android until the GLES sibling lands).  The
 * UV editor requires it: it registers an atlas-page area to render its underlay through the same engine
 * the 2D viewport uses, and owns its camera through it; with no service the UV editor shows the grid
 * placeholder, exactly like the 2D viewport's body.
 *
 * コンポジションのレンダサービス。UV エディタはこれ経由でアトラスページを描画しカメラを保持する。
 */
val LocalPuppetViewportService = staticCompositionLocalOf<PuppetViewportService?> { null }

/**
 * The open document's decoded texture atlas pages for the composition, or null when nothing is open.
 * The UV editor reads the full page a drawable samples to draw under its wireframe; the thumbnail
 * provider below serves the cropped-preview case.  Provided by the host from the same extraction the
 * renderer uploads, so the two always show the same texels.
 *
 * 開いているドキュメントのデコード済みアトラスページ。UV エディタがワイヤーフレームの下に描く。
 */
val LocalPuppetTextures = staticCompositionLocalOf<PuppetTextures?> { null }

/**
 * A platform-neutral source of small art-mesh previews, mirroring [SelectionHandle] / [LiveParamsHandle].
 * The Outliner asks for a drawable's thumbnail on hover; the host backs it with the same crop-and-downsample
 * machinery the viewport's overlap picker uses (the atlas region under the mesh UV bounds). Kept an interface
 * in `:ui` commonMain so the panels stay common - the desktop wraps its Skiko rasteriser, Android will wrap
 * its own. A null provider (or a null result) means no preview, so callers simply show nothing.
 *
 * アートメッシュのサムネイルを供給するプラットフォーム非依存のソース。アウトライナーがホバー時に要求する。
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
 * or a platform without the renderer). The Outliner hover preview no-ops when it is null.
 *
 * コンポジションのサムネイル供給元。未配線時は null（プレビュー無し）。
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
 * ライブパラメータ値（ポーズ）を読み書きするプラットフォーム非依存のハンドル。preview は毎フレーム描画へ
 * 流し（取り消し段にしない）、commit はジェスチャ境界で1つの取り消し段を記録する。
 */
interface LiveParamsHandle {
	/** The current parameter values (parameter id → value). */
	val values: Map<ParameterId, Float>

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
 *
 * コンポジションのライブパラメータハンドル。Parameters 空間がスライダー変更を書き込む。
 */
val LocalLiveParams = staticCompositionLocalOf<LiveParamsHandle?> { null }

/**
 * A thin, platform-neutral handle for reading and writing the current object-mode [Selection] from
 * common UI, mirroring [LiveParamsHandle]. The Outliner and viewport write gestures through it; the
 * Inspector and the highlight bridge read it. The desktop implementation backs it with Compose state
 * so panels recompose on change; Android will wrap its own.
 *
 * オブジェクトモード選択を読み書きするプラットフォーム非依存のハンドル。
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
}

/**
 * The selection handle for the composition, or null when no document is open. Panels read
 * `LocalSelection.current?.selection`; gesture sites call `set`.
 *
 * コンポジションの選択ハンドル。ドキュメント未オープン時は null。
 */
val LocalSelection = staticCompositionLocalOf<SelectionHandle?> { null }

/**
 * A platform-neutral handle for reading and setting the current [EditorMode], mirroring
 * [SelectionHandle]. The mode-toggle command and, later, the radial menu write it; the viewport pick
 * router and the Inspector read it.
 *
 * 現在の [EditorMode] を読み書きするプラットフォーム非依存のハンドル。
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
}

/**
 * The editor-mode handle for the composition, or null when no document is open. A null handle is
 * treated as [EditorMode.Object] by callers.
 *
 * コンポジションのモードハンドル。未オープン時は null（オブジェクトモード扱い）。
 */
val LocalEditorMode = staticCompositionLocalOf<EditorModeHandle?> { null }
