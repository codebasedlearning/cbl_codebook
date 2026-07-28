// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import junit.framework.TestCase

/** Pure unit test for the output parser - no IDE fixture needed. */
class CblOutputModelTest : TestCase() {

    fun testSections() {
        val text = """
            --- c_references.cpp ---

            using_references
            ================
             1| n=1, m=1
             2| n=2, m=2

            using_const_references
            ======================
             1| n=1, m=1
        """.trimIndent()
        val model = CblOutputModel(text)

        assertEquals(2, model.sections.size)
        assertEquals("using_references", model.sections[0].name)
        assertEquals(2, model.sections[0].startLine)
        assertEquals("using_const_references", model.sections[1].name)
        assertEquals(7, model.sections[1].startLine)
    }

    fun testLineOffsets() {
        val model = CblOutputModel("ab\ncdef\ng")
        assertEquals(0, model.lineStartOffset(0))
        assertEquals(2, model.lineEndOffset(0))
        assertEquals(3, model.lineStartOffset(1))
        assertEquals(7, model.lineEndOffset(1))
        assertEquals(8, model.lineStartOffset(2))
    }
}
