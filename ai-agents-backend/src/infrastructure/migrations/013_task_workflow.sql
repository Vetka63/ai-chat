CREATE TABLE IF NOT EXISTS task_workflow (
    task_id TEXT PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    phase TEXT NOT NULL DEFAULT 'planning' CHECK(phase IN ('planning','execution','validation','done')),
    status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','paused')),
    current_step_id TEXT,
    candidate_message_id INTEGER,
    revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS task_artifacts (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK(kind IN ('plan','solution','validation')),
    revision INTEGER NOT NULL,
    content TEXT NOT NULL,
    source_message_id INTEGER,
    created_at TEXT NOT NULL,
    UNIQUE(task_id,kind,revision)
);
CREATE TABLE IF NOT EXISTS task_workflow_events (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    action TEXT NOT NULL,
    from_phase TEXT NOT NULL,
    to_phase TEXT NOT NULL,
    from_status TEXT NOT NULL,
    to_status TEXT NOT NULL,
    revision INTEGER NOT NULL,
    created_at TEXT NOT NULL
);
INSERT OR IGNORE INTO task_workflow(task_id)
    SELECT id FROM tasks WHERE agent_id='algorithm_coach';
