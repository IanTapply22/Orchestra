# Dependency verification policy

Orchestra verifies the SHA-256 checksum of executable dependency artifacts, including runtime JARs, ZIPs, Gradle plugins, and native libraries. Repository metadata (`.pom` and `.module` variants) is intentionally excluded because Gradle metadata verification is disabled and those entries create repository-specific churn without protecting executable build inputs.

IDE-only `-sources.jar` and `-javadoc.jar` artifacts are trusted by a filename classifier rule. They are not placed on a build classpath or executed, and excluding them avoids checksum churn whenever IntelliJ downloads documentation for transitive Gradle plugin dependencies. This is deliberately narrower than trusting arbitrary JARs.

Dependency locking remains enabled for every subproject. CI also validates the Gradle wrapper and reviews dependency changes on pull requests.

When a trusted dependency is added or upgraded, regenerate checksums from the configured repositories:

```shell
./gradlew --write-verification-metadata sha256 help
```

Review the diff and the source of every new executable artifact. Remove generated `.pom`, `.module`, `-sources.jar`, and `-javadoc.jar` artifact blocks before committing so the file continues to match this policy. Never accept a checksum solely because a build requested it.
