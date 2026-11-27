---
description: Bring one non-EN/JA string catalog (ko, fr, zh-CN, zh-TW) up to the current English catalog as an AI draft for human proofreading — key diff, EN-reworded detection, glossary-first review, chunked drafting, validation, and the PR description. Use when asked to update, sync, or fill in a locale in bulk. Never use it for EN or JA, which are written by hand with each feature.
argument-hint: <locale-tag>
---

# Translate catalog

Updates one locale's `strings.xml` (`$1`: `ko`, `fr`, `zh-CN`, or `zh-TW`) against the current English
catalog.  The output is an AI draft: a human proofreads and edits it before the PR merges, and the PR
says so.  One locale per PR, and the PR carries nothing else.  Never commit; Alexia does.

Directory per tag: `ko` is `values-ko`, `fr` is `values-fr`, `zh-CN` is `values-zh-rCN`, and `zh-TW` is
`values-zh-rTW`, all under `module/ui/src/commonMain/composeResources/`.  The rules in CLAUDE.md
(Localization) bind every step here.

## 1. Scope the locale

- Baseline: the locale header's `Synced with the English catalog at <sha>.` line.  With no such line,
  use the commit that added the catalog: `git log --diff-filter=A --format=%h -- <locale strings.xml>`.
- Read the locale's whole header (glossary, style rules) and EN's Terminology block before anything else.
  `values-ja` is the worked example of a finished header.
- Confirm the working tree holds no feature work for this locale.

## 2. Build the work list

Use a script (`python3 -I`, scratchpad, parsing with `xml.etree`) and print counts before drafting.

1. Missing: EN keys the locale lacks.  Skip `quick_setup_artist_credit`; it is absent on purpose.
2. Stale: locale keys EN no longer has.  They are deleted.
3. Reworded: shared keys whose EN text at the baseline (`git show <baseline>:<EN strings.xml>`) differs
   from HEAD.  Flagging too much is safe; flagging too little leaves a stale translation.
4. Kept: everything else.  Sweep it for the glossary's retired terms (the locale's words for
   "document", a bare "model" meaning the puppet, "panel" or "space" meaning an area, "art mesh"),
   then read every kept pair for drift the baseline diff cannot see, such as keys born after the baseline.

Group the work by EN section.  The `<!-- -->` comment above a string says what it is for, so each
drafting chunk carries it.

## 3. Glossary first

- Extend the header to the `values-ja` shape: the locale's rendering of File, Artwork, Puppet, EXT, and
  Area; its term list; its style rules; and the sync line.
- Seed the term list from the existing translation's most frequent renderings.  A human's choice wins
  unless EN changed the concept.  A term the locale never needed gets a proposal.
- Stop here and have the proofreader confirm the glossary before drafting the rest.  A term settled
  after drafting costs a sweep of the whole file.

## 4. Draft

- Under about 100 strings, draft directly.  Otherwise split into contiguous EN-order chunks of roughly
  150 to 200 and draft each in a background `general-purpose` agent.  One chunk per agent, written to a
  scratchpad fragment, not the repo.
- Every agent prompt carries the header glossary and style rules, the EN Terminology block, the rules
  below, and the chunk as `key | preceding comment | EN | existing translation | status`.  It returns the
  fragment plus its low-confidence keys with a one-line reason each, in under 300 words.
- Rules for every string:
  - Translate the current EN's meaning in the target language's idiom, not its word order.  Never copy
    an EN typo.
  - Match the kind of string: a label (`cmd_`, `menu_`, `history_`, `status_`, `dialog_`) takes no
    trailing period; a notice or `export_reason_` sentence does.
  - Keep format identifiers, sidecar names, extensions, product names, units, and axis letters verbatim.
  - Keep the same positional placeholders (`%1$s`, `%2$d`) as EN, reordered if the grammar needs it.
    No `%%`, and no unnumbered `%s`.  Handle particles or agreement around a placeholder as the file
    already does.
  - Use the target language's typography: its own sentence spacing, quotation marks, and width of
    punctuation.  An ellipsis is `…` (U+2026), never `...`.
  - A term the glossary lacks and the draft cannot place stays in Latin letters and goes on the
    low-confidence list.  Never guess a term silently.
  - Long compounds in tool strips and menus: note any string far longer than EN, since layout cannot
    assume EN widths.

## 5. Merge

Emit the file in EN key order.  Existing lines the work list kept pass through byte-for-byte; drafted
lines replace reworded and fill missing keys; stale keys are dropped.  Keep tab indentation, the file's
existing ending, XML escapes (`&amp;`, `\n`), and unescaped apostrophes.  Copy no EN comments.  Advance
the sync line to the commit the draft was made against (`git rev-parse --short HEAD`).

Then a terminology pass over the merged file for any term the glossary retired.

## 6. Validate

Nothing in the build checks catalog content, so this is the only net.  A script must report zero
findings:

- Every EN key is present except `quick_setup_artist_credit`; no key EN lacks.
- Each string's placeholder multiset equals EN's.  No `%%` and no `...`.
- The file parses as XML, indents with tabs, and contains no retired glossary term.
- Kept lines match the original file byte-for-byte.

Then `./gradlew ktlintFormat` and `set -o pipefail; ./gradlew verifyChanged`, and read the `VERDICT:`
line, not the exit code.  `ExportNoticeLabelsTest` reads only the EN catalog, so it does not cover a
locale; the Compose resource generator at least rejects malformed XML.

## 7. Hand off

Write the PR description as a text file in the scratchpad.  Open it with: this is an AI draft that
has not been proofread, and it must not merge until a human has.  Then give:

- Counts: new, reworded, kept, removed.
- Glossary changes, with proposed terms marked as proposals.
- The reworded keys, so the proofreader can check what moved.
- The low-confidence keys with each reason.

A side-by-side `EN | locale | status` review sheet is optional.  Never describe the draft's quality
beyond what was checked: say that no native speaker has seen it.
