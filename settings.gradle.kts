// One Gradle build, one module: the plugin. The sample projects are deliberately
// NOT Gradle subprojects - each is a standalone project root, opened in the IDE
// that speaks its language (CLion, PyCharm, IDEA).
rootProject.name = "codebook"

include("plugin")
