package org.umamo.edit

/**
 * The 2D cursor: a placeable world-space anchor, the 2D analog of Blender's 3D cursor.  Placed with
 * Shift+RightClick in the viewport, drawn by the HUD overlay, usable as a transform pivot
 * ([TransformPivotMode.Cursor]) and as the source / target of the Shift+S snap operations.  Session
 * state, transient by design: cursor moves are deliberately NOT undo steps (they ride outside
 * [EditorSnapshot]), and the snap menu makes recovering a lost placement cheap.
 *
 * 2D カーソル。Blender の 3D カーソルの 2D 版。ピボットやスナップの基準点になるワールド座標の
 * アンカー。取り消し履歴には乗らない一時状態。
 *
 * @property Float worldX The cursor's world-space x.
 * @property Float worldZ The cursor's world-space z (up).
 */
data class Cursor2d(val worldX: Float, val worldZ: Float)

/**
 * The UV editor's own 2D cursor: a placeable anchor in normalized atlas coordinates, the texture-space
 * sibling of [Cursor2d] (Blender's UV editor likewise carries its own cursor, separate from the 3D
 * one).  Placed with Shift+RightClick in the UV editor, drawn by its overlay, and read as the UV
 * transform pivot in [TransformPivotMode.Cursor].  Session state, transient by design like [Cursor2d]:
 * cursor moves are deliberately NOT undo steps.
 *
 * UV エディタ専用の 2D カーソル。正規化アトラス座標のアンカーで、UV 変形のカーソルピボットになる。
 * 取り消し履歴には乗らない一時状態。
 *
 * @property Float u The cursor's normalized atlas u coordinate.
 * @property Float v The cursor's normalized atlas v coordinate.
 */
data class UvCursor(val u: Float, val v: Float)

/**
 * What a modal Scale / Rotate turns the selection about (Blender's pivot point selector, the Period
 * pie).  MedianPoint is the covered vertices' centroid (the default); IndividualOrigins splits the
 * selection into connectivity islands (edit mode) or per drawable (object mode), each turning about
 * its own centroid; ActiveElement anchors on the active element (or active drawable); Cursor anchors
 * on the 2D cursor.
 *
 * 変形の基準点の種類（Blender のピボットポイント）。中点・各自の原点・アクティブ要素・2D カーソル。
 */
enum class TransformPivotMode {
	MedianPoint,
	IndividualOrigins,
	ActiveElement,
	Cursor,
}

/**
 * The axis a modal Grab / Scale is locked to, toggled by pressing X or Z during the gesture (Blender's
 * axis constraint; Rotate is excluded - there is only one 2D rotation axis).  Named by the DISPLAYED
 * axes: the project presents the 2D plane as X (horizontal) and Z (vertical, world +y) per the
 * Y+ forward, Z+ up convention, so AxisZ constrains the position arrays' y coordinates.
 *
 * モーダル変形の軸ロック（X / Z キー）。表示規約は Y+ 前、Z+ 上なので、AxisZ は配列の y 成分に対応する。
 */
enum class TransformAxisConstraint {
	AxisX,
	AxisZ,
}

/**
 * Which radial pie menu is open over the viewport, or none (null in the session flow).  The pie
 * entries dispatch through the command registry; the session only coordinates which pie is showing
 * (a transient latch like the modal operators).
 *
 * 表示中のパイメニューの種類。エントリはコマンドレジストリ経由で実行される。
 */
enum class PieMenuKind {
	PivotMode,
	Snap,
	UvSnap,
	MergeTarget,
}

/**
 * A latched modal transform operator together with the viewport area that initiated it.  The area id
 * is an opaque workspace-leaf id (the same currency as [SessionToolLatches.zoomRegionArmedArea]): the
 * session never interprets it, but the UI gates gesture capture, HUD drawing, and confirm delivery to
 * the initiating area, so a gesture latched in one split viewport can never be driven or committed
 * from another.  Both fields publish atomically in one flow emission - a paired flow could tear.
 *
 * ラッチされたモーダル変形操作と、それを開始したビューポートエリア。エリア ID は不透明な文字列で、
 * UI 側がジェスチャの捕捉・HUD 描画・確定の配送を開始エリアに限定するために使う。
 *
 * @property MeshOperatorKind kind The latched operator (Grab / Scale / Rotate / VertexSlide).
 * @property String areaId The initiating viewport's area id.
 */
data class ActiveOperator(
	val kind: MeshOperatorKind,
	val areaId: String,
)