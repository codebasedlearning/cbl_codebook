// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

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
     * The three ref forms, one per intention:
     *
     *  - `[text](path#ref)` - a LINK, and nothing more than one: clicking it
     *    opens the file, at the block if the fragment names one. Markdown's own
     *    meaning, kept, so a reader who knows links is never surprised.
     *  - `![text](path#ref)` - an EMBED: the target's text, always there. The
     *    image form, transclusion instead of navigation - again Markdown's own
     *    meaning, one step further.
     *  - `!![text](path#ref)` - a FOLD: the headline plus a small arrow, and
     *    the text only when the reader asks for it.
     *
     * So `!` shows and `!!` offers, while a bare link navigates. The two banged
     * forms are the ones this plugin invents, and both degrade in a foreign
     * renderer to something harmless - a broken image, and a literal `!` in
     * front of a link.
     *
     * An embed is an image whose destination carries a '#'; ordinary images
     * never do. The lookbehind on EMBED keeps the `!!` form out of it.
     */
    internal val EMBED = Regex("""(?<!!)!\[[^\]]*]\(([^)\s]*#[^)\s]+)\)""")
    internal val FOLD = Regex("""!!\[([^\]]*)]\(([^)\s]*#[^)\s]+)\)""")

    /**
     * Inside BORROWED text - an embedded or unfolded body - a plain link counts
     * as a fold as well, so this matches both `!![t](d)` and `[t](d)` while
     * still leaving the single-bang embed form alone.
     *
     * The reason is whose context a click happens in. A glossary entry linking
     * to a neighbouring entry was written as a document, by an author who could
     * not know where it would be cited; the reader, however, is in the middle
     * of a snippet, and following that link to the file would cost them the
     * context that made the entry worth reading. So: links in your own text
     * take you somewhere, links in borrowed text keep you where you are. The
     * flag in the block's header is the way out for when the file is what you
     * actually want.
     *
     * A link WITHOUT a fragment is untouched here as well - there is no block
     * to look up, so it stays what it looks like.
     */
    internal val BORROWED_REF = Regex("""(?:!!|(?<!!))\[([^\]]*)]\(([^)\s]*#[^)\s]+)\)""")

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

    /**
     * Open a ref in the EDITOR: `cbl-open:<ref>`. Reached only with the menu
     * modifier (Cmd on macOS, Ctrl elsewhere) - on a headline, where a plain
     * click does nothing, and on a fold's arrow, where a plain click toggles.
     * One gesture for "the file itself", wherever the reader is.
     */
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
            renderFolds(renderEmbeds(markdown, idPrefix, slot, depth), idPrefix, slot, depth)

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
                         * The very same block a fold opens, minus the header
                         * bar: nothing to close, and nothing to say about where
                         * the text comes from that the headline does not
                         * already say. Headline in the referring line, body
                         * indented below - see [block].
                         *
                         * The headline IS the link: link colour, and
                         * Cmd/Ctrl-click opens the source. A plain click does
                         * nothing on purpose - text that is simply THERE should
                         * not move the editor under the reader's hands. Swing
                         * paints the hand cursor over any href, which overstates
                         * the plain click slightly; the colour is worth it.
                         */
                        "[**${CblMarkdown.titleText(resolved.block.title)}**]($OPEN_SCHEME$ref)" +
                            block(inner(resolved, id, slot, depth))
                    }
                }
            }
        }

        /**
         * `!![text](#ref)`: the text plus an arrow, and - when the slot is open
         * - the target's body below it. An ordinary link is left untouched
         * here: it is a link, and the panel opens the file it names.
         *
         * Inside a slot ([slot] set) folds are anchors only: they carry the
         * enclosing slot's id, so a click replaces that block instead of
         * opening another one under it. Lookups stay flat, however far a chain
         * of definitions goes.
         */
        private fun renderFolds(markdown: String, idPrefix: String, slot: String?, depth: Int): String {
            var index = 0
            // depth 0 is the file's OWN text, where a link is a link; deeper is
            // borrowed text, where a link is a lookup (see [BORROWED_REF])
            val pattern = if (depth == 0) CblMarkdown.FOLD else CblMarkdown.BORROWED_REF
            return pattern.replace(markdown) { match ->
                val text = match.groupValues[1]
                val ref = match.groupValues[2]
                /*
                 * Skip what this renderer has already produced: inside borrowed
                 * text the pattern also matches plain links, and our own
                 * anchors are plain links whose destination carries a '#'.
                 */
                if (ref.startsWith(CblMarkdown.SLOT_SCHEME) || ref.startsWith(CblMarkdown.OPEN_SCHEME)) {
                    return@replace match.value
                }
                /*
                 * A fold that points nowhere says so, open or not. Resolving
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
                    ?: return@replace anchor + block(unresolved(shownRef))
                /*
                 * A headline inside the block, but only once the reader has
                 * clicked THROUGH it: while the block still shows what the
                 * arrow above it names, saying so again would be one title too
                 * many. After a replacement the arrow names something else, and
                 * without a headline the text would sit there unattributed -
                 * which is the context the breadcrumb used to carry, minus the
                 * chain nobody needed.
                 */
                val head =
                    if (shownRef == ref) ""
                    else "[**${CblMarkdown.titleText(shown.block.title)}**]($OPEN_SCHEME$shownRef)\n\n"
                anchor + block(head + inner(shown, id, id, depth))
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
         * and rules off, holding the borrowed text and nothing else. No header,
         * no provenance line, no close button - the arrow that opened it closes
         * it, and the headline above it already says what this is. Raw HTML
         * because Swing has no <details>; blank lines around the content
         * because CommonMark renders the Markdown inside an HTML block only
         * when they are there.
         */
        private fun block(body: String): String =
            "\n\n<div class='slot'>\n\n$body\n\n</div>\n\n"

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
     * Every destination of a body that came from ANOTHER file, rewritten so it
     * resolves from the file being rendered.
     *
     * A glossary entry saying `!![RAII](#raii)` means "#raii in the GLOSSARY" -
     * resolved against the snippet it was embedded in, the fragment finds
     * nothing and the click does nothing at all, the least helpful failure a
     * ref can have. Same for `[here](img/x.md)` and for an image: relative to
     * the foreign file, not to ours.
     *
     * ONE pass over all three forms, because the previous two - one for images,
     * one for links - split exactly where the `!!` form sits, and each thought
     * the other owned it. What a destination means is decided by its shape, not
     * by the bangs in front of it: a '#' makes it a ref, everything else is a
     * path, and only a path with one bang is an image.
     */
    fun rebaseForeign(markdown: String, target: Resolved): String {
        val path = target.path
        val baseDir = target.baseDir
        if (path == null && baseDir == null) return markdown     // same file, nothing to move
        val folder = path?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
        return Regex("""(!{0,2})(\[[^\]]*]\()([^)\s]+)\)""").replace(markdown) { match ->
            val (bang, head, destination) = match.destructured
            val rebased = when {
                // ours, already rewritten - and absolute of any kind
                destination.startsWith(SLOT_SCHEME) || destination.startsWith(OPEN_SCHEME) ||
                    "://" in destination ||
                    destination.startsWith("mailto:") || destination.startsWith("data:") ||
                    destination.startsWith("/") -> destination
                // a ref into the foreign file itself, or into a third one
                destination.startsWith("#") -> if (path == null) destination else "$path$destination"
                '#' in destination -> "$folder$destination"
                // an image must load from the foreign folder, as an absolute URL
                bang == "!" && baseDir != null -> java.io.File(baseDir, destination).toURI().toString()
                else -> "$folder$destination"
            }
            "$bang$head$rebased)"
        }
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
            bang == "!" -> "![]($destination)"
            text == null -> "&#9888; *unresolved reference: `$content`*"
            // fold and link both read as their target's headline
            bang == "!!" -> "!![$text]($destination)"
            else -> "[$text]($destination)"
        }
    }

}
