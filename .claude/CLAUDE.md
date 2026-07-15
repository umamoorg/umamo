# CLAUDE.md — Umamo

## What This Is?

Umamo is an open-source cross-platform rigging editor for 2D puppet animation, with first class pen and touch support, built as a drop-in replacement for the Live2D Cubism Editor.

- **Language:** Kotlin, Kotlin Multiplatform (shared core across desktop + Android).
- **Package root:** `org.umamo` (reverse-DNS of the `umamo.org` domain). Shared library modules are flat — `org.umamo.format`, `org.umamo.runtime`, `org.umamo.gpu`, `org.umamo.ui`, `org.umamo.reimport`. App code carries product + platform — `org.umamo.editor.desktop`, `org.umamo.editor.android` (a future viewer takes `org.umamo.viewer.*`).  The editor's Android `applicationId` is `org.umamo.editor`, keeping bare `org.umamo` a clean umbrella.
- **Platforms:** Windows, macOS, Linux (keyboard/mouse/pen) **and Android tablets** (pen/touch — e.g.  Wacom MovinkPad).  Rigging is a first-class workflow on **all** of these, not desktop-only.
- **Headline workflow:** draw in Clip Studio Paint (or Photoshop), save/export, switch to Umamo, and the rig **refreshes against the updated art** without losing rigging work.
- **Compatibility is the product:** read and write the existing Cubism source format so riggers can adopt incrementally and interoperate with the official editor.

## The Thesis (do not let this drift)

v1's win condition is **a CMO3-native rigging editor that interoperates with the official Cubism Editor**:

1. **Read and write `.cmo3`** (editor source) → a rigger can bounce the same project between Umamo and the official Cubism Editor without burning bridges.  This bidirectional interop is the primary adoption wedge.
2. **Ingest source art** (PSD, CLIP, KRA with robust layer ID matching — see Re-import) and keep the rig live against art changes.
3. **GPU-accelerated, trustworthy preview** — the viewport deformation must match what a rigger would see in Cubism so they can trust what they're authoring.

Runtime concerns (MOC3 emit, SDK/Core replacement) are a work in progress — see Format Support.  If a proposed change trades away source-format compatibility or the refresh workflow for internal elegance, it is wrong by definition.  Raise it, don't silently take it.

## Format Support (priority order)

- **UMA — not yet implemented.**  Native Umamo format; no codec exists yet.  The design roadmap is `docs/plans/art-sourcing-pipeline.md` § Phase G, which leans **ZIP over TAR** (a manifest + model-graph JSON + packed atlas + thumbnails + editor view state, with the original source bytes embedded verbatim) — that roadmap supersedes the earlier "tentatively ZIP/TAR, Blender BLEND as inspiration" framing.  Gated on Phase E (the source-art → `PuppetModel` bridge) landing first.
- **CMO3 — read, and write at the byte/XML level; no edit-to-CMO3 lowering yet.**  A CMO3 is the native format for the Cubism editor.  `Cmo3.write` round-trips an unedited file's **decompressed `main.xml` byte-for-byte** — not the whole file; see the Fidelity contract for what is and is not guaranteed.  There is no `PuppetModel → CMO3` synthesis: Save re-serializes the model graph it read, it does not copy the original bytes.  See `docs/formats/CMO3.md`.
- **PSD — read.**  Layered-art ingestion.  Re-import join key is the lyid layer id when present (Photoshop-written, stable across rename/reorder), else layer name/path + order (lossy; some exporters omit lyid, so CLIP/KRA with always-present stable ids stay more robust).  See `docs/formats/PSD.md`.
- **CLIP (Clip Studio Paint) — read.**  Layered-art ingestion, proprietary (SQLite-based container).  Implemented: layer tree + raster decode, registered in `FormatRegistry` with corpus-backed tests.  See `docs/formats/CLIP.md`.
- **KRA (Krita) — read.**  Layered-art ingestion, open source format.  See `docs/formats/KRA.md`.
- **MOC3 — read AND write.**  Serialized C structs (memory-cast, so byte-exact layout).  See `docs/formats/MOC3.md`.
- **JSON family**: `model3` (manifest), `physics3`, `cdi3`, `pose3`, `exp3`, `motion3`, `userdata3`, texture atlas.

PSD/CLIP/KRA readers are implemented and corpus-tested, but nothing downstream consumes them yet: the document picker only offers `.cmo3`, and there is no source-art → `PuppetModel` bridge, so an ingested PSD/CLIP/KRA layer tree can't become an editable rig today.

**Unified codec contract.** Every binary container format presents the same face to the rest of the project: a `FormatCodec<TModel>` in `:format` (`kind`/`matches`/`read(ByteArray)`/`write(TModel)`, tied to `FileKind`), with `FormatRegistry` doing detect-by-magic and resolve-by-kind.  CMO3 (`object Cmo3`, in `jvmAndroidMain` — JDOM/reflection are JVM-only) and MOC3 (`object Moc3`, `commonMain`) both implement it; the in-memory model type differs per format and shares no supertype (hence the generic).  The **JSON sidecars are `String`-shaped, not bytes**, so they deliberately sit *outside* the codec contract as plain helpers on `Moc3` (`readModel3`/`writeModel3`/…).  Add a format by implementing the interface and registering it — never by growing a bespoke read/write facade.

## Architecture

### UI: Compose Multiplatform + Embedded GPU Viewport

The editor UI is **Compose Multiplatform** — Jetpack Compose on Android, Compose Desktop (Skiko) on Win/Mac/Linux.  One UI codebase, idiomatic pen/touch/gesture input. **Not ImGui** (desktop/mouse-only, no Android pen story). **Not Swing/JavaFX** as UI.

The puppet canvas is a **GPU surface composited into Compose**:
- Desktop (shipped): LWJGL renders offscreen through a hidden GLFW context; the frame is copied into a Compose `Image` — there is no Swing/AWT interop in this path.  See `OffscreenPuppetService` / `:desktop` in the module graph.
- Android (not yet implemented): the target is a GLES surface composited the same way; today `:android` mounts the shared shell with a null viewport factory, so viewport areas render placeholders.  See `:android` in the module graph.

**Pen input (design target, not yet implemented):** no stylus/pressure/tilt code exists anywhere in the repo yet.  The intended split is Android stylus (`MotionEvent` pressure/tilt/hover) as the easier target, desktop JVM pen as the harder one (plan for JPen or Wintab/Windows Ink).

### Panels & docking

**One interface, desktop and tablet.** The same edge-docked UI runs everywhere, modeled on Krita and Clip Studio Paint's desktop mode — both run their full desktop UI on Android tablets, and that is sufficient. There is **no separate slim tablet UI to maintain.**  The tablet gets a touch-tuned _skin_ of the same interface, not a second front-end: larger hit targets, more panels collapsed-to-rail by default, the pen radial menu carrying tool switching, and the menubar folded into an overflow rather than a top strip.

**Blender-style area tree, not free-floating**:
- The window content is a **recursive binary-split tree of areas**.  Each area hosts exactly one **editor space** — the seven `SpaceKind`s are 2D viewport, UV editor, outliner, parameters, inspector, tool details, and history (undo stack, click an entry to jump to that state; deformers live in the outliner/parameters, not a separate space) — and the space is **agnostic and switchable at runtime** via a dropdown in the area's top-left header — any area can become any space (turn the outliner into a tiny viewport if you like, exactly like Blender).
- Areas split along any edge into nested areas and merge/close; **splitters are draggable** with a min-size clamp; a Blender-style **corner-drag gesture joins two adjacent areas** back into one.
- A window holds **multiple named workspaces as tabs** (create/duplicate/rename/reorder/reset), each with its own area tree; a single workspace or the whole layout can be exported/imported as text.
- Each area also has a **right-click context menu** mirroring its header actions plus "Change Editor Type."
- **Free-floating / tear-off windows remain out of scope** — everything is tiled inside the window; this keeps the layout engine small.
- An area's shape does not restrict which space it may host (no "tall panels left/right, wide panels top/bottom" rail constraint).

Concretely the shell is `org.umamo.ui.workspace`: a sealed `AreaNode` (split/leaf) tree per `Workspace`, rendered by a recursive Compose layout (`AreaTree`/`SplitContainer` + a draggable `Splitter`) — a tractable custom layout, not an IDE-grade free-float dock manager.  The 2D GL viewport stays platform code in the app, injected into a leaf via a `ViewportHost` seam (per-area surface kept alive across tree mutations with `movableContentOf`).

**Defaults carry the opinion.**  Each workspace ships a curated default area tree (Modelling = one 2D viewport; Texture = UV editor beside a 2D viewport); user rearrangement is the customization layer on top, persisted to settings `interface.layout`; "reset layout" reseeds the defaults.  This is how the tool stays focused while configurable — the workspaces own the good arrangements, so configurability is the escape hatch, not the primary interaction.

### The tool surface is small and bounded

2D rigging is not a tool-complex process.  So the panel inventory is knowable up front:

- **Canvas / viewport** — the GPU work surface.
- **Parts** — drawable + deformer hierarchy (with a Project view: source-image → model-image binding).
- **Parameters** — the rigging cockpit (sliders, multi-key 2D pad, keyform binding).
- **Inspector** — properties of the selected drawable / deformer / part.
- **Tool details** — context for the active tool.
- **History** — undo-history stack; click an entry to jump to that state.

Animation later adds wide, bottom-docked panels (timeline, keyframe/graph editors).  Because the set is small and known, the dock shell can be defined early without churn — but get the core rigging working in the default layout first; freeform rearrangement is a fast follow, not a prerequisite.

### Commands, key bindings & input

A central **action registry** is the spine of input: every operation is a named command (`mesh.subdivide`, `param.addKeyform`, `view.fit`, …).  Menus, the toolbar, the pen radial menu, and keyboard shortcuts **all dispatch through it** — nothing hardcodes a handler.  Design this in early; it's cheap up front and painful to retrofit after handlers are wired directly.

- **Editable, persisted keymaps** with presets — default, a **Cubism-like** preset (so migrants keep their muscle memory), and a **Blender-like** one (matches the editing model we're borrowing).  Detect conflicts; scope bindings per mode/context where a key's meaning differs.
- **Bindable inputs are not just the keyboard, in principle:** the design allows stylus barrel buttons, tablet express keys, and mouse buttons to bind to actions too, so a keyboardless tablet can still reach every command.  Not yet wired — today only keyboard chords resolve through the keymap.
- Store a logical **primary modifier** (→ Cmd on macOS, Ctrl elsewhere), not a literal "Ctrl"; bind by **key position**, not character, so shortcuts survive non-QWERTY layouts.
- A searchable **command palette** falls out almost free from the registry — a strong discoverability / onboarding aid for rigging's harder abstractions.
- **Pie/radial menus** (`PieMenuKind` — pivot mode, snap, merge-target, …) are a second dispatch mechanism alongside the palette, built for pen/touch reach rather than search.

Implemented (live code in `:ui`'s `org.umamo.ui.action`): `Command`/`CommandHandler`/`CommandRegistry` (provided as `LocalCommands`), chords modeled by `KeyChord` + `parseKeyChord` and resolved through a `Keymap` (key position + a logical primary modifier).  `ShellCommands` registers several dozen commands — mesh editing (grab/scale/rotate/duplicate/merge/rip/connect/vertex-slide, select modes, box/circle select, proportional editing), transform pivot/snap pies, undo/redo, mode toggling, workspace navigation, view toolbar/sidebar toggles, plus the shell-level `palette.toggle`/`area.dragCancel`/`view.fit`/`view.zoom*` and the app's `file.open`/`file.saveAs` — and `handleShellKey` is the live keyboard dispatch (key position → `KeyChord` → `Keymap.commandFor` → `registry.invoke`); the `CommandPalette` lists `registry.all()`.  New operations register a `Command` and bind a chord — never wire a handler into a widget.  The keymap is editable and persisted: `KeymapPersistence` resolves `default`/`cubism`/`blender` presets (settings `input.keybinding.preset`) plus per-command user overrides (`input.keybinding.overrides`), reactively, and a **keybindings editor** (in Settings) supports capture/reassign/conflict-detection/reset per command.

### Localization (EN / JA first-class)

The audience is heavily Japanese and the primary developer is bilingual — so **EN + JA are first-class from day one**, not retrofitted.  Compose Multiplatform's resources system (`stringResource`, per-locale resources) is the mechanism.

- **Externalize every UI string** from the start; no hardcoded literals.  Build the pipeline for *N* locales, not just two.
- **Localize chrome and display names — never format-level identifiers.** Parameter IDs like `ParamAngleX` are part of the CMO3/Cubism data model and stay verbatim for interop; `cdi3.json` display names and the user's own part names are user data, not ours to translate.  Two layers: Umamo's UI (we localize) vs the document's names/IDs (we don't).
- **CJK gotchas to test deliberately:** Skiko font fallback must cover JA glyphs on desktop *and* Android (bundle a CJK-capable font).  Don't assume EN string widths in the dock/panel layout.
- RTL is out of scope for v1, but don't structurally preclude it.

### Settings, storage & the app shell

The **settings/storage foundation is built first** — it backs keymaps, layout, theme, and recent files, so nothing hardcodes config that has to be ripped out later.  Two modules:

- **`:storage`** — per-OS config/data directories + file IO over **okio** (Kotlin's stdlib has no common `File`; `java.io.File` is JVM-only), and a `FilePicker` open/save-dialog contract backed by **FileKit** — one commonMain `FileKitFilePicker` over each OS's native dialog (Win32 `IFileDialog`, Cocoa, GTK/portal) plus Android SAF, replacing the old desktop-only AWT `FileDialog`.  Because FileKit already spans platforms there is no per-platform picker impl: desktop is done, and Android needs only `FileKit.init(activity)` in its `Activity` (it owns the result registry) — no separate SAF implementation.  The contract returns FileKit's `PlatformFile`, not an okio `Path`: Android SAF hands back a `content://` URI with no real filesystem path that a `Path` cannot model.  Platform factories `desktopAppStorage` / `androidAppStorage` resolve the dirs.
- **`:settings`** — a JSON tree of **bundled defaults ← user overrides** (← vendor extension defaults, later), addressed by **dotted keys** (`getString("interface.theme")`).  No caller passes a default — `defaultSettings.json` (a Compose-MP resource in `:ui`, shared cross-platform) is the single baseline.  Writes hit the *user* layer only, persist via `:storage`, and emit the changed key on a `changes: SharedFlow` (the reactive spine the UI / keymap / undo-History collect).  Held as a dynamic `JsonObject`, not typed `@Serializable` classes (settings are open-ended + merged); typed per-domain views layer on later.  Live keys in use today: `interface.theme`, `interface.layout` (the workspace area tree), `interface.window.{width,height,x,y,placement}`, `interface.viewport.showToolbar`/`showSidebar`, `input.keybinding.preset`/`input.keybinding.overrides` (the keymap), `viewport.zoomStepPercent`/`zoomStepCoarsePercent`/`selectionHighlightColor`/`grid.scale`/`grid.subdivisions`, the twelve `viewport.meshEdit.*` color keys, `localization.locale`, and `app.recentFiles`.  `app.launchCount` and `input.pen.backend` exist in `defaultSettings.json` but are genuinely unread/unwritten today — reserved, not wired.

Apps load settings at startup and provide the instance through a `LocalSettings` composition local — the desktop loads **synchronously** so the saved window state is ready before the (unconditional) window opens; `application {}` exits if it ever has zero windows, so an async settings gate would close it.  The **app shell is shared in `:ui`** (`org.umamo.ui.app.EditorApp`, jvmAndroidMain): the File / Edit / Workspace / Help menu bar, a Preferences window (`edit.preferences`, holds the keybindings editor and other settings sections), Help Credits/About, the `Document` layer (open via FileKit, Save As, recent files, workspace-layout import/export), live locale switching, live keymap reload on preset/rebind, and the per-document session wiring.  `:desktop` owns the window (geometry persisted to settings, title dirty-marker, the LWJGL offscreen render service); `:android` owns the Activity and mounts the same shell with a null viewport factory until the GLES renderer lands — Android does have renderer groundwork (`GpuRenderer.android.kt`: GLES20/30 grid + axis shaders), just not a puppet-deformation renderer or the Compose-image bridge yet.

**Use the foundation, don't reinvent it:** new config (a keymap preset, a panel layout, a pen binding) is a settings key under its namespace via `Settings`; file open/save goes through `FilePicker`; app-data paths come from `AppStorage`.

### Deformation (the core math)

Parameter-driven morph blending, **not** skeletal: `p = base + Σ wᵢ·Δᵢ`, multilinear interpolation across an N-dimensional grid of keyforms.  Warp deformers are FFD lattices; rotation deformers are a nesting pivot-transform hierarchy.  Weights are computed CPU-side per frame (cheap; depend only on parameters); the per-vertex weighted delta-sum runs in a vertex shader.  None of this maps onto any engine's built-in rig — it is ours.

### Mesh-editing interaction model

Mesh and keyform-deformation editing should feel like **sculpting, not CAD**, adopting Blender's UV-editor interactions.

- **Proportional editing — shipped.**  Moving one vertex drags its neighbours within an adjustable radius, with six selectable **falloff curves** (smooth / sphere / root / sharp / linear / constant) plus a geodesic "connected only" mode that follows mesh edges instead of screen-space distance (`module/edit/.../ProportionalEditing.kt`).
- **Brush radius for grab / smooth / relax — not yet implemented.**  The only existing brush-radius tool today is circle-select, a selection brush, not a vertex-manipulation one; there are no grab/smooth/relax sculpt operators yet.
- **Pen-pressure-driven brush radius / falloff strength — not yet implemented.**  No pressure/tilt input is wired anywhere in the codebase yet (see the Pen input note above); this stays the target pen-first advantage over Cubism's click-drag-per-vertex model once it lands.

### Project format & source model

CMO3 does **not** store the original `.psd`.  On import Cubism **decomposes** the PSD into an editable, equivalent representation: a `CLayeredImage` tree of `CLayerGroup`/`CLayer` nodes, each layer's pixels stored as a separate embedded PNG (`CImageResource` → `imageFileBuf*.png`), positionally mapped.  PSD is still the privileged *source* throughout the Cubism pipeline (mesh and model-image derive from it).  That privilege is incompatible with first-class **CLIP** and **Krita (.kra)** support, so:

- Umamo's **native project format is source-agnostic.** PSD, CLIP, and KRA are peer sources, each retaining its own **stable layer identity** (CLIP / Krita layer IDs; PSD's lyid layer id when present, else name + order).
- **CMO3 is an interop boundary, not the native format.** A shim unwraps CMO3's decomposed layer images (the `CLayeredImage` tree + per-layer PNGs) on import and synthesizes that decomposed, PSD-derived shape on export, so CMO3 round-trip still works.
- **Do not shim CLIP/Krita down to PSD on import.** That discards the stable layer IDs that make non-destructive re-import reliable — i.e.  it throws away the exact reason CLIP support is worth having.  The source-agnostic native model is what preserves them.

This refines the thesis: CMO3 read/write stays primary (Cubism interop), but it is a *conversion at the boundary* layered over a custom native format — not the format you work in.  The concrete container design lives in `docs/plans/art-sourcing-pipeline.md` § Phase G (currently leaning ZIP over TAR); nothing here is implemented yet — see Format Support.

### Re-import / source-art binding (the headline feature's hard problem)

"File refreshes" = **non-destructive re-import keyed on stable layer identity**.  Each drawable is bound to a source layer by a stable key (CLIP/Krita layer ID, or PSD's lyid when present; else PSD name/path + heuristics).  On a watched-file change, reconcile:
- **Matched layer:** update its atlas region; preserve mesh/deformers/keyforms where topology still fits.
- **Moved/resized art:** adjust extents/UVs if possible; otherwise flag for re-mesh.
- **Added layer:** new unbound drawable.
- **Removed/renamed layer:** **never silently delete rig work** — flag, fuzzy-match, and make reconciliation reviewable (a diff/merge step).

This is where Live2D's own reimport is lossy; doing it well is a competitive feature.

**Current status: designed, not wired.**  `:reimport` (`Reconciler`, `SourceBinding`/`LayerKey`, `SourceWatcher`) is interfaces and pure data types only — no reconcile implementation, no persisted source-layer key on `Drawable`, no platform file-watcher (desktop `WatchService`/Android `FileObserver`), and nothing calls into it from the UI yet.  This section describes the target behavior to build toward, not current behavior.

### Module graph (Gradle, Kotlin DSL; KMP)

```
:format       commonMain  — Cubism file family + art I/O.  CMO3 (read/write), PSD (read),
                            CLIP (read), KRA (read), MOC3, JSON family.
:reimport     commonMain  — source-art binding + non-destructive reconcile. → :format
:storage      commonMain  — app config/data dirs + file IO (okio) + native open/save dialogs
                            (FileKit, one commonMain FilePicker impl); platform factories in
                            jvmMain/androidMain (no expect/actual). → okio, FileKit
:settings     commonMain  — JSON settings engine: bundled defaults ← user overrides, dotted-key
                            get/set, persistence, change-event Flow. → :storage, kotlinx-serialization
:runtime      commonMain  — puppet model + CMO3 ingest. → :format
:edit         commonMain  — the editing session over the immutable PuppetModel: EditorSession
                            (snapshot-based undo History, selection + mode state, tool latches,
                            request buses), the sealed Change hierarchy, and the pure edit ops
                            (mesh topology/transforms, parameter edits, proportional editing).
                            → :runtime (api), kotlinx-coroutines (api)
:render       expect/actual— deformation eval (CPU) + renderer + morph-blend shaders.
                            → :runtime, :format (jvmAndroid).
                            Backend-agnostic eval + a `Renderer` *interface* + `PuppetTextures`
                            live in commonMain (compiler-isolated from any GPU API); PNG decode is
                            an expect/actual (`decodePngToRgba`: ImageIO / BitmapFactory); the CMO3
                            atlas extraction sits in jvmAndroidMain (takes a `Cmo3Model`, hence the
                            jvmAndroid-only :format dep); the GL/GLES impl in jvmMain/androidMain.
                            desktop=LWJGL, android=GLES (LWJGL is DESKTOP-ONLY).  Vulkan/Metal later
                            = new `Renderer` impls, not new modules — the interface is the backend
                            seam.
:ui           commonMain  — Compose Multiplatform editor UI (panels, tree, timeline, parameter
                            grid) AND the shared app shell: the viewport composables + gizmo overlay
                            (`org.umamo.ui.viewport`, over the `PuppetViewportService` seam) in
                            commonMain, and a jvmAndroidMain (same pattern as :format) hosting the
                            document/file layer + shared `org.umamo.ui.app.EditorApp` — the
                            JDOM-backed CMO3 codec forces those off commonMain.
                            → :render (api), :runtime, :edit, :settings, :storage, :format (jvmAndroid).
:desktop      jvm         — thin desktop entrypoint over the shared EditorApp: the window, settings
                            gate, and the LWJGL offscreen `PuppetViewportService` (GLFW hidden
                            context → Compose Image; no SwingPanel/AWTGLCanvas interop remains).
:android      android     — thin Android entrypoint (Activity) over the same shared EditorApp;
                            the GLES `PuppetViewportService` implementation is pending — viewport
                            areas render placeholders until it lands.
```

**On disk:** library modules live under `module/`, application targets under `app/` (e.g. `module/format`, `app/desktop`); everything else at the repo root is intentionally non-module.  A module's Gradle path (`:format`, `:desktop`) is kept **flat and decoupled from its folder** via `projectDir` in `settings.gradle.kts`, so dependency declarations stay terse (`project(":format")`) and *path ≠ directory ≠ package*.  Stretch-phase modules follow the same scheme.

Keep `:format`, `:reimport`, `:runtime`, `:edit`, `:ui` in `commonMain` so Android and desktop share them verbatim.  Platform-specific code (GPU contexts, FFI, pen, windowing) lives in `jvmMain` / `androidMain`.

## Fidelity contract

Two tiers — conflating them is the classic mistake.

- **Semantic validity — CMO3 (v1 primary).** Emit a valid CAFF container whose `main.xml` encodes the same object graph; the official editor's XML deserializer rebuilds it.  Whole-file byte-identity is **not** the bar and is not achieved: re-emitting an unedited corpus model differs from the original (one 4-byte field per CAFF entry), and an edit would change the bytes by construction anyway.  What IS asserted, and what the tests actually check, is **decompressed-content identity**: `main.xml` inflated out of a re-emitted file is byte-for-byte the `main.xml` inflated out of the original (`Cmo3FacadeTest`, `ModelDocumentTest`, `AllVersionsGateTest`), with zero unmodeled tags (`CoverageGateTest`).  Do not "restore" a whole-file byte-identity claim here — it was checked against the corpus and is false.  Our writer is at least deterministic (the same model re-saves to the same bytes), which the official editor's own writer is not: it stamps the wall clock into each compressed blob's zip timestamp (the corpus sample reads 2022-06-06 17:03:30), so we pin that field to the 1980 epoch instead — a deliberate divergence, see `docs/formats/CMO3.md` §1. **Round-trip is the bar:** open a corpus model, re-save, reopen in the official Cubism Editor, assert no semantic loss (mangled physics or a dropped deformer subtree kills the migration promise).
- **Preview parity — geometry (v1 primary).** The viewport deformation must visually match the Cubism Editor's own preview within tolerance.  Computed geometry is **bounded ULP / visually identical**, never bit-identical (scalar vs SIMD vs GPU diverge by construction).

Maintain a **golden corpus** of real `.cmo3` (and later `.moc3`) models.  Never add a format version to the supported set without a corpus sample exercising it.

## Tech stack (and why)

- **Kotlin + Kotlin Multiplatform + Gradle (Kotlin DSL).** Android reuse; large accessible contributor pool; JVM reflection + JDOM make the CMO3 XML serializer (read *and* write) tractable on both desktop and Android.
- **Compose Multiplatform** for UI (Android + Desktop, pen/touch native).  See Architecture.
- **FileKit** for native open/save dialogs — one Kotlin Multiplatform API over each OS's native picker (Win32 `IFileDialog`, Cocoa, GTK/portal) plus Android SAF, behind `:storage`'s `FilePicker` contract.
- **GPU:** LWJGL on desktop (GL/Vulkan + GLFW, behind interop); GLES/Vulkan on Android.  **Baseline:** GL 3.3 core (desktop, `#version 330 core` shaders, GLFW core-profile context) / GLES 3.0 (Android, needed for VAOs).
- **CMO3:** CAFF container codec + reflection-driven XML serializer (JDOM 1.x, Kotlin reflection); JVM-and-Android (`jvmAndroidMain`)
- **C++ Core FFI (stretch):** `:core-bridge` behind one interface — Panama (`java.lang.foreign`) on desktop, **JNI/NDK on Android** (Android does NOT ship the FFM API).  Never put `java.lang.foreign` in shared/Android code paths.

## Build & run

See README.md for build instructions.

The official Cubism Core/SDK/Editor are **external dependencies of the harness only** — never committed, vendored, or linked into shipping artifacts.  Never attempt to reverse engineer the official Cubism Core, SDK, or Editor.

## Code style

- **Indentation: tabs.** Set `.editorconfig` `indent_style = tab` and configure ktlint to match (overrides Kotlin's space default).
- **Naming:** `lowerCamelCase` functions/values, `PascalCase` types (matches Kotlin convention).  Naming for functions and variables (including local variables, function parameters, and loop counters) is verbose and descriptive: `bitFlip`, never `bf` or `bFlip`. Single-letter names are NOT acceptable for loop counters (`i`/`j`/`k`) — use a name that reflects what's being iterated (`pageIndex`, `slotIndex`, `wordIndex`, `lineIndex`). Single-letter dimensions are NOT acceptable: `h` could be `height` or `hue`, so write `width`, `height`.  Single-letter aliases for locals inside function bodies are NOT acceptable either (e.g. `b` for `backend`, `s` for `status`, `v` for a register value, `p` for a pointer) — give the local a descriptive name. Some abbreviation is okay as long as it is not ambiguous, such as `rect` for `rectangle`. Permitted short names: `x`, `y`, `z` for coordinates (universal graphics idiom); `r`, `g`, `b` for color channels ONLY when the enclosing function/struct name disambiguates (e.g. `paletteRgbToGrbi(uint8_t r, uint8_t g, uint8_t b, ...)` — the `Rgb` in the name makes the parameters self-documenting).  Use US English for spelling, example: "Color", not "Colour".

- **Comments:** English; about *why*, not *what*.
- **Format-citation discipline:** any code reading/writing a format field MUST cite the spec location, e.g. `// CMO3: <className> field <name>` or `// MOC3 v4 §3.2 @ +0x18`.  This is the format analogue of register-citation discipline — every byte traceable to docs, and it protects the clean-room story.
- Model format versions and deformer kinds as **sealed classes/enums**; match exhaustively so the compiler enforces coverage.  No accidental nullability.
- Prefer `expect`/`actual` over `if (platform)` for desktop/Android splits.
- Braces are MANDATORY on EVERY `if`, `else if`, and `else` — including single-statement bodies. The body always goes on its own line inside `{ }`.  The brace-less one-liner form is NOT allowed under any circumstances.

### Comments & documentation

- Public API doc comments and any non-obvious logic.
- Don't use Markdown bold and italic formating in comments and Docblocks.  Purely visual formatting is fine such as spaces, line breaks, indentation, and lists.
- Oxford(Serial) comma, two spaces after a period at the end of a sentence.
- **Every function gets exactly one docblock**, PHP/PHPDoc-style.
    - Open with `/**` (two stars) and close with ` */`; every interior line starts with ` * ` (leading space, star, space). This is what distinguishes a docblock from an ordinary `/* ... */` comment — reserve `/**` for documentation, use `/* */` for inline notes.
    - Structure: a one-line summary sentence, then (optionally) a blank ` *` line and a longer prose description, then the tag block. Order of tags: `@param` (one per parameter, in signature order), `@return`, then any of `@note` / `@warning` / `@see` / `@pre` / `@post` as needed. Omit `@return` for `void` functions. Omit `@param` when there are no parameters (`void`).
    - `@param type variable description`
    - Indent with tabs, consistent with the rest of the file.
    - Example 1:
        ```kotlin
        /**
         * Clears the current GL framebuffer to the given color. Assumes a context is current.
         *
         * @param Float red   Red Color
         * @param Float green Green Color
         * @param Float blue  Blue Color
         * @param ByteArray bytes Raw bytes from the file
         * @return ReturnedVariable
         */
        actual fun clear(red: Float, green: Float, blue: Float, alpha: Float) { }
        ```

## Guardrails (hard rules — contributors and AI assistants)

- **Re-import never destroys rig work.** Removals/renames are flagged and reviewable, never silently applied.
- **Docking is a Blender-style area tree:** the window is a recursive split tree of areas; each area hosts one editor space and is switchable to any space via its header dropdown; splitters are draggable.  No free-floating windows.  One shared desktop/tablet interface with a touch-tuned skin — never a second front-end.
- **All input dispatches through the action registry** — menus, toolbar, radial menu, and shortcuts never hardcode handlers, so a rebind takes effect everywhere.
- **All UI strings externalized; localize chrome/display, never format-level IDs** — `ParamAngleX` and the rest of the CMO3 identifiers stay verbatim for interop.
- **CMO3 round-trip must pass before any format-write change merges.** (Open → re-save → reopen in official editor → no semantic loss.)
- **Binary formats implement the shared `FormatCodec` contract** (`kind`/`matches`/`read`/`write`, dispatched via `FormatRegistry`); JSON sidecars stay text helpers.  No bespoke per-format read/write facade.
- **Two fidelity tiers respected:** semantic-validity for CMO3, bounded-ULP for geometry, byte-exact only for MOC3 binary.  Never relax format correctness; never chase bit-identical geometry.
- **No `java.lang.foreign` in shared/Android paths** (absent on Android).  FFI only behind `:core-bridge`.
- **No Swing/JavaFX as UI** (Compose only).  The desktop GL viewport is not Swing interop either — it renders offscreen via a hidden GLFW context into a Compose `Image`.
- **MOC3 version policy:** read v2–v6(+)

## Glossary

- **Drawable / art mesh** — textured triangle mesh with draw/render order, masks, blend mode, opacity, multiply/screen color.
- **Warp deformer** — FFD lattice; **Rotation deformer** — nesting pivot transform.
- **Parameter** — animation axis (e.g. `ParamAngleX`); **Keyform** — captured state at specific parameter values; runtime blends across the N-D keyform grid.
- **Source-art binding** — the stable-identity link from a rig drawable to a source layer; basis of non-destructive re-import.
- **File family** — `.cmo3` (editor source: CAFF container + XML-serialized object graph + embedded PNGs) · `.moc3` (runtime, C structs) · `model3/physics3/cdi3/pose3/exp3/motion3/userdata3.json` · texture atlas.