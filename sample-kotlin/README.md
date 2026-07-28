# Kotlin samples — Codebook

A standalone Gradle build. Open this folder in IntelliJ IDEA (it is a project
root of its own) or run the snippet from the IDE or the command line:

```bash
gradle run          # or ./gradlew run once you have generated a wrapper
```

There is no wrapper in this folder on purpose — the repository already carries
one for the plugin build, and a second wrapper for a three-file sample is more
ceremony than it is worth. `gradle wrapper` adds one if you want it.

## What the plugin reads here

- `cbl.properties` — `glossary.path`
- `doc/glossary.md` — Kotlin glossary; headings are the referenceable blocks
- `doc/answers.md` — answers and background for the question blocks
