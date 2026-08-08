# Security policy

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub's private security-advisory reporting flow for this repository and include affected versions, impact, reproduction steps, and any proposed mitigation.

## Operational security

- Keep PostgreSQL, Redis, webhook, and API credentials out of committed configuration.
- Prefer `ORCHESTRA_POSTGRES_PASSWORD`, `ORCHESTRA_REDIS_URI`, and `ORCHESTRA_WEB_TOKEN` environment variables or the corresponding secret-file settings.
- Bind the HTTP listener to a private interface and terminate TLS at a trusted reverse proxy.
- Restrict Redis and PostgreSQL to trusted networks with authentication enabled.
- Rotate credentials after suspected exposure and review the audit log for operator-triggered executions.

Only supported releases receive security fixes. Operators should test upgrades on a staging network and retain database backups before migrations.
