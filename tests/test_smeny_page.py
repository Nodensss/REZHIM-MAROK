import json
import re
import sqlite3
import unittest
from pathlib import Path

from rezhim.smeny_page import build_data, render

ROOT = Path(__file__).resolve().parent.parent


def synthetic():
    """In-memory base with two grades on one line; not production data."""
    conn = sqlite3.connect(':memory:')
    conn.executescript((ROOT / 'rezhim/schema.sql').read_text(encoding='utf-8'))
    conn.execute("INSERT INTO param(key,name,tag,unit,role,load_dependency,group_name) VALUES ('pal','ПАЛ','T1','кг/ч','measurement','scales','Режим')")
    conn.executemany('INSERT INTO grade(id,name) VALUES (?,?)', [(1, 'ТЕСТ-1'), (2, 'ТЕСТ-2')])
    conn.executemany("INSERT INTO observation(id,grade_id,line,timestamp,a103,ab124,suutp) VALUES (?,?,'A',?,'unknown','unknown','unknown')",
                     [(1, 1, '2026-01-01T10:00:00'), (2, 2, '2026-01-02T10:00:00')])
    conn.executemany("INSERT INTO measurement(observation_id,param_key,number,state,status) VALUES (?,?,?,'number',?)",
                     [(1, 'pal', 5.0, 'review'), (1, 'pal', 5.5, 'confirmed'), (2, 'pal', 7.0, 'rejected'), (2, 'pal', 6.0, 'review')])
    return conn


class SmenyPageTests(unittest.TestCase):
    def test_confirmed_variant_wins_and_rejected_is_dropped(self):
        data = build_data(synthetic())
        self.assertEqual(data['values']['1']['pal'], [5.5, 'confirmed'])
        self.assertEqual(data['values']['2']['pal'], [6.0, 'review'])
        self.assertEqual([o['ts'] for o in data['observations']], ['2026-01-01T10:00:00', '2026-01-02T10:00:00'])

    def test_render_embeds_data_once(self):
        html = render(synthetic())
        self.assertTrue(html.startswith('<!doctype html>'))
        self.assertNotIn('__SMENY_DATA__', html)
        embedded = re.search(r'const D=(\{.*?\});\n', html).group(1)
        self.assertEqual(json.loads(embedded)['grades'][0]['name'], 'ТЕСТ-1')

    def test_desk_route(self):
        import tempfile
        from rezhim.app import create_app
        with tempfile.TemporaryDirectory() as folder:
            response = create_app(folder).test_client().get('/smeny/')
            self.assertEqual(response.status_code, 200)
            self.assertIn('Смены марок', response.get_data(as_text=True))
            response.close()


if __name__ == '__main__':
    unittest.main()
