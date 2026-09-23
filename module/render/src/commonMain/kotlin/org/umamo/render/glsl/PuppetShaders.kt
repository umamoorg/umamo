package org.umamo.render.glsl

// The puppet render programs' GLSL, shared by the GL-family backends.
//
// Each builder returns a READY-TO-COMPILE source: the preamble is already applied and there is no
// `trimIndent()` left for a caller to remember.  That is deliberate - the sources these replaced were
// trimmed inconsistently (inside the backdrop's `link`, at the call site for the fragment shader, and
// not at all for the concatenated vertex shaders), which is a trap with no upside.
//
// Metal shares none of this; see GlslDialect.

/**
 * The live-render vertex shader: deforms via [DEFORM_GLSL]'s `deformWorld`, then projects.
 *
 * The deform body is shared with the glue pass-1 capture shader and the GPU-vs-CPU validation test, so
 * all three exercise identical math rather than three hand-kept copies.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun puppetVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"layout(location = 0) in vec2 inBase;\n" + // rest-pose position (parent-deformer-local space)
		"layout(location = 1) in vec2 inUv;\n" + // atlas texture coordinate (CMO3 [0,1])
		"uniform vec4 worldToNdc;\n" + // (scaleX, scaleY, offsetX, offsetY) - the camera's world→NDC affine
		"out vec2 vUv;\n" +
		DEFORM_GLSL.trimIndent() + "\n" +
		"void main() {\n" +
		"	vUv = inUv;\n" +
		"	vec2 world = deformWorld(inBase);\n" +
		"	gl_Position = vec4(world.x * worldToNdc.x + worldToNdc.z, world.y * worldToNdc.y + worldToNdc.w, 0.0, 1.0);\n" +
		"}\n"

/**
 * The glue pass-2 vertex shader: welds against pass-1's shared positions, then projects.
 *
 * Positions are NOT deformed here - pass 1 already wrote them to the shared position buffer (world
 * space, post-Y-flip).  Each vertex reads its own deformed position and, when it is a glued seam vertex,
 * its partner's, and applies `own + (partner − own)·w·intensity` on the GPU, matching the CPU
 * `applyGluesResolved`.  Non-glued vertices pass through unchanged.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 * @warning The [GlslDialect.Es300] output is NOT compilable as-is: `samplerBuffer` is GLES 3.2, and the
 *   Android baseline is 3.0.  Making it portable means repacking the shared position buffer as a regular
 *   2D texture indexed `(i % width, i / width)` - see TODO.md § Android GLES renderer backend option (b).
 *   The parameter is accepted here so the seam exists, not because ES works today.
 */
internal fun glueVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"layout(location = 1) in vec2 inUv;\n" +
		"layout(location = 2) in int inPartnerIndex;\n" + // partner vertex's global index, or own when not glued
		"layout(location = 3) in int inGlueIndex;\n" + // which glue (for its per-pose intensity), or -1
		"layout(location = 4) in float inWeldWeight;\n" + // this vertex's weld weight (0 when not glued)
		"uniform vec4 worldToNdc;\n" +
		"uniform samplerBuffer positionBuffer;\n" + // RG = pass-1 deformed world positions, by global index
		"uniform int baseOffset;\n" + // this mesh's base index in positionBuffer
		"uniform float glueIntensity[$MAX_GLUES];\n" +
		"out vec2 vUv;\n" +
		"void main() {\n" +
		"	vUv = inUv;\n" +
		"	vec2 own = texelFetch(positionBuffer, baseOffset + gl_VertexID).rg;\n" +
		"	vec2 world = own;\n" +
		// Skip the partner read when the weld is a no-op: a zero per-pose intensity also flags an unposed
		// partner (set CPU-side), whose position-buffer region is uninitialised - so it is never read.
		"	if (inGlueIndex >= 0 && inWeldWeight != 0.0 && glueIntensity[inGlueIndex] != 0.0) {\n" +
		"		vec2 partner = texelFetch(positionBuffer, inPartnerIndex).rg;\n" +
		"		vec2 welded = own + (partner - own) * (inWeldWeight * glueIntensity[inGlueIndex]); if (welded.x == welded.x && welded.y == welded.y && distance(welded, own) < 100000.0) { world = welded; }\n" +
		"	}\n" +
		"	gl_Position = vec4(world.x * worldToNdc.x + worldToNdc.z, world.y * worldToNdc.y + worldToNdc.w, 0.0, 1.0);\n" +
		"}\n"

/**
 * The shared puppet fragment shader: samples the bound art texture through the stored-to-sampled uv
 * affine (or a flat color), applies the clip mask, tints the selection highlight, and writes
 * PREMULTIPLIED alpha (`rgb * alpha, alpha`) - which is why every blend mode's source factor is `GL_ONE`.
 *
 * A linear art texture is filtered in-shader, in premultiplied space: four `texelFetch` taps, each
 * weighted by its own coverage, then converted back to straight color for the tint math.  Art is stored
 * straight, and the hardware filter averages straight colors, so an alpha edge takes on whatever color
 * the neighboring transparent texels hold - black on every CMO3 layer image, and on any page composed
 * from them - as a dark, texel-stepped fringe.  Because the taps bypass the sampler, the shader applies
 * the texture's wrap itself, told by the `atlasTransparentBorder` flag; a nearest texture reads one
 * texel, where the two color spaces agree, so it keeps the hardware lookup.  A port must keep this
 * filter rather than trade it back for a plain `texture()` call.
 *
 * The mask is sampled by `gl_FragCoord.xy / screenTexSize` - screen space, with the divisor the mask
 * texture's ALLOCATED size (equal to the viewport size only when the texture is exactly viewport-sized;
 * side targets are grow-only, so it is usually the high-water capacity).  The coverage pass renders at
 * the same origin-anchored viewport, so fragment (x, y) reads coverage texel (x, y) exactly.  That is
 * self-consistent under either framebuffer origin convention (GL's bottom-left, Metal's top-left) and so
 * needs no per-dialect flip; it only holds while the mask pass and this pass share a convention, which a
 * port must keep true.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun puppetFragmentShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"""
		in vec2 vUv;
		out vec4 fragColor;
		uniform sampler2D atlas;
		uniform int useTexture;
		uniform vec4 drawColor;
		uniform float opacity;
		uniform int useMask;
		uniform sampler2D maskTexture;
		uniform vec2 screenTexSize;
		uniform int invertMask;
		uniform vec3 multiplyColor;
		uniform vec3 screenColor;
		uniform float highlight;
		uniform vec3 highlightColor;
		// The stored-to-sampled texture-coordinate affine, as two row vectors (m00 m01 m02 / m10 m11 m12).
		// Rows rather than a mat3 because the value arrives row-major and a GLSL mat3 is column-major -
		// passing rows keeps one convention end to end, and transliterates to MSL unchanged.  Identity
		// for a drawable sampling the atlas it was authored against.
		uniform vec3 uvAffineRow0;
		uniform vec3 uvAffineRow1;
		// How the bound art texture was created: 1 when it filters linearly (0 nearest), and 1 when it
		// wraps to a transparent border (0 to its edge texels).
		uniform int atlasLinear;
		uniform int atlasTransparentBorder;
		// One art texel, premultiplied.  A texel outside the image reads as the texture's wrap says.
		vec4 premultipliedTexel(ivec2 texel, ivec2 size) {
			if (atlasTransparentBorder == 1 && (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, size)))) {
				return vec4(0.0);
			}
			vec4 straight = texelFetch(atlas, clamp(texel, ivec2(0), size - ivec2(1)), 0);
			return vec4(straight.rgb * straight.a, straight.a);
		}
		// The art at a texture coordinate, as straight color.  Linear filtering blends the four surrounding
		// texels premultiplied, so a transparent texel's color never reaches the edge it borders.
		vec4 sampleArt(vec2 uv) {
			if (atlasLinear == 0) {
				return texture(atlas, uv);
			}
			ivec2 size = textureSize(atlas, 0);
			vec2 corner = uv * vec2(size) - 0.5;
			vec2 cornerFloor = floor(corner);
			vec2 fraction = corner - cornerFloor;
			ivec2 origin = ivec2(cornerFloor);
			vec4 firstRow = mix(premultipliedTexel(origin, size), premultipliedTexel(origin + ivec2(1, 0), size), fraction.x);
			vec4 secondRow = mix(premultipliedTexel(origin + ivec2(0, 1), size), premultipliedTexel(origin + ivec2(1, 1), size), fraction.x);
			vec4 premultiplied = mix(firstRow, secondRow, fraction.y);
			if (premultiplied.a <= 0.0) {
				return vec4(0.0);
			}
			return vec4(min(premultiplied.rgb / premultiplied.a, vec3(1.0)), premultiplied.a);
		}
		void main() {
			vec3 uvHomogeneous = vec3(vUv, 1.0);
			vec2 sampleUv = vec2(dot(uvAffineRow0, uvHomogeneous), dot(uvAffineRow1, uvHomogeneous));
			vec4 base = (useTexture == 1) ? sampleArt(sampleUv) : drawColor;
			float alpha = base.a * opacity;
			if (useMask == 1) {
				float coverage = texture(maskTexture, gl_FragCoord.xy / screenTexSize).a;
				alpha *= (invertMask == 1) ? (1.0 - coverage) : coverage;
			}
			vec3 tinted = base.rgb * multiplyColor;
			tinted = tinted + screenColor - tinted * screenColor;
			vec3 rgb = mix(tinted, highlightColor, highlight);
			fragColor = vec4(rgb * alpha, alpha);
		}
		""".trimIndent()

/**
 * The UV editor's underlay vertex shader, for an atlas page or a source-layer image.
 *
 * Emits the underlay rectangle's four corners from `gl_VertexID` (no vertex buffer, only an empty VAO),
 * directly in Y-up display / texel space - world X in [0, W], Y in [0, H] - with NO Cubism Y negation
 * (unlike `deformWorld`: the image is placed straight into the already-Y-up display space).  The UVs carry
 * the V-flip so the image's V=0 (its top texel row) lands at the top of the Y-up quad - corner (0, H) ->
 * uv (0, 0) - matching UvDisplayMapping's `displayY = (1-v)*H`.  Projects through the same worldToNdc
 * affine as the puppet.
 *
 * That V-flip is a CONTENT convention (Y-up display vs top-first image rows), not a backend one, so it is
 * identical in MSL - a Metal port must keep it, not "correct" it for Metal's top-left texture origin.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun atlasPageVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"uniform vec4 worldToNdc;\n" + // (scaleX, scaleY, offsetX, offsetY) - the camera's world→NDC affine
		"uniform vec2 pageSize;\n" + // the underlay image's size in texels (W, H)
		"out vec2 vUv;\n" +
		"void main() {\n" +
		"	float cornerX = float(gl_VertexID & 1);\n" + // 0,1,0,1 across the triangle strip
		"	float cornerY = float((gl_VertexID >> 1) & 1);\n" + // 0,0,1,1
		"	vec2 world = vec2(cornerX * pageSize.x, cornerY * pageSize.y);\n" +
		"	vUv = vec2(cornerX, 1.0 - cornerY);\n" + // V-flip: display top (Y=H) samples the image's top row (v=0)
		"	gl_Position = vec4(world.x * worldToNdc.x + worldToNdc.z, world.y * worldToNdc.y + worldToNdc.w, 0.0, 1.0);\n" +
		"}\n"