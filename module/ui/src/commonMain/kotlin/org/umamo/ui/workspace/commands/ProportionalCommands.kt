package org.umamo.ui.workspace.commands

import org.jetbrains.compose.resources.StringResource
import org.umamo.edit.EditorSession
import org.umamo.edit.ProportionalFalloff
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/** The localized palette title for each proportional-editing falloff curve. */
private val FALLOFF_TITLES: Map<ProportionalFalloff, StringResource> =
	mapOf(
		ProportionalFalloff.Smooth to Res.string.cmd_mesh_proportional_falloff_smooth,
		ProportionalFalloff.Sphere to Res.string.cmd_mesh_proportional_falloff_sphere,
		ProportionalFalloff.Root to Res.string.cmd_mesh_proportional_falloff_root,
		ProportionalFalloff.Sharp to Res.string.cmd_mesh_proportional_falloff_sharp,
		ProportionalFalloff.Linear to Res.string.cmd_mesh_proportional_falloff_linear,
		ProportionalFalloff.Constant to Res.string.cmd_mesh_proportional_falloff_constant,
	)

/**
 * Proportional editing (Blender's O): the toggle flips it, the falloff commands select the curve
 * (enabling it if off).  The Edit overlay reads the state when an operator latches and the wheel resizes
 * the radius mid-gesture, so nothing here needs to know which area the gesture will run in.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun proportionalCommands(editorSession: EditorSession?, availability: SessionAvailability): List<Command> =
	listOf(
		Command("mesh.proportional.toggle", title = Res.string.cmd_mesh_proportional_toggle, availability = availability.inEditMode) {
			editorSession?.toggleProportionalEdit()
		},
		Command(
			"mesh.proportional.connectedToggle",
			title = Res.string.cmd_mesh_proportional_connected,
			availability = availability.inEditMode,
		) {
			editorSession?.toggleProportionalConnected()
		},
	) +
		// One falloff command per curve, looped over the enum so a new falloff cannot be forgotten here -
		// getValue throws on a curve with no title rather than silently registering an unlabelled command.
		ProportionalFalloff.entries.map { falloff ->
			Command(
				"mesh.proportional.falloff.${falloff.name.lowercase()}",
				title = FALLOFF_TITLES.getValue(falloff),
				availability = availability.inEditMode,
			) { editorSession?.setProportionalFalloff(falloff) }
		}