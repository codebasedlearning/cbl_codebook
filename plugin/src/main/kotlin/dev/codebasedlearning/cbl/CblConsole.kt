// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentManager
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.lang.ref.WeakReference

/** Interaction with the IDE Run console: section lookup, focus + highlight. */
object CblConsole {

    private val LOG = Logger.getInstance(CblConsole::class.java)

    private val HIGHLIGHT = Key.create<RangeHighlighter>("cbl.console.highlight")

    /** The console of the selected run content, whatever type it is. */
    fun executionConsole(project: Project): ExecutionConsole? =
        RunContentManager.getInstance(project).selectedContent?.executionConsole

    /**
     * The console editor whose text we parse into output sections.
     *
     * A plain run configuration (CMake app, Python script, JVM application)
     * puts a [ConsoleViewImpl] into the run content and the first branch is all
     * it takes. A run DELEGATED TO AN EXTERNAL SYSTEM - Gradle, Maven - puts a
     * `BuildView` there instead, which merely WRAPS the real console; the log
     * shows it as `ExternalSystemRunnableState$3`, the anonymous BuildView
     * subclass created in `ExternalSystemRunnableState.createBuildView`.
     *
     * Reaching the wrapped console has exactly one door that is not marked
     * internal. `BuildView.getConsoleView()` is `@ApiStatus.Internal`, and so is
     * `CompositeView.getView("consoleView")` (internal AND experimental) - using
     * either would put INTERNAL_API_USAGES back into the verifier report, which
     * is an explicit marketplace approval criterion. But `BuildView` also
     * publishes the inner console in its data snapshot under
     * [LangDataKeys.CONSOLE_VIEW], and that key is ordinary public API.
     *
     * Must be called on the EDT - all our callers are (refresh, gutter click).
     */
    fun editor(project: Project): Editor? {
        val console = executionConsole(project) ?: return null
        (console as? ConsoleViewImpl)?.let { return it.editor }
        val inner = runCatching {
            DataManager.getInstance().getDataContext(console.component).getData(LangDataKeys.CONSOLE_VIEW)
        }.getOrNull()
        if (inner === console) return null // a wrapper answering with itself
        return (inner as? ConsoleViewImpl)?.editor
    }

    /**
     * What the console lookup currently sees, for the log. The interesting part
     * is the CLASS: only a [ConsoleViewImpl] has an editor we can read, and a
     * run delegated to an external system (Gradle, Maven) puts a composite view
     * there instead, whose inner console is reachable only through internal API.
     */
    fun describe(project: Project): String {
        val content = RunContentManager.getInstance(project).selectedContent
        val console = content?.executionConsole
        return "content='${content?.displayName}', console=${console?.javaClass?.name ?: "none"}" +
                ", unwrapped=${editor(project) != null}" +
                ", textLength=${editor(project)?.document?.textLength ?: -1}"
    }

    /**
     * Run the [action] once the console has flushed everything it was given.
     * [ConsoleView] prints asynchronously, so the text present the moment a
     * process terminates can be short of its tail - which showed up as the LAST
     * output section having no gutter icon while the earlier ones had one.
     * Falls back to running immediately when there is no console to ask.
     */
    fun whenFlushed(project: Project, action: () -> Unit) {
        val console = executionConsole(project) as? ConsoleView
        if (console == null) action() else console.performWhenNoDeferredOutput(action)
    }

    // The console text is re-read on every gutter refresh and every caret move
    // (the panel shows a section's output), so parse it once per document state.
    // Keyed by the DOCUMENT as well, not just its stamp: this cache is
    // object-level, so it outlives projects and consoles, and a fresh console
    // must never be answered with the previous one's sections.
    private class Cached(
        val document: WeakReference<Document>,
        val stamp: Long,
        val config: CblConfig,
        val model: CblOutputModel,
    )

    private var cached: Cached? = null

    /** Parsed Run-console output, or null if there is no console text. */
    fun outputModel(project: Project): CblOutputModel? {
        val console = editor(project) ?: return null
        val document = console.document
        if (document.textLength == 0) return null
        val stamp = document.modificationStamp
        val config = CblConfig.forSelectedFile(project)
        cached?.let {
            if (it.document.get() === document && it.stamp == stamp && it.config === config) return it.model
        }
        val model = CblOutputModel(document.text, config.sectionRegex)
        cached = Cached(WeakReference(document), stamp, config, model)
        return model
    }

    /** Names of the output sections currently present in the Run console. */
    fun sectionNames(project: Project): Set<String> =
        outputModel(project)?.sections?.mapTo(mutableSetOf()) { it.name } ?: emptySet()

    /**
     * Scroll the Run console to the section header, highlight it, show the
     * window - and say so when it cannot, rather than swallowing the click.
     *
     * A gutter icon can outlive the console it was made from: [CblGutter] keeps
     * the icons when the console becomes unreadable, on the grounds that they
     * still point at output that was produced. The price is a click that has
     * nothing to act on, and a silent one is indistinguishable from a broken
     * plugin - the same lesson the fold buttons taught.
     */
    fun focusSection(project: Project, name: String) {
        val console = editor(project)
        if (console == null) {
            log("focusSection('$name'): no readable console - ${describe(project)}")
            warn(project, "No readable Run console for '$name'. Run the file again, or select its tab in the Run window.")
            return
        }
        val out = outputModel(project) ?: return
        val section = out.sections.firstOrNull { it.name == name }
        if (section == null) {
            log("focusSection('$name'): not among ${out.sections.map { it.name }}")
            warn(project, "The Run console no longer contains a section '$name'.")
            return
        }
        val start = out.lineStartOffset(section.startLine)
        val end = out.lineEndOffset(section.startLine)
        if (end > console.document.textLength) return

        console.getUserData(HIGHLIGHT)?.dispose()
        val attributes = TextAttributes(null, JBColor(Color(255, 235, 130), Color(110, 90, 20)), null, null, Font.PLAIN)
        val highlighter = console.markupModel.addRangeHighlighter(
            start, end, HighlighterLayer.SELECTION - 1, attributes, HighlighterTargetArea.EXACT_RANGE
        )
        console.putUserData(HIGHLIGHT, highlighter)
        console.caretModel.moveToOffset(start)
        console.scrollingModel.scrollTo(console.offsetToLogicalPosition(start), ScrollType.CENTER)
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.RUN)?.show(null)
    }

    internal fun log(message: String) = LOG.info("Codebook: $message")

    private fun warn(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Codebook")
            ?.createNotification(message, NotificationType.WARNING)?.notify(project)
    }
}
