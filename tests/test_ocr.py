import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from rezhim.app import create_app
from rezhim.ocr_engine import UnavailableEngine
from rezhim.ocr_pilot import score_manifest, score_pairs


class OcrPilotTests(unittest.TestCase):
    def test_exact_match_and_false_binding_are_separate(self):
        result = score_pairs({'peak2': '308.0', 'pal': '6.7'}, {'peak2': '208.5', 'wrong_key': '6.7'})
        self.assertEqual(result['expected_fields'], 2)
        self.assertEqual(result['exact_fields'], 0)
        self.assertEqual(result['false_bindings'], 2)

    def test_default_requires_95_percent_and_no_false_bindings(self):
        report = score_manifest({'screens': [{'expected': {'a': '1'}, 'recognized': {'a': '1'}}, {'expected': {'b': '2'}, 'recognized': {'b': '2'}}]})
        self.assertTrue(report['totals']['eligible_for_default'])
        report = score_manifest({'screens': [{'expected': {'a': '1'}, 'recognized': {'a': '1', 'wrong': '3'}}]})
        self.assertFalse(report['totals']['eligible_for_default'])

    def test_unavailable_engine_never_returns_values(self):
        result = UnavailableEngine('нет движка').read(__file__)
        self.assertEqual(result.tokens, [])
        self.assertEqual(result.warning, 'нет движка')

    def test_status_endpoint_is_read_only_and_explicit(self):
        with TemporaryDirectory() as directory:
            response = create_app(Path(directory)).test_client().get('/api/ocr/status')
        self.assertEqual(response.status_code, 200)
        payload = response.get_json()
        self.assertTrue(payload['offline'])
        self.assertFalse(payload['writes_to_database'])
        self.assertIn('selected', payload)
