#!/usr/bin/env python3
"""Check migrations to v4: remove old demo data and attempt journal, preserve real devices."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schema_dir = root / 'core/database/schemas/ru.eluno.core.database.ElunoDatabase'
schemas = {version: json.loads((schema_dir / f'{version}.json').read_text())['database'] for version in (1, 2, 3, 4)}
source = (root / 'core/database/src/main/kotlin/ru/eluno/core/database/Database.kt').read_text()
migrations = {
    (int(start), int(end)): re.findall(r'connection.execSQL\("([^"]+)"\)', body)
    for start, end, body in re.findall(r'Migration\((\d+), (\d+)\) \{(.*?)\n        \}', source, re.S)
}
assert set(migrations) == {(1, 2), (2, 3), (3, 4)}


def validate_schema(connection):
    for entity in schemas[4]['entities']:
        actual = connection.execute(f"PRAGMA table_info('{entity['tableName']}')").fetchall()
        expected = [(field['columnName'], field['affinity'], int(field.get('notNull', False))) for field in entity['fields']]
        assert [(row[1], row[2], row[3]) for row in actual] == expected, entity['tableName']
        assert [row[1] for row in actual if row[5]] == entity['primaryKey']['columnNames']


for version in (1, 2, 3):
    for scenario in ('empty', 'legacy_only', 'mixed'):
        with sqlite3.connect(':memory:') as connection:
            for entity in schemas[version]['entities']:
                connection.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            if version >= 2:
                for index, status in enumerate(('Pairing', 'NeedsInspection', 'Ready')):
                    connection.execute('INSERT INTO commissioning VALUES (?,?,?)', (f'old-attempt-{index}', 'Прежняя попытка', status))
            expected = {}
            if scenario == 'mixed':
                # A user-defined name containing 'демо' is not a marker for synthetic data.
                expected['real-1'] = ('Моя демо-комната', 'ffffffff00000001')
                expected['real-2'] = ('Лампа', '0000000000000002')
                for device_id, (name, node_id) in expected.items():
                    values = (device_id, name, node_id)
                    sql = "INSERT INTO devices VALUES (?,NULL,?,'Light',?,1,'onoff')" if version < 3 else "INSERT INTO devices VALUES (?,?,'Light',?,1,'onoff')"
                    connection.execute(sql, values)
            related_ids = list(expected)
            if scenario != 'empty' and version < 3:
                for device_id, template_id in (('demo-outlet', 'outlet'), ('demo-legacy', None), ('legacy-template', 'lamp')):
                    connection.execute("INSERT INTO devices VALUES (?,?,'Тестовое устройство','Light','0000000000000001',1,'onoff')", (device_id, template_id))
                    related_ids.append(device_id)
                related_ids.append('demo-orphan')
            for index, device_id in enumerate(related_ids):
                connection.execute("INSERT INTO command_records VALUES (?,?,'power','true','Running',1,NULL)", (f'command-{device_id}', device_id))
                connection.execute("INSERT INTO snapshots VALUES (?,1,NULL,NULL,NULL,'Fresh','Reachable',1,?,'power','true',2,NULL,NULL)", (device_id, f'command-{device_id}'))
                connection.execute('INSERT INTO widget_bindings VALUES (?,?)', (index, device_id))
            for step in range(version, 4):
                for statement in migrations[(step, step + 1)]:
                    connection.execute(statement)
            validate_schema(connection)
            assert {row[0]: row[1:] for row in connection.execute('SELECT id,name,nodeIdHex FROM devices')} == expected
            for table in ('snapshots', 'command_records', 'widget_bindings'):
                assert {row[0] for row in connection.execute(f'SELECT deviceId FROM {table}')} == set(expected), table
            assert all(row[0] == 'Running' for row in connection.execute('SELECT status FROM command_records'))
            assert not connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='commissioning'").fetchall()
            print(f'{version}→4 {scenario}: OK')
print('Все 9 сценариев прошли: журнал попыток и демозаписи удалены, реальные данные сохранены, Room schema v4 совпадает.')
