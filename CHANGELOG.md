# Changelog

All notable changes to **Codebook** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

One line per change, newest first. **This file is the only source**: the
`<change-notes>` of the plugin are generated from it at build time by the
`org.jetbrains.changelog` Gradle plugin, which picks the section matching the
build's version. `./gradlew patchChangelog` promotes *Unreleased* to that
version and stamps the date — run it at release time, before tagging.

## [Unreleased]

## [2.1.0] - 2026-08-05

- One gesture vocabulary in the notes: a plain link opens its file, a fold's arrow toggles its block, a headline is link-coloured but inert — and ⌘/Ctrl-click on a headline or an arrow opens the source.
- The opened block lost its header: no provenance line, no ✕. The arrow that opened it closes it — and once a click inside has replaced the content, the block heads itself with what it now shows.
- Inside borrowed text — an embedded or unfolded body — a plain link is a lookup as well: it replaces the block it stands in, or opens its own under an embed. A glossary can cross-link itself in ordinary Markdown without knowing where it is cited, and the reader keeps their place; ⌘-click still opens the file.

## [2.0.0] - 2026-08-05

The reference syntax settled. One axis, three forms, and the panel reads as one
document from the outline down to the last looked-up definition.

### Refs

- **Three forms, one per intention**: `[#term]` is an ordinary link and
  navigates, `![#term]` shows the target's text where it stands, `!![#term]`
  offers it behind a headline and a `▸`.
- An opened fold appears in an indented block below the line. A fold *inside*
  an open block replaces that block, so a chain of lookups stays flat.
- Every fold occurrence has its own state; two refs to the same term open and
  close independently.
- **Block labels**: a header ending in `<a id="term"></a>` is addressed by that
  id instead of by its title slug, so rewording a heading cannot break a ref.
  The anchor is stripped from the title and is invisible in every renderer.
- Refs inside embedded or folded text resolve against the file they came from —
  fragments, relative paths and images alike.

### Blocks

- **Unlisted blocks**: a title starting with a dot (`---- .Setup ----`) is kept
  out of the outline and the breadcrumb, and heads its own section in the notes.
- A construct's opening line (`namespace {`, `class X {`, `def f():`) stays with
  the CBL comment above it, if there is one.
- Note bodies keep their relative indentation, so nested Markdown lists survive.
- A horizontal rule at the end of a Markdown block's body belongs to the file's
  layout, not to the block, and is dropped.

### Panel

- One line weight below the breadcrumb: a thin rule above each section, none at
  the end of one. Borrowed text — embedded or folded — is indented by the same
  step the outline uses.
- Code blocks wrap instead of widening the whole pane; list items breathe; the
  vertical scrollbar is always shown, so nothing re-wraps when it appears.
- The outline keeps its selection while a program runs, and editing a line no
  longer shows a neighbouring block's notes.
- Output gutter icons appear after the first run of a session too.

### Build

- `<change-notes>` is generated from this file by the Gradle Changelog Plugin.

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
