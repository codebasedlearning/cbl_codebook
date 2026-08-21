[© A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen](https://ami.codebasedlearning.dev)

# Codebook Authoring

[TECHNICAL.md](TECHNICAL.md) explains what the syntax means; this document discusses 
how a snippet (`staticmethod.cpp` from the C++ sample) may be built up.
You are, of course, free to develop your own structure.


## Idea Teaching

When discussing an idea or concept in code, you typically have three things 
to switch around:
- structures such as functions, interfaces, or classes that implement these 
  ideas or concepts;
- code that uses or applies these structures;
- output, logs, debug sessions, and other methods through which you observe 
  the effects.

The main thing is to keep your audience engaged and focused, so avoid scrolling 
and hopping between code parts or apps too much.


## Idea Codebook

The codebook helps you to focus, facilitates discussion, and minimizes 
distracting actions.


## Idea of a snippet's anatomy

Give your code a similar structure. In my example, this is a four-part structure, 
with one level-1 block for each part:

```cpp
/* ---- Content ---- */ 
/* ---- Header ---- */ 
/* ---- Discussion ---- */
/* ---- Run ---- */
```

The fixed skeleton lets students open any part of the course and immediately 
know where they are:
- 'Content' answers 'Why am I reading this?';
- Header is honest scaffolding and simply separates includes, imports, etc. 
  from the content to be discussed;
- Discussion is the lesson and contains all the code you want to discuss;
- Run is optional, but is often the table of contents in executable form — the 
  call order in `main` (or the script) is your didactic order.

### The Content block

Its first job is a Teaching Focus: the learning objectives as a short
list, three or four bullets, no more. Each goal may carry its background 
as a fold (`!![#named-constructor]`), so the claim stays one line, and the 
depth is opt-in:

```cpp
/* ---- Content ----
 *
 * Teaching Focus
 * - `static` member functions – a plain function in a class's !![#scope]
 * - What `static` gives up, and what it buys.
 * - Why C++ has no `@classmethod`, and what a !![#named-constructor] replaces.
 *
 * Background
 * - ...
 *
 * See also
 * - !![#code-style]
 */
```

You may optionally add some general `Background` information to the topic at hand 
after `Teaching Focus`, or provide additional references at the end under `See also`.

#### References with intent

Three forms, three intentions — choose by what the reader should do:

| Form | Reader's experience | Use when |
|---|---|---|
| `[#ref]` | jumps away | the target is the next thing to read |
| `![#ref]` | text is simply there | the definition is essential, always |
| `!![#ref]` | headline + ▸, opens on demand | depth for those who ask — the default |

The fold is the default for a reason: teaching text earns trust by being
short, and `!![#ref]` is how it stays short without being shallow. Embeds
are for the one definition the block cannot be read without. Plain links are
rare in snippets — jumping away is expensive mid-lesson.

Discipline: every ref must resolve. A dangling `!![#tpyo]` renders as a
literal `!` and a broken link — in front of an auditorium. The pre-flight
checklist below exists mostly for this line.

Glossary entries are written as standalone documents (their author cannot
know where they will be cited), live per language in `docs/glossary.md`, and
are found via `glossary.path`.

### The Discussion block

Your core message and all the structures you need live here.
Important functions and classes are placed on Level 2. For example, a class with 
its documentation can also serve as a Codebook block. This contains not only what 
the class does, but also its purpose in the code.

```
/* --- `Temperature` - A class with `static` functions. ---
 *
 * `Temperature` carries one value and three callables that differ only in what
 * the compiler hands them as the (hidden) first argument ...
 */
class Temperature { ... }
```

#### Titles are claims

A title is the one line a reader sees in the outline — for many readers 
it is all they read. So make it carry content, not category.

#### Context application

As mentioned previously, we have supporting structures, such as the `Temperature` 
class, and code that uses them. Here, we have a number of functions 
— for example, `calling_functions` — that are called from `main` and 
demonstrate the features we wish to discuss.

These functions exist on the same Level 2 as the class and begin with 
a call to the `print_function_header` function (or the equivalent in the 
shared helpers), which prints the section header that the output parser 
looks for.
This is what causes [output linking](TECHNICAL.md#output-linking) 
to put a gutter icon next to the function for easy navigation.

As with a docstring, this is a good place to explain the main point and 
provide further references.

```cpp
/* --- `calling_functions` ---
 * Note the call syntax via class or object or both. <p>
 * A static member function is reachable through the class and through an
 * object - the object is evaluated and then discarded, because there is no
 * `this` to pass.
 * - !![#background-static-via-object]
 *
 * Rule:
 * - Does the callable need the object's data? -> A member function.
 * ...
 */
void calling_functions() {
    print_function_header...
```

#### Listed and unlisted one-liners, numbered output

Most steps within a demo function, class or other structure are Level 3 one-liners. 
When used with a dot, they do not turn the outline into a scroll of noise, and 
the notes still stack under the caret.

```cpp
/* -- .call via object `t` -- */
cout << " 1| as_fahrenheit()   -> " << t.as_fahrenheit() << endl;
```

The output prefix ` n| ` numbers the console lines in step order. When a
function prints seven lines and the beamer shows all of them at once, ` 1|`
is how you say that line without a laser pointer. 

Also note: text after the frame on the same line is the first body line, so 
a one-liner is a complete block. 

#### A question with an answer

Sometimes, you want to include questions in the code. The foldable feature 
makes this easy without providing the answer.
Use a one-liner whose body folds into `docs/answers.md`:

```cpp
/* -- .Is there a difference in calling `is_plausible`? -- !![#a-002] */
```

The answer is genuinely not in the student's file. The burden is on the
answer's headline — it is visible before the click, so it must not
spoil. Name answers neutrally (`#a-002`).

#### Forward references

A forward reference is marked as `Preview` (=more later), so it reads as 
a promise rather than a gap.

```cpp
/* -- .Preview `cout`: print text and value to the console. -- */
```

Use it when a step needs machinery the course has not covered yet. The
marker tells the student explicitly: not knowing this yet is correct.

### The Run block

If a `main` is present, it contains the didactic order in executable form. 
Thanks to the output gutter icons, there is hardly any need to scroll here.


## Pre-flight checklist

Before a snippet meets an auditorium:

- [ ] It compiles and runs — markers are comments, but the code is the lesson.
- [ ] Every `[#…]`, `![#…]`, `!![#…]` resolves (click each once in the panel).
- [ ] Each demo function's title matches its printed section header — gutter icons appear after a run.
- [ ] Output numbers ` n| ` are gapless and in step order.
- [ ] Fold-all, read the frame lines only: does the file still tell its story as a table of contents?
- [ ] Question headlines do not spoil their answers.
- [ ] Teaching Focus has at most four bullets, and the Discussion actually delivers each one.
- [ ] Tables fit the panel without horizontal scrolling.

The fold-all reading is the cheapest review there is: presentation mode is
one click away, so click it.


## Template

Make yourself a template like `template.cpp` for your own structure.


## License

[MIT](LICENSE)
