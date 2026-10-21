"""Build the «Смены марок» page: chronology of grades and comparison of neighbouring modes.

The page is one self-contained HTML file (web/smeny.html with the data embedded), so it
opens offline on a PC or a phone. Values of every status except «rejected» are included;
the page marks unverified ones and can hide them. Neighbouring photos of different grades
are shown as candidates for a grade change, not as confirmed transitions.

The database is opened read-only; nothing in data/ is modified.

    python -m rezhim.smeny_page            -> output/smeny-marok.html
"""

import argparse
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / 'web' / 'smeny.html'
PLACEHOLDER = '/*__SMENY_DATA__*/null'
STATUS_RANK = {'confirmed': 2, 'review': 1}


def build_data(conn):
    conn.row_factory = sqlite3.Row
    grades = [{'id': r['id'], 'name': r['name']} for r in conn.execute('SELECT id, name FROM grade ORDER BY name')]
    params = [{'key': r['key'], 'name': r['name'], 'unit': r['unit'], 'tag': r['tag'], 'group': r['group_name'],
               'role': r['role'], 'precision': r['precision']}
              for r in conn.execute('SELECT * FROM param ORDER BY rowid')]
    observations = [{'id': r['id'], 'grade': r['grade_id'], 'line': r['line'], 'ts': r['timestamp'], 'doc': r['doc_id'], 'steady': r['steady']}
                    for r in conn.execute('SELECT * FROM observation ORDER BY line, timestamp')]
    # One value per observation and parameter: a confirmed variant wins over one under review;
    # rejected variants and missing numbers are left out.
    values = {}
    for r in conn.execute("SELECT * FROM measurement WHERE state = 'number' AND status != 'rejected' ORDER BY id"):
        slot = values.setdefault(str(r['observation_id']), {})
        old = slot.get(r['param_key'])
        if old is None or STATUS_RANK[r['status']] >= STATUS_RANK[old[1]]:
            slot[r['param_key']] = [r['number'], r['status']]
    docs = [{'id': r['id'], 'grade': r['grade_id'], 'version': r['version'], 'from': r['valid_from'], 'to': r['valid_to']}
            for r in conn.execute('SELECT * FROM doc_version ORDER BY grade_id, valid_from')]
    norms = [{'doc': r['doc_id'], 'key': r['param_key'], 'lo': r['lower_value'], 'target': r['target'], 'hi': r['upper_value']}
             for r in conn.execute('SELECT * FROM norm ORDER BY id')]
    return {'generated_at': datetime.now(timezone.utc).isoformat(timespec='seconds'), 'grades': grades, 'params': params,
            'observations': observations, 'values': values, 'docs': docs, 'norms': norms}


def render(conn):
    template = TEMPLATE.read_text(encoding='utf-8')
    if PLACEHOLDER not in template:
        raise RuntimeError(f'{TEMPLATE} has no data placeholder')
    data = json.dumps(build_data(conn), ensure_ascii=False, separators=(',', ':')).replace('</', '<\\/')
    return '<!doctype html>\n<html lang="ru">\n' + template.replace(PLACEHOLDER, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--db', type=Path, default=ROOT / 'data' / 'rezhim.sqlite3')
    parser.add_argument('--out', type=Path, default=ROOT / 'output' / 'smeny-marok.html')
    args = parser.parse_args()
    conn = sqlite3.connect(f'file:{args.db.as_posix()}?mode=ro', uri=True)
    try:
        html = render(conn)
    finally:
        conn.close()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(html, encoding='utf-8')
    print(f'{args.out} · {len(html) // 1024} КБ')


if __name__ == '__main__':
    main()
