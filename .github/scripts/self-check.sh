#!/usr/bin/env bash
# Runs a packaged or jar Umamo's headless self-check (--self-check, app/desktop SelfCheck.kt) and judges it.
#
# The self-check exercises what an ordinary launch never touches until a rigger opens the right file: the bundled
# runtime's modules, SQLite and its native library, the XML stack under every CMO3, the codecs, Skia, LWJGL's
# natives, the bundled resources, and the login module the Linux file dialogs use.  It opens no window and writes
# no settings or log, so it runs anywhere - a container with no display included.
#
# It fails when the self-check exits non-zero or times out, when its report has a FAILED line, when the JVM printed
# a warning on stderr (the launcher options exist to keep a JDK 24 or later runtime quiet), or when LWJGL reported
# an error there.
#
# Usage: self-check.sh <command...>   e.g. self-check.sh /opt/umamo/bin/umamo, or self-check.sh java -jar umamo.jar
#
# Written for the bash 3.2 macOS ships as well as Linux's.

set -euo pipefail

if [ "$#" -lt 1 ]; then
	echo "usage: $0 <command...>" >&2
	exit 2
fi

work_directory="$(mktemp -d)"
report="${work_directory}/self-check.txt"
stderr_file="${work_directory}/stderr.txt"

status=0
"$@" --self-check "${report}" > /dev/null 2> "${stderr_file}" || status=$?

echo "---- self-check report ($*)"
cat "${report}" 2> /dev/null || echo "(no report written)"
echo "---- stderr"
cat "${stderr_file}"

failures=0
if [ "${status}" -ne 0 ]; then
	echo "::error::the self-check exited with ${status}"
	failures=$((failures + 1))
fi
if grep -q ': FAILED ' "${report}" 2> /dev/null; then
	echo "::error::a self-check failed: $(grep ': FAILED ' "${report}" | head -n1)"
	failures=$((failures + 1))
fi
if grep -q '^WARNING:' "${stderr_file}"; then
	echo "::error::the JVM printed a warning: $(grep '^WARNING:' "${stderr_file}" | head -n1)"
	failures=$((failures + 1))
fi
if grep -qF '[LWJGL] [ERROR]' "${stderr_file}"; then
	echo "::error::LWJGL reported an error: $(grep -F '[LWJGL] [ERROR]' "${stderr_file}" | head -n1)"
	failures=$((failures + 1))
fi

if [ "${failures}" -ne 0 ]; then
	exit 1
fi
echo "self-check passed: $*"
