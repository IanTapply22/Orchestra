# Configuration

Paper/Folia creates `plugins/Orchestra/config.yml` on first startup. Event definitions live in the adjacent `events` directory and are validated during startup.

## Server identity

```yaml
server:
  id: survival-1
  groups: [survival]
  tags:
    game: survival
    region: na-east
```

`id` must be unique. Event targets can select all online backends, explicit server IDs, groups, or exact tag values.

## Engine limits

`engine.workers` and `engine.queue-capacity` bound immediately runnable work. Retry delays use asynchronous timers and do not occupy those workers. Poll interval/batch size control durable scans; lease duration controls the renewable execution lease; shutdown time is the graceful worker allowance.

## PostgreSQL

Enable PostgreSQL for restart recovery, optimistic compare-and-set updates, migrations, and durable audit history. Credentials are selected in this order: configured environment variable, secret file, then inline value.

```yaml
postgres:
  enabled: true
  jdbc-url: jdbc:postgresql://database:5432/orchestra
  username: orchestra
  password: ""
  password-environment-variable: ORCHESTRA_POSTGRES_PASSWORD
  password-file: /run/secrets/orchestra_postgres_password
  maximum-pool-size: 8
```

## Redis

Redis provides distributed leases and transient Paper-to-Velocity commands. The URI supports credentials and a database number. URI selection order is environment variable, file, then inline value.

```yaml
redis:
  enabled: true
  uri: redis://default:password@redis:6379/0
  uri-environment-variable: ORCHESTRA_REDIS_URI
  uri-file: /run/secrets/orchestra_redis_uri
  namespace: orchestra
```

## HTTP endpoint

The optional listener provides public health plus authenticated metrics and event triggers. Bind it privately and terminate TLS at a reverse proxy.

```yaml
web:
  enabled: true
  bind: 127.0.0.1
  port: 8787
  token-environment-variable: ORCHESTRA_WEB_TOKEN
  token-file: /run/secrets/orchestra_web_token
  tokens: {}
```

Tokens must contain at least 24 characters. Inline token keys map to `VIEWER`, `OPERATOR`, or `ADMINISTRATOR`; environment/file tokens receive `ADMINISTRATOR`. Routes enforce their documented HTTP methods and return `405` plus `Allow` otherwise.

## Event definitions

```yaml
id: weekend_double_xp
display-name: "Double XP Weekend"
schedule:
  cron: "0 18 * * FRI"
  timezone: America/Toronto
targets:
  groups: [survival]
stages:
  - id: announce
    duration: 5m
    timeout: 10s
    actions:
      - broadcast: "<gold>Double XP begins soon"
  - id: active
    duration: 48h
    conditions:
      - type: online_players_at_least
        count: 1
    actions:
      - type: set_variable
        key: xp_multiplier
        value: 2
      - id: multiplier
        type: command
        execute: "quests multiplier 2"
        retry:
          max-attempts: 3
          initial-delay: 250ms
          multiplier: 2
          max-delay: 5s
```

Cron has five fields: minute, hour, day of month, month, and day of week. Names, ranges, lists, and steps are supported. Invalid expressions fail while definitions load rather than during scheduler ticks.
