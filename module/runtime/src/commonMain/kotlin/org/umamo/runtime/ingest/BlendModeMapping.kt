package org.umamo.runtime.ingest

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode

/*
 * The blend-mode bijections shared by both importers, per the extraction tables in
 * docs/plans/offscreen-support.md: CMO3 serializes enum tokens (ColorComposition /
 * AlphaComposition), MOC3 packs ints (sections 153/157, colorMode or (alphaMode shl 8)).
 * The bare legacy tokens (NORMAL/ADD/MULTIPLY) are the pre-5.3 modes in every era; each
 * 5.3 mode has its own token and int.
 */

/**
 * Maps a CMO3 ColorComposition token to the runtime color blend mode.
 *
 * @param String? name The serialized enum constant name (e.g. "MULTIPLY_R2"), or null when absent.
 * @return BlendMode The runtime mode; Normal for null or an unknown token.
 */
internal fun colorBlendOfToken(name: String?): BlendMode =
	// CMO3: ColorComposition constants, matched by name (the generated enum is observed-only).
	when (name) {
		"NORMAL" -> BlendMode.Normal
		"ADD" -> BlendMode.Additive
		"MULTIPLY" -> BlendMode.Multiply
		"ADD_R2_TSL" -> BlendMode.AdditiveModern
		"ADD_R2" -> BlendMode.AdditiveGlow
		"DARKEN" -> BlendMode.Darken
		"MULTIPLY_R2" -> BlendMode.MultiplyModern
		"COLORBURN_TSL" -> BlendMode.ColorBurn
		"LINEARBURN_TSL" -> BlendMode.LinearBurn
		"LIGHTEN" -> BlendMode.Lighten
		"SCREEN" -> BlendMode.Screen
		"COLORDODGE_TSL" -> BlendMode.ColorDodge
		"OVERLAY" -> BlendMode.Overlay
		"SOFTLIGHT" -> BlendMode.SoftLight
		"HARDLIGHT" -> BlendMode.HardLight
		"LINEARLIGHT_TSL" -> BlendMode.LinearLight
		"HSL_HUE" -> BlendMode.Hue
		"HSL_COLOR" -> BlendMode.Color
		else -> BlendMode.Normal
	}

/**
 * Maps a CMO3 AlphaComposition token to the runtime alpha blend mode.
 *
 * @param String? name The serialized enum constant name (e.g. "DISJOINT"), or null when absent
 *                     (pre-5.3 sources carry no alpha mode).
 * @return AlphaBlendMode The runtime mode; Over for null or an unknown token.
 */
internal fun alphaBlendOfToken(name: String?): AlphaBlendMode =
	// CMO3: AlphaComposition constants, matched by name.
	when (name) {
		"OVER" -> AlphaBlendMode.Over
		"ATOP" -> AlphaBlendMode.Atop
		"OUT" -> AlphaBlendMode.Out
		"CONJOINT" -> AlphaBlendMode.Conjoint
		"DISJOINT" -> AlphaBlendMode.Disjoint
		else -> AlphaBlendMode.Over
	}

/**
 * Unpacks the color half of a MOC3 packed blend int (sections 153/157).
 *
 * @param Int packed The stored int, colorMode or (alphaMode shl 8).
 * @return BlendMode The runtime mode; Normal for an unknown colorMode.
 */
internal fun colorBlendOfPacked(packed: Int): BlendMode =
	// MOC3 v6 §5.6 s153/s157: colorMode 0-17 in editor-dropdown order (legacy first).
	when (packed and 0xFF) {
		0 -> BlendMode.Normal
		1 -> BlendMode.Additive
		2 -> BlendMode.Multiply
		3 -> BlendMode.AdditiveModern
		4 -> BlendMode.AdditiveGlow
		5 -> BlendMode.Darken
		6 -> BlendMode.MultiplyModern
		7 -> BlendMode.ColorBurn
		8 -> BlendMode.LinearBurn
		9 -> BlendMode.Lighten
		10 -> BlendMode.Screen
		11 -> BlendMode.ColorDodge
		12 -> BlendMode.Overlay
		13 -> BlendMode.SoftLight
		14 -> BlendMode.HardLight
		15 -> BlendMode.LinearLight
		16 -> BlendMode.Hue
		17 -> BlendMode.Color
		else -> BlendMode.Normal
	}

/**
 * Unpacks the alpha half of a MOC3 packed blend int (sections 153/157).
 *
 * @param Int packed The stored int, colorMode or (alphaMode shl 8).
 * @return AlphaBlendMode The runtime mode; Over for an unknown alphaMode.
 */
internal fun alphaBlendOfPacked(packed: Int): AlphaBlendMode =
	// MOC3 v6 §5.6 s153/s157: alphaMode 0-4 in editor-dropdown order.
	when ((packed shr 8) and 0xFF) {
		0 -> AlphaBlendMode.Over
		1 -> AlphaBlendMode.Atop
		2 -> AlphaBlendMode.Out
		3 -> AlphaBlendMode.Conjoint
		4 -> AlphaBlendMode.Disjoint
		else -> AlphaBlendMode.Over
	}
