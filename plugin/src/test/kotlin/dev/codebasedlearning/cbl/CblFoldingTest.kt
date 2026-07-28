// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Folding against a real editor: the BODY folds, the frame line stays readable,
 * and only multi-line comments get a region at all.
 */
class CblFoldingTest : BasePlatformTestCase() {

    private fun cblRegions() = myFixture.editor.foldingModel.allFoldRegions
        .filter { it.placeholderText == CblFolding.PLACEHOLDER }

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
        // expansion state is keyed by title, not by offsets
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
}
