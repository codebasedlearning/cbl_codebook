// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Folding against a real editor: the BODY folds, the frame line stays readable,
 * and only multi-line comments get a region at all.
 */
class CblFoldingTest : BasePlatformTestCase() {

    private fun cblRegions() = myFixture.editor.foldingModel.allFoldRegions
        .filter { it.placeholderText == CblFolding.PLACEHOLDER }
        .sortedBy { it.startOffset }

    /** Replace the first [old] in the document by [new], as typing would, and
     *  commit - the state a debounced re-parse finds. */
    private fun edit(old: String, new: String) {
        val document = myFixture.editor.document
        val at = document.text.indexOf(old)
        assertTrue("'$old' must be in the document", at >= 0)
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            document.replaceString(at, at + old.length, new)
        })
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun setExpanded(region: FoldRegion, expanded: Boolean) =
        myFixture.editor.foldingModel.runBatchFoldingOperation { region.isExpanded = expanded }

    fun testBodyOnlyFoldsAtEveryLevelAndFoldAllRoundTrips() {
        myFixture.configureByText(
            "Demo.java",
            """
            // plain header line - keeps the blocks away from offset 0, where the
            // startup caret would legitimately hold one open (caret-inside rule)
            /* ---- Topic ----
               body line */
            class A {}
            /* --- Child ---
               body */
            class B {}
            /*
               -- Detail --
               body */
            """.trimIndent()
        )
        val model = CblParser.parse(myFixture.file)
        assertEquals(3, model.blocks.size)

        val editor = myFixture.editor
        val document = editor.document
        editor.caretModel.moveToOffset(0)
        CblFolding.apply(editor, model)

        val regions = cblRegions()
        assertEquals("every multi-line block owns a region", 3, regions.size)
        assertTrue("regions start collapsed", regions.none { it.isExpanded })

        val topic = model.blocks[0]
        assertEquals(
            "fold starts on the frame line", document.getLineNumber(topic.startOffset),
            document.getLineNumber(regions[0].startOffset)
        )
        assertEquals("fold ends with the comment", topic.headerRange.endOffset, regions[0].endOffset)
        assertTrue(
            "the frame line itself must stay visible",
            "---- Topic ----" in document.getText(TextRange(topic.startOffset, regions[0].startOffset))
        )
        // below a bare '/*' the frame sits one line lower - and still stays visible
        val late = model.blocks[2]
        assertEquals(
            document.getLineNumber(late.startOffset) + 1, document.getLineNumber(late.foldStart)
        )

        assertTrue(CblFolding.setAll(editor, model, expanded = true))
        assertTrue("unfold-all must open every level", cblRegions().all { it.isExpanded })
        assertTrue(CblFolding.setAll(editor, model, expanded = false))
        assertTrue("fold-all must close every level", cblRegions().none { it.isExpanded })
    }

    fun testSingleLineCommentsGetNoRegion() {
        myFixture.configureByText(
            "Demo.java",
            """
            // header line, keeps blocks away from the startup caret at offset 0
            /* ---- One-liner ---- with a note on the same line */
            class A {}
            /* -- Also single line -- */
            class B {}
            """.trimIndent()
        )
        val model = CblParser.parse(myFixture.file)
        assertEquals("both are blocks", 2, model.blocks.size)
        assertFalse(model.blocks[0].isFoldable)
        assertFalse(model.blocks[1].isFoldable)

        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        CblFolding.apply(editor, model)
        assertTrue("nothing to hide, so no fold region", cblRegions().isEmpty())
        assertFalse("and nothing for the buttons to act on", CblFolding.setAll(editor, model, false))
    }

    /**
     * The central assumption of body-only folding: our range NESTS inside the
     * IDE's own whole-comment range instead of colliding with it. If the platform
     * refused that, foreign would come back null - which is what this test is
     * here to catch. And a CBL body inside a COLLAPSED foreign region would expand
     * invisibly, so unfold-all has to open the enclosing region too.
     */
    fun testExpandOpensEnclosingForeignRegion() {
        myFixture.configureByText(
            "Demo.java",
            """
            // header line
            /* ---- Topic ----
               body */
            class A {}
            """.trimIndent()
        )
        val model = CblParser.parse(myFixture.file)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        CblFolding.apply(editor, model)
        assertEquals(1, cblRegions().size)

        val block = model.blocks[0]
        var foreign: FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperation {
            foreign = editor.foldingModel.addFoldRegion(
                block.startOffset, block.headerRange.endOffset, "/*...*/"
            )
            foreign?.isExpanded = false
        }
        assertNotNull("the whole-comment range must stay available for the IDE", foreign)
        assertFalse(foreign!!.isExpanded)

        assertTrue(CblFolding.setAll(editor, model, expanded = true))
        assertTrue("unfold-all must open the enclosing region too", foreign!!.isExpanded)
        assertTrue(cblRegions().all { it.isExpanded })
    }

    fun testTypingDoesNotCollapseWhatTheAuthorIsWorkingOn() {
        myFixture.configureByText(
            "Demo.java",
            """
            // header line
            /* ---- Topic ----
               body */
            class A {}
            """.trimIndent()
        )
        val editor = myFixture.editor
        val model = CblParser.parse(myFixture.file)
        editor.caretModel.moveToOffset(0)
        CblFolding.apply(editor, model)
        assertTrue(CblFolding.setAll(editor, model, expanded = true))
        // a re-parse (debounced document change) must not re-collapse it:
        // a region that still covers its block's body is kept as it is
        CblFolding.apply(editor, CblParser.parse(myFixture.file))
        assertTrue("state must survive a reapply", cblRegions().all { it.isExpanded })

        // and a block being typed - caret inside it - is never folded away
        val fresh = myFixture.configureByText(
            "Fresh.java",
            """
            /* ---- Fresh topic being typed ----
               still writing this */
            class A {}
            """.trimIndent()
        )
        val freshModel = CblParser.parse(fresh)
        myFixture.editor.caretModel.moveToOffset(freshModel.blocks[0].foldStart + 1)
        CblFolding.apply(myFixture.editor, freshModel)
        assertTrue("the block under the caret stays expanded", cblRegions().all { it.isExpanded })
    }

    /**
     * The reported case: an ordinary comment BECOMES a block while the author is
     * writing its frame line. The caret is on that line - not in the body - and
     * the title is one nobody has seen before. Until v2.2.1 that combination
     * meant "unknown, and not being edited", and the body folded away mid-word.
     */
    fun testACommentThatBecomesABlockStaysOpen() {
        myFixture.configureByText(
            "Demo.java",
            """
            // header line
            /* plain comment
               more text */
            class A {}
            /* ---- Topic ----
               body */
            class B {}
            """.trimIndent()
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        CblFolding.apply(editor, CblParser.parse(myFixture.file))
        assertEquals(1, cblRegions().size)
        assertFalse("opening a file starts folded", cblRegions()[0].isExpanded)

        edit("plain comment", "---- Fresh ----")
        editor.caretModel.moveToOffset(editor.document.text.indexOf("Fresh") + 2)
        val model = CblParser.parse(myFixture.file)
        assertEquals(2, model.blocks.size)
        CblFolding.apply(editor, model)

        val regions = cblRegions()
        assertEquals(2, regions.size)
        assertTrue("a block that appears while typing starts open", regions[0].isExpanded)
        assertFalse("and the untouched block below keeps its state", regions[1].isExpanded)

        // the caret moving on within the debounce changes nothing either
        edit("Fresh", "Fresher")
        editor.caretModel.moveToOffset(0)
        CblFolding.apply(editor, CblParser.parse(myFixture.file))
        assertTrue("still open after the caret has left", cblRegions()[0].isExpanded)
    }

    /** Rewording a title is an edit like any other: it must neither fold an
     *  open block nor open a folded one. State used to be keyed by title, so
     *  every new wording was a block without a past. */
    fun testRetitlingKeepsTheFoldState() {
        myFixture.configureByText(
            "Demo.java",
            """
            // header line
            /* ---- Topic ----
               body */
            class A {}
            /* ---- Other ----
               body */
            class B {}
            """.trimIndent()
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        CblFolding.apply(editor, CblParser.parse(myFixture.file))
        setExpanded(cblRegions()[0], true)

        edit("Topic", "Renamed topic")
        editor.caretModel.moveToOffset(editor.document.text.indexOf("Renamed") + 3)
        CblFolding.apply(editor, CblParser.parse(myFixture.file))
        assertTrue("the open block stays open under a new title", cblRegions()[0].isExpanded)

        edit("Other", "Another")
        CblFolding.apply(editor, CblParser.parse(myFixture.file))
        assertFalse("the folded block stays folded under a new title", cblRegions()[1].isExpanded)
        assertEquals(2, cblRegions().size)
    }

    /** Didactic files repeat titles on purpose (`-- .Preview --`). Each block
     *  has its own fold state - keyed by title, the last one decided for all. */
    fun testBlocksWithTheSameTitleFoldIndependently() {
        myFixture.configureByText(
            "Demo.java",
            """
            // header line
            /* -- Preview --
               one */
            class A {}
            /* -- Preview --
               two */
            class B {}
            """.trimIndent()
        )
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(0)
        CblFolding.apply(editor, CblParser.parse(myFixture.file))
        assertEquals(2, cblRegions().size)
        setExpanded(cblRegions()[0], true)

        CblFolding.apply(editor, CblParser.parse(myFixture.file))
        assertTrue(cblRegions()[0].isExpanded)
        assertFalse(cblRegions()[1].isExpanded)
    }
}
