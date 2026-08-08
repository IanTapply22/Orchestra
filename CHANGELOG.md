# Changelog

All notable changes to Orchestra are documented here. The project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html), and release versions are derived from `vMAJOR.MINOR.PATCH` Git tags.

## [Unreleased]

## [1.1.0] - 2026-08-08

### Added

- Separate public API and core Maven publications.
- API binary/source compatibility verification against an optional published baseline.
- CycloneDX software bill of materials and signed build-provenance attestations.
- Dedicated cross-platform verification and Ubuntu infrastructure-test CI jobs.

### Changed

- Consolidated configuration and secret resolution across Paper and Velocity.
- Moved administration behavior behind the platform-neutral `OrchestraService`.
- Simplified the Paper plugin entry point and removed direct engine access.

## [1.0.0] - 2026-08-08

### Added

- Initial Paper, Folia, and Velocity distribution.
- YAML-defined scheduled events, durable PostgreSQL executions, and Redis coordination.
- Extension API, metrics, HTTP operations, and recovery support.

[Unreleased]: https://github.com/IanTapply22/Orchestra/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/IanTapply22/Orchestra/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/IanTapply22/Orchestra/releases/tag/v1.0.0
