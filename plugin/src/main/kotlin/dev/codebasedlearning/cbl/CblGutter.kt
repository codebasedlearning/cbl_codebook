// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

/**
 * Output-linking gutter icons: if a section name from the Run console occurs in
 * the source file, its first occurrence OUTSIDE A COMMENT - usually the function
 * definition - gets an icon, and clicking it focuses that section in the
 * console. One icon per section name, so the anchor has to be the right one.
 *
 * Per-block run triangles existed until v0.6 (on the removed '>' marker family)
 * and would need the section runner first: running the WHOLE file from a
 * block-scoped icon promises more than it delivers.
 */
object CblGutter {

    private val CBL_GUTTER = Key.create<Boolean>("cbl.gutter")

    fun apply(project: Project, editor: Editor, file: PsiFile? = null) {
        /*
         * Read BEFORE clearing, and keep what is on screen when there is nothing
         * to read. `clear()` used to run unconditionally, so any refresh that
         * happened while the console was unreadable - a rerun that had emptied
         * it, a run whose output went somewhere we cannot see - wiped the icons
         * for good: nothing schedules a retry, so they stayed gone until the
         * next run. Stale icons are the better failure: they point at output
         * that WAS produced, and the next readable console replaces them.
         */
        val model = CblConsole.outputModel(project)
        if (model == null) {
            CblConsole.log("gutter: no readable console, keeping ${count(editor)} icon(s) - ${CblConsole.describe(project)}")
            return
        }
        clear(editor)
        val names = model.sections.mapTo(mutableSetOf()) { it.name }
        val text = editor.document.charsSequence
        var placed = 0
        for (name in names) {
            val index = anchorOffset(text, name, file)
            if (index < 0) continue
            val line = editor.document.getLineNumber(index)
            val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null)
            highlighter.putUserData(CBL_GUTTER, true)
            highlighter.gutterIconRenderer = CblGutterIcon(project, name)
            placed++
        }
        CblConsole.log("gutter: ${names.size} section(s) ${names.sorted()}, $placed icon(s) placed")
    }

    private fun count(editor: Editor): Int =
        editor.markupModel.allHighlighters.count { it.getUserData(CBL_GUTTER) == true }

    /**
     * Where to hang the icon for an output section: the first occurrence of the
     * name that is NOT inside a comment - usually the definition line.
     *
     * The plain first-occurrence rule put the icon on a mention instead: a TOC
     * block that says "see whyThereIsNoCls below" comes before the function, so
     * the name matched there, and since one name yields one icon, the function
     * itself got none. Prose about a function is exactly what a didactic file is
     * full of, so this is the normal case here, not an edge case.
     *
     * Falls back to the first occurrence anywhere when every hit sits in a
     * comment - an icon in the wrong place still beats no icon, and it is
     * visible enough to be reported. Python docstrings are strings rather than
     * comments, so a name mentioned in one still wins; PSI has no cheaper answer
     * that stays language-agnostic.
     */
    private fun anchorOffset(text: CharSequence, name: String, file: PsiFile?): Int {
        var from = 0
        var firstAnywhere = -1
        while (true) {
            val index = text.indexOf(name, from)
            if (index < 0) break
            if (firstAnywhere < 0) firstAnywhere = index
            if (file == null || !isInsideComment(file, index)) return index
            from = index + 1
        }
        return firstAnywhere
    }

    private fun isInsideComment(file: PsiFile, offset: Int): Boolean {
        val element = file.findElementAt(offset) ?: return false
        return PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null
    }

    fun clear(editor: Editor) {
        editor.markupModel.allHighlighters
            .filter { it.getUserData(CBL_GUTTER) == true }
            .forEach { it.dispose() }
    }
}

/** Console-section icon: clicking scrolls the Run console to that section. */
class CblGutterIcon(private val project: Project, private val name: String) : GutterIconRenderer() {
    override fun getIcon(): Icon = AllIcons.Actions.Preview
    override fun getTooltipText(): String = "Show output of '$name'"
    override fun isNavigateAction(): Boolean = true
    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = CblConsole.focusSection(project, name)
    }

    override fun equals(other: Any?): Boolean = other is CblGutterIcon && other.name == name
    override fun hashCode(): Int = name.hashCode()
}
