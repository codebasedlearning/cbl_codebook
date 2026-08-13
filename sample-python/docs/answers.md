[© A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen](https://ami.codebasedlearning.dev)

# Answers — Python

Answers to the question blocks in the Python snippets. Referenced by the short
fold form, e.g. `!![#answer-classmethod-vs-static]`, which shows a headline and an arrow in the
panel — one click away, but not sitting in the source file.


## A-001                <a id="a-001"></a>

Ask what the callable must know. `from_kelvin` has to build an object of the
class it was called on, so it needs `cls` — with `Temperature(...)` hardcoded,
`Kelvin.from_kelvin(300)` would return a `Temperature` and the subclass would be
silently useless.
