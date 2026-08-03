// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

/**
 * Topic-only CBL DSL, parsed from PSI comment tokens (never from raw text).
 *
 * A CBL block is a **block comment** whose first non-blank interior line is a
 * dash-framed title (slash-star … star-slash in C-family languages,
 * triple-quoted strings in Python):
 *
 *     slash-star ---- Uniform initialization ----
 *     body text, Markdown
 *     star-slash
 *
 * Depth is encoded by the width of the frame, four levels, one regex each
 * (see [CblParser.LEVEL_PATTERNS]):
 *  - `---- text ----` = level 1 (topic)
 *  - `--- text ---`   = level 2 (child topic, indented in the TOC)
 *  - `-- text --`     = level 3
 *  - `- text -`       = level 4
 *
 * Rules:
 *  - Only block comments count. Line comments (slash-slash, '#') are ordinary
 *    comments by design, as are trailing (end-of-line) block comments - the
 *    comment must be the first thing on its line.
 *  - The dash runs must match EXACTLY on both sides, so `----- text -----`
 *    (five or more) is a plain banner and stays invisible to the plugin.
 *  - The framed text is the title, the remaining interior lines are the body
 *    (Markdown).
 *  - A block extends until the next block or end of file. No end markers.
 *  - A title ending in an anchor - `## Background Const <a id="acdf"></a>` -
 *    is addressed by that label INSTEAD of by its title slug. The anchor is
 *    stripped from the title, so only the front part is ever shown (see
 *    [CblParser.splitLabel]).
 *  - A title starting with a dot (`---- .Setup ----`) is UNLISTED: the dot is
 *    a marker, not text - it is stripped from the title, and the block is
 *    skipped in the TOC. Everything else stays as it is (body, breadcrumb,
 *    folding, refs, extent), and its children keep their own listing.
 */

class CblBlock(val headerRange: TextRange) {
    /** interior lines below the header, trimmed into [bodyLines] by [finish] */
    internal val rawBody = mutableListOf<String>()

    var title: String = ""
        internal set
    val bodyLines = mutableListOf<String>()

    /** frame level: 1 = topic, 2 = child, 3/4 = deeper */
    internal var depth: Int = 1

    /** child topics are rendered indented in the TOC */
    val isChild: Boolean get() = depth > 1

    /**
     * Dotted title (`---- .Setup ----`): the block still carries its notes,
     * folds, and answers to refs, but it appears in NEITHER the outline nor the
     * breadcrumb - the two places that describe where a reader is. Scaffolding
     * a file needs but a reader should not have to walk past. The dot itself is
     * consumed by the parser, so [title] never carries it.
     */
    var isUnlisted: Boolean = false
        internal set

    /**
     * Explicit address, from a trailing anchor in the header
     * (`## Background Const <a id="acdf"></a>`), slugified. It REPLACES the
     * title slug in [slug]: the point of naming a block is that the name, not
     * the wording of its title, is what refs hold on to - so the title is free
     * to change, and a ref that still uses the old title slug should fail
     * loudly rather than resolve to something that only looks right.
     *
     * The label is consumed by the parser, so [title] never carries it, and
     * every place a title is shown - TOC, breadcrumb, embed frame, the link
     * text of a `[#ref]` - shows the front part alone.
     */
    var label: String? = null
        internal set

    /**
     * Fold range: from the end of the frame line to the end of the comment, so
     * the header line stays visible as source and only the note body collapses.
     * This range sits strictly INSIDE the IDE's own whole-comment fold region,
     * i.e. the two nest legally instead of fighting over an identical range.
     * Equal offsets mean a single-line comment - nothing worth hiding.
     */
    var foldStart: Int = headerRange.endOffset
        internal set
    var foldEnd: Int = headerRange.endOffset
        internal set

    /** True if the comment spans more than the frame line. */
    val isFoldable: Boolean get() = foldStart < foldEnd

    /** extent of the whole block: header start .. next block (exclusive) or EOF */
    var endOffset: Int = headerRange.endOffset
        internal set

    /**
     * Start of the block's extent. Normally the comment itself, but when the
     * comment OPENS a construct - a Python docstring, or the first comment
     * inside a C-family body - the line that opens it, so the signature belongs
     * to the block that documents it (see [CblParser.extentStart]). Folding is
     * unaffected: it works on [foldStart] / [foldEnd].
     */
    var startOffset: Int = headerRange.startOffset
        internal set

    /** Title as inline HTML (Markdown rendered), cached for the TOC renderer. */
    val titleHtml: String by lazy { CblMarkdown.inlineToHtml(title.ifBlank { "(untitled)" }) }

    /** The block's address in #refs: its [label] if it declares one, otherwise
     *  the GitHub-style anchor slug of the title. */
    val slug: String by lazy { label ?: CblModel.slugOf(title) }

    internal fun finish() {
        bodyLines.addAll(rawBody.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() })
    }

    override fun toString(): String = title
}

class CblModel(val blocks: List<CblBlock>) {
    /** The TOC's view of the file: everything but the dotted (unlisted)
     *  blocks. Every other consumer works on [blocks]. */
    val listedBlocks: List<CblBlock> get() = blocks.filter { !it.isUnlisted }

    fun blockAt(offset: Int): CblBlock? =
        blocks.lastOrNull { offset >= it.startOffset && offset <= it.endOffset }

    /**
     * Resolve a block reference like "code-style" or "final-remarks:code-style".
     * The last segment is the target title slug; preceding segments qualify the
     * ancestor chain (innermost last) and must match a suffix of it - so a
     * sub-child can be addressed by its immediate parent alone. First match in
     * file order wins (same convention as [blockAt]).
     */
    fun blockByRef(ref: String): CblBlock? {
        val segments = ref.split(':').map { slugOf(it) }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val target = segments.last()
        val qualifier = segments.dropLast(1)
        blocks.forEachIndexed { i, block ->
            if (block.slug == target) {
                val chain = ancestorChain(i).map { it.slug }
                if (qualifier.isEmpty() || chain.takeLast(qualifier.size) == qualifier) return block
            }
        }
        return null
    }

    /** Enclosing blocks of [block], outermost first - the breadcrumb chain.
     *  Empty for depth-1 topics (and unknown blocks). */
    fun chainOf(block: CblBlock): List<CblBlock> {
        val index = blocks.indexOf(block)
        return if (index < 0) emptyList() else ancestorChain(index)
    }

    /** Enclosing blocks of blocks[index], outermost first (nearest preceding
     *  block of smaller depth, repeated upwards). */
    private fun ancestorChain(index: Int): List<CblBlock> {
        val chain = mutableListOf<CblBlock>()
        var depth = blocks[index].depth
        for (i in index - 1 downTo 0) {
            if (depth == 1) break
            if (blocks[i].depth < depth) {
                chain.add(0, blocks[i])
                depth = blocks[i].depth
            }
        }
        return chain
    }

    companion object {
        /** Slugify a title: markdown decoration and quotes dropped, remaining
         *  non-word runs collapse to '-' ("**Final Remarks**" -> "final-remarks",
         *  "'main'-guard" -> "main-guard"). */
        fun slugOf(title: String): String = title.lowercase()
            .replace(Regex("[`*_~\"']"), "")
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), "-")
            .trim('-')
    }
}

object CblParser {

    const val MAX_DEPTH = 4

    /**
     * Built-in header patterns, one per level, index 0 = level 1: the frame
     * narrows as the level gets deeper. Two capture groups:
     *  - `title` - the framed text (group 1 also works, so an alternative
     *    pattern needs no named group)
     *  - `rest`  - text following the frame ON THE SAME LINE, which becomes
     *    the first body line (`... --- Title --- explain ...`); optional
     *
     * Each pattern pins its dash run EXACTLY - `(?!-)` / `(?<!-)` around both
     * runs - which buys three things for free: levels cannot bleed into each
     * other, the two sides must be symmetric, and any wider run
     * (`----- banner -----`) is not a CBL block at all. The title must contain
     * at least one non-space character, so a pure dash rule (`--------`) is a
     * separator, not an untitled topic.
     *
     * Overridable per course via `cbl.properties` (`block.level1.regex` …),
     * see [CblConfig]: the *shape of the header* is a course convention, while
     * the semantics around it - block comment, four levels, title plus body -
     * are the language and stay fixed.
     */
    val LEVEL_PATTERNS: List<Regex> = (MAX_DEPTH downTo 1).map { dashes ->
        val frame = "-{$dashes}"
        // ---- topic ---- / --- child --- / -- detail -- / - aside -
        Regex("^$frame(?!-)\\s*(?<title>\\S.*?)\\s*(?<!-)$frame(?!-)\\s*(?<rest>.*)\$")
    }

    fun parse(file: PsiFile, patterns: List<Regex> = LEVEL_PATTERNS): CblModel {
        val text = file.text
        val comments = mutableListOf<PsiElement>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (isBlockComment(element)) {
                    /*
                     * Take the match and do NOT descend. A doc comment is a
                     * COMPOSITE, not a leaf: Kotlin parses slash-star-star into
                     * a KDoc with its own token children, Java into a
                     * PsiDocComment, while CLion hands out one plain comment
                     * leaf - which is why the same header worked in C++ and was
                     * silently ignored in Kotlin. Both composites implement
                     * PsiDocCommentBase, which extends PsiComment, so the type
                     * test below is enough; the children are the ones that must
                     * not be visited afterwards.
                     */
                    comments.add(element)
                    return
                }
                super.visitElement(element)
            }
        })
        comments.sortBy { it.textRange.startOffset }

        val blocks = mutableListOf<CblBlock>()
        for (comment in comments) {
            val range = comment.textRange
            if (!isStandalone(text, range.startOffset)) continue // ordinary comment
            val header = matchHeader(interiorLines(comment.text), patterns) ?: continue
            val block = CblBlock(range)
            block.startOffset =
                extentStart(text, range.startOffset, blocks.lastOrNull()?.headerRange?.endOffset)
            block.depth = header.depth
            block.title = header.title
            block.isUnlisted = header.unlisted
            block.label = header.label
            block.rawBody.addAll(header.body)
            // fold the body only: start after the frame line, which is the
            // header.line-th line of the comment (0-based, usually 0)
            block.foldStart = lineEnd(text, range.startOffset, header.line, range.endOffset)
            block.foldEnd = range.endOffset
            blocks.add(block)
        }

        blocks.forEachIndexed { i, block ->
            block.finish()
            block.endOffset = if (i + 1 < blocks.size) blocks[i + 1].startOffset - 1 else text.length
        }
        return CblModel(blocks)
    }

    /**
     * Markdown files have no comments - there, ATX headings ARE the blocks:
     * '## Code Style' = block titled "Code Style", body until the next
     * heading, depth = heading level (capped at [MAX_DEPTH]). Slugs thus
     * match GitHub's own anchors. Fenced code blocks are skipped so a
     * Python '# comment' inside a fence is not mistaken for a heading.
     * The dotted-title rule holds here as well ('## .Internals').
     */
    fun parseMarkdown(text: String): CblModel {
        val blocks = mutableListOf<CblBlock>()
        var inFence = false
        var offset = 0
        for (line in text.lines()) {
            val lineEnd = offset + line.length
            val t = line.trimStart()
            val isFenceLine = t.startsWith("```") || t.startsWith("~~~")
            val hashes = if (inFence || isFenceLine) 0 else t.takeWhile { it == '#' }.length
            if (hashes in 1..6 && t.getOrNull(hashes) == ' ') {
                val block = CblBlock(TextRange(offset, lineEnd))
                block.depth = minOf(hashes, MAX_DEPTH)
                val (marked, unlisted) = splitMarker(t.substring(hashes + 1).trim())
                val (title, label) = splitLabel(marked)
                block.title = title
                block.isUnlisted = unlisted
                block.label = label
                blocks.add(block)
            } else {
                blocks.lastOrNull()?.rawBody?.add(line.trimEnd())
            }
            if (isFenceLine) inFence = !inFence
            offset = lineEnd + 1
        }
        blocks.forEachIndexed { i, block ->
            block.finish()
            block.endOffset = if (i + 1 < blocks.size) blocks[i + 1].startOffset - 1 else text.length
        }
        return CblModel(blocks)
    }

    /**
     * True for the block-comment forms the DSL reads: slash-star in C-family
     * languages and triple-quoted strings in Python (a string leaf, NOT a
     * comment - hence the second branch). Line comments are deliberately not
     * eligible.
     *
     * The comment branch does not require a leaf. Plain slash-star comments are
     * one token in every language, but the doc form is a composite - KDoc in
     * Kotlin, PsiDocComment in Java - and both implement PsiDocCommentBase,
     * hence [PsiComment]. The string branch keeps the leaf test: in Python the
     * enclosing expression statement carries the same text, and taking it
     * instead of the string would report the wrong range.
     */
    private fun isBlockComment(element: PsiElement): Boolean {
        val t = element.text.trimStart()
        return if (element is PsiComment) t.startsWith("/*")
        else element.firstChild == null && (t.startsWith("\"\"\"") || t.startsWith("'''"))
    }

    /**
     * Comment interior: delimiters removed, boxed-comment star decoration
     * stripped, split into lines. Empty for anything that is not one of the
     * recognized block-comment forms.
     */
    private fun interiorLines(raw: String): List<String> {
        val t = raw.trim()
        val interior = when {
            t.startsWith("/*") -> t.removePrefix("/*").removeSuffix("*/")
            t.startsWith("\"\"\"") -> t.removePrefix("\"\"\"").removeSuffix("\"\"\"")
            t.startsWith("'''") -> t.removePrefix("'''").removeSuffix("'''")
            else -> return emptyList()
        }
        return cleanLines(interior)
    }

    internal class Header(
        val depth: Int,
        val title: String,
        val body: List<String>,
        /** 0-based line of the frame WITHIN the comment (0 unless the frame
         *  sits below a bare opening delimiter) */
        val line: Int,
        /** title was dotted - keep it out of the TOC (see [CblBlock.isUnlisted]) */
        val unlisted: Boolean = false,
        /** trailing `[#label]`, the block's explicit address (see [CblBlock.label]) */
        val label: String? = null,
    )

    /**
     * Splits the unlisted marker off a title: a LEADING dot means "not in the
     * TOC" and is not part of the text (`.Setup helpers` -> `Setup helpers`).
     * Exactly one dot is consumed, and only at the very front - a dot anywhere
     * else (`v1.2`, `etc.`) is ordinary text.
     */
    internal fun splitMarker(title: String): Pair<String, Boolean> =
        if (title.startsWith(".")) title.removePrefix(".").trim() to true else title to false

    /**
     * A label at the END of a title: `Background Const <a id="acdf"></a>`.
     *
     * An ordinary HTML anchor, and nothing invented: every Markdown renderer
     * passes it through and shows NOTHING, so the heading stays clean in the
     * panel, in exports and on GitHub - where it doubles as a real anchor
     * (`#user-content-acdf`) - and it can be tabbed far to the right, out of
     * the way of the prose. A syntax of our own would have had to be explained
     * to every one of those renderers, and to every reader.
     *
     * `name=` is accepted alongside `id=` (GitHub's own docs still use it), as
     * is a lone or self-closed `<a …>`: only `<a id="x"></a>` is valid HTML5 -
     * `a` is not a void element - but a parser that quietly ignored the other
     * spellings would leave raw HTML in the title, where it renders as nothing
     * and is impossible to see.
     */
    private val LABEL = Regex(
        """\s*<a\s[^<>]*\b(?:id|name)\s*=\s*["']([^"']+)["'][^<>]*>\s*(?:</a\s*>)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Splits a trailing [LABEL] off a title, slugified. Null label = the title
     * keeps its own slug, i.e. everything written before this existed still
     * addresses the way it always did.
     */
    internal fun splitLabel(title: String): Pair<String, String?> {
        val match = LABEL.find(title) ?: return title to null
        val label = CblModel.slugOf(match.groupValues[1])
        if (label.isEmpty()) return title to null
        return title.substring(0, match.range.first).trim() to label
    }

    /**
     * Offset of the end of the [n]-th line (0-based) starting at [from], i.e.
     * the position of its newline - or [limit] if that line is the last one
     * inside the range. Used to place the fold start behind the frame line.
     */
    private fun lineEnd(text: String, from: Int, n: Int, limit: Int): Int {
        var offset = from
        repeat(n) {
            val newline = text.indexOf('\n', offset)
            if (newline < 0 || newline >= limit) return limit
            offset = newline + 1
        }
        val newline = text.indexOf('\n', offset)
        return if (newline < 0 || newline > limit) limit else newline
    }

    /**
     * Matches the first non-blank interior line against [patterns] and returns
     * level, title and body - or null if the line is not a framed header, i.e.
     * an ordinary block comment. Patterns are tried in order (widest frame
     * first); the first hit wins. Text following the frame on the same line
     * (group `rest`) becomes the first body line, so a one-liner
     * `... --- Title --- explain ...` carries a note as well.
     */
    private fun matchHeader(lines: List<String>, patterns: List<Regex>): Header? {
        val index = lines.indexOfFirst { it.isNotBlank() }
        if (index < 0) return null
        val line = lines[index].trim()
        patterns.forEachIndexed { i, pattern ->
            val match = pattern.find(line)
            if (match != null) {
                val title = (groupOf(match, "title") ?: match.groupValues.getOrNull(1))?.trim()
                if (!title.isNullOrEmpty()) {
                    val rest = groupOf(match, "rest")?.trim().orEmpty()
                    val body = lines.drop(index + 1)
                    val (marked, unlisted) = splitMarker(title)
                    val (text, label) = splitLabel(marked)
                    return Header(
                        i + 1, text,
                        if (rest.isEmpty()) body else listOf(rest) + body,
                        line = index,
                        unlisted = unlisted,
                        label = label,
                    )
                }
            }
        }
        return null
    }

    /** Named group, or null if the pattern does not declare it. */
    private fun groupOf(match: MatchResult, name: String): String? =
        runCatching { (match.groups as? MatchNamedGroupCollection)?.get(name)?.value }.getOrNull()

    /**
     * Start of a block's extent, given the offset of its comment.
     *
     * A CBL comment may sit ABOVE the definition it documents (the C++ form,
     * and a bare string statement in Python) or INSIDE it (the Python docstring
     * form). In the second case the signature line would fall into the PREVIOUS
     * block, so the caret on a `def` would select the wrong topic and any
     * range-based join would attribute the function to its predecessor.
     *
     * The construct's opening line is therefore folded into the block when the
     * previous non-blank line ends with `:` or `{` - language-agnostic, since
     * every language the DSL targets opens a body that way.
     *
     * UNLESS that opening line already has an owner: if a CBL comment sits
     * directly above it, with nothing but whitespace in between, then THAT
     * comment documents the construct and keeps it. Otherwise
     *
     *     ... --- namespace --- ...
     *     namespace {
     *         ... --- define_and_init --- ...
     *         void define_and_init() {
     *
     * would hand `namespace {` to the inner block, and a caret on the line the
     * namespace comment was written for would select the function below it.
     * [previousCommentEnd] is the end of the preceding CBL comment, or null if
     * this is the first block in the file.
     */
    private fun extentStart(text: String, commentStart: Int, previousCommentEnd: Int?): Int {
        var lineStart = commentStart
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var cursor = lineStart - 1 // the newline ending the previous line, or -1
        while (cursor >= 0) {
            var previousStart = cursor
            while (previousStart > 0 && text[previousStart - 1] != '\n') previousStart--
            var last = cursor - 1
            while (last >= previousStart && text[last].isWhitespace()) last--
            if (last < previousStart) { // blank line - keep walking up
                cursor = previousStart - 1
                continue
            }
            if (text[last] != ':' && text[last] != '{') return commentStart
            // the opening line is claimed by the CBL comment directly above it,
            // if there is one - nothing but whitespace may stand between them
            val claimed = previousCommentEnd != null &&
                previousCommentEnd <= previousStart &&
                (previousCommentEnd until previousStart).all { text[it].isWhitespace() }
            return if (claimed) commentStart else previousStart
        }
        return commentStart
    }

    /** True if only whitespace precedes [offset] on its line. */
    private fun isStandalone(text: String, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '\n') return true
            if (!c.isWhitespace()) return false
            i--
        }
        return true
    }

    /**
     * Split content into lines, stripping boxed-comment star decorations
     * (a lone '*' or '* ' prefix). Bare '*'/'**' prefixes are left intact so
     * Markdown emphasis survives; consequently, Markdown lists in CBL
     * comments should use '-' rather than '*'.
     *
     * RELATIVE indentation is preserved, absolute indentation is not: the
     * common indent of the body is measured and removed, so the body starts at
     * column 0 no matter how deep the comment sits in the code, while anything
     * indented FURTHER keeps the difference. That difference is structure in
     * Markdown - it is what makes a sub-list a sub-list - and trimming every
     * line (which this did until v1.0.3) silently flattened every nested list
     * in a CBL comment into a sequence of sibling lists.
     *
     * The frame line is excluded from the measurement and trimmed outright:
     * it usually starts one space after the opening delimiter, an accidental
     * column that the whole body would otherwise inherit.
     */
    private fun cleanLines(content: String): List<String> {
        val lines = content.lines().map { stripDecoration(it) }
        val frame = lines.indexOfFirst { it.isNotBlank() }
        val indent = lines.drop(frame + 1)
            .filter { it.isNotBlank() }
            .minOfOrNull { line -> line.takeWhile { it == ' ' }.length } ?: 0
        return lines.mapIndexed { i, line ->
            when {
                line.isBlank() -> ""
                i == frame -> line.trim()
                else -> line.substring(indent)
            }
        }
    }

    /**
     * One line without its boxed-comment decoration: for a ' * ' line the star
     * column becomes the new left margin (so indentation AFTER the star is what
     * counts), everything else keeps its leading whitespace. Leading tabs are
     * expanded first - CommonMark measures indentation in columns, and a body
     * that mixes tabs and spaces would otherwise dedent by the wrong amount.
     */
    private fun stripDecoration(raw: String): String {
        val line = expandLeadingTabs(raw.trimEnd())
        val start = line.indexOfFirst { it != ' ' }
        if (start < 0) return ""
        val rest = line.substring(start)
        return when {
            rest == "*" -> ""
            rest.startsWith("* ") -> rest.substring(2)
            else -> line
        }
    }

    /** Leading tabs as [TAB_WIDTH] spaces each. Not true tab stops - close
     *  enough for indentation that is compared against itself. */
    private fun expandLeadingTabs(line: String): String {
        val lead = line.takeWhile { it == ' ' || it == '\t' }
        if ('\t' !in lead) return line
        val width = lead.sumOf { if (it == '\t') TAB_WIDTH else 1 }
        return " ".repeat(width) + line.substring(lead.length)
    }

    private const val TAB_WIDTH = 4
}

/**
 * Models of OTHER files (cross-file refs, glossary/dictionary use), cached by
 * modification stamp. Markdown files are parsed by headings from document
 * text; everything else goes through the PSI-comment parser.
 * Call inside a read action.
 */
object CblForeign {
    private val markdownExtensions = setOf("md", "markdown")

    // keyed by path; the cached CblConfig is compared by identity, so a saved
    // cbl.properties edit (which yields a fresh instance) invalidates as well
    private val cache = HashMap<String, Triple<Long, CblConfig, CblModel>>()

    fun model(project: Project, file: VirtualFile): CblModel? {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        val config = CblConfig.forFile(project, file)
        cache[file.path]?.let { (cachedStamp, cachedConfig, model) ->
            if (cachedStamp == stamp && cachedConfig === config) return model
        }
        val model = if (file.extension?.lowercase() in markdownExtensions) {
            CblParser.parseMarkdown(document?.text ?: return null)
        } else {
            PsiManager.getInstance(project).findFile(file)
                ?.let { CblParser.parse(it, config.levelPatterns) } ?: return null
        }
        // the cache lives in an object, i.e. for the IDE's lifetime and across
        // projects - so it must not grow without bound (same guard as CblConfig)
        if (cache.size > 64) cache.clear()
        cache[file.path] = Triple(stamp, config, model)
        return model
    }
}
