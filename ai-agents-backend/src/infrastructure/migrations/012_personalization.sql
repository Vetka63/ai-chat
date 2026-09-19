ALTER TABLE profiles ADD COLUMN preferences TEXT NOT NULL DEFAULT '{}';
ALTER TABLE profiles ADD COLUMN revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE profiles ADD COLUMN updated_at TEXT NOT NULL DEFAULT '';
INSERT INTO profiles(id,name,preferences,updated_at) VALUES(
    'beginner','Новичок',
    '{"explanation_language":"ru","experience_level":"beginner","detail_level":"detailed","response_format":"steps","preferred_code_language":"python","soft_constraints":["Объясняй новые термины простыми словами","Приводи небольшой пример перед кодом"]}',
    strftime('%Y-%m-%dT%H:%M:%fZ','now')
);
INSERT INTO profiles(id,name,preferences,updated_at) VALUES(
    'experienced','Опытный разработчик',
    '{"explanation_language":"ru","experience_level":"advanced","detail_level":"concise","response_format":"bullets","preferred_code_language":"python","soft_constraints":["Не объясняй базовый синтаксис","Указывай временную и пространственную сложность"]}',
    strftime('%Y-%m-%dT%H:%M:%fZ','now')
);
