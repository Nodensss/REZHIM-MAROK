"""Build the factory snapshot bundled into the Android APK.

The snapshot carries every grade, observation and value of the working database
(all statuses), tech cards, recipes, history and a manifest of source files.
The source files themselves are copied next to it so the phone opens them offline
and can verify each one against its SHA-256.

The database is opened read-only; nothing in data/ is modified.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android' / 'app' / 'src' / 'main' / 'assets'
FORMAT = 'rezhim-android-snapshot'
SCHEMA_VERSION = 1

VALUE_TYPES = {
    'measurement': 'fact', 'setpoint': 'setpoint', 'controller_output': 'output',
    'suutp': 'calc', 'lab': 'lab',
}
MEDIA = {
    '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png',
    '.json': 'application/json', '.html': 'text/html', '.pdf': 'application/pdf',
}
# Observations whose time origin is documented outside the per-observation notes.
TIME_OVERRIDES = {
    6: ('screen', 'Время с экрана RulerControl, см. recognition-pending-grade-report.md; снимки сделаны в 08:43:26–08:44:27.'),
    7: ('screen', 'Время с экрана, см. recognition-pending-grade-report.md.'),
    4: ('manual', 'Замер создан вручную на ПК; время 00:00 может означать, что известна только дата.'),
}
SCREEN_WORDS = ('с экрана', 'меток списка', 'rulercontrol')


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def photo_exif(path):
    """Return (DateTimeOriginal as ISO text or None, EXIF orientation 1-8)."""
    try:
        with Image.open(path) as image:
            exif = image.getexif()
            raw = exif.get_ifd(0x8769).get(36867) or exif.get(306)
            orientation = exif.get(274) or 1
    except OSError:
        return None, 1
    stamp = None
    if isinstance(raw, str) and re.fullmatch(r'\d{4}:\d\d:\d\d \d\d:\d\d:\d\d', raw.strip()):
        stamp = raw.strip().replace(':', '-', 2).replace(' ', 'T')
    return stamp, orientation if orientation in range(1, 9) else 1


def classify_time(obs_id, timestamp, notes, migrated, exif_times):
    """Decide where an observation time came from: screen, exif, manual or unknown."""
    if obs_id in TIME_OVERRIDES:
        return TIME_OVERRIDES[obs_id]
    text = ' '.join(notes).lower()
    for note in notes:
        lowered = note.lower()
        if any(word in lowered for word in SCREEN_WORDS):
            return 'screen', note
    for note in notes:
        if 'exif' in note.lower():
            return 'exif', note
    if migrated:
        return 'manual', 'Время из записи прототипа rezhim.html (ручной ввод).'
    if timestamp[:19] in exif_times:
        return 'exif', 'Время замера совпадает с EXIF фотографии ' + exif_times[timestamp[:19]] + '.'
    if 'время' in text:
        return 'unknown', ' '.join(notes)
    return 'unknown', ''


def classify_grade(obs_id, notes, migrated, reassigned, from_recognition):
    if obs_id in reassigned:
        return 'reassigned', reassigned[obs_id]
    joined = ' '.join(notes)
    if 'Grade assigned by user' in joined or 'Пользователь указал' in joined or 'Папка источника' in joined:
        return 'user', 'Марку указал пользователь (папка или подпись при передаче фото).'
    if migrated:
        return 'prototype', 'Перенос из прототипа rezhim.html.'
    if from_recognition:
        return 'recognition', 'Марка взята из распознанного JSON; отдельного подтверждения нет.'
    return 'manual', 'Введено вручную на ПК.'


def history_text(action, details, param_names, grade_names, source_names):
    def value_text(item):
        if not isinstance(item, dict):
            return '—'
        number = item.get('number')
        shown = item.get('state') if number is None else str(number).replace('.', ',')
        return f"{shown} ({item.get('status', '?')})"

    reason = details.get('reason')
    tail = f' Причина: {reason}' if reason else ''
    if action == 'ai_value':
        after = details.get('after') or {}
        name = param_names.get(after.get('param_key'), after.get('param_key'))
        return f'Импорт распознавания: {name} = {value_text(after)}.{tail}'
    if action == 'user_correction':
        name = param_names.get(details.get('field'), details.get('field'))
        return f"Исправление «{name}»: {value_text(details.get('before'))} → {value_text(details.get('after'))}.{tail}"
    if action == 'ai_context_review':
        return f"Реквизит {details.get('field')}: {details.get('before')} → {details.get('after')}.{tail}"
    if action == 'reassign_grade':
        before = grade_names.get(details.get('before_grade_id'), details.get('before_grade_id'))
        after = grade_names.get(details.get('after_grade_id'), details.get('after_grade_id'))
        return f'Марка замера: {before} → {after}.{tail}'
    if action == 'ai_photo':
        return f"Выбран исходник для сверки: {source_names.get(details.get('after'), details.get('after'))}.{tail}"
    if action == 'ai_import':
        return f"Импорт распознавания: {details.get('items')} полей.{tail}"
    if action == 'ai_grade':
        return f"Марка {grade_names.get(details.get('grade_id'), details.get('grade_id'))} создана.{tail}"
    if action == 'observation':
        return f"Создан замер по источнику {source_names.get(details.get('source'), details.get('source'))}."
    if action == 'doc':
        return f"Добавлена версия техкарты №{details.get('id')}."
    if action in ('export', 'export_full'):
        return f"Выгрузка пакета версии {details.get('version')}."
    if action == 'legacy_migration':
        return 'Миграция данных 15803-020 из прототипа rezhim.html.'
    return action


def build_snapshot(db_path, sources_dir):
    connection = sqlite3.connect(f'file:{db_path}?mode=ro', uri=True)
    connection.row_factory = sqlite3.Row
    rows = lambda sql, *args: [dict(row) for row in connection.execute(sql, args)]
    try:
        grades = rows('SELECT * FROM grade ORDER BY id')
        params = rows('SELECT * FROM param ORDER BY group_name, key')
        sources = rows('SELECT * FROM source ORDER BY id')
        docs = rows('SELECT * FROM doc_version ORDER BY id')
        doc_details = {row['doc_id']: json.loads(row['payload']) for row in rows('SELECT * FROM doc_details')}
        norms = rows('SELECT * FROM norm ORDER BY doc_id, id')
        recipes = rows('SELECT * FROM recipe ORDER BY doc_id, id')
        observations = rows('SELECT * FROM observation ORDER BY id')
        obs_details = {row['observation_id']: json.loads(row['payload']) for row in rows('SELECT * FROM observation_details')}
        measurements = rows('SELECT * FROM measurement ORDER BY id')
        links = rows('SELECT * FROM value_source ORDER BY value_id, source_id')
        pumps = rows('SELECT * FROM pump_map ORDER BY observation_id, zone')
        ai_obs = rows('SELECT * FROM ai_observation ORDER BY batch_id, observation_id')
        ai_items = rows('SELECT * FROM ai_item ORDER BY id')
        audit = rows('SELECT * FROM audit ORDER BY id')
        metadata = {row['key']: row['value'] for row in rows('SELECT * FROM metadata')}
    finally:
        connection.close()

    param_by_key = {p['key']: p for p in params}
    grade_names = {g['id']: g['name'] for g in grades}
    source_by_id = {s['id']: s for s in sources}
    source_names = {s['id']: s['name'] for s in sources}
    param_names = {p['key']: p['name'] for p in params}

    source_items = []
    exif_by_source = {}
    for source in sources:
        path = Path(sources_dir) / Path(source['path']).name
        if not path.is_file():
            raise FileNotFoundError(f'Нет файла источника {source["id"]}: {path}')
        actual = sha256_file(path)
        if actual != source['sha256']:
            raise ValueError(f'SHA-256 источника {source["id"]} не совпадает с базой')
        extension = path.suffix.lower()
        exif_time, orientation = photo_exif(path) if extension in ('.jpg', '.jpeg', '.png') else (None, 1)
        exif_by_source[source['id']] = exif_time
        source_items.append({
            'id': f"s{source['id']}", 'name': source['name'], 'sha256': source['sha256'],
            'size': path.stat().st_size, 'kind': source['kind'], 'screen': source['screen'],
            'created_at': source['created_at'], 'asset': f"sources/{source['sha256']}{extension}",
            'media': MEDIA.get(extension, 'application/octet-stream'),
            'exif_time': exif_time, 'orientation': orientation,
        })

    notes_by_obs = defaultdict(list)
    screens_by_obs = defaultdict(list)
    phase_by_obs = {}
    photos_by_batch_obs = {}
    for item in ai_obs:
        meta = json.loads(item['metadata_json'])
        if meta.get('note') and meta['note'] not in notes_by_obs[item['observation_id']]:
            notes_by_obs[item['observation_id']].append(meta['note'])
        if meta.get('screen') and meta['screen'] not in screens_by_obs[item['observation_id']]:
            screens_by_obs[item['observation_id']].append(meta['screen'])
        if meta.get('phase'):
            phase_by_obs[item['observation_id']] = meta['phase']
        photos_by_batch_obs[(item['batch_id'], item['observation_id'])] = item['photo_source_id']

    recognized = {item['observation_id'] for item in ai_obs}
    reassigned = {}
    history = []
    value_history = defaultdict(list)
    for entry in audit:
        details = json.loads(entry['details'])
        obs_id = details.get('observation_id')
        value_ids = {side.get('id') for side in (details.get('before'), details.get('after')) if isinstance(side, dict)}
        value_ids.discard(None)
        if entry['action'] == 'reassign_grade':
            reassigned[obs_id] = history_text(entry['action'], details, param_names, grade_names, source_names)
        record = {
            'id': f"a{entry['id']}", 'at': entry['created_at'], 'action': entry['action'],
            'obs': f'o{obs_id}' if isinstance(obs_id, int) else None,
            'values': sorted(f'v{v}' for v in value_ids if isinstance(v, int)),
            'text': history_text(entry['action'], details, param_names, grade_names, source_names),
            'details': details,
        }
        history.append(record)
        for value_id in value_ids:
            value_history[value_id].append(record['id'])

    sources_by_value = defaultdict(list)
    for link in links:
        sources_by_value[link['value_id']].append(link['source_id'])
    ai_by_value = {item['value_id']: item for item in ai_items if item['value_id'] is not None}
    obs_sources = defaultdict(set)
    photos_by_value = {}
    for value in measurements:
        linked = sources_by_value[value['id']]
        photos = [s for s in linked if source_by_id[s]['kind'] == 'photo']
        item = ai_by_value.get(value['id'])
        if item:
            photo = photos_by_batch_obs.get((item['batch_id'], item['observation_id']))
            if photo and photo not in photos:
                photos.append(photo)
        photos_by_value[value['id']] = photos
        obs_sources[value['observation_id']].update(linked + photos)
    for item in ai_items:
        photo = photos_by_batch_obs.get((item['batch_id'], item['observation_id']))
        if photo:
            obs_sources[item['observation_id']].add(photo)
    for (batch, obs_id), photo in photos_by_batch_obs.items():
        if photo:
            obs_sources[obs_id].add(photo)
    for entry in history:
        if entry['action'] == 'observation':
            obs_sources[entry['details']['id']].add(entry['details']['source'])

    pumps_by_obs = defaultdict(list)
    for pump in pumps:
        pumps_by_obs[pump['observation_id']].append({'zone': pump['zone'], 'pump': pump['pump']})

    observation_items = []
    for obs in observations:
        oid = obs['id']
        details = obs_details.get(oid, {})
        migrated = bool(details.get('migration_sha256'))
        notes = list(notes_by_obs[oid])
        exif_times = {exif_by_source[s]: source_names[s] for s in obs_sources[oid] if exif_by_source.get(s)}
        time_origin, time_note = classify_time(oid, obs['timestamp'], notes, migrated, exif_times)
        grade_origin, grade_note = classify_grade(oid, notes, migrated, reassigned, oid in recognized)
        if details.get('source_description'):
            notes.insert(0, 'Источник: ' + details['source_description'])
        if details.get('pumps_text'):
            notes.append('Насосы: ' + details['pumps_text'])
        for key in details.get('review_without_value') or []:
            notes.append(f'На сверке без числа: {param_names.get(key, key)}')
        observation_items.append({
            'id': f'o{oid}', 'grade': f"g{obs['grade_id']}", 'line': obs['line'], 'time': obs['timestamp'],
            'time_origin': time_origin, 'time_note': time_note,
            'grade_origin': grade_origin, 'grade_note': grade_note,
            'a103': obs['a103'], 'ab124': obs['ab124'], 'suutp': obs['suutp'],
            'steady': None if obs['steady'] is None else bool(obs['steady']),
            'doc': f"d{obs['doc_id']}" if obs['doc_id'] else None,
            'recipe12': obs['recipe12'], 'recipe3': obs['recipe3'],
            'pumps': pumps_by_obs[oid], 'screens': screens_by_obs[oid], 'phase': phase_by_obs.get(oid),
            'notes': notes, 'sources': [f's{s}' for s in sorted(obs_sources[oid])],
        })

    value_items = []
    for value in measurements:
        param = param_by_key[value['param_key']]
        linked = sources_by_value[value['id']]
        kinds = {source_by_id[s]['kind'] for s in linked}
        if 'ai_json' in kinds:
            method = 'recognition'
        elif 'correction' in kinds:
            method = 'correction'
        else:
            method = 'manual'
        item = ai_by_value.get(value['id'])
        unit_note = ''
        if item and item['unit'] and item['unit'] != param['unit']:
            unit_note = f"В распознавании указана единица «{item['unit']}», в словаре — «{param['unit'] or 'не задана'}»."
        value_items.append({
            'id': f"v{value['id']}", 'obs': f"o{value['observation_id']}", 'param': value['param_key'],
            'number': value['number'], 'state': value['state'], 'status': value['status'],
            'unit': param['unit'], 'unit_note': unit_note, 'type': VALUE_TYPES[param['role']], 'method': method,
            'sources': [f's{s}' for s in linked], 'photos': [f's{s}' for s in photos_by_value[value['id']]],
            'raw': item['raw_value'] if item else None, 'label': item['label'] if item else None,
            'history': value_history.get(value['id'], []),
        })

    extra_items = []
    for item in ai_items:
        if item['kind'] != 'extra':
            continue
        photo = photos_by_batch_obs.get((item['batch_id'], item['observation_id']))
        extra_items.append({
            'id': f"x{item['id']}", 'obs': f"o{item['observation_id']}", 'label': item['label'],
            'tag': item['tag'], 'unit': item['unit'], 'raw': item['raw_value'], 'number': item['number'],
            'parse_error': item['parse_error'], 'status': item['status'],
            'photos': [f's{photo}'] if photo else [],
        })

    photo_count = sum(1 for s in source_items if s['media'].startswith('image/'))
    counts = {
        'grades': len(grades), 'observations': len(observations), 'values': len(measurements),
        'extras': len(extra_items), 'docs': len(docs), 'norms': len(norms), 'recipes': len(recipes),
        'sources': len(source_items), 'photos': photo_count, 'history': len(history),
        'statuses': {s: sum(1 for v in measurements if v['status'] == s) for s in ('confirmed', 'review', 'rejected')},
    }
    revision = max((entry['id'] for entry in audit), default=0)
    return {
        'format': FORMAT, 'schema_version': SCHEMA_VERSION, 'revision': revision,
        'built_at': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'database_sha256': sha256_file(db_path), 'pc_package_version': metadata.get('package_version'),
        'counts': counts,
        'grades': [{'id': f"g{g['id']}", 'name': g['name'], 'aliases': g['aliases']} for g in grades],
        'params': [{
            'key': p['key'], 'name': p['name'], 'tag': p['tag'], 'unit': p['unit'], 'precision': p['precision'],
            'role': p['role'], 'type': VALUE_TYPES[p['role']], 'group': p['group_name'],
        } for p in params],
        'docs': [{
            'id': f"d{d['id']}", 'grade': f"g{d['grade_id']}", 'version': d['version'],
            'valid_from': d['valid_from'], 'valid_to': d['valid_to'], 'source': f"s{d['source_id']}",
            'notes': d['notes'], 'details': doc_details.get(d['id']),
        } for d in docs],
        'norms': [{
            'doc': f"d{n['doc_id']}", 'param': n['param_key'], 'lower': n['lower_value'], 'target': n['target'],
            'upper': n['upper_value'], 'section': n['section'], 'row': n['row_number'],
        } for n in norms],
        'recipes': [{
            'doc': f"d{r['doc_id']}", 'zones': r['zones'], 'version': r['version'], 'component': r['component'],
            'percent': r['percent'], 'mass': r['mass'], 'count': r['count'],
        } for r in recipes],
        'sources': source_items,
        'observations': observation_items,
        'values': value_items,
        'extras': extra_items,
        'history': history,
    }


def write_bundle(snapshot, sources_dir, assets_dir):
    """Write snapshot.json and copy source files into the Android assets folder."""
    assets_dir = Path(assets_dir)
    target = assets_dir / 'sources'
    target.mkdir(parents=True, exist_ok=True)
    wanted = {Path(item['asset']).name for item in snapshot['sources']}
    for stale in target.iterdir():
        if stale.name not in wanted:
            stale.unlink()
    for item in snapshot['sources']:
        destination = assets_dir / item['asset']
        if destination.is_file() and destination.stat().st_size == item['size']:
            continue
        shutil.copyfile(Path(sources_dir) / Path(item['asset']).name, destination)
    base = assets_dir / 'base'
    base.mkdir(parents=True, exist_ok=True)
    output = base / 'snapshot.json'
    temporary = output.with_suffix('.json.tmp')
    with temporary.open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(snapshot, stream, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
    os.replace(temporary, output)
    return output


def _android_form(value):
    """Numbers as Android org.json writes them: integral doubles without a fraction."""
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, dict):
        return {key: _android_form(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_android_form(item) for item in value]
    return value


def verify_export(path):
    """Checks a phone export: format, data checksum and per-source SHA-256 list."""
    document = json.loads(Path(path).read_text(encoding='utf-8'))
    if document.get('format') != 'rezhim-android-export':
        raise ValueError('Это не выгрузка приложения «Режимы марок»')
    text = json.dumps(_android_form(document['data']), ensure_ascii=False, separators=(',', ':')).replace('/', '\\/')
    data_ok = hashlib.sha256(text.encode('utf-8')).hexdigest() == document['checksums']['data_sha256']
    sources = {item['id']: item['sha256'] for item in document['data']['sources']}
    return {
        'data_sha256_ok': data_ok,
        'sources_listed_ok': sources == document['checksums']['sources_sha256'],
        'counts': document['counts'],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--data', default=os.environ.get('REZHIM_DATA', ROOT / 'data'))
    parser.add_argument('--assets', default=ASSETS)
    parser.add_argument('--verify', metavar='EXPORT_JSON', help='проверить выгрузку с телефона и выйти')
    args = parser.parse_args()
    if args.verify:
        print(json.dumps(verify_export(args.verify), ensure_ascii=False))
        return
    data = Path(args.data)
    snapshot = build_snapshot(data / 'rezhim.sqlite3', data / 'sources')
    output = write_bundle(snapshot, data / 'sources', args.assets)
    print(output)
    print(f"revision={snapshot['revision']} bytes={output.stat().st_size} sha256={sha256_file(output)}")
    print('counts=' + json.dumps(snapshot['counts'], ensure_ascii=False, sort_keys=True))


if __name__ == '__main__':
    main()
