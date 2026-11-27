# Releasing Umamo

Pushing a semantic version tag builds, tests, and publishes desktop artifacts for all five supported targets.

## What ships

Every target ships its app image and its jar, except `macos-x64`, which ships the jar alone; `windows-x64` adds an MSI, `macos-arm64` a DMG, and each Linux target a DEB and an RPM.  That is fifteen files, plus `SHA256SUMS.txt` and its signature `SHA256SUMS.txt.asc`.  The `macos-arm64` and `windows-x64` app images unpack to `Umamo.app` and an `Umamo/` folder, the Linux ones to `umamo/`:

| File                                     | Note                                                                                                                                                                                                                                                                                                  |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `umamo-windows-x64-<version>.msi`        | Per-user MSI (`:desktop:packageWindowsMsi`, over `createDistributable`'s image).  Installs to `%LOCALAPPDATA%\Programs\umamo`.                                                                                                                                                                        |
| `umamo-macos-arm64-<version>.dmg`        | DMG (`:desktop:packageDmg`, over the app image once it is signed, notarized, and stapled).  Signed, notarized, and stapled itself.                                                                                                                                                                    |
| `umamo-linux-<arch>-<version>.deb`       | DEB (`:desktop:packageLinuxDeb`).  Installs to `/opt/umamo`; its dependencies are curated in `app/desktop/packaging/linux/control`.                                                                                                                                                                   |
| `umamo-linux-<arch>-<version>.rpm`       | RPM (`:desktop:packageLinuxRpm`).  Installs to `/opt/umamo`; signed with the release key, which dnf checks once it is imported (`rpm --import`) and told to (`--setopt=localpkg_gpgcheck=1`).                                                                                                         |
| `umamo-<target>-<version>.zip`/`.tar.gz` | Self-contained app image (`:desktop:createDistributable`).  Bundles a jlinked JRE.                                                                                                                                                                                                                    |
| `umamo-<target>-<version>.jar`           | You will need Java SDK 21 or higher to run.  When Java's default would give Umamo less than 3 GB of memory, it restarts itself with room for up to half of your computer's memory.  To always allow half, start it from a terminal: `java -XX:MaxRAMPercentage=50 -jar umamo-<target>-<version>.jar`. |

On the `macos-arm64` leg the app image is signed as it is built, then notarized and stapled on its own before the DMG is built around it, so the app a user drags out of the DMG, like the one in the zip, carries its own ticket; the DMG is then signed, notarized, and stapled in turn.  See § Signing.

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

Not yet: Windows code signing, an update notice, and any Android artifact.  Umamo never updates itself: a new version is announced, and the user installs it when they choose.  See `TODO.md` § Build and Distribute.

## File associations

The `.uma` document type (`application/vnd.umamo.uma+zip`, `docs/format/UMA.md` § 2) is declared in `app/desktop/build.gradle.kts` - `fileAssociation` for macOS, and the `windowsFileAssociation` and `linuxFileAssociation` properties the installer tasks hand jpackage - and in the Android manifest.  On Windows and Linux jpackage applies it to **installers only**:

| Platform | Installer                                                                                                                         | Portable archive                                                                                                                                |
| -------- | --------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Linux    | The DEB and RPM register the type, the menu entry (the tarball's `umamo.desktop`, rewritten by the build), and the document icon. | Not registered.  The app image ships `lib/app/resources/umamo-uma.xml` and `umamo.desktop`; the README gives the two per-user `xdg-*` commands. |
| Windows  | The MSI registers the type for the user.                                                                                          | Not registered.  *Open with* only.                                                                                                              |
| macOS    | The DMG holds the same `Umamo.app`.                                                                                               | The app image's own `Info.plist` carries `CFBundleDocumentTypes`, so an unpacked `Umamo.app` is the handler with no installer - but see below.  |
| Android  | n/a                                                                                                                               | The manifest's VIEW intent filters; no artifact ships yet.                                                                                      |

The `macos-arm64` leg publishes `Umamo.app`, so the association is live on Apple silicon Macs; Intel Macs get the jar, which has no bundle to associate.  The open-file handler in `Main.kt` meets a real bundle for the first time with it, so check a Finder double-click - at launch, and while running - on a Mac before publishing.

The Linux installers' desktop entry passes the file as a path (`%f`), which is what `Main.kt` reads; jpackage's own generated entry passes nothing, which is one reason the DEB and RPM come from `packageLinuxDeb` / `packageLinuxRpm` with a resource directory of the project's own rather than from the Compose plugin.  `OsAssociationFilesTest` (`:desktop`) holds the build script, the manifest, and both freedesktop files - the desktop entry being the installers' too - to the codec's `Uma.MIME_TYPE`, and evaluates the freedesktop magic against a file the writer really produces.  Those files are declared as inputs of `:desktop`'s test task - they are not on its classpath, so without that an edit to one leaves the test up to date and unrun.

## Cutting a release

A released version is always a plain `MAJOR.MINOR.PATCH`.  Between releases master carries the next version with a `-dev` suffix (`0.4.0-dev` while `0.4.0` is being worked on), so the About dialog and every `.uma` a build writes tell a development build from the release it precedes.  Installers decide an upgrade by the numeric version alone, so two releases at one number would not upgrade cleanly: a release that would once have been "another `-dev`" bumps PATCH instead.

1. Set `VERSION` in `module/ui/src/commonMain/kotlin/org/umamo/ui/help/ProjectInfo.kt` to the plain `X.Y.Z`, dropping master's `-dev`.  The workflow **verifies** the tag against it and never injects a version, so a mismatch fails with an annotation telling you what to fix; a pushed tag that carries a suffix fails the same way.
2. In `CHANGELOG.md`, replace the `(Unreleased changes)` line with a `## X.Y.Z - YYYY-MM-DD` heading.
3. Run the pre-flight checks below.
4. Merge to `master`, then tag and push:
   ```bash
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```
5. Approve the run.  The packaging and checksums jobs wait on the `release-signing` environment, which holds the signing secrets and hands them over only on a reviewer's approval: on the run's page choose **Review deployments**, once for the packaging legs together and once more for the checksums.
6. The workflow creates the release as a **draft**, which publishes as a full release rather than a prerelease: GitHub's latest release, which download links and update checks follow, skips prereleases.
7. Publish: `gh release edit vX.Y.Z --draft=false`, or discard and re-tag:
   ```bash
   gh release delete vX.Y.Z --yes
   git push --delete origin vX.Y.Z && git tag -d vX.Y.Z
   ```
8. Move master on to the next version: set `VERSION` to `<next>-dev` and put an `(Unreleased changes)` line back at the top of `CHANGELOG.md`.

The `-dev` suffix exists only on master.  jpackage rejects suffixes, so `project-version.gradle.kts` strips it for `packageVersion` while everything user visible keeps the full string.  That makes a dev build's installer carry the NEXT release's number, so never hand one out: an installer would treat it as that release.  The same goes for a dry run's installers, which you install to check them: uninstall them before installing the real release, which would otherwise be taken for the same version and not replace them.

To rehearse the whole pipeline without a tag, run the workflow manually(`gh workflow run release.yml --ref <branch>`).  With no tag, the version gate synthesizes `v<ProjectInfo.VERSION>` (a `-dev` version is fine here), sets `publish=false`, and the publish job is skipped.  All of the artifacts will be visible on the action runner page and not published as a release.  A rehearsal signs with the real identities, so it waits for the same approvals, from a branch the environment allows.

## Local Pre-flight Checks

A local build is never signed - a macOS app image comes out ad-hoc signed, and the installers unsigned - since signing is switched on only by the release workflow's secrets (§ Signing).  The signing scripts can be rehearsed with a throwaway key: give `linux-signing-key.sh` a scratch GnuPG home, the throwaway public key, and its fingerprint, with the throwaway secret key in `LINUX_SIGNING_KEY`.

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