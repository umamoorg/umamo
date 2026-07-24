package org.umamo.runtime.model

/**
 * One axis of a keyform grid: the parameter it keys on and the key values along it (the parameter
 * values where keyforms exist, e.g. `[-1, 0, 1]`).
 *
 * キーフォーム格子の 1 軸：対応パラメータとキー値（キーフォームが存在するパラメータ値）。
 */
class KeyformAxis(
	val parameterId: ParameterId,
	val keys: FloatArray,
)

/**
 * A single keyform cell: its N-D [coordinate] (key index per axis, in [KeyformGrid.axes] order) and
 * the deformation [form] captured at that parameter combination.
 */
class KeyformCell<TForm>(
	val coordinate: IntArray,
	val form: TForm,
)

/**
 * The keyform grid for one controllable entity (drawable / deformer): the [axes] (one parameter each)
 * and the keyed [cells]. `cells.size == Π(axis key counts)` - the "interpolation" count the editor
 * reports. Cells are tagged with their coordinate rather than stored row-major, so the evaluator
 * can build whatever lookup the deformation math needs.
 *
 * キーフォーム格子：軸（各 1 パラメータ）とセル群。セル数は各軸キー数の積。
 */
class KeyformGrid<TForm>(
	val axes: List<KeyformAxis>,
	val cells: List<KeyformCell<TForm>>,
)

/**
 * A drawable keyform: per-vertex position deltas (interleaved x,y) relative to the mesh base
 * (`p = base + Σ wᵢ·Δᵢ`, stored as deltas to match the GPU vertex-shader morph), plus the animatable
 * scalars that ride on the same keyform. [drawOrder] (Cubism default 500) is the primary render-order
 * sort key; [opacity] (0..1) scales the drawable's alpha; [multiplyColor] / [screenColor] tint the
 * drawable per the Cubism per-art-mesh color (CMO3 `CArtMeshForm.multiplyColor`/`screenColor`, MOC3
 * color-table rows 108-113), left at their identities on pre-5.3 sources. All blend with the same
 * multilinear weights as the positions.
 */
class MeshForm(
	val positionDeltas: FloatArray,
	// Defaults are Cubism's own (drawOrder 500, fully opaque, identity tints); the CMO3 importer always
	// sets them explicitly, so the defaults only serve geometry-only unit tests that don't exercise these.
	val drawOrder: Float = 500f,
	val opacity: Float = 1f,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)

/**
 * A part keyform: the animatable per-cell channels of a part, riding the part's own keyform grid.
 * [drawOrder] positions a grouped part's stacking slot (Cubism `CPartForm.drawOrder`); the
 * remaining channels are the layer composite's keyformed state ([opacity], [multiplyColor],
 * [screenColor] - CMO3 `CPartForm.opacity`/`multiplyColor`/`screenColor`, MOC3 §5.6 section 161 +
 * the color-table offscreen prefix rows), meaningful only when the part's group mode is Isolated
 * and left at their identities otherwise.
 */
class PartForm(
	val drawOrder: Float,
	val opacity: Float = 1f,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)

/**
 * A warp-deformer keyform: the absolute FFD control-point positions (interleaved x,y). Warp
 * sources carry no separate rest lattice, so the forms are kept absolute; the evaluator interpolates
 * them directly.
 *
 * [opacity] / [multiplyColor] / [screenColor] are the deformer's own render channels, which CASCADE
 * down the deformer chain onto every drawable underneath (see `DeformerCascade`). Riggers use the
 * opacity as a parameter-driven subtree show/hide switch, so dropping it renders whole effect
 * subtrees permanently visible. (CMO3 `ACDeformerForm.opacity`/`multiplyColor`/`screenColor`;
 * MOC3 `WarpKeyform`.) A deformer has no draw order - that is a drawable/part concept.
 */
class WarpForm(
	val controlPoints: FloatArray,
	val opacity: Float = 1f,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)

/**
 * A rotation-deformer keyform: the absolute pivot transform captured at this grid cell, plus the
 * same cascading render channels a [WarpForm] carries.
 */
class RotationForm(
	val originX: Float,
	val originY: Float,
	val angle: Float,
	val scale: Float,
	/** Reflection flags (the Umamo C++ Runtime snaps these per grid cell into the affine's flipX/flipY). */
	val flipX: Boolean,
	val flipY: Boolean,
	val opacity: Float = 1f,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)
