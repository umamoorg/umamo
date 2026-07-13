package org.umamo.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Surface
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes

/** The square hit size of each corner drag handle (matches Blender's small corner widget). */
private val CORNER_HANDLE_SIZE_HEIGHT = 20.dp
private val CORNER_HANDLE_SIZE_WIDTH = 15.dp

/**
 * One leaf area: its header (editor-type dropdown + split/close) above the current space's content,
 * with four corner drag handles for the Blender-style join gesture.  The content is resolved from
 * [LocalSpaceRegistry] by the area's [LeafArea.space]; the [AreaScope] is remembered on the stable area
 * id so it (and any future per-space state) survives recomposition.  A thin outline delineates the area.
 *
 * The leaf reports its on-screen rectangle to the shared [LocalAreaDragController] (content-local, the
 * one space the drag overlay also uses) and evicts it on dispose, so a leaf consumed by a join cleans
 * up after itself.
 *
 * 1 つの葉エリア。ヘッダ（種別ドロップダウン＋分割／閉じる）の下に現在の空間の内容を表示し、四隅に結合
 * 用のドラッグハンドルを置く。自身の矩形を共有コントローラへ報告する。
 *
 * @param LeafArea area The area to render.
 * @param Function onCommand Sink for the header's and the corner gesture's structural edits.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun AreaLeaf(area: LeafArea, onCommand: (AreaCommand) -> Unit, modifier: Modifier = Modifier) {
	val registry = LocalSpaceRegistry.current
	val scope = remember(area.id) { AreaScope(area.id) }
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val borderColor =
		when {
			hovered -> colors.panelBorderHover
			else -> colors.panelBorder
		}
	val dragController = LocalAreaDragController.current

	// The right-click context menu mirrors the header's structural actions plus a Change Editor Type
	// submenu of every registered space.  Built here (in composition) so each label can be localized.
	val contextItems =
		listOf(
			MenuItem.Action(
				label = stringResource(Res.string.area_split_left_right),
				onSelect = { onCommand(AreaCommand.SplitArea(area.id, SplitOrientation.Horizontal)) },
			),
			MenuItem.Action(
				label = stringResource(Res.string.area_split_top_bottom),
				onSelect = { onCommand(AreaCommand.SplitArea(area.id, SplitOrientation.Vertical)) },
			),
			MenuItem.Separator,
			MenuItem.Submenu(
				label = stringResource(Res.string.area_change_editor),
				items =
					registry.all.map { descriptor ->
						MenuItem.Action(
							label = stringResource(descriptor.title),
							onSelect = { onCommand(AreaCommand.SwitchSpace(area.id, descriptor.kind)) },
							icon = descriptor.icon,
						)
					},
			),
			MenuItem.Separator,
			MenuItem.Action(
				label = stringResource(Res.string.area_close),
				onSelect = { onCommand(AreaCommand.CloseArea(area.id)) },
			),
		)

	// Drop this leaf's captured rectangle when it leaves composition (e.g. it was consumed by a join);
	// keyed on the stable id (the leaf lives under key(id) in AreaTree), like the viewport host's unregister.
	if (dragController != null) {
		DisposableEffect(area.id) {
			onDispose { dragController.removeBounds(area.id) }
		}
	}

	Box(
		modifier =
			modifier
				.fillMaxSize()
				.onGloballyPositioned { leafCoords ->
					val content = dragController?.contentCoords
					if (content != null && content.isAttached && leafCoords.isAttached) {
						dragController.putBounds(area.id, content.localBoundingBoxOf(leafCoords))
					}
				},
	) {
		ContextMenuArea(items = contextItems, modifier = Modifier.fillMaxSize()) {
			Surface(
				modifier = Modifier.fillMaxSize().hoverable(interaction),
				border = BorderStroke(1.dp, borderColor),
				shape = shapes.large,
			) {
				Column(modifier = Modifier.fillMaxSize()) {
					AreaHeader(area = area, scope = scope, onCommand = onCommand)
					Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
						registry.descriptor(area.space).content(scope)
					}
				}
			}
		}
		// Corner handles last so they sit above the content for hit-testing.  They only capture a real drag
		// (touch-slop), so a plain tap still falls through to header controls underneath the top corners.
		if (dragController != null) {
			for (corner in AreaCorner.entries) {
				AreaCornerHandle(
					controller = dragController,
					areaId = area.id,
					corner = corner,
					onCommand = onCommand,
				)
			}
		}
	}
}

/**
 * One corner drag handle: a small, invisible hit target showing the move cursor on hover and, on a real
 * drag, driving the shared [controller] to begin / update / end a corner join.  Pointer positions are
 * converted from this handle's local space into the controller's content-local space via the captured
 * coordinates, so the drag is tracked correctly even after the pointer leaves the handle.
 *
 * 四隅のドラッグハンドル。ホバーで移動カーソル、ドラッグで結合ジェスチャを駆動する。
 *
 * @param AreaDragController controller The shared corner-drag state.
 * @param String areaId The owning leaf's id (the join survivor).
 * @param AreaCorner corner Which corner this handle is.
 * @param Function onCommand Sink for the resulting join command on release.
 */
@Composable
private fun BoxScope.AreaCornerHandle(
	controller: AreaDragController,
	areaId: String,
	corner: AreaCorner,
	onCommand: (AreaCommand) -> Unit,
) {
	var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
	Box(
		modifier =
			Modifier
				.align(corner.toAlignment())
				.size(width = CORNER_HANDLE_SIZE_WIDTH, height = CORNER_HANDLE_SIZE_HEIGHT)
				.onGloballyPositioned { handleCoords = it }
				.pointerHoverIcon(areaMovePointerIcon())
				.pointerInput(areaId) {
					detectDragGestures(
						onDragStart = { startOffset ->
							contentLocalOf(controller, handleCoords, startOffset)?.let { pointer ->
								controller.beginDrag(areaId, corner, pointer)
							}
						},
						onDrag = { change, _ ->
							change.consume()
							contentLocalOf(controller, handleCoords, change.position)?.let { pointer ->
								controller.updateDrag(pointer)
							}
						},
						onDragEnd = { controller.endDrag()?.let { command -> onCommand(command) } },
						onDragCancel = { controller.cancelDrag() },
					)
				},
	)
}

/**
 * Converts a handle-local pointer [offset] into the controller's content-local space, or null when the
 * coordinates are not both attached (a transient layout state).
 *
 * @param AreaDragController controller Holds the content-space coordinates.
 * @param LayoutCoordinates? handleCoords The corner handle's coordinates.
 * @param Offset offset The pointer position in handle-local space.
 * @return Offset? The pointer in content-local space, or null.
 */
private fun contentLocalOf(
	controller: AreaDragController,
	handleCoords: LayoutCoordinates?,
	offset: Offset,
): Offset? {
	val content = controller.contentCoords
	if (content == null || handleCoords == null || !content.isAttached || !handleCoords.isAttached) {
		return null
	}
	return content.localPositionOf(handleCoords, offset)
}

/**
 * Maps a corner to the Box alignment that pins a handle there.
 *
 * @return Alignment The matching corner alignment.
 */
private fun AreaCorner.toAlignment(): Alignment =
	when (this) {
		AreaCorner.TopLeft -> Alignment.TopStart
		AreaCorner.TopRight -> Alignment.TopEnd
		AreaCorner.BottomLeft -> Alignment.BottomStart
		AreaCorner.BottomRight -> Alignment.BottomEnd
	}
