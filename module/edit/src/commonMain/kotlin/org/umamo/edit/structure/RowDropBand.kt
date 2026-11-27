package org.umamo.edit.structure

/**
 * Where a row drop lands relative to the target row: before it, into it (nesting inside a container
 * row, when the panel's rules allow it), or after it.  Shared by every row-dragging panel (outliner,
 * parameters) so their drop rules and indicators speak one vocabulary.
 */
enum class RowDropBand { Before, Into, After }