package org.umamo.edit

/**
 * The active modal mesh operator: Blender-style Grab (translate), Scale, or Rotate. The session latches
 * one of these while a gesture is in flight (the desktop overlay drives the pointer tracking and the
 * corresponding [MeshTransforms] function); null means no operator is running.
 *
 * モーダルなメッシュ操作の種類（移動・拡縮・回転）。
 */
enum class MeshOperatorKind {
	Grab,
	Scale,
	Rotate,

	/**
	 * Slides the active vertex along one of its incident edges (Blender's Shift+V), clamped between the
	 * endpoints.  Edit mode only, driven by the Edit overlay's own slide branch (the pointer projects
	 * onto the edge rather than through the shared operator math).
	 */
	VertexSlide,
}