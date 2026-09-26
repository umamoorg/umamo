# Releasing Umamo

Pushing a semantic version tag builds, tests, and publishes desktop artifacts for all five supported targets.

## What ships

Every target ships its app image and its jar, except `macos-x64`, which ships the jar alone; `windows-x64` adds an MSI, and each Linux target a DEB and an RPM.  That is fourteen files, plus a `SHA256SUMS.txt`.  The `macos-arm64` and `windows-x64` app images unpack to `Umamo.app` and an `Umamo/` folder, the Linux ones to `umamo/`:

| File                                     | Note                                                                                                                                                                                                                                                                                                  |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `umamo-windows-x64-<version>.msi`        | Per-user MSI (`:desktop:packageWindowsMsi`, over `createDistributable`'s image).  Installs to `%LOCALAPPDATA%\Programs\umamo`.                                                                                                                                                                               |
| `umamo-linux-<arch>-<version>.deb`       | DEB (`:desktop:packageLinuxDeb`).  Installs to `/opt/umamo`; its dependencies are curated in `app/desktop/packaging/linux/control`.                                                                                                                                                                   |
| `umamo-linux-<arch>-<version>.rpm`       | RPM (`:desktop:packageLinuxRpm`).  Installs to `/opt/umamo`; unsigned, so Fedora 45+ needs `dnf install --no-gpgchecks`.                                                                                                                                                                              |
| `umamo-<target>-<version>.zip`/`.tar.gz` | Self-contained app image (`:desktop:createDistributable`).  Bundles a jlinked JRE.                                                                                                                                                                                                                    |
| `umamo-<target>-<version>.jar`           | You will need Java SDK 21 or higher to run.  When Java's default would give Umamo less than 3 GB of memory, it restarts itself with room for up to half of your computer's memory.  To always allow half, start it from a terminal: `java -XX:MaxRAMPercentage=50 -jar umamo-<target>-<version>.jar`. |

The `macos-arm64` leg also builds a DMG (`:desktop:packageDmg`), which the install-test job opens and checks on every run but which is NOT published: an unnotarized DMG is worse than the zip it would replace (docs/plan/distribution.md D5).  It is uploaded as the `unpublished-dmg-macos-arm64` workflow artifact.

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

Out of scope until alpha: code signing, notarization, auto-update, and any Android artifact.  See `TODO.md` § Build and Distribute.

## File associations

The `.uma` document type (`application/vnd.umamo.uma+zip`, `docs/format/UMA.md` § 2) is declared in `app/desktop/build.gradle.kts` - `fileAssociation` for Windows and macOS, the `linuxFileAssociation` properties the Linux installer tasks hand jpackage - and in the Android manifest.  On Windows and Linux jpackage applies it to **installers only**:

| Platform | Installer                                                                                                               | Portable archive                                                                                                                                |
| -------- | ----------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Linux    | The DEB and RPM register the type, the menu entry (`app/desktop/packaging/linux/umamo.desktop`), and the document icon. | Not registered.  The app image ships `lib/app/resources/umamo-uma.xml` and `umamo.desktop`; the README gives the two per-user `xdg-*` commands. |
| Windows  | The MSI registers the type for the user.                                                                                | Not registered.  *Open with* only.                                                                                                              |
| macOS    | The DMG (unpublished) holds the same `Umamo.app`.                                                                       | The app image's own `Info.plist` carries `CFBundleDocumentTypes`, so an unpacked `Umamo.app` is the handler with no installer - but see below.  |
| Android  | n/a                                                                                                                     | The manifest's VIEW intent filters; no artifact ships yet.                                                                                      |

The `macos-arm64` leg publishes `Umamo.app`, so the association is live on Apple silicon Macs; Intel Macs get the jar, which has no bundle to associate.  The open-file handler in `Main.kt` meets a real bundle for the first time with it, so check a Finder double-click - at launch, and while running - on a Mac before publishing.

The Linux installers' desktop entry passes the file as a path (`%f`), which is what `Main.kt` reads; jpackage's own generated entry passes nothing, which is one reason the DEB and RPM come from `packageLinuxDeb` / `packageLinuxRpm` with `app/desktop/packaging/linux` as jpackage's resource directory rather than from the Compose plugin.  `OsAssociationFilesTest` (`:desktop`) holds the build script, the manifest, the installers' desktop entry, and both freedesktop files to the codec's `Uma.MIME_TYPE`, and evaluates the freedesktop magic against a file the writer really produces.  Those files are declared as inputs of `:desktop`'s test task - they are not on its classpath, so without that an edit to one leaves the test up to date and unrun.

## Cutting a release

A released version is always a plain `MAJOR.MINOR.PATCH`.  Between releases master carries the next version with a `-dev` suffix (`0.4.0-dev` while `0.4.0` is being worked on), so the About dialog and every `.uma` a build writes tell a development build from the release it precedes.  Installers decide an upgrade by the numeric version alone, so two releases at one number would not upgrade cleanly: a release that would once have been "another `-dev`" bumps PATCH instead.

1. Set `VERSION` in `module/ui/src/commonMain/kotlin/org/umamo/ui/help/ProjectInfo.kt` to the plain `X.Y.Z`, dropping master's `-dev`.  The workflow **verifies** the tag against it and never injects a version, so a mismatch fails with an annotation telling you what to fix; a pushed tag that carries a suffix fails the same way.
2. In `CHANGELOG.md`, replace the `(Unreleased changes)` line with a `## X.Y.Z - YYYY-MM-DD` heading.
3. Run the pre-flight checks below.
4. Merge to `master`, then tag and push:
   ```bash
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```
5. The workflow creates the release as a **draft**, which publishes as a full release rather than a prerelease: GitHub's latest release, which download links and update checks follow, skips prereleases.  Every app image and the jar pass `--self-check` in CI first (`.github/scripts/self-check.sh` and `.ps1`: a headless pass over the bundled runtime, the natives, and the codecs), and every app image a launch smoke test (`launch-smoke-test.sh` and `.ps1`: they start the unpacked archive and read its session log).  The install-test job then installs each installer on a clean machine, upgrades it over an older one, checks what it registered, runs the installed app's self-check and smoke test, and uninstalls it (`install-test-linux.sh` on Ubuntu 24.04, Ubuntu 22.04, Debian 12, and Fedora 44 and 45; `install-test-windows.ps1`; `install-test-macos.sh` for the unpublished DMG).  So by hand before publishing check only what CI cannot see:
   * Gatekeeper and Finder on a real Mac, and a real GPU.
   * The MSI through SmartScreen: "Umamo" in the Start menu and in Settings > Apps with its icon, a `.uma` double-click opens it, and it uninstalls.
   * The RPM on a desktop Fedora (`sudo dnf install ./umamo-linux-x64-<version>.rpm`): the menu entry, a `.uma` opened from the file manager, and `sudo dnf remove umamo`.
   * Help > Open Log Folder opens the folder in the file manager.
   * A jar started by double-click: one window and one Dock icon after its relaunch.
6. Publish: `gh release edit vX.Y.Z --draft=false`, or discard and re-tag:
   ```bash
   gh release delete vX.Y.Z --yes
   git push --delete origin vX.Y.Z && git tag -d vX.Y.Z
   ```
7. Move master on to the next version: set `VERSION` to `<next>-dev` and put an `(Unreleased changes)` line back at the top of `CHANGELOG.md`.

The `-dev` suffix exists only on master.  jpackage rejects suffixes, so `project-version.gradle.kts` strips it for `packageVersion` while everything user visible keeps the full string.  That makes a dev build's installer carry the NEXT release's number, so never hand one out: an installer would treat it as that release.  The same goes for a dry run's installers, which you install to check them: uninstall them before installing the real release, which would otherwise be taken for the same version and not replace them.

To rehearse the whole pipeline without a tag, run the workflow manually(`gh workflow run release.yml --ref <branch>`).  With no tag, the version gate synthesizes `v<ProjectInfo.VERSION>` (a `-dev` version is fine here), sets `publish=false`, and the publish job is skipped.  All of the artifacts will be visible on the action runner page and not published as a release.

## Local Pre-flight Checks

```bash
# Export a compatible Java SDK location other Compose's checkRuntime will error.
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

./gradlew :desktop:suggestRuntimeModules            # after any dependency change
./gradlew :desktop:createDistributable :desktop:packageUberJarForCurrentOS
./gradlew build                                     # what the release gate runs

# The Linux installers, over the image createDistributable built (needs dpkg-deb and fakeroot; rpmbuild for the RPM).
./gradlew :desktop:packageLinuxDeb :desktop:packageLinuxRpm
# On Windows, the MSI (downloads WiX 3.11 on first use, as the Compose plugin does).
./gradlew :desktop:packageWindowsMsi

# The corpus suites specifically — see the caching caveat below.
./gradlew :format:jvmTest :runtime:jvmTest :render:jvmTest :ui:jvmTest --rerun
```

Then check the app image at `app/desktop/build/compose/binaries/main/app/`:

* `umamo/lib/app/umamo.cfg` (`Umamo.app/Contents/app/Umamo.cfg` on macOS) — the `[JavaOptions]` block must carry `java-options=-XX:MaxRAMPercentage=50`, `java-options=--enable-native-access=ALL-UNNAMED`, and `java-options=--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`, and **must not** carry `-Dumamo.testCmo3`; the release workflow asserts all four.  Compose forwards `application.jvmArgs` to jpackage as `--java-options`, so anything added there ships; the corpus-preview override is deliberately set on the `run` task alone (see the comment at the bottom of `app/desktop/build.gradle.kts`).
* `umamo/lib/runtime/release` — the `MODULES=` line must list `java.instrument`, `java.sql`, `java.xml`, `jdk.security.auth`, and `jdk.unsupported`, and `JAVA_VERSION` must be the leg's packaging JDK.  The release workflow asserts both.
* The self-check needs no display: `bash .github/scripts/self-check.sh umamo/bin/umamo`, or `java -jar <jar> --self-check` for the jar.  Every line must read OK.
* The launch smoke test runs locally too, against an unpacked app image and a display (Xvfb on WSL or a headless Linux): `bash .github/scripts/launch-smoke-test.sh umamo/bin/umamo ~/.local/share/umamo/logs true`.
* The installers install and remove in a container: `bash .github/scripts/install-test-linux.sh <package> <version>` inside `ubuntu:24.04`, `debian:12`, or `registry.fedoraproject.org/fedora:44`, with the repository mounted.  Pass an older package and its package manager flag to test the upgrade too, as the workflow does.

Build the images you mean to check with the packaging JDK the workflow uses: a distribution's own JDK links system libraries the release's Temurin bundles, so its image passes here and fails on a machine without them.  Point `-Pumamo.packagingJavaHome` at a Temurin 21, and delete `app/desktop/build/compose/binaries` and `app/desktop/build/compose/tmp/main` when you switch between JDKs of the same version: Gradle cannot tell them apart and reuses the old runtime image.

And the jar at `app/desktop/build/compose/jars/`: `jar xf <jar> META-INF/MANIFEST.MF` must carry `Multi-Release: true`, `Add-Exports: java.base/jdk.internal.misc`, and `Enable-Native-Access: ALL-UNNAMED` beside the `Main-Class`, and the release workflow asserts all four.  Without `Multi-Release` the JVM ignores the version-specific classes the merged jars carry, and the other two are the launcher's options in the form `java -jar` reads.  A manifest cannot set the heap, so a jar started without a heap option relaunches itself with `-XX:MaxRAMPercentage=50` when Java's default leaves it under 3 GB (`JarRelaunch.kt`); its session log says so on the line after "started from the jar".

The Linux installers are built from `app/desktop/packaging/linux/`, jpackage's resource directory: the desktop entry, and `umamo.spec`, `control`, `postinst`, and `prerm`, which are the packaging JDK's own templates with two changes each - the desktop registration made best-effort, so a machine without a menu system still installs, and the RPM's desktop uninstall run only on a real removal (JDK-8301856, fixed in JDK 22; without it every RPM upgrade loses the menu entry and the `.uma` registration).  They replace the templates of the JDK they were copied from, so re-diff them against the new JDK's `jdk.jpackage` resources whenever the packaging JDK changes, and keep `control`'s `Depends` current: it is curated, with `t64` alternatives, so one DEB installs on Ubuntu 22.04 and on 24.04.

The MSI is built the same way, from `app/desktop/packaging/windows/main.wxs`: the Windows JDK's WiX template (from `jmods/jdk.jpackage.jmod`, which only the Windows JDK carries) with one component added.  The install folder is `%LOCALAPPDATA%\Programs\umamo`, and a per-user MSI must remove every folder it creates in the user profile (WiX's ICE64 validation); jpackage removes the install folder but writes nothing for `Programs` above it, so the template removes it, once it is empty, by the id jpackage gives it.  Re-diff it with the Linux templates when the packaging JDK changes.  WiX 3.11 comes from the Compose plugin's own download (the root project's `unzipWix`) or the folder `WIX_PATH` names.  `InstallerIdentityTest` pins these changes, the folder id, and the installers' identities.

`suggestRuntimeModules` under-reports: it misses reflective and service-loaded edges, and does not name `java.xml` even though JDOM — and therefore all of CMO3 read/write — needs it.  Treat its output as a lower bound and confirm with `jdeps --list-deps` when adding a dependency.