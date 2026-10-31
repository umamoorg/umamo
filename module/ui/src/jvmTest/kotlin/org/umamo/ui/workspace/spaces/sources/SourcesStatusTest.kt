package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the status each row's glyph reads as in the table, and when a file's presence is asked about again.
 * Which glyph and tint a status draws with is pinned without a composition, in SourcesRowVisualTest.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class SourcesStatusTest {
	/**
	 * Counts the composable bodies open at any moment, of every package: the compiler reports a body's
	 * start and its end, and a body that skipped reports neither.
	 */
	private class OpenBodyCounter : CompositionTracer {
		/** How many bodies are open now; zero outside a composition. */
		var open = 0
			private set

		override fun isTraceInProgress(): Boolean = true

		override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
			open += 1
		}

		override fun traceEventEnd() {
			open -= 1
		}
	}

	/**
	 * The probe is asked from an effect, never while a composable body is open: asking is a look at the
	 * disk, and a composition waits for whatever it calls.
	 */
	@Test
	fun theProbeIsNeverAskedWhileComposing() {
		val bodies = OpenBodyCounter()
		val openBodiesWhenAsked = ArrayList<Int>()
		Composer.setTracer(bodies)

		try {
			runComposeUiTest {
				val harness = mountSources(onSourcePresenceAsked = { openBodiesWhenAsked.add(bodies.open) })
				runOnIdle { harness.sourcesViewState.refreshSerial++ }
				waitForIdle()
			}
		} finally {
			Composer.setTracer(null)
		}

		assertEquals(PROBED_FILES * 2, openBodiesWhenAsked.size, "each file asked about at the mount and at the refresh")
		assertTrue(openBodiesWhenAsked.all { open -> open == 0 }, "asked with bodies open: $openBodiesWhenAsked")
	}

	/** Until its first answer lands a file reads unknown, never missing: the table accuses no file it has not checked. */
	@Test
	fun aFileReadsUnknownUntilItsAnswerLands() =
		runComposeUiTest {
			val gate = CompletableDeferred<Unit>()
			val harness = mountSources(presenceGate = gate)
			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnknown, SourcesNames.FACE))
			assertFalse(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.FACE))

			runOnIdle { gate.complete(Unit) }
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.FACE))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.BODY))
		}

	/** A file keeps its last answer while it is asked about again: a missing file does not blink back to unknown. */
	@Test
	fun aFileKeepsItsAnswerWhileItIsAskedAboutAgain() =
		runComposeUiTest {
			val harness = mountSources()
			val gate = CompletableDeferred<Unit>()

			runOnIdle {
				harness.sourcePresenceGate = gate
				harness.sourcePresenceByPath[SourcesPaths.FACE] = true
				harness.sourcesViewState.refreshSerial++
			}
			waitForIdle()
			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.FACE), "the answer in hand, while the next is on its way")

			runOnIdle { gate.complete(Unit) }
			waitForIdle()
			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.FACE))
		}

	/**
	 * A round of asking that the next one overtook lands nothing, however late its answers come.  The
	 * overtaken round holds the answer it took for the first file it asked about, and the later round reads
	 * the other way for both files, so whichever file came first, a late landing would show.
	 */
	@Test
	fun aRoundTheNextOneOvertookLandsNothing() =
		runComposeUiTest {
			val harness = mountSources()
			val slowRound = CompletableDeferred<Unit>()
			runOnIdle {
				harness.sourcePresenceGate = slowRound
				harness.sourcesViewState.refreshSerial++
			}
			waitForIdle()

			runOnIdle {
				harness.sourcePresenceGate = null
				harness.sourcePresenceByPath[SourcesPaths.BODY] = false
				harness.sourcePresenceByPath[SourcesPaths.FACE] = true
				harness.sourcesViewState.refreshSerial++
			}
			waitForIdle()
			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.BODY), "the later round must really have landed")
			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.FACE), "the later round must really have landed")

			runOnIdle { slowRound.complete(Unit) }
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.BODY), "the round that was overtaken took Body for present")
			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.FACE), "the round that was overtaken took Face for missing")
		}

	/** A Sources space opened again in its area shows what it last knew, before anything is asked again. */
	@Test
	fun theAnswersOutliveTheBody() =
		runComposeUiTest {
			val harness = mountSources()

			assertEquals(
				mapOf(SourcesIds.body to SourcePresence.Present, SourcesIds.face to SourcePresence.Missing),
				harness.sourcesViewState.presenceBySource,
				"kept on the area's view state, which lives as long as the document",
			)
		}

	/** A file reads present or missing by what the probe answers for its path. */
	@Test
	fun aFileReadsItsPresenceFromTheProbe() =
		runComposeUiTest {
			val harness = mountSources()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.BODY))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.FACE))
		}

	/** A layer reads bound, bound by name, unbound, ignored, or under review; a tile on no page reads unplaced. */
	@Test
	fun aLayerAndATileReadTheirBinding() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.EYE)

			assertTrue(sourcesRowHasSlot(harness.text.sourcesBound, SourcesNames.HAIR))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesBoundByName, SourcesNames.EYE))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnbound, SourcesNames.SKETCH))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesIgnored, SourcesNames.NOTES))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesNeedsReview, SourcesNames.BROW_OLD))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnplaced, SourcesNames.EYE_ART))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnbound, harness.text.sourcesUnboundArt), "the group's heading carries its status")
		}

	/** A file the probe cannot answer for reads unknown, never missing. */
	@Test
	fun aFileTheProbeCannotAnswerForReadsUnknown() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle {
				harness.sourcePresenceByPath[SourcesPaths.BODY] = null
				harness.sourcesViewState.refreshSerial++
			}
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesUnknown, SourcesNames.BODY))
			assertFalse(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.BODY))
		}

	/** A file's presence is remembered: a change on disk shows only once something asks again. */
	@Test
	fun presenceIsNotAskedAboutOnEveryComposition() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle {
				harness.sourcePresenceByPath[SourcesPaths.BODY] = false
				harness.sourcesViewState.query = SourcesNames.BODY
			}
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.BODY), "the table composed again and asked nothing")
		}

	/** The header's refresh asks about every file again. */
	@Test
	fun aRefreshProbesAgain() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle {
				harness.sourcePresenceByPath[SourcesPaths.BODY] = false
				harness.sourcePresenceByPath[SourcesPaths.FACE] = true
				harness.sourcesViewState.refreshSerial++
			}
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.BODY))
			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.FACE))
		}

	/** The watcher's serial asks about every file again, so a file that came back shows without a click. */
	@Test
	fun theWatchersSerialProbesAgain() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle {
				harness.sourcePresenceByPath[SourcesPaths.FACE] = true
				harness.sourceWatchSerial.value += 1
			}
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesPresent, SourcesNames.FACE))
		}

	private companion object {
		/** The files of the rig the probe is asked about: both record a path. */
		const val PROBED_FILES = 2
	}
}