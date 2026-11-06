import hashlib
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from rezhim.android_snapshot import build_snapshot, classify_time, write_bundle
from rezhim.app import ROOT

DATA = ROOT / 'data'


@unittest.skipUnless((DATA / 'rezhim.sqlite3').is_file(), 'нет рабочей базы')
class WorkingDatabaseSnapshotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        db = DATA / 'rezhim.sqlite3'
        before = hashlib.sha256(db.read_bytes()).hexdigest()
        cls.snapshot = build_snapshot(db, DATA / 'sources')
        cls.unchanged = before == hashlib.sha256(db.read_bytes()).hexdigest()
        with closing(sqlite3.connect(f'file:{db}?mode=ro', uri=True)) as conn:
            cls.db_counts = {
                'grades': conn.execute('SELECT COUNT(*) FROM grade').fetchone()[0],
                'observations': conn.execute('SELECT COUNT(*) FROM observation').fetchone()[0],
                'values': conn.execute('SELECT COUNT(*) FROM measurement').fetchone()[0],
                'photos': conn.execute("SELECT COUNT(*) FROM source WHERE kind = 'photo'").fetchone()[0],
            }
            cls.statuses = dict(conn.execute('SELECT status, COUNT(*) FROM measurement GROUP BY status').fetchall())

    def test_database_is_read_only(self):
        self.assertTrue(self.unchanged)

    def test_every_record_and_status_is_carried(self):
        counts = self.snapshot['counts']
        for key, expected in self.db_counts.items():
            self.assertEqual(counts[key], expected, key)
        self.assertEqual(counts['statuses'], {k: self.statuses.get(k, 0) for k in ('confirmed', 'review', 'rejected')})
        self.assertEqual(len(self.snapshot['values']), self.db_counts['values'])
        self.assertEqual(len(self.snapshot['observations']), self.db_counts['observations'])

    def test_references_are_whole(self):
        grades = {g['id'] for g in self.snapshot['grades']}
        observations = {o['id'] for o in self.snapshot['observations']}
        sources = {s['id'] for s in self.snapshot['sources']}
        params = {p['key'] for p in self.snapshot['params']}
        for obs in self.snapshot['observations']:
            self.assertIn(obs['grade'], grades)
            self.assertTrue(set(obs['sources']) <= sources)
        for value in self.snapshot['values']:
            self.assertIn(value['obs'], observations)
            self.assertIn(value['param'], params)
            self.assertTrue(set(value['sources']) <= sources)
            self.assertTrue(set(value['photos']) <= sources)

    def test_marked_transitions_carry_their_observations(self):
        observations = {o['id']: o for o in self.snapshot['observations']}
        with closing(sqlite3.connect(f'file:{DATA / "rezhim.sqlite3"}?mode=ro', uri=True)) as conn:
            self.assertEqual(len(self.snapshot['transitions']), conn.execute('SELECT COUNT(*) FROM transition').fetchone()[0])
        for t in self.snapshot['transitions']:
            for key, grade, check in (('before', t['from'], lambda o: o['time'] < t['start']),
                                      ('after', t['to'], lambda o: t['end'] is None or o['time'] >= t['end'])):
                for oid in t[key]:
                    self.assertEqual(observations[oid]['grade'], grade, oid)
                    self.assertEqual(observations[oid]['line'], t['line'], oid)
                    self.assertTrue(check(observations[oid]), oid)
            for oid in t['during']:
                self.assertIs(observations[oid]['steady'], False, oid)
            self.assertEqual(t['status'] == 'confirmed', bool(t['start'] and t['end'] and t['before'] and t['after']))

    def test_time_origin_is_never_invented(self):
        origins = {o['id']: o['time_origin'] for o in self.snapshot['observations']}
        self.assertTrue(set(origins.values()) <= {'screen', 'exif', 'manual', 'unknown'})
        self.assertEqual(origins['o52'], 'exif')
        self.assertEqual(origins['o46'], 'screen')
        self.assertEqual(origins['o3'], 'manual')

    def test_control_snapshot_values_and_units(self):
        values = {(v['obs'], v['param']): v for v in self.snapshot['values'] if v['status'] != 'rejected'}
        self.assertEqual(values[('o3', 'pal')]['number'], 6.6)
        self.assertEqual(values[('o3', 'c3_flow')]['unit'], 'м³/ч')
        self.assertEqual(values[('o3', 'z3_f')]['number'], 17.28)
        self.assertEqual({v['unit'] for v in self.snapshot['values'] if v['param'] == 'propylene'}, {''})

    def test_bundle_copies_sources_with_matching_sha(self):
        with tempfile.TemporaryDirectory() as folder:
            output = write_bundle(self.snapshot, DATA / 'sources', folder)
            written = json.loads(output.read_text(encoding='utf-8'))
            self.assertEqual(written['counts'], self.snapshot['counts'])
            for item in self.snapshot['sources']:
                path = Path(folder) / item['asset']
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), item['sha256'])


class TimeOriginTests(unittest.TestCase):
    def test_screen_note_wins_over_exif_match(self):
        origin, _ = classify_time(99, '2026-09-10T20:12:12', ['Время с экрана и совпадает с EXIF.'], False,
                                  {'2026-09-10T20:12:12': 'IMG.jpg'})
        self.assertEqual(origin, 'screen')

    def test_exif_match_without_note(self):
        origin, note = classify_time(99, '2025-09-15T20:41:17', ['Панель А-329.'], False, {'2025-09-15T20:41:17': 'IMG_20250915_204117.jpg'})
        self.assertEqual(origin, 'exif')
        self.assertIn('IMG_20250915_204117.jpg', note)

    def test_unknown_stays_unknown(self):
        self.assertEqual(classify_time(99, '2026-07-05T19:08:07', ['Общий экран.'], False, {})[0], 'unknown')


if __name__ == '__main__':
    unittest.main()
