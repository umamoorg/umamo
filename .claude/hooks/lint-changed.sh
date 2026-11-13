#!/bin/bash
# Formats and lints the Kotlin files changed in the working tree when a Claude Code turn ends.
#
# Runs as a Stop hook.  It applies ktlintFormat to the changed and untracked *.kt / *.kts files and then
# runs ktlintCheck over them.  What the formatter could not fix is written to stderr with exit code 2,
# which hands the report back to Claude and keeps the turn going until it is dealt with.
#
# It only ever blocks on real violations.  A missing tool, a Gradle failure, or a second report of the
# very same violations lets the turn end and tells the user instead.  Set UMAMO_SKIP_LINT_HOOK=1 to turn
# it off for a session.

hook_input=$(cat)

if [ -n "$UMAMO_SKIP_LINT_HOOK" ] || ! command -v jq > /dev/null || ! command -v git > /dev/null; then
	exit 0
fi

project_directory="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$project_directory" || exit 0

if [ ! -x ./gradlew ] || ! git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
	exit 0
fi

# Tells the user something without blocking the turn.
#
# $1: the message.
tell_user() {
	jq -n --arg message "$1" '{systemMessage: $message}'
}

# The Kotlin files that differ from HEAD or are new, and still exist.
changed_files=()

while IFS= read -r changed_file; do
	if [ -f "$changed_file" ]; then
		changed_files+=("$changed_file")
	fi
done < <({
	git diff --name-only HEAD -- '*.kt' '*.kts'
	git ls-files --others --exclude-standard -- '*.kt' '*.kts'
} 2> /dev/null | sort -u)

if [ ${#changed_files[@]} -eq 0 ]; then
	exit 0
fi

# Prints a fingerprint of the changed files: their names and their contents.
fingerprint_of_changes() {
	{
		printf '%s\n' "${changed_files[@]}"
		cat "${changed_files[@]}"
	} | cksum | tr -d ' \t'
}

# One state file per session: the outcome of the last run, the fingerprint it saw, and a hash of what
# it reported.  It is what stops an unchanged tree from being linted again on every turn, and what
# stops the same violations from blocking twice.
session_id=$(printf '%s' "$hook_input" | jq -r '.session_id // "unknown"' 2> /dev/null | tr -cd 'A-Za-z0-9_-')
state_file="${TMPDIR:-/tmp}/umamo-lint-hook-${session_id:-unknown}.state"
last_outcome=""
last_fingerprint=""
last_report_hash=""

if [ -f "$state_file" ]; then
	read -r last_outcome last_fingerprint last_report_hash < "$state_file"
fi

if [ "$last_outcome" != "blocked" ] && [ "$last_fingerprint" = "$(fingerprint_of_changes)" ]; then
	exit 0
fi

gradle_tasks=(ktlintFormat ktlintCheck)

# The rule set is not one of the root build's projects, so its own test is what lints it.
for changed_file in "${changed_files[@]}"; do
	if [[ "$changed_file" == gradle/ktlint-rules/* ]]; then
		gradle_tasks+=(:ktlint-rules:test)

		break
	fi
done

fingerprint_before=$(fingerprint_of_changes)
run_marker=$(mktemp)
gradle_log=$(mktemp)
trap 'rm -f "$run_marker" "$gradle_log"' EXIT

# The property is the one ktlint-gradle's own git hook passes: newline-separated paths from the root.
./gradlew "${gradle_tasks[@]}" "-PinternalKtlintGitFilter=$(printf '%s\n' "${changed_files[@]}")" --continue --console=plain > "$gradle_log" 2>&1 < /dev/null
gradle_exit=$?

# The check ran after the format, so its reports hold exactly what the format could not fix.
violations=$(
	find build/reports/ktlint module/*/build/reports/ktlint app/*/build/reports/ktlint -path '*Check/*' -name '*.txt' -newer "$run_marker" 2> /dev/null |
		while IFS= read -r report_file; do
			sed -E 's/\x1b\[[0-9;]*m//g' "$report_file"
		done |
		grep -E '\((standard|umamo):[a-z-]+\)$' |
		sed -E "s|^$project_directory/||" |
		sort -u
)

# A failed rule-set test prints its name with FAILED and then the assertion, which lists the violations.
rule_set_failures=$(grep -A 12 -E '^[A-Za-z]+Test > .* FAILED$' "$gradle_log" | grep -vE '^\s+at |^--$' | cut -c1-400 | head -40)

# Only the rule set and the build logic are compiled on the way to a lint, so a compile error is one of theirs.
compile_errors=$(grep -E '^e: ' "$gradle_log" | sed -E "s|file://$project_directory/||" | cut -c1-300 | head -20)

fingerprint_after=$(fingerprint_of_changes)
formatted_note=""

if [ "$fingerprint_before" != "$fingerprint_after" ]; then
	formatted_note="ktlintFormat rewrote some of the changed files on disk; re-read a file before editing it again."
fi

if [ -z "$violations" ] && [ -z "$rule_set_failures" ] && [ -z "$compile_errors" ]; then
	if [ "$gradle_exit" -ne 0 ]; then
		# Remembered with its fingerprint, so a broken setup is reported once and not on every turn.
		printf 'failed %s -\n' "$fingerprint_after" > "$state_file"
		failure_detail=$(grep -m 1 -A 2 'What went wrong' "$gradle_log" | tail -n +2)
		tell_user "The lint hook could not run ktlint (Gradle exited $gradle_exit): $(printf '%s' "${failure_detail:-$(grep -v '^[[:space:]]*$' "$gradle_log" | tail -n 2)}" | tr '\n' ' ' | cut -c1-300)"

		exit 0
	fi

	printf 'clean %s -\n' "$fingerprint_after" > "$state_file"

	if [ -n "$formatted_note" ]; then
		tell_user "The lint hook ran ktlintFormat over the changed Kotlin files and reformatted some of them."
	fi

	exit 0
fi

report="$violations"

if [ -n "$rule_set_failures" ]; then
	report="${report:+$report
}The ktlint rule set's own tests fail (./gradlew :ktlint-rules:test):
$rule_set_failures"
fi

if [ -n "$compile_errors" ]; then
	report="${report:+$report
}The build failed to compile before ktlint could finish:
$compile_errors"
fi

report_hash=$(printf '%s' "$report" | cksum | tr -d ' \t')

# The same report a second time means it was not fixed; blocking again would only loop.
if [ "$last_outcome" = "blocked" ] && [ "$last_report_hash" = "$report_hash" ]; then
	printf 'allowed %s %s\n' "$fingerprint_after" "$report_hash" > "$state_file"
	tell_user "The lint hook is letting this turn end with ktlint violations still open:
$report"

	exit 0
fi

printf 'blocked %s %s\n' "$fingerprint_after" "$report_hash" > "$state_file"

{
	echo "ktlint still reports these in the Kotlin files changed in the working tree, after ktlintFormat fixed what it could:"
	echo
	echo "$report"
	echo

	if [ -n "$formatted_note" ]; then
		echo "$formatted_note"
	fi

	echo "Fix them before finishing.  If one is not yours to fix, say so in your reply; the same report will not block a second time."
} >&2

exit 2