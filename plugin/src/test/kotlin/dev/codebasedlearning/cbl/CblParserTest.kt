// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The DSL contract, against real PSI. This suite pays for its fixture: it is the
 * only place that proves the parser reads actual comment TOKENS - which is what
 * keeps a banner, a line comment or a trailing comment from becoming a topic - and
 * `.java` is the one language the test platform can parse.
 *
 * C++ and Python (the `"""` docstring branch) have no PSI here, because the test
 * platform bundles neither language. Those two are covered by feature testing in
 * `runClion` / `runPycharm` with the sample projects, deliberately: a fake PSI
 * would prove nothing about the real one.
 */
class CblParserTest : BasePlatformTestCase() {

    fun testTopicBodyAndFoldPlaceholder() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            /* ---- Names are bindings ----
             * **a second name** is an *alias*
             */
            class Demo {}
            /* ---- Rebinding ----
               assignment rebinds the name */
            class B {}
            """.trimIndent()
        )
        val model = CblParser.parse(file)
        assertEquals(2, model.blocks.size)

        val topic = model.blocks[0]
        assertFalse(topic.isChild)
        assertEquals("Names are bindings", topic.title)
        // Markdown emphasis must survive cleanLines (no leading-star stripping)
        assertEquals(listOf("**a second name** is an *alias*"), topic.bodyLines)
        // fold range covers the body only, the frame line stays visible
        assertTrue(topic.isFoldable)
        assertTrue(topic.foldStart > topic.startOffset)
        assertEquals(topic.headerRange.endOffset, topic.foldEnd)

        // boxed-comment decoration ('* ' prefix) is still stripped
        assertEquals("Rebinding", model.blocks[1].title)
        assertEquals(listOf("assignment rebinds the name"), model.blocks[1].bodyLines)
    }

    fun testFourLevelsByFrameWidth() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            /* ---- Parent topic ---- */
            /* --- Child topic --- */
            /* -- Deep dive -- */
            /* - Deeper still - */
            class A {}
            """.trimIndent()
        )
        val model = CblParser.parse(file)
        assertEquals(4, model.blocks.size)
        // adjacent comments never merge - one block comment, one block
        assertEquals(listOf(1, 2, 3, 4), model.blocks.map { it.depth })
        assertEquals(
            listOf("Parent topic", "Child topic", "Deep dive", "Deeper still"),
            model.blocks.map { it.title }
        )
        assertTrue(model.blocks[1].isChild)
    }

    fun testHeaderNeedNotBeOnTheOpeningLine() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            /*
               --- Late header ---

               body text
             */
            class A {}
            """.trimIndent()
        )
        val model = CblParser.parse(file)
        assertEquals(1, model.blocks.size)
        assertEquals("Late header", model.blocks[0].title)
        assertEquals(2, model.blocks[0].depth)
        assertEquals(listOf("body text"), model.blocks[0].bodyLines)
    }

    fun testIgnoredForms() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            /* ----- five dashes: a banner, not a block ----- */
            /* -------- */
            /* ---- asymmetric frame --- */
            /* an ordinary block comment */
            // ---- line comments are not eligible ----
            class A {
                void f() {
                    int n = 1; /* ---- trailing, hence ordinary ---- */
                }
            }
            """.trimIndent()
        )
        assertTrue(CblParser.parse(file).blocks.isEmpty())
    }

    fun testTextAfterTheFrameBecomesBody() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            /* ---- Aliases ---- m is n, and n is m. */
            class A {}
            /* --- Const --- reading still works
               writing does not */
            class B {}
            """.trimIndent()
        )
        val model = CblParser.parse(file)
        assertEquals(2, model.blocks.size)
        assertEquals("Aliases", model.blocks[0].title)
        assertEquals(listOf("m is n, and n is m."), model.blocks[0].bodyLines)
        // same-line remainder is the FIRST body line, following lines append
        assertEquals("Const", model.blocks[1].title)
        assertEquals(
            listOf("reading still works", "writing does not"),
            model.blocks[1].bodyLines
        )
    }

    /** The other end of `block.levelN.regex`: whatever cbl.properties supplies
     *  reaches the parser, and the built-in frames then no longer apply. */
    fun testConfiguredLevelPatternsReplaceTheBuiltInFrames() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            /* ==== Framed with equals ==== and a note */
            class A {}
            /* ---- default frames no longer match ---- */
            """.trimIndent()
        )
        val model = CblParser.parse(
            file,
            listOf(Regex("^={4}(?!=)\\s*(?<title>\\S.*?)\\s*(?<!=)={4}(?!=)\\s*(?<rest>.*)\$"))
        )
        assertEquals(1, model.blocks.size)
        assertEquals("Framed with equals", model.blocks[0].title)
        assertEquals(listOf("and a note"), model.blocks[0].bodyLines)
        assertEquals(1, model.blocks[0].depth)

        // group 1 is the title when a pattern declares no (?<title>...)
        val plain = myFixture.configureByText("Plain.java", "/* # Heading style */\nclass A {}")
        val headings = CblParser.parse(plain, listOf(Regex("^#\\s+(\\S.*?)\\s*\$")))
        assertEquals("Heading style", headings.blocks.single().title)
    }

    /**
     * A doc comment is a COMPOSITE PSI element, not a leaf - PsiDocComment in
     * Java, KDoc in Kotlin, both PsiComment via PsiDocCommentBase. The parser
     * once collected leaves only, so a slash-star-star header worked in C++
     * (one comment token there) and was silently ignored in Kotlin and Java.
     * `.java` is the one language this fixture can parse, and it reproduces the
     * bug exactly.
     */
    fun testDocCommentFormIsRecognized() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            /**
             * ---- TOC ----
             *
             * body text
             */
            class Demo {}
            """.trimIndent()
        )
        val model = CblParser.parse(file)
        assertEquals(1, model.blocks.size)
        assertEquals("TOC", model.blocks[0].title)
        assertEquals(listOf("body text"), model.blocks[0].bodyLines)
        // one block, not one per doc token: the walk must not descend into it
        assertEquals(1, model.blocks.count { it.title == "TOC" })
    }

    fun testBlockOpeningAConstructOwnsItsHeaderLine() {
        val file = myFixture.configureByText(
            "Demo.java",
            """
            /* ---- Above the class ---- */
            class Demo {
                /* ---- Inside the body ----
                   this is the Python docstring position, in Java syntax */
                void f() {}
            }
            """.trimIndent()
        )
        val model = CblParser.parse(file)
        assertEquals(2, model.blocks.size)
        val document = myFixture.editor.document

        // the inner block starts at 'class Demo {', not at its own comment, so
        // the caret on a signature selects the block that documents it
        val inner = model.blocks[1]
        assertEquals(1, document.getLineNumber(inner.startOffset))
        assertEquals(2, document.getLineNumber(inner.headerRange.startOffset))
        assertSame(inner, model.blockAt(document.getLineStartOffset(1)))
        // folding is unaffected - it works on the comment, not on the extent
        assertEquals(2, document.getLineNumber(inner.foldStart))

        // a block that follows ordinary code keeps its own start
        val outer = model.blocks[0]
        assertEquals(outer.headerRange.startOffset, outer.startOffset)
        // ... and a block reaches to just before the next one, so every offset in
        // the file belongs to exactly one block (this drives the breadcrumb)
        assertEquals(inner.startOffset - 1, outer.endOffset)
        assertSame(outer, model.blockAt(outer.startOffset + 3))
        assertSame(inner, model.blockAt(file.textLength - 1))
    }
}
