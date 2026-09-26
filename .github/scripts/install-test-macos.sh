#!/usr/bin/env bash
# Opens the Umamo DMG the way the user does - mount it, drag Umamo.app out - and checks the copy: the DMG opens with
# no prompt, the bundle and the Applications link are on the image, the copied app's self-check passes, and it
# starts.
#
# The DMG is built and tested on every release run but published only once it can be notarized (docs/plan/
# distribution.md D5).  A DMG has no install or uninstall of its own: an upgrade is dragging the new app over the
# old one and a removal is deleting it, so there is nothing further to test here.
#
# Usage: install-test-macos.sh <dmg>
#
# Written for the bash 3.2 macOS ships.

set -euo pipefail

if [ "$#" -ne 1 ]; then
	echo "usage: $0 <dmg>" >&2
	exit 2
fi
dmg="$1"
script_directory="$(cd "$(dirname "$0")" && pwd)"
work_directory="$(mktemp -d)"
mount_point="${work_directory}/mount"
applications="${work_directory}/Applications"
mkdir -p "${mount_point}" "${applications}"

failures=0
# fail <message>: reports a failed check and carries on, so one run names every problem.
fail() {
	echo "::error::$1"
	failures=$((failures + 1))
}

# Nothing answers here, so a DMG that asks a question - a license agreement, which the build leaves out because the
# license ships inside the app - fails to mount.
if ! hdiutil attach -nobrowse -readonly -mountpoint "${mount_point}" "${dmg}" < /dev/null; then
	echo "::error::the DMG did not mount without an answer: does it carry a license agreement again?"
	exit 1
fi
ls -la "${mount_point}"
[ -d "${mount_point}/Umamo.app" ] || fail "the DMG holds no Umamo.app"
[ -L "${mount_point}/Applications" ] || fail "the DMG has no Applications link to drag onto"
if [ -d "${mount_point}/Umamo.app" ]; then
	ditto "${mount_point}/Umamo.app" "${applications}/Umamo.app"
fi
hdiutil detach "${mount_point}"

if [ -d "${applications}/Umamo.app" ]; then
	bash "${script_directory}/self-check.sh" "${applications}/Umamo.app/Contents/MacOS/Umamo" || fail "the copied app's self-check failed"
	bash "${script_directory}/launch-smoke-test.sh" "${applications}/Umamo.app/Contents/MacOS/Umamo" "${HOME}/Library/Application Support/umamo/logs" false ||
		fail "the copied app's launch smoke test failed"
fi

if [ "${failures}" -ne 0 ]; then
	echo "${failures} check(s) failed"
	exit 1
fi
echo "install test passed: ${dmg}"