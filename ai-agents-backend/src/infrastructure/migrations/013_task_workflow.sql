CREATE TABLE task_states (
    task_id TEXT PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    payload TEXT NOT NULL DEFAULT '{"phase":"planning","status":"active","current_step_id":null,"expected_action":"save_plan","revision":1}'
);
INSERT INTO task_states(task_id) SELECT id FROM tasks;
CREATE TABLE task_artifacts (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL,
    UNIQUE(task_id,kind,revision)
);
CREATE TABLE task_events (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE, payload TEXT NOT NULL
);
CREATE TABLE task_commands (
    task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    command_id TEXT NOT NULL, fingerprint TEXT NOT NULL, status TEXT NOT NULL,
    result TEXT, run_id TEXT, PRIMARY KEY(task_id,command_id)
);
CREATE UNIQUE INDEX task_one_pending ON task_commands(task_id) WHERE status='pending';
