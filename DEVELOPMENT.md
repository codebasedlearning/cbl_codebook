# Development guide — Codebook

Building, testing, signing and publishing the plugin. For what Codebook *does*,
see [README.md](README.md).

## Prerequisites

- **JDK 21** — `jvmToolchain(21)`, and the baseline IDE runs on JBR 21.
  `brew install temurin@21`, or any JDK 21 you already have. Gradle itself needs
  a JDK between 17 and 21; a system JDK 25 breaks it, so set `JAVA_HOME`
  accordingly.
- Nothing else. Everything runs through the committed wrapper, which downloads
  the IntelliJ Platform SDK on first use (~1 GB, cached in `~/.gradle`).

Toolchain: Gradle 9.6.1 · IntelliJ Platform Gradle Plugin 2.18.1 · Kotlin 2.4.0
· baseline IntelliJ IDEA 2026.1.4, `sinceBuild = 261`, no upper bound.

The build-time versions can be bumped freely. The **baseline** is the deliberate
one: a first listing starts at the current release, because a baseline ages by
itself — pinning an old IDE today means supporting a four-year-old one two years
from now. Lower it only if Marketplace download statistics (reported per IDE
build) show a real population on older versions; frozen lab machines are the
realistic case. Always compile against the baseline, so the compiler rather than
a user finds APIs the oldest supported IDE lacks.

## Project layout

One Gradle module beside three standalone sample roots:

```
cbl_codebook/
├── settings.gradle.kts          include("plugin")
├── gradlew, gradle/, gradle.properties
├── plugin/                      the plugin — the only Gradle module
│   ├── build.gradle.kts
│   └── src/{main,test}
├── sample-cpp/                  ← open in CLion   (CMake)
│   ├── snippets/                   one file, one topic, one executable
│   │   └── utils/                  header-only helpers
│   ├── doc/                        glossary.md, answers.md, images
│   └── cbl.properties              the fully documented reference
├── sample-python/               ← open in PyCharm (uv project)
│   ├── snippets/  snippets/utils/  doc/
│   └── cbl.properties
└── sample-kotlin/               ← open in IDEA    (own Gradle build)
    ├── snippets/  snippets/utils/  doc/
    ├── settings.gradle.kts  build.gradle.kts
    └── cbl.properties
```

`sample-kotlin` carries its own `settings.gradle.kts`, so it is a **second,
independent Gradle build** inside the repository — not a subproject of the
plugin build, which only ever includes `plugin`. Opening the repository root in
IDEA may offer to link it; there is no need to accept. Its `.gradle/` and
`build/` are covered by the existing ignore rules.

Every task carries the module path: `./gradlew :plugin:runIde`, `:plugin:test`,
`:plugin:buildPlugin`. Output lands in `plugin/build/`.

The samples are deliberately **not** Gradle subprojects. Each is a standalone
project root, opened in the IDE that speaks its language, and each has its own
nested `.idea/`, which keeps CLion and PyCharm away from the plugin project's
config. All `.idea/` folders are gitignored, so the worst case is a re-import.

Both cover the same topic (static methods) in the same shape — TOC, discussion,
run, and a closing block about the panel itself — so the two panels can be read
side by side. The glossaries are per language and share nothing.

In IDEA, mark `sample-python/.venv/` as excluded so it is not indexed.

## The development cycle

```
edit Kotlin → ./gradlew :plugin:runIde → try it in the sandbox → close → repeat
```

```bash
./gradlew :plugin:runIde        # IDEA sandbox — Java and Kotlin only
./gradlew :plugin:runClion      # C++ sandbox
./gradlew :plugin:runPycharm    # Python sandbox
```

Each launches a fresh IDE with the plugin installed and its own isolated
config (`.intellijPlatform/sandbox/`); your real IDE stays untouched. Open a
sample folder there, and the **Codebook** tool window appears on the right.

The plugin code is identical in all three — only the sandbox IDE changes. The
default `runIde` sandbox is IntelliJ IDEA, which has **no C++ or Python
support**: `.cpp` and `.py` files are plain text there, produce no comment
tokens, and the panel stays empty. Use `runClion` / `runPycharm` for those
(each downloads its IDE on first use); `sample-kotlin` is what `runIde` itself
is good for, since IDEA parses Kotlin natively.

To debug, open the project as a Gradle project and run
`Tasks → intellij platform → runIde` under the debugger. Useful while at it:

- sandbox log — `.intellijPlatform/sandbox/<name>/<IDE>/log/idea.log`, e.g.
  `.intellijPlatform/sandbox/plugin/IU-2026.1.4/log/idea.log`. IPGP 2.x puts the
  sandbox under `sandboxContainer`, which defaults to
  `[rootProject]/.intellijPlatform/sandbox` — not under `build/` as in 1.x.
  Codebook logs its gutter decisions there at INFO under `Codebook:`.
- `Help → Edit Custom Properties…` → `idea.is.internal=true` in the sandbox
  enables `Tools → View PSI Structure`, which shows exactly which `PsiComment`
  elements the parser receives per language.

Running the samples outside the IDE:

```bash
cd sample-python && uv run snippets/staticmethod.py
cd sample-kotlin && gradle run
cd sample-cpp    && cmake -B cmake-build-debug && cmake --build cmake-build-debug \
                 && ./cmake-build-debug/staticmethod
```

`utils/` sits *inside* `snippets/` in both samples, which is what makes this
work: running a script puts the script's own directory on `sys.path` (and the
C++ include path points at `snippets/` for the same reason), so `import utils`
resolves from the command line exactly as it does in the IDE.

`sample-python` is a uv project (`pyproject.toml` with `package = false`,
`.python-version`, committed `uv.lock`) and stdlib-only, so a clone runs without
waiting for a resolver. Delete `.python-version` to use whatever `>=3.10` is
installed instead of letting uv fetch the pinned interpreter.

## Tests

```bash
./gradlew :plugin:test
```

No IDE window opens. 33 tests in two tiers, and the split is the point:

| Suite | Kind | Subject |
|---|---|---|
| `CblRefsTest` (13) | plain JUnit | refs, short forms, embed framing, Markdown → HTML |
| `CblConfigTest` (6) | plain JUnit | the `cbl.properties` cascade, its search bound, pattern validation |
| `CblOutputModelTest` (2) | plain JUnit | output sections, line offsets |
| `CblParserTest` (8) | fixture | the DSL against real PSI comment tokens |
| `CblFoldingTest` (4) | fixture | body-only folding in a real editor |

**Tested:** string-to-string logic where a regression is invisible to the eye —
a regex that starts eating prose, an embed that swallows the blank line after
its frame, a cascade that stops inheriting per key. These need no IDE, so they
cost milliseconds, and 20 of the 31 are of this kind.

**Deliberately not tested:** anything a glance in the sandbox settles faster
than an assertion — panel layout, colours, icons, gutter placement. That is what
`runIde` / `runClion` / `runPycharm` with the samples are for, and it is also
the only way to cover C++ and Python at all: the test platform bundles neither
language, so there is no PSI to parse. Signing, publishing and the zip layout
are not tested either — that is Gradle's and IPGP's code.

Reach for a fixture test only when the platform itself is the thing in question
(does our fold region nest inside the IDE's own? do real comment tokens parse?).
Everything else belongs in the pure tier; if it cannot go there, that is usually
a hint that some logic wants to be separated from its I/O, the way
`CblConfig.mergeLayers` is.

Fixture tests use `myFixture.configureByText` throughout. `addFileToProject`
writes through the real VFS, and on 2026.1 that trips a platform bug in an
unrelated bundled plugin — read the long comment in `plugin/build.gradle.kts`
before reaching for it.

## Build and verify

```bash
./gradlew :plugin:buildPlugin   # → plugin/build/distributions/codebook-<version>.zip
./gradlew :plugin:verifyPlugin  # the same verifier the Marketplace runs on upload
```

Verification must be green before publishing. Strictness is IPGP's default,
including `INTERNAL_API_USAGES` — "the plugin does not violate JetBrains'
internal API usage" is an explicit approval criterion, and the Marketplace's own
run cannot be relaxed. Nothing is suppressed, so a green run means green.

The verdict is expected to be **Compatible** while still *listing* findings:

- 4 deprecated + 6 experimental `ToolWindowFactory` usages — **not ours**.
  Kotlin emits delegating `ACC_BRIDGE` members for the interface's default
  methods and the verifier reads them as our overrides
  ([MP-7604](https://youtrack.jetbrains.com/issue/MP-7604), acknowledged by
  JetBrains, not an approval criterion). Verify rather than believe:
  `javap -p plugin/build/classes/kotlin/main/dev/codebasedlearning/cbl/CblToolWindowFactory.class`
  lists eight bridges the source declares nowhere.
- 1 deprecated `MarkdownParser.buildMarkdownTreeFromString(String)` — real, but
  the replacement overload exists only in the library's master branch, not in
  the copy any IDE bundles.

What must stay **absent** is `INTERNAL_API_USAGES`. Do not add a `failureLevel`
override to make the list shorter. Tests never appear in the report — the
verifier reads the distribution zip, which holds main classes only.

## Sign

Signed plugins install without a warning dialog. Generate a key pair once:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA \
  -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096   # asks for a password
openssl rsa -in private_encrypted.pem -out private.pem       # convert to RSA form
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Keep all three **outside the repository** and pass them through the environment
(`*.pem`, `*.crt`, `*.jks` are gitignored as a second line of defence, not as
the primary one):

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD='…'
./gradlew :plugin:signPlugin
./gradlew :plugin:verifyPluginSignature
git push origin <name> 
```

`signPlugin` runs automatically before `publishPlugin` and is **skipped** when
the variables are absent, so an ordinary `buildPlugin` needs no secrets. In an
IDE run configuration the multi-line PEM values must be base64-encoded on a
single line; the task detects and decodes that.

`verifyPluginSignature` needs one workaround, configured in
`plugin/build.gradle.kts`: on IPGP 2.18.1 the task appends the certificate text
as a stray CLI argument when it is given the chain as *content*, and the signer
answers with exit code **64** — a usage error, not a bad signature. The task is
therefore pointed at `chain.crt` as a file. If it ever fails with 64 again,
check whether that workaround is still needed before suspecting the key.

Signing is not a publishing gate either way: the Marketplace verifies the
signature server-side on upload and then re-signs with the JetBrains CA. The
end-to-end check is installing the signed zip from disk — a valid signature
installs with no warning dialog.

## Release checklist

The version number is written in exactly **one** place: `version` in
`plugin/build.gradle.kts`. `patchPluginXml` fills `<version>` and
`<idea-version>` from it, so plugin.xml declares neither — keep it that way.

The release **note** is written once, in CHANGELOG.md: the Gradle Changelog
Plugin renders the section matching `version` into `<change-notes>` at build
time, so plugin.xml carries none of its own. To see what the Marketplace will
show, read `plugin/build/patchedPluginXml/plugin.xml` after a build.

```bash
./gradlew :plugin:clean
# 1. bump `version` in plugin/build.gradle.kts — semver, the Marketplace enforces it
# 2. ./gradlew :plugin:patchChangelog — promotes [Unreleased] to that version, with today's date
./gradlew :plugin:test
./gradlew :plugin:buildPlugin           # → codebook-x.y.z.zip
./gradlew :plugin:verifyPlugin          # Compatible, and no INTERNAL_API_USAGES
# git commit -am "release x.y.z"
# git tag -a vx.y.z -m "x.y.z" && git push --follow-tags
# after release push
git tag -a 1.0.6 -m "release 1.0.6"  
git push origin 1.0.6
export CERTIFICATE_CHAIN PRIVATE_KEY PRIVATE_KEY_PASSWORD PUBLISH_TOKEN
./gradlew :plugin:publishPlugin         # signs on the way out
```

Finish with a GitHub release on the tag, pasting the same changelog section into
its body.

## Publish

```bash
export PUBLISH_TOKEN='perm:…'      # Marketplace → your profile → tokens
./gradlew :plugin:publishPlugin
```

First upload only: accept the Developer Agreement, create the vendor profile,
declare trader / non-trader status (EEA), select the licence (MIT) and provide
the **source-code link** — required for open-source licences — and supply
screenshots. Review takes 3–4 working days; ask `marketplace@jetbrains.com` if
it stays silent.

Two things are immutable after the first upload: the plugin ID
`dev.codebasedlearning.codebook`, and the fact that people have installed it.
Name rules for the listing: 1–4 words, at most 20 characters, no "IntelliJ" and
no "Plugin".

## Install from disk

1. `Settings → Plugins`
2. gear icon ⚙ → **Install Plugin from Disk…**
3. pick the zip from `plugin/build/distributions/`
4. restart the IDE

The plugin uses no language-specific API, so the same zip installs in CLion,
PyCharm and IntelliJ IDEA alike.

## Known limitations

- The **Run** button executes the *currently selected* run configuration — pick
  the snippet's configuration once, then run from the panel.
- Output linking works for plain JVM and native runs; Logcat (Android device
  runs) is not the Run console.
- **Runs delegated to Gradle or Maven wrap their console.** A plain run
  configuration puts a `ConsoleViewImpl` into the run content; an external
  system puts a `BuildView` around it (the log shows the anonymous subclass
  `ExternalSystemRunnableState$3`). `CblConsole.editor()` therefore unwraps via
  `LangDataKeys.CONSOLE_VIEW` from the view's data snapshot. That indirection is
  deliberate: `BuildView.getConsoleView()` is `@ApiStatus.Internal` and
  `CompositeView.getView()` is internal *and* experimental, so either would put
  `INTERNAL_API_USAGES` back in the verifier report — an explicit approval
  criterion. If output linking ever stops working under Gradle, that data key is
  the first thing to check; the fallback is switching Settings → Build Tools →
  Gradle → *Build and run using* to **IntelliJ IDEA**, which yields a plain
  console again.
- **CLion Nova**: comment PSI generally works, but if folding does not appear,
  test with the classic language engine (`Settings → Advanced Settings → Use the
  classic language engine`) and report it.
- An IDE without support for a language produces no comment tokens, and the
  panel stays empty. That is the fold buttons' diagnostic message ("N foldable
  blocks…") talking when it reports zero.
