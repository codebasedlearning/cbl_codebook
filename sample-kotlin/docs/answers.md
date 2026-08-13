[© A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen](https://ami.codebasedlearning.dev)

# Answers and Background — Kotlin

Answers or background information to the question blocks in the Kotlin snippets.
Referenced by the short fold form, e.g. `!![#background-companion-as-value]`,
which shows only a headline and an arrow until the reader clicks.


## Background companion-not-on-instance

Because the name lives in the companion object, not in the class. A Java or C++
static is a member of the *class*, so the language lets an instance stand in for
the class name and then discards it. Kotlin has no such rule: `Temperature` and
`Temperature.Companion` are the qualifiers that work, an instance is not one.

The consequence is worth noticing — `t.isPlausible(20.0)` failing to compile is
the compiler telling you that the call never needed `t` in the first place.


## Background companion-as-value

`Temperature` used where a value is expected does not mean the class; it means
its companion object. Since that object implements `TemperatureFactory`, the
class name satisfies the interface, and the factory can be stored, passed to a
function, or swapped for a test double.

This is the practical payoff of "a companion is an instance, not a static": no
`static` in Java, C++ or Python can be handed to something expecting an
interface. It is also why Kotlin needs no separate factory-class boilerplate.


## Answer no-cls-in-kotlin

No. `fromKelvin` names the type it constructs, and there is exactly one
companion object in the whole hierarchy — it belongs to `Temperature`, and
`Kelvin` does not get one of its own. Nothing in the call says which class the
call was *written* on, so the result is always a `Temperature`. Python's
`@classmethod` receives precisely that missing information as `cls`, which is
why `Kelvin.from_kelvin(...)` returns a `Kelvin`.

The Kotlin ways to get the same effect are explicit: a reified type parameter
(`inline fun <reified T : Temperature> fromKelvin(...)`), a factory interface
implemented per class — the one this snippet already declares — or simply a
constructor per class.
