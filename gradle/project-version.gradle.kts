// EN: Shared application-version resolution. Applied via `apply(from = …)` by the app modules that
//     stamp a version onto an artifact — :desktop (uber-jar name, jpackage packageVersion) and
//     :android (versionName). ProjectInfo.kt IS the version (its docblock forbids a second
//     definition), so this script parses the constant out of the source at configuration time
//     rather than declaring its own. providers.fileContents (not File.readText) so the
//     configuration cache tracks the file as an input and invalidates when the version changes.
//
//     The result is handed back to the applying module through two extra properties:
//       umamoVersion        — the full string, e.g. "0.1.0-dev" (jar names, Android versionName)
//       umamoVersionNumeric — the prerelease-stripped MAJOR.MINOR.PATCH, e.g. "0.1.0" (jpackage
//                             rejects suffixes, so native installers get the numeric form)
// JA: 共有のアプリバージョン解決スクリプト。ProjectInfo.kt の VERSION 定数を設定時に読み取り、
//     umamoVersion（完全な文字列）と umamoVersionNumeric（数値のみ）を extra で返す。

val projectInfoFile =
	rootProject.layout.projectDirectory.file("module/ui/src/commonMain/kotlin/org/umamo/ui/help/ProjectInfo.kt")
val projectInfoText = providers.fileContents(projectInfoFile).asText.get()
val versionMatch =
	Regex("""VERSION\s*=\s*"([^"]+)"""").find(projectInfoText)
		?: error("ProjectInfo.kt: VERSION constant not found; the build derives its version from it")
val fullVersion = versionMatch.groupValues[1]
val numericVersion = fullVersion.substringBefore('-')
require(numericVersion.matches(Regex("""\d+\.\d+\.\d+"""))) {
	"ProjectInfo.VERSION '$fullVersion' does not start with numeric MAJOR.MINOR.PATCH"
}

extra["umamoVersion"] = fullVersion
extra["umamoVersionNumeric"] = numericVersion
