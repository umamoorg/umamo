package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.edit.transform.beginBrush
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.CommandState
import org.umamo.ui.action.CommandTable
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.workspace.SpaceScope

/**
 * The Deform brush's command: arming the brush in the viewport the pointer is on, where a drag smears the
 * lattice points the brush covers.
 *
 * The brush's settings are not commands of their own: the operation settings strip carries them, so a
 * finished stroke can be re-run at another strength, falloff, or radius, and the settings a stroke landed
 * with are the ones the next stroke starts from.  The mode is switched beside them, in the sidebar's tool
 * settings, so the brush's settings live in one place.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun brushCommands(
	editorSession: EditorSession?,
	routing: CommandRouting,
	availability: SessionAvailability,
): List<Command> =
	commandTable(
		CommandTable.Brush,
		// The brush is an Edit-mode tool over a warp's cage, so it arms in the 2D viewport alone - there is
		// no lattice to smear in the UV editor, and Pose mode has the lattice's own tool.  The command applies
		// in Edit mode whatever the selection holds, so the toolbar shows it there and nowhere else; a session
		// with no warp to smear says so when the brush is asked for.
		Command(
			"mesh.deformBrush",
			title = Res.string.cmd_mesh_deform_brush,
			availability = availability.inEditMode,
			spaces = SpaceScope.Viewport2D,
			group = CommandSection.ToolBrush,
			icon = LocalUmamoIcons.toolDeformBrush,
			state = CommandState { editorSession?.activeBrush?.value != null },
		) {
			routing.viewportArea()?.let { areaId -> editorSession?.beginBrush(areaId) }
		},
	)