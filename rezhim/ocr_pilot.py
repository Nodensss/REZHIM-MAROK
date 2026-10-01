"""Score OCR candidates against reviewed truth without changing the working database."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def score_pairs(expected: dict[str, str], recognized: dict[str, str]) -> dict:
    total = len(expected)
    exact = sum(recognized.get(key) == value for key, value in expected.items())
    false_bindings = sum(key not in expected or expected[key] != value for key, value in recognized.items())
    return {'expected_fields': total, 'exact_fields': exact, 'field_accuracy': exact / total if total else None,
            'recognized_fields': len(recognized), 'false_bindings': false_bindings,
            'false_binding_rate': false_bindings / len(recognized) if recognized else 0}


def score_manifest(manifest: dict) -> dict:
    screens = []
    for screen in manifest.get('screens', []):
        if not isinstance(screen.get('expected'), dict) or not isinstance(screen.get('recognized'), dict):
            raise ValueError('Каждый экран должен содержать expected и recognized как объекты ключ: значение')
        screens.append({'file': screen.get('file', ''), 'screen_type': screen.get('screen_type', 'unknown'), **score_pairs(screen['expected'], screen['recognized'])})
    totals = {key: sum(s[key] for s in screens) for key in ('expected_fields', 'exact_fields', 'recognized_fields', 'false_bindings')}
    totals['field_accuracy'] = totals['exact_fields'] / totals['expected_fields'] if totals['expected_fields'] else None
    totals['false_binding_rate'] = totals['false_bindings'] / totals['recognized_fields'] if totals['recognized_fields'] else 0
    totals['eligible_for_default'] = totals['field_accuracy'] is not None and totals['field_accuracy'] >= .95 and totals['false_bindings'] == 0
    return {'screens': screens, 'totals': totals}


def main():
    parser = argparse.ArgumentParser(description='Метрики пилота распознавания Режимов марок УПЭ')
    parser.add_argument('manifest', type=Path, help='JSON с expected и recognized для размеченных экранов')
    parser.add_argument('--output', type=Path, default=Path('output/ocr-pilot-report.json'))
    args = parser.parse_args()
    report = score_manifest(json.loads(args.manifest.read_text(encoding='utf-8')))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report['totals'], ensure_ascii=False))


if __name__ == '__main__':
    main()
