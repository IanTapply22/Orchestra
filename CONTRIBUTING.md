# Contributing to Orchestra

Thank you for improving Orchestra. Keep changes focused, testable, and compatible with both Paper/Folia and Velocity where applicable.

## Development setup

1. Install JDK 25. Gradle itself may run on an earlier supported JDK because the build uses a Java toolchain.
2. Clone the repository and run `./gradlew check javadoc jar` (`gradlew.bat` on Windows).
3. Optionally run `./gradlew installGitHooks` to enable the tracked formatting pre-commit hook.

## Expectations

- Add tests for behavior changes and regressions.
- Keep platform APIs outside the engine, domain, and port packages.
- Treat persisted fields, event YAML, action names, HTTP routes, and public Java types as compatibility surfaces.
- Update documentation and the example configuration when behavior changes.
- Do not commit credentials, local server files, build output, or IDE metadata.

Use `./gradlew lintFix` to format files. Pull requests must pass formatting, unit tests, coverage thresholds, Javadocs, wrapper validation, and the three-platform build matrix.
