package org.umamo.ui.properties

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.setCanvasSize
import org.umamo.edit.setRuntimeTarget
import org.umamo.edit.setWorldOrigin
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.ui.kit.FieldStack
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.SelectField
import org.umamo.ui.resources.*

/*
 * The Document tab's sections: properties of the document itself rather than of any selected item, so they
 * are the one tab that shows with an empty selection.
 */

/** Document > Canvas: the document canvas size and world origin. */
internal val CanvasSection =
	PropertySection(
		id = "document.canvas",
		title = Res.string.properties_section_canvas,
		rows = { context ->
			val puppet = context.puppet
			val session = context.session
			listOf(
				// Width + height joined into one stacked group; the whole stack is one searchable row.
				PropertyRow(
					terms = listOf(Res.string.properties_field_canvas_width, Res.string.properties_field_canvas_height),
				) { _ ->
					val pixels = stringResource(Res.string.unit_pixels)
					FieldStack(
						listOf(
							{ position ->
								PropertyFieldRow(stringResource(Res.string.properties_field_canvas_width)) {
									NumberField(
										value = puppet.canvasWidth,
										onValueChange = { newWidth -> session?.setCanvasSize(newWidth, puppet.canvasHeight) },
										modifier = Modifier.fillMaxWidth(),
										range = POSITIVE_RANGE,
										decimals = 0,
										unitSuffix = pixels,
										stackPosition = position,
									)
								}
							},
							{ position ->
								PropertyFieldRow(stringResource(Res.string.properties_field_canvas_height)) {
									NumberField(
										value = puppet.canvasHeight,
										onValueChange = { newHeight -> session?.setCanvasSize(puppet.canvasWidth, newHeight) },
										modifier = Modifier.fillMaxWidth(),
										range = POSITIVE_RANGE,
										decimals = 0,
										unitSuffix = pixels,
										stackPosition = position,
									)
								}
							},
						),
					)
				},
				// The origin x / y into a second stacked group below it.
				PropertyRow(
					terms = listOf(Res.string.properties_field_origin_x, Res.string.properties_field_origin_z),
				) { _ ->
					FieldStack(
						listOf(
							{ position ->
								PropertyFieldRow(stringResource(Res.string.properties_field_origin_x)) {
									NumberField(
										value = puppet.worldOriginX,
										onValueChange = { newX -> session?.setWorldOrigin(newX, puppet.worldOriginY) },
										modifier = Modifier.fillMaxWidth(),
										range = UNBOUNDED_RANGE,
										decimals = 1,
										stackPosition = position,
									)
								}
							},
							{ position ->
								PropertyFieldRow(stringResource(Res.string.properties_field_origin_z)) {
									NumberField(
										value = puppet.worldOriginY,
										onValueChange = { newY -> session?.setWorldOrigin(puppet.worldOriginX, newY) },
										modifier = Modifier.fillMaxWidth(),
										range = UNBOUNDED_RANGE,
										decimals = 1,
										stackPosition = position,
									)
								}
							},
						),
					)
				},
			)
		},
	)

/**
 * Document > Runtime: the document's runtime-compatibility target (Cubism, Ayagami) and the features
 * it restricts.  The selector writes [org.umamo.runtime.model.PuppetModel.runtimeTarget] as one undo
 * step; the restricted list derives from the capability matrix, never a hardcoded per-target list.
 *
 * Every target except Ayagami survives a CMO3 save round-trip (NoTarget persists as the editor's
 * SDK(N/A)/Latest sentinel); Ayagami has no CMO3 targetVersionNo encoding, so a save keeps the
 * file's original value - UMA will carry it natively.
 */
internal val RuntimeSection =
	PropertySection(
		id = "document.runtime",
		title = Res.string.properties_section_runtime,
		rows = { context ->
			val puppet = context.puppet
			val session = context.session
			listOf(
				PropertyRow(terms = listOf(Res.string.properties_field_runtime_target)) { _ ->
					val targetLabels = runtimeTargetLabels()
					PropertyFieldRow(stringResource(Res.string.properties_field_runtime_target)) {
						SelectField(
							selected = puppet.runtimeTarget,
							modifier = Modifier.fillMaxWidth(),
							options = RuntimeTarget.entries,
							label = { target -> targetLabels[target] ?: target.displayName },
							onSelect = { target -> session?.setRuntimeTarget(target) },
						)
					}
				},
				// The whole restricted list is one searchable row so header search keeps the block
				// intact, same rationale as the stacked canvas fields above.
				PropertyRow(
					terms = listOf(Res.string.properties_runtime_restricted, Res.string.properties_runtime_no_restrictions),
				) { _ ->
					val restricted = puppet.runtimeTarget.restrictedFeatures()
					if (restricted.isEmpty()) {
						PropertyLine(stringResource(Res.string.properties_runtime_no_restrictions))
					} else {
						Column {
							PropertyLine(stringResource(Res.string.properties_runtime_restricted))
							restricted.forEach { feature ->
								Box(modifier = Modifier.padding(start = 12.dp)) {
									PropertyLine(stringResource(runtimeFeatureLabelRes(feature)))
								}
							}
						}
					}
				},
			)
		},
	)
