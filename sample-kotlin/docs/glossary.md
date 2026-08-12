# Glossary — Kotlin

Shared definitions for the Kotlin snippets. In Markdown files the **headings are
the blocks**: reference them from any snippet via `[#term]` (short form, the file
comes from `glossary.path`), `[term](doc/glossary.md#term)` (explicit path) or
`!![#term]` to offer it as a fold, or `![#term]` to show it outright. Slugs match GitHub's own
heading anchors, so these links also work when browsing the repository online.

Language-specific on purpose: the Python and C++ projects have their own pairs
of files, so nothing has to stay in sync across languages.

## Companion Object

The single object declared inside a class with the `companion` keyword. Its
members are called through the class name, which makes them look like statics
from Java or C++:

```kotlin
class User(val name: String) {
    companion object { fun create(name: String) = User(name) }
}
User.create("Ada")
```

They are not statics. A companion is an ordinary singleton — an *instance* — so
it can hold state, implement interfaces, and be passed around as a value. There
is exactly **one** companion per class, and a subclass does not get one of its
own, which is why a factory in a base class can never build the derived type on
its own.

## Top-Level Function

A function declared outside any class, directly in a file. Kotlin has them, so
the Java habit of parking helpers in a `Utils` class is unnecessary — a
companion function's only advantage over a top-level one is that it lives in the
namespace a reader searches first, and that it may touch the class's private
members.

## JvmStatic

The annotation that makes a companion member compile to a real JVM `static`
method or field:

```kotlin
companion object { @JvmStatic fun fromKelvin(k: Double) = ... }
```

Only relevant for Java interoperability — Java callers otherwise have to write
`Temperature.Companion.fromKelvin(...)`. From Kotlin, nothing changes.

## Main Function

A top-level `fun main()` is the entry point; unlike Python there is no guard to
write, because importing a file never executes it. On the JVM the compiler
generates a class named after the file (`staticmethod.kt` → `StaticmethodKt`)
that holds it — which is the name a build script needs as its main class.

## Code Style

From the Kotlin coding conventions, the points that matter most in this course:

- four spaces, no tabs
- `lowerCamelCase` for functions and properties, `UpperCamelCase` for types
- expression bodies (`fun f() = ...`) where the body is one expression
- `val` by default, `var` only when something really changes
- type hints where they help the reader; omit them where the value says it
- one demo function per topic, called from `main`

## The Idiomatic Way

Solving a problem the way the standard library suggests rather than the way Java
would: data classes over getters and setters, `when` over chained `if`, scope
functions (`let`, `apply`, `run`) over temporary variables, extension functions
over utility classes, nullability in the type system over `null` checks.
Shortest definition: code that makes an experienced Kotlin reader nod instead of
ask why it looks like Java.
