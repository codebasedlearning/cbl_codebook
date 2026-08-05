# Changelog

All notable changes to **Codebook** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

One line per change, newest first. **This file is the only source**: the
`<change-notes>` of the plugin are generated from it at build time by the
`org.jetbrains.changelog` Gradle plugin, which picks the section matching the
build's version. `./gradlew patchChangelog` promotes *Unreleased* to that
version and stamps the date — run it at release time, before tagging.

## [Unreleased]

## [1.0.14] - 2026-08-05

- Editing a line no longer shows a neighbouring block's notes: the caret is ignored while the model is older than the document, and the panel catches up when the re-parse lands.

## [1.0.12] - 2026-08-05

- A horizontal rule at the end of a Markdown block's body is dropped: it separates two sections of the file, so the last entry of a group no longer ends with a line its siblings do not have.
- Embedded blocks are indented like opened ones - same step, no rule.

## [1.0.11] - 2026-08-04

- Two ref forms instead of three: `[#term]` is a link that unfolds the target in place (headline + ▸, click again to close), `![#term]` shows it always. `!!` is gone, and so is the peek pane.
- A link inside an open block replaces that block instead of nesting another one under it; ⚐ opens the source in the editor, ✕ closes the block.
- Every link occurrence has its own state, so two refs to the same term no longer toggle together.
- Embedded and peeked sections are indented by the same step the outline uses, so borrowed text reads as borrowed.

## [1.0.8] - 2026-08-03

- Output gutter icons appear again on every function satisfying the pattern.

## [1.0.7] - 2026-08-03

- Pinned embeds (`!![#term]`) render without a frame: text the author put here reads as prose, only the headline says where it is kept.
- The peek frame has a ✕ in its header.
- `<change-notes>` is generated from this file instead of being copied into `plugin.xml` by hand.

## [1.0.6] - 2026-08-03

- Markdown links open in a *peek* frame below the notes instead of in the editor; same link closes it, ⚐ opens the file, ✕ dismisses it.
- Links inside an embedded body now resolve against the file they came from.

## [1.0.4] - 2026-07-31

- Pinned embeds: `!![#term]` includes a block outright, without a toggle.
- Inline embeds show the target's headline before the `▸`.
- Block labels: a header ending in `<a id="term"></a>` is addressed by that id instead of by its title slug.
- Unlisted (dotted) blocks are skipped in the breadcrumb, and head their own section in the notes.
- A construct's opening line stays with the CBL comment above it, if there is one.
- Code blocks wrap instead of widening the whole notes pane.
- Note bodies keep their relative indentation, so nested lists stay nested.
- Outline and notes pane indent by the same amount per level; list items breathe.
- The outline keeps its selection while a program runs.

## [1.0.3] - 2026-07-31

- Inline embeds: an embed with text before it on the same line stays in that line.
- Dotted titles (`---- .Setup ----`) are unlisted.

## [1.0.2] - 2026-07-29

- Screenshots added, Readme explained.

## [1.0.1] - 2026-07-28

- Minor warnings and errors from the Problem report fixed.
- Gradle update.

## [1.0.0] - 2026-07-28

- Kotlin sample added.
- Bugs concerning Gradle projects eliminated.
- First public version.

## [0.9.5] - 2026-07-28

- Icons changed.

## [0.9.4] - 2026-07-28

- Unifying changes; Java and Kotlin samples postponed.

## [0.9.3] - 2026-07-28

First public release. Baseline IntelliJ IDEA 2026.1 (`since-build 261`), no
upper bound; installs in every JetBrains IDE.

- Topic outline of a file's dash-framed block comments, four levels by frame width.
- Layered notes that follow the caret, with a breadcrumb over the bodies of the chain.
- Markdown rendering of note bodies, GitHub-flavoured, images resolved relative to the source file.
- Editor folding of the note body, leaving the frame line visible; fold-all acts on CBL comments only.
- References and embeds between blocks: `[text](#topic)` links, `![](#topic)` embeds, folded by default.
- Cross-file references into other snippets or a Markdown glossary, where headings are the blocks.
- Short reference forms `[#term]` and `![#term]`, resolved along a configurable `glossary.path`.
- Run and Debug from the panel, derived from the current file's context.
- Output linking: printed section headers get a gutter icon that scrolls the console to that section.
- Per-course configuration through cascading `cbl.properties` files.
