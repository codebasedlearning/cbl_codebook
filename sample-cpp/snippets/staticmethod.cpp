// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

/* ---- Content ----
 *
 * Teaching Focus
 * - `static` member functions – a plain function in a class's !![#scope]
 * - What `static` gives up, and what it buys.
 * - Why C++ has no `@classmethod`, and what a !![#named-constructor] replaces.
 *
 * Idea Codebook
 * - We structured the code such that the main discussion points are shown
 *   and navigable inside the Codebook-Window.
 * - Answers to questions can be shown or hidden, same for definitions or
 *   concepts such as !![#named-constructor].
 * - Text is rendered, so you can also show some links
 *   [C++ Core Guidelines](https://isocpp.github.io/CppCoreGuidelines/CppCoreGuidelines)
 *   or plain html <p>
 *   <center><img src="../docs/Logo_CBL_2024_72.png" width="50"></center>
 *
 * See also
 * - !![#code-style]
 */

/* ---- Header ---- */

#include <cstdlib>
#include <iostream>

#include "utils/printing.h"
using std::cout, std::endl;

/* ---- Discussion ---- */

/* --- `Temperature` - A class with `static` functions. ---
 *
 * `Temperature` carries one value and three callables that differ only in what
 * the compiler hands them as the (hidden) first argument:
 *
 * | Kind                   | Hidden argument | Typical use                     |
 * |------------------------|-----------------|---------------------------------|
 * | member function        | `this`          | needs the object's data         |
 * | static member function | nothing         | helper that belongs to the type |
 * | [#named-constructor]   | nothing         | build an object, named clearly  |
 *
 * Python has a third kind, `@classmethod`, which receives the class itself.
 * C++ has no equivalent.
 */
class Temperature {
public:
    static constexpr double zero_celsius_in_kelvin = 273.15;

    explicit Temperature(double celsius) : celsius_{celsius} {}

    /* -- `as_fahrenheit` – The member function --
     * It reads `celsius_`, so it needs an object - the hidden `this`. <p>
     * `const` promises not to modify it: the `this` pointer is `const Temperature*` inside.
     */
    [[nodiscard]] double as_fahrenheit() const { return celsius_ * 9.0 / 5.0 + 32.0; }

    /* -- .`from_kelvin` – The static named constructor --
       A static member function that returns an object - what C++ uses where
       Python writes an alternative constructor as a `@classmethod`.

       Unlike Python's `cls`, it has no idea which class the call was written
       on.
     */
    [[nodiscard]] static Temperature from_kelvin(double kelvin) { return Temperature{kelvin - zero_celsius_in_kelvin}; }

    /* -- .`is_plausible` – The static member function --
       No `this`: an ordinary function that happens to live in the class's
       [#scope].

       It validates a value without needing any object at all. Written as a free
       function it would compile to the same thing; written here it stays where
       a reader looks for it.
     */
    [[nodiscard]] static bool is_plausible(double celsius) { return -273.15 <= celsius && celsius <= 5000.0; }

    [[nodiscard]] double celsius() const { return celsius_; }

private:
    double celsius_;
};

/* --- `calling_functions` ---
 * Note the call syntax via class or object or both. <p>
 * A static member function is reachable through the class and through an
 * object - the object is evaluated and then discarded, because there is no
 * `this` to pass.
 * - !![#background-static-via-object]
 *
 * Rule:
 * - Does the callable need the object's data? -> A member function.
 * - Does it need neither the object nor its type? -> `static`.
 * - Does it build an object and deserve a name? -> A named constructor.
 *
 * Code:
 * - `as_fahrenheit` needs `this`
 * - `is_plausible` needs nothing - it only reads its argument
 */
void calling_functions() {
    print_function_header(__func__);

    const Temperature t{20.0};

    // call via object `t`
    cout << " 1| as_fahrenheit()   -> " << t.as_fahrenheit() << endl;

    // call via class `Temperature`
    cout << " 2| from_kelvin(300)  -> " << Temperature::from_kelvin(300.0).celsius() << endl;

    // both calls valid
    cout << " 3| via class         -> " << Temperature::is_plausible(20.0) << endl;
    cout << " 4| via object        -> " << t.is_plausible(20.0) << endl;
}

// internal linkage, see below
static int file_local_calls = 0;

/* --- `three_meanings_of_static` ---
 * We have
 * - internal linkage – not visible to other [#translation-unit]s
 * - function-local – initialized once, on first use, and outlives the call
 * - class member – belongs to the class, not to an object
 */
void three_meanings_of_static() {
    print_function_header(__func__);

    // function-local
    static int local_calls = 0;
    ++local_calls;
    ++file_local_calls;

    cout << " 1| local_calls       -> " << local_calls << endl;
    cout << " 2| file_local_calls  -> " << file_local_calls << endl;

    // class member
    cout << " 3| class constant    -> " << Temperature::zero_celsius_in_kelvin << endl;

    if (local_calls < 2) {
        three_meanings_of_static();         // call again to show the difference
    }
}

/* --- `why_there_is_no_cls` ---
 * Assume we call `from_kelvin` on a derived class. <br>
 * !![What is the type?](#a-001)
 */
void why_there_is_no_cls() {
    print_function_header(__func__);

    /* -- .Inheritance without `cls` --
     * A derived class inherits static member functions, so `Kelvin::is_plausible`
     * compiles - but `from_kelvin` still constructs a `Temperature`, because
     * nothing tells it where the call was written.
     */
    struct Kelvin : Temperature {
        using Temperature::Temperature;
    };

    cout << " 1| Kelvin::is_plausible(-500)  -> " << Kelvin::is_plausible(-500.0) << endl;
    cout << " 2| Kelvin::from_kelvin(300)    -> " << Kelvin::from_kelvin(300.0).celsius() << endl;
}

/* ---- Run ---- */

int main() {
    calling_functions();
    three_meanings_of_static();
    why_there_is_no_cls();

    return EXIT_SUCCESS;
}
