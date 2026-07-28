// (C) A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package utils

/**
 * Prints the section header the Codebook plugin looks for in the Run console:
 * a name line underlined with '=' (see output.section.regex in cbl.properties),
 * then runs [block].
 *
 * Kotlin has no decorators, but it has functions taking a lambda - so this
 * wraps the call the way the Python sample's `@print_function_header` wraps the
 * function:
 *
 *     fun callingFunctions() = withFunctionHeader("callingFunctions") {
 *         ...
 *     }
 */
fun withFunctionHeader(name: String, block: () -> Unit) {
    println("\n$name\n${"=".repeat(name.length)}")
    block()
}
