# Portability — Design Roadmap

Status: roadmap (2026-07-15). This is a multi-session design backbone, not a single implementation plan. Each lettered phase below is sized to become its own planning session. It expands the terse `## Render` entries in [TODO.md](../../TODO.md) ("Android GLES renderer backend", "MacOS/iPadOS renderer backend") and supersedes the platform table in [README.md](../../README.md), which is inaccurate — see § Corrections to the tree.

## Purpose

Decide what it costs to run Umamo on every platform the thesis names — Windows, Linux, macOS, Android tablets, and iPadOS — and in what order to pay for it. The caller's framing was "as portable as possible; full Kotlin/Native if that is what the goal needs." This document answers what portability is already paid for, what the remaining bill is, and which single decision the iPad hinges on.

## The finding

Kotlin/Native is needed for iPadOS and nothing else. Android and macOS are both JVM/ART targets — neither needs a line of it. So "should we go Kotlin/Native" is not the real question. The real question is narrower and sharper:

> Is iPadOS worth de-reflecting the CMO3 codec?

One dependency choice — JDOM 1.x plus `kotlin-reflect` in the CMO3 XML serializer — is what created `jvmAndroidMain`, and it is the only hard Kotlin/Native blocker in the tree. Everything downstream of it (`Document`, `EditorApp`, `extractPuppetTextures`) is pinned off `commonMain` by association, not by its own JVM use.

The rest of the portability story is unusually healthy. `:ui/commonMain` is 138 files and 100% JVM-clean. `:edit`, `:settings`, `:runtime`, and `:reimport` are clean end to end. The PSD/CLIP/KRA pixel decoders are hand-written portable Kotlin with no host image library anywhere. That discipline is real and it has already been paid for — but nothing currently enforces it (§ Phase A).

## Invariants this roadmap must not break

Locked in [CLAUDE.md](../../.claude/CLAUDE.md). Every phase is checked against them.

1. CMO3 read/write is the adoption wedge. Semantic validity is the bar; the current byte-for-byte round-trip of an unedited file is a strong correctness signal, not a contract — but it is not to be casually discarded either.
2. Two fidelity tiers stay separate: semantic validity for CMO3, bounded-ULP for geometry, byte-exact for MOC3 binary. A second renderer backend must not chase bit-identical geometry, and must not relax CMO3 correctness to get there.
3. One shared desktop/tablet interface with a touch-tuned skin — never a second front-end. An `app/ios` is an entrypoint over the same `EditorApp`, exactly as `app/android` is.
4. `expect`/`actual` over `if (platform)` for platform splits. (One live violation: [OffscreenGlContext.kt:48-53](../../app/desktop/src/jvmMain/kotlin/org/umamo/editor/desktop/viewport/OffscreenGlContext.kt#L48-L53) string-sniffs `os.name` — defensible, since both branches are JVM, but worth naming.)
5. No `java.lang.foreign` in shared/Android paths; FFI only behind `:core-bridge`. A zlib cinterop for Native (Phase C) sits behind an existing `expect`, not a new FFI facade.
6. Binary formats implement `FormatCodec` dispatched through `FormatRegistry`; JSON sidecars stay `String`-shaped helpers. A de-reflected CMO3 codec keeps the same face.
7. Localize chrome and display names, never format-level identifiers.

## Where we are today (the starting line)

Verified against the tree on 2026-07-15.

### Targets

All eight library modules declare exactly `jvm()` + `android {}`. There is no `iosArm64`, no `iosSimulatorArm64`, no `macosArm64`, no `linuxX64`, no `native()` anywhere in the build, and no `iosMain` / `appleMain` / `nativeMain` directory on disk. **No Kotlin/Native target has ever been configured.**

**UPDATE (2026-07-16): `:format`, `:runtime`, and `:render` now declare `iosArm64()`**, each wired into `check` for main AND test compiles (compile-only on Linux/CI — klibs need no Xcode). For those three, commonMain purity is a compiler guarantee. `:render/iosMain` carries the `MetalRenderDevice` stub — the iPadOS port's entry point — and `:render/androidMain` the `GlesRenderDevice` stub. Phase A's remaining candidates are `:settings`, `:edit`, `:reimport`, and `:ui`/`:storage` (the latter two need actuals — Phase F).

### Portable today, zero work

| Module      | Source set | Files | Note                                                                                                                                                                                                                                                                                                |
| ----------- | ---------- | ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `:ui`       | commonMain | 138   | 100% clean. No `java.*`, `javax.*`, `System.*`, `Thread`, `String.format`, Skia, AWT/Swing, or `::class.java`. The one `@Volatile` ([LiveParams.kt:18](../../module/ui/src/commonMain/kotlin/org/umamo/ui/viewport/LiveParams.kt#L18)) imports `kotlin.concurrent.Volatile`, the multiplatform one. |
| `:edit`     | commonMain | 30    | Zero hits. Build declares no platform source sets.                                                                                                                                                                                                                                                  |
| `:settings` | commonMain | 1     | Zero hits. Build declares no platform source sets.                                                                                                                                                                                                                                                  |
| `:runtime`  | commonMain | 12    | Zero hits.                                                                                                                                                                                                                                                                                          |
| `:reimport` | commonMain | 3     | Zero JVM leakage, no expect/actual. Compiles to Native the moment `:format` does.                                                                                                                                                                                                                   |
| `:format`   | commonMain | 44    | The whole MOC3 subsystem, the JSON family, `SourceArt`, `BinaryReader`/`LittleEndianReader`. Strings via multiplatform `decodeToString()`, not JVM charsets.                                                                                                                                        |
| `:render`   | commonMain | 18    | `eval/`, `pick/`, `ViewportCamera`. Every import is `org.umamo.runtime.*` or `kotlin.math.*`.                                                                                                                                                                                                       |
| `:storage`  | commonMain | 5     | Pure okio + FileKit.                                                                                                                                                                                                                                                                                |

That is the bulk of the codebase — roughly 250 files that would compile to Native today.

### The JVM-bound set — why `jvmAndroidMain` exists

```
	:format/jvmAndroidMain (28 files)  ◄── the root cause
	│     CMO3 serializer: JDOM 1.x + kotlin-reflect
	│     art readers:     java.util.zip.Inflater, ZipInputStream
	│
	├──► :render/jvmAndroidMain (1 file)
	│      Cmo3PuppetTextures.kt — extractPuppetTextures(Cmo3Model)
	│
	└──► :ui/jvmAndroidMain (3 files)
	       Document.kt (111)   ── names FormatRegistry, Cmo3Model, extractPuppetTextures
	       EditorApp.kt (433)  ── ZERO JVM API.  Pinned only by naming Document/Cmo3.write
	       AppLocale.jvmAndroid.kt (19) ── genuinely platform-bound, already seamed
```

The reflection sites, precisely:

- [Serializers.kt:166-170](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/serialize/Serializers.kt#L166-L170) — instantiation via `kClass.java.getDeclaredConstructor()` + `isAccessible`.
- [Serializers.kt:173-183](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/serialize/Serializers.kt#L173-L183) — `declaredMemberProperties` + `javaField`, sorted by `kClass.java.declaredFields` order. **The field ordering is load-bearing for byte-exact `main.xml`.**
- `SerializeEngine.kt` — `findAnnotation` at `:139`/`:227`, `kClass.java.isEnum`/`superclass` at `:211`/`:219-221`. All six annotations are `@Retention(RUNTIME)`, which is meaningless on Native.
- [Cmo3.kt:46-55](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/Cmo3.kt#L46-L55) — a separate raw `java.lang.reflect` field walk, used only to find `CImageResource`s. Self-contained; replaceable by a generated visitor without touching the XML format.

The single most important mitigating fact: **there is no `Class.forName` anywhere in the codebase.** Grepping `Class.forName`, `ClassLoader`, and `ServiceLoader` across every source set returns zero hits. Tag→class resolution is a plain map read ([SerializeEngine.kt:144](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/serialize/SerializeEngine.kt#L144), `tagToClass[tag]`) populated by [GeneratedRegistration.kt](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/serialize/gen/GeneratedRegistration.kt) — 168 explicit `registry.register(X::class)` calls. The registry is already the shape a compile-time generator needs. Phase G is a large job, but it is not an open-ended one.

### Dependency portability matrix

| Dependency            | Version | Native / iOS | Note                                                                          |
| --------------------- | ------- | ------------ | ----------------------------------------------------------------------------- |
| Compose Multiplatform | 1.11.1  | yes          | iOS is a supported target; Skia ships there.                                  |
| okio                  | 3.16.2  | yes          | `FileSystem.SYSTEM` works on Native.                                          |
| kotlinx-serialization | 1.11.0  | yes          |                                                                               |
| kotlinx-coroutines    | 1.10.2  | yes          |                                                                               |
| FileKit               | 0.14.2  | yes          | iOS wraps `NSURL`; see the bookmark caveat in Phase F.                        |
| SQLDelight            | 2.3.2   | yes          | A native driver exists; the CLIP seam is the most mechanical port in the set. |
| **JDOM**              | 1.1.3   | **no**       | JVM-only. Phase G.                                                            |
| **kotlin-reflect**    | 2.4.0   | **no**       | Does not exist on Native. Phase G.                                            |
| **LWJGL**             | 3.4.1   | **no**       | Desktop JVM only. Phase H.                                                    |
| JNA                   | 5.19.1  | no           | Already correctly isolated to `:desktop` window chrome.                       |

Only two dependencies actually block Native, and they are both inside the CMO3 codec.

### Corrections to the tree

Two documented claims are wrong and should be fixed as part of Phase A.

- **[README.md:41](../../README.md#L41)** — the iPadOS row reads "Application and UI loads, but there is no renderer and JDOM is incompatible." Nothing loads. No module declares an iOS target, so the app cannot be built for iPadOS at all. That row describes an aspiration, not the committed tree. ("JDOM is incompatible" is correct, and is the one true half of the sentence.)
- **[TODO.md:92](../../TODO.md#L92)** — "MacOS/iPadOS renderer backend - Core GL(LWJGL CGL)" conflates two unrelated problems. LWJGL cannot run on iPadOS; there is no JVM there. macOS is a JVM/threading fix that keeps the entire existing GL codebase; iPadOS is Kotlin/Native + Metal + no LWJGL + no OpenGL. Filing them as one line understates iOS by roughly this whole document. Split into Phase E and Phase H.

A third, minor: [CLAUDE.md § Settings, storage & the app shell](../../.claude/CLAUDE.md) describes `:storage`'s platform factories as "no expect/actual". The factories are still plain functions with deliberately different signatures (correct — Android's takes a `Context`), but the module is no longer expect/actual-free: [PlatformFiles.kt:16](../../module/storage/src/commonMain/kotlin/org/umamo/storage/PlatformFiles.kt#L16) added `platformFileFromSavedPath`. One-word fix.

## The phases

```
	PORTABLE TODAY (~250 files, no work)
	:edit  :settings  :runtime  :reimport
	:ui/common (138)  :render/common  :format/common  :storage/common
	                 │
	    (A) LOCK IT IN — native targets on the clean leaves
	        turns discipline into a compiler guarantee
	                 │
	 ┌───────────────┼────────────────┬──────────────────┐
	 ▼               ▼                ▼                  ▼
	(B) Document   (C) zlib seam   (F) iosMain      (G) CMO3 without
	    seam           CaffZip +       actuals          reflection
	 shell moves       Inflater     :ui ×12,        ◄── THE DECISION
	 to commonMain     sites        :storage, l10n      gates iPad documents

	RENDERERS — one per backend, unavoidable, independent of the above
	(D) Android GLES ──► reveals the real backend seam ──► (H) iPadOS Metal
	(E) macOS offscreen context — a JVM fix, keeps all GL code
```

Phases A–F are small. G and H are the entire cost, and **only G is unique to iPadOS** — D and E are owed for Android and macOS regardless.

---

## Phase A — Lock in the portability we already have

Goal: make the existing cleanliness a constraint the compiler enforces, rather than a discipline that survives on care.

`:ui/commonMain` being 138 files of zero JVM leakage is a genuine achievement. But nothing checks it. There is no non-JVM target in the graph, so nothing breaks the build the day someone reaches for `java.util.Locale` in a panel. Every week without a Native target is a week that cleanliness is luck.

Add `linuxX64()` (cheapest — no Apple toolchain needed on the current dev machine) or `iosSimulatorArm64()` to the four modules with no UI, no GL, and no JVM leakage: `:settings`, `:edit`, `:runtime`, and `:reimport`. They should go green on the first try. `:format`'s commonMain half is a stretch goal for this phase — it is clean, but `:format` cannot declare a Native target until `jvmAndroidMain` stops being unconditionally compiled into every target, which is really Phase G's problem.

Depends on: nothing. Do this first.

Decisions: which token target to add (recommend `linuxX64` — it costs nothing on Linux/WSL2 CI and catches every `java.*` import just as well as an Apple target would); whether to add it to `:storage` too (it has one `expect` needing a trivial Native actual, so it is a Phase-F item, not an A item).

Also in scope: the three doc corrections above (README platform table, TODO § Render split, CLAUDE.md `:storage` note).

Session scope: small. A handful of build-script lines and a doc pass.

---

## Phase B — The `Document` seam

Goal: move the entire app shell to `commonMain` by abstracting the one thing pinning it.

[EditorApp.kt](../../module/ui/src/jvmAndroidMain/kotlin/org/umamo/ui/app/EditorApp.kt) is 433 lines and contains **no JVM API at all** — it imports no `java.*`, `javax.*`, or `android.*` whatsoever; every import is Compose, FileKit, coroutines, or `org.umamo.*`. It lives in `jvmAndroidMain` purely because it names `Document` / `Cmo3Document` (`:29-31`), `Cmo3.write` (`:193`), and `FileKind.Cmo3` (`:178`) — its only two `:format` imports are `FileKind` (`:19`) and `Cmo3` (`:20`). Abstract `Document` behind a commonMain interface plus a `DocumentLoader` seam and the shell — area tree, panels, menus, palette, keymap, preferences, spaces — becomes `commonMain`, leaving only the loader in `jvmAndroidMain`.

This is the highest-leverage refactor available, because it converts "CMO3 blocks the iPad app" into "CMO3 blocks _opening files_ on the iPad." An iOS target could build and run a document-less shell _before_ the Phase G decision is made — which is exactly how you find out whether the iPad is worth Phase G.

The codebase is already asking for this. `DocumentOpenFailure` sits in `commonMain` in the same package for precisely this reason and [says so in its docblock](../../module/ui/src/commonMain/kotlin/org/umamo/ui/document/DocumentOpenFailure.kt#L23): "Lives in commonMain (unlike the JVM-bound document loader) so the shell's command handler can receive it on every platform." `Files.kt` and `RecentFiles.kt` are already commonMain too. Only `Document.kt` is not.

Depends on: nothing. Pure refactor, no behavior change, testable by the existing suite.

Decisions: whether `DocumentLoader` is an interface injected into `EditorApp` (matching the `viewportServiceFactory` / `ViewportHost` pattern already used) or resolved through `FormatRegistry`; what a loader-less platform returns (recommend `DocumentLoad.Failed` with a "format not supported on this platform" reason, which the shell already renders).

Session scope: small-to-medium. One session.

---

## Phase C — The compression seam

Goal: get zlib/zip off `java.util.zip` so `:format`'s art readers and the CAFF container can compile to Native.

Four sites, three of them decompression-only:

| Site                                                                                                                    | Framing                                                         | Need                            |
| ----------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- | ------------------------------- |
| [CaffZip.jvm.kt:17-33](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/caff/CaffZip.jvm.kt#L17-L33) | single-entry zip container (entry literally named `"contents"`) | read + **write**, level control |
| [PsdRaster.kt:234-255](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/psd/PsdRaster.kt#L234-L255)       | raw zlib stream                                                 | inflate only                    |
| [ClipRaster.kt:328-346](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/clip/ClipRaster.kt#L328-L346)    | `[4-byte LE len][zlib stream]`                                  | inflate only                    |
| [KraReader.kt:18](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/kra/KraReader.kt#L18)                  | zip archive walk                                                | zip read                        |

`CaffZip` already is an `expect object` ([CaffZip.kt:13](../../module/format/src/commonMain/kotlin/org/umamo/format/cmo3/caff/CaffZip.kt#L13)) with its only actual in `jvmAndroidMain` — the seam exists, it just needs a third actual over a libz cinterop (libz ships on iOS).

The one sharp edge is `CaffZip.zipSingle`. [CaffZip.kt:7-8](../../module/format/src/commonMain/kotlin/org/umamo/format/cmo3/caff/CaffZip.kt#L7-L8) states the framing must match the Live2D editor's `ZipOutputStream` output exactly. A Native write path must reproduce JDK zip framing byte-for-byte — local header fields, general-purpose flags, data-descriptor usage — or CMO3 write-back breaks compatibility with the official editor. That is invariant #1 territory and needs a corpus test, not an eyeball.

Depends on: nothing, though it only pays off once `:format` can declare a Native target (Phase G). Worth doing early anyway because it also removes the last `java.*` from the art readers, which are otherwise pure Kotlin.

Decisions: libz cinterop vs a pure-Kotlin DEFLATE (recommend cinterop — a correct, fast pure-Kotlin deflate is a project of its own, and Phase A of the [art-sourcing roadmap](art-sourcing-pipeline.md) already wants DEFLATE for the PNG codec, so whatever lands here should serve both); whether the zip _container_ layer is worth abstracting or whether `CaffZip` hand-rolls the two headers it needs.

Also trivially in scope: [PsdLayerRecords.kt:103](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/psd/PsdLayerRecords.kt#L103) uses `java.nio.ByteBuffer` purely for big-endian reads. `commonMain` already has `BinaryReader` to model it on.

Session scope: medium. One session, gated on a `CaffZip` round-trip corpus test.

---

## Phase D — Android GLES renderer, and the real backend seam

Goal: the second `Renderer` implementation — the thing blocking Android, and the thing that de-risks iPadOS.

Status: [MainActivity.kt:54](../../app/android/src/main/kotlin/org/umamo/editor/android/MainActivity.kt#L54) passes `viewportServiceFactory = null`. Everything else on Android is wired, including `FileKit.init` at `:35`. `:render/androidMain` has grid + axis shaders only ([GpuRenderer.android.kt](../../module/render/src/androidMain/kotlin/org/umamo/render/GpuRenderer.android.kt), the `expect class GpuRenderer` actual) — the backdrop seam, not the puppet. What is missing is a GLES peer to `GlPuppetRenderer` (1504 lines + `DeformShaderGlsl.kt`'s 235) and the Compose-image bridge. The TODO's Claude Note § Android GLES renderer backend scopes this correctly, including the GLES 3.2 `samplerBuffer` problem and the option (b) 2D-texture repack — that analysis stands and is not restated here.

**What the note does not cover, and this phase should:** the `Renderer` interface is not carrying the load it is documented to carry.

- [Renderer.kt:15-48](../../module/render/src/commonMain/kotlin/org/umamo/render/Renderer.kt#L15-L48) declares four methods (`setPose`, `setCamera`, `contentBounds`, `render`). It has one implementor and **zero consumers** — grepping the whole tree for `Renderer` in type position returns exactly one hit, its own `implements` clause at [GlPuppetRenderer.kt:166](../../module/render/src/jvmMain/kotlin/org/umamo/render/gl/GlPuppetRenderer.kt#L166). Nothing holds a variable of that type.
- The real contract is the ~20 additional public methods on the concrete `GlPuppetRenderer` (`initGl`, `setGrid`, `setSelection`, `updateModel`, `pickGeometry`, `renderAtlasPage`, …), and the desktop app binds to the concrete class: [OffscreenRenderEngine.kt:57](../../app/desktop/src/jvmMain/kotlin/org/umamo/editor/desktop/viewport/OffscreenRenderEngine.kt#L57), [ViewportPicker.kt:38](../../app/desktop/src/jvmMain/kotlin/org/umamo/editor/desktop/viewport/ViewportPicker.kt#L38).
- `MorphRenderer` / `GpuMesh` / `GpuProgram` are dead code — zero implementors, zero consumers. They currently misrepresent the architecture and should be wired or deleted.
- The seam that actually works cross-platform is `PuppetViewportService`, which is what all the UI consumes.

CLAUDE.md's "Vulkan/Metal later = new `Renderer` impls — the interface is the backend seam" is therefore aspirational today. This matters _now_ rather than later: the second backend is exactly what reveals the true seam, so fix it as part of writing one, not after. A Metal backend written against a four-method façade that assumes GL's current-context / bound-framebuffer model would be built on sand.

**UPDATE (2026-07-16): this is now built.** The seam is `RenderDevice` (`:render/commonMain/.../device/`), not the old four-method `Renderer` (deleted). It is deliberately shaped for the GL family first, with the GL-isms this section warned about handled explicitly: an explicit render *target* (no bound-framebuffer discovery), immutable blend-baked pipelines, uniform *structs* (no name lookups), a `captureDeformedPositions` primitive naming the effect not the mechanism (TF today, compute later), and a `barrier(store)` dependency (glFinish on GL, a no-op on Metal). `GlPuppetRenderer` makes zero GL calls and drives everything through it; `GlRenderDevice` is the desktop impl. Per the project decision, Metal was NOT pre-solved — the five Metal risks below are notes for the port, not blockers, and the API changes if Metal needs it. Phases D/H below are updated accordingly.

Also in scope, cheaply: the grid/axis GLSL is duplicated verbatim across `GpuRenderer.jvm.kt` (`#version 330 core`) and `GpuRenderer.android.kt` (`#version 300 es`) with no shared source and nothing pinning them together — they will drift. `DeformShaderGlsl.kt` demonstrates the correct shared-snippet pattern a few files away.

Depends on: nothing. Owed for Android regardless of the iPad decision.

Session scope: large; realistically split (renderer vs. the offscreen/readback stack extraction out of `app/desktop` vs. the seam refactor).

---

## Phase E — macOS offscreen context

Goal: unblock macOS, which ships today with a permanently blank viewport.

[CglOffscreenGlContext.kt](../../app/desktop/src/jvmMain/kotlin/org/umamo/editor/desktop/viewport/CglOffscreenGlContext.kt) is a no-op stub whose `createAndMakeCurrent()` returns `false` with a warn line. It degrades gracefully rather than crashing, but the editor's core surface does not function on a platform CLAUDE.md lists as supported.

Worth understanding its actual purpose before replacing it: [CglOffscreenGlContext.kt:13-14](../../app/desktop/src/jvmMain/kotlin/org/umamo/editor/desktop/viewport/CglOffscreenGlContext.kt#L13-L14) is explicit that the stub exists to route macOS _away_ from GLFW, because GLFW requires window/context creation on the main thread while `OffscreenRenderEngine` owns a dedicated render thread. **That thread-affinity conflict is the substance of the macOS problem.** CGL is one candidate fix; so is restructuring the render thread's context ownership. Decide which before writing CGL bindings.

This is a JVM/LWJGL problem that keeps 100% of the existing GL codebase. It shares nothing with Phase H but the word "Apple."

Depends on: nothing. Blocked in practice on access to a Mac (TODO already notes a Mac engineer is wanted).

Session scope: medium, and hardware-gated.

---

## Phase F — The iOS actuals

Goal: everything Kotlin/Native needs that is _not_ the codec or the renderer. This is the phase that is much smaller than it looks.

- **`:ui` — 12 `expect`s** (`rgbaToImageBitmap`, five pointer icons, `Tooltip`, `applyAppLocale`, `primaryModifierLabel`, `normalizeKeyPosition`, two `VerticalScrollbarOverlay` overloads). `androidMain`'s actuals are already the touch-shaped answer and only one of the nine files touches an Android API — the rest are `PointerIcon.Default`, identity functions, empty bodies, and `detectTapGestures`. Copy them. `ImageBitmaps.ios.kt` is a near-verbatim copy of the _jvm_ one, since Skia ships on iOS. Roughly eight files of 10-30 lines.
- **`:storage`** — an `iosAppStorage` over `NSSearchPathForDirectoriesInDomains` (~30 lines; mirror `AndroidStorage.kt`'s sandboxed shape, not desktop's XDG branching), plus a `platformFileFromSavedPath` actual.
- **`:render`** — `decodePngToRgba` needs a third actual; both existing ones are JVM-bound (the Android one uses `java.nio` too). **Prefer deleting the expect entirely**: [art-sourcing-pipeline.md § Phase A](art-sourcing-pipeline.md) already plans a pure-Kotlin PNG codec in `:format` that retires this seam on all platforms. Do not write a third actual if that phase is close.
- **`:format`** — the CLIP SQLite seam is the most mechanical port in the tree (SQLDelight has a native driver), but note the `expect` lives in `jvmAndroidMain`, not `commonMain` ([ClipDatabaseAccess.kt:18](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/clip/ClipDatabaseAccess.kt#L18)). The ~1165-line CLIP reader is nearly all portable and would move down to `commonMain` with the expect. Both actuals need a writable temp path; iOS wants `NSTemporaryDirectory`.

**The one non-mechanical item here is FileKit's `absolutePath()`**, used as the recent-files key ([EditorApp.kt:159](../../module/ui/src/jvmAndroidMain/kotlin/org/umamo/ui/app/EditorApp.kt#L159), `Document.kt:67`). iOS `NSURL`s make a raw path string least meaningful, and iOS _requires_ security-scoped bookmarks to reopen a file across launches. [PlatformFiles.android.kt:10-12](../../module/storage/src/androidMain/kotlin/org/umamo/storage/PlatformFiles.android.kt#L10-L12) already documents that Android reopening "may still be refused" — iOS has the same latent bug and worse. Recent files on iOS needs bookmark storage, not a path.

Depends on: Phase A (the targets must exist).

Session scope: small — a day or two for the actuals, plus a separate decision on the bookmark model.

---

## Phase G — CMO3 without reflection

Goal: a CMO3 codec that compiles to Kotlin/Native without losing the round-trip. **This is the decision the iPad hinges on, and it is a strategic call, not a chore.**

The tension is real and should be stated plainly. [CLAUDE.md § Tech stack](../../.claude/CLAUDE.md) names "JVM reflection + JDOM make the CMO3 XML serializer (read _and_ write) tractable" as a _stack rationale_ — reflection is not an accident here, it is why the serializer exists at all. De-reflecting it buys the iPad and spends the thing that made byte-exact round-trip achievable.

Two sub-problems, separable:

**G1 — De-reflect the object graph.** Replace `kotlin-reflect` with a KSP processor emitting per-class property accessors. Tractable _because_ the class list is already explicit ([GeneratedRegistration.kt](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/serialize/gen/GeneratedRegistration.kt), 168 registrations) and there is no dynamic lookup to replicate. The delicate part is preserving `kClass.java.declaredFields` ordering semantics, since byte-exact output depends on it — a generator must emit properties in _backing-field declaration order_, and that must be pinned by a corpus test before the reflection path is removed. `Cmo3.kt`'s raw `java.lang.reflect` image-resource walk becomes a generated visitor in the same pass.

**G2 — Replace JDOM.** A multiplatform XML parser. The risk is not parsing — it is emission. [XmlCodec.kt:27-31](../../module/format/src/jvmAndroidMain/kotlin/org/umamo/format/cmo3/xml/XmlCodec.kt#L27-L31) documents that byte-exact `main.xml` depends on JDOM 1.1.3's precise `Format.getPrettyFormat()` + `TextMode.NORMALIZE` behavior. Reproducing that on another backend byte-for-byte is the hard part, and it is likely underestimated.

Depends on: nothing technically. Depends _strategically_ on Phase B having answered "is the iPad shell worth having."

**Sequencing pressure worth naming now:** nothing lowers `PuppetModel` → CMO3 yet ([EditorApp.kt:189-192](../../module/ui/src/jvmAndroidMain/kotlin/org/umamo/ui/app/EditorApp.kt#L189-L192) — save re-emits the original bytes; TODO § Read/Write Filing Handling; [art-sourcing-pipeline.md § Phase H](art-sourcing-pipeline.md)). **The writer is unwritten.** If iPadOS is on the roadmap at all, deciding G _before_ that exporter is built is dramatically cheaper than deciding it after — otherwise the exporter gets written twice. This is the one real deadline in this document.

Decisions, in order:

1. Does iPadOS ship? (Phase B makes this answerable cheaply — run the shell on an iPad with no document support and find out.)
2. If yes: is byte-exact `main.xml` retained as a goal, or explicitly downgraded to the semantic-validity bar invariant #1 actually requires? This single answer sets G2's difficulty from "hard" to "routine."
3. KSP for all targets, or KSP only for Native with reflection retained on JVM? (Recommend all targets — two serializers is two round-trip surfaces to test, and the reflection path's value disappears once the generator exists.)

Session scope: several sessions. G1 and G2 are independently plannable. Neither should start before decision 2.

---

## Phase H — iPadOS

Goal: the target itself.

Needs, once G lands: `iosArm64()` + `iosSimulatorArm64()` across the module graph; an `app/ios` entrypoint over the same shared `EditorApp` (invariant #3); and a Metal renderer.

The renderer is the second large piece, and it is _not_ a transliteration of the GL path:

- Metal has no current context and no bound framebuffer — every `Renderer` method needs an explicit command-buffer / render-pass parameter that does not exist in today's signatures (Phase D's seam refactor is the prerequisite).
- Metal's texture origin is top-left vs GL's bottom-left, so the V-flip conventions baked into `PAGE_VERTEX_SHADER` and `deformWorld` become per-backend.
- `samplerBuffer` → a Metal `device float2*` buffer argument. Transform feedback has no Metal equivalent; the glue pass-1 becomes a compute kernel writing a buffer. Both are architecture changes to the glue path.
- ~200 lines of fidelity-critical `DEFORM_GLSL` → MSL, of which `warpExtrap`'s 135-line branch-heavy lattice extrapolation is where a mistranslation silently breaks rig fidelity.

The mitigation already exists: `GpuDeformValidationTest` pins `DEFORM_GLSL` against the CPU `applyCpuDeform` oracle. A Metal port has a correctness oracle to check against, provided the equivalent harness is built on the new backend. Budget that harness as part of the phase, not after it.

Depends on: G (documents), D (the real seam), F (the actuals), A (the targets).

Session scope: large. Plan after G's decision, not before.

---

## Cross-cutting decisions

### Is the iPad worth Phase G?

The whole document reduces to this. Framing that makes it answerable rather than a coin-flip:

- Phases A, B, C, F are ~a week total and are worth doing on their own merits — A prevents regression, B is a refactor the codebase is already asking for, C removes the last `java.*` from otherwise-pure readers, F is small.
- Phases D and E are owed for Android and macOS regardless. Neither is iPad work.
- **Only G and H are the iPad's bill**, and H is gated on G.

So the decision is not "do we go Kotlin/Native" — it is "do we spend Phase G." Phase B is the cheap experiment that informs it: get the shell running on an iPad with documents stubbed out, and the answer becomes evidence rather than a guess.

### Byte-exact vs semantic validity

Invariant #1 asks for semantic validity; the current byte-exactness is a bonus correctness signal. But `XmlCodec`, `CaffZip.zipSingle`, and `Serializers.buildProperties` are each carrying real complexity _specifically_ to preserve byte-exactness, and each of them is a Phase C or G blocker because of it. Whether that bonus is worth its portability cost is a decision the project has never explicitly made — it accreted. Make it explicitly, before G.

### One shared shell, three renderers

The renderer is the only genuinely per-platform component, and there will be three (GL desktop, GLES Android, Metal iOS) plus a macOS context variant. That is the irreducible core. Everything else — including the entire 138-file UI — is shared or trivially seamed. This is worth protecting: the pressure to fork the UI for touch must keep losing (invariant #3), and Phase D/H should not smuggle backend concepts up into `commonMain` to make a port easier.

## Risks and open questions

- **Byte-exact `main.xml` on a non-JDOM backend is the highest-risk item in Phase G**, and the most likely to be underestimated. It may be the thing that decides the iPad, and it should be prototyped (one class, one round-trip) before G is committed to.
- `CaffZip.zipSingle` must reproduce JDK zip framing exactly or CMO3 write-back breaks against the official editor. A corpus test gates Phase C.
- `declaredFields` ordering is load-bearing and undocumented as a contract. Pin it with a test _before_ touching the serializer, not during.
- The GLES 3.0 baseline cannot express the glue pass (`samplerBuffer` is GLES 3.2). Already scoped in the TODO Claude Note; option (b) also simplifies the desktop path and should be taken there too.
- Phase D will discover that `Renderer` does not describe what a backend must do. Budget the seam refactor into D rather than treating it as a surprise.
- Recent files on iOS needs security-scoped bookmarks, not paths. The same latent weakness exists on Android today and is documented but unfixed.
- Nothing enforces `commonMain` cleanliness until Phase A lands. Every commit until then can silently add a blocker.

## Suggested first session

Phase A + Phase B together.

A is a handful of build-script lines and the three doc corrections. B is a self-contained refactor with no behavior change, covered by the existing suite. Together they cost little, and they compound: A makes B's result permanent (the shell cannot silently re-acquire a JVM dependency once a Native target compiles it), and B converts the Phase G decision from a large speculative bet into a cheap experiment — a running iPad shell with documents stubbed, which is the only honest way to find out whether the iPad is worth the codec.

Phase C and Phase D are independently schedulable and do not depend on either.
