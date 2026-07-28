// (C) A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

/** ---- TOC ----

    This snippet discusses
    - the [#companion-object] - where Kotlin puts what other languages call static
    - what a companion gives up, and what it buys
    - why there is no `cls`, and why `Temperature` can be passed as a value

    Kotlin has no `static` keyword at all. The CBL blocks sit ABOVE the
    definitions here - Kotlin has no docstring position, so this is the
    C-family form of the Python convention.

    ![#companion-object]
 */

import utils.withFunctionHeader


/* ---- `Temperature` - a class with two kinds of functions ----

   `Temperature` carries one value and three callables that differ only in what
   Kotlin hands them as the receiver:

   | Kind                 | Receiver            | Typical use                     |
   |----------------------|---------------------|---------------------------------|
   | member function      | the instance        | needs the object's data         |
   | companion function   | the companion       | helper that belongs to the type |
   | [#top-level-function]| none                | helper that belongs to nobody   |

   Python has three kinds and one of them, `@classmethod`, receives the class
   itself. Kotlin has no equivalent. */

open class Temperature(val celsius: Double) {

    /* --- `asFahrenheit` - The member function ---
       It reads `celsius`, so it needs an object - the receiver `this`. */
    fun asFahrenheit(): Double = celsius * 9 / 5 + 32

    /* --- `Companion` - the object every "static" lives in ---
       Members declared here are called through the class name, which makes
       them look static. They are not: they are instance members of a singleton
       that happens to be called `Temperature.Companion`.

       Because it is an object, it can implement an interface - the whole point
       of the second example below. */
    companion object : TemperatureFactory {

        const val ZERO_CELSIUS_IN_KELVIN = 273.15

        /* -- `fromKelvin` - the factory --
           What Kotlin uses where Python writes an alternative constructor as a
           `@classmethod`. Unlike Python's `cls`, it has no idea which class the
           call was written on. */
        override fun fromKelvin(kelvin: Double): Temperature =
            Temperature(kelvin - ZERO_CELSIUS_IN_KELVIN)

        /* -- `isPlausible` - the helper --
           No object needed at all: it only reads its argument. As a
           [#top-level-function] it would work identically; written here it
           stays where a reader looks for it. */
        fun isPlausible(celsius: Double): Boolean = celsius in -273.15..5000.0
    }
}

/* --- The interface the companion implements ---
   An ordinary interface. That a class name can satisfy it is the Kotlin
   speciality this snippet is really about. */
interface TemperatureFactory {
    fun fromKelvin(kelvin: Double): Temperature
}

/* --- A subclass, for the last example --- */
class Kelvin(celsius: Double) : Temperature(celsius)


/* ---- Discussion ---- */

fun callingFunctions() = withFunctionHeader("callingFunctions") {
    /* --- `callingFunctions` ---

       Note the call syntax via class or instance.

       Rule:
       - Does the callable need the object's data? -> A member function.
       - Does it belong to the type? -> The companion.
       - Does it belong to neither? -> A [#top-level-function].

       Code:
       - `asFahrenheit` needs the receiver
       - `isPlausible` needs nothing - it only reads its argument */

    val t = Temperature(20.0)

    // call via instance `t`
    println(" 1| asFahrenheit()    -> ${t.asFahrenheit()}")

    // call via class `Temperature` - really: via its companion
    println(" 2| fromKelvin(300)   -> ${"%.2f".format(Temperature.fromKelvin(300.0).celsius)}")
    println(" 3| via class         -> ${Temperature.isPlausible(20.0)}")

    /* -- Not reachable through the instance --
       Unlike a Java or C++ static, a companion function cannot be called on an
       object: `t.isPlausible(20.0)` does not compile. The name lives in the
       companion, not in `Temperature`.

       ![#background-companion-not-on-instance] */
    println(" 4| explicit companion-> ${Temperature.Companion.isPlausible(20.0)}")
}


fun theCompanionIsAnObject() = withFunctionHeader("theCompanionIsAnObject") {
    /* --- `theCompanionIsAnObject` ---

       Members of a companion only look static - they are instance members of a
       singleton. Which means the class name is a VALUE, and can be passed to
       anything expecting the interface the companion implements.

       No `static` in any language does that. See also [#jvmstatic] for the case
       where you really do need a JVM static.

       ![#background-companion-as-value] */

    // `Temperature` here is not the class - it is its companion object
    val factory: TemperatureFactory = Temperature

    println(" 1| factory is        -> ${factory::class.simpleName}")
    println(" 2| factory.fromKelvin-> ${"%.2f".format(factory.fromKelvin(300.0).celsius)}")
    println(" 3| same singleton?   -> ${factory === Temperature.Companion}")
}


fun whyThereIsNoCls() = withFunctionHeader("whyThereIsNoCls") {
    /* --- `whyThereIsNoCls` ---

       What if we ask the factory for a `Kelvin` - do we get one, the way
       Python's `@classmethod` would give us?

       ![#answer-no-cls-in-kotlin] */

    val t = Temperature.fromKelvin(300.0)
    println(" 1| fromKelvin(300)   -> ${t::class.simpleName}")

    val k = Kelvin(26.85)
    println(" 2| a real Kelvin     -> ${k::class.simpleName}")
    println(" 3| still plausible?  -> ${Temperature.isPlausible(k.celsius)}")
}


/* ---- Run ----

   See also
   ![#main-function]
   ![#code-style]
   ![#the-idiomatic-way]
 */

fun main() {
    callingFunctions()
    theCompanionIsAnObject()
    whyThereIsNoCls()
}


/* ---- Codebook ----

   In this snippet we structured the code such that the main discussion
   points are shown and navigable inside the Codebook-Window.

   Answers to questions can be shown or hidden, same for definitions or
   concepts such as 'Companion Object'.

   In fact, text is rendered, so you can also show some links
   [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
   or html
   <center><img src="../doc/Logo_CBL_2024_72.png" width="100"></center>
 */
