// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import junit.framework.TestCase

/**
 * Refs and Markdown rendering: slugs, hierarchical resolution, the short
 * `[#ref]` forms, and the two ref forms - a link that opens a slot, an embed
 * that is simply there.
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
        val embed = CblMarkdown.RefRenderer({ null }, resolver(m)).render("![](#acdf)", "b0")
        assertTrue("the embed is headed with the front part", "**Background Const**" in embed)
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

    /**
     * A file that groups its entries separates the groups with a rule - and a
     * heading's body reaches to the next heading, so the LAST entry of every
     * group would end with a horizontal line that none of its siblings has.
     * The rule belongs to the file's layout, not to the entry.
     */
    fun testATrailingRuleIsNotPartOfTheBody() {
        val m = CblParser.parseMarkdown(
            """
            ## Unnamed namespace

            body text

            ---

            ## Next group

            - a
            - b

            ***
            """.trimIndent()
        )
        assertEquals(listOf("body text"), m.blocks[0].bodyLines)
        assertEquals(listOf("- a", "- b"), m.blocks[1].bodyLines)
        // ... while a rule INSIDE a body is content and stays
        val kept = CblParser.parseMarkdown("## T\n\nabove\n\n---\n\nbelow\n")
        assertEquals(listOf("above", "", "---", "", "below"), kept.blocks[0].bodyLines)
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

    // ---- the two ref forms: link (on demand) and embed (always) ----

    /** A renderer with a given set of open slots. */
    private fun renderer(m: CblModel, open: Map<String, String> = emptyMap()) =
        CblMarkdown.RefRenderer(openRef = { open[it] }, resolve = resolver(m))

    private fun blockCount(text: String) = Regex("<div class='slot'>").findAll(text).count()

    /** The header bar is what tells an opened link from an embedded block. */
    private fun barCount(text: String) = Regex("class='slotbar'").findAll(text).count()

    /**
     * A LINK shows the target's headline and a '▸', and nothing else until it
     * is clicked. The href carries the slot it owns and the target it points
     * at - the panel needs both to decide between opening, replacing and
     * closing.
     */
    fun testLinkIsAnArrowUntilItIsClicked() {
        val m = model()
        val closed = renderer(m).render("see [Code Style](#code-style) for more", "b0")
        assertTrue("[Code Style &#9656;](${CblMarkdown.SLOT_SCHEME}b0-l0:#code-style)" in closed)
        assertEquals("nothing is shown yet", 0, blockCount(closed))
        assertFalse("use spaces, not tabs" in closed)
        // the sentence around it survives as one paragraph
        assertEquals(1, Regex("<p>").findAll(CblMarkdown.toHtml(closed)).count())
    }

    /** Clicked open: the arrow flips and the body appears in a slot, with a
     *  header naming the source and offering the editor and a close. */
    fun testOpenSlotShowsTheBodyWithAHeader() {
        val m = model()
        val open = renderer(m, mapOf("b0-l0" to "#code-style"))
            .render("see [Code Style](#code-style)", "b0")
        assertTrue("[Code Style &#9662;](${CblMarkdown.SLOT_SCHEME}b0-l0:#code-style)" in open)
        assertEquals(1, blockCount(open))
        assertEquals("an opened link has the header bar", 1, barCount(open))
        assertTrue("the body is there", "use spaces, not tabs" in open)
        assertTrue("open in editor", "${CblMarkdown.OPEN_SCHEME}#code-style" in open)
        assertTrue("close this slot", "${CblMarkdown.CLOSE_SCHEME}b0-l0" in open)
        // following Markdown still renders, i.e. the block is properly closed
        assertTrue("<strong>x</strong>" in CblMarkdown.toHtml(open + "\n\nafter **x**\n"))
    }

    /** Two links in one body are two slots: ids come from the occurrence, so
     *  opening one leaves the other alone. */
    fun testTwoLinksAreIndependent() {
        val m = model()
        val text = "[Code Style](#code-style) and [Final Remarks](#final-remarks)"
        val one = renderer(m, mapOf("b0-l1" to "#final-remarks")).render(text, "b0")
        assertEquals("only the second is open", 1, blockCount(one))
        assertTrue("Wrap-up of conventions" in one)
        assertFalse("use spaces, not tabs" in one)
        // ... and the ids are what tells them apart
        assertTrue("b0-l0:#code-style" in one && "b0-l1:#final-remarks" in one)
    }

    /**
     * A link INSIDE an open slot carries the slot's own id, so clicking it
     * replaces that block instead of opening a second one under it. Lookups
     * stay flat however far a chain of definitions goes.
     */
    fun testLinksInsideASlotReplaceIt() {
        val m = CblParser.parseMarkdown(
            """
            # Topic

            See [RAII](#raii).

            ## RAII

            Owning is [Code Style](#code-style), see there.

            ## Code Style

            - spaces
            """.trimIndent()
        )
        val open = CblMarkdown.RefRenderer(
            openRef = { if (it == "b0-l0") "#raii" else null },
            resolve = resolver(m),
        ).render("See [RAII](#raii).", "b0")
        assertEquals("one block, not two", 1, blockCount(open))
        assertTrue("Owning is" in open)
        // the inner link points at the SAME slot, with a different target
        assertTrue("[Code Style &#9656;](${CblMarkdown.SLOT_SCHEME}b0-l0:#code-style)" in open)
    }

    /** An EMBED is the other form: shown always, headline and body, no arrow,
     *  no slot, nothing to click. */
    fun testEmbedIsAlwaysShown() {
        val m = model()
        val embedded = renderer(m).render("![](#code-style)", "b0")
        assertTrue("headline", "**Code Style**" in embedded)
        assertTrue("body", "use spaces, not tabs" in embedded)
        // the same block a link opens - same indent, same rule ...
        assertEquals(1, blockCount(embedded))
        // ... minus the header bar, and with nothing to click
        assertEquals("no header bar", 0, barCount(embedded))
        assertFalse("nothing to toggle", CblMarkdown.SLOT_SCHEME in embedded)
        assertFalse("no arrows", "&#9656;" in embedded || "&#9662;" in embedded)
    }

    /** An unresolved ref says so, in both forms, instead of rendering nothing. */
    fun testUnresolvedRefsAreVisible() {
        val m = model()
        assertTrue("unresolved reference: `#nope`" in renderer(m).render("[x](#nope)", "b0"))
        assertTrue("unresolved reference: `#nope`" in renderer(m).render("![](#nope)", "b0"))
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

    /**
     * Code blocks must not be `<pre>`: Swing lays one out on a single line, and
     * a view that cannot fit its content stops tracking the viewport - so one
     * 90-column line of C++ widens the whole pane and re-flows every paragraph
     * around it. Exactly what "the text above reformats when I open a lookup"
     * turned out to be.
     */
    fun testCodeBlocksWrapInsteadOfWidening() {
        val html = CblMarkdown.softenCodeBlocks(
            CblMarkdown.toHtml(
                """
                See:

                ```cpp
                namespace { void define_and_init() { } }   // helper
                    int indented{0};
                ```

                after
                """.trimIndent()
            )
        )
        assertFalse("no <pre> may survive", "<pre>" in html)
        assertTrue("<div class='code'>" in html)
        assertTrue("lines are kept apart", "<br>" in html)
        assertTrue("indentation survives as nbsp", "&nbsp;&nbsp;&nbsp;&nbsp;int" in html)
        // the prose around it is untouched
        assertTrue("<p>See:</p>" in html && "<p>after</p>" in html)
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

    /**
     * A glossary entry that links to another entry means "another entry of the
     * GLOSSARY" - but the notes pane renders it inside a snippet, where a bare
     * fragment resolves against the snippet's own blocks and finds nothing. The
     * click then did nothing at all, the least helpful failure a link has. So
     * the foreign path travels with the embed and is written back into every
     * relative destination in the body.
     */
    fun testCrossFileEmbedRebasesLinks() {
        val foreign = CblParser.parseMarkdown(
            """
            # Dict

            ## RAII

            See [const correctness](#const-correctness), the [notes](notes/more.md)
            and the [guidelines](https://isocpp.github.io/).

            ## Const correctness

            body
            """.trimIndent()
        )
        val resolved = CblMarkdown.RefRenderer(openRef = { null }, resolve = { ref ->
            foreign.blockByRef(ref.substringAfter('#'))
                ?.let { CblMarkdown.Resolved(it, java.io.File("/course/doc"), "doc/glossary.md") }
        }).render("![](doc/glossary.md#raii)", "b0")
        assertTrue(
            "a fragment becomes a cross-file ref into the glossary",
            "[const correctness &#9656;](${CblMarkdown.SLOT_SCHEME}b0-e0-l0:doc/glossary.md#const-correctness)"
                in resolved
        )
        assertTrue(
            "a relative path is rebased to the glossary's folder",
            "[notes](doc/notes/more.md)" in resolved
        )
        assertTrue("web links are left alone", "(https://isocpp.github.io/)" in resolved)
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
        val resolved = CblMarkdown.RefRenderer(openRef = { null }, resolve = { ref ->
            foreign.blockByRef(ref.substringAfter('#'))
                ?.let { CblMarkdown.Resolved(it, java.io.File("/course/notes")) }
        }).render("![](dictionary.md#with-image)", "b0")
        assertTrue("With Image" in resolved)
        assertTrue(
            "foreign image must be rebased to its own folder",
            "file:/course/notes/img/sketch.png" in resolved
        )
    }
}
