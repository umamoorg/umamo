package org.umamo.ui.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Surface
import org.umamo.ui.theme.LocalUmamoColors

/** Header bar height - compact, since every area carries one. */
private val HEADER_HEIGHT = 28.dp

/**
 * The top strip of every leaf area: the area-type dropdown on the left (the agnostic "switch this
 * area to any space" control), the current space's optional [SpaceDescriptor.headerContent] beside it,
 * and split/close actions on the right. All structural edits are emitted as [AreaCommand]s through
 * [onCommand], the single choke point the shell routes through the action registry - the header never
 * mutates the tree itself.  The space slot is resolved from the registry so the header itself stays
 * space-agnostic; when a slot exists it OWNS the flexible middle region (a weighted Row), so each
 * space arranges its own controls - start-packed by default, or centered / end-aligned via its own
 * weight spacers - without the header knowing.
 *
 * すべての葉エリア上部の帯。左にエディタ種別ドロップダウンと空間固有のヘッダ内容、右に分割／閉じる。
 * ヘッダ内容スロットは中央の可変領域全体を占め、配置（中央寄せ・右寄せ）は各空間側が決める。
 * 構造編集は AreaCommand として onCommand に流すだけ（ヘッダ自身はツリーを変更しない）。
 *
 * @param LeafArea area The area this header belongs to.
 * @param AreaScope scope The area's stable scope, handed to the space's header content.
 * @param Function onCommand Sink for structural edits (split / close / switch space).
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun AreaHeader(area: LeafArea, scope: AreaScope, onCommand: (AreaCommand) -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	Surface(modifier = modifier.fillMaxWidth(), color = colors.headerBackground) {
		Row(
			modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = 15.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AreaTypeDropdown(area = area, onCommand = onCommand)
			val headerContent = LocalSpaceRegistry.current.descriptor(area.space).headerContent
			if (headerContent != null) {
				Spacer(modifier = Modifier.width(8.dp))
				// The slot owns the flexible middle: its RowScope is this weighted Row, so a space's
				// own weight spacers resolve against the whole region (centering, right-alignment).
				Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
					headerContent(scope)
				}
			} else {
				Spacer(modifier = Modifier.weight(1f))
			}
		}
	}
}

/**
 * The area-type selector, Blender-style: the button face is the current space's icon plus a chevron
 * that points right while the menu is closed and down while it is open. Clicking opens a menu of every
 * registered space, each row an icon plus its localized title. Choosing one emits a SwitchSpace command
 * (the area keeps its id, so a hosted GL surface survives the switch only if you switch back - switching
 * away is a genuine content change).  The chip anatomy is the shared kit [DropdownChip].
 *
 * エディタ種別セレクタ（Blender 式）。現在の空間アイコンとシェブロンを表示し、クリックで全空間の
 * アイコン付きメニューを開く。
 *
 * @param LeafArea area The area whose space is being chosen.
 * @param Function onCommand Sink for the SwitchSpace command.
 */
@Composable
private fun AreaTypeDropdown(area: LeafArea, onCommand: (AreaCommand) -> Unit) {
	val registry = LocalSpaceRegistry.current
	val current = registry.descriptor(area.space)
	// Resolved in composition because the chip's semantics lambda is not composable.
	val currentTitle = stringResource(current.title)
	var expanded by remember { mutableStateOf(false) }
	// One menu row per registered space; selecting it switches this area and the menu's own dismiss closes
	// the popup (so onSelect need not toggle `expanded` itself).
	val items =
		registry.all.map { descriptor ->
			MenuItem.Action(
				label = stringResource(descriptor.title),
				onSelect = { onCommand(AreaCommand.SwitchSpace(area.id, descriptor.kind)) },
				icon = descriptor.icon,
			)
		}
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = true },
		contentDescription = currentTitle,
		icon = current.icon,
	) {
		Menu(
			items = items,
			onDismissRequest = { expanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}
