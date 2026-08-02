# Changelog

All notable changes to **Codebook** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

The newest section is also the source of `<change-notes>` in
`plugin/src/main/resources/META-INF/plugin.xml` — kept in step by hand, as the
release checklist in [DEVELOPMENT.md](DEVELOPMENT.md) spells out.

## [Unreleased]

## [1.0.4] - 2026-07-31

- Unlisted (dotted) blocks are skipped in the breadcrumb.
- Pinned embeds: a second `!` (`!![#tco]`, `!![](notes.md#tco)`) includes the
  block outright.
- Inline embeds now show the target's headline before the `▸` instead of a bare
  arrow.
- Block labels: a header ending in an anchor - `## Background Const
  <a id="acdf"></a>` - is addressed by that label instead of by its title slug,
  and the anchor is stripped from the title.
- Outline and notes pane indent by the same amount per level.
- body's common indent is removed instead of trimming every line
- The outline no longer jumps to the first topic while a program runs

## [1.0.3] - 2026-07-31

- Inline embeds: an embed with text in front of it on the same line renders as
  a bare, clickable `▸` in that line.
- Dotted titles (`---- .Setup ----`) are unlisted.

## [1.0.2] - 2026-07-29

- Screenshots added, Readme explained

## [1.0.1] - 2026-07-28

- Minor warnings and errors from Problem report
- Gradle update

## [1.0.0] - 2026-07-28

- Kotlin sample added
- Bugs concerning gradle projects eliminated
- first public version

## [0.9.5] - 2026-07-28

Icons changed

## [0.9.4] - 2026-07-28

Unifying changes, postpone the Java and Kotlin samples.

## [0.9.3] - 2026-07-28

First public release. Baseline IntelliJ IDEA 2026.1 (`since-build 261`), no
upper bound; installs in every JetBrains IDE.

### Added

- **Topic outline** of a file's dash-framed block comments — four levels by
  frame width, shown by bullet, indent and emphasis at once.
- **Layered notes** that follow the caret: a breadcrumb over the bodies of all
  levels in the chain, so the topic stays visible while reading a detail.
- **Markdown rendering** of note bodies, GitHub-flavoured — tables, lists,
  inline code, and images resolved relative to the source file.
- **Editor folding** of the note body, leaving the frame line visible as
  ordinary source; fold-all and unfold-all act on CBL comments only.
- **References and embeds** between blocks — `[text](#topic)` links,
  `![](#topic)` embeds, rendered folded by default.
- **Cross-file references** into other snippets or a Markdown glossary, where
  ATX headings are the blocks and slugs match GitHub's anchors.
- **Short reference forms** `[#term]` and `![#term]`, resolved along a
  configurable `glossary.path` — which is what makes question blocks work
  without any new syntax.
- **Run and Debug** from the panel, derived from the current file's context.
- **Output linking**: printed section headers in the Run console get a gutter
  icon on their source line; clicking scrolls the console to that section.
- **Per-course configuration** through cascading `cbl.properties` files —
  header patterns per level, the output section pattern, and the glossary
  search path.
