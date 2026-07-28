# Answers — Python

Answers to the question blocks in the Python snippets. Referenced by the short
embed form, e.g. `![#answer-classmethod-vs-static]`, which renders folded in the
panel — one click away, but not sitting in the source file.


## Answer classmethod-vs-static

Ask what the callable must know. `from_kelvin` has to build an object of the
class it was called on, so it needs `cls` — with `Temperature(...)` hardcoded,
`Kelvin.from_kelvin(300)` would return a `Temperature` and the subclass would be
silently useless.
