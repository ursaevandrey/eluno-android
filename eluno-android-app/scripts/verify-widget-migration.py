#!/usr/bin/env python3
"""Check that the widget appearance migration preserves existing bindings."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schemas = root / 'core/database/schemas/ru.eluno.core.database.ElunoDatabase'
old = json.loads((schemas / '5.json').read_text())['database']['entities']
new = json.loads((schemas / '6.json').read_text())['database']['entities']
source = (root / 'core/database/src/main/kotlin/ru/eluno/core/database/Database.kt').read_text()
body = re.search(r'Migration\(5, 6\) \{(.*?)\n        \}', source, re.S).group(1)
statements = re.findall(r'connection.execSQL\("([^"]+)"\)', body)
assert len(statements) == 2
with sqlite3.connect(':memory:') as db:
    for entity in old:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    db.executemany('INSERT INTO widget_bindings (appWidgetId, deviceId) VALUES (?, ?)', [(11, 'device-a'), (12, 'device-b')])
    for sql in statements:
        db.execute(sql)
    assert db.execute('SELECT * FROM widget_bindings ORDER BY appWidgetId').fetchall() == [(11, 'device-a', 1, 100), (12, 'device-b', 1, 100)]
    for entity in new:
        actual = db.execute(f"PRAGMA table_info('{entity['tableName']}')").fetchall()
        fields = entity['fields']
        assert [(r[1], r[2], r[3], r[4]) for r in actual] == [(f['columnName'], f['affinity'], int(f.get('notNull', False)), f.get('defaultValue')) for f in fields], entity['tableName']
        assert [r[1] for r in actual if r[5]] == entity['primaryKey']['columnNames']
print('Миграция 5→6: схема и сохранение привязок проверены.')

# Existing opaque and transparent widgets must keep their own appearance when
# the color mode is added; older installations can also migrate from v5.
schema7 = json.loads((schemas / '7.json').read_text())['database']['entities']
body67 = re.search(r'Migration\(6, 7\) \{(.*?)\n        \}', source, re.S).group(1)
statements67 = re.findall(r'connection.execSQL\("([^"]+)"\)', body67)
assert len(statements67) == 1
for start in (5, 6):
    with sqlite3.connect(':memory:') as db:
        for entity in (old if start == 5 else new):
            db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        if start == 5:
            db.executemany('INSERT INTO widget_bindings (appWidgetId, deviceId) VALUES (?, ?)', [(11, 'device-a'), (12, 'device-b')])
            for sql in statements: db.execute(sql)
        else:
            db.executemany('INSERT INTO widget_bindings VALUES (?, ?, ?, ?)', [(11, 'device-a', 0, 20), (12, 'device-b', 1, 60)])
        for sql in statements67: db.execute(sql)
        expected = [(11, 'device-a', 1, 100, 'System'), (12, 'device-b', 1, 100, 'System')] if start == 5 else [(11, 'device-a', 0, 20, 'System'), (12, 'device-b', 1, 60, 'System')]
        assert db.execute('SELECT * FROM widget_bindings ORDER BY appWidgetId').fetchall() == expected
        for entity in schema7:
            actual = db.execute(f"PRAGMA table_info('{entity['tableName']}')").fetchall()
            fields = entity['fields']
            assert [(r[1], r[2], r[3], r[4]) for r in actual] == [(f['columnName'], f['affinity'], int(f.get('notNull', False)), f.get('defaultValue')) for f in fields], entity['tableName']
            assert [r[1] for r in actual if r[5]] == entity['primaryKey']['columnNames']
print('Миграции 5→7 и 6→7: схема и настройки виджетов сохранены.')
