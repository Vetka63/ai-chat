ALTER TABLE task_artifacts ADD COLUMN based_on_artifact_id TEXT REFERENCES task_artifacts(id);
ALTER TABLE task_artifacts ADD COLUMN task_revision INTEGER;
ALTER TABLE task_artifacts ADD COLUMN invariant_revision INTEGER;

CREATE TABLE IF NOT EXISTS task_lifecycle_control (
    task_id TEXT PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    approved_plan_id TEXT REFERENCES task_artifacts(id),
    current_solution_id TEXT REFERENCES task_artifacts(id),
    current_validation_id TEXT REFERENCES task_artifacts(id),
    approved_task_revision INTEGER,
    approved_invariant_revision INTEGER,
    validation_solution_id TEXT REFERENCES task_artifacts(id),
    change_request TEXT,
    updated_at TEXT NOT NULL
);

INSERT OR IGNORE INTO task_lifecycle_control(task_id,updated_at)
    SELECT id,datetime('now') FROM tasks WHERE agent_id='algorithm_coach';

UPDATE task_artifacts SET task_revision=(SELECT revision FROM tasks WHERE tasks.id=task_artifacts.task_id)
    WHERE task_revision IS NULL;
UPDATE task_artifacts SET invariant_revision=COALESCE(
    (SELECT revision FROM task_invariant_sets WHERE task_invariant_sets.task_id=task_artifacts.task_id),1)
    WHERE invariant_revision IS NULL;

UPDATE task_lifecycle_control SET approved_plan_id=(
    SELECT id FROM task_artifacts WHERE task_id=task_lifecycle_control.task_id AND kind='plan'
    ORDER BY revision DESC LIMIT 1)
    WHERE EXISTS(SELECT 1 FROM task_workflow WHERE task_id=task_lifecycle_control.task_id AND phase!='planning');
UPDATE task_lifecycle_control SET current_solution_id=(
    SELECT id FROM task_artifacts WHERE task_id=task_lifecycle_control.task_id AND kind='solution'
    ORDER BY revision DESC LIMIT 1)
    WHERE EXISTS(SELECT 1 FROM task_workflow WHERE task_id=task_lifecycle_control.task_id AND phase IN ('validation','done'));
UPDATE task_lifecycle_control SET current_validation_id=(
    SELECT id FROM task_artifacts WHERE task_id=task_lifecycle_control.task_id AND kind='validation'
    ORDER BY revision DESC LIMIT 1)
    WHERE EXISTS(SELECT 1 FROM task_workflow WHERE task_id=task_lifecycle_control.task_id AND phase='done');
UPDATE task_lifecycle_control SET
    approved_task_revision=(SELECT task_revision FROM task_artifacts WHERE id=approved_plan_id),
    approved_invariant_revision=(SELECT invariant_revision FROM task_artifacts WHERE id=approved_plan_id),
    validation_solution_id=CASE WHEN current_validation_id IS NOT NULL THEN current_solution_id ELSE NULL END;

UPDATE task_artifacts SET based_on_artifact_id=(
    SELECT id FROM task_artifacts parent WHERE parent.task_id=task_artifacts.task_id AND parent.kind='plan'
      AND parent.created_at<=task_artifacts.created_at ORDER BY parent.revision DESC LIMIT 1)
    WHERE kind='solution' AND based_on_artifact_id IS NULL;
UPDATE task_artifacts SET based_on_artifact_id=(
    SELECT id FROM task_artifacts parent WHERE parent.task_id=task_artifacts.task_id AND parent.kind='solution'
      AND parent.created_at<=task_artifacts.created_at ORDER BY parent.revision DESC LIMIT 1)
    WHERE kind='validation' AND based_on_artifact_id IS NULL;
