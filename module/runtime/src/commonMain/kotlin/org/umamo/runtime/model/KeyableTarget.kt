package org.umamo.runtime.model

/*
 * How a keyform channel is ADDRESSED: an entity plus one of its channels.
 *
 * In :runtime rather than :edit because it is vocabulary about the model, not about editing - the renderer
 * needs it too, to accept per-channel overrides for a value the user has typed but not yet keyed, and
 * :render depends on :runtime but not on :edit.
 *
 * キーフォームチャンネルのアドレス指定（オブジェクト＋チャンネル）。編集層と描画層の双方が使う。
 */

/**
 * The entity a keyform channel belongs to.
 *
 * Its own taxonomy rather than [SelectionTarget] because a glue is keyable but not selectable, and a glue
 * carries no id - it is addressed by the mesh pair it welds, which is stable across edits in a way a list
 * index is not.
 */
sealed interface KeyformOwner {
	/** A textured drawable mesh. */
	data class Drawable(val id: DrawableId) : KeyformOwner

	/** An organisational tree part. */
	data class Part(val id: PartId) : KeyformOwner

	/** A warp or rotation deformer. */
	data class Deformer(val id: DeformerId) : KeyformOwner

	/** A glue affecter, addressed by the pair of meshes it welds. */
	data class Glue(val meshA: DrawableId, val meshB: DrawableId) : KeyformOwner
}

/**
 * One keyable property: an entity and one of its channels.
 *
 * This is what a keyform insert aims at, and what the properties panel and the keyform sheet both resolve
 * a hover or a click into.
 *
 * @property KeyformOwner owner The entity.
 * @property FormChannel channel The channel on it.
 */
data class KeyableTarget(
	val owner: KeyformOwner,
	val channel: FormChannel,
)
