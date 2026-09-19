"""Последовательные добавочные миграции поверх существующей схемы Дня 10."""
from pathlib import Path


async def migrate_memory_layers(store):
    """Выполняет миграции один раз в транзакции без удаления старых таблиц."""
    async with store.connection() as db:
        await db.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version TEXT PRIMARY KEY)")
        await db.commit()
        for path in sorted(Path(__file__).with_suffix("").glob("*.sql")):
            await db.execute("BEGIN IMMEDIATE")
            exists = await (await db.execute("SELECT 1 FROM schema_migrations WHERE version=?", (path.stem,))).fetchone()
            if not exists:
                # Скрипты содержат простые DDL/DML. executescript сделал бы неявный COMMIT.
                for statement in path.read_text(encoding="utf-8").split(";"):
                    if statement.strip():
                        await db.execute(statement)
                await db.execute("INSERT INTO schema_migrations VALUES(?)", (path.stem,))
            await db.commit()
