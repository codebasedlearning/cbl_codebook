// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

package dev.codebasedlearning.cbl

import junit.framework.TestCase

/**
 * Identity without an IDE: which block is "the same one" after a re-parse
 * ([CblModel.keyOf]), and which occurrence of a name is the name itself rather
 * than a piece of a longer one ([wholeWordIndexOf]). Both replaced a comparison
 * that looked right in every sample and was wrong in real course files - by
 * title, and by substring.
 */
class CblIdentityTest : TestCase() {

    private val text = """
        # Discussion

        ## Preview

        one

        ## Preview

        two

        # Run

        ## Preview

        three
    """.trimIndent()

    fun testKeysTellBlocksWithTheSameTitleApart() {
        val model = CblParser.parseMarkdown(text)
        val keys = model.blocks.map { model.keyOf(it) }
        assertEquals(
            listOf("discussion#0", "discussion:preview#0", "discussion:preview#1", "run#0", "run:preview#0"),
            keys,
        )
        model.blocks.forEach { assertSame(it, model.blockByKey(model.keyOf(it)!!)) }
    }

    fun testAKeyFindsTheSameBlockInALaterParse() {
        val before = CblParser.parseMarkdown(text)
        val second = before.blocks[2]
        assertEquals("two", second.bodyLines.joinToString(""))
        val key = before.keyOf(second)!!

        // an edit ABOVE the block: every offset behind it shifts, and a new
        // parse hands out new objects - the key still names the second Preview
        val after = CblParser.parseMarkdown("# Content\n\nsomething new\n\n$text")
        val found = after.blockByKey(key)
        assertNotNull(found)
        assertNotSame(second, found)
        assertEquals("two", found!!.bodyLines.joinToString(""))
    }

    fun testKeysOfForeignOrVanishedBlocks() {
        val model = CblParser.parseMarkdown(text)
        val other = CblParser.parseMarkdown(text)
        assertNull("a block of another model has no key here", model.keyOf(other.blocks[0]))
        assertNull(model.blockByKey("discussion:preview#2"))
        assertNull(model.blockByKey("no-such-block#0"))
    }

    fun testWholeWordsOnly() {
        val source = "void define_and_init() { init_all(); }\nvoid init() {}\n"
        // not the tail of define_and_init, not the head of init_all
        assertEquals(source.indexOf("init() {}"), wholeWordIndexOf(source, "init"))
        assertEquals(source.indexOf("define_and_init"), wholeWordIndexOf(source, "define_and_init"))
        assertEquals(-1, wholeWordIndexOf("print_function_header(); reprint();", "print"))
        assertEquals(-1, wholeWordIndexOf(source, ""))
        // 'from' skips earlier hits
        val twice = "init(); init();"
        assertEquals(8, wholeWordIndexOf(twice, "init", 1))
    }

    fun testBoundariesFollowTheNameItself() {
        // a name that ends in punctuation needs no boundary behind it,
        // and one with a blank inside is matched as written
        assertEquals(5, wholeWordIndexOf("void demo()x", "demo()"))
        assertEquals(3, wholeWordIndexOf("// using print here", "using print"))
        // letters of any script are word characters
        assertEquals(-1, wholeWordIndexOf("größe", "gr"))
        assertEquals(0, wholeWordIndexOf("größe = 1", "größe"))
    }
}
