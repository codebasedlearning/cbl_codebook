// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import org.intellij.markdown.ExperimentalApi
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser


/** Shared Markdown -> HTML pipeline for the tool window's TOC titles and
 *  notes pane, including ref/embed expansion. */
object CblMarkdown {

    /**
     * GitHub-flavoured, not plain CommonMark: comparison TABLES are a teaching
     * staple ("instance method | classmethod | staticmethod") and CommonMark has
     * no table syntax at all - pipes would render as a literal paragraph. GFM is
     * a superset, so everything else parses as before; the defaults for link
     * handling are inherited unchanged, which keeps the `cbl-toggle:` scheme
     * working. Swing renders tables without rules, hence the padding in the
     * notes pane stylesheet - keep tables narrow.
     */
    private val flavour = GFMFlavourDescriptor()

    @OptIn(ExperimentalApi::class)
    fun toHtml(markdown: String): String {
        /*
         * The CONSTRUCTOR uses the non-deprecated primary form: the named
         * argument selects it and leaves assertionsEnabled at its default, while
         * passing `flavour` alone would bind to the deprecated secondary one.
         * `CancellationToken` is @ExperimentalApi in this library version, hence
         * the opt-in above.
         */
        val tree = MarkdownParser(flavour, cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
            .removePrefix("<body>").removeSuffix("</body>")
    }

    /** Single-line variant for titles: unwraps the paragraph element. */
    fun inlineToHtml(markdown: String): String =
        toHtml(markdown).trim().removePrefix("<p>").removeSuffix("</p>")

    /** A resolved embed target; [baseDir] is the target file's folder for
     *  cross-file refs (relative images in the body are rebased against it),
     *  null for same-file refs. */
    class Resolved(val block: CblBlock, val baseDir: java.io.File? = null)

    /** Embed syntax: ![...](#ref) or ![...](path.md#ref) - the image form
     *  carries transclusion semantics, exactly as it does for images.
     *  Ordinary images never contain '#'. */
    private val EMBED = Regex("""!\[[^\]]*]\(([^)\s]*#[^)\s]+)\)""")

    /**
     * A RUN of stand-alone embeds: consecutive lines that hold nothing but an
     * embed, blank lines in between allowed. Such a run becomes ONE frame with
     * several entries instead of one frame each - otherwise a list of glossary
     * refs shows a doubled rule (bottom border plus top border) and a paragraph
     * gap between every pair. Adjacent-sibling CSS would be the natural fix,
     * but Swing's HTML engine does not support it, so the markup has to merge.
     *
     * Deliberately does NOT consume the line break after the last embed: the
     * blank line that [frame] then forms is what TERMINATES the raw HTML block.
     * Swallow it and CommonMark keeps the following line inside the block -
     * visible first as a missing gap under the closing rule, and worse, as the
     * next paragraph's Markdown no longer being rendered.
     */
    private val EMBED_RUN = Regex(
        """(?m)^[ \t]*!\[[^\]]*]\([^)\s]*#[^)\s]+\)""" +
            """(?:(?:[ \t]*\R[ \t]*)+!\[[^\]]*]\([^)\s]*#[^)\s]+\))*[ \t]*"""
    )

    /** Custom link scheme for fold/unfold of embedded blocks - the panel's
     *  hyperlink listener dispatches on it and re-renders. */
    const val TOGGLE_SCHEME = "cbl-toggle:"

    /**
     * Short ref form: brackets WITHOUT parentheses, containing a fragment -
     * `[#main-guard]` references, `![#main-guard]` embeds, and an explicit path
     * works too (`![../notes.md#tco]`). A CommonMark shortcut reference, so a
     * foreign renderer prints it as literal text instead of mangling it.
     *
     * The lookahead keeps the full forms out: `[t](#r)`, `![](#r)`, `[#r][l]`.
     * Prose brackets never match, because the content must contain a '#' and no
     * whitespace. Link reference DEFINITIONS (`[#r]: dest`) are excluded in
     * [isLinkDefinition] rather than here - they are a block construct, and a
     * blanket "no colon after" would break ordinary prose such as
     * "`@staticmethod` is a [#decorator]: it changes …".
     */
    private val SHORTCUT = Regex("""(!?)\[([^\]\s]*#[^\]\s]+)](?![(\[])""")

    /**
     * True for a link reference definition: `[#ref]: destination` at the start
     * of a line (only whitespace before it). Mid-sentence colons are prose.
     */
    private fun isLinkDefinition(text: String, match: MatchResult): Boolean {
        if (text.getOrNull(match.range.last + 1) != ':') return false
        for (i in match.range.first - 1 downTo 0) {
            val c = text[i]
            if (c == '\n') break
            if (!c.isWhitespace()) return false
        }
        return true
    }

    /**
     * Rewrites [SHORTCUT] refs into canonical Markdown, BEFORE embeds and
     * rendering - so everything downstream (embed splicing, cycle cap, image
     * rebasing, click handling) sees only the long forms and stays unchanged.
     *
     * A path-less fragment is looked up along [glossaryPath] (the configured
     * files as paths relative to the current file), in order, first hit wins,
     * and finally in the current file. [titleOf] does the probing and supplies
     * the link text of reference forms; an unresolved one renders the usual
     * warning rather than a link into the void.
     */
    fun expandShortcuts(
        markdown: String,
        glossaryPath: List<String> = emptyList(),
        titleOf: (String) -> String? = { null },
    ): String = SHORTCUT.replace(markdown) { match ->
        val (bang, content) = match.destructured
        if (isLinkDefinition(markdown, match)) return@replace match.value
        val destination = if (!content.startsWith("#")) content else {
            // glossary entries first, current file last; if nothing resolves,
            // keep the first candidate so the warning names a sensible target
            val candidates = glossaryPath.map { "$it$content" } + content
            candidates.firstOrNull { titleOf(it) != null } ?: candidates.first()
        }
        if (bang.isNotEmpty()) {
            "![]($destination)"
        } else {
            val text = titleOf(destination)
            if (text == null) "&#9888; *unresolved reference: `$content`*"
            else "[$text]($destination)"
        }
    }

    /**
     * Splice referenced block bodies into the markdown, BEFORE parsing - so
     * embedded text gets full rendering and the image machinery never sees
     * the fragment src. Depth-capped: an embed inside an embedded body renders
     * as a plain note instead of recursing (cycle protection). The [resolve]
     * callback receives the full destination ("#frag" or "path#frag");
     * [expanded] decides per ref whether the body is spliced or folded to a
     * title-only toggle line (Swing HTML has no <details> - the title is a
     * TOGGLE_SCHEME link instead, default: everything expanded).
     */
    fun resolveEmbeds(
        markdown: String,
        depth: Int = 0,
        expanded: (String) -> Boolean = { true },
        resolve: (String) -> Resolved?,
    ): String {
        // runs of two or more stand-alone embeds share one frame; a single one
        // is left to the pass below, so its output is unchanged
        val grouped = EMBED_RUN.replace(markdown) { run ->
            val refs = EMBED.findAll(run.value).map { it.groupValues[1] }.toList()
            if (refs.size < 2) run.value
            else frame(refs.map { entry(it, depth, expanded, resolve).markdown })
        }
        return EMBED.replace(grouped) { match ->
            val ref = match.groupValues[1]
            val entry = entry(ref, depth, expanded, resolve)
            if (entry.framed) frame(listOf(entry.markdown)) else entry.markdown
        }
    }

    /** One entry of an embed frame; [framed] is false for the degraded forms
     *  (unresolved, nesting cap), which render as inline notes. */
    private class Entry(val markdown: String, val framed: Boolean)

    private fun entry(
        ref: String,
        depth: Int,
        expanded: (String) -> Boolean,
        resolve: (String) -> Resolved?,
    ): Entry {
        val resolved = resolve(ref)
        return when {
            resolved == null -> Entry("&#9888; *unresolved reference: `$ref`*", framed = false)
            depth >= 1 -> Entry("*&#8230; see `$ref` (nested embed not expanded)*", framed = false)
            // folded: title-only line, '▸' toggle link
            !expanded(ref) ->
                Entry("[&#9656; **${resolved.block.title}**]($TOGGLE_SCHEME$ref)", framed = true)
            else -> {
                var body = resolveEmbeds(resolved.block.bodyLines.joinToString("\n"), depth + 1, expanded, resolve)
                resolved.baseDir?.let { body = rebaseImages(body, it) }
                Entry("[&#9662; **${resolved.block.title}**]($TOGGLE_SCHEME$ref)\n\n$body", framed = true)
            }
        }
    }

    /**
     * The frame around one or more embed entries: a raw-HTML wrapper instead of
     * '---' rules, because Swing's HRuleView paints black and ignores css
     * margins. The div.embed class is styled by the notes pane's private
     * stylesheet (thin gray top/bottom borders). CommonMark passes the HTML
     * block through and renders the markdown in between normally (same pattern
     * as <center> blocks). Entries inside share the frame, separated by a
     * paragraph break only - no rule between them.
     */
    private fun frame(entries: List<String>): String =
        "\n\n<div class='embed'>\n\n${entries.joinToString("\n\n")}\n\n</div>\n"

    /** Rewrite relative image destinations to absolute file URLs against
     *  [baseDir] - embedded foreign bodies must load THEIR images, not ours.
     *  Runs after nested-embed resolution, so remaining ![]() are real images. */
    private fun rebaseImages(markdown: String, baseDir: java.io.File): String =
        Regex("""(!\[[^\]]*]\()([^)\s]+)\)""").replace(markdown) { match ->
            val dest = match.groupValues[2]
            if ("://" in dest || dest.startsWith("data:") || dest.startsWith("/")) match.value
            else "${match.groupValues[1]}${java.io.File(baseDir, dest).toURI()})"
        }
}
