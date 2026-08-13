# (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

""" ---- Content ----

Teaching Focus
- `@staticmethod` – a !![#decorator] for a plain function in a class.
- What `@staticmethod` gives up, and what it buys.
- When `@classmethod` is the better answer.

Idea Codebook
- In this snippet we structured the code such that the main discussion
  points are shown and navigable inside the Codebook-Window.
- Answers to questions can be shown or hidden, same for definitions or
  concepts such as 'Decorator'.
- In fact, text is rendered, so you can also show some links
  [PEP 8 - Style Guide for Python Code](https://peps.python.org/pep-0008)
  or html <p>
  <center><img src="../docs/Logo_CBL_2024_72.png" width="50"></center>

See also
- !![#decorator]
"""

""" ---- Header ---- """

from typing import Self
from utils import print_function_header

""" ---- Discussion ---- """

class Temperature:
    """ --- `Temperature` - A class with all three kinds of functions. ---

    `Temperature` carries one value and three callables that differ only in what
    Python hands them as the first argument:

    | Kind            | First argument | Typical use                     |
    |-----------------|----------------|---------------------------------|
    | instance method | `self`         | needs the object's data         |
    | `@classmethod`  | `cls`          | alternative constructor         |
    | `@staticmethod` | nothing        | helper that belongs to the type |
    """

    ZERO_CELSIUS_IN_KELVIN = 273.15

    def __init__(self, celsius: float):
        self.celsius = celsius

    def as_fahrenheit(self) -> float:
        """ -- `as_fahrenheit` – The instance method --
        It reads `self.celsius`, so it needs an object `self`. """
        return self.celsius * 9 / 5 + 32

    @classmethod
    def from_kelvin(cls, kelvin: float) -> Self:
        """ -- .`from_kelvin` – The class method --
        It constructs an instance via `cls`.

        `cls` is the class the call was made on, so a subclass gets *its* own
        type back - which is exactly why `Temperature(...)` is not hardcoded.
        """
        return cls(kelvin - cls.ZERO_CELSIUS_IN_KELVIN)

    @staticmethod
    def is_plausible(celsius: float) -> bool:
        """ -- .`is_plausible` – The static method --
        No `self`, no `cls`: a plain function in the class's [#namespace].

        It validates a value without needing any object at all. Written as a
        module function it would work identically; written here it stays where a
        reader looks for it.
        """
        return -273.15 <= celsius <= 5000.0

@print_function_header
def calling_functions():
    """ --- `calling_functions` ---

    Note the call syntax via class or instance or both.

    Rule:
    - Does the callable need the class it was called on? -> `@classmethod`.
    - Does it need neither the object nor the class? -> `@staticmethod`.
    - Does it need the object's data? -> A plain method.

    Code:
    - `from_kelvin` needs `cls`
    - `is_plausible` needs nothing - it only reads its argument
    """

    t = Temperature(20.0)

    # call via instance `t`
    print(f" 1| as_fahrenheit()   -> {t.as_fahrenheit()}")

    # call via class `Temperature`
    print(f" 2| from_kelvin(300)  -> {Temperature.from_kelvin(300.0).celsius:.2f}")

    # call via class or instance
    print(f" 3| via class         -> {Temperature.is_plausible(20.0)}")
    print(f" 4| via instance      -> {t.is_plausible(20.0)}")


@print_function_header
def why_cls_is_important():
    """ --- `why_cls_is_important` ---

    !![What if we do not use `return cls(...)` in `from_kelvin` but
    `return Temperature(...)`?](#a-001)
    """

    class Kelvin(Temperature):
        pass

    t = Temperature.from_kelvin(300.0)
    print(f" 1| Temperature.from_kelvin(300)  -> {type(t).__name__=}")

    k = Kelvin.from_kelvin(300.0)
    print(f" 2| Kelvin.from_kelvin type  -> {type(k).__name__=}")

""" ---- Run ---- """

if __name__ == "__main__":
    """ --- `main` ---
    - !![#main-guard]
    - !![#code-style]
    - !![#pythonic-way]
    """
    calling_functions()
    why_cls_is_important()
