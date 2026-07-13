package org.umamo.runtime.model

/**
 * How a drawable composites against what's already drawn. Mirrors Cubism's drawable blend modes.
 * An `enum` (not sealed) because the set is fixed and each case is a plain value with no extra data.
 *
 * 描画オブジェクトの合成モード。Cubism のブレンドモードに対応。
 */
enum class BlendMode {
	Normal,
	Additive,
	Multiply,
}
