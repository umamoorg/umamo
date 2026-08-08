package org.umamo.cli

import kotlin.system.exitProcess

/*
 * The Umamo diagnostic CLI: dump / convert / diff over cmo3 and moc3 files.
 *
 * Replaces the diagnostic-tests-in-disguise workflow (Cmo3ResaveDumpTest's -Dcmo3.resave, ad-hoc
 * probe printlns) with a proper operator tool.  Data goes to stdout, diagnostics to stderr; exit 0
 * on success, 1 on a command failure, 2 on a usage error.
 */

private const val USAGE = """Umamo diagnostic CLI

Usage (via Gradle; -q suppresses Gradle's own build output, leaving only this tool's):
  ./gradlew -q :cli:run --args="dump <file> [--sections] [--xml] [--puppet]"
  ./gradlew -q :cli:run --args="convert <in> <out>"
  ./gradlew -q :cli:run --args="diff <a> <b>"

Commands:
  dump <file>      Print a cmo3/moc3's contents.  MOC3 defaults to printing a static
                   rig summary.  CMO3 defaults to the archive entry table plus the
                   parsed main.xml overview.
                     --sections  MOC3 container tier: per-section presence and counts.
                     --xml       CMO3 only: the decompressed main.xml, byte-for-byte.
                     --puppet    Either format: import to a PuppetModel and summarize.
  convert <in> <out>
                   Direction by input format and output extension:
                     cmo3 -> cmo3  Resave (An unedited main.xml will reemit byte-identical.)
                     moc3 -> moc3  Rebake (Fresh synthesis, NOT byte-identical by design.)
                     cmo3 -> moc3  Full family: moc3 + model3.json + cdi3.json + textures
                     moc3 -> cmo3  Fresh-graph synthesis (Cubism can open it; the source
                                   art pages are atlas copies, so a MissingSourceArt
                                   notice is always reported.)
                   A moc3 input may also be given as its .model3.json manifest; sibling
                   sidecars (model3/cdi3/physics3/pose3/userdata3) are discovered by
                   the manifest's own file references.
  diff <a> <b>     Import both files (either format) and print the semantic per-entity
                   difference.
  help             This text."""

/**
 * The CLI entry point: dispatches to a subcommand and converts its outcome to an exit code.
 *
 * @param Array args The raw command-line arguments (Gradle's --args, split).
 */
fun main(args: Array<String>) {
	val exitCode = runCli(args.toList())
	if (exitCode != 0) {
		exitProcess(exitCode)
	}
}

/**
 * Runs one CLI invocation.  Split from main so the dispatch stays testable without exiting the JVM.
 *
 * @param List arguments The command line: a verb followed by its positionals and flags.
 * @return Int The process exit code (0 success, 1 failure, 2 usage error).
 */
internal fun runCli(arguments: List<String>): Int {
	if (arguments.isEmpty() || arguments[0] == "help" || arguments[0] == "--help") {
		println(USAGE)
		return if (arguments.isEmpty()) 2 else 0
	}
	return try {
		when (arguments[0]) {
			"dump" -> runDump(arguments.drop(1))
			"convert" -> runConvert(arguments.drop(1))
			"diff" -> runDiff(arguments.drop(1))
			else -> {
				System.err.println("unknown command '${arguments[0]}'")
				System.err.println(USAGE)
				2
			}
		}
	} catch (usageError: CliUsageException) {
		System.err.println(usageError.message)
		2
	} catch (failure: Exception) {
		System.err.println("error: ${failure.message ?: failure::class.simpleName}")
		1
	}
}

/** A malformed invocation - reported as usage (exit 2) rather than a command failure (exit 1). */
internal class CliUsageException(message: String) : Exception(message)
