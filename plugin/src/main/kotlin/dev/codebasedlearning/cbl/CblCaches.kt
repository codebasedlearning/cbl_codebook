// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.openapi.components.Service

/**
 * The plugin's caches, owned by the PROJECT they were filled for.
 *
 * They used to live in `object`s - [CblConfig], [CblForeign], [CblConsole] -
 * i.e. for the lifetime of the IDE and across all its projects. Each was capped
 * or weakly keyed, so nothing grew without bound, but a parsed model or a
 * resolved `VirtualFile` of a project closed hours ago had no business being
 * reachable at all. As a project service the whole lot goes when the project
 * does, and two open courses can no longer answer each other's lookups.
 *
 * Plain maps on purpose: every reader and writer is on the EDT.
 */
@Service(Service.Level.PROJECT)
internal class CblCaches {
    /** [CblConfig.forFile]: cascade key (paths and stamps) -> merged config. */
    val configs = HashMap<String, CblConfig>()

    /** [CblForeign.model]: file path -> its parsed model. */
    val foreign = HashMap<String, CblForeign.Entry>()

    /** [CblConsole.outputModel]: the Run console as last parsed. */
    var console: CblConsole.Cached? = null
}
