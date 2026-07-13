# UV Editor — Manual Test Checklist

Covers the UV editor work landed 2026-07 (see the TODO.md § UV Editor summary).  Ordered so the
early sections build the state the later ones need.  Items marked (!) are the ones most likely to
hide a bug — they cross feature boundaries or touch the render-sync / undo machinery.

Suggested model: a corpus CMO3 with an atlas and several drawables sharing a page.  A model with a
left/right pair (eyes) is ideal for the mirror workflow near the end.

Reach the UV editor via the Texture workspace tab — it ships a UV editor area beside a 2D viewport
by default (WorkspaceDefaults).  Both panes drive one shared EditorSession.

## Smoke pass (5 minutes)

- [x] Open a model, switch to the Texture workspace: left pane is the UV editor, right pane is the
      2D viewport (or your default arrangement).
- [x] The UV editor shows the active drawable's atlas page as an upright image with a page border,
      a checker backdrop behind it, and a header (select-mode buttons, active-mesh name, pivot,
      proportional).
- [x] Object mode: the UV pane shows a read-only wireframe over the art; you can pan (MMB) and zoom
      (wheel) but not edit.
- [x] Select a meshed drawable, Tab into Edit mode: its wireframe becomes editable in the UV pane.

## V-orientation acceptance check (highest-risk correctness) (!)

- [x] In Edit mode, the UV wireframe sits on the CORRECT texels of the art and is UPRIGHT — not
      vertically mirrored against the image.  Pick a drawable with an obvious top/bottom asymmetry
      (an eye, a mouth, hair tips) and confirm the mesh's top lands on the art's top.
- [x] The same drawable's wireframe in the 2D viewport and in the UV pane describe the same art
      (nothing is upside down between the two panes).

## Atlas underlay & page resolution

- [x] The page shown follows the active drawable: select a drawable on a different atlas region and
      the UV pane recenters on the art it samples.
- [x] Zoom in past 1:1 — the image stays crisp / nearest-filtered (no bilinear smear) above 1:1.
- [x] Alpha reads correctly (no dark fringing / wrong premultiply halo around art edges against the
      checker).
- [x] Edit mode shows every session drawable that lives on this page (active emphasized); Object
      mode shows only the active drawable, read-only.
- [x] Multi-page model: selecting a drawable whose art is on another page swaps the page; drawables
      on other pages simply are not drawn (no indicator yet — expected, deferred).

## Selection — shared with the 2D viewport (UV sync always on)

- [x] Vertex / edge / face select modes via the header buttons and 1 / 2 / 3: click selects,
      Shift-click and Ctrl-click toggle, empty-click clears.
- [x] A selects all, Ctrl+I inverts — in all three modes.
- [x] Box drag selects; C circle paint selects and the wheel resizes the brush ring. (!)
- [x] L under the pointer floods the connected island; Ctrl+L floods from the whole selection;
      nothing happens over empty canvas. (!)
- [x] Selection is genuinely SHARED: select vertices in the 2D viewport, look at the UV pane — the
      same vertices are selected there, and vice versa (no separate UV selection). (!)
- [x] The header names the active drawable, and it updates as you click into a different mesh.

## Modal G / S / R over UVs (the core feature)

Do each of these in Edit mode with the pointer over the UV pane.

- [x] G grabs the selected UVs; the art visibly resamples as you drag; click confirms, and it is
      ONE undo step. (!)
- [x] S scales and R rotates the selected UVs about the pivot; confirm/cancel behave.
- [x] X / Z during G and S lock the axis (guide line + HUD "Along X / Along Z"); same key releases,
      the other switches; R ignores them; the lock clears when the operator ends.
- [x] Pivot modes (header dropdown / pivot pie) change the anchor: Median centroid, Individual
      Origins (two islands about their own centres), Active Element, 2D Cursor.
- [x] Proportional editing (O): unselected UVs inside the ring follow, weighted by distance; the
      ring is sized in TEXELS and the wheel grows/shrinks it mid-drag without the viewport also
      zooming. (!)
- [x] The HUD badge during the op reads sensibly (e.g. "Grab  Along X  Proportional Smooth · N").
- [x] Escape cancels the op cleanly (art snaps back, no half-applied UVs); Enter confirms without
      the mouse.
- [x] LIVE GPU PREVIEW: while dragging UVs in the UV pane, the SAME drawable in the 2D viewport
      updates in real time (the art resamples there too), and on confirm both panes agree. (!)
- [x] Attempt a UV op with an empty selection, or on a mesh with no UVs: refused with the near-cursor
      "no UVs" notice rather than a broken gesture.

## UV cursor

- [x] Shift+RightClick in the UV pane places the UV cursor (a crosshair marker); it stays glued to
      its texel while you pan / zoom.
- [x] With pivot set to 2D Cursor, S / R anchor on the placed UV cursor; before it is ever placed,
      the Cursor pivot falls back to median.

## Mirror U / V — the duplicated/flipped eyes workflow (!)

- [x] uv.mirrorU (via the command palette) flips the selected UVs horizontally in one undo step;
      uv.mirrorV flips vertically.
- [x] Full workflow end to end: select one eye's island (L), mirror it (uv.mirrorU), then G it into
      place over the other eye's art — the mirrored eye now samples the source eye's texels.  Undo
      walks back through the grab then the mirror, one step each.
- [x] Mirror respects the pivot (Median vs 2D Cursor land the flip about the expected axis); the
      mesh POSITIONS do not move (only UVs).

## View commands over the hovered UV area

With the pointer over the UV pane:

- [x] Fit frames the whole page; Actual Size is 1:1 texel-per-pixel; Zoom In / Out step; Frame
      Selected frames the covered UVs.
- [x] These act on the UV camera, NOT the 2D viewport camera — move the pointer to the 2D viewport
      and the same commands act there instead (hovered-area routing). (!)
- [x] The UV camera survives switching the area to another space and back (it is parked per-area).

## Key routing — hovered-area (Blender-style) (!)

- [x] With the pointer over the UV pane, G/S/R, select modes, L, and view commands hit the UV
      editor; move the pointer over the 2D viewport and the same keys hit the mesh/object operators
      there — no leakage either direction.
- [x] A keyboard command issued before touching either pane is a clean no-op (does not latch onto
      whichever pane you hover next).

## Multi-viewport & ownership gating (the risky cross-boundary cases) (!)

- [x] Start a UV grab in the UV pane, then pan the 2D viewport (bystander): the 2D viewport keeps
      panning while the UV gesture keeps tracking — neither steals the other.
- [x] While a UV op is running, the 2D viewport's own gizmo overlay is inert (no double-drive); and
      while a 2D-viewport G/S/R is running, the UV overlay is inert.
- [x] Split the UV area mid-gesture (or switch workspace tab / space): the running UV preview is not
      stomped, and the model does not strand on an un-committed preview.
- [x] Close the initiating UV area mid-gesture: the gesture cancels and the raster resyncs (no
      orphaned latch, no leftover preview).

## Undo, render sync & thumbnails

- [x] Every UV gesture and every mirror is exactly ONE undo step; a total no-op records nothing. (!)
- [x] Undo after a UV commit restores the exact prior mapping (the art in both panes returns to
      where it was).
- [x] After a UV commit the outliner / part thumbnails update to the new mapping (they self-heal on
      a uvs change).
- [x] Rapid repeated UV drags stay smooth (the renderer re-uploads only the UV buffer, not the whole
      drawable) — no stutter or flash on each commit.

## Cross-cutting sweeps

- [x] Mesh/UV decoupling proof: in the 2D viewport, move mesh vertices (G) — the UVs in the UV pane
      do NOT move; then move UVs in the UV pane — the mesh geometry in the 2D viewport does NOT
      move.  The two are independent. (!)
- [x] Deformer-parented mesh: pick a drawable under a warp or rotation deformer and edit its UVs —
      the UV mapping edits normally and the deformed art in the 2D viewport still samples the new
      texels. (!)
- [x] Japanese locale (localization.locale = ja): the UV header, notices ("no UVs"), the HUD badge,
      and the history entries (Move UVs / Mirror UVs) render translated — no raw keys, no clipped
      CJK text.
- [x] History panel labels: a UV drag reads "Move UVs", a mirror reads "Mirror UVs".
- [x] Save As after a UV edit session still produces a CMO3 the official editor opens (the writer
      re-emits the source file today, so this is a no-regression check — NOT a test that the UV edit
      survives the round-trip; that needs the PuppetModel→CMO3 writer, which does not exist yet).

## Known / deferred (do not chase)

- Relax / Pinch brush tools — deferred (no brush machinery yet).
- UV snap pie and UV zoom-region — documented no-ops over a hovered UV area.
- Multi-page indicator — meshes on non-active pages are simply not drawn; no chip yet.
- CMO3 write-back of edited UVs — no PuppetModel→CMO3 writer exists; the Save As item above is only
  a no-regression check.  The decoupled-geometry ↔ Cubism default-form decision is tracked in
  docs/plans/art-sourcing-pipeline.md § Phase H.
