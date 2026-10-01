"""Lossless schema upgrades for already installed databases."""
import sqlite3
from contextlib import closing
from datetime import datetime


def allow_unknown_steady(conn, folder):
    columns = conn.execute('PRAGMA table_info(observation)').fetchall()
    if not any(r['name']=='steady' and r['notnull'] for r in columns):
        return
    backups = folder / 'backups'
    backups.mkdir(exist_ok=True)
    target = backups / ('before-schema-v2-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.sqlite3')
    with closing(sqlite3.connect(target)) as copy:
        conn.backup(copy)
    # Rebuild only this table, keeping every ID and its existing FK references.
    sql = conn.execute("SELECT sql FROM sqlite_master WHERE name='observation'").fetchone()[0]
    sql = sql.replace('CREATE TABLE observation', 'CREATE TABLE observation_v2', 1)
    sql = sql.replace('steady INTEGER NOT NULL', 'steady INTEGER')
    conn.commit()
    conn.execute('PRAGMA foreign_keys=OFF')
    try:
        conn.execute('BEGIN IMMEDIATE')
        conn.execute(sql)
        conn.execute('INSERT INTO observation_v2 SELECT * FROM observation')
        conn.execute('DROP TABLE observation')
        conn.execute('ALTER TABLE observation_v2 RENAME TO observation')
        if conn.execute('PRAGMA foreign_key_check').fetchall():
            raise ValueError('Нарушены связи после обновления схемы')
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.execute('PRAGMA foreign_keys=ON')
