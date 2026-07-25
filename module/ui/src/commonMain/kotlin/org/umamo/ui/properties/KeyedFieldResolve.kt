package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.ui.model.KeyedFieldState
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.keyedFieldStateOf

/*
 * Resolving a properties-panel row's keyed state from the session.
 *
 * Kept beside the panel rather than inside the field primitives: the kit knows how to PAINT a keyed state
 * and nothing about parameters, poses, or sessions, which is what lets the same fields serve rows that are
 * not keyable at all.
 *
 * プロパティ行のキー状態の解決。kit 側は描画のみを知り、モデルには触れない。
 */

/**
 * The keyed state of [channel] on [drawable] against the currently targeted parameter.
 *
 * @param Drawable drawable The drawable the row edits.
 * @param FormChannel channel The channel the row edits.
 * @return KeyedFieldState The state to tint the field with.
 */
@Composable
internal fun keyedFieldStateOf(drawable: Drawable, channel: FormChannel): KeyedFieldState {
	val puppet = LocalPuppet.current ?: return KeyedFieldState.None
	val session = LocalEditorSession.current ?: return KeyedFieldState.None
	val parameterSelection by remember(session) { session.parameterSelection }.collectAsState()
	val pose by remember(session) { session.pose }.collectAsState()
	val pendingEdits by remember(session) { session.pendingChannelEdits }.collectAsState()
	return keyedFieldStateOf(
		puppet = puppet,
		target = KeyableTarget(KeyformOwner.Drawable(drawable.id), channel),
		parameterId = parameterSelection.active,
		pose = pose,
		pendingEdits = pendingEdits,
	)
}
