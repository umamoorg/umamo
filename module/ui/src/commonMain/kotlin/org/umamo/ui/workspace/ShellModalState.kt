package org.umamo.ui.workspace

import org.umamo.edit.EditorSession
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.kit.InlineEditController
import org.umamo.ui.kit.MenuBarController
import org.umamo.ui.model.SelectionHandle

/**
 * Everything the shell's modal key ladder consults, in one aggregate.
 *
 * The ladder reads a dozen collaborators to decide who owns a key, and threading them as a dozen
 * parameters is what kept it untested: a test for one arm had to name all twelve.  Every field defaults
 * to an inert instance, so a test states only the modal it exercises and the rest stay quiet - the
 * shell, which holds live instances of all of them, passes each one explicitly.
 *
 * This is a plain holder, not a controller: it owns no state of its own and adds no behavior.
 *
 * @property ShellOverlayState overlays The modal chrome flags (confirm, alerts, palette, preferences, Help).
 * @property MenuBarController menuBarController The menu-bar seam; an open menu claims Escape.
 * @property InlineEditController inlineEditController The inline-editor seam; an open field claims the keyboard.
 * @property EditorSession? editorSession The open document's session, or null with no document.
 * @property SelectionHandle? selection The object-selection handle, or null with no document.
 * @property AreaDragController dragController The area corner-drag state; an in-flight drag defers Escape to area.dragCancel.
 * @property SplitterDragCancelController splitterDragCancel The divider-drag seam; an in-flight divider drag claims Escape.
 * @property RowDragCancelController rowDragCancel The panel row-drag seam; an in-flight row drag claims Escape.
 * @property RelationPickController relationPick The relation-pick seam; an armed eyedropper claims Escape.
 * @property KeyformSheetViews keyformSheets The open keyform sheets; an armed marquee claims Escape.
 * @property CommandRegistry commandRegistry The registry pie picks and the fallthrough dispatch into.
 * @property Keymap keymap The active keymap for the fallthrough dispatch.
 */
internal class ShellModalState(
	val overlays: ShellOverlayState = ShellOverlayState(),
	val menuBarController: MenuBarController = MenuBarController(),
	val inlineEditController: InlineEditController = InlineEditController(),
	val editorSession: EditorSession? = null,
	val selection: SelectionHandle? = null,
	val dragController: AreaDragController = AreaDragController(),
	val splitterDragCancel: SplitterDragCancelController = SplitterDragCancelController(),
	val rowDragCancel: RowDragCancelController = RowDragCancelController(),
	val relationPick: RelationPickController = RelationPickController(),
	val keyformSheets: KeyformSheetViews = KeyformSheetViews(),
	val commandRegistry: CommandRegistry = CommandRegistry(),
	val keymap: Keymap = Keymap(emptyMap()),
)