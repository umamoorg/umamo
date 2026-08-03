package org.umamo.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The area tree's hovered-surface stamping, driven with real pointer input against a real composition.
 *
 * None of this is reachable from a pure test: the stamp is a pointer-input node, and every property that
 * makes it trustworthy - that it covers every kind, that it follows a Change Editor Type, that a drag does
 * not stamp the area it passes over, that a dying area stops claiming the pointer - is a property of
 * Compose's dispatch rather than of any function we wrote.  The routing tests in commonTest assert what
 * the shell does with an answer; these assert the answer is right.
 */
class AreaHoverStampTest {
	/**
	 * A registry with every real kind's title and icon but an empty body, so a leaf composes with no
	 * puppet, no renderer, and no space state.  The stamp is on the leaf, not the space, so the real
	 * bodies are irrelevant to what is being measured here.
	 *
	 * @return SpaceRegistry The stub registry.
	 */
	private fun stubRegistry(): SpaceRegistry =
		SpaceRegistry(
			defaultSpaceRegistry().all.associate { descriptor ->
				descriptor.kind to
					SpaceDescriptor(
						kind = descriptor.kind,
						title = descriptor.title,
						icon = descriptor.icon,
						content = { Box(modifier = Modifier.fillMaxSize()) },
					)
			},
		)

	/**
	 * Composes one leaf of [kind] under a tracker, drives [interact] against it, and returns what the
	 * tracker ended up holding.
	 *
	 * @param SpaceKind kind The space the leaf hosts.
	 * @param Function interact The pointer input to drive.
	 * @return HoveredSurface? The stamped surface, or null if nothing stamped.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun stampAfter(kind: SpaceKind, interact: MouseInjectionScope.() -> Unit): HoveredSurface? {
		var result: HoveredSurface? = null
		runComposeUiTest {
			val tracker = HoveredSurfaceTracker()
			setContent {
				UmamoTheme {
					CompositionLocalProvider(
						LocalSpaceRegistry provides stubRegistry(),
						LocalHoveredSurfaceTracker provides tracker,
					) {
						Box(modifier = Modifier.size(400.dp, 300.dp).testTag("leaf")) {
							AreaLeaf(area = LeafArea("area-1", kind), onCommand = {})
						}
					}
				}
			}
			onNodeWithTag("leaf").performMouseInput(interact)
			result = tracker.lastTouched
		}
		return result
	}

	/**
	 * Every space kind stamps, not just the three editor surfaces that used to carry their own stamp.
	 *
	 * Swept over the whole enum because the point of moving the stamp to the leaf is that coverage stops
	 * being something a space author has to remember - a kind added later is covered by construction, and
	 * this fails if that ever stops being true.
	 */
	@Test
	fun everySpaceKindStampsItsOwnArea() {
		for (kind in SpaceKind.entries) {
			assertEquals(
				HoveredSurface("area-1", kind),
				stampAfter(kind) { moveTo(Offset(200f, 200f)) },
				"${kind.name} did not stamp the hovered surface",
			)
		}
	}

	/**
	 * The area HEADER stamps too, which the per-space stamps never did.
	 *
	 * This is the case the whole change exists for: the outliner's search field lives in its header, so
	 * using it has to read as "the pointer is on the outliner" - otherwise the tracker keeps naming
	 * whichever viewport the pointer visited before, and a key pressed afterwards acts there.
	 */
	@Test
	fun theHeaderStampsAsWellAsTheBody() {
		// The header band is the leaf's top 28dp; 8px in is inside it and clear of the body.
		assertEquals(
			HoveredSurface("area-1", SpaceKind.Outliner),
			stampAfter(SpaceKind.Outliner) { moveTo(Offset(200f, 8f)) },
			"hovering the header must claim the area",
		)
	}

	/** Leaving an area does not clear the stamp - it means "last touched", which keeps shortcuts alive over chrome. */
	@Test
	fun leavingAnAreaKeepsTheStamp() {
		assertEquals(
			HoveredSurface("area-1", SpaceKind.Properties),
			stampAfter(SpaceKind.Properties) {
				moveTo(Offset(200f, 200f))
				moveTo(Offset(-50f, -50f))
			},
			"an Exit must not clear the stamp",
		)
	}

	/**
	 * The stamp follows a Change Editor Type.
	 *
	 * SwitchSpace rewrites the leaf's kind while leaving its id alone, and the tree keys leaf composition
	 * on that id - so the leaf survives the change.  A pointer-input block keyed on the id alone would
	 * capture the original kind and keep reporting it forever; this is that bug's regression test.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theStampFollowsASpaceChange() =
		runComposeUiTest {
			val tracker = HoveredSurfaceTracker()
			var switchToLogs: (() -> Unit)? = null
			setContent {
				var space by remember { mutableStateOf(SpaceKind.Outliner) }
				switchToLogs = { space = SpaceKind.Logs }
				UmamoTheme {
					CompositionLocalProvider(
						LocalSpaceRegistry provides stubRegistry(),
						LocalHoveredSurfaceTracker provides tracker,
					) {
						Box(modifier = Modifier.size(400.dp, 300.dp).testTag("leaf")) {
							AreaLeaf(area = LeafArea("area-1", space), onCommand = {})
						}
					}
				}
			}
			onNodeWithTag("leaf").performMouseInput { moveTo(Offset(200f, 200f)) }
			assertEquals(HoveredSurface("area-1", SpaceKind.Outliner), tracker.lastTouched)

			switchToLogs?.invoke()
			waitForIdle()
			onNodeWithTag("leaf").performMouseInput { moveTo(Offset(210f, 200f)) }
			assertEquals(
				HoveredSurface("area-1", SpaceKind.Logs),
				tracker.lastTouched,
				"the leaf kept its id across the switch, so a stamp keyed only on the id would still say Outliner",
			)
		}

	/**
	 * A drag that starts in one area and crosses into another does not restamp.
	 *
	 * Compose hit-tests only unpressed pointers; a pressed one keeps the path captured at press.  The whole
	 * design leans on that - an area-join drag or a modal transform crossing a neighbour must not hand the
	 * neighbour the pointer - and it would fail silently, so it is pinned rather than assumed.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDragDoesNotStampTheAreaItCrossesInto() =
		runComposeUiTest {
			val tracker = HoveredSurfaceTracker()
			setContent {
				UmamoTheme {
					CompositionLocalProvider(
						LocalSpaceRegistry provides stubRegistry(),
						LocalHoveredSurfaceTracker provides tracker,
					) {
						Row(modifier = Modifier.size(400.dp, 300.dp).testTag("pair")) {
							Box(modifier = Modifier.size(200.dp, 300.dp)) {
								AreaLeaf(area = LeafArea("left", SpaceKind.Outliner), onCommand = {})
							}
							Box(modifier = Modifier.size(200.dp, 300.dp)) {
								AreaLeaf(area = LeafArea("right", SpaceKind.Logs), onCommand = {})
							}
						}
					}
				}
			}
			// 300f is squarely inside the RIGHT leaf (the pair is 400px wide, split at 200f), so the drag
			// genuinely crosses the boundary rather than wandering off the pair entirely.
			onNodeWithTag("pair").performMouseInput {
				moveTo(Offset(100f, 200f))
				press()
				moveTo(Offset(300f, 200f))
			}
			assertEquals(
				HoveredSurface("left", SpaceKind.Outliner),
				tracker.lastTouched,
				"a pressed pointer keeps its captured path, so the right leaf must not claim it",
			)

			onNodeWithTag("pair").performMouseInput {
				release()
				moveTo(Offset(310f, 200f))
			}
			assertEquals(
				HoveredSurface("right", SpaceKind.Logs),
				tracker.lastTouched,
				"once released, an ordinary move stamps normally",
			)
		}

	/**
	 * A disposed area stops claiming the pointer.
	 *
	 * Unlike leaving an area, a closed or joined one is gone - keeping its id would leave every downstream
	 * lookup resolving against something that no longer exists.  The other per-area registries all evict on
	 * dispose; this one does too.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDisposedAreaReleasesTheStamp() =
		runComposeUiTest {
			val tracker = HoveredSurfaceTracker()
			var closeArea: (() -> Unit)? = null
			setContent {
				var present by remember { mutableStateOf(true) }
				closeArea = { present = false }
				UmamoTheme {
					CompositionLocalProvider(
						LocalSpaceRegistry provides stubRegistry(),
						LocalHoveredSurfaceTracker provides tracker,
					) {
						Box(modifier = Modifier.size(400.dp, 300.dp).testTag("host")) {
							if (present) {
								AreaLeaf(area = LeafArea("area-1", SpaceKind.History), onCommand = {})
							}
						}
					}
				}
			}
			onNodeWithTag("host").performMouseInput { moveTo(Offset(200f, 200f)) }
			assertEquals(HoveredSurface("area-1", SpaceKind.History), tracker.lastTouched)

			closeArea?.invoke()
			waitForIdle()
			assertNull(tracker.lastTouched, "a closed area must not keep claiming the pointer")
		}
}
