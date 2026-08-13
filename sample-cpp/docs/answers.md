[© A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen](https://ami.codebasedlearning.dev)

# Answers and Background — C++

Answers or background information to the question blocks in the C++ snippets. 
Referenced by the short fold form, e.g. `!![#background-static-via-object]`,
which shows only a headline and an arrow until the reader clicks.


## Background static-via-object

Because the object contributes nothing to the call. `t.is_plausible(20.0)` looks
up the name in `Temperature`'s scope, finds a function with no implicit `this`
parameter, and calls it — the expression `t` is still *evaluated* (side effects
happen), then discarded. The language allows it so that the call syntax does not
have to change when a member function is made `static`.


## A-001                                    <a id="a-001"></a>

`from_kelvin` names the type it constructs, and a static member function
receives nothing that says which class the call was *written* on.
`Kelvin::from_kelvin` and `Temperature::from_kelvin` are the same function, so
both return a `Temperature`. Python's `@classmethod` receives that missing
information as `cls`, which is precisely why `Kelvin.from_kelvin(...)` returns a
`Kelvin`.

The C++ ways to get the same effect are explicit: a template parameter
(`template <class T> static T from_kelvin(...)`), CRTP, or simply a named
constructor per class.
