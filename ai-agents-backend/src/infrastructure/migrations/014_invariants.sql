ALTER TABLE tasks ADD COLUMN invariant_revision INTEGER NOT NULL DEFAULT 1;
CREATE TABLE task_invariants (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    payload TEXT NOT NULL
);
CREATE TABLE invariant_checks (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX invariant_checks_task ON invariant_checks(task_id,created_at);
