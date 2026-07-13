package org.umamo.format.moc3

import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocParameter
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.Glue
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Offscreen
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.RenderOrderGroup

/**
 * The fully decoded semantic model of a `.moc3` - the agnostic intermediate for editor
 * reconstruction and (later) the bake.
 *
 * EN: Objects reference each other and parameters by index (their position in these lists). Each
 *     deformable object names a [keyformBindingIndex]; look the binding up with [keyformBinding] to
 *     learn the controlling parameters and key positions for its keyforms. The per-frame
 *     interpolation/deformation math is intentionally not modelled - this is the stored data.
 *     Blend shapes (moc 5+) and offscreens (moc 6) are not yet assembled into objects; read their
 *     raw sections via [org.umamo.format.moc3.moc.MocModel.sections] when present.
 * JA: 復元用のセマンティックモデル。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5</a>
 */
public class MocDocument(
	public val version: MocVersion,
	public val canvas: CanvasInfo?,
	public val parameters: List<MocParameter>,
	private val keyformBindings: Map<Int, KeyformBinding>,
	public val parts: List<Part>,
	public val deformers: List<Deformer>,
	public val artMeshes: List<ArtMesh>,
	public val glues: List<Glue>,
	public val renderOrderGroups: List<RenderOrderGroup>,
	/** Blend-shape records (moc 4+); empty on older versions. */
	public val blendShapes: List<BlendShape> = emptyList(),
	/** Offscreen render targets (moc 6); empty on older versions. */
	public val offscreens: List<Offscreen> = emptyList(),
) {
	/** All resolved keyform bindings referenced by objects, ascending by index. */
	public val bindings: List<KeyformBinding> get() = keyformBindings.values.sortedBy { it.index }

	/**
	 * The keyform binding with the given index, or null if no object references it.
	 *
	 * @param Int index A `keyformBindingIndex` from an object.
	 * @return KeyformBinding? The resolved binding.
	 */
	public fun keyformBinding(index: Int): KeyformBinding? = keyformBindings[index]
}
