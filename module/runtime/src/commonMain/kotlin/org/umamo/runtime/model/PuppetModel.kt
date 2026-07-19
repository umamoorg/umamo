package org.umamo.runtime.model

/**
 * The whole rig as a concrete, in-memory puppet: what `:runtime` evaluates and the editor renders.
 * `:format` maps a parsed `.cmo3` (and later `.moc3`) into this; the deformation core depends only on
 * this model, never on the format's serialization quirks (that mapping lives in `runtime.ingest`).
 *
 * リグ全体の具象モデル。:runtime が評価し、:format がこれへ変換する。
 */
data class PuppetModel(
	val parameters: List<Parameter>,
	val parts: List<Part>,
	val deformers: List<Deformer>,
	val drawables: List<Drawable>,
	/**
	 * The organisational tree's top level: sub-parts and drawables interleaved in parts-panel order, the
	 * same ordered child list as [Part.children]. Single source of truth for top-level structure and
	 * order; [parts] / [drawables] are flat lookup lists. Draw order derives from this (deriveRenderRoot).
	 */
	val rootChildren: List<OrgChild>,
	/** The organisational tree root (the Parts panel's top), or null if the model is flat. */
	val rootPartId: PartId?,
	/** Glue affecters - seam-weld pairs of drawables' shared vertices after deformation. */
	val glues: List<Glue> = emptyList(),
	/**
	 * The draw-order group tree (Cubism's "Group by Draw Order" hierarchy) used to compute render order. A
	 * group-less model is one flat [RenderGroup]; empty (the default) means "fall back to the flat base order".
	 */
	val renderRoot: RenderGroup = RenderGroup(null, CUBISM_DEFAULT_PART_DRAW_ORDER, emptyList()),
	/**
	 * LINKED ("combined") parameter pairs - each renders as one 2D pad instead of two sliders. Empty
	 * (the default) means every parameter is an independent 1D axis. Both members of a link also remain
	 * in [parameters]; this list only records the X/Y pairing.
	 */
	val parameterLinks: List<ParameterLink> = emptyList(),
	/**
	 * The parameter-panel group tree (Cubism's CParameterGroup organisation): the root group's children
	 * in panel order, each a [ParameterNode.Param] or a (possibly nested) [ParameterNode.Group]. Empty
	 * (the default) means the model has no groups, so the panel renders the flat [parameters] list. This
	 * is layout only - every leaf id also appears in [parameters], which stays the authoritative axis list.
	 */
	val parameterTree: List<ParameterNode> = emptyList(),
	/**
	 * The document canvas size in world units (canvas px), or 0 when the source carried no canvas.
	 * Drawable rest positions live in canvas space, with the canvas rect spanning [0, canvasWidth] x
	 * [0, canvasHeight] (art may overhang it).
	 */
	val canvasWidth: Float = 0f,
	/** The document canvas height in world units; see [canvasWidth]. */
	val canvasHeight: Float = 0f,
	/**
	 * The world origin in WORLD space (the deform eval's output space, canvas x / negated canvas y) -
	 * where the viewport's axis lines cross and the anchor for origin-relative operations (the 2D
	 * cursor's snap home, frame grids).  Imported documents carry the authored origin (CMO3
	 * CModelInfo.originInPixels, the origin MOC3 CanvasInfo exports), falling back to the canvas
	 * center; (0, 0) when the source carried no canvas.
	 */
	val worldOriginX: Float = 0f,
	/** The world origin's y in world space (negated canvas y); see [worldOriginX]. */
	val worldOriginY: Float = 0f,
)

/** Cubism's neutral part draw order; matches the drawable default and the editor's Inspector default. */
const val CUBISM_DEFAULT_PART_DRAW_ORDER = 500

/**
 * One ordered child of the organisational tree: a sub-part or a drawable. Cubism's parts panel keeps
 * sub-parts and drawables in one interleaved sibling order per level (a loose mesh can sit between two
 * folders), and that order is also the draw-order tiebreak. This sealed type is how [Part.children] and
 * [PuppetModel.rootChildren] express that single order, instead of splitting parts and drawables apart.
 *
 * 組織ツリーの順序付き子要素：サブパートか描画オブジェクト。パーツとメッシュは1つの並びに交互に並ぶ。
 */
sealed interface OrgChild {
	/**
	 * A sub-part child.
	 *
	 * @property PartId id The sub-part.
	 */
	data class Part(val id: PartId) : OrgChild

	/**
	 * A drawable child.
	 *
	 * @property DrawableId id The drawable.
	 */
	data class Drawable(val id: DrawableId) : OrgChild
}

/**
 * A part's offscreen-drawing state (Cubism 5.3): the part's whole subtree renders into an offscreen
 * buffer and composites back into the scene as one layer. Null on [Part.offscreen] encodes the
 * checkbox off. The keyformed composite channels (opacity, multiply/screen colors) ride the part's
 * [Part.drawOrderGrid]; the static fallbacks here apply when that grid is absent, mirroring the
 * [Part.drawOrder] + grid pattern. (CMO3 `CPartSource.useOffscreen` and friends; MOC3 §5.6
 * sections 152-163.)
 *
 * パートのオフスクリーン描画状態（Cubism 5.3）。サブツリーを一枚のレイヤーとして合成する。
 */
data class PartOffscreen(
	/** The composite's color blend mode. (CMO3 colorComposition; MOC3 s157 colorMode.) */
	val blendMode: BlendMode = BlendMode.Normal,
	/** The composite's alpha blend mode. (CMO3 alphaComposition; MOC3 s157 alphaMode.) */
	val alphaBlendMode: AlphaBlendMode = AlphaBlendMode.Over,
	/**
	 * Drawables whose alpha clips the whole composite (Cubism clipping masks at the part level).
	 * Always drawables - a part chosen as Clipping ID in the editor is expanded to its constituent
	 * drawables at authoring time. (CMO3 clipGuidList; MOC3 offscreen mask suffix.)
	 */
	val maskedBy: List<DrawableId> = emptyList(),
	/** When true, the clip is inverted - the composite shows outside the [maskedBy] coverage. (CMO3 invertClippingMask; MOC3 offscreen flags bit 3.) */
	val invertMask: Boolean = false,
	/** Static composite opacity (0..1) when the part has no keyform grid. (CMO3 CPartForm.opacity; MOC3 s161.) */
	val opacity: Float = 1f,
	/** Static multiply color when the part has no keyform grid. (CMO3 CPartForm.multiplyColor.) */
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	/** Static screen color when the part has no keyform grid. (CMO3 CPartForm.screenColor.) */
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)

/** A node in the parts tree - the organisational hierarchy shown in the Parts panel. */
data class Part(
	val id: PartId,
	val name: String,
	/**
	 * This part's ordered children - sub-parts and drawables interleaved, in parts-panel (top = front)
	 * order. The single source of truth for the hierarchy AND the panel order at this level; draw order
	 * derives from it (see [PuppetModel.deriveRenderRoot]).
	 */
	val children: List<OrgChild>,
	/** The Parts-panel eyeball: when false, this part and everything under it are hidden (cascades). */
	val isVisible: Boolean = true,
	/** A "Guide Image" / sketch part - an editor reference overlay, excluded from runtime (MOC3) export. */
	val isSketch: Boolean = false,
	/**
	 * Blender-style selectable toggle: an unselectable part cannot be picked in the viewport.
	 * Maps inverted to CMO3's isLocked (Cubism lock = not selectable), so a future writer must
	 * emit isLocked = !isSelectable.
	 */
	val isSelectable: Boolean = true,
	/**
	 * Whether this is a Cubism "Group by Draw Order" part: a draw-order boundary whose whole subtree
	 * takes one slot in the global stacking and orders internally among itself. A non-group part is
	 * transparent to draw order - its drawables stack with the enclosing group's. (CMO3 enableDrawOrderGroup.)
	 */
	val isDrawOrderGroup: Boolean = false,
	/**
	 * The part's own draw order (0-1000, default 500) - the sort key for its slot when it is a draw-order
	 * group. (CMO3 CPartForm.drawOrder / defaultOrder_forEditor.)
	 */
	val drawOrder: Int = CUBISM_DEFAULT_PART_DRAW_ORDER,
	/**
	 * The parameter-driven keyform grid for a draw-order group part, or null for a static
	 * [drawOrder]. Lets a group's stacking slot animate per pose, like a drawable's own draw order;
	 * for an offscreen part the same grid also keys the composite's opacity/color channels.
	 */
	val drawOrderGrid: KeyformGrid<PartForm>? = null,
	/**
	 * The part's offscreen-drawing state, or null when the part is not offscreen. An offscreen part
	 * is always a draw-order group (the editor forces Grouped on - the subtree must occupy one slot
	 * in the global stacking to composite as a single layer).
	 */
	val offscreen: PartOffscreen? = null,
)

/**
 * A textured triangle mesh - the thing actually drawn. Two bindings place it: [partId] (organisational
 * tree) and [parentDeformerId] (the deformer chain that deforms it) - these are independent
 * hierarchies in Cubism.
 *
 * 描画される三角形メッシュ。所属パート(組織)と親デフォーマ(変形)は別系統。
 */
data class Drawable(
	val id: DrawableId,
	/** The user-facing display name (CMO3 localName), e.g. "ArtMesh1"; the id when unnamed. */
	val name: String,
	/** The innermost deformer that deforms this drawable, or null if it is undeformed. */
	val parentDeformerId: DeformerId?,
	val blendMode: BlendMode,
	/** Drawables whose alpha clips this one (Cubism clipping masks). */
	val maskedBy: List<DrawableId>,
	/** Rest-pose mesh, or null if the source carried no geometry. */
	val mesh: DrawableMesh?,
	/** Per-parameter morph deltas over the mesh, or null if the drawable is unkeyed. */
	val keyforms: KeyformGrid<MeshForm>?,
	/** When true, the clip is inverted - this drawable shows outside the [maskedBy] coverage. */
	val invertMask: Boolean = false,
	/**
	 * How the drawable's alpha combines with the destination (Cubism 5.3); pre-5.3 sources have no
	 * alpha mode and stay [AlphaBlendMode.Over]. (CMO3 alphaComposition; MOC3 s153 alphaMode.)
	 */
	val alphaBlendMode: AlphaBlendMode = AlphaBlendMode.Over,
	/**
	 * When true, back faces are culled - a mesh flipped inside-out by deformation disappears
	 * instead of showing mirrored. Default false = double-sided, Cubism's default. (CMO3 culling;
	 * MOC3 = the INVERSE of drawable constant-flags bit 2, IS_DOUBLE_SIDED.)
	 */
	val culling: Boolean = false,
	/** The drawable's own Parts-panel eyeball; the effective shown-state also folds in ancestor parts. */
	val isVisible: Boolean = true,
	/**
	 * Blender-style selectable toggle: an unselectable drawable cannot be picked in the viewport.
	 * Maps inverted to CMO3's isLocked (Cubism lock = not selectable), so a future writer must
	 * emit isLocked = !isSelectable.
	 */
	val isSelectable: Boolean = true,
	/**
	 * The drawable whose texture-atlas binding this one shares, or null when it binds by its own [id].
	 * The atlas page mapping is keyed by the SOURCE format's drawable ids (see PuppetTextures), so a
	 * session-created copy (Object-mode duplicate) carries its source's id here and the renderer
	 * resolves the atlas through it; a copy of a copy inherits the original.  Editor-only - no source
	 * format persists it.
	 */
	val textureSourceId: DrawableId? = null,
	/**
	 * Additive blend-shape bindings on this drawable's mesh, applied on top of the [keyforms] grid
	 * result; empty when the drawable has none. (CMO3 keyformMorphTargetSet.)
	 */
	val blendShapes: List<BlendShapeBinding<MeshForm>> = emptyList(),
)

/**
 * The drawables that are actually shown, after cascading the Parts-panel visibility toggles: a drawable is
 * shown only when its own [Drawable.isVisible] is set and every ancestor part up the tree is visible.
 * This is Cubism's editor-preview rule - hiding a folder hides everything inside it (e.g. a hidden
 * `[Guide Image]` sketch part hides its art) - and it is static (the eyeball is an authoring toggle, not
 * parameter-animated), so callers compute it once. It gates only what is drawn: a hidden drawable that is
 * still a mask source or a glue partner must keep being deformed, so apply those gates elsewhere.
 *
 * Parts パネルの表示トグルを親へ遡って合成し、実際に描画される drawable を返す（フォルダを隠すと中身も隠れる）。
 *
 * @return Set<DrawableId> The ids of the drawables to draw.
 */
fun PuppetModel.visibleDrawableIds(): Set<DrawableId> {
	val partById = parts.associateBy { it.id }
	val drawableById = drawables.associateBy { it.id }
	val shown = HashSet<DrawableId>()

	fun walk(children: List<OrgChild>, ancestorsVisible: Boolean) {
		for (child in children) {
			when (child) {
				is OrgChild.Drawable -> {
					val drawable = drawableById[child.id] ?: continue
					if (ancestorsVisible && drawable.isVisible) {
						shown.add(child.id)
					}
				}

				is OrgChild.Part -> {
					val part = partById[child.id] ?: continue
					walk(part.children, ancestorsVisible && part.isVisible)
				}
			}
		}
	}
	walk(rootChildren, ancestorsVisible = true)
	return shown
}

/**
 * The owning part of each drawable, derived from the org tree (the drawable's parent in [rootChildren] /
 * [Part.children]), or null for a root-level drawable. Since membership lives only in the tree, this is how
 * the few sites that need "which part owns this mesh" (the Inspector) read it - computed once per model.
 *
 * 各描画オブジェクトの所属パートを組織ツリーから導出する（ルート直下は null）。所属はツリーのみが保持する。
 *
 * @return Map<DrawableId, PartId?> Drawable id to its owning part id, or null at the root.
 */
fun PuppetModel.partByDrawable(): Map<DrawableId, PartId?> {
	val partById = parts.associateBy { it.id }
	val owner = HashMap<DrawableId, PartId?>()

	fun walk(children: List<OrgChild>, parent: PartId?) {
		for (child in children) {
			when (child) {
				is OrgChild.Drawable -> owner[child.id] = parent
				is OrgChild.Part -> partById[child.id]?.let { walk(it.children, child.id) }
			}
		}
	}
	walk(rootChildren, parent = null)
	return owner
}

/**
 * The first drawable in Parts-panel order (top = front) that can actually be edited: it carries a mesh
 * and is shown (its own [Drawable.isVisible] is set and every ancestor part is visible).  Edit mode seeds
 * onto this when nothing is selected or remembered, so a fresh document lands on the topmost editable mesh
 * instead of an inert Edit session.  A drawable without a mesh, or a hidden one, leaves the gizmo overlay
 * with nothing to draw, so both are skipped.  The scan is the same forward pre-order walk of the org tree
 * as [visibleDrawableIds], short-circuiting at the first match.  Only when the org tree carries no drawable
 * at all (a degenerate model with an empty [rootChildren]) does it fall back to a flat scan of [drawables];
 * a populated tree that yields no shown, meshed drawable returns null (nothing editable to seed onto).
 *
 * Parts パネル順（上＝前）で最初に編集可能な（メッシュを持ち表示中の）描画オブジェクト。編集モードの初期選択に使う。
 *
 * @return DrawableId? The topmost editable drawable, or null when the model has none.
 */
fun PuppetModel.firstEditableDrawableInPanelOrder(): DrawableId? {
	val partById = parts.associateBy { it.id }
	val drawableById = drawables.associateBy { it.id }

	fun walk(children: List<OrgChild>, ancestorsVisible: Boolean): DrawableId? {
		for (child in children) {
			when (child) {
				is OrgChild.Drawable -> {
					val drawable = drawableById[child.id] ?: continue
					if (ancestorsVisible && drawable.isVisible && drawable.mesh != null) {
						return child.id
					}
				}

				is OrgChild.Part -> {
					val part = partById[child.id] ?: continue
					val found = walk(part.children, ancestorsVisible && part.isVisible)
					if (found != null) {
						return found
					}
				}
			}
		}
		return null
	}

	val fromTree = walk(rootChildren, ancestorsVisible = true)
	if (fromTree != null) {
		return fromTree
	}
	// Degenerate model with no org tree: the visibility cascade has nothing to say, so fall back to the
	// flat list (a drawable's own eyeball still gates it).
	if (rootChildren.isEmpty()) {
		return drawables.firstOrNull { candidate -> candidate.mesh != null && candidate.isVisible }?.id
	}
	return null
}
