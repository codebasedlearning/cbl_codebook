// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import junit.framework.TestCase

/**
 * `cbl.properties`: the cascade and the pattern validation - the parts with logic
 * in them. Plain JUnit, no IDE fixture: [CblConfig.mergeLayers] and friends are
 * functions of strings, so these run in milliseconds and cannot be broken by
 * whatever the platform bundles this year.
 */
class CblConfigTest : TestCase() {

    /** Nearest-first layers, as [CblConfig.forFile] collects them. */
    private fun merge(vararg layers: Pair<String, String>) = CblConfig.mergeLayers(layers.toList())

    /**
     * Where the upward search stops. The content root is NOT reliably "the
     * folder opened as the project": Gradle assigns one per source set, so a
     * module with a non-standard source directory reports that directory - and
     * stopping there skipped the `cbl.properties` one level above it, silently
     * leaving `glossary.path` unset so every short ref resolved to nothing.
     * PyCharm and CLion never showed it, because the whole sample is one root.
     */
    fun testSearchStopsAtTheProjectDirNotAtASourceSetRoot() {
        val project = "/courses/sample-kotlin"

        // the Gradle case: content root BELOW the project dir - keep going up
        assertEquals(project, CblConfig.outerBoundPath(project, "$project/snippets"))
        // the ordinary case: they coincide
        assertEquals(project, CblConfig.outerBoundPath(project, project))
        // outside the project (library sources): the content root is the only bound
        assertEquals("/elsewhere/lib", CblConfig.outerBoundPath(project, "/elsewhere/lib"))
        // a sibling whose path merely SHARES A PREFIX must not count as inside
        assertEquals("/courses/sample-kotlin-old", CblConfig.outerBoundPath(project, "/courses/sample-kotlin-old"))
        // either side missing
        assertEquals(project, CblConfig.outerBoundPath(project, null))
        assertEquals("/x", CblConfig.outerBoundPath(null, "/x"))
        assertNull(CblConfig.outerBoundPath(null, null))
    }

    fun testCascadeMergesPerKeyAndNearerWins() {
        val merged = merge(
            "chapter" to """
                # only the level frame is redefined here
                block.level1.regex = ^===\s*(?<title>\S.*?)\s*===${'$'}
            """.trimIndent(),
            "course" to """
                output.section.regex = (?m)^--- (?<name>\S+) ---${'$'}
                block.level1.regex = ^###\s*(?<title>\S.*?)\s*###${'$'}
            """.trimIndent(),
        )
        // per KEY, not per file: the chapter inherits the section regex ...
        assertEquals("course", merged[CblConfig.KEY_SECTION_REGEX]?.second)
        // ... and overrides the one key it sets
        assertEquals("chapter", merged[CblConfig.keyForLevel(1)]?.second)
        assertTrue(CblConfig.levelPatternsOf(merged)[0].matches("=== Topic ==="))

        val sections = CblOutputModel("--- inherited ---\nbody", CblConfig.sectionRegexOf(merged))
        assertEquals(listOf("inherited"), sections.sections.map { it.name })
    }

    fun testUnusableSectionPatternFallsBackAndSaysWhy() {
        val problems = mutableListOf<String>()
        // uncompilable, and valid-but-without-the-required-group: both must fall
        // back, because a typo in a course config may not break the panel
        for (pattern in listOf("([", "(?m)^(\\S+)${'$'}")) {
            problems.clear()
            val merged = merge("course" to "${CblConfig.KEY_SECTION_REGEX} = $pattern")
            assertEquals(
                CblConfig.DEFAULT_SECTION_REGEX.pattern,
                CblConfig.sectionRegexOf(merged) { problems.add(it) }.pattern
            )
            assertEquals(1, problems.size)
            // the message must name the key AND the file, or it is useless
            assertTrue(problems[0], CblConfig.KEY_SECTION_REGEX in problems[0])
            assertTrue(problems[0], "course" in problems[0])
        }
    }

    fun testLevelPatternsAreOverriddenPerLevelAndValidated() {
        val problems = mutableListOf<String>()
        val merged = merge(
            "course" to """
                block.level1.regex = ([
                block.level2.regex = ^===\s*(?<title>\S.*?)\s*===${'$'}
                block.level3.regex = ^===[^=]+===${'$'}
            """.trimIndent()
        )
        val patterns = CblConfig.levelPatternsOf(merged) { problems.add(it) }

        assertEquals(CblParser.MAX_DEPTH, patterns.size)
        // level 2 is redefined ...
        assertTrue(patterns[1].matches("=== Child ==="))
        assertFalse(patterns[1].matches("--- Child ---"))
        // ... levels 1 and 3 are rejected (uncompilable / no capture group) and
        // level 4 was never mentioned - all three keep the built-in dash frames
        for (level in listOf(1, 3, 4)) {
            assertEquals(
                "level $level must keep the built-in pattern",
                CblConfig.DEFAULT_LEVEL_PATTERNS[level - 1].pattern, patterns[level - 1].pattern
            )
        }
        assertEquals(2, problems.size)
    }

    fun testRelativePath() {
        assertEquals("../../d.md", CblConfig.relativePath("/src/rel/a/b", "/src/rel/d.md"))
        assertEquals("e.md", CblConfig.relativePath("/src/rel/a/b", "/src/rel/a/b/e.md"))
        // no common ancestor at all -> no relative form
        assertNull(CblConfig.relativePath("/one", "other/x.md"))
    }

    fun testNoConfigFileMeansBuiltInDefaults() {
        val defaults = CblConfig.forFile(null, null)
        assertEquals("defaults", defaults.source)
        assertEquals(CblConfig.DEFAULT_SECTION_REGEX.pattern, defaults.sectionRegex.pattern)
        assertTrue(defaults.glossaryPath.isEmpty())
    }
}
