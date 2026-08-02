import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.codebasedlearning"
version = "1.0.4"

base {
    archivesName = "codebook"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.4")
        // Java language support for tests: the parser tests use .java fixtures,
        // and the bare platform test framework has no Java PSI.
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.intellij.plugins.markdown")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testImplementation("junit:junit:4.13.2")
    // Known issue: the platform test framework is missing this transitively
    // (see IPGP FAQ, NoClassDefFoundError: org/opentest4j/AssertionFailedError).
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    projectName = "codebook"

    // @NotNull bytecode instrumentation is not needed for this plugin;
    // avoids the "No Java Compiler dependency found" error in IPGP 2.1.0.
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // No upper bound: newer IDEs may load the plugin.
            untilBuild = provider { null }
        }
    }

    /*
     * Signing: `signPlugin` runs automatically before `publishPlugin` when
     * certificateChain and privateKey are set, and is skipped otherwise - so a
     * plain `buildPlugin` still works without any secrets present. Values come
     * from the environment; never commit a key. See DEVELOPMENT.md for the
     * openssl commands that produce them. Multi-line PEM content may also be
     * passed base64-encoded; the task detects and decodes that.
     */
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// IPGP names the distribution after the Gradle module, which is "plugin" here
// (it lives in plugin/, beside the sample projects) - and it sets the archive
// name on the task itself, so `base { archivesName }` is not enough.
tasks.named<Zip>("buildPlugin") {
    archiveBaseName = "codebook"
}

/*
 * Works around a bug in IPGP 2.18.1, not in our setup.
 *
 * VerifyPluginSignatureTask builds the CLI arguments from `certificateChain`
 * (the CONTENT, which is what signing reads from CERTIFICATE_CHAIN) like this:
 *
 *     yield("-cert"); yield(temporaryCertificateChainFile)   // correct
 *     yield(<the certificate text itself>)                   // stray argument
 *
 * The signer receives that trailing text as an unrecognised positional argument
 * and exits 64 - a usage error, NOT a bad signature. The duplicated debug line
 * right next to it says the same thing about how it got there. Its
 * `certificateChainFile` branch has no such slip, so we send the verify task
 * down that one; signing itself keeps reading the environment variables.
 *
 * Remove once the upstream task is fixed - the symptom to re-test is exit 64.
 */
tasks.named<VerifyPluginSignatureTask>("verifyPluginSignature") {
    certificateChain.set(null as String?)
    certificateChainFile = rootProject.layout.projectDirectory.file("chain.crt")
}

kotlin {
    // Java 21: safe since sinceBuild=261 (2026.1 runs on JBR 21).
    jvmToolchain(21)

    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatformTesting {
    runIde {
        register("runClion") {
            type = IntelliJPlatformType.CLion
            version = "2026.1"
        }
        register("runPycharm") {
            // PyCharm unified as well: PyCharmCommunity (PC) is not published
            // since 2025.3 either - use the PyCharm type from then on.
            type = IntelliJPlatformType.PyCharm
            version = "2026.1"
        }
    }
}
