package org.umamo.render.glsl

/**
 * The coverage rule an overlay fragment shader applies: a flat fill, a band around a line, or a round
 * dot.  One fragment builder serves every overlay pipeline, specialised by this.
 */
internal enum class OverlayShape {
	Fill,
	Band,
	Round,
}

/*
 * The mesh overlay's shaders: the Edit-mode wireframe, dots, and face fills drawn over the art, and the
 * UV editor's islands.
 *
 * Every overlay draw is INSTANCED over a deformed-position store: one instance per primitive, with the
 * primitive's vertex indices (and its flag) as per-instance attributes, and the quad or triangle corner
 * taken from gl_VertexID.  No vertex buffer carries positions; the vertex stage fetches each endpoint from
 * the store by `baseOffset + localIndex`, exactly as the glue draw reads its own store, so in the 2D
 * viewport the overlay sits on the very positions the art was deformed to (a UV scene fills its store with
 * the shown positions directly).  Lines and dots are expanded in framebuffer pixels, so
 * their on-screen size is constant at any zoom (the grid line does the same), and their edges fade over
 * one framebuffer pixel so they stay anti-aliased when the frame renders at scale 1.
 *
 * The frame is premultiplied, so the fragment writes `vec4(rgb * alpha, alpha)` for PipelineBlend.Normal.
 */

/** The six vertices of one quad as (along, across) corners: two counter-clockwise triangles. */
private const val QUAD_CORNER_GLSL =
	"vec2 quadCorner(int cornerIndex) {\n" +
		"	if (cornerIndex == 0) { return vec2(0.0, -1.0); }\n" +
		"	if (cornerIndex == 1) { return vec2(1.0, -1.0); }\n" +
		"	if (cornerIndex == 2) { return vec2(0.0, 1.0); }\n" +
		"	if (cornerIndex == 3) { return vec2(0.0, 1.0); }\n" +
		"	if (cornerIndex == 4) { return vec2(1.0, -1.0); }\n" +
		"	return vec2(1.0, 1.0);\n" +
		"}\n"

/** A batched instance the vertex stage leaves to another draw: every corner lands behind the far plane. */
private const val CLIP_INSTANCE_GLSL =
	"		vFlag = 0;\n" +
		"		vLocal = vec2(0.0);\n" +
		"		gl_Position = vec4(0.0, 0.0, 2.0, 1.0);\n" +
		"		return;\n"

/**
 * The uniforms, varyings, and helpers every overlay vertex shader shares: the store read and the
 * screen-space round trip.
 *
 * @return String The GLSL prologue.
 */
private fun overlayVertexPrologue(): String =
	"uniform samplerBuffer positionBuffer;\n" + // RG = the deformed positions of this overlay's meshes, by store index
		"uniform vec4 worldToNdc;\n" +
		"uniform vec2 viewportSize;\n" + // the pass viewport in framebuffer pixels
		"uniform float sizePx;\n" + // the line half-width or the dot radius, in framebuffer pixels
		"uniform int baseOffset;\n" + // this mesh's first vertex in positionBuffer
		"uniform int activeDraw;\n" + // 1: draw the one primitive at activeIndices in the active color
		"uniform ivec3 activeIndices;\n" +
		"flat out int vFlag;\n" +
		"out vec2 vLocal;\n" + // the fragment's offset from the line or the dot center, in framebuffer pixels
		"vec2 toScreen(int localIndex) {\n" +
		"	vec2 world = texelFetch(positionBuffer, baseOffset + localIndex).rg;\n" +
		"	vec2 ndc = vec2(world.x * worldToNdc.x + worldToNdc.z, world.y * worldToNdc.y + worldToNdc.w);\n" +
		"	return (ndc * 0.5 + 0.5) * viewportSize;\n" +
		"}\n" +
		"vec4 fromScreen(vec2 px) {\n" +
		"	return vec4(px / viewportSize * 2.0 - 1.0, 0.0, 1.0);\n" +
		"}\n" +
		QUAD_CORNER_GLSL

/**
 * The face-fill vertex shader: one instance per triangle, its three corners as a per-instance ivec3,
 * each corner's position fetched from the store.  Outside Face mode only the selected faces fill, so
 * an idle instance is clipped when `fillIdle` is 0; the active face fills as selected, since the active
 * face color belongs to its dot alone.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 * @warning The [GlslDialect.Es300] output is NOT compilable as-is: `samplerBuffer` is GLES 3.2, so it
 *   needs the same 2D-texture store read as the glue draw - see [glueVertexShader]'s warning.
 */
internal fun overlayFaceFillVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"layout(location = 0) in ivec3 inCorners;\n" +
		"layout(location = 1) in int inFlag;\n" +
		"uniform int fillIdle;\n" +
		overlayVertexPrologue() +
		"void main() {\n" +
		"	if (fillIdle == 0 && inFlag == 0) {\n" +
		CLIP_INSTANCE_GLSL +
		"	}\n" +
		"	int localIndex = gl_VertexID == 0 ? inCorners.x : (gl_VertexID == 1 ? inCorners.y : inCorners.z);\n" +
		"	vFlag = inFlag == 2 ? 1 : inFlag;\n" +
		"	vLocal = vec2(0.0);\n" +
		"	gl_Position = fromScreen(toScreen(localIndex));\n" +
		"}\n"

/**
 * The edge vertex shader: one instance per edge, its two endpoints as a per-instance ivec2, expanded
 * to a six-vertex band of half-width `sizePx` plus one pixel of fade on each side, with butt caps.  A
 * batched instance flagged active is clipped: the active edge is drawn again by its own draw, with
 * `activeDraw` set, so it lands on top of every other edge.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 * @warning See [overlayFaceFillVertexShader] for the Es300 caveat.
 */
internal fun overlayEdgeVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"layout(location = 0) in ivec2 inEndpoints;\n" +
		"layout(location = 1) in int inFlag;\n" +
		overlayVertexPrologue() +
		"void main() {\n" +
		"	if (activeDraw == 0 && inFlag == 2) {\n" +
		CLIP_INSTANCE_GLSL +
		"	}\n" +
		"	ivec2 endpoints = activeDraw == 1 ? activeIndices.xy : inEndpoints;\n" +
		"	vec2 start = toScreen(endpoints.x);\n" +
		"	vec2 end = toScreen(endpoints.y);\n" +
		"	vec2 direction = end - start;\n" +
		"	float span = length(direction);\n" +
		"	vec2 normal = span > 0.0 ? vec2(-direction.y, direction.x) / span : vec2(0.0, 1.0);\n" +
		"	float reach = sizePx + 1.0;\n" +
		"	vec2 corner = quadCorner(gl_VertexID);\n" +
		"	vFlag = activeDraw == 1 ? 2 : inFlag;\n" +
		"	vLocal = vec2(0.0, corner.y * reach);\n" +
		"	gl_Position = fromScreen(mix(start, end, corner.x) + normal * (corner.y * reach));\n" +
		"}\n"

/**
 * The dot vertex shader: one instance per vertex (its store index is the instance index) or per
 * triangle (its centroid, from a per-instance ivec3 of corners), expanded to a six-vertex quad of
 * radius `sizePx` plus one pixel of fade.  A batched instance flagged active is clipped and drawn
 * again on top by its own draw, as the edges are.
 *
 * @param GlslDialect dialect The target flavor.
 * @param Boolean fromFaceCentroid True for the face-centroid dots, false for the vertex dots.
 * @return String The ready-to-compile source.
 * @warning See [overlayFaceFillVertexShader] for the Es300 caveat.
 */
internal fun overlayDotVertexShader(dialect: GlslDialect, fromFaceCentroid: Boolean): String =
	glslHeader(dialect) +
		(if (fromFaceCentroid) "layout(location = 0) in ivec3 inCorners;\n" else "") +
		"layout(location = 1) in int inFlag;\n" +
		overlayVertexPrologue() +
		"void main() {\n" +
		"	if (activeDraw == 0 && inFlag == 2) {\n" +
		CLIP_INSTANCE_GLSL +
		"	}\n" +
		(
			if (fromFaceCentroid) {
				"	ivec3 corners = activeDraw == 1 ? activeIndices : inCorners;\n" +
					"	vec2 center = (toScreen(corners.x) + toScreen(corners.y) + toScreen(corners.z)) / 3.0;\n"
			} else {
				"	int localIndex = activeDraw == 1 ? activeIndices.x : gl_InstanceID;\n" +
					"	vec2 center = toScreen(localIndex);\n"
			}
		) +
		"	float reach = sizePx + 1.0;\n" +
		"	vec2 corner = quadCorner(gl_VertexID);\n" +
		"	vec2 offset = vec2(corner.x * 2.0 - 1.0, corner.y) * reach;\n" +
		"	vFlag = activeDraw == 1 ? 2 : inFlag;\n" +
		"	vLocal = offset;\n" +
		"	gl_Position = fromScreen(center + offset);\n" +
		"}\n"

/**
 * The one overlay fragment shader: the flag picks the idle, selected, or active color, the shape
 * decides the coverage (a flat fill, since a soft edge on tiled triangles would seam; a band that fades
 * over the last framebuffer pixel beyond the half-width; a round dot that fades over the last pixel
 * beyond the radius), and the output is premultiplied for the Normal blend.
 *
 * @param GlslDialect dialect The target flavor.
 * @param OverlayShape shape The coverage rule.
 * @return String The ready-to-compile source.
 */
internal fun overlayFragmentShader(dialect: GlslDialect, shape: OverlayShape): String =
	glslHeader(dialect) +
		"flat in int vFlag;\n" +
		"in vec2 vLocal;\n" +
		"uniform float sizePx;\n" +
		"uniform vec4 idleColor;\n" +
		"uniform vec4 selectedColor;\n" +
		"uniform vec4 activeColor;\n" +
		"out vec4 fragColor;\n" +
		"void main() {\n" +
		"	vec4 color = vFlag == 0 ? idleColor : (vFlag == 1 ? selectedColor : activeColor);\n" +
		(
			when (shape) {
				OverlayShape.Fill -> "	float coverage = 1.0;\n"
				OverlayShape.Band -> "	float coverage = clamp(sizePx - abs(vLocal.y) + 0.5, 0.0, 1.0);\n"
				OverlayShape.Round -> "	float coverage = clamp(sizePx - length(vLocal) + 0.5, 0.0, 1.0);\n"
			}
		) +
		"	float alpha = color.a * coverage;\n" +
		"	fragColor = vec4(color.rgb * alpha, alpha);\n" +
		"}\n"