import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from rezhim.app import ROOT, create_app
from rezhim.corrections import apply_corrections
from rezhim.migrate import migrate


class CorrectionsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.folder = Path(self.tmp.name)
        migrate(ROOT / 'migration-15803-020.json', ROOT / 'rezhim.html', self.folder)
        self.client = create_app(self.folder).test_client()

    def tearDown(self):
        self.tmp.cleanup()

    def state(self):
        return self.client.get('/api/state').get_json()

    def test_requested_changes_preserve_old_numbers_steady_other_observations_and_audit(self):
        before = self.state()
        result = apply_corrections(self.folder)
        after = self.state()
        oid = result['observation_id']
        self.assertTrue(Path(result['backup']).exists())
        obs_before = next(o for o in before['observation'] if o['id'] == oid)
        obs_after = next(o for o in after['observation'] if o['id'] == oid)
        self.assertEqual(obs_after, {**obs_before, 'a103': 'running', 'ab124': 'running'})
        self.assertIsNone(obs_after['steady'])
        self.assertEqual([o for o in after['observation'] if o['id'] != oid], [o for o in before['observation'] if o['id'] != oid])
        values = [v for v in after['measurement'] if v['observation_id'] == oid]
        expected = {'a171_cap': (29.89, 'confirmed'), 'a124_rpm': (313.9, 'confirmed'),
                    'ab124_rpm': (280.4, 'confirmed'), 'a152_p': (235.3, 'review'),
                    'a171_p': (235.8, 'review'), 'c3_vgnd': (1.438, 'review')}
        for key, wanted in expected.items():
            active = [v for v in values if v['param_key'] == key and v['status'] != 'rejected']
            self.assertEqual([(v['number'], v['status']) for v in active], [wanted])
        for key, old in [('a171_cap', 174.8), ('a124_rpm', 280.4)]:
            self.assertEqual(next(v['status'] for v in values if v['param_key'] == key and v['number'] == old), 'rejected')
        untouched = lambda s: [v for v in s['measurement'] if v['observation_id'] != oid or v['param_key'] not in expected]
        self.assertEqual(untouched(after), untouched(before))
        self.assertEqual(after['norm'], before['norm'])
        self.assertEqual(after['recipe'], before['recipe'])
        detail = next(d for d in after['observation_details'] if d['observation_id'] == oid)
        self.assertNotIn('a152_p', detail['review_without_value'])
        self.assertNotIn('a171_p', detail['review_without_value'])
        with closing(sqlite3.connect(self.folder / 'rezhim.sqlite3')) as conn:
            records = [json.loads(row[0]) for row in conn.execute("SELECT details FROM audit WHERE action='user_correction'")]
            for key in [*expected, 'a103', 'ab124']:
                changes = [r for r in records if r['field'] == key]
                self.assertTrue(changes, key)
                self.assertTrue(all(r['reason'] and 'before' in r and 'after' in r for r in changes))
            self.assertEqual(conn.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_corrections_are_idempotent_and_do_not_create_second_backup(self):
        apply_corrections(self.folder)
        before = self.state()
        backups = list((self.folder / 'backups').iterdir())
        with closing(sqlite3.connect(self.folder / 'rezhim.sqlite3')) as conn:
            count = conn.execute('SELECT COUNT(*) FROM audit').fetchone()[0]
        self.assertTrue(apply_corrections(self.folder)['already_applied'])
        self.assertEqual(self.state(), before)
        self.assertEqual(list((self.folder / 'backups').iterdir()), backups)
        with closing(sqlite3.connect(self.folder / 'rezhim.sqlite3')) as conn:
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM audit').fetchone()[0], count)

    def test_changed_original_aborts_corrections_without_partial_data_changes(self):
        with closing(sqlite3.connect(self.folder / 'rezhim.sqlite3')) as conn:
            with conn:
                conn.execute("UPDATE measurement SET number=999 WHERE param_key='a124_rpm' AND number=280.4")
        before = self.state()
        with self.assertRaisesRegex(ValueError, 'a124_rpm'):
            apply_corrections(self.folder)
        self.assertEqual(self.state(), before)


if __name__ == '__main__':
    unittest.main()
