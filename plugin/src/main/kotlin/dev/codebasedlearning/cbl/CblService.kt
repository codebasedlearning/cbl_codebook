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

    private val modelListeners = mutableListOf<() -> Unit>()
    private val caretListeners = mutableListOf<(Int) -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

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
                            if (!project.isDisposed) refresh()
                        }
                    }
                }
            }
        })

        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val editor = event.editor
                if (editor.project === project) {
                    val offset = editor.caretModel.offset
                    caretListeners.forEach { it(offset) }
                }
            }
        }, this)
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ refresh() }, 700)
            }
        }, this)
    }

    fun addModelListener(listener: () -> Unit) { modelListeners.add(listener) }
    fun addCaretListener(listener: (Int) -> Unit) { caretListeners.add(listener) }

    /** Must be called on the EDT (all our callers are). */
    fun refresh() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
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
            CblFolding.apply(editor, parsed)
            CblGutter.apply(project, editor, psiFile)
            modelListeners.forEach { it() }
        }
    }

    override fun dispose() {
        modelListeners.clear()
        caretListeners.clear()
    }
}
