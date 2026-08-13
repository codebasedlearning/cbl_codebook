// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * Run-configuration derivation and execution, behind the panel's Run and Debug
 * buttons.
 */
object CblRun {

    /** Derive a run configuration from the current file's context (like the
     *  IDE's gutter run icon), fall back to the selected configuration. */
    fun runCurrentFile(project: Project, executor: Executor) {
        val settings = configurationFromContext(project)
            ?: RunManager.getInstance(project).selectedConfiguration
            ?: return
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    private fun configurationFromContext(project: Project): RunnerAndConfigurationSettings? {
        // PSI lookup and configurationsFromContext consult the module/file
        // index -> read access required. Swing listeners and gutter clicks
        // run on the EDT *without* the (formerly implicit) read lock.
        val settings = ApplicationManager.getApplication().runReadAction(
            Computable<RunnerAndConfigurationSettings?> {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                val psiFile = editor?.document?.let { PsiDocumentManager.getInstance(project).getPsiFile(it) }
                if (psiFile == null) null else {
                    val dataContext = SimpleDataContext.builder()
                        .add(CommonDataKeys.PROJECT, project)
                        .add(CommonDataKeys.EDITOR, editor)
                        .add(CommonDataKeys.PSI_FILE, psiFile)
                        .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
                        .build()
                    val context = ConfigurationContext.getFromContext(dataContext, "CblPanel")
                    context.configurationsFromContext?.firstOrNull()?.configurationSettings
                }
            }
        ) ?: return null
        RunManager.getInstance(project).setTemporaryConfiguration(settings)
        return settings
    }
}
