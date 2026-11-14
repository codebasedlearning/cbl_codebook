// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * The panel's commands as REGISTERED actions (see plugin.xml), rather than
 * buttons that exist only inside the tool window: they show up in Find Action
 * and in the keymap, so presentation mode can sit on a shortcut - in front of
 * an auditorium that beats aiming at a 26px icon. The panel's toolbar shows the
 * very same actions, so there is one implementation and one state.
 */
object CblActionIds {
    const val FOLLOW_CARET = "Codebook.FollowCaret"
    const val UNFOLD_ALL = "Codebook.UnfoldAll"
    const val FOLD_ALL = "Codebook.FoldAll"
}

/** Everything the plugin says to the user, in one notification group. */
internal object CblNotify {
    private const val GROUP = "Codebook"

    fun info(project: Project?, message: String) = show(project, message, NotificationType.INFORMATION)
    fun warn(project: Project?, message: String) = show(project, message, NotificationType.WARNING)

    private fun show(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            ?.createNotification(GROUP, message, type)?.notify(project)
    }
}

/** Fold-all / unfold-all for the CBL comments of the selected editor. */
object CblFoldCommand {

    /**
     * Never silent: a command that finds nothing to do says why. A dead button
     * is indistinguishable from a broken plugin - but a balloon is enough, the
     * modal dialogs this used to raise stopped a lecture to say "nothing here".
     */
    fun setAll(project: Project, expand: Boolean) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            CblNotify.info(project, "No editor selected.")
            return
        }
        val model = project.service<CblService>().model
        if (CblFolding.setAll(editor, model, expand)) return

        val blocks = model?.blocks ?: emptyList()
        val foldable = blocks.count { it.isFoldable }
        if (foldable > 0 && model != null) {
            val live = CblFolding.apply(editor, model)
            if (CblFolding.setAll(editor, model, expand)) return
            CblNotify.warn(
                project,
                "$foldable foldable block(s) in the model, but the editor holds " +
                    "$live fold region(s) for them and reports " +
                    "${CblFolding.regionCount(editor, model)} of ours. " +
                    "The folding model refused the ranges - please report this " +
                    "together with the file."
            )
            return
        }
        CblNotify.info(
            project,
            if (blocks.isNotEmpty())
                "All ${blocks.size} CBL block(s) in this file are single-line comments - " +
                    "there is no note body to fold away."
            else
                "Parser sees 0 blocks in the current file. " +
                    "If the file clearly contains CBL comments, the language's " +
                    "PSI probably provides no comment tokens here (CLion Nova?) - " +
                    "try Settings > Advanced Settings > classic C/C++ language engine."
        )
    }
}

class CblUnfoldAllAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { CblFoldCommand.setAll(it, expand = true) }
    }
}

class CblFoldAllAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { CblFoldCommand.setAll(it, expand = false) }
    }
}

/** Follow-caret, persisted per project in [CblSettings]. */
class CblFollowCaretAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.service<CblSettings>()?.followCaret ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        project.service<CblSettings>().followCaret = state
        // switching follow back on: sync to the current caret immediately
        if (state) project.service<CblService>().syncCaret()
    }
}
