package org.umamo.ui.properties

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorSession
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a keyable properties row DISPLAYS: the pending unkeyed edit, else the track at the pose, else the
 * static.
 *
 * Driven through a real composition because the resolver reads the session's flows, and the bug it exists
 * to prevent - a keyed color field stuck on the shadowed static, so typing a new value looked rejected -
 * is invisible to any test that does not actually recompose on those flows.
 */
class DisplayedChannelValueTest {
	private val angleX = ParameterId("ParamAngleX")
	private val drawableId = DrawableId("d")
	private val target = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.MULTIPLY_COLOR)

	private val staticColor = ColorRgb(1f, 1f, 1f)
	private val keyedAtDefault = ColorRgb(0.25f, 1f, 0f)
	private val pendingColor = ColorRgb(1f, 0f, 1f)

	/** A drawable whose multiply color is keyed over ParamAngleX, holding [keyedAtDefault] at the default. */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(angleX, "ParamAngleX", min = -30f, max = 30f, default = 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "d",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = null,
						geometryGrid = null,
						multiplyColor = staticColor,
						channelGrids =
							ChannelGrids(
								mapOf(
									FormChannel.MULTIPLY_COLOR to
										KeyformGrid(
											axes = listOf(KeyformAxis(angleX, floatArrayOf(-30f, 0f, 30f))),
											cells =
												listOf(
													KeyformCell(intArrayOf(0), ChannelValue.Color(keyedAtDefault)),
													KeyformCell(intArrayOf(1), ChannelValue.Color(keyedAtDefault)),
													KeyformCell(intArrayOf(2), ChannelValue.Color(keyedAtDefault)),
												),
										),
								),
							),
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * A keyed channel shows the TRACK's value, not the static it shadows - and a pending unkeyed edit wins
	 * over both, so a typed value stays on screen instead of snapping back.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun pendingEditWinsOverTrackWhichWinsOverStatic() =
		runComposeUiTest {
			val session = EditorSession(model())
			var shown: ColorRgb? = null
			setContent {
				CompositionLocalProvider(
					LocalPuppet provides model(),
					LocalEditorSession provides session,
				) {
					shown =
						displayedChannelColor(KeyformOwner.Drawable(drawableId), FormChannel.MULTIPLY_COLOR, staticColor)
				}
			}
			waitForIdle()
			assertEquals(keyedAtDefault, shown, "a keyed channel must show the track, not the shadowed static")

			session.setPendingChannelEdit(target, ChannelValue.Color(pendingColor))
			waitForIdle()
			assertEquals(pendingColor, shown, "a pending unkeyed edit must win over the stored track")

			session.clearPendingChannelEdits()
			waitForIdle()
			assertEquals(keyedAtDefault, shown, "discarding the pending edit returns the field to the track")
		}
}