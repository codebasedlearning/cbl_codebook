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

    /**
     * Fenced code blocks as a WRAPPING monospace block instead of `<pre>`.
     *
     * Swing lays a `<pre>` out on one line, whatever its width, and a view that
     * cannot fit its content stops tracking the viewport - so ONE long code
     * line widens the whole document, and the prose above it re-flows to that
     * width and runs off the right edge. In a docked tool window that is not a
     * corner case: 80 columns of C++ is wider than the panel almost always.
     *
     * Wrapping costs the guarantee that a line stays a line; keeping `<pre>`
     * costs the reader a horizontal scrollbar for every paragraph on the page.
     * Indentation survives as non-breaking spaces, which is what carries the
     * shape of the code.
     */
    fun softenCodeBlocks(html: String): String =
        Regex("""<pre>\s*<code[^>]*>([\s\S]*?)</code>\s*</pre>""").replace(html) { match ->
            val lines = match.groupValues[1].trim('\n').lines().map { line ->
                val indent = line.takeWhile { it == ' ' }.length
                "&nbsp;".repeat(indent) + line.substring(indent)
            }
            "<div class='code'>${lines.joinToString("<br>")}</div>"
        }

    /**
     * A resolved embed target. [baseDir] is the target file's folder for
     * cross-file refs (relative images in the body are rebased against it),
     * [path] the same file as the ref spelled it - relative to the file we are
     * rendering, which is the form links have to be written in to be
     * clickable. Both null for same-file refs.
     */
    class Resolved(
        val block: CblBlock,
        val baseDir: java.io.File? = null,
        val path: String? = null,
    )

    /**
     * Embed syntax: ![...](#ref) or ![...](path.md#ref) - the image form
     * carries transclusion semantics, exactly as it does for images.
     * Ordinary images never contain '#'.
     *
     * A SECOND leading '!' (group 1) pins the embed open: `!![](#ref)` is a
     * transclusion - text that belongs here and is merely kept in one place -
     * while `![](#ref)` is a question, folded until asked. Two different
     * intentions, one character apart, and the extra '!' costs a foreign
     * renderer nothing but a literal '!'.
     */
    private val EMBED = Regex("""(!?)!\[[^\]]*]\(([^)\s]*#[^)\s]+)\)""")

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
        """(?m)^[ \t]*!?!\[[^\]]*]\([^)\s]*#[^)\s]+\)""" +
            """(?:(?:[ \t]*\R[ \t]*)+!?!\[[^\]]*]\([^)\s]*#[^)\s]+\))*[ \t]*"""
    )

    /** Custom link scheme for fold/unfold of embedded blocks - the panel's
     *  hyperlink listener dispatches on it and re-renders. */
    const val TOGGLE_SCHEME = "cbl-toggle:"

    /** Custom link scheme for "open this in the EDITOR", used by the peek
     *  header - a plain link would only open the peek again. */
    const val OPEN_SCHEME = "cbl-open:"

    /** Custom link scheme for the peek's close button. */
    const val CLOSE_SCHEME = "cbl-close:"

    /**
     * Images and links of a body that came from ANOTHER file, rewritten so both
     * resolve from the file being rendered: images to absolute URLs, links to
     * the foreign file's path (see [rebaseLinks]).
     */
    fun rebaseForeign(markdown: String, target: Resolved): String {
        var out = markdown
        target.baseDir?.let { out = rebaseImages(out, it) }
        target.path?.let { out = rebaseLinks(out, it) }
        return out
    }

    /**
     * Short ref form: brackets WITHOUT parentheses, containing a fragment -
     * `[#main-guard]` references, `![#main-guard]` embeds, `!![#main-guard]`
     * pins one open, and an explicit path works too (`![../notes.md#tco]`).
     * A CommonMark shortcut reference, so a foreign renderer prints it as
     * literal text instead of mangling it.
     *
     * The lookahead keeps the full forms out: `[t](#r)`, `![](#r)`, `[#r][l]`.
     * Prose brackets never match, because the content must contain a '#' and no
     * whitespace. Link reference DEFINITIONS (`[#r]: dest`) are excluded in
     * [isLinkDefinition] rather than here - they are a block construct, and a
     * blanket "no colon after" would break ordinary prose such as
     * "`@staticmethod` is a [#decorator]: it changes …".
     */
    private val SHORTCUT = Regex("""(!{0,2})\[([^\]\s]*#[^\]\s]+)](?![(\[])""")

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
            // one bang embeds, two pin it open - passed through as written, so
            // the embed pass is the only place that knows what they mean
            "$bang[]($destination)"
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
     *
     * An embed with TEXT IN FRONT OF IT on the same line is inline instead:
     * see [inlineEmbed]. Position decides, not syntax, so nothing new has to
     * be written for it.
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
            val refs = EMBED.findAll(run.value)
                .map { it.groupValues[1].isNotEmpty() to it.groupValues[2] }.toList()
            if (refs.size < 2) run.value
            else {
                val entries = refs.map { (pinned, ref) -> entry(ref, depth, expanded, resolve, pinned) }
                // a run of pinned entries is prose, not an insertion: no frame
                if (entries.none { it.framed }) entries.joinToString("\n\n") { it.markdown }
                else frame(entries.map { it.markdown })
            }
        }
        return EMBED.replace(grouped) { match ->
            val pinned = match.groupValues[1].isNotEmpty()
            val ref = match.groupValues[2]
            if (isInline(grouped, match.range.first)) {
                inlineEmbed(ref, depth, expanded, resolve, pinned)
            } else {
                val entry = entry(ref, depth, expanded, resolve, pinned)
                if (entry.framed) frame(listOf(entry.markdown)) else entry.markdown
            }
        }
    }

    /** True if something other than whitespace precedes [start] on its line -
     *  i.e. the embed sits IN a sentence rather than on a line of its own. */
    private fun isInline(text: String, start: Int): Boolean {
        for (i in start - 1 downTo 0) {
            val c = text[i]
            if (c == '\n') return false
            if (!c.isWhitespace()) return true
        }
        return false
    }

    /**
     * An embed inside a sentence ("What prints `cout << v1`? ![#answer-x]"):
     * the ref becomes its target's HEADLINE followed by the toggle arrow, right
     * where it stands, and nothing else - no frame, no line break. The sentence
     * keeps its shape and the reader can see what is behind the arrow before
     * clicking. Which puts the burden where it belongs: a headline must not
     * give away what the block answers, so answer blocks stay neutrally named
     * (`## Answer 0x02-3`) or ask the question themselves.
     *
     * Unfolded, the arrow flips and the body follows in the usual frame
     * directly below the paragraph - unheadlined, since the headline is already
     * standing in the sentence above it. Inline the body cannot go: a glossary
     * entry is block content (lists, tables, images), and Markdown has no way
     * to put a block inside a paragraph. The frame is what terminates that
     * paragraph, hence the trailing blank line - same contract as [frame]'s
     * other callers.
     */
    private fun inlineEmbed(
        ref: String,
        depth: Int,
        expanded: (String) -> Boolean,
        resolve: (String) -> Resolved?,
        pinned: Boolean = false,
    ): String {
        val resolved = resolve(ref) ?: return "&#9888; *unresolved reference: `$ref`*"
        if (depth >= 1) return "*&#8230; see `$ref` (nested embed not expanded)*"
        val title = titleText(resolved.block.title)
        // plain, not bold: this sits mid-sentence, and the link colour marks it
        val toggle = { arrow: String -> "[$title $arrow]($TOGGLE_SCHEME$ref)" }
        if (!pinned && !expanded(ref)) return toggle("&#9656;")
        val body = rebaseForeign(
            resolveEmbeds(resolved.block.bodyLines.joinToString("\n"), depth + 1, expanded, resolve),
            resolved,
        )
        // pinned: the headline stays, the arrow goes - there is nothing to click
        val head = if (pinned) title else toggle("&#9662;")
        // pinned text is not framed - see [entry] for the rule. The blank lines
        // are what ends the sentence's paragraph, which the frame did before.
        return if (pinned) "$head\n\n$body\n\n" else head + frame(listOf(body)) + "\n"
    }

    /**
     * A block title as rendered text. Brackets are escaped - a title may
     * legitimately contain them ("See [#acdf] for details"), and an unescaped
     * one would end a link text early and leave the rest as prose. The escapes
     * are inert everywhere else, so the same form serves headlines too.
     */
    private fun titleText(title: String): String = title.ifBlank { "(untitled)" }
        .replace("[", "\\[").replace("]", "\\]")

    /** One entry of an embed frame; [framed] is false for the degraded forms
     *  (unresolved, nesting cap), which render as inline notes. */
    private class Entry(val markdown: String, val framed: Boolean)

    private fun entry(
        ref: String,
        depth: Int,
        expanded: (String) -> Boolean,
        resolve: (String) -> Resolved?,
        pinned: Boolean = false,
    ): Entry {
        val resolved = resolve(ref)
        return when {
            resolved == null -> Entry("&#9888; *unresolved reference: `$ref`*", framed = false)
            depth >= 1 -> Entry("*&#8230; see `$ref` (nested embed not expanded)*", framed = false)
            // folded: title-only line, '▸' toggle link
            !pinned && !expanded(ref) ->
                Entry("[&#9656; **${titleText(resolved.block.title)}**]($TOGGLE_SCHEME$ref)", framed = true)
            else -> {
                val body = rebaseForeign(
                    resolveEmbeds(resolved.block.bodyLines.joinToString("\n"), depth + 1, expanded, resolve),
                    resolved,
                )
                /*
                 * The rule for the rules: a FRAME marks text the reader let in -
                 * an answer they unfolded, a definition they asked for - so it
                 * has to be told apart from the prose around it. Pinned text was
                 * never asked for and never folds: the author wrote it here and
                 * keeps it elsewhere, so it should read as if it stood here,
                 * with nothing but its headline to say where it comes from.
                 */
                val head = if (pinned) "**${titleText(resolved.block.title)}**"
                else "[&#9662; **${titleText(resolved.block.title)}**]($TOGGLE_SCHEME$ref)"
                Entry("$head\n\n$body", framed = !pinned)
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

    /**
     * Rewrite the LINK destinations of an embedded foreign body so they point
     * where the foreign file meant them to point, seen from the file we are
     * rendering. A glossary entry saying `[RAII](#raii)` means "#raii in the
     * glossary" - resolved against the snippet it is embedded in, the fragment
     * finds nothing and the click does nothing at all, which is the least
     * helpful failure a link can have. `[here](img/x.md)` gets the same
     * treatment, relative to the foreign file's folder.
     *
     * [path] is the foreign file as the ref spelled it, e.g. `doc/glossary.md`.
     * Images are excluded (`!` before the bracket): [rebaseImages] has already
     * turned them into absolute file URLs.
     */
    private fun rebaseLinks(markdown: String, path: String): String {
        val folder = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return Regex("""(?<!!)(\[[^\]]*]\()([^)\s]+)\)""").replace(markdown) { match ->
            val destination = match.groupValues[2]
            val rebased = when {
                destination.startsWith("#") -> "$path$destination"
                "://" in destination || destination.startsWith("mailto:") ||
                    destination.startsWith("data:") || destination.startsWith("/") ||
                    destination.startsWith(TOGGLE_SCHEME) -> destination
                else -> "$folder$destination"
            }
            "${match.groupValues[1]}$rebased)"
        }
    }

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
