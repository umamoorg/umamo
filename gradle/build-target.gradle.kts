// EN: Shared build-target resolution. Applied via `apply(from = …)` by every module that bundles
//     platform-specific LWJGL natives — :desktop and :render today. It works out the "<os>-<arch>"
//     token the build is targeting (defaulting to the host), honoring -Pumamo.target to cross-resolve
//     so a Windows-native artifact can be produced from, say, WSL2 Linux:
//
//         ./gradlew :desktop:packageUberJarForCurrentOS -Pumamo.target=windows-x64
//
//     then launched from the Windows side. This only swaps which prebuilt natives get bundled — JVM
//     bytecode is platform-independent, so there is no real cross-compiler involved. (jpackage
//     installers like .msi/.dmg must still be produced ON the target OS; only a runnable uber-jar is
//     portable this way.)
//
//     The result is handed back to the applying module through two extra properties:
//       umamoBuildTarget  — the resolved "<os>-<arch>" string (e.g. "windows-x64")
//       umamoLwjglNatives — the matching LWJGL natives classifier (e.g. "natives-windows")
//
//     Why centralize: both :desktop and :render bundle LWJGL natives, and host-detection logic living in
//     only one place means the two can never drift apart — otherwise a Windows build could bundle one
//     module's Linux natives and silently fail to launch. The Compose/Skiko side stays in :desktop alone,
//     because the `compose.desktop.<target>` accessors only resolve inside a `dependencies {}` block —
//     :desktop reads umamoBuildTarget from here and maps it there.
// JA: 共有のビルド対象解決スクリプト。LWJGL ネイティブを同梱する各モジュールが apply(from=) で取り込む。
//     -Pumamo.target=windows-x64 等で他プラットフォーム向けに解決でき、WSL2 から Windows 用 jar を作れる。

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val hostOs =
	when {
		osName.contains("linux") -> "linux"
		osName.contains("mac") || osName.contains("darwin") -> "macos"
		else -> "windows"
	}
val hostArch =
	when {
		osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
		else -> "x64"
	}
// EN: Skiko/Compose ships no windows-arm64 build; Windows-on-ARM runs the x64 artifact emulated.
val hostTarget =
	if (hostOs == "windows" && hostArch == "arm64") {
		"windows-x64"
	} else {
		"$hostOs-$hostArch"
	}

// EN: `findProperty` reads a Gradle project property (`-Pkey=value` on the command line, or a line in
//     gradle.properties); it returns null when unset, so `?:` falls back to the host. The override wins.
val requestedTarget = (findProperty("umamo.target") as String?)?.lowercase()?.trim()
val resolvedTarget = requestedTarget ?: hostTarget

val recognizedTargets = listOf("linux-x64", "linux-arm64", "windows-x64", "macos-x64", "macos-arm64")
require(resolvedTarget in recognizedTargets) {
	"Unknown -Pumamo.target='$resolvedTarget'. Recognized: ${recognizedTargets.joinToString(", ")}."
}
if (requestedTarget != null) {
	logger.lifecycle("[${project.path}] resolving natives for $resolvedTarget (build host is $hostTarget)")
}

// EN: LWJGL publishes its per-platform natives under these classifiers (org.lwjgl:lwjgl::natives-windows, …).
val lwjglNativesClassifier =
	when (resolvedTarget) {
		"linux-x64" -> "natives-linux"
		"linux-arm64" -> "natives-linux-arm64"
		"windows-x64" -> "natives-windows"
		"macos-x64" -> "natives-macos"
		"macos-arm64" -> "natives-macos-arm64"
		else -> error("unreachable: resolvedTarget validated above")
	}

// EN: `extra` is Gradle's per-project untyped property bag; the applying module reads these back with
//     `extra["umamoBuildTarget"] as String`. This is how an `apply(from =)` script returns values.
extra["umamoBuildTarget"] = resolvedTarget
extra["umamoLwjglNatives"] = lwjglNativesClassifier
