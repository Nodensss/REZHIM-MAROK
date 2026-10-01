import json
import sqlite3
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

from rezhim.app import ROOT, create_app
from rezhim.migrate import migrate, prototype_seed


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.folder=Path(self.tmp.name)
        self.report=migrate(ROOT/'migration-15803-020.json',ROOT/'rezhim.html',self.folder)
        self.app=create_app(self.folder)
        self.client=self.app.test_client()

    def tearDown(self):
        self.tmp.cleanup()

    def state(self):
        return self.client.get('/api/state').get_json()

    def test_all_source_numbers_norms_and_recipe_preserved(self):
        raw=json.loads((ROOT/'migration-15803-020.json').read_text(encoding='utf-8'),parse_float=Decimal,parse_int=Decimal)
        state=self.state()
        self.assertEqual(self.report['values'],113)
        self.assertEqual(self.report['review'],12)
        self.assertEqual(self.report['confirmed'],101)
        self.assertEqual(len(state['observation']),3)
        self.assertEqual(len(state['norm']),28)
        self.assertEqual(len(state['recipe']),11)
        g=raw['grades'][0]
        for m in g['measurements']:
            obs=next(o for o in state['observation'] if o['timestamp']==m['ts']+':00')
            values={v['param_key']:Decimal(str(v['number'])) for v in state['measurement'] if v['observation_id']==obs['id']}
            self.assertEqual(values,m['values'])
        for n in state['norm']:
            for source,key in [('lo','lower_value'),('tg','target'),('hi','upper_value')]:
                self.assertEqual(None if n[key] is None else Decimal(str(n[key])),g['norms'][n['param_key']].get(source))
        self.assertEqual(state['doc_details'][0]['spec'],g['spec'])
        self.assertEqual(state['doc_details'][0]['recipe'],g['recipe'])
        rows=[row for block in g['recipe'] for row in block['rows']]
        for saved,original in zip(state['recipe'],rows):
            self.assertEqual(saved['component'],original[0])
            for key,value in zip(['percent','mass','count'],original[1:]):
                self.assertEqual(None if saved[key] is None else Decimal(str(saved[key])),None if value=='—' else Decimal(value.replace(',','.')))

    def test_unknown_context_not_invented_and_missing_review_not_fabricated(self):
        state=self.state()
        self.assertTrue(all(o['steady'] is None and o['a103']=='unknown' and o['ab124']=='unknown' for o in state['observation']))
        self.assertEqual(sum(len(d['review_without_value']) for d in state['observation_details']),3)
        self.assertTrue(all(v['state']=='number' for v in state['measurement']))
        self.assertTrue(all(len([s for s in state['value_source'] if s['value_id']==v['id']])==2 for v in state['measurement']))

    def test_context_changes_only_three_fields_and_survives_reimport(self):
        before=self.state()
        payload={'a103':'running','ab124':'stopped','steady':True}
        response=self.client.post('/api/observations/1/context',json=payload,headers={'X-Rezhim-Client':'desk'})
        self.assertEqual(response.status_code,200)
        again=migrate(ROOT/'migration-15803-020.json',ROOT/'rezhim.html',self.folder)
        self.assertTrue(again['already_imported'])
        after=self.state()
        self.assertEqual(before['measurement'],after['measurement'])
        self.assertEqual(before['norm'],after['norm'])
        self.assertEqual(before['recipe'],after['recipe'])
        self.assertEqual(after['observation'][0]['steady'],1)
        self.assertEqual(after['observation'][1]['steady'],None)
        for body in [{**payload,'timestamp':'2020-01-01'}, {**payload,'steady':'false'}, {**payload,'a103':'maybe'}]:
            self.assertEqual(self.client.post('/api/observations/1/context',json=body,headers={'X-Rezhim-Client':'desk'}).status_code,400)
        self.assertEqual(self.client.post('/api/observations/1/context',json={**payload,'steady':None},headers={'X-Rezhim-Client':'desk'}).status_code,200)

    def test_export_keeps_unknown_context_and_excludes_review(self):
        response=self.client.post('/api/export',json={},headers={'X-Rezhim-Client':'desk'})
        package=response.get_json()
        self.assertEqual(package['schema_version'],2)
        self.assertEqual(package['counts']['values'],101)
        self.assertEqual(len(package['data']['observation']),3)
        self.assertTrue(all(o['steady'] is None for o in package['data']['observation']))

    def test_prototype_parser_does_not_execute_javascript(self):
        with self.assertRaises(ValueError):
            prototype_seed('var SEED = {grades: evil()}; var LS_KEY = "x";')


class UpgradeTests(unittest.TestCase):
    def test_v1_upgrade_preserves_ids_and_links(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'rezhim.sqlite3'
            with sqlite3.connect(path) as conn:
                sql=(ROOT/'rezhim/schema.sql').read_text(encoding='utf-8').replace('steady INTEGER CHECK','steady INTEGER NOT NULL CHECK')
                conn.executescript(sql)
                conn.execute("INSERT INTO grade(id,name) VALUES (7,'test')")
                conn.execute("INSERT INTO observation(id,grade_id,line,timestamp,a103,ab124,suutp,steady) VALUES (9,7,'A','2026-01-01T12:00:00','unknown','unknown','unknown',0)")
                conn.execute('INSERT INTO pump_map VALUES (9,1,2)')
            conn.close()
            create_app(temp)
            with sqlite3.connect(path) as conn:
                self.assertEqual(conn.execute('SELECT id,grade_id,steady FROM observation').fetchall(),[(9,7,0)])
                self.assertEqual(conn.execute('SELECT * FROM pump_map').fetchall(),[(9,1,2)])
                self.assertEqual(conn.execute('PRAGMA foreign_key_check').fetchall(),[])
                conn.execute('UPDATE observation SET steady=NULL WHERE id=9')
            conn.close()
            self.assertTrue(list((Path(temp)/'backups').glob('before-schema-v2-*.sqlite3')))


if __name__=='__main__':unittest.main()
