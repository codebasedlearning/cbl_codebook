// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Instantiates the service (which wires up all listeners) and does an initial parse. */
class CblStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.service<CblService>()
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) service.refresh()
        }
    }
}
