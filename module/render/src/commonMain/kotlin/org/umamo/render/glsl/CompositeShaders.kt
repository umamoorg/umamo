package org.umamo.render.glsl

// The layer-composite program's GLSL, shared by the GL-family backends.
//
// The fragment shader is a line-for-line mirror of the pure reference in
// org.umamo.render.puppet.BlendMath (`compositeReference`) - the reference is the spec, the
// analytic GL tests compare read-back pixels against it, and any change must land in both.
// Formulas are the PUBLIC standards only (W3C Compositing and Blending Level 1, Porter-Duff,
// X Render conjoint/disjoint); nothing here is derived from the official Cubism SDK.

/**
 * The composite vertex shader: an attribute-less full-screen triangle from `gl_VertexID`
 * (draw 3 vertices with an empty VAO, like the grid backdrop).
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun compositeVertexShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"void main() {\n" +
		"	vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n" +
		"	gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);\n" +
		"}\n"

/**
 * The composite fragment shader: samples the rendered layer and the destination snapshot at the
 * fragment's screen position, applies the composite's channels (opacity, mask coverage,
 * multiply/screen colors) to the layer, then computes the color blend B(Cb, Cs) and the alpha
 * mode's Porter-Duff combination in-shader, writing premultiplied with blending DISABLED.
 *
 * The colorMode/alphaMode ints are the MOC3 packed halves (colorMode 0-17, alphaMode 0-4).  The legacy
 * "(Before 5.3)" Add/Multiply (colorMode 1/2) take the fixed-function premultiplied branch regardless of
 * the alpha mode (they ignore Alpha blend, per BlendMath.compositeReference); Normal (0) does so only
 * under Over.
 *
 * @param GlslDialect dialect The target flavor.
 * @return String The ready-to-compile source.
 */
internal fun compositeFragmentShader(dialect: GlslDialect): String =
	glslHeader(dialect) +
		"""
		out vec4 fragColor;
		uniform sampler2D layerTexture;
		uniform sampler2D destTexture;
		uniform sampler2D maskTexture;
		uniform vec2 viewportSize;
		uniform int colorMode;
		uniform int alphaMode;
		uniform float opacity;
		uniform vec3 multiplyColor;
		uniform vec3 screenColor;
		uniform int useMask;
		uniform int invertMask;

		float lum3(vec3 c) {
			return 0.3 * c.r + 0.59 * c.g + 0.11 * c.b;
		}

		vec3 clipColor3(vec3 c) {
			float l = lum3(c);
			float n = min(c.r, min(c.g, c.b));
			float x = max(c.r, max(c.g, c.b));
			if (n < 0.0) {
				c = l + (c - l) * l / (l - n);
			}
			if (x > 1.0) {
				c = l + (c - l) * (1.0 - l) / (x - l);
			}
			return c;
		}

		vec3 setLum3(vec3 c, float l) {
			return clipColor3(c + (l - lum3(c)));
		}

		float sat3(vec3 c) {
			return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
		}

		vec3 setSat3(vec3 c, float s) {
			float mn = min(c.r, min(c.g, c.b));
			float mx = max(c.r, max(c.g, c.b));
			return (mx > mn) ? (c - mn) * s / (mx - mn) : vec3(0.0);
		}

		float hardLightChannel(float base, float selector) {
			float doubled = 2.0 * selector - 1.0;
			return (selector <= 0.5) ? 2.0 * selector * base : base + doubled - base * doubled;
		}

		float softLightD(float x) {
			return (x <= 0.25) ? ((16.0 * x - 12.0) * x + 4.0) * x : sqrt(x);
		}

		float blendChannel(float b, float s) {
			if (colorMode == 1 || colorMode == 3 || colorMode == 4) {
				return min(1.0, b + s);
			}
			if (colorMode == 2 || colorMode == 6) {
				return b * s;
			}
			if (colorMode == 5) {
				return min(b, s);
			}
			if (colorMode == 7) {
				return (b >= 1.0) ? 1.0 : ((s <= 0.0) ? 0.0 : 1.0 - min(1.0, (1.0 - b) / s));
			}
			if (colorMode == 8) {
				return max(0.0, b + s - 1.0);
			}
			if (colorMode == 9) {
				return max(b, s);
			}
			if (colorMode == 10) {
				return b + s - b * s;
			}
			if (colorMode == 11) {
				return (b <= 0.0) ? 0.0 : ((s >= 1.0) ? 1.0 : min(1.0, b / (1.0 - s)));
			}
			if (colorMode == 12) {
				return hardLightChannel(s, b);
			}
			if (colorMode == 13) {
				return (s <= 0.5) ? b - (1.0 - 2.0 * s) * b * (1.0 - b) : b + (2.0 * s - 1.0) * (softLightD(b) - b);
			}
			if (colorMode == 14) {
				return hardLightChannel(b, s);
			}
			if (colorMode == 15) {
				return clamp(b + 2.0 * s - 1.0, 0.0, 1.0);
			}
			return s;
		}

		vec3 blendColor3(vec3 Cb, vec3 Cs) {
			if (colorMode == 16) {
				return setLum3(setSat3(Cs, sat3(Cb)), lum3(Cb));
			}
			if (colorMode == 17) {
				return setLum3(Cs, lum3(Cb));
			}
			return vec3(blendChannel(Cb.r, Cs.r), blendChannel(Cb.g, Cs.g), blendChannel(Cb.b, Cs.b));
		}

		void main() {
			vec2 screenUv = gl_FragCoord.xy / viewportSize;
			vec4 layer = texture(layerTexture, screenUv);
			vec4 dest = texture(destTexture, screenUv);
			float layerScale = opacity;
			if (useMask == 1) {
				float coverage = texture(maskTexture, screenUv).a;
				layerScale *= (invertMask == 1) ? (1.0 - coverage) : coverage;
			}
			layer *= layerScale;
			float as = layer.a;
			float ab = dest.a;
			vec3 Cs = (as > 0.0) ? layer.rgb / as : vec3(0.0);
			Cs = Cs * multiplyColor;
			Cs = Cs + screenColor - Cs * screenColor;
			vec3 Cb = (ab > 0.0) ? dest.rgb / ab : vec3(0.0);
			vec4 outColor;
			if (colorMode == 1 || colorMode == 2 || (alphaMode == 0 && colorMode == 0)) {
				vec3 srcPremul = Cs * as;
				if (colorMode == 0) {
					outColor = vec4(srcPremul + dest.rgb * (1.0 - as), as + ab * (1.0 - as));
				} else if (colorMode == 1) {
					outColor = vec4(srcPremul + dest.rgb, ab);
				} else {
					outColor = vec4(srcPremul * dest.rgb + dest.rgb * (1.0 - as), ab);
				}
			} else {
				vec3 mixed = (1.0 - ab) * Cs + ab * blendColor3(Cb, Cs);
				float Fa;
				float Fb;
				if (alphaMode == 1) {
					Fa = ab;
					Fb = 1.0 - as;
				} else if (alphaMode == 2) {
					Fa = 1.0 - ab;
					Fb = 0.0;
				} else if (alphaMode == 3) {
					Fa = 1.0;
					Fb = (ab <= 0.0 || as >= ab) ? 0.0 : 1.0 - as / ab;
				} else if (alphaMode == 4) {
					Fa = 1.0;
					Fb = (ab <= 0.0) ? 0.0 : min(1.0, (1.0 - as) / ab);
				} else {
					Fa = 1.0;
					Fb = 1.0 - as;
				}
				outColor = vec4(as * Fa * mixed + ab * Fb * Cb, as * Fa + ab * Fb);
			}
			fragColor = clamp(outColor, 0.0, 1.0);
		}
		""".trimIndent()
