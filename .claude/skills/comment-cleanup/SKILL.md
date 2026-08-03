---
description: Sweep a branch's changed files against a base ref and fix comments/docblocks that no longer describe current code — stale claims, leftover planning/phase language, bug-narrative diary comments, wrong CMO3/MOC3 field citations, and incomplete docblocks. Batches large branches across parallel background agents. Use when asked to "clean up comments," "fix comments on this branch," or before merging a branch that accumulated debugging-session comment cruft.
argument-hint: [base-ref]
---

# Comment cleanup

Reviews every comment and docblock touched by a branch (compared against `$1`, default `master`)
and fixes the ones that no longer tell the truth about the code below them. This is a text-only
pass: never change code logic. Suspected actual code bugs get flagged for the user, not silently
fixed — this skill's job is the comments, not the implementation, except where explicitly asked
to also apply a flagged fix.

## 1. Scope the diff

```
git merge-base <base-ref> HEAD
git diff --name-status -M <base-ref>...HEAD
```

Always pass `-M` (rename detection) — without it, a renamed-with-edits file shows as a spurious
delete+add pair, and pathspec-glob queries on the raw file list can double-count or miss renamed
paths. Cross-check any manually-assembled file list against `git diff --name-only` with a
set-difference (`comm -13`/`comm -23`) before trusting it's complete and non-overlapping.

If the user says "second round" or references prior cleanup work on the same branch, scope to
`<last-cleanup-commit>..HEAD` instead of the whole branch — that's usually a much smaller, more
targeted diff, and re-reviewing already-clean content wastes the pass.

## 2. Decide solo vs. delegated

- **Under ~30 changed files**: review directly, file by file, using `Read` and `git diff <base>...HEAD -- <path>`
  per file. Keep a `TodoWrite` list per file cluster so progress survives context compaction.
- **Larger** (a big feature branch, a multi-week history sweep): batch by **module/directory
  boundary**, not by commit range — a file's *final* state is what matters, not which commit last
  touched it. Dispatch each batch as a background `Agent` (`subagent_type: general-purpose`,
  `run_in_background: true`) so batches run in parallel. Aim for batches of roughly 5–20 files or
  ~1000–2000 diff lines; split a single very large file (500+ lines) into its own batch if it's
  dense.
- Before delegating, personally read and fix ONE representative example yourself first (see §5) —
  it gives you a concrete before/after to paste into every agent prompt, which is what keeps
  agent output consistent instead of six different interpretations of "stale."

Every delegated agent prompt must include, verbatim or adapted:
- The exact file list for its batch (absolute repo-relative paths).
- The six categories from §3, each with the concrete calibration example from §5.
- The explicit "what not to do" list from §4.
- An instruction to flag suspected real code bugs as `POSSIBLE CODE BUG: file:line — reason`
  instead of fixing them.
- A word budget on the final report (400–700 words depending on batch size) so N parallel reports
  don't blow the orchestrating session's context on consolidation.

## 3. What counts as a fix (six categories)

**(A) Staleness.** A comment/docblock describes behavior, a signature, a nullability, a module
location, or a control-flow path that no longer matches the code beneath it. Includes dangling
cross-references (`[FormerName]`, a package that moved to another module, a docblock enumerating
fields that no longer match a `when` below it). Fix to describe the code as it stands now.

**(B) Planning/phase language.** Leftover roadmap-numbering that reads like a project-plan label
rather than a description of what a step *does* — `Phase 1` / `Phase 2` inside a function that
runs both steps unconditionally every time, `(M2)`/`(M4)` milestone tags, `Slice A`, `Part 2 of`,
"placeholder pending a future step" for something that's actually wired, "dedicated follow-up" for
work already done in the same branch. Fix by renaming to what the step IS, not by deleting the
structure. Example fix applied in this project:

```
- // Phase 1 - set membership: identity shells for creations, source removal for deletions.
+ // Structural pass - set membership: identity shells for creations, source removal for deletions.
```

**Do not touch legitimate roadmap citations** — `docs/plan/art-sourcing-pipeline.md § Phase G` is a
real, still-open phase in a real design doc; a comment citing it accurately is not planning-language
cruft. The test is whether the *cited plan* still exists and is still pending, not whether the word
"Phase" appears.

**(C) Bug-narrative / debugging-diary comments.** Rewrite comments that narrate a bug's discovery
story in a way that needs hidden context — "used to X, which caused Y, so now Z," "never showed the
bug," "one desync was observed (date) where..." — into either a direct atemporal statement of the
current invariant, or counterfactual "would" framing explaining what the guard prevents. Example:

```
- // one desync was observed (2026-08-03) where a second MOC3 exported the first model's rig
- // onto the second's atlas pages, which reads as a wall of drawable notices...
+ // a session from a stale composition pass would export the PREVIOUS document's rig onto this
+ // document's atlas pages, surfacing as a wall of drawable notices...
```

**Exception — test docblocks only:** a regression test MAY reference a historical bug if the
mechanism is fully and concretely spelled out in the comment itself (not left as an opaque "the
bug"). That's normal regression-test practice, not narrative cruft — don't over-correct it.

**Exception — dated "KNOWN GAP" / investigative documentation:** a long, precise, dated note that
documents a real, currently-open limitation (what's broken, why, what's been ruled out, where the
real fix lives) is valuable "why" documentation, not diary cruft — even when it's long and reads
like it came out of a debugging session. Only fix it if a claim in it is factually wrong or has
been superseded by a later change; never trim it just for length.

**(D) Format-citation discipline.** Any comment touching a binary/serialized format field must cite
it per the project convention (`// CMO3: <ClassName> field <name>`, `// MOC3 §N ...`). Fix a
missing/wrong citation only if you can verify the correct one by reading the actual field/class
definition — never guess a citation.

**(E) Docblock completeness.** Per the project's docblock style, an *existing* docblock needs one
`@param` per parameter (signature order) and `@return` for non-void functions. If a parameter was
added later without updating the docblock, add the tag. **Never add a docblock to a function that
never had one** — that's a different task, not this one.

**(F) Formatting/whitespace artifacts.** Heavy debugging-session editing sometimes leaves stray
indentation (a nested function at 4 tabs instead of 1, relative to its scope) or a duplicate
comment block sitting next to the new one that replaced it. Cheap to spot with `cat -A` on the
touched region; fix as pure whitespace, zero semantic risk.

## 4. What not to do

- No code-logic changes. Comment, docblock, and pure-whitespace text only.
- Don't add new comments/docblocks to undocumented functions.
- Don't "improve" already-accurate comments for style alone.
- Don't shorten legitimate dated investigative documentation just because it's long (see the (C)
  exception above).
- Don't touch files outside the assigned scope/batch.
- Don't touch a project's own scratch/notes file above whatever heading marks it off-limits, if the
  project's memory or CLAUDE.md says so (this repo: `TODO.md` above `# Claude Notes` is Alexia's
  personal pad).

## 5. Calibrate before delegating

Read one file yourself, find one real instance of category (A) or (B), fix it, and keep the exact
before/after diff. Paste that verbatim into every batch's agent prompt as "here's a fix I already
made for calibration — read the file yourself since it won't show in `git diff` yet." This is what
keeps parallel agents from either being too aggressive (rewriting fine comments for style) or too
timid (leaving real staleness because it wasn't a 100% textual match to the example).

## 6. Consolidate and verify

- Every `POSSIBLE CODE BUG` an agent reports gets personally verified before it goes in the final
  report — read the actual code, trace the call graph, confirm the claim with `grep`/`git log -L`
  as needed. Never forward an unverified agent claim as a finding.
- Run a final repo-wide sweep for residual patterns the agents might have missed individually:
  ```
  git diff --name-only <base-ref>...HEAD -- '*.kt' | while read -r f; do
    grep -noE 'Phase [A-Za-z0-9]+|\(M[0-9]+\)|Step [0-9]+|Slice [A-Z]|for now\b' "$f"
  done
  ```
- Sanity-check the aggregate diff: `git diff --stat` should show a comment-sized insert/delete
  ratio (roughly 1:1, small numbers) — a large net line-count change means something drifted into
  code edits.
- If a fix touches control flow that a report only weakly implied should change (e.g. a stale
  comment that was ALSO a real duplicate-notice bug), don't fix the logic unasked — report it, and
  wait for explicit approval before removing/changing behavior (see the `Cmo3PropertyLowering`
  redundant-notice example from this project's history: flagged first, fixed only after the user
  said "go ahead").

## 7. Report format

Group findings by theme, not by batch — the user doesn't care which agent found what. Lead with
the most consequential fixes (wrong logic implied by a comment, swapped comment blocks, a
misleading user-facing string), then the routine staleness/phase-language sweep, then any verified
`POSSIBLE CODE BUG` flags left for the user's own triage. Keep it scannable: file references, one
line of before → after gist each, no prose padding.
