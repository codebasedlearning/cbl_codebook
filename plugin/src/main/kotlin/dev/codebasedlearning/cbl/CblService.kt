// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm

/**
 * Project service: owns the parsed CblModel of the currently selected editor,
 * reparses on file switch and (debounced) on document changes, applies folding,
 * and notifies the tool window.
 */
@Service(Service.Level.PROJECT)
class CblService(private val project: Project) : Disposable {

    var model: CblModel? = null
        private set

    /** The editor [model] was parsed from, i.e. the only editor whose caret and
     *  document say anything about it (see the multicaster filters below). */
    private var modelEditor: Editor? = null

    /** Document stamp [model] was parsed at - see [isStale]. */
    private var modelStamp: Long = -1L

    /**
     * True while the document has moved on since the model was parsed. Typing
     * shifts every offset after the caret, and the re-parse is debounced, so
     * for those 700ms the model's ranges describe a document that no longer
     * exists: resolving a caret against them names a block that is merely
     * NEAR the right one. That is what showed a neighbouring topic's text
     * while a line was being edited, and put the right one back on the next
     * keystroke, when the re-parse had caught up.
     */
    private fun isStale(): Boolean = modelEditor?.document?.modificationStamp != modelStamp

    private val modelListeners = mutableListOf<() -> Unit>()
    private val caretListeners = mutableListOf<(Int) -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Separate from [alarm] on purpose: a keystroke cancels the debounced
     *  re-parse, and must not cancel a pending console retry with it. */
    private val consoleAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
            })
        // After a run finishes, refresh to place the output gutter icons - but
        // only once the console has flushed. A ConsoleView prints
        // asynchronously, so at processTerminated its tail may still be queued,
        // and parsing then yields a text that is missing its LAST section.
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processTerminated(
                executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int,
            ) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    CblConsole.whenFlushed(project) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) refreshWhenConsoleReadable()
                        }
                    }
                }
            }
        })

        /*
         * The multicaster fires for EVERY editor of this project, and a Run
         * console is an editor: it has our project, a document, and a caret
         * that jumps to the end on every line of output. Unfiltered, running a
         * file therefore fed the CONSOLE's caret offset to the panel, which
         * resolved it against the SOURCE file's model - a small offset, so the
         * outline jumped to the first topic while the reader's caret had not
         * moved at all. Diff views, the commit dialog and the debugger's
         * evaluate field are editors too, with the same effect.
         *
         * Hence the filter: only the editor the model was parsed from speaks
         * for the model. Same for document changes - console output must not
         * trigger a re-parse of the source file every 700ms.
         */
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor !== modelEditor || isStale()) return
                caretListeners.forEach { it(event.editor.caretModel.offset) }
            }
        }, this)
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.document !== modelEditor?.document) return
                alarm.cancelAllRequests()
                alarm.addRequest({ refresh() }, 700)
            }
        }, this)
    }

    fun addModelListener(listener: () -> Unit) { modelListeners.add(listener) }
    fun addCaretListener(listener: (Int) -> Unit) { caretListeners.add(listener) }

    /**
     * Refresh, and try again while the Run console has nothing to read.
     *
     * `processTerminated` plus `performWhenNoDeferredOutput` says the OUTPUT has
     * arrived, not that the console is reachable: on the FIRST run of a session
     * the Run tool window is still being built, so the selected run content, or
     * the `ConsoleViewImpl`'s editor, can be missing for a moment.
     * [CblGutter] then keeps whatever icons it has and returns - deliberately,
     * it must not wipe icons over a console it cannot see - and since nothing
     * scheduled another attempt, the first run of the session ended up without
     * icons while every later one had them.
     *
     * Four attempts over about a second and a half, then give up: a program
     * that printed nothing has no sections either, and retrying forever would
     * only burn EDT cycles.
     */
    private fun refreshWhenConsoleReadable(attempt: Int = 0, seen: Int = -1) {
        if (project.isDisposed) return
        refresh()
        val length = CblConsole.textLength(project)
        // settled = readable AND unchanged since the previous attempt. Readable
        // alone is not enough: a console that is still filling yields the
        // sections printed SO FAR, and the gutter would then icon the first
        // function and no other, with nothing scheduled to correct it.
        if ((length != null && length == seen) || attempt >= 3) return
        // only from the second attempt on: one retry is the normal case, more
        // than one means the console is slow enough to be worth knowing about
        if (attempt >= 1) {
            CblConsole.log(
                "console not settled (attempt ${attempt + 1}, length=$length, was=$seen)" +
                    if (length == null) " - ${CblConsole.describe(project)}" else ""
            )
        }
        consoleAlarm.addRequest(
            { refreshWhenConsoleReadable(attempt + 1, length ?: -1) },
            150L * (attempt + 1) * 2,
        )
    }

    /** Must be called on the EDT (all our callers are). */
    fun refresh() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        modelEditor = editor
        if (editor == null) {
            model = null
            modelListeners.forEach { it() }
            return
        }
        val documentManager = PsiDocumentManager.getInstance(project)
        documentManager.performForCommittedDocument(editor.document) {
            val psiFile = documentManager.getPsiFile(editor.document) ?: return@performForCommittedDocument
            // header patterns come from the course config (cascaded cbl.properties)
            val parsed = CblParser.parse(psiFile, CblConfig.forSelectedFile(project).levelPatterns)
            model = parsed
            modelStamp = editor.document.modificationStamp
            CblFolding.apply(editor, parsed)
            CblGutter.apply(project, editor, psiFile)
            modelListeners.forEach { it() }
            // the caret was ignored while the model was stale, so the panel may
            // be a few keystrokes behind: catch it up now that the offsets mean
            // something again. Listeners that do not follow the caret ignore it.
            val offset = editor.caretModel.offset
            caretListeners.forEach { it(offset) }
        }
    }

    override fun dispose() {
        modelEditor = null
        modelListeners.clear()
        caretListeners.clear()
    }
}
