# Architecture

Orchestra follows a ports-and-adapters design. Immutable API and domain values describe events and executions. The engine depends on repository, lock, target-resolution, and network contracts; infrastructure and Minecraft platform code provide their implementations.

## Dependency rule

Dependencies flow toward `api`, `domain`, and `port`. The `engine` package may use those packages but must not import Bukkit, Velocity, PostgreSQL, Redis, or HTTP adapter types. Platform packages compose the application and own lifecycle-bound resources.

## Execution guarantees

Executions use optimistic versions and owner-checked renewable leases. An action is still at-least-once: a process can fail after an external side effect and before persisting its completion key. Action implementations must forward `ActionContext.idempotencyKey()` when the target system supports deduplication.

## Decisions

- One distribution JAR currently contains Paper/Folia and Velocity entry points so operators deploy one version across a network.
- Runtime database libraries are resolved by Paper's plugin loader; their versions are generated from the Gradle catalog.
- Redis Pub/Sub is used for transient proxy commands and heartbeats, not durable work.
- PostgreSQL is the durable execution and audit store; in-memory adapters support tests and single-process development.
