package org.umamo.interop.uma

import org.umamo.format.uma.puppet.UmaAlphaBlendMode
import org.umamo.format.uma.puppet.UmaBlendMode
import org.umamo.format.uma.puppet.UmaFormChannel
import org.umamo.format.uma.puppet.UmaGroupMode
import org.umamo.format.uma.puppet.UmaParameterKind
import org.umamo.format.uma.puppet.UmaRuntimeTarget
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.RuntimeTarget

/*
 * The runtime model's enums against the puppet entry's (docs/format/UMA.md §4.1).  Every mapping is an
 * exhaustive `when` in both directions, so a constant added on either side is a compile error here rather
 * than a value that silently fails to round-trip.
 */

/**
 * The file-side spelling of a blend mode.
 *
 * @return UmaBlendMode The file's value.
 */
internal fun BlendMode.toUma(): UmaBlendMode =
	when (this) {
		BlendMode.Normal -> UmaBlendMode.Normal
		BlendMode.AdditivePremultiplied -> UmaBlendMode.AdditivePremultiplied
		BlendMode.MultiplyPremultiplied -> UmaBlendMode.MultiplyPremultiplied
		BlendMode.Additive -> UmaBlendMode.Additive
		BlendMode.AdditiveGlow -> UmaBlendMode.AdditiveGlow
		BlendMode.Darken -> UmaBlendMode.Darken
		BlendMode.Multiply -> UmaBlendMode.Multiply
		BlendMode.ColorBurn -> UmaBlendMode.ColorBurn
		BlendMode.LinearBurn -> UmaBlendMode.LinearBurn
		BlendMode.Lighten -> UmaBlendMode.Lighten
		BlendMode.Screen -> UmaBlendMode.Screen
		BlendMode.ColorDodge -> UmaBlendMode.ColorDodge
		BlendMode.Overlay -> UmaBlendMode.Overlay
		BlendMode.SoftLight -> UmaBlendMode.SoftLight
		BlendMode.HardLight -> UmaBlendMode.HardLight
		BlendMode.LinearLight -> UmaBlendMode.LinearLight
		BlendMode.Hue -> UmaBlendMode.Hue
		BlendMode.Color -> UmaBlendMode.Color
	}

/**
 * The runtime blend mode a file value names.
 *
 * @return BlendMode The runtime value.
 */
internal fun UmaBlendMode.toRuntime(): BlendMode =
	when (this) {
		UmaBlendMode.Normal -> BlendMode.Normal
		UmaBlendMode.AdditivePremultiplied -> BlendMode.AdditivePremultiplied
		UmaBlendMode.MultiplyPremultiplied -> BlendMode.MultiplyPremultiplied
		UmaBlendMode.Additive -> BlendMode.Additive
		UmaBlendMode.AdditiveGlow -> BlendMode.AdditiveGlow
		UmaBlendMode.Darken -> BlendMode.Darken
		UmaBlendMode.Multiply -> BlendMode.Multiply
		UmaBlendMode.ColorBurn -> BlendMode.ColorBurn
		UmaBlendMode.LinearBurn -> BlendMode.LinearBurn
		UmaBlendMode.Lighten -> BlendMode.Lighten
		UmaBlendMode.Screen -> BlendMode.Screen
		UmaBlendMode.ColorDodge -> BlendMode.ColorDodge
		UmaBlendMode.Overlay -> BlendMode.Overlay
		UmaBlendMode.SoftLight -> BlendMode.SoftLight
		UmaBlendMode.HardLight -> BlendMode.HardLight
		UmaBlendMode.LinearLight -> BlendMode.LinearLight
		UmaBlendMode.Hue -> BlendMode.Hue
		UmaBlendMode.Color -> BlendMode.Color
	}

/**
 * The file-side spelling of an alpha blend mode.
 *
 * @return UmaAlphaBlendMode The file's value.
 */
internal fun AlphaBlendMode.toUma(): UmaAlphaBlendMode =
	when (this) {
		AlphaBlendMode.Over -> UmaAlphaBlendMode.Over
		AlphaBlendMode.Atop -> UmaAlphaBlendMode.Atop
		AlphaBlendMode.Out -> UmaAlphaBlendMode.Out
		AlphaBlendMode.Conjoint -> UmaAlphaBlendMode.Conjoint
		AlphaBlendMode.Disjoint -> UmaAlphaBlendMode.Disjoint
	}

/**
 * The runtime alpha blend mode a file value names.
 *
 * @return AlphaBlendMode The runtime value.
 */
internal fun UmaAlphaBlendMode.toRuntime(): AlphaBlendMode =
	when (this) {
		UmaAlphaBlendMode.Over -> AlphaBlendMode.Over
		UmaAlphaBlendMode.Atop -> AlphaBlendMode.Atop
		UmaAlphaBlendMode.Out -> AlphaBlendMode.Out
		UmaAlphaBlendMode.Conjoint -> AlphaBlendMode.Conjoint
		UmaAlphaBlendMode.Disjoint -> AlphaBlendMode.Disjoint
	}

/**
 * The file-side spelling of a parameter kind.
 *
 * @return UmaParameterKind The file's value.
 */
internal fun ParameterKind.toUma(): UmaParameterKind =
	when (this) {
		ParameterKind.NORMAL -> UmaParameterKind.Normal
		ParameterKind.BLEND_SHAPE -> UmaParameterKind.BlendShape
	}

/**
 * The runtime parameter kind a file value names.
 *
 * @return ParameterKind The runtime value.
 */
internal fun UmaParameterKind.toRuntime(): ParameterKind =
	when (this) {
		UmaParameterKind.Normal -> ParameterKind.NORMAL
		UmaParameterKind.BlendShape -> ParameterKind.BLEND_SHAPE
	}

/**
 * The file-side spelling of a part group mode.
 *
 * @return UmaGroupMode The file's value.
 */
internal fun PartGroupMode.toUma(): UmaGroupMode =
	when (this) {
		PartGroupMode.PassThrough -> UmaGroupMode.PassThrough
		PartGroupMode.Grouped -> UmaGroupMode.Grouped
		PartGroupMode.Isolated -> UmaGroupMode.Isolated
	}

/**
 * The runtime part group mode a file value names.
 *
 * @return PartGroupMode The runtime value.
 */
internal fun UmaGroupMode.toRuntime(): PartGroupMode =
	when (this) {
		UmaGroupMode.PassThrough -> PartGroupMode.PassThrough
		UmaGroupMode.Grouped -> PartGroupMode.Grouped
		UmaGroupMode.Isolated -> PartGroupMode.Isolated
	}

/**
 * The file-side spelling of a runtime target.
 *
 * @return UmaRuntimeTarget The file's value.
 */
internal fun RuntimeTarget.toUma(): UmaRuntimeTarget =
	when (this) {
		RuntimeTarget.NoTarget -> UmaRuntimeTarget.NoTarget
		RuntimeTarget.Ayagami -> UmaRuntimeTarget.Ayagami
		RuntimeTarget.Cubism30 -> UmaRuntimeTarget.Cubism30
		RuntimeTarget.Cubism33 -> UmaRuntimeTarget.Cubism33
		RuntimeTarget.Cubism40 -> UmaRuntimeTarget.Cubism40
		RuntimeTarget.Cubism42 -> UmaRuntimeTarget.Cubism42
		RuntimeTarget.Cubism50 -> UmaRuntimeTarget.Cubism50
		RuntimeTarget.Cubism53 -> UmaRuntimeTarget.Cubism53
	}

/**
 * The runtime target a file value names.
 *
 * @return RuntimeTarget The runtime value.
 */
internal fun UmaRuntimeTarget.toRuntime(): RuntimeTarget =
	when (this) {
		UmaRuntimeTarget.NoTarget -> RuntimeTarget.NoTarget
		UmaRuntimeTarget.Ayagami -> RuntimeTarget.Ayagami
		UmaRuntimeTarget.Cubism30 -> RuntimeTarget.Cubism30
		UmaRuntimeTarget.Cubism33 -> RuntimeTarget.Cubism33
		UmaRuntimeTarget.Cubism40 -> RuntimeTarget.Cubism40
		UmaRuntimeTarget.Cubism42 -> RuntimeTarget.Cubism42
		UmaRuntimeTarget.Cubism50 -> RuntimeTarget.Cubism50
		UmaRuntimeTarget.Cubism53 -> RuntimeTarget.Cubism53
	}

/**
 * The file-side spelling of a keyform channel.
 *
 * @return UmaFormChannel The file's value.
 */
internal fun FormChannel.toUma(): UmaFormChannel =
	when (this) {
		FormChannel.DRAW_ORDER -> UmaFormChannel.DrawOrder
		FormChannel.OPACITY -> UmaFormChannel.Opacity
		FormChannel.MULTIPLY_COLOR -> UmaFormChannel.MultiplyColor
		FormChannel.SCREEN_COLOR -> UmaFormChannel.ScreenColor
		FormChannel.FLIP_X -> UmaFormChannel.FlipX
		FormChannel.FLIP_Y -> UmaFormChannel.FlipY
		FormChannel.GLUE_INTENSITY -> UmaFormChannel.GlueIntensity
	}

/**
 * The runtime keyform channel a file value names.
 *
 * @return FormChannel The runtime value.
 */
internal fun UmaFormChannel.toRuntime(): FormChannel =
	when (this) {
		UmaFormChannel.DrawOrder -> FormChannel.DRAW_ORDER
		UmaFormChannel.Opacity -> FormChannel.OPACITY
		UmaFormChannel.MultiplyColor -> FormChannel.MULTIPLY_COLOR
		UmaFormChannel.ScreenColor -> FormChannel.SCREEN_COLOR
		UmaFormChannel.FlipX -> FormChannel.FLIP_X
		UmaFormChannel.FlipY -> FormChannel.FLIP_Y
		UmaFormChannel.GlueIntensity -> FormChannel.GLUE_INTENSITY
	}

/**
 * The key a channel's track is written under, which failure paths name.
 *
 * @return String The key.
 */
internal fun UmaFormChannel.wireName(): String = UmaFormChannel.serializer().descriptor.getElementName(ordinal)