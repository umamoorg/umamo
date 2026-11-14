package org.umamo.ui.workspace.commands

import org.jetbrains.compose.resources.StringResource
import org.umamo.edit.EditorSession
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.transform.choiceKey
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandState
import org.umamo.ui.action.CommandTable
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.viewport.falloffLabelResource

/** The localized palette title for each proportional-editing falloff curve. */
private val FALLOFF_TITLES: Map<ProportionalFalloff, StringResource> =
	mapOf(
		ProportionalFalloff.Smooth to Res.string.cmd_mesh_proportional_falloff_smooth,
		ProportionalFalloff.Sphere to Res.string.cmd_mesh_proportional_falloff_sphere,
		ProportionalFalloff.Root to Res.string.cmd_mesh_proportional_falloff_root,
		ProportionalFalloff.InverseSquare to Res.string.cmd_mesh_proportional_falloff_inverse_square,
		ProportionalFalloff.Sharp to Res.string.cmd_mesh_proportional_falloff_sharp,
		ProportionalFalloff.Linear to Res.string.cmd_mesh_proportional_falloff_linear,
		ProportionalFalloff.Constant to Res.string.cmd_mesh_proportional_falloff_constant,
		ProportionalFalloff.Random to Res.string.cmd_mesh_proportional_falloff_random,
	)

/**
 * A falloff curve's glyph: Blender's curve icon, on the proportional chip while the curve is current and on the
 * curve's row among the curves.
 *
 * @param ProportionalFalloff falloff The falloff curve.
 * @return UmamoIcon The curve's glyph.
 */
private fun falloffIcon(falloff: ProportionalFalloff): UmamoIcon =
	when (falloff) {
		ProportionalFalloff.Smooth -> LocalUmamoIcons.falloffSmooth
		ProportionalFalloff.Sphere -> LocalUmamoIcons.falloffSphere
		ProportionalFalloff.Root -> LocalUmamoIcons.falloffRoot
		ProportionalFalloff.InverseSquare -> LocalUmamoIcons.falloffInverseSquare
		ProportionalFalloff.Sharp -> LocalUmamoIcons.falloffSharp
		ProportionalFalloff.Linear -> LocalUmamoIcons.falloffLinear
		ProportionalFalloff.Constant -> LocalUmamoIcons.falloffConstant
		ProportionalFalloff.Random -> LocalUmamoIcons.falloffRandom
	}

/**
 * The payload a control that shows Connected Only hands mesh.proportional.connectedToggle: the flag to set,
 * set silently.  The proportional panel's checkbox is its own confirmation, and a near-cursor notice raised
 * from inside the open panel would land under it, at the pointer as it was when the panel opened.  Without
 * the payload (Alt+O, the palette) the command flips the flag and confirms with the notice.
 *
 * @property Boolean connectedOnly Whether influence spreads only along mesh edges.
 */
internal class ProportionalConnectedRequest(val connectedOnly: Boolean)

/**
 * Proportional editing (Blender's O): the toggle flips it, the falloff commands select the curve and the
 * connected toggle flips Connected Only, neither switching the tool on or off.  The Edit overlay reads the state when an operator latches and the wheel resizes
 * the radius mid-gesture, so nothing here needs to know which area the gesture will run in.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun proportionalCommands(editorSession: EditorSession?, availability: SessionAvailability): List<Command> {
	// One falloff command per curve, looped over the enum so a new falloff cannot be forgotten here -
	// getValue throws on a curve with no title rather than silently registering an unlabelled command.  Together
	// they are the falloff family, each under the curve's short name and in effect while its curve is the setting's.
	val falloffs =
		ProportionalFalloff.entries.map { falloff ->
			Command(
				"mesh.proportional.falloff.${falloff.choiceKey}",
				title = FALLOFF_TITLES.getValue(falloff),
				availability = availability.inElementMode,
				group = CommandFamily.ProportionalFalloff,
				groupLabel = falloffLabelResource(falloff),
				icon = falloffIcon(falloff),
				state = CommandState { editorSession?.proportionalSettings?.value?.falloff == falloff },
			) { editorSession?.setProportionalFalloff(falloff) }
		}

	return commandTable(
		CommandTable.Proportional,
		// On while the tool is, which is what lights the header chip's toggle.
		Command(
			"mesh.proportional.toggle",
			title = Res.string.cmd_mesh_proportional_toggle,
			availability = availability.inElementMode,
			icon = LocalUmamoIcons.proportionalOn,
			state = CommandState { editorSession?.proportionalEdit?.value != null },
		) {
			editorSession?.toggleProportionalEdit()
		},
		Command(
			"mesh.proportional.connectedToggle",
			title = Res.string.cmd_mesh_proportional_connected,
			availability = availability.inElementMode,
		) { argument ->
			if (argument is ProportionalConnectedRequest) {
				editorSession?.setProportionalConnected(argument.connectedOnly)
			} else {
				editorSession?.toggleProportionalConnected()
			}
		},
		*falloffs.toTypedArray(),
	)
}