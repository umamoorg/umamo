# CLAUDE.md — Umamo

## What this is

Umamo is an open-source, cross-platform rigging editor for 2D puppet animation, built as a drop-in replacement for the Live2D Cubism Editor.  It is Kotlin Multiplatform, with one shared core across desktop and Android.

- **Platforms:** Windows, macOS, Linux (keyboard/mouse/pen) and Android tablets (pen/touch).  Rigging is a first-class workflow on all of them, not desktop-only.
- **Headline workflow:** draw in Clip Studio Paint, Photoshop, or Krita, save, switch to Umamo, and the rig refreshes against the updated art without losing rigging work.
- **Compatibility is the product:** Umamo reads and writes `.cmo3`, so a rigger can bounce one project between Umamo and the official Cubism Editor.  This is the primary adoption wedge.
- **Trustworthy preview:** the viewport deformation must match what a rigger would see in Cubism.

A change that trades away source-format compatibility or the refresh workflow for internal elegance is wrong by definition.  Raise it, don't silently take it.

## Hard rules

- **Re-import never destroys rig work.**  Removals and renames are flagged and reviewable, never silently applied.
- **CMO3 round trip must pass before any format-write change merges:** open, re-save, reopen in the official editor, no semantic loss.
- **Export never drops silently.**  Anything a target format cannot represent surfaces as an `ExportNotice`.
- **Fidelity tiers:** semantic validity for CMO3, bounded ULP for geometry, byte-exact only for the MOC3 binary.  Never relax format correctness; never chase bit-identical geometry.
- **Binary formats implement `FormatCodec`** and register in `FormatRegistry`.  No bespoke per-format read/write facade.
- **MOC3 version policy:** read v1–v6.
- **All input dispatches through the command registry.**  Menus, toolbars, pie menus, and shortcuts never hardcode a handler, so a rebind takes effect everywhere.
- **All UI strings are externalized.**  Localize chrome, never format-level ids: `ParamAngleX` and the rest of the Cubism identifiers stay verbatim.
- **Docking is a Blender-style area tree.**  No free-floating windows.  Desktop and tablet share one interface; the tablet gets a touch-tuned skin, never a second front-end.
- **Compose only.**  No Swing, JavaFX, or ImGui as UI, and no Swing/AWT interop for the viewport.
- **No `java.lang.foreign` in shared or Android paths** (Android has no FFM API).  Native FFI goes behind one dedicated bridge module, JNI on Android.
- **The official Cubism Core, SDK, and Editor are external dependencies of the test harness only:** never committed, vendored, or linked into shipping artifacts, and never reverse engineered.

## Formats

- **UMA** (read/write) — the native format and the app's Open/Save.  Spec: `docs/format/UMA.md`.
- **CMO3** (read/write) — the Cubism editor's source format.  An interop boundary, offered as Import/Export and never as Open/Save.  Spec: `docs/format/CMO3.md`.
- **MOC3** (read/write) — the Cubism runtime binary: memory-cast C structs, so the layout is byte-exact.  Spec: `docs/format/MOC3.md`.
- **PSD, CLIP, KRA** (read) — layered source art.  Specs: `docs/format/PSD.md`, `CLIP.md`, `KRA.md`.
- **JSON sidecars** — `model3`, `physics3`, `cdi3`, `pose3`, `exp3`, `motion3`, `userdata3`.  These are text, so they sit outside the codec contract as plain helpers.

Decisions that hold across them:

- **The native model is source-agnostic.**  PSD, CLIP, and KRA are peer sources, each keeping its own layer identity.  Never shim CLIP or KRA down to PSD on import: that discards the stable layer ids that make re-import reliable.
- **CMO3 does not store the source file.**  It holds a decomposed layer tree with one embedded PNG per layer; `:interop` unwraps that shape on import and synthesizes it on export.
- **CMO3 export is a state-based reconcile.**  The retained graph is re-imported as a baseline, the session's model is diffed against it, and only what changed is patched.  A document with no retained graph (any `.uma`, a MOC3 import, imported artwork) runs the same reconcile against a freshly synthesized blank graph.  See `docs/format/CMO3.md` §6.
- **UMA editor state is never model input.**  `editor/state.json` restores view state without creating an undo step, and a save is laid over the file as opened, so entries this reader cannot interpret are carried through (`docs/format/UMA.md` §3).
- **Import Artwork adds to the open document** as one undo step; it never replaces it.  The editor always has a document.

## Fidelity contract

Two tiers; conflating them is the classic mistake.

- **CMO3: semantic validity.**  The bar is the round trip: open a corpus model, re-save, reopen in the official Cubism Editor, no semantic loss.  What the tests assert is decompressed-content identity: `main.xml` inflated from a re-emitted, unedited file is byte-for-byte the original's, with zero unmodeled tags.  Whole-file byte identity is not the bar and does not hold: the container differs per entry, and each compressed blob carries a wall-clock zip timestamp, as the editor's own does.  Do not claim it, and do not pin those bytes for determinism.  The official reader is fragile, so when in doubt, write what the editor writes; a convenience of ours never outranks matching its bytes.  See `docs/format/CMO3.md` §1.
- **Geometry: preview parity.**  Viewport deformation matches the Cubism Editor's preview within tolerance: bounded ULP, never bit-identical (scalar, SIMD, and GPU diverge by construction).

A golden corpus of real models backs both (`test/corpus`, gitignored).  Never add a format version to the supported set without a corpus sample exercising it.

## Architecture

### Modules

Library modules live under `module/`, apps under `app/`.  Gradle paths are flat (`:format`) and decoupled from the folder via `projectDir` in `settings.gradle.kts`.  Packages are `org.umamo.<module>` for libraries, `org.umamo.<product>.<platform>` for apps (`org.umamo.editor.desktop`), and `org.umamo.cli` for the CLI.

- `:geometry` — pure vector geometry: exact predicates, constrained Delaunay, refinement.  Knows no pixels, puppets, or formats.
- `:runtime` — the immutable `PuppetModel`, typed ids, the keyform grid algebra, and the atlas domain.
- `:format` — the container codecs and source-art readers.
- `:interop` — every format ↔ `PuppetModel` conversion: ingest, the model diff, the CMO3 export reconcile, the source-art bridge, and the art mesher.
- `:reimport` — the non-destructive reload pipeline: reconcile, planner, file watcher, matcher.
- `:edit` — `EditorSession` over the immutable model, with snapshot undo and one subpackage per editing domain.
- `:render` — the CPU deformation eval, the puppet renderer, and its shaders.
- `:storage`, `:settings` — app directories, file IO, and native dialogs; the JSON settings engine.
- `:ui` — the Compose editor UI and the shared app shell.
- `:desktop`, `:android` — thin entrypoints over the shared shell.
- `:cli` — a headless diagnostic tool.

Deliberate boundaries:

- **Shared modules stay in `commonMain`** so desktop and Android share them verbatim.  Platform code (GPU contexts, windowing, pen, FFI) lives in `jvmMain` / `androidMain`.  `jvmAndroidMain` exists only where the JDOM and reflection behind the CMO3 codec force it.
- **`:geometry` and `:runtime` have no project dependencies**, and `:runtime` has no format knowledge.  Authored atlas placement lives in `:runtime` because the CMO3 export needs it and cannot see `:render`.
- **`:edit` and `:render` are siblings over `:runtime`.**  Editing logic over `EditorSession` belongs in `:edit`; `:ui` keeps only the pointer and composition bookkeeping that feeds it plain data.  The one exception is `org.umamo.ui.transform`, which needs `:render`'s eval.  Inside `:edit`, a domain subpackage imports the root package, never the reverse.
- **The CPU eval stays in `:render` on purpose**, beside the GPU deform shaders it mirrors and is diffed against, even though it would move to `:runtime` cleanly.
- **`:render` knows no container format.**  `:interop` walks a document's atlas pages and hands over neutral page data.  `PuppetRenderer` makes no GL calls: every GPU operation goes through `RenderDevice`, and a backend is one `RenderDevice` implementation.  The baseline is GL 3.3 core on desktop and GLES 3.0 on Android; LWJGL is desktop-only.
- **`:cli` depends on neither `:ui` nor `:edit`.**

### Build logic

Build logic that more than one module needs is a convention plugin in `gradle/build-logic` (an included build, not `buildSrc`), never a copied block:

- `umamo.kmp-jvmandroid` — the `jvmAndroidMain` source-set group.
- `umamo.kmp-ios-gate` — makes `check` compile the iOS target, which is what enforces commonMain purity.
- `umamo.test-corpus` — corpus paths for sample-gated tests, declared in a `umamoTestCorpus { }` block.  Never hand a Test task a sample path by hand.

### UI

- **Viewport.**  The puppet canvas is a GPU surface composited into Compose: the desktop renders offscreen through a hidden GLFW context and copies the frame into a Compose `Image`.
- **Area tree.**  The window is a recursive binary-split tree of areas.  Each area hosts one editor space (`SpaceKind`) and can switch to any other from its header; an area's shape never restricts which space it may host.
- **Defaults carry the opinion.**  Each workspace ships a curated area tree, and user rearrangement is a layer on top, persisted to settings.  Configurability is the escape hatch, not the primary interaction.
- **Register, don't wire.**  Properties tabs come from `PropertyTabRegistry`, never a render switch.  Row drag-and-drop inside a space uses the shared kit in `org.umamo.ui.workspace.rowdrag`; a space adds only its payload type and its drop rule.
- **`org.umamo.ui.tracks` is domain-agnostic** so a time-based dope sheet can reuse it.  Nothing about parameters, keyform grids, or `PuppetModel` may appear there (`TrackAxis` is the only place a domain appears), and the `dopesheet` serialization key is reserved for that editor.

### Commands and input

- Every operation is a named `Command` (`mesh.subdivide`, `view.fit`) in the `CommandRegistry`.  A new operation registers a command and binds a chord; never wire a handler into a widget.
- Command tables live in `org.umamo.ui.workspace.commands`, one file per domain, registered through `registerAll`.  A `Command(...)` built anywhere else is a bug.
- A command's `spaces` and `hint` filter the palette and the status bar only; dispatch and the keymap never read them.  Declare `spaces` from what the handler's routing really does, never narrower.
- `CommandRouting.kt` is the one place a command resolves which area the pointer means.  There is no fallback: a command that needs a viewport and finds none under the pointer does nothing.  A request that must run in one specific area carries that area's id in its payload.
- Keymaps are editable and persisted, with `default`, `cubism`, and `blender` presets.  Chords store a logical primary modifier (Cmd on macOS, Ctrl elsewhere) and bind by key position, not character, so shortcuts survive non-QWERTY layouts.

### Localization

- EN and JA are first-class.  Strings go through Compose resources (`stringResource`).
- Two layers: Umamo's chrome is ours to localize; the document is not.  Parameter ids, `cdi3.json` display names, and the user's own part names are user data and stay verbatim.
- Don't assume EN string widths in layout.  CJK glyph coverage needs a bundled font on desktop and Android.  RTL is out of scope, but don't structurally preclude it.

### Settings and storage

- New config is a settings key under its domain namespace, read through `Settings` (`LocalSettings`).  No caller passes a default: `defaultSettings.json` in `:ui` is the single baseline, and writes hit the user layer only.
- File open and save go through `FilePicker`, which returns FileKit's `PlatformFile` rather than an okio `Path`, because Android SAF hands back a `content://` URI with no filesystem path.  App data paths come from `AppStorage`.
- The desktop loads settings synchronously before its window opens: `application {}` exits when it has zero windows, so an async settings gate would close the app.

### Deformation

Parameter-driven morph blending, not skeletal: `p = base + Σ wᵢ·Δᵢ`, multilinear interpolation across an N-dimensional grid of keyforms.  Warp deformers are FFD lattices; rotation deformers are a nesting pivot-transform hierarchy.  Weights are computed on the CPU per frame (they depend only on parameters); the per-vertex weighted delta sum runs in a vertex shader.  None of this maps onto an engine's built-in rig.

### Re-import

A reload is a non-destructive reconcile keyed on layer identity.

- **Identity has one representation:** the reader's `LayerId.raw`, with the reader saying how strong it is (`SourceLayer.idIsStable`).  CLIP and KRA ids are stable; PSD uses its lyid when present, else name/path and order.  The binding persists on the tile (`AtlasTile.source`), and the document lists its artwork files with their pixel-free layer inventories (`PuppetModel.sources`), the baseline a reconcile diffs against.
- **Matched layer:** its tile is superseded by a new tile, never mutated.  Pixels are immutable per tile id, so undo and the CMO3 export both follow the lineage.  Mesh, deformers, and keyforms are kept where the topology still fits.
- **Moved or resized art:** adjust extents and UVs if possible; otherwise flag for re-mesh.
- **Added layer:** a new unbound drawable.
- **Removed or renamed layer:** left alone and flagged for review, with fuzzy-match suggestions.
- A file edited while the document was closed is reported at open, never auto-reloaded.

### Design targets

Directions the design commits to.  None of this says the code is there; check before building on it.

- **Android viewport:** a GLES surface composited into Compose the same way the desktop's is.
- **Pen input:** pressure, tilt, and hover.  Android stylus (`MotionEvent`) is the easier target; desktop JVM pen (JPen, or Wintab/Windows Ink) the harder one.
- **Sculpting, not CAD:** mesh and keyform editing borrow Blender's UV-editor interactions: proportional editing with falloff, then grab/smooth/relax brushes with pressure-driven radius and strength.
- **Keyboardless reach:** stylus barrel buttons, tablet express keys, and mouse buttons bind to commands the way keys do.

## Verifying a change

CI runs `./gradlew build -Dumamo.requireGl=true`: every compile, every test, `ktlintCheck`, and the purity checks.  Run that before calling a branch done, and the narrowest task that covers the change while iterating.  README.md has the run commands and the WSL2 setup.

- **Format first.**  Run `./gradlew ktlintFormat` before a build or lint, and again afterward if the lint still reports something.  It fixes most code style violations by itself; what it leaves needs a hand fix.
- **Tests** are `:<module>:jvmTest`, for example `./gradlew :ui:jvmTest --tests '*CommandDispatchTest'`.
- **Read the outcome, not the exit code.**  Piping Gradle through `grep` or `tail` returns the last command's status, so `BUILD FAILED` reads as success: use `set -o pipefail` and read the output.  A task marked `SKIPPED` verified nothing.
- **A gated test can pass while asserting nothing.**  A test whose corpus sample or GL context is missing self-skips and still reports PASSED, so confirm the test you are relying on really ran.  `-Dumamo.requireGl=true` turns a missing GL context into a failure.
- **commonMain purity has three nets,** and `build` runs all of them: the `checkCommonSourcePurity` scan, the iOS compile, and `:<module>:compileCommonMainKotlinMetadata`.  The metadata compile rejects platform-only stdlib APIs that the JVM and iOS compiles both accept.  The two compiles only cover `:geometry`, `:runtime`, `:format`, `:interop`, and `:render`; the other modules report them `SKIPPED`, so their common code is checked by the scan and by inspection.

## Code style

ktlint enforces much of this: its standard rules plus Umamo's own rule set in `gradle/ktlint-rules`.  `./gradlew ktlintFormat` applies whatever can be fixed mechanically; the rest is yours to follow by hand.

- **Indentation: tabs** (`.editorconfig`; ktlint follows it).  US English spelling: "Color", not "Colour".
- **Naming:** `lowerCamelCase` functions and values, `PascalCase` types.  Names are verbose and descriptive: `bitFlip`, never `bf`.  No single-letter names: not for loop counters (`pageIndex`, not `i`), not for dimensions (`width`, `height`), and not for local aliases (`backend`, not `b`).  An unambiguous abbreviation is fine (`rect`).  Permitted short names are `x`, `y`, `z` for coordinates, and `r`, `g`, `b` for color channels when the enclosing name disambiguates them (`rgbToHsv(r, g, b)`).
- **World axis names:** the viewport convention is X right, Y forward, Z up, so a name that says it is world space calls its vertical axis Z (`worldOriginZ`, `screenToWorldZ`), never `worldY`.  Space-agnostic math (`[x, y]` arrays, `MeshBounds`, GLSL `vec2`), canvas / screen / UV / NDC names, and the deform eval's pre-negation space keep y.
- **Braces** are mandatory on every statement `if`, `else if`, and `else`, single-statement bodies included, with the body on its own line.  A value `if` (`val side = if (…) … else …`) keeps its one-line form.  Enforced by the `umamo:statement-if-braces` ktlint rule, which `ktlintFormat` applies.
- **Blank lines around control flow:** a blank line goes before and after every statement-level `if`, `when`, `for`, `while`, `do`-`while`, and `try` block, and before every `return`, `throw`, `break`, and `continue`, in lambda bodies too, except where the statement opens or closes its block.  Comment lines above a statement belong to it, so the blank line goes above them.  A value `if`/`when` and a jump inside an expression (`?: return`) are part of their statement, not blocks.  Enforced by the `umamo:blank-line-around-control-flow` ktlint rule; `ktlintFormat` inserts whatever is missing.
- Model format versions and deformer kinds as **sealed classes or enums** and match exhaustively, so the compiler enforces coverage.  No accidental nullability.
- Prefer `expect`/`actual` over `if (platform)` for desktop/Android splits.
- **Format-citation discipline:** any code reading or writing a format field cites the spec location, e.g. `// CMO3: <className> field <name>` or `// MOC3 v4 §3.2 @ +0x18`.  Every byte stays traceable to the docs, which protects the clean-room story.

### Comments and docblocks

- Comments are in English and say why, not what.  Comment non-obvious logic.
- The `comment-cleanup` skill (`.claude/skills/comment-cleanup/SKILL.md`) is the reference for what a comment must not be: stale, a plan or phase label, a bug's discovery story, a comparison to an unrelated tool, or a docblock that narrates the body instead of stating its purpose.  Write to that standard, and run the skill over a branch before it merges.
- Oxford comma, and two spaces after a period that ends a sentence.
- No Markdown bold or italics in comments or docblocks.  Purely visual formatting (spaces, line breaks, indentation, lists) is fine.  Enforced by the `umamo:no-comment-emphasis` ktlint rule, which `ktlintFormat` applies.
- **Every function gets exactly one docblock**, PHPDoc-style: open with `/**`, start every interior line with ` * `, close with ` */`, indented with tabs.  Reserve `/**` for documentation and use `/* */` for inline notes.
- An override needs a docblock only when it does something its original's docblock does not already say; a short, self-explanatory override goes without.  Test sources are not required to carry docblocks.  The `umamo:function-docblock` ktlint rule holds the rest: every named function outside test sources that is not an override, local functions included.
- Structure: a one-line summary, then optionally a blank ` *` line and a longer description, then the tags.  Tag order is `@param` (one per parameter, in signature order, written `@param Type name description`), `@return` (omitted for `Unit`), then `@note` / `@warning` / `@see` / `@pre` / `@post` as needed.  A function type is written `Function`.
- The `umamo:docblock-tags` ktlint rule holds a docblock's tags to its signature; it reports and cannot fix, since a missing tag needs a written description.  Test sources are the one exception to the full tag block: a test docblock may be a summary alone, but the tags it does have must match the signature (`umamo_docblock_tags_mode` in `.editorconfig`).

```kotlin
/**
 * Maps a screen pixel y back to world space.
 *
 * @param Float screenY The screen y in pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportHeight The viewport height in pixels.
 * @return Float The world-space z (up).
 */
fun screenToWorldZ(screenY: Float, camera: ViewportCamera, viewportHeight: Int): Float
```
