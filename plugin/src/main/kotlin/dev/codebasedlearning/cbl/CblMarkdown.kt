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
     * handling are inherited unchanged, which keeps the `cbl-slot:` scheme
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
     * The two ref forms, and the whole difference between them:
     *
     *  - `[text](path#ref)` - a LINK: the target's headline plus a small '\u25b8'.
     *    Nothing is shown until the reader clicks it, and clicking again hides
     *    it. This is what a question, a glossary word, a "see also" is.
     *  - `![text](path#ref)` - an EMBED: the target's text, always there. This
     *    is what a passage the author wrote here but keeps elsewhere is.
     *
     * Brackets mean "on demand", the bang means "shown" - one axis, no third
     * form. Both are ordinary Markdown, so a foreign renderer degrades them to
     * a link and to a broken image respectively, never to junk.
     *
     * An embed is an image whose destination carries a '#'; ordinary images
     * never do. A link must NOT be preceded by '!', hence the lookbehind.
     */
    internal val EMBED = Regex("""!\[[^\]]*]\(([^)\s]*#[^)\s]+)\)""")
    internal val LINK = Regex("""(?<!!)\[([^\]]*)]\(([^)\s]*#[^)\s]+)\)""")

    /**
     * Toggle or replace the SLOT a link owns: `cbl-slot:<id>:<ref>`.
     *
     * Separated by a COLON, not by a pipe: the href travels through a
     * GitHub-flavoured Markdown parser, which reads '|' as a table delimiter,
     * and through an HTML generator that may percent-encode it. Slot ids are
     * built from letters, digits and '-', so the first colon after the scheme
     * is unambiguous.
     *
     * Every link occurrence owns one slot, identified by where it stands, and
     * the slot holds at most one target at a time. The anchor's own click
     * opens it, clicking the anchor again with the same target closes it, and
     * a link INSIDE the slot carries the same id with a different target -
     * which is what makes a lookup chain replace the block instead of nesting
     * a new one inside it.
     */
    const val SLOT_SCHEME = "cbl-slot:"

    /** Close the slot with this id: `cbl-close:<id>`. */
    const val CLOSE_SCHEME = "cbl-close:"

    /** Open a ref in the EDITOR: `cbl-open:<ref>`. The slot's flag icon - the
     *  one gesture that leaves the panel, and the reason a link no longer has
     *  to be one. */
    const val OPEN_SCHEME = "cbl-open:"

    /**
     * Renders the ref forms of one notes page.
     *
     * [openRef] answers, for a slot id, which target that slot currently shows
     * (null = closed); [resolve] turns a destination into a block. Both are the
     * panel's business - this class only decides what the Markdown looks like.
     */
    class RefRenderer(
        private val openRef: (String) -> String?,
        private val resolve: (String) -> Resolved?,
    ) {
        /**
         * [idPrefix] must be unique per rendered body: slot ids are built from
         * it plus the occurrence index, which is what keeps two `[#raii]` in one
         * file independent of each other, and stable across re-renders.
         */
        fun render(markdown: String, idPrefix: String): String =
            render(markdown, idPrefix, slot = null, depth = 0)

        private fun render(markdown: String, idPrefix: String, slot: String?, depth: Int): String =
            renderLinks(renderEmbeds(markdown, idPrefix, slot, depth), idPrefix, slot, depth)

        /** `![](#ref)`: the target's headline and its text, unconditionally. No
         *  frame - see the design note on [RefRenderer]: this is the author's
         *  prose, kept elsewhere, and prose is not boxed. */
        private fun renderEmbeds(markdown: String, idPrefix: String, slot: String?, depth: Int): String {
            var index = 0
            return CblMarkdown.EMBED.replace(markdown) { match ->
                val ref = match.groupValues[1]
                val id = "$idPrefix-e${index++}"
                val resolved = resolve(ref)
                when {
                    resolved == null -> unresolved(ref)
                    // an embed inside an embedded body would recurse forever
                    depth >= 1 -> "*&#8230; see `$ref` (nested embed not expanded)*"
                    else -> {
                        /*
                         * The very same block a link opens, minus the header
                         * bar: nothing to close, and nothing to say about where
                         * the text comes from that the headline does not
                         * already say. Headline in the referring line, body
                         * indented below - see [block].
                         */
                        "**${CblMarkdown.titleText(resolved.block.title)}**" +
                            block(inner(resolved, id, slot, depth))
                    }
                }
            }
        }

        /**
         * `[text](#ref)`: the text plus an arrow, and - when the slot is open -
         * the target's body below it.
         *
         * Inside a slot ([slot] set) links are anchors only: they carry the
         * enclosing slot's id, so a click replaces that block instead of
         * opening another one under it. Lookups stay flat, however far a chain
         * of definitions goes.
         */
        private fun renderLinks(markdown: String, idPrefix: String, slot: String?, depth: Int): String {
            var index = 0
            return CblMarkdown.LINK.replace(markdown) { match ->
                val text = match.groupValues[1]
                val ref = match.groupValues[2]
                /*
                 * Skip what this renderer has already produced. An embed is
                 * rendered BEFORE the link pass runs, so its body arrives here
                 * carrying finished anchors - and their `cbl-slot:<id>:<ref>`
                 * destination contains a '#' like any other, so the pass would
                 * happily wrap an anchor in a second anchor. Ours are the only
                 * destinations with a scheme of ours.
                 */
                if (ref.startsWith(CblMarkdown.SLOT_SCHEME) || ref.startsWith(CblMarkdown.OPEN_SCHEME) ||
                    ref.startsWith(CblMarkdown.CLOSE_SCHEME)
                ) return@replace match.value
                /*
                 * A link that points nowhere says so, closed or not. Resolving
                 * only on click would hide the typo until someone clicked it,
                 * and an arrow that opens an empty block is the worst of the
                 * three outcomes - the reader cannot tell it from a bug.
                 */
                if (resolve(ref) == null) return@replace unresolved(ref)
                val id = slot ?: "$idPrefix-l${index++}"
                val shownRef = if (slot == null) openRef(id) else null
                val anchor = "[$text ${if (shownRef == null) "&#9656;" else "&#9662;"}]($SLOT_SCHEME$id:$ref)"
                if (shownRef == null) return@replace anchor
                val shown = resolve(shownRef)
                    ?: return@replace anchor + block(unresolved(shownRef), bar(id, shownRef, null))
                anchor + block(inner(shown, id, id, depth), bar(id, shownRef, shown))
            }
        }

        /** The target's body, rebased to ITS file and rendered with the same
         *  machinery - so an embed inside it works, and a link inside it knows
         *  which slot it belongs to. */
        private fun inner(target: Resolved, idPrefix: String, slot: String?, depth: Int): String =
            render(
                CblMarkdown.rebaseForeign(target.block.bodyLines.joinToString("\n"), target),
                idPrefix, slot, depth + 1,
            )

        /**
         * The block both forms use: a raw-HTML wrapper the stylesheet indents
         * and rules off, holding [bar] (a link's header, empty for an embed)
         * and the borrowed text. Raw HTML because Swing has no <details> and no
         * float; blank lines around the content because CommonMark renders the
         * Markdown inside an HTML block only when they are there.
         */
        private fun block(body: String, bar: String = ""): String =
            "\n\n<div class='slot'>\n\n$bar$body\n\n</div>\n\n"

        /**
         * A link's header: where the text comes from, a flag that opens it in
         * the editor, a cross that closes the slot. An embed has none - it was
         * not opened, so there is nothing to close, and its headline already
         * stands in the line above.
         */
        private fun bar(id: String, ref: String, shown: Resolved?): String {
            val where = shown?.let {
                if (it.path == null) it.block.title else "${it.path} &#9656; ${it.block.title}"
            } ?: ref
            return "<table class='slotbar' width='100%'><tr>" +
                "<td class='slotwhere'><a href='$OPEN_SCHEME$ref'>&#9873;</a> $where</td>" +
                "<td class='slotclose' align='right'><a href='$CLOSE_SCHEME$id'>&#10005;</a></td>" +
                "</tr></table>\n\n"
        }

        private fun unresolved(ref: String) = "&#9888; *unresolved reference: `$ref`*"
    }

    /**
     * A block title as rendered text. Brackets are escaped - a title may
     * legitimately contain them ("See [#acdf] for details"), and an unescaped
     * one would end a link text early and leave the rest as prose. The escapes
     * are inert everywhere else, so the same form serves headlines too.
     */
    internal fun titleText(title: String): String = title.ifBlank { "(untitled)" }
        .replace("[", "\\[").replace("]", "\\]")

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
     * `[#main-guard]` links to a block, `![#main-guard]` shows it, and an
     * explicit path works too (`![../notes.md#tco]`). A CommonMark shortcut
     * reference, so a foreign renderer prints it as literal text instead of
     * mangling it.
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
     * Rewrites [SHORTCUT] refs into canonical Markdown, BEFORE rendering - so
     * everything downstream (slots, embeds, image rebasing, click handling)
     * sees only the long forms and stays unchanged.
     *
     * A path-less fragment is looked up along [glossaryPath] (the configured
     * files as paths relative to the current file), in order, first hit wins,
     * and finally in the current file. [titleOf] does the probing and supplies
     * the link text; an unresolved ref renders the usual warning rather than a
     * link into the void.
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
        val text = titleOf(destination)
        when {
            // the embed form needs no link text - it shows the headline itself
            bang.isNotEmpty() -> "![]($destination)"
            text == null -> "&#9888; *unresolved reference: `$content`*"
            else -> "[$text]($destination)"
        }
    }

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
                    destination.startsWith(SLOT_SCHEME) -> destination
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
