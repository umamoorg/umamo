package org.umamo.render.glsl

// The limits and binding points the shader sources and the backends must agree on.
//
// These are a CONTRACT, not tuning knobs: each is interpolated into the GLSL below and read by the
// backend that fills the matching uniform, so the two cannot be changed independently.  They live here,
// beside the sources that interpolate them, precisely so that stays true - [MAX_CORNERS] previously sat
// in the desktop renderer while the GLSL hardcoded a bare `16` in three places, which meant changing the
// constant silently disagreed with the shader.

/**
 * The most keyform-grid corners a single mesh's morph can blend at once.
 *
 * A hard ceiling, not a guess: it sizes the `cornerCell` / `cornerWeight` uniform arrays, so a mesh with
 * more active corners has the surplus dropped rather than blended.  16 corners is a 4-dimensional
 * keyform grid (2^4), which is past anything Cubism authors in practice.
 */
internal const val MAX_CORNERS = 16

/**
 * The most glue affecters one model can weld on the GPU.
 *
 * Sizes the `glueIntensity` uniform array.  A model's glue #65 and beyond render UNWELDED rather than
 * failing - the renderer drops them when resolving the pose.  A shader contract, so raising it means
 * raising the array here and re-checking the uniform budget, not just editing a number.
 */
internal const val MAX_GLUES = 64

/**
 * How strongly a selected drawable tints toward the selection accent (0 = untinted, 1 = fully the accent).
 * A subtle wash, so the art stays readable under the highlight.
 */
internal const val SELECTION_TINT_STRENGTH = 0.35f

// Texture units, shared across every program so the per-mesh deform uniforms can be set the same way
// regardless of which program is bound. atlas/mask are fragment-stage; delta/cp/position are
// vertex-stage (texture fetch).
internal const val UNIT_ATLAS = 0
internal const val UNIT_MASK = 1
internal const val UNIT_DELTA = 2
internal const val UNIT_CP = 3
internal const val UNIT_POSITION = 4
