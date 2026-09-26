// :desktop (app/desktop) — desktop entrypoint (Compose Desktop + LWJGL viewport interop).
// :desktop — デスクトップ起動点（Compose Desktop ＋ LWJGL ビューポート連携）。

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

// EN: Resolve which platform's natives this build bundles (default: the host; override with
//     -Pumamo.target=<os>-<arch>, e.g. windows-x64 to cross-build from WSL2 Linux). The shared script
//     also drives :render so both modules agree; it exposes its result via extra properties. See
//     gradle/build-target.gradle.kts for the full rationale and the cross-build recipe.
// JA: 同梱するネイティブの対象プラットフォームを解決する（既定はホスト、-Pumamo.target で上書き）。
apply(from = rootProject.file("gradle/build-target.gradle.kts"))
val buildTarget = extra["umamoBuildTarget"] as String
val lwjglNatives = extra["umamoLwjglNatives"] as String

// Resolve the application version from ProjectInfo.kt (see gradle/project-version.gradle.kts) so
// the packaged artifacts never drift from what the About dialog shows.
apply(from = rootProject.file("gradle/project-version.gradle.kts"))
val umamoVersion = extra["umamoVersion"] as String
val umamoVersionNumeric = extra["umamoVersionNumeric"] as String

// The .uma document type every OS registration names.  OsAssociationFilesTest holds this script, the Android
// manifest, and the freedesktop files to the codec's own Uma.MIME_TYPE, so the four cannot drift apart.
val umaMimeType = "application/vnd.umamo.uma+zip"
val umaExtension = "uma"
val umaDescription = "Umamo Document"

// Who made the app and under what terms, stamped into every installer: the plugin's (the Windows exe version resource, the
// macOS Info.plist, the MSI) and packageLinuxDeb / packageLinuxRpm below.
val appVendor = "Umamo Project"
val appDescription = "Cross-platform 2D puppet modelling editor with Live2D Cubism .cmo3 interop."
val appCopyright = "Copyright (C) Umamo Project contributors.  Licensed under the GPL-3.0-only."
val appLicenseIdentifier = "GPL-3.0-only"
val appAboutUrl = "https://umamo.org"

// The name the app image, its launcher, and its cfg take (docs/plan/distribution.md D15).  A Mac app is
// capitalized, and this is also the name the macOS menu bar shows.  Windows follows, because the MSI names its
// Start-menu entry and its Settings > Apps entry after it.  Linux keeps the lowercase name of its package and launcher
// conventions.  Folders a rigger's files live in stay lowercase on every OS - the Windows install folder among them.  The settings and log folders are "umamo" everywhere (desktopAppStorage),
// and the uber jar's name is set at the bottom of this file; neither follows this.
val packageBaseName =
	if (buildTarget.startsWith("macos-") || buildTarget.startsWith("windows-")) {
		"Umamo"
	} else {
		"umamo"
	}

// The version the installers record, when a CI run needs an OLDER installer of the same build to upgrade from.
// Never set for anything published: the release workflow builds its upgrade fixtures with it and keeps them out
// of the release.  It reaches only the installer tasks, never packageVersion, so the app image is the same one.
val installerVersion = providers.gradleProperty("umamo.installerVersionOverride").orNull ?: umamoVersionNumeric

// The JDK whose jlink and jpackage build the app image, when it is not the Gradle daemon's (D14): only JDK 27's
// jpackage accepts a macOS version starting with 0, so the macOS arm64 release leg passes a JDK 27 here while
// everything else, development included, stays on 21.  application.javaHome also drives `:desktop:run`, which
// is why it is set only when asked for.  A path that is not a JDK fails now rather than as a jpackage error later.
val packagingJavaHome =
	providers.gradleProperty("umamo.packagingJavaHome").orNull?.let { configuredPath ->
		val home = rootProject.file(configuredPath)
		require(home.resolve("bin/jpackage").isFile || home.resolve("bin/jpackage.exe").isFile) {
			"-Pumamo.packagingJavaHome=$configuredPath resolves to '${home.absolutePath}', which is not a JDK with jpackage."
		}
		home.absolutePath
	}

kotlin {
	jvmToolchain(21)

	jvm()

	sourceSets {
		jvmMain {
			dependencies {
				// EN: Compose Desktop's per-target artifact bundles the matching Skiko native (a
				//     .so/.dll/.dylib). buildTarget is exactly the artifact's "<os>-<arch>" suffix, so
				//     `desktop-jvm-$buildTarget` selects the right one — on the host this is what
				//     `compose.desktop.currentOs` resolves to, so the default path is unchanged. Direct
				//     coordinate (not the `compose.desktop.<target>` accessor) because those accessors are
				//     deprecated in CMP 1.11 — same migration the version catalog already made for the
				//     other compose.* artifacts (see libs.versions.toml).
				// JA: 対象プラットフォームの Skiko ネイティブを含む Compose Desktop 依存を直接座標で選ぶ。
				implementation("org.jetbrains.compose.desktop:desktop-jvm-$buildTarget:${libs.versions.composeMultiplatform.get()}")
				// Compose resources: the desktop menu bar localizes against :ui's EN/JA catalogs via
				// stringResource(Res.string.*); :ui exposes it as implementation, so declare it here too.
				implementation(libs.compose.components.resources)
				implementation(project(":ui"))
				implementation(project(":runtime"))
				// Editing core: the per-document EditorSession the desktop host creates and the
				// Selection / EditorMode model the viewport pick reads (used directly in jvmMain).
				implementation(project(":edit"))
				implementation(project(":render"))
				implementation(project(":settings"))
				implementation(project(":storage"))
				// Explicit (also transitive via :runtime) — Document loading calls FormatRegistry/Cmo3 directly.
				implementation(project(":format"))
				// The headless self-check converts an empty puppet to a CMO3 and back (SelfCheck.kt).
				implementation(project(":interop"))

				// JNA: a tiny Win32 FFI for desktop window chrome (the DWM title-bar caption tint).
				// Already on the runtime classpath via FileKit; declared here so jvmMain compiles
				// against com.sun.jna.* directly. No-op off Windows — the call is OS-guarded.
				implementation(libs.jna)

				// LWJGL GL for the offscreen viewport renderer; BOM keeps module versions aligned.
				implementation(project.dependencies.platform(libs.lwjgl.bom))
				implementation(libs.lwjgl.core)
				implementation(libs.lwjgl.opengl)
				// GLFW: a hidden-window GL context for the OFFSCREEN viewport renderer. The puppet is
				// rendered to an FBO and shown as a lightweight Compose Image (not a heavyweight AWT
				// canvas), so Compose menus/overlays/gizmos layer over it correctly on every platform.
				implementation(libs.lwjgl.glfw)
				runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
			}
		}
		jvmTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}

// Compose Desktop's packaging/run DSL. `:desktop:run` launches the editor.
compose.desktop {
	application {
		mainClass = "org.umamo.editor.desktop.MainKt"
		if (packagingJavaHome != null) {
			javaHome = packagingJavaHome
		}
		// Everything in this jvmArgs list is baked into the PACKAGED launcher too — the plugin
		// hands application.jvmArgs straight to jpackage as --java-options, which end up in
		// umamo.cfg. So only put things here that are true for a shipped build. The corpus-preview
		// override, a developer affordance pointing at a gitignored absolute host path, is set on
		// `:desktop:run` alone at the bottom of this file.
		//
		// Half of the machine's memory (docs/plan/distribution.md D6): a large model's export needs gigabytes,
		// and the user's paint app runs beside the editor.  A percentage rather than a fixed size, so a small
		// machine is never promised more than it has.  A jar cannot carry launcher options, so README, RELEASING,
		// and the release notes print this same option for `java -jar`, as the in-app alerts do (JAR_HEAP_OPTION);
		// LauncherHeapOptionTest holds all of them to that constant, and the release workflow checks umamo.cfg.
		// `:desktop:run` inherits it too.
		jvmArgs.add("-XX:MaxRAMPercentage=50")
		// Native libraries and LWJGL's memory access, kept quiet on a JDK 24 or later runtime (the macOS arm64 app
		// image bundles 27).  Skiko, LWJGL, JNA, and sqlite-jdbc load natives from the class path, which those JDKs
		// warn about and will one day refuse without the first option.  LWJGL 3.4 picks its JDK 27 memory backend
		// only with the second, and otherwise falls back to sun.misc.Unsafe, which warns that it will be removed.
		// JDK 21 accepts both, so every leg shares one launcher configuration; the `=` form keeps each option one
		// line of umamo.cfg, which the release workflow checks, and LauncherJvmOptionsTest holds the two here.
		jvmArgs.add("--enable-native-access=ALL-UNNAMED")
		jvmArgs.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
		nativeDistributions {
			// The app image's, launcher's, and cfg's name: Umamo on macOS and Windows, umamo on Linux (see
			// packageBaseName).
			packageName = packageBaseName
			// jpackage rejects prerelease suffixes, so installers get the numeric form; the uber jar
			// keeps the full ProjectInfo string (below).
			packageVersion = umamoVersionNumeric

			// The installer the plugin builds: the DMG (D3), on macOS only.  The MSI, DEB, and RPM are built by
			// packageWindowsMsi, packageLinuxDeb, and packageLinuxRpm below instead - the plugin's own tasks cannot take
			// the WiX template, desktop entry, and RPM spec the project needs.  The plugin checks every listed format's
			// version rules while it configures, on every OS, and the numeric packageVersion meets them.
			targetFormats(TargetFormat.Dmg)

			// Identity metadata. jpackage stamps vendor/description/copyright into the Windows exe
			// version resource and the macOS Info.plist, so even an unsigned build says who made it.  No
			// licenseFile: the plugin would hand it to the DMG as an Agree/Disagree dialog and to the MSI as
			// an "I accept" page, and the GPL asks no one to accept it to run the program (GPLv3 section 9).
			// The license text ships inside the app image instead (prepareAppResources below).
			vendor = appVendor
			description = appDescription
			copyright = appCopyright

			// The jlink module set for the bundled runtime. Compose's default is
			// [java.base, java.desktop, java.logging, jdk.crypto.ec] and modules(...) APPENDS to
			// it rather than replacing it, so these are purely additive. Each is load-bearing,
			// and each was traced to the jar that needs it with `jdeps --list-deps`:
			//   java.instrument   — kotlinx-coroutines' AgentPremain debug-probe transformer.
			//   java.sql          — SQLDelight's JdbcSqliteDriver / sqlite-jdbc (CLIP ingest).
			//   java.xml          — JDOM's SAX/JAXP path, i.e. all of CMO3 read/write. Already
			//                       implied (java.desktop requires it transitively), but CMO3 is
			//                       the product; it should not ride on someone else's implication.
			//   jdk.security.auth — dbus-java, under FileKit's XDG-portal open/save dialogs.
			//   jdk.unsupported   — sun.misc.Unsafe, which LWJGL's MemoryBackendUnsafe* needs.
			// Deliberately NOT includeAllModules: that adds ~50 MB per platform to paper over a
			// class of bug the release workflow already catches by asserting on the MODULES= line
			// of jlink's `release` descriptor. Re-run `:desktop:suggestRuntimeModules` after any
			// dependency change and add whatever it names — but note it under-reports, since it
			// misses reflective and service-loaded edges (it does not name java.xml).
			modules("java.instrument", "java.sql", "java.xml", "jdk.security.auth", "jdk.unsupported")

			// App icon per OS.  jpackage demands a different container per platform and reads only
			// its own host's file, so all three are committed and each is consumed when packaging on
			// that OS.  These feed createDistributable / the native installers (not the uber jar,
			// which carries no icon).  Regenerate from the mascot with docs/design/appicon/generate.sh.
			//
			// The .uma document type (docs/format/UMA.md § 2).  Where it is registered depends on what is installed:
			//   * macOS: the plugin writes CFBundleDocumentTypes into the app image's own Info.plist, so an
			//     unpacked Umamo.app is already the handler for .uma - the DMG adds nothing.
			//   * Windows: packageWindowsMsi passes it to jpackage as --file-associations (windowsFileAssociation below),
			//     which only an installer applies: the MSI registers it, the zip does not.
			//   * Linux: packageLinuxDeb / packageLinuxRpm pass the same three values (linuxFileAssociation below);
			//     the tarball registers the type per user from the two files under resources/linux.
			// The document reuses the application icon until it has one of its own.
			// The MSI's own settings are packageWindowsMsi's, below.
			windows {
				iconFile.set(project.file("icons/umamo.ico"))
			}
			macOS {
				iconFile.set(project.file("icons/umamo.icns"))
				// The document icon is a file of its own (a copy of the app icon, written by generate.sh): the bundle
				// holds it under its file name in Contents/Resources beside the app icon, which jpackage names
				// Umamo.icns, and macOS's case-insensitive file system reads umamo.icns as that same file.
				fileAssociation(umaMimeType, umaExtension, umaDescription, project.file("icons/umamo-document.icns"))
				// CFBundleIdentifier. Matches :android's applicationId so one reverse-DNS identity
				// covers the project on both platforms. Not required for an unsigned app image (the
				// plugin only validates it when signing), but jpackage would otherwise derive one
				// from the main-class package — and macOS keys preferences and TCC permission grants
				// on this string, so changing it later orphans every user's saved state.
				bundleID = "org.umamo.editor"
				// LSMinimumSystemVersion.  11.0 is the floor of the JDK 27 runtime the arm64 app image bundles, which
				// is every Apple silicon Mac; left alone the plugin writes 10.13, a promise the runtime cannot keep.
				minimumSystemVersion = "11.0"
			}
			linux {
				iconFile.set(project.file("icons/umamo.png"))
			}

			// Files copied into the app image beside the jars (lib/app/resources on Linux).  resources/linux
			// holds the freedesktop shared-mime-info entry and the desktop entry a rigger installs per user to
			// make the file manager open .uma with an unpacked Umamo.
			appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
		}
	}
}

// The GPL's text goes into the app image beside the appResourcesRootDir files (lib/app/resources/LICENSE on Linux), so
// the archive and every installer made from the image carry it without asking anyone to agree to it.  Matched lazily:
// the plugin registers the task after this script has run.
val projectLicense = rootProject.file("LICENSE")
tasks.withType<Sync>().matching { syncTask -> syncTask.name == "prepareAppResources" }
	.configureEach {
		from(projectLicense)
	}

// The JDK whose jlink built the app image: the one passed as umamo.packagingJavaHome, else the plugin's default.  The
// Linux installer tasks run ITS jpackage, because jpackage refuses an app image another jpackage version built.
val imageJavaHome = compose.desktop.application.javaHome

// The Windows installer (D2): a per-user MSI, built by jpackage over createDistributable's image - the very image the
// Windows zip ships - so what the smoke test launches, what the zip carries, and what the installer installs are one set
// of files, and Phase 3b can sign the exe in the image before the MSI takes it.  It is built here rather than by the
// plugin's packageMsi because the project needs a WiX template of its own (packaging/windows/main.wxs), and the plugin
// fixes jpackage's resource directory and empties it on every run.  The install folder is %LOCALAPPDATA%\Programs\umamo,
// Windows' place for per-user programs (FOLDERID_UserProgramFiles), and a per-user MSI must remove every folder it
// creates in the user profile (WiX's ICE64 validation): jpackage does so for the install folder but writes nothing for
// Programs above it, which the template adds.  The template is JDK 21's copy with that one component, so moving the
// packaging JDK means re-diffing it (RELEASING.md).  WiX 3.11 is the plugin's own download - the root project's
// unzipWix, which the plugin registers on every Windows host - or the folder WIX_PATH names, as for the plugin's tasks.
//
// Identities, never changed: the upgrade code (minted once on 2026-09-26) is how every later MSI finds and replaces this
// one, and the install folder is where every install lives.  Moving either strands existing installs beside the new one.
val windowsUpgradeUuid = "bed289f4-0c1c-41c6-a315-70e44eeede4a"
val windowsInstallDirectory = "Programs\\umamo"
val windowsFileAssociation =
	tasks.register<WriteProperties>("windowsFileAssociation") {
		description = "Writes the .uma file association jpackage reads for the Windows installer."
		destinationFile.set(layout.buildDirectory.file("compose/tmp/windows/uma-file-association.properties"))
		property("mime-type", umaMimeType)
		property("extension", umaExtension)
		property("description", umaDescription)
		property("icon", project.file("icons/umamo.ico").absolutePath)
	}
val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
run {
	val imageDirectory = layout.buildDirectory.dir("compose/binaries/main/app/$packageBaseName").get().asFile
	val destinationDirectory = layout.buildDirectory.dir("compose/binaries/main/msi").get().asFile
	val associationFile = layout.buildDirectory.file("compose/tmp/windows/uma-file-association.properties").get().asFile
	val wixDirectory =
		providers.environmentVariable("WIX_PATH").orElse(rootProject.layout.buildDirectory.dir("wix311").map { directory -> directory.asFile.absolutePath })
	// A local copy: a task action that read the script's own property would capture the script object, which the
	// configuration cache cannot store.
	val buildsOnWindows = isWindowsHost
	tasks.register<Exec>("packageWindowsMsi") {
		group = "compose desktop"
		description = "Builds the Windows .msi installer from createDistributable's app image."
		dependsOn("createDistributable", windowsFileAssociation, rootProject.tasks.matching { rootTask -> rootTask.name == "unzipWix" })
		onlyIf("the Windows installer is built on Windows") { buildsOnWindows }
		inputs.dir(imageDirectory).withPropertyName("appImage")
		inputs.dir("packaging/windows").withPropertyName("packagingTemplates")
		inputs.file(associationFile).withPropertyName("fileAssociation")
		inputs.property("installerVersion", installerVersion)
		outputs.dir(destinationDirectory)
		doFirst {
			// jpackage will not write over an installer that is already there.
			destinationDirectory.deleteRecursively()
			destinationDirectory.mkdirs()
			// jpackage finds WiX on the PATH, as the plugin's own tasks arrange.  Windows spells the variable in any case,
			// so every spelling is replaced by the one.
			val execTask = this as Exec
			val inheritedPath = execTask.environment.entries.firstOrNull { (name, _) -> name.equals("PATH", ignoreCase = true) }?.value
			execTask.environment =
				execTask.environment.filterKeys { name -> !name.equals("PATH", ignoreCase = true) } +
				("PATH" to listOfNotNull(wixDirectory.get(), inheritedPath).joinToString(File.pathSeparator))
		}
		val options =
			listOf(
				"--type" to "msi",
				"--app-image" to imageDirectory.absolutePath,
				"--dest" to destinationDirectory.absolutePath,
				// The name the Start menu and Settings > Apps show.
				"--name" to packageBaseName,
				"--app-version" to installerVersion,
				"--vendor" to appVendor,
				"--description" to appDescription,
				"--copyright" to appCopyright,
				"--about-url" to appAboutUrl,
				"--resource-dir" to project.file("packaging/windows").absolutePath,
				"--file-associations" to associationFile.absolutePath,
				"--install-dir" to windowsInstallDirectory,
				"--win-upgrade-uuid" to windowsUpgradeUuid,
				// A Start-menu entry in an Umamo folder.
				"--win-menu-group" to "Umamo",
			)
		// Per user, so no administrator prompt: riggers on managed or shared machines often have no admin rights, and
		// winget checks a non-admin install.  No folder page and no desktop shortcut: one click is what a per-user
		// install is for.  Verbose, because a WiX failure is otherwise reported only as jpackage's exit code.
		val arguments =
			listOf(File(imageJavaHome, "bin/jpackage.exe").absolutePath) +
				options.flatMap { (option, value) -> listOf(option, value) } +
				listOf("--win-per-user-install", "--win-menu", "--verbose")
		commandLine(arguments)
	}
}

// The Linux installers (D4): a .deb and an .rpm, built by jpackage over createDistributable's image rather than by
// the plugin's packageDeb / packageRpm.  The plugin fixes jpackage's resource directory and empties it on every run,
// and the project needs files of its own there (packaging/linux):
//   * umamo.desktop - jpackage's own menu entry reads "umamo" and starts the launcher without the file it was
//     asked to open (no %f), so a double-clicked .uma would open an empty editor.
//   * umamo.spec - JDK 21's spec removes the menu entry and the .uma registration in %preun without checking for an
//     upgrade (JDK-8301856, fixed in 22), and an RPM upgrade runs the OLD package's %preun after the new %post.
//     The guarded spec has to be in the first RPM ever published, since that is the one whose %preun runs.
//   * control - the .deb's Depends, curated so one package installs on Ubuntu 22.04 and 24.04 alike (jpackage
//     computes the list from the build machine, which names only 24.04's t64 packages).
//   * postinst and prerm - the menu entry and the .uma registration installed and removed best-effort, as the spec
//     does too: on a system with no desktop menu directories xdg-desktop-menu fails, which would otherwise leave the
//     .deb half-configured or impossible to remove.
// All but the desktop entry are copies of JDK 21's templates: moving the packaging JDK means re-diffing them
// (RELEASING.md).
// An RPM built on Debian declares no library dependencies of its own, so the libraries AWT and Skiko load are
// declared as sonames, which Fedora and openSUSE both resolve.
val linuxFileAssociation =
	tasks.register<WriteProperties>("linuxFileAssociation") {
		description = "Writes the .uma file association jpackage reads for the Linux installers."
		destinationFile.set(layout.buildDirectory.file("compose/tmp/linux/uma-file-association.properties"))
		property("mime-type", umaMimeType)
		property("extension", umaExtension)
		property("description", umaDescription)
		property("icon", project.file("icons/umamo.png").absolutePath)
	}
// Every library the image's native code links from outside it (readelf over the Temurin-built image, 2026-09-26):
// AWT's X11 libraries and the sound library, and Skiko's GL, X11, fontconfig, and C++ runtime - plus EGL, which
// Skiko's arm64 build links and its x64 build does not (2026-09-27).  glibc goes without saying; Temurin carries its
// own freetype.  The .deb's control file names the same set as Debian packages.
val linuxRpmLibraryRequirements =
	listOf(
		"libstdc++.so.6()(64bit)",
		"libGL.so.1()(64bit)",
		"libEGL.so.1()(64bit)",
		"libX11.so.6()(64bit)",
		"libXext.so.6()(64bit)",
		"libXi.so.6()(64bit)",
		"libXrender.so.1()(64bit)",
		"libXtst.so.6()(64bit)",
		"libfontconfig.so.1()(64bit)",
		"libasound.so.2()(64bit)",
	)
val isLinuxHost = System.getProperty("os.name").startsWith("Linux")
for (packageType in listOf("deb", "rpm")) {
	val imageDirectory = layout.buildDirectory.dir("compose/binaries/main/app/$packageBaseName").get().asFile
	val destinationDirectory = layout.buildDirectory.dir("compose/binaries/main/$packageType").get().asFile
	val associationFile = layout.buildDirectory.file("compose/tmp/linux/uma-file-association.properties").get().asFile
	// A local copy: a task action that read the script's own property would capture the script object, which the
	// configuration cache cannot store.
	val buildsOnLinux = isLinuxHost
	tasks.register<Exec>("packageLinux" + packageType.replaceFirstChar(Char::uppercaseChar)) {
		group = "compose desktop"
		description = "Builds the Linux .$packageType installer from createDistributable's app image."
		dependsOn("createDistributable", linuxFileAssociation)
		onlyIf("the Linux installers are built on Linux") { buildsOnLinux }
		inputs.dir(imageDirectory).withPropertyName("appImage")
		inputs.dir("packaging/linux").withPropertyName("packagingTemplates")
		inputs.file("icons/umamo.png").withPropertyName("icon")
		inputs.file(associationFile).withPropertyName("fileAssociation")
		inputs.property("installerVersion", installerVersion)
		outputs.dir(destinationDirectory)
		// jpackage will not write over an installer that is already there.
		doFirst {
			destinationDirectory.deleteRecursively()
			destinationDirectory.mkdirs()
		}
		val options =
			mutableListOf(
				"--type" to packageType,
				"--app-image" to imageDirectory.absolutePath,
				"--dest" to destinationDirectory.absolutePath,
				"--name" to "umamo",
				"--app-version" to installerVersion,
				// Without it jpackage puts its own default icon over lib/umamo.png.
				"--icon" to project.file("icons/umamo.png").absolutePath,
				"--vendor" to appVendor,
				"--description" to appDescription,
				"--copyright" to appCopyright,
				"--license-file" to rootProject.file("LICENSE").absolutePath,
				"--about-url" to appAboutUrl,
				"--resource-dir" to project.file("packaging/linux").absolutePath,
				"--file-associations" to associationFile.absolutePath,
				// Identities: the package name and /opt/umamo, which every later package upgrades in place.
				"--install-dir" to "/opt",
				"--linux-package-name" to "umamo",
				"--linux-deb-maintainer" to "umamo@proton.me",
				"--linux-rpm-license-type" to appLicenseIdentifier,
				"--linux-app-category" to "graphics",
			)
		if (packageType == "rpm") {
			options += "--linux-package-deps" to linuxRpmLibraryRequirements.joinToString(", ")
		}
		val arguments =
			listOf(File(imageJavaHome, "bin/jpackage").absolutePath) +
				options.flatMap { (option, value) -> listOf(option, value) } +
				"--linux-shortcut"
		commandLine(arguments)
	}
}

// Two tests read files straight from disk: OsAssociationFilesTest holds the OS registration files - this script,
// the Android manifest, and the two freedesktop files - to the codec's Uma.MIME_TYPE, and LauncherHeapOptionTest
// holds this script, README, RELEASING, and the release workflow to the one heap option.  None of them is on the
// test classpath, so Gradle does not know the tests depend on them: left undeclared, an edit to any one leaves
// jvmTest UP-TO-DATE and the check silently never runs against the change it exists to catch.
val filesReadByTests =
	files(
		"resources/linux/umamo-uma.xml",
		"resources/linux/umamo.desktop",
		"packaging/windows/main.wxs",
		"packaging/linux/umamo.desktop",
		"packaging/linux/umamo.spec",
		"packaging/linux/control",
		"packaging/linux/postinst",
		"packaging/linux/prerm",
		"build.gradle.kts",
		rootProject.file("app/android/src/main/AndroidManifest.xml"),
		rootProject.file("README.md"),
		rootProject.file("RELEASING.md"),
		rootProject.file(".github/workflows/release.yml"),
	)
tasks.withType<Test>().configureEach {
	inputs.files(filesReadByTests).withPropertyName("filesReadByTests").withPathSensitivity(PathSensitivity.RELATIVE)
}

// `umamo.testCmo3` opens the corpus CMO3 (gitignored; the puppet preview) on launch. It is a
// DEVELOPER affordance carrying an absolute host path, so it must never reach a packaged
// artifact where that path does not exist — hence the run task rather than
// application.jvmArgs, which the plugin forwards to jpackage as --java-options.
//
// Two deliberate choices:
//   * withType/matching/configureEach, not tasks.named("run"): the Compose plugin registers its
//     tasks inside its own afterEvaluate, so `run` does not exist while this script is being
//     evaluated and named() would throw. Same lazy idiom as the uber-jar override below.
//   * systemProperty(), not jvmArgs(): the plugin's own configuration action calls
//     JavaExec.setJvmArgs() — a REPLACE, not an append. Gradle happens to run that action before
//     this one so an appended jvmArg would survive today, but systemProperties is a separate
//     collection setJvmArgs never touches, so this holds regardless of action ordering.
// The path is resolved to a val first so the lambda captures a String, not the Project
// (configuration cache).
val runPreviewCmo3Path = rootProject.file("test/corpus/cmo3/EricaTamamo.cmo3").absolutePath
tasks.withType<JavaExec>().matching { execTask -> execTask.name == "run" }
	.configureEach {
		systemProperty("umamo.testCmo3", runPreviewCmo3Path)
	}

// The Compose plugin stamps the uber jar with the HOST os token (its own currentTarget
// detection), which lies when -Pumamo.target cross-resolves the bundled natives — the jar
// CONTENTS already honor buildTarget (Skiko artifact and LWJGL natives above). The plugin
// assigns the name PARTS (appendix/version) directly in its afterEvaluate, after any
// configureEach action, so part-level overrides lose; an explicit archiveFileName replaces the
// derived-from-parts convention outright and wins regardless of assignment order.
//
// Target before version — umamo-<os>-<arch>-<version>.<ext>. Keep that order: it is what the
// release assets are named and what downstream tooling matches on.
tasks.withType<org.gradle.jvm.tasks.Jar>().matching { jarTask -> jarTask.name == "packageUberJarForCurrentOS" }
	.configureEach {
		archiveFileName.set("umamo-$buildTarget-$umamoVersion.jar")
		// What `java -jar` reads from the manifest, beside the Main-Class the plugin adds to the same map.  Without
		// Multi-Release the JVM ignores the version-specific classes of every multi-release jar merged in here -
		// LWJGL's JDK 25 and 27 paths among them.  The other two are the app image launcher's options
		// (application.jvmArgs) in the manifest's spelling, so a jar on JDK 24 or later starts as quietly; JDK 21
		// ignores the attribute it does not know.  A manifest cannot carry a heap size, which is why the jar
		// relaunches itself when it starts with too little (JarRelaunch.kt).  LauncherJvmOptionsTest holds these to
		// the release workflow's check of the built jar.
		manifest.attributes(
			mapOf(
				"Multi-Release" to "true",
				"Add-Exports" to "java.base/jdk.internal.misc",
				"Enable-Native-Access" to "ALL-UNNAMED",
			),
		)
	}