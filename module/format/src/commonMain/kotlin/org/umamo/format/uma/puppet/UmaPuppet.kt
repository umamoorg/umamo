package org.umamo.format.uma.puppet

import kotlinx.serialization.Serializable

/*
 * The puppet entry's file-side shape (docs/format/UMA.md §4): `model/puppet.json` as it is written, one
 * class per object the schema names, properties in the order the file lists them.
 *
 * Every optional property is nullable with a null default, so an absent key and a null are the same
 * thing and neither is written.  What counts as a default is decided by the bridge that fills these
 * classes, not here: a value is left out only when it is bit-for-bit the model's own default, which a
 * default value on these properties could not express (an IEEE comparison takes -0.0 for 0.0).
 */

/**
 * The root of `model/puppet.json`: the document fields and the puppet's object lists.
 *
 * @property Float?                  canvasWidth             UMA §4.2: the canvas width in world units.
 * @property Float?                  canvasHeight            UMA §4.2: the canvas height in world units.
 * @property Float?                  worldOriginX            UMA §4.2: the world origin's x in world space.
 * @property Float?                  worldOriginY            UMA §4.2: the world origin's y in world space.
 * @property Float?                  pixelsPerUnit           UMA §4.2: the bake scale, absent when the document has none.
 * @property UmaRuntimeTarget?       runtimeTarget           UMA §4.2: the runtime-compatibility target.
 * @property Boolean?                rendersFromSourceLayers UMA §4.2: whether the puppet displays its source art.
 * @property List<UmaParameter>?     parameters              UMA §4.3: the parameter axes.
 * @property List<UmaParameterLink>? parameterLinks          UMA §4.3: the linked 2D parameter pairs.
 * @property List<UmaParameterNode>? parameterTree           UMA §4.3: the parameter panel's group tree.
 * @property String?                 rootPart                UMA §4.2: the organizational tree's root part id.
 * @property List<UmaOrgRef>?        rootChildren            UMA §4.2: the organizational tree's top level.
 * @property List<UmaPart>?          parts                   UMA §4.4: the parts.
 * @property List<UmaDeformer>?      deformers               UMA §4.5: the deformers.
 * @property List<UmaDrawable>?      drawables               UMA §4.6: the drawables.
 * @property List<UmaGlue>?          glues                   UMA §4.14: the glue affecters.
 */
@Serializable
public data class UmaPuppet(
	val canvasWidth: Float? = null,
	val canvasHeight: Float? = null,
	val worldOriginX: Float? = null,
	val worldOriginY: Float? = null,
	val pixelsPerUnit: Float? = null,
	val runtimeTarget: UmaRuntimeTarget? = null,
	val rendersFromSourceLayers: Boolean? = null,
	val parameters: List<UmaParameter>? = null,
	val parameterLinks: List<UmaParameterLink>? = null,
	val parameterTree: List<UmaParameterNode>? = null,
	val rootPart: String? = null,
	val rootChildren: List<UmaOrgRef>? = null,
	val parts: List<UmaPart>? = null,
	val deformers: List<UmaDeformer>? = null,
	val drawables: List<UmaDrawable>? = null,
	val glues: List<UmaGlue>? = null,
)

/**
 * UMA §4.3: one parameter axis.
 *
 * @property String             id      The format-level identifier, verbatim.
 * @property String             name    The display name.
 * @property Float              min     The lower limit.
 * @property Float              max     The upper limit.
 * @property Float              default The resting value.
 * @property UmaParameterKind?  kind    Absent for a normal parameter.
 * @property Boolean?           repeat  Absent when the axis clamps.
 */
@Serializable
public data class UmaParameter(
	val id: String,
	val name: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val kind: UmaParameterKind? = null,
	val repeat: Boolean? = null,
)

/**
 * UMA §4.3: two parameters presented as one 2D pad.
 *
 * @property String horizontal The X-axis parameter id.
 * @property String vertical   The Y-axis parameter id.
 */
@Serializable
public data class UmaParameterLink(
	val horizontal: String,
	val vertical: String,
)

/**
 * UMA §4.3: one node of the parameter panel's tree - a leaf naming a parameter, or a group.  Exactly one
 * of [parameter] and [group] is set; a leaf carries nothing else.
 *
 * @property String?                 parameter     A leaf's parameter id.
 * @property String?                 group         A group's id.
 * @property String?                 name          A group's display name (required for a group).
 * @property Boolean?                initiallyOpen A group's saved expanded state, absent when closed.
 * @property List<UmaParameterNode>? children      A group's children, absent when it has none.
 */
@Serializable
public data class UmaParameterNode(
	val parameter: String? = null,
	val group: String? = null,
	val name: String? = null,
	val initiallyOpen: Boolean? = null,
	val children: List<UmaParameterNode>? = null,
)

/**
 * UMA §4.2: one entry of an ordered organizational child list - a part or a drawable.  Exactly one of
 * the two is set.
 *
 * @property String? part     A sub-part's id.
 * @property String? drawable A drawable's id.
 */
@Serializable
public data class UmaOrgRef(
	val part: String? = null,
	val drawable: String? = null,
)

/**
 * UMA §4.4: one part of the organizational tree.
 *
 * @property String             id           The part id.
 * @property String             name         The display name.
 * @property List<UmaOrgRef>?   children     The ordered children, absent when none.
 * @property Boolean?           isVisible    Absent when visible.
 * @property Boolean?           isSketch     Absent when not a guide-image part.
 * @property Boolean?           isSelectable Absent when selectable.
 * @property UmaGroupMode?      groupMode    Absent for pass-through.
 * @property Int?               drawOrder    Absent at the neutral 500.
 * @property UmaPartComposite?  composite    Absent when every composite setting is at its default.
 * @property Map?               channels     UMA §4.12: the keyed channels, absent when none.
 * @property List?              blendShapes  UMA §4.13: the blend-shape bindings, absent when none.
 */
@Serializable
public data class UmaPart(
	val id: String,
	val name: String,
	val children: List<UmaOrgRef>? = null,
	val isVisible: Boolean? = null,
	val isSketch: Boolean? = null,
	val isSelectable: Boolean? = null,
	val groupMode: UmaGroupMode? = null,
	val drawOrder: Int? = null,
	val composite: UmaPartComposite? = null,
	val channels: Map<UmaFormChannel, UmaChannelGrid>? = null,
	val blendShapes: List<UmaPartBlendShape>? = null,
)

/**
 * UMA §4.4: a part's latent compositing settings.
 *
 * @property UmaBlendMode?      blendMode      Absent for normal.
 * @property UmaAlphaBlendMode? alphaBlendMode Absent for over.
 * @property List<String>?      maskedBy       The clipping drawables' ids, absent when none.
 * @property List<String>?      maskedByParts  The clipping parts' ids, absent when none.
 * @property Boolean?           invertMask     Absent when the clip is not inverted.
 * @property Float?             opacity        Absent at 1.
 * @property List<Float>?       multiplyColor  Red, green, blue; absent at white.
 * @property List<Float>?       screenColor    Red, green, blue; absent at black.
 */
@Serializable
public data class UmaPartComposite(
	val blendMode: UmaBlendMode? = null,
	val alphaBlendMode: UmaAlphaBlendMode? = null,
	val maskedBy: List<String>? = null,
	val maskedByParts: List<String>? = null,
	val invertMask: Boolean? = null,
	val opacity: Float? = null,
	val multiplyColor: List<Float>? = null,
	val screenColor: List<Float>? = null,
)

/**
 * UMA §4.5: one deformer.  [kind] decides which of the kind-specific properties are required and which
 * are not allowed: a warp carries [rows], [columns], and [isQuadTransform]; a rotation carries
 * [baseAngle] and may carry [flipX] and [flipY].
 *
 * @property String           id              The deformer id.
 * @property UmaDeformerKind  kind            Warp or rotation.
 * @property String           name            The display name.
 * @property String?          parent          The parent deformer's id, absent at the root.
 * @property String?          part            The owning part's id, absent at the root.
 * @property Boolean?         isVisible       Absent when visible.
 * @property Boolean?         isEnabled       Absent when enabled.
 * @property Boolean?         isSelectable    Absent when selectable.
 * @property Float?           opacity         Absent at 1.
 * @property List<Float>?     multiplyColor   Red, green, blue; absent at white.
 * @property List<Float>?     screenColor     Red, green, blue; absent at black.
 * @property Int?             rows            A warp's lattice rows.
 * @property Int?             columns         A warp's lattice columns.
 * @property Boolean?         isQuadTransform A warp's interpolation mode.
 * @property Float?           baseAngle       A rotation's reference angle.
 * @property Boolean?         flipX           A rotation's static horizontal reflection, absent when false.
 * @property Boolean?         flipY           A rotation's static vertical reflection, absent when false.
 * @property UmaDeformerGrid? geometry        UMA §4.11: the geometry keyform grid, absent when unkeyed.
 * @property Map?             channels        UMA §4.12: the keyed channels, absent when none.
 * @property List?            blendShapes     UMA §4.13: the blend-shape bindings, absent when none.
 */
@Serializable
public data class UmaDeformer(
	val id: String,
	val kind: UmaDeformerKind,
	val name: String,
	val parent: String? = null,
	val part: String? = null,
	val isVisible: Boolean? = null,
	val isEnabled: Boolean? = null,
	val isSelectable: Boolean? = null,
	val opacity: Float? = null,
	val multiplyColor: List<Float>? = null,
	val screenColor: List<Float>? = null,
	val rows: Int? = null,
	val columns: Int? = null,
	val isQuadTransform: Boolean? = null,
	val baseAngle: Float? = null,
	val flipX: Boolean? = null,
	val flipY: Boolean? = null,
	val geometry: UmaDeformerGrid? = null,
	val channels: Map<UmaFormChannel, UmaChannelGrid>? = null,
	val blendShapes: List<UmaDeformerBlendShape>? = null,
)

/**
 * UMA §4.6: one drawable.  Its mesh, keyforms, and blend shapes are the geometry half of the schema
 * (§4.10-§4.13).
 *
 * @property String             id             The drawable id.
 * @property String             name           The display name.
 * @property String?            parentDeformer The deforming parent's id, absent when undeformed.
 * @property UmaBlendMode?      blendMode      Absent for normal.
 * @property List<String>?      maskedBy       The clipping drawables' ids, absent when none.
 * @property Boolean?           invertMask     Absent when the clip is not inverted.
 * @property Float?             drawOrder      Absent at the neutral 500.
 * @property Float?             opacity        Absent at 1.
 * @property List<Float>?       multiplyColor  Red, green, blue; absent at white.
 * @property List<Float>?       screenColor    Red, green, blue; absent at black.
 * @property UmaAlphaBlendMode? alphaBlendMode Absent for over.
 * @property Boolean?           culling        Absent when double-sided.
 * @property Boolean?           isVisible      Absent when visible.
 * @property Boolean?           isSelectable   Absent when selectable.
 * @property String?            textureSource  The drawable whose texture binding this one shares, absent when its own.
 * @property Int?               texturePage    The page a MOC3-origin drawable samples, absent when none.
 * @property String?            atlasTile      The atlas tile its art is, a reference into the textures entry.
 * @property UmaMesh?           mesh           UMA §4.10: the rest-pose mesh, absent when none.
 * @property UmaMeshGrid?       geometry       UMA §4.11: the geometry keyform grid, absent when unkeyed.
 * @property Map?               channels       UMA §4.12: the keyed channels, absent when none.
 * @property List?              blendShapes    UMA §4.13: the blend-shape bindings, absent when none.
 */
@Serializable
public data class UmaDrawable(
	val id: String,
	val name: String,
	val parentDeformer: String? = null,
	val blendMode: UmaBlendMode? = null,
	val maskedBy: List<String>? = null,
	val invertMask: Boolean? = null,
	val drawOrder: Float? = null,
	val opacity: Float? = null,
	val multiplyColor: List<Float>? = null,
	val screenColor: List<Float>? = null,
	val alphaBlendMode: UmaAlphaBlendMode? = null,
	val culling: Boolean? = null,
	val isVisible: Boolean? = null,
	val isSelectable: Boolean? = null,
	val textureSource: String? = null,
	val texturePage: Int? = null,
	val atlasTile: String? = null,
	val mesh: UmaMesh? = null,
	val geometry: UmaMeshGrid? = null,
	val channels: Map<UmaFormChannel, UmaChannelGrid>? = null,
	val blendShapes: List<UmaMeshBlendShape>? = null,
)