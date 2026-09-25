# Releasing Umamo

Pushing a semantic version tag builds, tests, and publishes desktop artifacts for all five supported targets.

## What ships

Two files per target except `macos-x64`, which ships the jar alone, so nine in all, plus a `SHA256SUMS.txt`.  The `macos-arm64` zip holds `Umamo.app`; the other app images unpack to an `umamo/` folder:

| File                                     | Note                                                                                                                                                                                                                                                                                                  |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `umamo-<target>-<version>.zip`/`.tar.gz` | Self-contained app image (`:desktop:createDistributable`).  Bundles a jlinked JRE.                                                                                                                                                                                                                    |
| `umamo-<target>-<version>.jar`           | You will need Java SDK 21 or higher to run.  When Java's default would give Umamo less than 3 GB of memory, it restarts itself with room for up to half of your computer's memory.  To always allow half, start it from a terminal: `java -XX:MaxRAMPercentage=50 -jar umamo-<target>-<version>.jar`. |

Targets and the runner each is built on:

| Target        | Runner             |
| ------------- | ------------------ |
| `linux-x64`   | `ubuntu-latest`    |
| `linux-arm64` | `ubuntu-24.04-arm` |
| `windows-x64` | `windows-latest`   |
| `macos-arm64` | `macos-latest`     |
| `macos-x64`   | `macos-15-intel`   |

Every leg builds on its own OS and architecture.  Unlike the uber jar, an application image cannot be cross-produced: jpackage jlinks the *host* JDK into the image, so cross-resolving natives would bundle one platform's Skiko/LWJGL inside another platform's runtime.  The package job passes `-Pumamo.requireTargetIsHost=true` so an unexpected runner `os.arch` fails the build rather than shipping an artifact that runs nowhere.

The bundled runtime is JDK 21 everywhere except `macos-arm64`, which packages with JDK 27 (`-Pumamo.packagingJavaHome`, set by the leg's `packagingJdk`): only JDK 27's jpackage accepts a macOS version starting with `0`, and the fix was never backported.  That leg therefore follows the JDK feature releases (28 in March 2027) until the JDK 29 LTS; Linux and Windows stay on 21 until Temurin 27 ships for Windows or 29 arrives.  Intel Macs get the jar only: Temurin will never publish 27 for macOS x64.  Re-check a new JDK before moving to it - Compose's default jlink module set includes `jdk.crypto.ec`, deprecated and empty since JDK 22, and jlink fails the release it is removed in.

Out of scope until alpha: code signing, notarization, native installers, auto-update, and any Android artifact.  See `TODO.md` § Build and Distribute.

## File associations

The `.uma` document type (`application/vnd.umamo.uma+zip`, `docs/format/UMA.md` § 2) is declared once per OS in `app/desktop/build.gradle.kts` (`fileAssociation`) and in the Android manifest.  What that does depends on the artifact, because on Windows and Linux jpackage applies `--file-associations` to **installers only**:

| Platform | Today's artifacts                                                                                                                                  | With an installer            |
| -------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| Linux    | Not registered.  The app image ships `lib/app/resources/umamo-uma.xml` and `umamo.desktop`; the README gives the two per-user `xdg-*` commands.    | deb / rpm register the type. |
| Windows  | Not registered.  *Open with* only.                                                                                                                 | msi / exe register the type. |
| macOS    | The app image's own `Info.plist` carries `CFBundleDocumentTypes`, so an unpacked `Umamo.app` is the handler with no installer - but see below.     | Same.                        |
| Android  | The manifest's VIEW intent filters; no artifact ships yet.                                                                                         | n/a                          |

The `macos-arm64` leg publishes `Umamo.app`, so the association is live on Apple silicon Macs; Intel Macs get the jar, which has no bundle to associate.  The open-file handler in `Main.kt` meets a real bundle for the first time with it, so check a Finder double-click - at launch, and while running - on a Mac before publishing.

`OsAssociationFilesTest` (`:desktop`) holds the build script, the manifest, and both freedesktop files to the codec's `Uma.MIME_TYPE`, and evaluates the freedesktop magic against a file the writer really produces.  Those files are declared as inputs of `:desktop`'s test task - they are not on its classpath, so without that an edit to one leaves the test up to date and unrun.

## Cutting a release

A released version is always a plain `MAJOR.MINOR.PATCH`.  Between releases master carries the next version with a `-dev` suffix (`0.4.0-dev` while `0.4.0` is being worked on), so the About dialog and every `.uma` a build writes tell a development build from the release it precedes.  Installers decide an upgrade by the numeric version alone, so two releases at one number would not upgrade cleanly: a release that would once have been "another `-dev`" bumps PATCH instead.

1. Set `VERSION` in `module/ui/src/commonMain/kotlin/org/umamo/ui/help/ProjectInfo.kt` to the plain `X.Y.Z`, dropping master's `-dev`.  The workflow **verifies** the tag against it and never injects a version, so a mismatch fails with an annotation telling you what to fix; a pushed tag that carries a suffix fails the same way.
2. In `CHANGELOG.md`, replace the `(Unreleased changes)` line with a `## X.Y.Z - YYYY-MM-DD` heading.
3. Run the pre-flight checks below.
4. Merge to `master`, then tag and push:
   ```bash
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```
5. The workflow creates the release as a **draft**, which publishes as a full release rather than a prerelease: GitHub's latest release, which download links and update checks follow, skips prereleases.  Every app image passes a launch smoke test in CI first (`.github/scripts/launch-smoke-test.sh` and `.ps1`: they start the unpacked archive and read its session log), so by hand before publishing check only what CI cannot see: Gatekeeper and Finder on a real Mac, SmartScreen on Windows, a real GPU, and a jar started by double-click (one window and one Dock icon after its relaunch).
6. Publish: `gh release edit vX.Y.Z --draft=false`, or discard and re-tag:
   ```bash
   gh release delete vX.Y.Z --yes
   git push --delete origin vX.Y.Z && git tag -d vX.Y.Z
   ```
7. Move master on to the next version: set `VERSION` to `<next>-dev` and put an `(Unreleased changes)` line back at the top of `CHANGELOG.md`.

The `-dev` suffix exists only on master.  jpackage rejects suffixes, so `project-version.gradle.kts` strips it for `packageVersion` while everything user visible keeps the full string.  That makes a dev build's installer carry the NEXT release's number, so never hand one out: an installer would treat it as that release.

To rehearse the whole pipeline without a tag, run the workflow manually(`gh workflow run release.yml --ref <branch>`).  With no tag, the version gate synthesizes `v<ProjectInfo.VERSION>` (a `-dev` version is fine here), sets `publish=false`, and the publish job is skipped.  All of the artifacts will be visible on the action runner page and not published as a release.

## Local Pre-flight Checks

```bash
# Export a compatible Java SDK location other Compose's checkRuntime will error.
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

./gradlew :desktop:suggestRuntimeModules            # after any dependency change
./gradlew :desktop:createDistributable :desktop:packageUberJarForCurrentOS
./gradlew build                                     # what the release gate runs

# The corpus suites specifically — see the caching caveat below.
./gradlew :format:jvmTest :runtime:jvmTest :render:jvmTest :ui:jvmTest --rerun
```

Then check the app image at `app/desktop/build/compose/binaries/main/app/`:

* `umamo/lib/app/umamo.cfg` (`Umamo.app/Contents/app/Umamo.cfg` on macOS) — the `[JavaOptions]` block must carry `java-options=-XX:MaxRAMPercentage=50`, `java-options=--enable-native-access=ALL-UNNAMED`, and `java-options=--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`, and **must not** carry `-Dumamo.testCmo3`; the release workflow asserts all four.  Compose forwards `application.jvmArgs` to jpackage as `--java-options`, so anything added there ships; the corpus-preview override is deliberately set on the `run` task alone (see the comment at the bottom of `app/desktop/build.gradle.kts`).
* `umamo/lib/runtime/release` — the `MODULES=` line must list `java.instrument`, `java.sql`, `java.xml`, `jdk.security.auth`, and `jdk.unsupported`, and `JAVA_VERSION` must be the leg's packaging JDK.  The release workflow asserts both.
* The launch smoke test runs locally too, against an unpacked app image and a display (Xvfb on WSL or a headless Linux): `bash .github/scripts/launch-smoke-test.sh umamo/bin/umamo ~/.local/share/umamo/logs true`.

And the jar at `app/desktop/build/compose/jars/`: `jar xf <jar> META-INF/MANIFEST.MF` must carry `Multi-Release: true`, `Add-Exports: java.base/jdk.internal.misc`, and `Enable-Native-Access: ALL-UNNAMED` beside the `Main-Class`, and the release workflow asserts all four.  Without `Multi-Release` the JVM ignores the version-specific classes the merged jars carry, and the other two are the launcher's options in the form `java -jar` reads.  A manifest cannot set the heap, so a jar started without a heap option relaunches itself with `-XX:MaxRAMPercentage=50` when Java's default leaves it under 3 GB (`JarRelaunch.kt`); its session log says so on the line after "started from the jar".

`suggestRuntimeModules` under-reports: it misses reflective and service-loaded edges, and does not name `java.xml` even though JDOM — and therefore all of CMO3 read/write — needs it.  Treat its output as a lower bound and confirm with `jdeps --list-deps` when adding a dependency.