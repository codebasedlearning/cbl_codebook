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
