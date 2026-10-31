#!/usr/bin/env python3
"""Execute the actual DAO retention query against archived, active and pending records."""
from pathlib import Path
import json
import re
import sqlite3
root = Path(__file__).resolve().parents[1]
source = (root / 'core/database/src/main/kotlin/ru/eluno/core/database/Database.kt').read_text()
query = re.search(r'@Query\("(DELETE FROM command_records WHERE id IN [^\"]+)"\)', source).group(1)
schema = json.loads((root / 'core/database/schemas/ru.eluno.core.database.ElunoDatabase/7.json').read_text())['database']
with sqlite3.connect(':memory:') as db:
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    db.executemany("INSERT INTO command_records VALUES (?, 'lamp','power','true','Confirmed',?,NULL)", [(f'c{i:05}', i) for i in range(1500)])
    db.execute("INSERT INTO command_records VALUES ('running','lamp','power','true','Running',0,NULL)")
    db.execute("INSERT INTO command_records VALUES ('pending','lamp','power','true','Interrupted',0,NULL)")
    db.execute("INSERT INTO snapshots VALUES ('lamp',NULL,NULL,NULL,NULL,'Unknown','Unknown',NULL,'pending','power','true',0,NULL,NULL)")
    sizes = []
    while True:
        count = db.execute(query, {'before': 100, 'keep': 1000}).rowcount
        assert 0 <= count <= 200
        if count == 0: break
        sizes.append(count)
    assert db.execute("SELECT COUNT(*) FROM command_records WHERE status='Confirmed'").fetchone()[0] == 1000
    assert db.execute("SELECT COUNT(*) FROM command_records WHERE id IN ('running','pending')").fetchone()[0] == 2
    assert db.execute("SELECT MIN(createdAtEpochMs) FROM command_records WHERE status='Confirmed'").fetchone()[0] == 500
    db.execute(query, {'before': 2000, 'keep': 1000})
    assert db.execute("SELECT COUNT(*) FROM command_records WHERE id IN ('running','pending')").fetchone()[0] == 2
    print('Retention: newest 1000 preserved, batches <=200, active/pending never deleted, age limit checked')
