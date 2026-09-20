CREATE TABLE IF NOT EXISTS task_invariant_sets (
    task_id TEXT PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS task_invariant_rules (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    label TEXT NOT NULL,
    value TEXT NOT NULL,
    active INTEGER NOT NULL CHECK (active IN (0, 1)),
    revision INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS task_invariant_rules_task ON task_invariant_rules(task_id);

CREATE TABLE IF NOT EXISTS task_invariant_checks (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS task_invariant_checks_task ON task_invariant_checks(task_id, created_at);
