# Agent Notes

## Parallel Tooling

- Do not run multiple `bash` commands through `multi_tool_use.parallel` in this repository.
- In this environment, parallel `bash` calls can hang or get aborted, especially with Gradle commands.
- Run shell commands sequentially instead, even when they look independent.
- `multi_tool_use.parallel` is safe to prefer for read-only search tools like `glob`, `grep`, and `read`.

## Gradle Validation

- Prefer one Gradle command at a time; the project has several modules (see README.md).
- Unit tests: `./gradlew testDebugUnitTest :core:domain:test` (or `:<module>:testDebugUnitTest` for one module).
- Formatting: `./gradlew spotlessApply` before committing; CI runs `spotlessCheck`.
- Instrumented tests only compile without a device: `./gradlew :app:assembleDebugAndroidTest`.
