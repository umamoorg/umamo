package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.PartGroupMode
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alpha_blend_atop
import org.umamo.ui.resources.alpha_blend_conjoint
import org.umamo.ui.resources.alpha_blend_disjoint
import org.umamo.ui.resources.alpha_blend_out
import org.umamo.ui.resources.alpha_blend_over
import org.umamo.ui.resources.blend_mode_additive
import org.umamo.ui.resources.blend_mode_additive_glow
import org.umamo.ui.resources.blend_mode_additive_modern
import org.umamo.ui.resources.blend_mode_color
import org.umamo.ui.resources.blend_mode_color_burn
import org.umamo.ui.resources.blend_mode_color_dodge
import org.umamo.ui.resources.blend_mode_darken
import org.umamo.ui.resources.blend_mode_hard_light
import org.umamo.ui.resources.blend_mode_hue
import org.umamo.ui.resources.blend_mode_lighten
import org.umamo.ui.resources.blend_mode_linear_burn
import org.umamo.ui.resources.blend_mode_linear_light
import org.umamo.ui.resources.blend_mode_multiply
import org.umamo.ui.resources.blend_mode_multiply_modern
import org.umamo.ui.resources.blend_mode_normal
import org.umamo.ui.resources.blend_mode_overlay
import org.umamo.ui.resources.blend_mode_screen
import org.umamo.ui.resources.blend_mode_soft_light
import org.umamo.ui.resources.part_group_grouped
import org.umamo.ui.resources.part_group_isolated
import org.umamo.ui.resources.part_group_pass_through

/*
 * Localized display names for the format-level enums the Properties dropdowns edit (blend mode, alpha
 * blend mode, part group mode).  The label is UI chrome - the enum entry itself is the data model and
 * stays verbatim.  Each mapping is an exhaustive `when`, so adding an enum entry is a compile error until
 * it gets a label (compile-time completeness, in place of a runtime coverage test).
 */

/**
 * The flat, closed set of part group-mode choices for a dropdown.  [PartGroupMode] is a sealed type whose
 * Isolated case carries a composite; this projects it to a plain three-way selector so the dropdown edits
 * the mode while the composite rides along (default when first switched to Isolated).
 */
enum class PartGroupModeKind {
	PassThrough,
	Grouped,
	Isolated,
}

/**
 * The [PartGroupModeKind] of this mode (drops the Isolated composite payload).
 *
 * @return PartGroupModeKind The mode's kind.
 */
fun PartGroupMode.kind(): PartGroupModeKind =
	when (this) {
		PartGroupMode.PassThrough -> PartGroupModeKind.PassThrough
		PartGroupMode.Grouped -> PartGroupModeKind.Grouped
		is PartGroupMode.Isolated -> PartGroupModeKind.Isolated
	}

/**
 * Builds a [PartGroupMode] from a chosen [kind].  The modes are payload-free now that a part's composite
 * lives latently on the part itself (independent of the mode), so this is a plain projection - switching a
 * part's mode never touches its composite settings.
 *
 * @param PartGroupModeKind kind The chosen kind.
 * @return PartGroupMode The mode for that kind.
 */
fun partGroupModeOf(kind: PartGroupModeKind): PartGroupMode =
	when (kind) {
		PartGroupModeKind.PassThrough -> PartGroupMode.PassThrough
		PartGroupModeKind.Grouped -> PartGroupMode.Grouped
		PartGroupModeKind.Isolated -> PartGroupMode.Isolated
	}

/**
 * The [StringResource] naming a blend mode in the UI.
 *
 * @param BlendMode mode The blend mode.
 * @return StringResource The localized label resource.
 */
fun blendModeLabelRes(mode: BlendMode): StringResource =
	when (mode) {
		BlendMode.Normal -> Res.string.blend_mode_normal
		BlendMode.Additive -> Res.string.blend_mode_additive
		BlendMode.Multiply -> Res.string.blend_mode_multiply
		BlendMode.AdditiveModern -> Res.string.blend_mode_additive_modern
		BlendMode.AdditiveGlow -> Res.string.blend_mode_additive_glow
		BlendMode.Darken -> Res.string.blend_mode_darken
		BlendMode.MultiplyModern -> Res.string.blend_mode_multiply_modern
		BlendMode.ColorBurn -> Res.string.blend_mode_color_burn
		BlendMode.LinearBurn -> Res.string.blend_mode_linear_burn
		BlendMode.Lighten -> Res.string.blend_mode_lighten
		BlendMode.Screen -> Res.string.blend_mode_screen
		BlendMode.ColorDodge -> Res.string.blend_mode_color_dodge
		BlendMode.Overlay -> Res.string.blend_mode_overlay
		BlendMode.SoftLight -> Res.string.blend_mode_soft_light
		BlendMode.HardLight -> Res.string.blend_mode_hard_light
		BlendMode.LinearLight -> Res.string.blend_mode_linear_light
		BlendMode.Hue -> Res.string.blend_mode_hue
		BlendMode.Color -> Res.string.blend_mode_color
	}

/**
 * The [StringResource] naming an alpha blend mode in the UI.
 *
 * @param AlphaBlendMode mode The alpha blend mode.
 * @return StringResource The localized label resource.
 */
fun alphaBlendModeLabelRes(mode: AlphaBlendMode): StringResource =
	when (mode) {
		AlphaBlendMode.Over -> Res.string.alpha_blend_over
		AlphaBlendMode.Atop -> Res.string.alpha_blend_atop
		AlphaBlendMode.Out -> Res.string.alpha_blend_out
		AlphaBlendMode.Conjoint -> Res.string.alpha_blend_conjoint
		AlphaBlendMode.Disjoint -> Res.string.alpha_blend_disjoint
	}

/**
 * The [StringResource] naming a part group-mode choice in the UI.
 *
 * @param PartGroupModeKind kind The group-mode kind.
 * @return StringResource The localized label resource.
 */
fun partGroupModeLabelRes(kind: PartGroupModeKind): StringResource =
	when (kind) {
		PartGroupModeKind.PassThrough -> Res.string.part_group_pass_through
		PartGroupModeKind.Grouped -> Res.string.part_group_grouped
		PartGroupModeKind.Isolated -> Res.string.part_group_isolated
	}

/**
 * Resolves every blend-mode label once, as an option-to-string map for a dropdown.  The call count is
 * fixed ([BlendMode] is a closed enum), so it stays within Compose's positional-memoization rules.
 *
 * @return Map Each blend mode to its localized label.
 */
@Composable
fun blendModeLabels(): Map<BlendMode, String> = BlendMode.entries.associateWith { mode -> stringResource(blendModeLabelRes(mode)) }

/**
 * Resolves every alpha-blend-mode label once, as an option-to-string map for a dropdown.
 *
 * @return Map Each alpha blend mode to its localized label.
 */
@Composable
fun alphaBlendModeLabels(): Map<AlphaBlendMode, String> =
	AlphaBlendMode.entries.associateWith { mode -> stringResource(alphaBlendModeLabelRes(mode)) }

/**
 * Resolves every part group-mode label once, as a kind-to-string map for a dropdown.
 *
 * @return Map Each group-mode kind to its localized label.
 */
@Composable
fun partGroupModeLabels(): Map<PartGroupModeKind, String> =
	PartGroupModeKind.entries.associateWith { kind -> stringResource(partGroupModeLabelRes(kind)) }
