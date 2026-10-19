# Agent Notes

## Parallel Tooling

- Do not run multiple `bash` commands through `multi_tool_use.parallel` in this repository.
- In this environment, parallel `bash` calls can hang or get aborted, especially with Gradle commands.
- Run shell commands sequentially instead, even when they look independent.
- `multi_tool_use.parallel` is safe to prefer for read-only search tools like `glob`, `grep`, and `read`.

## Wording

- User-facing text never uses the masculine for the person using the app, in every language. Rephrase instead of adding a "·e" ending: a noun ("Après connexion" rather than "Une fois connecté"), "vous", or an epicene word ("personne", "membre", "proche").
- Agreement with things stays as is ("canyons suivis"). `InclusiveWordingTest` in `core/ui` fails on the forms that used to slip in.

## Gradle Validation

- Prefer one Gradle command at a time; the project has several modules (see README.md).
- Unit tests: `./gradlew testDebugUnitTest :core:domain:test` (or `:<module>:testDebugUnitTest` for one module).
- Formatting: `./gradlew spotlessApply` before committing; CI runs `spotlessCheck`.
- Instrumented tests only compile without a device: `./gradlew :app:assembleDebugAndroidTest`.
