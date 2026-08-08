CREATE TABLE IF NOT EXISTS orchestra_schema_history (
    version integer PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS event_executions (
    id uuid PRIMARY KEY,
    definition_id varchar(64) NOT NULL,
    status varchar(24) NOT NULL,
    stage_index integer NOT NULL,
    version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    due_at timestamptz,
    stage_started_at timestamptz,
    variables bytea NOT NULL,
    completed_actions bytea NOT NULL,
    failure text
);

CREATE INDEX IF NOT EXISTS event_executions_due_idx
    ON event_executions (due_at)
    WHERE status IN ('SCHEDULED', 'STARTING', 'RUNNING');

CREATE TABLE IF NOT EXISTS audit_log (
    sequence bigserial PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor varchar(128) NOT NULL,
    action varchar(96) NOT NULL,
    resource varchar(256) NOT NULL,
    detail text NOT NULL,
    remote_address varchar(64)
);
