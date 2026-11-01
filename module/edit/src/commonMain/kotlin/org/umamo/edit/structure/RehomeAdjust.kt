package org.umamo.edit.structure

import org.umamo.edit.OperatorParameter

/*
 * A re-parent's face on the operation settings strip: whether the moved item was kept where it rests in its new
 * parent's space, or kept its numbers and followed the new parent.  The key is declared here, in the module that
 * records the move, so the UI's label table names a constant rather than repeating a string.
 */

/** The parameter key a re-parent's row carries. */
object RehomeParameterKeys {
	/** The Keep in Place row: whether the moved item stays where it rests. */
	const val KEEP_IN_PLACE = "rehome.keepInPlace"
}

/**
 * The strip's rows for a re-parent: the Keep in Place checkbox.
 *
 * @param Boolean keepInPlace Whether the move kept the item where it rests.
 * @return List The one row.
 */
fun rehomeParameters(keepInPlace: Boolean): List<OperatorParameter> =
	listOf(OperatorParameter.BooleanParameter(RehomeParameterKeys.KEEP_IN_PLACE, RehomeParameterKeys.KEEP_IN_PLACE, keepInPlace))