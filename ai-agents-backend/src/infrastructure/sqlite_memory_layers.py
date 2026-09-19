"""Транзакционное хранение задач, подтверждённой памяти и предложений Дня 11."""

import json

from agent_core.models import AgentError, ConversationSummary, new_id, now
from capabilities.context_memory.models import ContextSettings
from capabilities.memory_layers.models import (
    MemoryEntry, MemoryProfile, MemoryProposal, MemoryVersions, MemoryWorkspace,
    StoredMessage, TaskMemory,
)
from infrastructure.migrations import migrate_memory_layers


class SqliteMemoryRepository:
    """Разделяет области задачи/профиля и проверяет версии перед каждой записью."""

    def __init__(self, store):
        self.store = store

    async def initialize(self):
        await migrate_memory_layers(self.store)

    async def create(self, agent_id, title, settings, problem):
        """Чат и задача создаются атомарно; профиль Дня 11 выбирается сервером."""
        timestamp, conversation_id = now(), new_id()
        title = title.strip() or 'Новая задача'
        item = ConversationSummary(id=conversation_id, agent_id=agent_id, title=title,
            created_at=timestamp, updated_at=timestamp, context_settings=settings,
            root_conversation_id=conversation_id)
        async with self.store.connection() as db:
            await db.execute("BEGIN IMMEDIATE")
            await db.execute("""INSERT INTO conversations
                (id,agent_id,title,created_at,updated_at,context_settings,root_conversation_id)
                VALUES(?,?,?,?,?,?,?)""", (item.id, agent_id, title, timestamp, timestamp,
                                           settings.model_dump_json(), item.id))
            await db.execute("INSERT INTO tasks(id,conversation_id,agent_id,profile_id,problem) VALUES(?,?,?,?,?)",
                (new_id(), item.id, agent_id, 'local', json.dumps(problem, ensure_ascii=False)))
            await db.commit()
        return item

    async def _task(self, db, agent_id, conversation_id, versions=None):
        row = await (await db.execute("""SELECT t.*,p.memory_revision,p.name AS profile_name,
                c.context_settings FROM tasks t JOIN conversations c ON c.id=t.conversation_id
                JOIN profiles p ON p.id=t.profile_id
                WHERE t.agent_id=? AND c.agent_id=? AND t.conversation_id=?""",
                (agent_id, agent_id, conversation_id))).fetchone()
        if row is None:
            raise AgentError("task_not_found", "Задача не найдена у выбранного агента", 404)
        if versions and (row['revision'] != versions.task_revision or row['memory_revision'] != versions.profile_revision):
            raise AgentError("state_conflict", "Память изменилась. Обновите панель и повторите действие", 409)
        return row

    @staticmethod
    def versions(workspace):
        return MemoryVersions(task_revision=workspace.task.revision, profile_revision=workspace.profile.memory_revision)

    @staticmethod
    def _scope(task, layer):
        # Имя таблицы и SQL-условие выбираются только из серверных констант.
        if layer == 'working':
            return 'task_memory', 'task_id=?', (task['id'],)
        if layer == 'long_term':
            return 'long_term_memory', 'agent_id=? AND profile_id=?', (task['agent_id'], task['profile_id'])
        raise AgentError("invalid_memory_layer", "Неизвестный слой памяти")

    async def _entries(self, db, task, layer):
        table, condition, args = self._scope(task, layer)
        rows = await (await db.execute(f"""SELECT id,key,value,active,revision,source_message_id,
            source_excerpt,author,updated_at FROM {table} WHERE {condition} ORDER BY updated_at,id""", args)).fetchall()
        return [MemoryEntry(layer=layer, **dict(row)) for row in rows]

    async def workspace(self, agent_id, conversation_id):
        """Снимок слоёв согласован в одной read-транзакции."""
        async with self.store.connection() as db:
            await db.execute("BEGIN")
            task = await self._task(db, agent_id, conversation_id)
            keep = ContextSettings.model_validate_json(task['context_settings']).keep_last
            total = await (await db.execute("SELECT count(*) FROM messages WHERE conversation_id=?", (conversation_id,))).fetchone()
            tail = await (await db.execute("""SELECT sequence AS id,role,content,created_at FROM messages
                WHERE conversation_id=? ORDER BY sequence DESC LIMIT ?""", (conversation_id, keep))).fetchall()
            proposals = await (await db.execute("""SELECT id,layer,key,value,reason,source_message_id,
                source_excerpt,status,base_entry_id,base_entry_revision,created_at FROM memory_proposals
                WHERE task_id=? ORDER BY created_at,id""", (task['id'],))).fetchall()
            return MemoryWorkspace(
                task=TaskMemory(id=task['id'], conversation_id=conversation_id, agent_id=agent_id,
                    profile_id=task['profile_id'], problem=json.loads(task['problem']), revision=task['revision']),
                profile=MemoryProfile(id=task['profile_id'], name=task['profile_name'], memory_revision=task['memory_revision']),
                keep_last=keep, history_message_count=total[0],
                short_term=[StoredMessage.model_validate(dict(row)) for row in reversed(tail)],
                working=await self._entries(db, task, 'working'), long_term=await self._entries(db, task, 'long_term'),
                proposals=[MemoryProposal.model_validate(dict(row)) for row in proposals],
            )

    async def _source(self, db, conversation_id, source_id):
        if source_id is None:
            return 'Введено пользователем в панели памяти'
        row = await (await db.execute("SELECT role,content FROM messages WHERE sequence=? AND conversation_id=?",
                                     (source_id, conversation_id))).fetchone()
        if row is None:
            raise AgentError("source_not_found", "Сообщение-источник отсутствует в этом чате", 404)
        return f"{row['role']}: {row['content'][:300]}"

    async def source(self, agent_id, conversation_id, source_id):
        """Извлечение разрешено только из собственного сообщения пользователя."""
        async with self.store.connection() as db:
            await self._task(db, agent_id, conversation_id)
            row = await (await db.execute("SELECT sequence AS id,role,content,created_at FROM messages WHERE sequence=? AND conversation_id=?",
                (source_id, conversation_id))).fetchone()
            if row is None or row['role'] != 'user':
                raise AgentError("invalid_memory_source", "Выберите сообщение пользователя из этой задачи")
            return StoredMessage.model_validate(dict(row))

    async def source_index(self, agent_id, conversation_id, source_id):
        """Возвращает позицию своего сообщения для существующего формата отчёта."""
        async with self.store.connection() as db:
            await self._task(db, agent_id, conversation_id)
            return (await (await db.execute('SELECT count(*) FROM messages WHERE conversation_id=? AND sequence<?',
                (conversation_id, source_id))).fetchone())[0]

    async def _bump(self, db, task, layer):
        if layer == 'working':
            await db.execute("UPDATE tasks SET revision=revision+1 WHERE id=?", (task['id'],))
        else:
            await db.execute("UPDATE profiles SET memory_revision=memory_revision+1 WHERE id=?", (task['profile_id'],))

    async def _write(self, db, task, layer, key, value, active, source_id, excerpt, author):
        table, condition, args = self._scope(task, layer)
        existing = await (await db.execute(f"SELECT id FROM {table} WHERE {condition} AND key=?", (*args, key))).fetchone()
        if existing:
            await db.execute(f"""UPDATE {table} SET value=?,active=?,revision=revision+1,
                source_message_id=?,source_excerpt=?,author=?,updated_at=? WHERE id=?""",
                (value, active, source_id, excerpt, author, now(), existing['id']))
        else:
            scope_columns = 'task_id' if layer == 'working' else 'agent_id,profile_id'
            columns = f'id,{scope_columns},key,value,active,source_message_id,source_excerpt,author,updated_at'
            values = (new_id(), *args, key, value, active, source_id, excerpt, author, now())
            await db.execute(f"INSERT INTO {table}({columns}) VALUES({','.join('?' for _ in values)})", values)
        await self._bump(db, task, layer)

    async def save_entry(self, agent_id, conversation_id, command):
        async with self.store.connection() as db:
            await db.execute("BEGIN IMMEDIATE")
            task = await self._task(db, agent_id, conversation_id, command)
            excerpt = await self._source(db, conversation_id, command.source_message_id)
            source_id, author = command.source_message_id, 'user'
            # Включение/выключение записи не меняет происхождение её содержимого.
            table, condition, args = self._scope(task, command.layer)
            old = await (await db.execute(f"SELECT * FROM {table} WHERE {condition} AND key=?",
                (*args, command.key))).fetchone()
            if old and old['value'] == command.value and 'source_message_id' not in command.model_fields_set:
                source_id, excerpt, author = old['source_message_id'], old['source_excerpt'], old['author']
            await self._write(db, task, command.layer, command.key, command.value, command.active,
                              source_id, excerpt, author)
            await db.commit()

    async def save_problem(self, agent_id, conversation_id, problem, versions):
        async with self.store.connection() as db:
            await db.execute("BEGIN IMMEDIATE")
            task = await self._task(db, agent_id, conversation_id, versions)
            await db.execute("UPDATE tasks SET problem=?,revision=revision+1 WHERE id=?",
                             (json.dumps(problem, ensure_ascii=False), task['id']))
            await db.commit()

    async def delete_entry(self, agent_id, conversation_id, entry_id, versions):
        async with self.store.connection() as db:
            await db.execute("BEGIN IMMEDIATE")
            task = await self._task(db, agent_id, conversation_id, versions)
            for layer in ('working', 'long_term'):
                table, condition, args = self._scope(task, layer)
                cursor = await db.execute(f"DELETE FROM {table} WHERE {condition} AND id=?", (*args, entry_id))
                if cursor.rowcount:
                    await self._bump(db, task, layer)
                    await db.commit()
                    return
            raise AgentError("memory_not_found", "Запись не найдена в этой области памяти", 404)

    async def save_proposals(self, workspace, source_id, candidates):
        async with self.store.connection() as db:
            await db.execute("BEGIN IMMEDIATE")
            task = await self._task(db, workspace.task.agent_id, workspace.task.conversation_id, self.versions(workspace))
            excerpt = await self._source(db, task['conversation_id'], source_id)
            # Повтор анализа сообщения заменяет только прежние неподтверждённые предложения.
            await db.execute("UPDATE memory_proposals SET status='rejected' WHERE task_id=? AND source_message_id=? AND status='pending'",
                             (task['id'], source_id))
            for candidate in candidates:
                table, condition, args = self._scope(task, candidate.layer)
                old = await (await db.execute(f"SELECT id,revision FROM {table} WHERE {condition} AND key=?", (*args, candidate.key))).fetchone()
                await db.execute("""INSERT INTO memory_proposals
                    (id,task_id,layer,key,value,reason,source_message_id,source_excerpt,base_entry_id,base_entry_revision,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)""", (new_id(), task['id'], candidate.layer, candidate.key,
                    candidate.value, candidate.reason, source_id, excerpt, old['id'] if old else None,
                    old['revision'] if old else None, now()))
            await db.commit()

    async def resolve(self, agent_id, conversation_id, proposal_id, action, versions):
        async with self.store.connection() as db:
            await db.execute("BEGIN IMMEDIATE")
            task = await self._task(db, agent_id, conversation_id, versions)
            proposal = await (await db.execute("SELECT * FROM memory_proposals WHERE id=? AND task_id=?",
                                               (proposal_id, task['id']))).fetchone()
            if proposal is None:
                raise AgentError("proposal_not_found", "Предложение не найдено", 404)
            if proposal['status'] != 'pending':
                raise AgentError("proposal_resolved", "Предложение уже обработано", 409)
            if action == 'accept':
                table, condition, args = self._scope(task, proposal['layer'])
                target = await (await db.execute(f"SELECT id,revision FROM {table} WHERE {condition} AND key=?", (*args, proposal['key']))).fetchone()
                actual = (target['id'], target['revision']) if target else (None, None)
                if actual != (proposal['base_entry_id'], proposal['base_entry_revision']):
                    raise AgentError("stale_proposal", "Поле изменено после анализа. Отклоните предложение и выполните анализ заново", 409)
                await self._write(db, task, proposal['layer'], proposal['key'], proposal['value'], True,
                                  proposal['source_message_id'], proposal['source_excerpt'], 'llm_confirmed')
            await db.execute("UPDATE memory_proposals SET status=? WHERE id=?",
                             ('accepted' if action == 'accept' else 'rejected', proposal_id))
            await db.commit()

    async def append_reply(self, workspace, reply):
        """Не сохраняет ответ как актуальный, если память изменилась во время генерации."""
        async with self.store.connection() as db:
            await db.execute("BEGIN IMMEDIATE")
            await self._task(db, workspace.task.agent_id, workspace.task.conversation_id, self.versions(workspace))
            await db.execute("INSERT INTO messages(conversation_id,role,content,created_at) VALUES(?,?,?,?)",
                             (workspace.task.conversation_id, 'assistant', reply, now()))
            await db.execute("UPDATE conversations SET updated_at=? WHERE id=?", (now(), workspace.task.conversation_id))
            await db.commit()
