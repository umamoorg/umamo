package org.umamo.ui.workspace

import org.umamo.edit.MergeTarget
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.ProportionalRows
import org.umamo.edit.TransformGestureParameters
import org.umamo.edit.TransformRowSpace
import org.umamo.edit.mergeParameters
import org.umamo.edit.slideParameters
import org.umamo.edit.transformParameters
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.ui.model.addArtworkParameters
import org.umamo.ui.model.matchArtworkParameters
import org.umamo.ui.model.repackParameters
import org.umamo.ui.viewport.PlacementDragStatus
import org.umamo.ui.viewport.placementParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the operation strip's row descriptions: every row the strip can show has one, no two rows share
 * one by accident, and a choice entry's key never picks one up.
 *
 * The rows come from the operations' own row builders rather than from a hand-kept key list, so an
 * operation that grows a row fails here until the row has a description to show.
 */
class OperationStripLabelsTest {
	/**
	 * Every row every adjustable operation registers: the repack, the three placement gestures, adding and
	 * matching artwork, each modal transform in both of its spaces with the proportional rows on, the
	 * vertex slide, and the merge.
	 */
	private val everyRow: List<OperatorParameter> =
		buildList {
			addAll(repackParameters(AtlasPackOptions()))
			for (kind in listOf(MeshOperatorKind.Grab, MeshOperatorKind.Rotate, MeshOperatorKind.Scale)) {
				addAll(placementParameters(PlacementDragStatus(kind, 0, 0, 0f, 1f, 1f, 0, false), 1024, 1024))
				for (space in TransformRowSpace.entries) {
					addAll(transformParameters(kind, TransformGestureParameters.IDENTITY, space, ProportionalRows(true, ProportionalFalloff.Smooth, 50f, false)))
				}
			}
			addAll(addArtworkParameters(SourceArtImportOptions(), placed = true))
			addAll(matchArtworkParameters(0.8f, SourceArtImportOptions()))
			addAll(slideParameters(0.5f))
			addAll(mergeParameters(MergeTarget.AtCenter))
		}

	/** The distinct label keys of [everyRow], the unit a description is looked up by. */
	private val everyLabelKey: List<String> = everyRow.map { row -> row.labelKey }.distinct()

	/** A row with no description would show its label and nothing to explain it. */
	@Test
	fun everyRowHasADescription() {
		for (labelKey in everyLabelKey) {
			assertNotNull(operatorParameterDescriptionRes(labelKey), "the strip row $labelKey has no description")
		}
	}

	/**
	 * Two rows mapped to one description is a copy-paste slip: the keys are distinct precisely because the
	 * rows mean different things, even where two share a label (a placement's Move Y and a transform's).
	 */
	@Test
	fun noTwoRowsShareADescription() {
		val descriptions = everyLabelKey.mapNotNull { labelKey -> operatorParameterDescriptionRes(labelKey) }
		assertEquals(descriptions.size, descriptions.distinct().size, "two strip rows share one description")
	}

	/**
	 * A choice entry's key names one option in a dropdown, not a row, so it describes nothing; resolving
	 * one would mean a prefixed key collided with a row's.
	 */
	@Test
	fun choiceEntriesHaveNoDescription() {
		val entryKeys = everyRow.filterIsInstance<OperatorParameter.ChoiceParameter>().flatMap { row -> row.choices.mapNotNull { choice -> choice.labelKey } }
		assertTrue(entryKeys.isNotEmpty(), "the strip has choice rows whose entries carry label keys")
		for (entryKey in entryKeys) {
			assertNull(operatorParameterDescriptionRes(entryKey), "the choice entry $entryKey resolved a row description")
		}
	}

	/** An unknown key describes nothing rather than borrowing another row's text. */
	@Test
	fun anUnknownKeyHasNoDescription() {
		assertNull(operatorParameterDescriptionRes("not.a.row"))
	}
}