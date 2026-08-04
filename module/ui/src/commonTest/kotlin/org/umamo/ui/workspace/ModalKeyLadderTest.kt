package org.umamo.ui.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.PieMenuKind
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformAxisConstraint
import org.umamo.interop.ExportReport
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.Keymap
import org.umamo.ui.document.DocumentOpenError
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.kit.InlineEditController
import org.umamo.ui.kit.MenuBarController
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_mesh_grab
import org.umamo.ui.viewport.pieMenuEntriesFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the shell's modal key precedence - the order in which modal chrome and in-flight gestures claim a
 * key before the keymap ever sees it.
 *
 * The ladder's own header says "Order is the contract", and until this suite existed nothing enforced
 * it: every arm was reachable only through a live composition, so a reordering merged silently.  Two
 * halves here, and the second is the one that matters.  The per-arm tests prove each state claims its
 * key at all; the precedence tests open two or three modals at once and assert which one wins, which is
 * the only way a mis-ordered arm actually shows up.
 *
 * Several arms exist because of a specific past bug - Escape during an area drag must reach
 * area.dragCancel, Escape on a row drag must not deselect the dragged rows, Edit mode must never clear
 * the object selection - so those are pinned against the behavior their comments describe.
 */
class ModalKeyLadderTest {
	private val areaId = "viewport-1"

	private fun meshDrawable(id: String): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh =
				DrawableMesh(
					floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
					floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
					intArrayOf(0, 1, 2),
				),
			geometryGrid = null,
		)

	/**
	 * A session in [mode] with one drawable selected and every mesh element selected, so an operator or
	 * tool actually latches rather than refusing on an empty selection.
	 *
	 * @param EditorMode mode The mode to leave the session in.
	 * @return EditorSession The session.
	 */
	private fun session(mode: EditorMode = EditorMode.Object): EditorSession {
		val session =
			EditorSession(
				PuppetModel(
					parameters = emptyList(),
					parts = emptyList(),
					deformers = emptyList(),
					drawables = listOf(meshDrawable("a")),
					rootChildren = emptyList(),
					rootPartId = null,
				),
			)
		val drawable = SelectionTarget.Drawable(DrawableId("a"))
		session.setSelection(Selection(setOf(drawable), drawable))
		session.setMode(mode)
		if (mode == EditorMode.Edit) {
			session.selectAllMeshElements()
		}
		return session
	}

	/**
	 * One session per operator family, each already latched, in the mode that family requires - Object
	 * refuses outside Object mode and UV outside Edit mode.
	 *
	 * Each entry asserts its OWN latch, because a fixture that quietly fails to arm would make every
	 * ladder assertion below it pass for the wrong reason.
	 *
	 * @return List<Pair<String, EditorSession>> The family name and its latched session.
	 */
	private fun latchedOperatorFamilies(): List<Pair<String, EditorSession>> =
		listOf(
			"mesh" to session(EditorMode.Edit).apply { beginMeshOperator(MeshOperatorKind.Grab, areaId) },
			"object" to session(EditorMode.Object).apply { beginObjectOperator(MeshOperatorKind.Grab, areaId) },
			"uv" to session(EditorMode.Edit).apply { beginUvOperator(MeshOperatorKind.Grab, areaId) },
		).onEach { (family, session) ->
			assertNotNull(session.activeOperator, "the $family fixture must actually latch")
		}

	/** A selection handle over a mutable slot, so the clear-selection arm's effect is observable. */
	private class RecordingSelection(initial: Selection) : SelectionHandle {
		override var selection: Selection = initial
			private set

		override fun set(selection: Selection) {
			this.selection = selection
		}
	}

	private fun nonEmptySelection(): RecordingSelection {
		val drawable = SelectionTarget.Drawable(DrawableId("a"))
		return RecordingSelection(Selection(setOf(drawable), drawable))
	}

	/** A menu-bar seam whose close was recorded, so "the menu claimed it" is an assertion and not an absence. */
	private class RecordingMenuBar {
		var closed = false
		val controller = MenuBarController().apply { closeOpenMenu = { closed = true } }
	}

	private class RecordingInlineEdit {
		var cancelled = false
		val controller = InlineEditController().apply { cancel = { cancelled = true } }
	}

	private class RecordingRowDrag {
		var cancelled = false
		val controller = RowDragCancelController().apply { cancel = { cancelled = true } }
	}

	/** A sheet surface with an armed marquee, recording the disarm the ladder is expected to call. */
	private class RecordingSheet(armedInitially: Boolean) {
		var armed = armedInitially

		val surface: KeyformSheetSurface =
			KeyformSheetSurface(
				selectedTracks = { emptyList() },
				hasSelection = { false },
				frameAll = {},
				armBoxSelect = { armed = true },
				boxSelectArmed = { armed },
				disarmBoxSelect = { armed = false },
				nudgeSelection = {},
			)
	}

	private fun sheetViews(armed: Boolean): Pair<KeyformSheetViews, RecordingSheet> {
		val sheet = RecordingSheet(armed)
		val views = KeyformSheetViews()
		views.register("sheet-1", sheet.surface)
		return views to sheet
	}

	/** A controller reporting an in-flight corner drag, which several arms gate off. */
	private fun draggingController(): AreaDragController =
		AreaDragController().apply { beginDrag("a", AreaCorner.TopLeft, Offset.Zero) }

	/** A registry with one recording command, for the arms that dispatch by id. */
	private class RecordingRegistry(commandId: String) {
		var invoked = false
		val registry =
			CommandRegistry().apply {
				register(Command(commandId, title = Res.string.cmd_mesh_grab) { invoked = true })
			}
	}

	private fun press(
		key: Key,
		state: ShellModalState,
		isDown: Boolean = true,
		primaryModifier: Boolean = false,
		shift: Boolean = false,
		alt: Boolean = false,
	): Boolean = handleModalKeyLadder(ShellKeyStroke(key, isDown, primaryModifier, shift, alt), state)

	private fun escape(state: ShellModalState): Boolean = press(Key.Escape, state)

	private fun enter(state: ShellModalState): Boolean = press(Key.Enter, state)

	// ---------------------------------------------------------------------------------------------
	// Arm 1-3: the modal alerts, which swallow every key so nothing fires behind them.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun aConfirmDialogTakesEscapeAndSwallowsEverythingElse() {
		val overlays = ShellOverlayState().apply { pendingConfirm = ConfirmRequest(Res.string.cmd_mesh_grab) {} }
		val state = ShellModalState(overlays = overlays)

		assertTrue(press(Key.Spacebar, state), "every key is swallowed so no shortcut fires behind the dialog")
		assertNotNull(overlays.pendingConfirm, "but only Escape dismisses it")

		assertTrue(escape(state))
		assertNull(overlays.pendingConfirm)
	}

	@Test
	fun theOpenFailureAlertTakesEscapeOrEnter() {
		val overlays =
			ShellOverlayState().apply { openFailure = DocumentOpenFailure(DocumentOpenError.ReadFailed, "model.cmo3") }
		val state = ShellModalState(overlays = overlays)

		assertTrue(press(Key.Spacebar, state))
		assertNotNull(overlays.openFailure)

		assertTrue(enter(state), "Enter acknowledges it like its OK button")
		assertNull(overlays.openFailure)
	}

	@Test
	fun theExportReportAlertTakesEscapeOrEnter() {
		val overlays = ShellOverlayState().apply { exportReport = ExportReport(emptyList()) }
		val state = ShellModalState(overlays = overlays)

		assertTrue(escape(state))
		assertNull(overlays.exportReport)
	}

	// ---------------------------------------------------------------------------------------------
	// Arm 4-9: the chrome that claims Escape but yields other keys to its own content.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun anOpenMenuTakesEscape() {
		val menu = RecordingMenuBar()
		val state = ShellModalState(menuBarController = menu.controller)

		assertTrue(escape(state))
		assertTrue(menu.closed)
	}

	@Test
	fun anOpenMenuSwallowsOtherKeysInsteadOfFiringShortcutsBehindIt() {
		// The bar's dropdowns open non-focusable so the labels keep receiving hover, which leaves the host
		// window focused and this root ladder seeing every key first.  The bar's own handler claims ONLY
		// Escape, so before this arm covered the rest, G with the File menu open dispatched mesh.grab and
		// left the menu hanging open over the moving geometry.
		val menu = RecordingMenuBar()
		val registry = RecordingRegistry("mesh.grab")
		val keymap = Keymap(mapOf(KeyChord("KeyG") to "mesh.grab"))
		val state =
			ShellModalState(
				menuBarController = menu.controller,
				commandRegistry = registry.registry,
				keymap = keymap,
			)

		assertFalse(press(Key.G, state), "the key is inert; the menu has no keyboard content to yield to")

		assertFalse(registry.invoked, "no shortcut may fire while a menu is open")
		assertFalse(menu.closed, "and an unrelated key does not dismiss the menu either")
	}

	@Test
	fun theSelfFocusedOverlaysTakeEscapeAndYieldOtherKeys() {
		// Preferences, the two Help dialogs, and the palette are one family: Escape closes, anything else
		// falls through to the overlay's own content (its search field, its scroll, its links).
		val cases =
			listOf<Triple<String, ShellOverlayState, (ShellOverlayState) -> Boolean>>(
				Triple("preferences", ShellOverlayState().apply { settingsVisible = true }, { it.settingsVisible }),
				Triple("about", ShellOverlayState().apply { aboutVisible = true }, { it.aboutVisible }),
				Triple("credits", ShellOverlayState().apply { creditsVisible = true }, { it.creditsVisible }),
				Triple("palette", ShellOverlayState().apply { paletteVisible = true }, { it.paletteVisible }),
			)
		for ((name, overlays, isOpen) in cases) {
			val state = ShellModalState(overlays = overlays)

			assertFalse(press(Key.A, state), "$name yields non-Escape keys to its own content")
			assertTrue(isOpen(overlays), "$name stays open")

			assertTrue(escape(state), "$name claims Escape")
			assertFalse(isOpen(overlays), "$name closed")
		}
	}

	@Test
	fun escapeClosesOnlyTheTopmostSelfFocusedOverlay() {
		// The four used to be four consecutive arms, so their relative order was the ladder's.  Collapsing
		// them moved that order into closeTopmostSelfFocused, and this is what keeps the two from drifting:
		// one Escape closes exactly one overlay, the topmost, leaving the one beneath it up.
		val overlays =
			ShellOverlayState().apply {
				settingsVisible = true
				aboutVisible = true
			}
		val state = ShellModalState(overlays = overlays)

		assertTrue(escape(state))
		assertFalse(overlays.settingsVisible, "preferences sits above the Help dialogs")
		assertTrue(overlays.aboutVisible, "and the dialog beneath it survives the first Escape")

		assertTrue(escape(state))
		assertFalse(overlays.aboutVisible, "a second Escape takes the next one down")
	}

	@Test
	fun anInlineEditorTakesEscapeAndYieldsOtherKeysToTheField() {
		val inline = RecordingInlineEdit()
		val state = ShellModalState(inlineEditController = inline.controller)

		assertFalse(press(Key.A, state), "letters must reach the field, not fire file commands")
		assertFalse(inline.cancelled)

		assertTrue(escape(state))
		assertTrue(inline.cancelled)
	}

	// ---------------------------------------------------------------------------------------------
	// Arm 10-15: the three modal operator latches.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun escapeCancelsAnyModalOperator() {
		for ((family, session) in latchedOperatorFamilies()) {
			assertTrue(escape(ShellModalState(editorSession = session)), family)

			assertNull(session.activeMeshOperator.value, family)
			assertNull(session.activeObjectOperator.value, family)
			assertNull(session.activeUvOperator.value, family)
		}
	}

	@Test
	fun escapeOnAMeshOperatorDoesNotAlsoClearTheVertexSelection() {
		// The arm sits above the clear-selection arm precisely so cancelling a grab keeps the selection.
		val session = session(EditorMode.Edit)
		session.beginMeshOperator(MeshOperatorKind.Grab, areaId)
		assertNotNull(session.activeMeshOperator.value, "fixture: the grab must be running")
		val before = session.meshSelection.value

		assertTrue(escape(ShellModalState(editorSession = session)))

		assertNull(session.activeMeshOperator.value)
		assertEquals(before, session.meshSelection.value, "the grab was cancelled, not the selection")
	}

	@Test
	fun enterConfirmsAnyModalOperator() =
		runTest {
			for ((family, session) in latchedOperatorFamilies()) {
				var confirms = 0
				val collector = launch { session.meshConfirmRequests.collect { confirms++ } }
				@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
				runCurrent()

				assertTrue(enter(ShellModalState(editorSession = session)))
				@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
				runCurrent()

				assertEquals(1, confirms, "$family: Enter mirrors a primary click through the shared confirm signal")
				collector.cancel()
			}
		}

	// ---------------------------------------------------------------------------------------------
	// Arm 16: the pie menu, which owns the keyboard while its ring is up.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun anOpenPieMenuTakesEscapeAndSwallowsUnhandledKeys() {
		val session = session(EditorMode.Object)
		session.openPieMenu(PieMenuKind.PivotMode)
		val state = ShellModalState(editorSession = session)

		assertTrue(press(Key.A, state), "shortcuts must not fire under the ring")
		assertNotNull(session.activePieMenu.value, "an unmapped key leaves the ring up")

		assertTrue(escape(state))
		assertNull(session.activePieMenu.value)
	}

	@Test
	fun aPieMenuDigitPicksItsEntryAndClosesTheRing() {
		val session = session(EditorMode.Object)
		session.openPieMenu(PieMenuKind.PivotMode)
		val first = pieMenuEntriesFor(PieMenuKind.PivotMode).first()
		val registry = RecordingRegistry(first.commandId)
		val state = ShellModalState(editorSession = session, commandRegistry = registry.registry)

		assertTrue(press(Key.One, state))

		assertTrue(registry.invoked, "digit 1 picks the first chip, matching the ordinal it draws")
		assertNull(session.activePieMenu.value, "and the ring closes behind the pick")
	}

	@Test
	fun aPieMenuIgnoresKeyUp() {
		// The arm gates on isDown, so a key-up under the ring falls past it - the ring stays open.
		val session = session(EditorMode.Object)
		session.openPieMenu(PieMenuKind.PivotMode)

		press(Key.Escape, ShellModalState(editorSession = session), isDown = false)

		assertNotNull(session.activePieMenu.value)
	}

	// ---------------------------------------------------------------------------------------------
	// Arm 17-18: the axis lock, which only the ladder can deliver while an operator swallows input.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun xAndZToggleTheAxisLockUnderEveryOperatorFamily() {
		for ((family, session) in latchedOperatorFamilies()) {
			val state = ShellModalState(editorSession = session)

			assertTrue(press(Key.X, state), family)
			assertEquals(TransformAxisConstraint.AxisX, session.axisConstraint.value, family)

			assertTrue(press(Key.Z, state), family)
			assertEquals(TransformAxisConstraint.AxisZ, session.axisConstraint.value, family)
		}
	}

	@Test
	fun xIsNotClaimedWithNoOperatorRunning() {
		// Without a live operator X must reach the keymap, or the axis arm would eat a bindable key.
		val session = session(EditorMode.Object)

		assertFalse(press(Key.X, ShellModalState(editorSession = session)))
		assertNull(session.axisConstraint.value)
	}

	// ---------------------------------------------------------------------------------------------
	// Arm 19-23: the armed tools and in-flight panel gestures.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun anArmedSelectToolTakesEscapeAndEnterAndCancelsBeforeDisarming() =
		runTest {
			for (key in listOf(Key.Escape, Key.Enter)) {
				val session = session(EditorMode.Object)
				session.beginBoxSelect(areaId)
				assertNotNull(session.activeSelectTool.value, "fixture: the tool must be armed")
				var cancels = 0
				val collector = launch { session.meshGestureCancelRequests.collect { cancels++ } }
				@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
				runCurrent()

				assertTrue(press(key, ShellModalState(editorSession = session)))
				@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
				runCurrent()

				// The cancel must fire as well as the disarm: clearSelectTool alone routes cleanup through a
				// recomposition-gated effect that can lose the race to a mouse release still in flight.
				assertEquals(1, cancels, "$key resolves the in-flight gesture")
				assertNull(session.activeSelectTool.value, "$key disarms the tool")
				collector.cancel()
			}
		}

	@Test
	fun anArmedSheetMarqueeTakesEscape() {
		val (views, sheet) = sheetViews(armed = true)

		assertTrue(escape(ShellModalState(keyformSheets = views)))

		assertFalse(sheet.armed, "an armed marquee hides the cursor, so it must always have a way out")
	}

	@Test
	fun anArmedRelationPickTakesEscape() {
		val relationPick = RelationPickController()
		relationPick.arm(accepts = emptySet()) {}
		val selection = nonEmptySelection()
		val state = ShellModalState(selection = selection, relationPick = relationPick)

		assertTrue(escape(state))

		assertNull(relationPick.request)
		assertFalse(selection.selection.isEmpty, "abandoning a pick must never also wipe the selection")
	}

	@Test
	fun anArmedZoomRegionTakesEscape() {
		val session = session(EditorMode.Object)
		session.armZoomRegion(areaId)
		assertNotNull(session.zoomRegionArmedArea.value, "fixture: the region must be armed")

		assertTrue(escape(ShellModalState(editorSession = session)))

		assertNull(session.zoomRegionArmedArea.value)
	}

	@Test
	fun anInFlightRowDragTakesEscapeWithoutDeselectingTheDraggedRows() {
		// The press that started the drag already selected the row, so the clear-selection arm below would
		// otherwise deselect the rows mid-drag.
		val rowDrag = RecordingRowDrag()
		val registry = RecordingRegistry("row.dragCancel")
		val selection = nonEmptySelection()
		val state =
			ShellModalState(
				selection = selection,
				rowDragCancel = rowDrag.controller,
				commandRegistry = registry.registry,
			)

		assertTrue(escape(state))

		assertTrue(registry.invoked, "the cancel routes through the registry, mirroring area.dragCancel")
		assertFalse(selection.selection.isEmpty, "and the dragged rows keep their selection")
	}

	@Test
	fun anInFlightDividerDragTakesEscapeWithoutClearingTheSelection() {
		// A divider drag keeps its session inside the dragged SplitContainer and never touches
		// AreaDragController, so isDragging stays false and the corner-drag gates below do not cover it.
		// Before this arm existed, Escape while resizing a panel fell all the way through and wiped the
		// object selection.
		val splitterDragCancel = SplitterDragCancelController()
		var cancelled = false
		splitterDragCancel.cancel = { cancelled = true }
		val registry = RecordingRegistry("area.dragCancel")
		val selection = nonEmptySelection()
		val state =
			ShellModalState(
				editorSession = session(EditorMode.Object),
				selection = selection,
				splitterDragCancel = splitterDragCancel,
				commandRegistry = registry.registry,
			)

		assertTrue(escape(state))

		assertTrue(registry.invoked, "the cancel routes through area.dragCancel like the corner drag")
		assertFalse(selection.selection.isEmpty, "and resizing a panel never touches the selection")
		assertFalse(cancelled, "the recording seam is only reached through the real command, not directly")
	}

	@Test
	fun noDividerDragLeavesEscapeToTheArmsBelow() {
		// Guards the arm against widening to "the area tree exists": with no drag parked, Escape must
		// still reach the clear-selection arm.
		val selection = nonEmptySelection()

		assertTrue(escape(ShellModalState(selection = selection, splitterDragCancel = SplitterDragCancelController())))

		assertTrue(selection.selection.isEmpty)
	}

	// ---------------------------------------------------------------------------------------------
	// Arm 24-26: the viewport gestures and the final clear-selection.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun anInFlightViewportDragTakesEscapeWithoutClearingTheSelection() =
		runTest {
			val session = session(EditorMode.Object)
			session.setViewportGestureActive(true)
			val selection = nonEmptySelection()
			var cancels = 0
			val collector = launch { session.meshGestureCancelRequests.collect { cancels++ } }
			@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
			runCurrent()

			assertTrue(escape(ShellModalState(editorSession = session, selection = selection)))
			@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
			runCurrent()

			assertEquals(1, cancels)
			assertFalse(selection.selection.isEmpty, "cancelling a drag never also wipes the selection")
			collector.cancel()
		}

	@Test
	fun editModeConsumesEscapeAndNeverClearsTheObjectSelection() {
		// Blender parity: Edit mode leaves the selection alone.  Clearing it here would strand the Edit
		// session on a drawable nothing points at, since the object selection holds the edited drawable.
		val session = session(EditorMode.Edit)
		val selection = nonEmptySelection()

		assertTrue(escape(ShellModalState(editorSession = session, selection = selection)))

		assertFalse(selection.selection.isEmpty)
	}

	@Test
	fun objectModeEscapeClearsANonEmptySelection() {
		val session = session(EditorMode.Object)
		val selection = nonEmptySelection()

		assertTrue(escape(ShellModalState(editorSession = session, selection = selection)))

		assertTrue(selection.selection.isEmpty)
	}

	@Test
	fun aNullSessionCountsAsObjectModeForTheClear() {
		val selection = nonEmptySelection()

		assertTrue(escape(ShellModalState(selection = selection)))

		assertTrue(selection.selection.isEmpty)
	}

	// ---------------------------------------------------------------------------------------------
	// The fallthrough.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun anUnclaimedKeyReachesTheKeymap() {
		val registry = RecordingRegistry("mesh.grab")
		val keymap = Keymap(mapOf(KeyChord("KeyG") to "mesh.grab"))
		val state = ShellModalState(commandRegistry = registry.registry, keymap = keymap)

		assertTrue(press(Key.G, state))
		assertTrue(registry.invoked)
	}

	@Test
	fun anUnclaimedKeyUpReachesNothing() {
		val registry = RecordingRegistry("mesh.grab")
		val keymap = Keymap(mapOf(KeyChord("KeyG") to "mesh.grab"))
		val state = ShellModalState(commandRegistry = registry.registry, keymap = keymap)

		assertFalse(press(Key.G, state, isDown = false))
		assertFalse(registry.invoked, "only key-down dispatches")
	}

	@Test
	fun theKeymapSeesTheStrokesModifiers() {
		val registry = RecordingRegistry("mesh.grab")
		val keymap = Keymap(mapOf(KeyChord("KeyG", primaryModifier = true, shift = true) to "mesh.grab"))
		val state = ShellModalState(commandRegistry = registry.registry, keymap = keymap)

		assertFalse(press(Key.G, state), "the bare chord is unbound")
		assertTrue(press(Key.G, state, primaryModifier = true, shift = true))
		assertTrue(registry.invoked)
	}

	// ---------------------------------------------------------------------------------------------
	// Precedence: two or more modals live at once.  A per-arm sweep passes regardless of order, so
	// these are what actually enforce the contract.
	// ---------------------------------------------------------------------------------------------

	@Test
	fun aConfirmDialogOutranksEverythingBelowIt() {
		val session = session(EditorMode.Edit)
		session.beginMeshOperator(MeshOperatorKind.Grab, areaId)
		val overlays = ShellOverlayState().apply { pendingConfirm = ConfirmRequest(Res.string.cmd_mesh_grab) {} }
		val menu = RecordingMenuBar()
		val selection = nonEmptySelection()

		assertTrue(
			escape(
				ShellModalState(
					overlays = overlays,
					menuBarController = menu.controller,
					editorSession = session,
					selection = selection,
				),
			),
		)

		assertNull(overlays.pendingConfirm, "the dialog took it")
		assertFalse(menu.closed, "and nothing below it ran")
		assertNotNull(session.activeMeshOperator.value)
		assertFalse(selection.selection.isEmpty)
	}

	@Test
	fun anOpenMenuOutranksAModalOperator() {
		val session = session(EditorMode.Edit)
		session.beginMeshOperator(MeshOperatorKind.Grab, areaId)
		val menu = RecordingMenuBar()

		assertTrue(escape(ShellModalState(menuBarController = menu.controller, editorSession = session)))

		assertTrue(menu.closed)
		assertNotNull(session.activeMeshOperator.value, "the grab survives closing the menu")
	}

	@Test
	fun aModalOperatorOutranksAnArmedSelectTool() {
		// beginBoxSelect then beginMeshOperator leaves only the operator latched (they are mutually
		// exclusive), so this pins the ordering with the operator arm reached first.
		val session = session(EditorMode.Edit)
		session.beginBoxSelect(areaId)
		session.beginMeshOperator(MeshOperatorKind.Grab, areaId)
		assertNotNull(session.activeMeshOperator.value, "fixture: the grab must be running")
		assertNull(session.activeSelectTool.value, "the latches are mutually exclusive")

		assertTrue(escape(ShellModalState(editorSession = session)))

		assertNull(session.activeMeshOperator.value)
	}

	@Test
	fun anAreaDragDefersEscapeToTheDragCancelCommand() {
		// The three lowest arms all gate off !isDragging so an in-flight area CORNER drag reaches
		// area.dragCancel through the keymap; without that gate it would clear the selection instead of
		// cancelling.  Note this covers the corner drag ONLY - a divider (splitter) drag keeps its session
		// in SplitContainer's own state, never sets isDragging, and so has no Escape cancel at all.
		val session = session(EditorMode.Object)
		val selection = nonEmptySelection()
		val registry = RecordingRegistry("area.dragCancel")
		val keymap = Keymap(mapOf(KeyChord("Escape") to "area.dragCancel"))
		val state =
			ShellModalState(
				editorSession = session,
				selection = selection,
				dragController = draggingController(),
				commandRegistry = registry.registry,
				keymap = keymap,
			)

		assertTrue(escape(state))

		assertTrue(registry.invoked, "Escape reached area.dragCancel")
		assertFalse(selection.selection.isEmpty, "and did not clear the selection on the way")
	}

	@Test
	fun anArmedSheetMarqueeOutranksTheClearSelection() {
		val (views, sheet) = sheetViews(armed = true)
		val selection = nonEmptySelection()

		assertTrue(escape(ShellModalState(selection = selection, keyformSheets = views)))

		assertFalse(sheet.armed)
		assertFalse(selection.selection.isEmpty, "disarming a marquee is not a deselect")
	}

	@Test
	fun anUnarmedSheetLeavesEscapeToTheArmsBelow() {
		// Guards the arm against widening to "a sheet is open" - it must key on ARMED.
		val (views, _) = sheetViews(armed = false)
		val selection = nonEmptySelection()

		assertTrue(escape(ShellModalState(selection = selection, keyformSheets = views)))

		assertTrue(selection.selection.isEmpty, "with nothing armed the clear-selection arm runs")
	}
}
