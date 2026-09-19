CREATE TABLE IF NOT EXISTS profiles (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, memory_revision INTEGER NOT NULL DEFAULT 1
);
INSERT OR IGNORE INTO profiles(id,name) VALUES('local','Мой учебный профиль');
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL UNIQUE REFERENCES conversations(id) ON DELETE CASCADE,
    agent_id TEXT NOT NULL, profile_id TEXT NOT NULL REFERENCES profiles(id),
    problem TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS task_memory (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    key TEXT NOT NULL, value TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1,
    revision INTEGER NOT NULL DEFAULT 1, source_message_id INTEGER,
    source_excerpt TEXT NOT NULL, author TEXT NOT NULL, updated_at TEXT NOT NULL,
    UNIQUE(task_id,key)
);
CREATE TABLE IF NOT EXISTS long_term_memory (
    id TEXT PRIMARY KEY, agent_id TEXT NOT NULL,
    profile_id TEXT NOT NULL REFERENCES profiles(id),
    key TEXT NOT NULL, value TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1,
    revision INTEGER NOT NULL DEFAULT 1, source_message_id INTEGER,
    source_excerpt TEXT NOT NULL, author TEXT NOT NULL, updated_at TEXT NOT NULL,
    UNIQUE(agent_id,profile_id,key)
);
CREATE TABLE IF NOT EXISTS memory_proposals (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    layer TEXT NOT NULL CHECK(layer IN ('working','long_term')),
    key TEXT NOT NULL, value TEXT NOT NULL, reason TEXT NOT NULL,
    source_message_id INTEGER NOT NULL, source_excerpt TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted','rejected')),
    base_entry_id TEXT, base_entry_revision INTEGER, created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS memory_proposals_task ON memory_proposals(task_id,created_at);
CREATE INDEX IF NOT EXISTS tasks_agent_profile ON tasks(agent_id,profile_id);
