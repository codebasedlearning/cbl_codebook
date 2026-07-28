// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

/**
 * Parsed program output. The default convention: a section starts with a
 * name line followed by a line of '=' characters (the __func__ header the
 * snippets print; in Kotlin/Java, print the function name manually).
 * Courses can override the pattern via `output.section.regex` in
 * `cbl.properties` (see [CblConfig]) - the regex must bind a group `name`;
 * the match position marks the section start.
 */
class CblOutputModel(
    val text: String,
    private val sectionRegex: Regex = CblConfig.DEFAULT_SECTION_REGEX,
) {

    class Section(val name: String, val startLine: Int) {
        var endLine: Int = startLine
            internal set
    }

    val lines: List<String> = text.lines()

    private val lineStarts: IntArray = run {
        val starts = IntArray(lines.size)
        var offset = 0
        lines.forEachIndexed { i, line -> starts[i] = offset; offset += line.length + 1 }
        starts
    }

    val sections: List<Section> = parseSections()

    fun lineStartOffset(line: Int): Int = lineStarts[line.coerceIn(0, lines.size - 1)]
    fun lineEndOffset(line: Int): Int {
        val l = line.coerceIn(0, lines.size - 1)
        return lineStarts[l] + lines[l].length
    }

    private fun parseSections(): List<Section> {
        val result = mutableListOf<Section>()
        var current: Section? = null
        for (match in sectionRegex.findAll(text)) {
            val name = match.groups["name"]?.value?.trim() ?: continue
            if (name.isEmpty()) continue
            val startLine = lineOf(match.range.first)
            current?.endLine = startLine - 1
            current = Section(name, startLine)
            result.add(current)
        }
        current?.endLine = lines.size - 1
        return result
    }

    /** Line index containing [offset] (binary search over line starts). */
    private fun lineOf(offset: Int): Int {
        val idx = java.util.Arrays.binarySearch(lineStarts, offset)
        return if (idx >= 0) idx else -idx - 2
    }
}
