// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

class CblSettingsState : BaseState() {
    /** Selection and notes track the block above the caret. On by default. */
    var followCaret by property(true)
}

/**
 * What the panel remembers between sessions - per project, and in the
 * WORKSPACE file: follow-caret is how one person likes to work, not a course
 * convention, so it must not travel with the repository the way
 * `cbl.properties` does.
 */
@Service(Service.Level.PROJECT)
@State(name = "CodebookSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class CblSettings : SimplePersistentStateComponent<CblSettingsState>(CblSettingsState()) {

    var followCaret: Boolean
        get() = state.followCaret
        set(value) {
            state.followCaret = value
        }
}
