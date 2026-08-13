// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Course-level configuration: `cbl.properties` files searched UPWARDS from
 * the current source file and CASCADED like .editorconfig - every config
 * file between the source file and the content root contributes, nearer
 * files override per KEY (not per file). A chapter config therefore only
 * needs the keys it changes; everything else is inherited from the course
 * root, and built-in defaults fill the rest.
 *
 * Design rule: configure course *conventions* (data), never DSL semantics.
 * The line between the two runs through the header regex: the *shape* of a
 * CBL header (`---- topic ----` vs. anything else) is a convention and is
 * configurable per level; what a header MEANS - block comment, four levels,
 * framed text is the title, the rest is Markdown body - is the language and
 * has no config key.
 *
 * File format: `key = value` lines, `#` comments. Parsed manually, NOT via
 * java.util.Properties - its escape processing would eat the backslashes of
 * regex values (and it insists on ISO-8859-1).
 */
class CblConfig private constructor(
    /** Matches one output-section header; must bind the group `name`. */
    val sectionRegex: Regex,
    /**
     * CBL header patterns, index 0 = level 1, always [CblParser.MAX_DEPTH]
     * entries. Each must capture the title as group `title` or group 1; the
     * optional group `rest` picks up text after the frame on the same line.
     */
    val levelPatterns: List<Regex>,
    /**
     * Search path for the short ref forms `[#frag]` / `![#frag]`: the files a
     * path-less fragment is looked up in, in order, first hit wins - typically
     * a glossary plus an answer file. Each entry is resolved relative to the
     * `cbl.properties` that DECLARES the key, so a course-root config serves
     * every chapter depth. Empty when unset or unresolvable; the short forms
     * then mean same-file refs.
     */
    val glossaryPath: List<VirtualFile>,
    /** Effective cascade, nearest first ("a <- b" = a overrides b), or "defaults". */
    val source: String,
) {
    companion object {
        const val FILE_NAME = "cbl.properties"
        const val KEY_SECTION_REGEX = "output.section.regex"
        const val KEY_GLOSSARY = "glossary.path"

        /** `block.level1.regex` … `block.level4.regex`. */
        fun keyForLevel(level: Int): String = "block.level$level.regex"

        /** Course convention default: a name line followed by a '=' underline. */
        val DEFAULT_SECTION_REGEX =
            Regex("(?m)^[ \\t]*(?<name>\\S[^\\r\\n]*?)[ \\t]*\\r?\\n={2,}[ \\t]*$")

        /** Built-in dash frames: `---- topic ----` … `- aside -`. */
        val DEFAULT_LEVEL_PATTERNS: List<Regex> = CblParser.LEVEL_PATTERNS

        val DEFAULTS =
            CblConfig(DEFAULT_SECTION_REGEX, DEFAULT_LEVEL_PATTERNS, emptyList(), "defaults")

        /**
         * Path of [target] relative to the folder [fromDir], with `..` segments
         * where needed - the form `VirtualFile.findFileByRelativePath` and the
         * Markdown image rebasing both understand. Null without a common
         * ancestor. Hand-rolled on purpose: the platform helper's file-vs-folder
         * semantics are ambiguous, and this one is trivially testable.
         */
        fun relativePath(fromDir: VirtualFile, target: VirtualFile): String? =
            relativePath(fromDir.path, target.path)

        /** Pure form of [relativePath] - VFS paths are always '/'-separated,
         *  so the interesting part needs no VirtualFile and no fixture. */
        internal fun relativePath(fromDirPath: String, targetPath: String): String? {
            val from = fromDirPath.split('/').filter { it.isNotEmpty() }
            val to = targetPath.split('/').filter { it.isNotEmpty() }
            var common = 0
            while (common < from.size && common < to.size && from[common] == to[common]) common++
            if (common == 0) return null
            return buildString {
                repeat(from.size - common) { append("../") }
                append(to.drop(common).joinToString("/"))
            }
        }

        // single-threaded use (EDT); keyed by the chain of (path, stamp)
        // pairs - any edit to any file in the cascade invalidates (on save)
        private val cache = HashMap<String, CblConfig>()

        fun forSelectedFile(project: Project): CblConfig =
            forFile(project, FileEditorManager.getInstance(project).selectedFiles.firstOrNull())

        /**
         * Cascaded config for [file]: all `cbl.properties` between the file and
         * the project directory, merged per key, nearer wins. The search stops
         * at [outerBound] - never the filesystem root: a stray config in a home
         * directory must not silently reconfigure every course. Outside any
         * project (scratch files), a small depth cap applies instead.
         */
        fun forFile(project: Project?, file: VirtualFile?): CblConfig {
            if (file == null) return DEFAULTS
            val boundary = project?.let { p ->
                val contentRoot = ApplicationManager.getApplication().runReadAction(
                    Computable<VirtualFile?> { ProjectFileIndex.getInstance(p).getContentRootForFile(file) }
                )
                outerBound(p.guessProjectDir(), contentRoot)
            }
            // collect the cascade, nearest first
            val chain = mutableListOf<VirtualFile>()
            var dir = file.parent
            var depthLeft = if (boundary == null) FALLBACK_SEARCH_DEPTH else Int.MAX_VALUE
            while (dir != null && depthLeft-- > 0) {
                dir.findChild(FILE_NAME)?.let { chain.add(it) }
                if (dir == boundary) break // content root checked - stop here
                dir = dir.parent
            }
            if (chain.isEmpty()) return DEFAULTS

            val cacheKey = chain.joinToString("|") { "${it.path}@${it.timeStamp}" }
            cache[cacheKey]?.let { return it }

            val merged = mergeLayers(
                chain.map { it to runCatching { VfsUtilCore.loadText(it) }.getOrDefault("") }
            )
            val config = build(project, merged, chain)
            if (cache.size > 64) cache.clear() // stale stamps accumulate; keep it tiny
            cache[cacheKey] = config
            return config
        }

        private const val FALLBACK_SEARCH_DEPTH = 8

        /**
         * Where the upward search stops: the OUTERMOST of the project directory
         * and the file's content root, so long as both are on the same branch.
         *
         * The content root alone is too tight. It is not necessarily "the folder
         * opened as the project": Gradle's importer assigns a content root PER
         * SOURCE SET, so a module whose sources live in a non-standard folder
         * gets that folder as its content root - and a `cbl.properties` one
         * level above it, at the project level, was never seen. The symptom is
         * silent: no `glossary.path`, so every path-less short ref degrades to a
         * same-file lookup and resolves to nothing. In PyCharm and CLion the
         * whole sample is one content root, which is why it only showed up in a
         * Gradle project.
         *
         * The project directory alone would be too loose for files OUTSIDE it
         * (library sources, attached folders), where the content root is the
         * only meaningful bound. Hence: prefer the project directory when it
         * contains the content root, otherwise keep the content root. Neither
         * branch ever walks above the project.
         */
        private fun outerBound(projectDir: VirtualFile?, contentRoot: VirtualFile?): VirtualFile? =
            when (outerBoundPath(projectDir?.path, contentRoot?.path)) {
                null -> null
                projectDir?.path -> projectDir
                else -> contentRoot
            }

        /** Pure form of [outerBound] - VFS paths are always '/'-separated, so
         *  the decision needs no VirtualFile and no fixture. */
        internal fun outerBoundPath(projectDirPath: String?, contentRootPath: String?): String? = when {
            projectDirPath == null -> contentRootPath
            contentRootPath == null -> projectDirPath
            contentRootPath == projectDirPath -> projectDirPath
            contentRootPath.startsWith("$projectDirPath/") -> projectDirPath
            else -> contentRootPath
        }

        /**
         * Merge a cascade per KEY. [layers] is NEAREST-FIRST - the order
         * [forFile] collects them in - as (origin, file content) pairs; the
         * result maps each key to its winning value plus the origin it came
         * from, for precise error messages. Generic in the origin so tests can
         * pass plain names where production passes [VirtualFile]s.
         */
        internal fun <T> mergeLayers(layers: List<Pair<T, String>>): Map<String, Pair<String, T>> {
            val merged = HashMap<String, Pair<String, T>>()
            // farthest -> nearest, so nearer files overwrite per key
            for ((origin, text) in layers.asReversed()) {
                parse(text).forEach { (key, value) -> merged[key] = value to origin }
            }
            return merged
        }

        /** Validated section pattern, or the default plus a [warn] message. */
        internal fun sectionRegexOf(
            merged: Map<String, Pair<String, String>>,
            warn: (String) -> Unit = {},
        ): Regex {
            val (pattern, origin) = merged[KEY_SECTION_REGEX] ?: return DEFAULT_SECTION_REGEX
            return try {
                require("(?<name>" in pattern) { "pattern must contain a named group (?<name>...)" }
                Regex(pattern)
            } catch (e: Exception) {
                warn("Invalid $KEY_SECTION_REGEX in $origin:\n${e.message}\n" +
                    "Using the default pattern instead.")
                DEFAULT_SECTION_REGEX
            }
        }

        /**
         * Header patterns per level, always [CblParser.MAX_DEPTH] entries - a
         * course may redefine the frame of level 2 alone and inherit the rest,
         * built-in defaults included. An unusable pattern warns and keeps the
         * built-in one: a typo in a course config must not break the panel.
         */
        internal fun levelPatternsOf(
            merged: Map<String, Pair<String, String>>,
            warn: (String) -> Unit = {},
        ): List<Regex> {
            val levelPatterns = DEFAULT_LEVEL_PATTERNS.toMutableList()
            for (level in 1..CblParser.MAX_DEPTH) {
                val key = keyForLevel(level)
                val (pattern, origin) = merged[key] ?: continue
                try {
                    val regex = Regex(pattern)
                    require(regex.toPattern().matcher("").groupCount() >= 1) {
                        "pattern must capture the title, as (?<title>...) or as group 1"
                    }
                    levelPatterns[level - 1] = regex
                } catch (e: Exception) {
                    warn("Invalid $key in $origin:\n${e.message}\n" +
                        "Using the built-in pattern instead.")
                }
            }
            return levelPatterns
        }

        private fun build(
            project: Project?,
            merged: Map<String, Pair<String, VirtualFile>>,
            chain: List<VirtualFile>,
        ): CblConfig {
            val onProblem: (String) -> Unit = { message -> warn(project, message) }
            // the pure validators only need a LABEL for the origin
            val labelled = merged.mapValues { (_, v) -> v.first to v.second.presentableUrl }
            val sectionRegex = sectionRegexOf(labelled, onProblem)
            val levelPatterns = levelPatternsOf(labelled, onProblem)
            // comma-separated search path, each entry relative to the config
            // file that declares it; unresolvable entries warn and drop out
            val glossaryPath = mutableListOf<VirtualFile>()
            merged[KEY_GLOSSARY]?.let { (value, origin) ->
                val missing = mutableListOf<String>()
                for (entry in value.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    val file = origin.parent?.findFileByRelativePath(entry)
                    if (file != null) glossaryPath.add(file) else missing.add(entry)
                }
                if (missing.isNotEmpty()) {
                    warn(project, "$KEY_GLOSSARY in ${origin.presentableUrl}:\n" +
                        "${missing.joinToString(", ")} does not resolve to a file.\n" +
                        "Short refs ([#frag]) fall back to the remaining entries.")
                }
            }
            return CblConfig(
                sectionRegex, levelPatterns, glossaryPath, chain.joinToString(" <- ") { it.path }
            )
        }

        /** key = value lines, '#'/'!' comments, no escape processing. */
        private fun parse(text: String): Map<String, String> =
            text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") && '=' in it }
                .associate { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }

        private fun warn(project: Project?, message: String) {
            val group = NotificationGroupManager.getInstance()
                .getNotificationGroup("Codebook") ?: return
            group.createNotification(message, NotificationType.WARNING).notify(project)
        }
    }
}
