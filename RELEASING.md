# Releasing Umamo

Pushing a semantic version tag builds, tests, and publishes desktop artifacts for all five supported targets.

## What ships

Two files per target, ten in total, plus a `SHA256SUMS.txt`:

| File                                       | For                                                                                      |
| ------------------------------------------ | ---------------------------------------------------------------------------------------- |
| `umamo-<target>-<version>.zip` / `.tar.gz` | Self-contained app image (`:desktop:createDistributable`).  Bundles a jlinked JRE.       |
| `umamo-<target>-<version>.jar`             | Uber jar (`:desktop:packageUberJarForCurrentOS`).  Needs a JDK/JRE 21 on the machine.    |

Targets and the runner each is built on:

| Target        | Runner             |
| ------------- | ------------------ |
| `linux-x64`   | `ubuntu-latest`    |
| `linux-arm64` | `ubuntu-24.04-arm` |
| `windows-x64` | `windows-latest`   |
| `macos-arm64` | `macos-latest`     |
| `macos-x64`   | `macos-15-intel`   |

Every leg builds on its own OS and architecture.  Unlike the uber jar, an application image cannot be cross-produced: jpackage jlinks the *host* JDK into the image, so cross-resolving natives would bundle one platform's Skiko/LWJGL inside another platform's runtime.  The package job passes `-Pumamo.requireTargetIsHost=true` so an unexpected runner `os.arch` fails the build rather than shipping an artifact that runs nowhere.

Out of scope until alpha: code signing, notarization, native installers, auto-update, and any Android artifact.  See `TODO.md` § Build and Distribute.

## File associations

The `.uma` document type (`application/vnd.umamo.uma+zip`, `docs/format/UMA.md` § 2) is declared once per OS in `app/desktop/build.gradle.kts` (`fileAssociation`) and in the Android manifest.  What that does depends on the artifact, because on Windows and Linux jpackage applies `--file-associations` to **installers only**:

| Platform | Today's artifacts                                                                                                                                  | With an installer            |
| -------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| Linux    | Not registered.  The app image ships `lib/app/resources/umamo-uma.xml` and `umamo.desktop`; the README gives the two per-user `xdg-*` commands.    | deb / rpm register the type. |
| Windows  | Not registered.  *Open with* only.                                                                                                                 | msi / exe register the type. |
| macOS    | The app image's own `Info.plist` carries `CFBundleDocumentTypes`, so an unpacked `umamo.app` is the handler with no installer - but see below.     | Same.                        |
| Android  | The manifest's VIEW intent filters; no artifact ships yet.                                                                                         | n/a                          |

The macOS legs publish the uber jar only until JDK 27 (the `appImage: false` note in `release.yml`), and a jar has no bundle to associate.  Setting `appImage` back to `true` is therefore also what switches the macOS association on; the open-file handler in `Main.kt` is already in place for it and has not been exercised on a real bundle.

`OsAssociationFilesTest` (`:desktop`) holds the build script, the manifest, and both freedesktop files to the codec's `Uma.MIME_TYPE`, and evaluates the freedesktop magic against a file the writer really produces.  Those files are declared as inputs of `:desktop`'s test task - they are not on its classpath, so without that an edit to one leaves the test up to date and unrun.

## Cutting a release

1. Bump `VERSION` in `module/ui/src/commonMain/kotlin/org/umamo/ui/help/ProjectInfo.kt`.  The workflow **verifies** the tag against it and never injects a version so a mismatch will fail with an annotation telling you what to fix.
2. Update the `CHANGELOG.md` with new changes under a `## [X.Y.Z] - YYYY-MM-DD` heading.
3. Run the pre-flight checks below.
4. Merge to `master`, then tag and push.  Don't forget `-dev`, `-alpha`, and so on if needed.
   ```bash
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```
5. The workflow creates the release as a **draft**, marked prerelease when the version carries a suffix.  Download the artifacts and launch at least one per OS before publishing.  The CI does not test the packaged binaries.
6. Publish: `gh release edit vX.Y.Z --draft=false`, or discard and re-tag:
   ```bash
   gh release delete vX.Y.Z --yes
   git push --delete origin vX.Y.Z && git tag -d vX.Y.Z
   ```

Prerelease tags are supported as well, so having `-dev` or `-rc` is fine.  However, jpackage rejects prerelease suffixes, so `project-version.gradle.kts` strips it for `packageVersion` while everything user visible keeps the full string.

To rehearse the whole pipeline without a tag, run the workflow manually(`gh workflow run release.yml --ref <branch>`).  With no tag, the version gate synthesizes `v<ProjectInfo.VERSION>`, sets `publish=false`, and the publish job is skipped.  All of the artifacts will be visible on the action runner page and not published as a release.

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

* `umamo/lib/app/umamo.cfg` — the `[JavaOptions]` block must carry `-Xmx4g` and **must not** carry `-Dumamo.testCmo3`.  Compose forwards `application.jvmArgs` to jpackage as `--java-options`, so anything added there ships; the corpus-preview override is deliberately set on the `run` task alone (see the comment at the bottom of `app/desktop/build.gradle.kts`).
* `umamo/lib/runtime/release` — the `MODULES=` line must list `java.instrument`, `java.sql`, `java.xml`, `jdk.security.auth`, and `jdk.unsupported`.  The release workflow asserts this too.

`suggestRuntimeModules` under-reports: it misses reflective and service-loaded edges, and does not name `java.xml` even though JDOM — and therefore all of CMO3 read/write — needs it.  Treat its output as a lower bound and confirm with `jdeps --list-deps` when adding a dependency.