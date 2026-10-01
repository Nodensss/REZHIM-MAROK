import io
import json
import tempfile
import unittest

from rezhim.app import create_app


HEADERS = {'X-Rezhim-Client': 'desk'}


class AIImportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = create_app(self.tmp.name)
        self.client = self.app.test_client()

    def tearDown(self):
        self.tmp.cleanup()

    def post(self, path, data, code=200):
        response = self.client.post('/api' + path, json=data, headers=HEADERS)
        self.assertEqual(response.status_code, code, response.get_data(as_text=True))
        return response.get_json()

    def payload(self, values=None, **changes):
        measurement = {'ts': '2026-09-21T10:20', 'line': 'А',
                       'values': {'production': '172.03'} if values is None else values,
                       'status': 'confirmed', 'steady': True, 'a103': 'running', 'ab124': 'running'}
        measurement.update(changes)
        return {'grades': [{'id': 'AI-TEST', 'measurements': [measurement]}]}

    def load(self, payload, code=200):
        raw = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        response = self.client.post('/api/ai-imports', data={'file': (io.BytesIO(raw), 'recognition.json')}, headers=HEADERS)
        self.assertEqual(response.status_code, code, response.get_data(as_text=True))
        return response.get_json()

    def state(self):
        return self.client.get('/api/state').get_json()

    def photo(self, batch):
        response = self.client.post('/api/sources', data={'file': (io.BytesIO(b'\x89PNG\r\n\x1a\nTEST fixture'), 'screen.png')}, headers=HEADERS)
        self.assertEqual(response.status_code, 200)
        source = response.get_json()['id']
        self.post(f"/ai-imports/{batch['id']}/observations/{batch['observations'][0]['observation_id']}/photo", {'source_id': source})
        return source

    def decide(self, item, number=None, key=None, status='confirmed', reason='Сверено оператором по фото', code=200):
        return self.post(f"/ai-items/{item['id']}/review", {'number': item['number'] if number is None else number,
                         'param_key': key or item['param_key'], 'status': status, 'reason': reason}, code)

    def test_numeric_strings_and_bad_input_retained_and_all_pending(self):
        raw = {'production': '172.03', 'peak1': '-0,5', 'peak2': '12abc', 'peak3': True, 'temp1': None}
        batch = self.load(self.payload(raw))
        items = {i['key_hint']: i for i in batch['items']}
        self.assertEqual(len(items), len(raw))
        self.assertEqual(items['production']['number'], 172.03)
        self.assertEqual(items['peak1']['number'], -.5)
        for key, value in raw.items():
            self.assertEqual(json.loads(items[key]['raw_value']), value)
            self.assertEqual(items[key]['status'], 'review')
        for key in ('peak2', 'peak3', 'temp1'):
            self.assertIsNone(items[key]['number'])
            self.assertTrue(items[key]['parse_error'])
        state = self.state()
        self.assertTrue(all(v['status'] == 'review' for v in state['measurement']))
        self.assertEqual(self.post('/export', {})['counts']['values'], 0)
        obs = state['observation'][0]
        self.assertEqual((obs['a103'], obs['ab124'], obs['steady']), ('unknown', 'unknown', None))

    def test_unknown_keys_extra_and_raw_bad_value_survive(self):
        extra = [{'tag': 'PT-unknown', 'label': 'Новая температура', 'value': '???', 'unit': '°C'}]
        payload = self.payload({'not_in_dictionary': '13.10'}, extra=extra)
        payload['extra'] = [{'tag': 'X-2', 'label': 'Другой', 'value': '0', 'unit': 'bar'}]
        batch = self.load(payload)
        self.assertEqual(len(batch['items']), 3)
        self.assertTrue(all(i['param_key'] is None for i in batch['items']))
        self.assertEqual(batch['items'][0]['key_hint'], 'not_in_dictionary')
        self.assertEqual(batch['items'][1]['tag'], 'PT-unknown')
        self.assertEqual(batch['items'][1]['label'], 'Новая температура')
        self.assertEqual(json.loads(batch['items'][1]['raw_value']), '???')
        self.assertEqual(batch['items'][2]['number'], 0)
        self.assertEqual(self.state()['measurement'], [])
        again = self.client.get(f"/api/ai-imports/{batch['id']}").get_json()
        self.assertEqual(again, batch)

    def test_photo_and_reason_required_and_no_direct_review_bypass(self):
        batch = self.load(self.payload())
        item = batch['items'][0]
        self.decide(item, code=400)
        self.post(f"/values/{item['value_id']}/review", {'status': 'confirmed'}, 400)
        self.photo(batch)
        self.decide(item, reason=' ', code=400)
        self.decide(item)
        self.assertEqual(self.state()['measurement'][0]['status'], 'confirmed')
        self.decide(item, code=400)
        audit = self.client.get('/api/audit').get_json()
        review = next(a for a in audit if a['action'] == 'ai_review')
        self.assertEqual(review['details']['reason'], 'Сверено оператором по фото')
        self.assertEqual(review['details']['before']['status'], 'review')
        self.assertEqual(review['details']['after']['status'], 'confirmed')

    def test_later_photo_attachment_keeps_original_confirmation_provenance(self):
        batch = self.load(self.payload({'production': '172.03', 'peak1': '210.5'}))
        first_photo = self.photo(batch)
        self.decide(batch['items'][0])
        response = self.client.post('/api/sources', data={'file': (io.BytesIO(b'\x89PNG\r\n\x1a\nSECOND TEST fixture'), 'second.png')}, headers=HEADERS)
        self.assertEqual(response.status_code, 200)
        second_photo = response.get_json()['id']
        self.post(f"/ai-imports/{batch['id']}/observations/{batch['observations'][0]['observation_id']}/photo", {'source_id': second_photo})
        self.decide(batch['items'][1])
        state = self.state()
        photo_sources = lambda item: {s['source_id'] for s in state['value_source'] if s['value_id'] == item['value_id']} & {first_photo, second_photo}
        self.assertEqual(photo_sources(batch['items'][0]), {first_photo})
        self.assertEqual(photo_sources(batch['items'][1]), {second_photo})
        changes = [r for r in self.client.get('/api/audit').get_json() if r['action'] == 'ai_photo']
        self.assertEqual(changes[0]['details']['before'], first_photo)
        self.assertEqual(changes[0]['details']['after'], second_photo)

    def test_root_extra_must_be_an_array(self):
        for extra in ({'tag': 'X', 'value': 3}, 'bad array', 0, None):
            payload = self.payload()
            payload['extra'] = extra
            self.load(payload, 400)
        self.assertEqual(self.state()['observation'], [])

    def test_manual_context_review_records_reason_and_validates_document(self):
        batch = self.load(self.payload())
        source_id = self.photo(batch)
        doc = self.post('/docs', {'grade_id': 1, 'version': 'TEST-54.2', 'valid_from': '2026-01-01',
                                 'valid_to': '2026-12-31', 'source_id': source_id, 'norms': []})['id']
        oid = batch['observations'][0]['observation_id']
        path = f"/ai-imports/{batch['id']}/observations/{oid}/context"
        context = {'a103': 'running', 'ab124': 'stopped', 'steady': True, 'doc_id': doc,
                   'recipe12': 'checked-12', 'recipe3': 'checked-3', 'reason': 'Реквизиты проверены оператором'}
        before_values = self.state()['measurement']
        self.post(path, {**context, 'doc_id': 9999}, 400)
        self.post(path, {**context, 'steady': 'true'}, 400)
        self.post(path, {**context, 'reason': ''}, 400)
        self.post(path, context)
        after = self.state()
        self.assertEqual(after['measurement'], before_values)
        self.assertEqual((after['observation'][0]['doc_id'], after['observation'][0]['recipe12'], after['observation'][0]['steady']), (doc, 'checked-12', 1))
        audits = [a['details'] for a in self.client.get('/api/audit').get_json() if a['action'] == 'ai_context_review']
        self.assertEqual({a['field'] for a in audits}, {'a103', 'ab124', 'steady', 'doc_id', 'recipe12', 'recipe3'})
        self.assertTrue(all(a['reason'] == context['reason'] for a in audits))

    def test_confirm_corrected_number_rejects_original_candidate(self):
        batch = self.load(self.payload())
        self.photo(batch)
        self.decide(batch['items'][0], number='173.03')
        values = self.state()['measurement']
        self.assertEqual({v['number']: v['status'] for v in values}, {172.03: 'rejected', 173.03: 'confirmed'})
        self.assertEqual(self.post('/export', {})['data']['measurement'][0]['number'], 173.03)

    def test_known_key_cannot_remap_leaving_orphan_candidate(self):
        batch = self.load(self.payload({'peak1': '200.5'}))
        self.photo(batch)
        self.decide(batch['items'][0], key='peak2', code=400)
        self.assertEqual(len(self.state()['measurement']), 1)
        self.assertEqual(self.state()['measurement'][0]['status'], 'review')

    def test_reimport_same_source_preserves_review_decisions(self):
        payload = self.payload({'production': '172.03', 'peak1': '210.5'})
        batch = self.load(payload)
        self.photo(batch)
        self.decide(batch['items'][0])
        self.decide(batch['items'][1], status='rejected')
        before = self.state()
        audit_before = self.client.get('/api/audit').get_json()
        again = self.load(payload)
        self.assertTrue(again['already_imported'])
        self.assertEqual(again['id'], batch['id'])
        self.assertEqual([i['status'] for i in again['items']], ['confirmed', 'rejected'])
        self.assertEqual(self.state(), before)
        self.assertEqual(self.client.get('/api/audit').get_json(), audit_before)

    def test_existing_observation_is_extended_metadata_kept_conflicts_reviewed(self):
        self.post('/grades', {'name': 'AI-TEST'})
        response = self.client.post('/api/sources', data={'file': (io.BytesIO(b'%PDF-1.4 test'), 'photo.pdf')}, headers=HEADERS)
        source_id = response.get_json()['id']
        old = {'grade_id': 1, 'line': 'A', 'timestamp': '2026-09-21T10:20',
               'a103': 'stopped', 'ab124': 'running', 'steady': False, 'suutp': 'off',
               'recipe12': 'original-12', 'recipe3': 'original-3', 'source_id': source_id,
               'pumps': {'1': 2, '2': 4, '3': 1}, 'values': [{'param_key': 'production', 'number': 170, 'status': 'confirmed'}]}
        self.post('/observations', old)
        before = self.state()
        batch = self.load(self.payload({'production': '172.03', 'peak1': '200.5'}, recipe12='AI invented', pumps={'1': 1}))
        after = self.state()
        self.assertEqual(after['observation'], before['observation'])
        self.assertEqual(after['pump_map'], before['pump_map'])
        self.assertEqual(len(after['measurement']), 3)
        self.assertTrue(all(v['status'] == 'review' for v in after['measurement']))
        self.assertEqual({v['number'] for v in after['measurement']}, {170, 172.03, 200.5})
        self.assertTrue(any('Расхождение' in w for w in json.loads(batch['observations'][0]['warnings_json'])))
        self.assertTrue(any(a['action'] == 'ai_value' and a['details']['before'] and a['details']['before']['number'] == 170 for a in self.client.get('/api/audit').get_json()))

    def test_extra_maps_to_existing_or_new_dictionary_param(self):
        param = next(p for p in self.state()['param'] if p['key'] == 'production')
        extra = [{'tag': 'existing', 'label': 'Производительность', 'value': '172.03', 'unit': param['unit']},
                 {'tag': 'NEW', 'label': 'Параметр с фото', 'value': '30,6', 'unit': 'bar'}]
        batch = self.load(self.payload({}, extra=extra))
        self.photo(batch)
        self.decide(batch['items'][0], key='production')
        self.decide(batch['items'][1], key='test_new', code=400)
        self.post('/params', {'key': 'test_new', 'name': 'Параметр с фото', 'tag': 'NEW', 'unit': 'bar',
                             'precision': 2, 'role': 'measurement', 'load_dependency': 'base', 'group_name': 'Тест'})
        self.decide(batch['items'][1], key='test_new')
        self.assertEqual({v['param_key']: v['number'] for v in self.post('/export', {})['data']['measurement']}, {'production': 172.03, 'test_new': 30.6})

    def test_extra_unit_mismatch_cannot_silently_convert(self):
        batch = self.load(self.payload({}, extra=[{'tag': 'X', 'label': 'Pressure', 'value': '3.5', 'unit': 'definitely-not-temperature'}]))
        self.photo(batch)
        self.decide(batch['items'][0], key='peak1', code=400)
        self.assertEqual(self.state()['measurement'], [])

    def test_invalid_later_measurement_rolls_back_entire_import(self):
        payload = self.payload()
        payload['grades'][0]['measurements'].append({'ts': 'not a date', 'line': 'A', 'values': {'peak1': 2}})
        self.load(payload, 400)
        self.assertEqual(self.state()['observation'], [])
        self.assertEqual(self.state()['measurement'], [])
        self.assertEqual(self.client.get('/api/ai-imports').get_json(), [])


if __name__ == '__main__':
    unittest.main()
