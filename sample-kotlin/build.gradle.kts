plugins {
    kotlin("jvm") version "2.4.0"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    // The snippets keep the layout of the other samples - snippets/ for the
    // code, snippets/utils/ for the shared helpers - instead of Gradle's
    // src/main/kotlin. A directory under the source root is a package, so
    // snippets/utils/printing.kt declares `package utils` and the snippet
    // writes `import utils.withFunctionHeader`, exactly as the Python sample
    // writes `from utils import ...`.
    sourceSets["main"].kotlin.setSrcDirs(listOf("snippets"))
}

application {
    // staticmethod.kt has no package declaration, so its top-level `main` ends
    // up in a generated class named after the file.
    mainClass = "StaticmethodKt"
}
