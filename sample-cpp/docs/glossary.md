[© A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen](https://ami.codebasedlearning.dev)

# Glossary — C++

Shared definitions for the C++ snippets. In Markdown files the **headings are
the blocks**: reference them from any snippet via `[#term]` (short form, the file
comes from `glossary.path`), `[term](doc/glossary.md#term)` (explicit path) or
`!![#term]` to offer it as a fold, or `![#term]` to show it outright. Slugs match GitHub's own
heading anchors, so these links also work when browsing the repository online.

Language-specific on purpose: the Python project has its own pair of files, so
nothing has to stay in sync across two languages.

## Named Constructor

A static member function that returns an object, used where a constructor would
be ambiguous or unnamed:

```cpp
static Temperature from_kelvin(double kelvin);   // reads better than Temperature(double, tag)
```

C++ constructors cannot be named or overloaded on intent, only on parameter
types — so the idiom trades `Temperature{300.0, kelvin_tag{}}` for a name a
reader understands. It is the closest counterpart to Python's `@classmethod`
alternative constructor, minus the `cls` parameter.

## Scope

The region of a program in which a name is visible — a namespace, a class body,
a function, a block. Nothing forces a helper into a class: a static member
function's only advantage over a free function is that it lives in the scope a
reader searches first, and that it may touch the class's private members.

Not to be confused with *lifetime*: `static` at block scope changes the lifetime
of a variable, not the scope in which its name can be used.

## Translation Unit

One source file after preprocessing — the unit the compiler actually sees, and
the unit the linker later joins with the others. `static` at file scope gives a
name *internal linkage*, meaning it is invisible to every other translation
unit; an unnamed namespace does the same thing and is preferred in modern C++,
because it also works for types.

## Code Style

The points that matter most in this course:

- four spaces, no tabs; braces on the same line as the statement
- `lower_snake_case` for functions and variables, `_` suffix for data members
- `[[nodiscard]]` on functions whose result is the whole point
- `const` by default — on member functions, parameters and locals
- prefer uniform initialization (`double x{1.0}`) over `=` where it reads clearly
- one demo function per topic, called from `main`

## The Modern Way

Solving a problem the way the standard library and the
[Core Guidelines](https://isocpp.github.io/CppCoreGuidelines/CppCoreGuidelines)
suggest rather than the way C would: values and references over raw pointers,
RAII over manual cleanup, range-based `for` over index arithmetic, `constexpr`
over macros, unnamed namespaces over file-scope `static`. Shortest definition:
code that makes an experienced C++ reader nod instead of reach for a debugger.
