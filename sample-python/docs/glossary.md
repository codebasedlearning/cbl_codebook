[© A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen](https://ami.codebasedlearning.dev)

# Glossary — Python

Shared definitions for the Python snippets. In Markdown files the **headings
are the blocks**: reference them from any snippet via `[#term]` (short form, the
file comes from `glossary.path`), `[term](doc/glossary.md#term)` (explicit path) or
`!![#term]` to offer it as a fold, or `![#term]` to show it outright. Slugs match GitHub's own
heading anchors, so these links also work when browsing the repository online.

## Main Guard

The `if __name__ == "__main__":` idiom. The guarded code only runs when the file
is executed as a script — on import, `__name__` holds the module name instead of
`"__main__"`. Keep the guarded part minimal (ideally one call to your main
function) to avoid accidental global variables.

## Decorator

A callable that takes a function (or class) and returns a replacement, applied
with the `@` syntax directly above a `def`:

```python
@print_function_header      # print_function_header(demo) is what actually runs
def demo(): ...
```

`@staticmethod` and `@classmethod` are the two decorators built into the
language's object model: they do not wrap the function in new behaviour, they
change how the *descriptor protocol* binds it when accessed through a class or
an instance — which is why one receives `cls`, and the other receives nothing.

## Namespace

A mapping from names to objects — a module, a class body, a function's locals.
Nothing forces a helper into a class: a static method's only advantage over a
module-level function is that it lives in the namespace a reader searches first.
"Namespaces are one honking great idea" (PEP 20) is about exactly this: keeping
names near the thing they belong to.

## Tail Call Optimization

A call is *in tail position* if it is the last thing a function does. In
languages with tail-call optimization (TCO) the stack frame is reused and the
recursion runs like a loop:

```python
def calc_sum_tail_recursively(n, acc=0):
    # tail position: nothing happens after the recursive call
    return acc if n <= 0 else calc_sum_tail_recursively(n - 1, acc + n)
```

Python deliberately does **not** perform TCO (Guido considers the lost stack
traces worse than the gained elegance) — deep recursion still overflows.

## Code Style

From PEP 8, the points that matter most in this course:

- spaces (no tabs) for indentation
- `lower_snake_case` for functions and variables
- no decorative file headers — version control already knows author and date
- type hints where they help the reader
- define a main function and use the [main guard](#main-guard)

## Pythonic Way

Solving a problem the way the language community considers idiomatic:
comprehensions over index loops, `with` for resources, unpacking over index
fiddling, EAFP (try/except) over look-before-you-leap where it reads better.
Shortest definition: code that makes an experienced Python reader nod instead of
squint.
