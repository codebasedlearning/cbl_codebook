// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import junit.framework.TestCase

/**
 * Refs, embeds and Markdown rendering: slugs, hierarchical resolution, the short
 * `[#ref]` forms, embed framing.
 *
 * Plain JUnit on purpose. None of this needs an IDE: a [CblModel] comes just as
 * well from [CblParser.parseMarkdown] as from PSI comments (that the PSI side
 * produces the same shape is what [CblParserTest] is for), and `CblMarkdown` is a
 * string-to-string pipeline. Which makes this the cheapest suite to run and the
 * one worth having the most tests in - these are the failures a human never spots
 * by looking at the panel, unlike a wrong colour or a missing icon.
 */
class CblRefsTest : TestCase() {

    /** Topic / sibling topic / child, the shape the refs tests need. */
    private fun model(): CblModel = CblParser.parseMarkdown(
        """
        # Content

        See [style](#final-remarks:code-style).

        ![](#code-style)

        # Final Remarks

        Wrap-up of conventions.

        ## Code Style

        - use spaces, not tabs
        """.trimIndent()
    )

    private fun resolver(m: CblModel): (String) -> CblMarkdown.Resolved? = { ref ->
        m.blockByRef(ref.removePrefix("#"))?.let { CblMarkdown.Resolved(it) }
    }

    private fun frameCount(text: String) = Regex("<div class='embed'>").findAll(text).count()

    fun testSlugs() {
        assertEquals("final-remarks", CblModel.slugOf("**Final Remarks**"))
        assertEquals("main-guard", CblModel.slugOf("'main'-guard"))
        assertEquals("tail-call-optimization-tco", CblModel.slugOf("Tail-call optimization (TCO)"))
    }

    /**
     * A labelled heading: the block is addressed by the label, and shown by the
     * front part of the title alone. The two halves of the feature - a stable
     * address, a readable link text - are what makes a glossary survive an
     * edit of its own headings.
     */
    fun testLabelledHeadingIsAddressedByItsLabelAndShownWithout() {
        val m = CblParser.parseMarkdown(
            """
            # Glossary

            ## Background Const <a id="acdf"></a>

            `const` binds a promise, not a storage class.

            ## Plain Heading
            """.trimIndent()
        )
        val labelled = m.blocks[1]
        assertEquals("Background Const", labelled.title)   // the label is not text
        assertEquals("acdf", labelled.label)
        assertEquals("acdf", labelled.slug)
        assertSame(labelled, m.blockByRef("acdf"))
        assertSame("qualified refs address the label too", labelled, m.blockByRef("glossary:acdf"))
        // the label REPLACES the title slug - a ref by title must fail loudly
        assertNull(m.blockByRef("background-const"))
        // ... while an unlabelled heading is untouched
        assertNull(m.blocks[2].label)
        assertSame(m.blocks[2], m.blockByRef("plain-heading"))

        // link text and embed title show the front part alone
        assertEquals(
            "see [Background Const](#acdf)",
            CblMarkdown.expandShortcuts("see [#acdf]") { ref ->
                m.blockByRef(ref.removePrefix("#"))?.title
            }
        )
        val embed = CblMarkdown.resolveEmbeds("![](#acdf)", resolve = resolver(m))
        assertTrue("the frame is titled with the front part", "**Background Const**" in embed)
        assertTrue("binds a promise" in embed)
    }

    /**
     * The spellings a label may be written in: `id` and GitHub's older `name`,
     * either quote, any case - and the two invalid closings, taken on purpose.
     * `<a id="x"/>` and a lone `<a id="x">` are not legal HTML5, but leaving
     * them in the title would hide raw markup where it renders as nothing.
     */
    fun testLabelSpellingVariants() {
        val m = CblParser.parseMarkdown(
            """
            # Glossary

            ## Zero Overhead <a name='zo'></a>

            body

            ## Half Written <a id="hw">

            body

            ## Self Closed <A ID="SC"/>

            body
            """.trimIndent()
        )
        assertEquals(
            listOf("Glossary", "Zero Overhead", "Half Written", "Self Closed"),
            m.blocks.map { it.title }
        )
        assertEquals(listOf(null, "zo", "hw", "sc"), m.blocks.map { it.label })
        assertSame(m.blocks[1], m.blockByRef("zo"))
    }

    /**
     * Only a TRAILING anchor is a label. Everything else in a heading - an
     * anchor in the middle, a real link, and the bracket form the refs are
     * written in - is text, and slugs the way it always did.
     */
    fun testAnythingButATrailingAnchorIsHeadingText() {
        val m = CblParser.parseMarkdown(
            """
            # See [#acdf] for the details

            ## Answer 0x02-3 [#a2-3]

            ## A <a href="x">link</a> in a heading
            """.trimIndent()
        )
        assertEquals(listOf(null, null, null), m.blocks.map { it.label })
        assertEquals("See [#acdf] for the details", m.blocks[0].title)
        // the bracket form addresses a block, it never names one
        assertEquals("Answer 0x02-3 [#a2-3]", m.blocks[1].title)
        assertSame(m.blocks[1], m.blockByRef("answer-0x02-3-a2-3"))
    }

    fun testHierarchicalResolutionMatchesAChainSuffix() {
        val m = CblParser.parseMarkdown(
            """
            # Topic

            ## Child

            ### Detail
            """.trimIndent()
        )
        val detail = m.blocks[2]
        assertEquals(listOf(m.blocks[0], m.blocks[1]), m.chainOf(detail))
        assertSame(detail, m.blockByRef("detail"))              // short form
        assertSame(detail, m.blockByRef("child:detail"))         // immediate parent
        assertSame(detail, m.blockByRef("topic:child:detail"))   // full chain
        assertNull(m.blockByRef("topic:detail"))                 // must be a SUFFIX
        assertNull(m.blockByRef("no-such-block"))
    }

    fun testEmbedSplicesTheReferencedBody() {
        val m = model()
        val resolved = CblMarkdown.resolveEmbeds(
            m.blocks[0].bodyLines.joinToString("\n"), resolve = resolver(m)
        )
        assertTrue("the referenced body must be spliced in", "use spaces, not tabs" in resolved)
        assertTrue("the referenced title must come along", "Code Style" in resolved)
        assertEquals("a single embed still owns a frame", 1, frameCount(resolved))
        // a click-REFERENCE stays a plain link, untouched by the embed pass
        assertTrue("[style](#final-remarks:code-style)" in resolved)
        // and a ref into the void says so instead of rendering nothing
        assertTrue(
            "unresolved reference: `#nope`" in
                CblMarkdown.resolveEmbeds("![](#nope)", resolve = resolver(m))
        )
    }

    fun testFoldedEmbedRendersTheToggleOnly() {
        val m = model()
        val toggle = "${CblMarkdown.TOGGLE_SCHEME}#code-style"
        val folded = CblMarkdown.resolveEmbeds(
            "![](#code-style)", expanded = { false }, resolve = resolver(m)
        )
        assertTrue("folded embed carries the toggle link", toggle in folded)
        assertFalse("folded embed must not splice the body", "use spaces, not tabs" in folded)

        val unfolded = CblMarkdown.resolveEmbeds(
            "![](#code-style)", expanded = { true }, resolve = resolver(m)
        )
        assertTrue("unfolded embed keeps the toggle link", toggle in unfolded)
        assertTrue("unfolded embed splices the body", "use spaces, not tabs" in unfolded)
    }

    /**
     * The quiz form: a question, and the answer behind an arrow in the same
     * line. The target's HEADLINE stands in the sentence, the arrow after it,
     * and the body appears only when unfolded - the headline is the author's
     * responsibility, the body is the plugin's.
     */
    fun testInlineEmbedShowsTheHeadlineThenTheArrow() {
        val m = model()
        val toggle = "${CblMarkdown.TOGGLE_SCHEME}#code-style"
        val question = "What prints `cout << v1`? ![](#code-style)"

        val folded = CblMarkdown.resolveEmbeds(question, expanded = { false }, resolve = resolver(m))
        assertEquals("no frame while folded", 0, frameCount(folded))
        assertTrue(
            "headline and arrow sit inline, in the question's line",
            "v1`? [Code Style &#9656;]($toggle)" in folded
        )
        assertFalse("folded inline embed must not splice the body", "use spaces, not tabs" in folded)
        // one paragraph, one link - the sentence survives the pass
        val html = CblMarkdown.toHtml(folded)
        assertEquals(1, Regex("<p>").findAll(html).count())

        val unfolded = CblMarkdown.resolveEmbeds(question, expanded = { true }, resolve = resolver(m))
        assertTrue("unfolded flips the arrow", "[Code Style &#9662;]($toggle)" in unfolded)
        assertEquals("the body comes framed, below", 1, frameCount(unfolded))
        assertTrue("use spaces, not tabs" in unfolded)
        // the headline stands in the sentence, so the frame does not repeat it
        assertFalse("**Code Style**" in unfolded)
    }

    /** Brackets in a title would end the link text early - they are escaped. */
    fun testTitleBracketsSurviveTheToggleLink() {
        val m = CblParser.parseMarkdown("# T\n\n## See [x] first\n\nbody")
        val inline = CblMarkdown.resolveEmbeds(
            "ref ![](#see-x-first)", expanded = { false }, resolve = resolver(m)
        )
        assertTrue("See \\[x\\] first &#9656;" in inline)
        // ... and it renders as ONE link, not as a short link plus stray prose
        val html = CblMarkdown.toHtml(inline)
        assertEquals(1, Regex("<a ").findAll(html).count())
        assertTrue("See [x] first" in html)
    }

    /** Text following an inline embed must stay Markdown, i.e. the frame has to
     *  close with a blank line (same contract as the stand-alone forms). */
    fun testInlineEmbedIsFollowedByABlankLine() {
        val m = model()
        val text = CblMarkdown.resolveEmbeds(
            "question? ![](#code-style)\nafter **bold**\n", expanded = { true }, resolve = resolver(m)
        )
        assertTrue("blank line missing after the frame", "</div>\n\n" in text)
        assertTrue("<strong>bold</strong>" in CblMarkdown.toHtml(text))
    }

    /** A line that holds nothing but the embed keeps the titled, framed form -
     *  position is what distinguishes the two, and only that. */
    fun testStandaloneEmbedIsUnaffectedByTheInlineForm() {
        val m = model()
        val folded = CblMarkdown.resolveEmbeds(
            "  ![](#code-style)", expanded = { false }, resolve = resolver(m)
        )
        assertEquals(1, frameCount(folded))
        assertTrue("stand-alone embeds still show their title", "Code Style" in folded)
    }

    /**
     * `!!` pins an embed open: a transclusion, not a question. Same frame, same
     * headline, but no arrow and no toggle link - there is nothing to fold, so
     * the fold state is never consulted (`expanded = { false }` below).
     */
    fun testDoubleBangPinsAnEmbedOpen() {
        val m = model()
        val pinned = CblMarkdown.resolveEmbeds(
            "!![](#code-style)", expanded = { false }, resolve = resolver(m)
        )
        assertEquals("still framed", 1, frameCount(pinned))
        assertTrue("headline stays", "**Code Style**" in pinned)
        assertTrue("body is spliced despite the fold state", "use spaces, not tabs" in pinned)
        assertFalse("no toggle link", CblMarkdown.TOGGLE_SCHEME in pinned)
        assertFalse("no arrow", "&#9656;" in pinned || "&#9662;" in pinned)
        // the extra '!' is consumed, not left in the text
        assertFalse("!" in CblMarkdown.toHtml(pinned).substringBefore("Code Style"))

        // inline, the headline stands in the sentence and the body follows
        val inline = CblMarkdown.resolveEmbeds(
            "as defined here: !![](#code-style)", expanded = { false }, resolve = resolver(m)
        )
        assertTrue("here: Code Style" in inline)
        assertFalse(CblMarkdown.TOGGLE_SCHEME in inline)
        assertTrue("use spaces, not tabs" in inline)

        // and the short form carries the second bang through
        assertEquals("!![](dictionary.md#tco)", CblMarkdown.expandShortcuts("!![#tco]", listOf("dictionary.md")) { "T" })
        assertEquals("![](dictionary.md#tco)", CblMarkdown.expandShortcuts("![#tco]", listOf("dictionary.md")) { "T" })
    }

    fun testAdjacentEmbedsShareOneFrame() {
        val m = model()
        val frames = { text: String -> frameCount(CblMarkdown.resolveEmbeds(text, resolve = resolver(m))) }
        assertEquals(1, frames("![](#code-style)\n![](#final-remarks)\n"))
        assertEquals("blank lines still make one run", 1, frames("![](#code-style)\n\n![](#final-remarks)\n"))
        assertEquals("trailing whitespace must not break the run", 1, frames("![](#code-style)   \n![](#final-remarks)  \n"))
        assertEquals("prose in between breaks the run", 2, frames("![](#code-style)\n\nprose\n\n![](#final-remarks)\n"))
        // both titles survive the merge
        val merged = CblMarkdown.resolveEmbeds("![](#code-style)\n![](#final-remarks)\n", resolve = resolver(m))
        assertTrue("Code Style" in merged && "Final Remarks" in merged)
    }

    fun testEmbedFrameIsFollowedByABlankLine() {
        val m = model()
        val run = CblMarkdown.resolveEmbeds(
            "![](#code-style)\n![](#final-remarks)\nafter **bold**\n", resolve = resolver(m)
        )
        val single = CblMarkdown.resolveEmbeds("![](#code-style)\nafter **bold**\n", resolve = resolver(m))
        for ((name, text) in listOf("run" to run, "single" to single)) {
            assertTrue("$name: blank line missing after the frame", "</div>\n\nafter" in text)
            assertTrue(
                "$name: following Markdown must still be rendered",
                "<strong>bold</strong>" in CblMarkdown.toHtml(text)
            )
        }
    }

    fun testShortRefExpansion() {
        val titles = mapOf(
            "dictionary.md#main-guard" to "'main'-guard",
            "#code-style" to "Code Style",
            "../notes.md#tco" to "Tail-call optimization",
        )
        val expand = { text: String ->
            CblMarkdown.expandShortcuts(text, listOf("dictionary.md")) { titles[it] }
        }
        // embed form: a path-less fragment is completed from the glossary path
        assertEquals("![](dictionary.md#main-guard)", expand("![#main-guard]"))
        // reference form: the target's title becomes the link text
        assertEquals("[Tail-call optimization](../notes.md#tco)", expand("[../notes.md#tco]"))
        // an explicit path passes through untouched
        assertEquals("![](../notes.md#tco)", expand("![../notes.md#tco]"))
        // unresolved: a warning instead of a link into the void
        assertTrue("unresolved reference: `#nope`" in expand("[#nope]"))
        // without any glossary path a short ref means "same file"
        assertEquals("![](#code-style)", CblMarkdown.expandShortcuts("![#code-style]"))
    }

    fun testShortRefSearchesTheGlossaryPathInOrderThenTheCurrentFile() {
        // the point of glossary.path: the author writes [#tco] without naming a
        // file, and the dictionary or the answer file is found for them
        val known = setOf("dictionary.md#tco", "answers.md#answer-0x02-3", "#local")
        val expand = { text: String ->
            CblMarkdown.expandShortcuts(text, listOf("dictionary.md", "answers.md")) {
                if (it in known) "T" else null
            }
        }
        assertEquals("![](dictionary.md#tco)", expand("![#tco]"))
        assertEquals("![](answers.md#answer-0x02-3)", expand("![#answer-0x02-3]"))
        assertEquals("the current file is the last candidate", "![](#local)", expand("![#local]"))
    }

    fun testShortRefLeavesLongFormsProseAndDefinitionsAlone() {
        val text = """
            full form [style](#code-style) and embed ![](#code-style)
            prose with [brackets and spaces] plus an image ![alt](img/x.png)
            [#main-guard]: dictionary.md#main-guard
            a collapsed reference [#a][label]
        """.trimIndent()
        assertEquals(text, CblMarkdown.expandShortcuts(text, listOf("dictionary.md")) { "T" })
    }

    fun testColonAfterARefIsProseNotADefinition() {
        val expand = { text: String ->
            CblMarkdown.expandShortcuts(text, listOf("dictionary.md")) { "Decorator" }
        }
        assertEquals(
            "a [Decorator](dictionary.md#decorator): it changes the def below",
            expand("a [#decorator]: it changes the def below")
        )
        assertEquals("indented definitions are definitions", "  [#decorator]: dest", expand("  [#decorator]: dest"))
        assertEquals("... after a line break, too", "text\n[#decorator]: dest", expand("text\n[#decorator]: dest"))
    }

    fun testGfmTablesRender() {
        // guards the flavour choice: plain CommonMark has no table syntax, so a
        // comparison table - a teaching staple - would degrade into a paragraph
        // full of pipes
        val html = CblMarkdown.toHtml(
            """
            | Kind            | First argument |
            |-----------------|----------------|
            | instance method | `self`         |
            | `@staticmethod` | nothing        |
            """.trimIndent()
        )
        assertTrue("GFM table must produce a table element", "<table>" in html)
        assertTrue("header cell expected", "<th>" in html)
        assertTrue("body cell expected", "<td>" in html)
        assertFalse("no raw pipes may survive", "| instance method |" in html)
        // the rest of the pipeline is unaffected by the flavour switch
        assertTrue("<strong>bold</strong>" in CblMarkdown.toHtml("**bold**"))
        assertTrue("<code>x</code>" in CblMarkdown.toHtml("`x`"))
    }

    fun testMarkdownHeadingParser() {
        // how a glossary file becomes referenceable blocks
        val m = CblParser.parseMarkdown(
            """
            # Glossary

            Shared definitions.

            ## Code Style

            - spaces over tabs

            ```python
            # not a heading, just a Python comment in a fence
            ```

            ## Tail Calls

            body text
            """.trimIndent()
        )
        assertEquals(3, m.blocks.size)
        assertEquals("Glossary", m.blocks[0].title)
        assertEquals(1, m.blocks[0].depth)
        assertEquals("Code Style", m.blocks[1].title)
        assertEquals(2, m.blocks[1].depth)
        // the fenced '# comment' must not have become a heading
        assertTrue(m.blocks[1].bodyLines.any { "not a heading" in it })
        // heading slugs resolve like any block, qualified included
        assertSame(m.blocks[1], m.blockByRef("code-style"))
        assertSame(m.blocks[2], m.blockByRef("glossary:tail-calls"))
    }

    fun testCrossFileEmbedRebasesImages() {
        // an embedded foreign body must load ITS images, not ours - invisible in
        // review, immediately visible as a broken image in the panel
        val foreign = CblParser.parseMarkdown(
            """
            # Dict

            ## With Image

            ![sketch](img/sketch.png)
            """.trimIndent()
        )
        val resolved = CblMarkdown.resolveEmbeds("![](dictionary.md#with-image)") { ref ->
            foreign.blockByRef(ref.substringAfter('#'))
                ?.let { CblMarkdown.Resolved(it, java.io.File("/course/notes")) }
        }
        assertTrue("With Image" in resolved)
        assertTrue(
            "foreign image must be rebased to its own folder",
            "file:/course/notes/img/sketch.png" in resolved
        )
    }
}
