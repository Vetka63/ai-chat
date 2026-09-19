"""Хранение профилей отдельно от задач и записей долговременной памяти."""
import json
from agent_core.models import AgentError, new_id, now
from capabilities.personalization.models import UserProfile


class SqliteProfileRepository:
    """Явные настройки профиля с optimistic locking, без автоматической записи LLM."""
    def __init__(self, store):
        self.store = store

    @staticmethod
    def decode(row):
        return UserProfile(**{**dict(row), 'preferences': json.loads(row['preferences'])})

    async def list(self):
        async with self.store.connection() as db:
            rows = await (await db.execute('SELECT * FROM profiles ORDER BY name,id')).fetchall()
            return [self.decode(row) for row in rows]

    async def get(self, profile_id):
        async with self.store.connection() as db:
            row = await (await db.execute('SELECT * FROM profiles WHERE id=?', (profile_id,))).fetchone()
            if row is None:
                raise AgentError('profile_not_found', 'Профиль не найден', 404)
            return self.decode(row)

    async def create(self, command):
        profile = UserProfile(id=new_id(), name=command.name, preferences=command.preferences, updated_at=now())
        async with self.store.connection() as db:
            await db.execute('INSERT INTO profiles(id,name,preferences,updated_at) VALUES(?,?,?,?)',
                (profile.id, profile.name, profile.preferences.model_dump_json(), profile.updated_at))
            await db.commit()
        return profile

    async def update(self, profile_id, command):
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            old = await (await db.execute('SELECT * FROM profiles WHERE id=?', (profile_id,))).fetchone()
            if old is None:
                raise AgentError('profile_not_found', 'Профиль не найден', 404)
            if old['revision'] != command.revision:
                raise AgentError('profile_conflict', 'Профиль изменён в другом окне. Обновите данные и повторите правку', 409)
            await db.execute('UPDATE profiles SET name=?,preferences=?,revision=revision+1,updated_at=? WHERE id=?',
                (command.name, command.preferences.model_dump_json(), now(), profile_id))
            result = self.decode(await (await db.execute('SELECT * FROM profiles WHERE id=?', (profile_id,))).fetchone())
            await db.commit()
            return result
