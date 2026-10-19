import hashlib
import json
import math
import os
import re
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path

from flask import Flask, jsonify, request, send_file, send_from_directory
from werkzeug.exceptions import HTTPException

from .catalog import PARAMS
from .ocr_engine import installation_status
from .smeny_page import render as render_smeny
from .upgrades import allow_unknown_steady

ROOT = Path(__file__).resolve().parent.parent
STATES = {'running', 'stopped', 'unknown'}


def now():
    return datetime.now(timezone.utc).isoformat()


def number(raw):
    if raw is None or isinstance(raw, str) and not raw.strip():
        return None
    if isinstance(raw, bool) or not isinstance(raw, (int, float, str)):
        raise ValueError('Ожидается число, а не логическое значение или объект')
    if isinstance(raw, str):
        raw = raw.strip().replace(',', '.')
        if not re.fullmatch(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)', raw):
            raise ValueError('Неверный формат числа')
    result = float(raw)
    if not math.isfinite(result):
        raise ValueError('Число должно быть конечным')
    return result


def required(data, key):
    value = data.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f'Заполните поле {key}')
    return value.strip()


def create_app(data_dir=None):
    app = Flask(__name__, static_folder=None)
    folder = Path(data_dir or os.environ.get('REZHIM_DATA', ROOT / 'data'))
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'sources').mkdir(exist_ok=True)
    app.config.update(MAX_CONTENT_LENGTH=25 * 1024 * 1024, DATA_DIR=folder)

    @contextmanager
    def db():
        conn = sqlite3.connect(folder / 'rezhim.sqlite3')
        conn.row_factory = sqlite3.Row
        conn.execute('PRAGMA foreign_keys=ON')
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    with db() as conn:
        conn.executescript((ROOT / 'rezhim/schema.sql').read_text(encoding='utf-8'))
        allow_unknown_steady(conn, folder)
        conn.execute("UPDATE metadata SET value='2' WHERE key='schema_version'")
        for p in PARAMS:
            conn.execute('INSERT OR IGNORE INTO param(key,name,tag,unit,role,load_dependency,group_name) VALUES (?,?,?,?,?,?,?)', p)

    def rows(conn, table):
        return [dict(r) for r in conn.execute(f'SELECT * FROM {table}')]

    def log(conn, action, details):
        conn.execute('INSERT INTO audit(created_at,action,details) VALUES (?,?,?)', (now(), action, json.dumps(details, ensure_ascii=False)))

    def snapshot(conn, confirmed=False):
        result = {name: rows(conn, name) for name in ('grade','param','doc_version','norm','recipe','observation','pump_map')}
        result['source'] = [{k:v for k,v in s.items() if k != 'path'} for s in rows(conn, 'source')]
        values = rows(conn, 'measurement')
        if confirmed:
            values = [v for v in values if v['status']=='confirmed' and v['state']=='number']
        ids = {v['id'] for v in values}
        result['measurement'] = values
        result['value_source'] = [s for s in rows(conn, 'value_source') if s['value_id'] in ids]
        for table, id_key in [('doc_details','doc_id'),('observation_details','observation_id')]:
            result[table] = [{id_key:r[id_key], **json.loads(r['payload'])} for r in rows(conn,table)]
        return result

    @app.before_request
    def local_writes_only():
        if request.method in ('POST','PUT','DELETE'):
            if request.headers.get('X-Rezhim-Client') != 'desk':
                return jsonify(error='Запись разрешена только из локального приложения'), 403
            origin = request.headers.get('Origin')
            if origin and origin != request.host_url.rstrip('/'):
                return jsonify(error='Недопустимый источник запроса'), 403

    @app.errorhandler(ValueError)
    @app.errorhandler(sqlite3.IntegrityError)
    def invalid(error):
        return jsonify(error=str(error)), 400

    @app.errorhandler(HTTPException)
    def http_error(error):
        return jsonify(error=error.description), error.code

    @app.get('/')
    def home():
        return send_from_directory(ROOT / 'web', 'index.html')

    @app.get('/assets/<path:name>')
    def assets(name):
        return send_from_directory(ROOT / 'web', name)

    @app.get('/mobile/')
    def mobile():
        return send_from_directory(ROOT / 'mobile', 'index.html')

    @app.get('/smeny/')
    def smeny():
        with db() as conn:
            return render_smeny(conn), 200, {'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache'}

    @app.get('/mobile/<path:name>')
    def mobile_assets(name):
        response = send_from_directory(ROOT / 'mobile', name)
        if name == 'sw.js':
            response.headers['Cache-Control'] = 'no-cache'
        return response

    @app.get('/api/state')
    def state():
        with db() as conn:
            result = snapshot(conn)
            result['templates'] = [json.loads(p.read_text(encoding='utf-8')) for p in sorted((ROOT/'screen_templates').glob('*.json'))]
            return jsonify(result)

    @app.get('/api/audit')
    def audit_history():
        with db() as conn:
            return jsonify([{**dict(r),'details':json.loads(r['details'])} for r in conn.execute('SELECT * FROM audit ORDER BY id DESC LIMIT 1000')])

    @app.get('/api/ocr/status')
    def ocr_status():
        """Read-only diagnostic: OCR is intentionally isolated from database writes."""
        return jsonify(installation_status())

    @app.post('/api/grades')
    def add_grade():
        data = request.get_json()
        with db() as conn:
            conn.execute('INSERT INTO grade(name,aliases) VALUES (?,?)', (required(data,'name'), data.get('aliases','')))
        return jsonify(ok=True)

    @app.post('/api/params')
    def save_param():
        data = request.get_json()
        key = required(data,'key')
        if not re.fullmatch('[a-z][a-z0-9_]{0,60}',key):
            raise ValueError('Ключ: латинские строчные буквы, цифры и подчёркивания')
        unit = required(data,'unit')
        with db() as conn:
            old = conn.execute('SELECT * FROM param WHERE key=?',(key,)).fetchone()
            used = conn.execute("SELECT 1 FROM measurement WHERE param_key=? AND state='number' UNION ALL SELECT 1 FROM norm WHERE param_key=?",(key,key)).fetchone()
            if old and old['unit'] != unit and used:
                raise ValueError('Единицы параметра с замерами или нормами менять нельзя. Создайте новый параметр.')
            conn.execute('''INSERT INTO param(key,name,tag,unit,precision,role,load_dependency,group_name) VALUES (?,?,?,?,?,?,?,?)
              ON CONFLICT(key) DO UPDATE SET name=excluded.name,tag=excluded.tag,unit=excluded.unit,precision=excluded.precision,
              role=excluded.role,load_dependency=excluded.load_dependency,group_name=excluded.group_name''',
              (key,required(data,'name'),data.get('tag',''),unit,int(data.get('precision',2)),data['role'],data['load_dependency'],required(data,'group_name')))
            log(conn,'param',data)
        return jsonify(ok=True)

    @app.post('/api/sources')
    def upload():
        file = request.files.get('file')
        if not file or not file.filename:
            raise ValueError('Выберите исходный файл')
        content = file.read()
        if not content:
            raise ValueError('Исходник пуст')
        # Store only inert image/PDF source formats; never serve user HTML or SVG.
        if content.startswith(b'\x89PNG\r\n\x1a\n'):
            ext = '.png'
        elif content.startswith(b'\xff\xd8\xff'):
            ext = '.jpg'
        elif content.startswith(b'%PDF-'):
            ext = '.pdf'
        else:
            raise ValueError('Поддерживаются исходники JPEG, PNG и PDF')
        digest = hashlib.sha256(content).hexdigest()
        path = folder / 'sources' / (digest + ext)
        path.write_bytes(content)
        with db() as conn:
            conn.execute('INSERT OR IGNORE INTO source(name,sha256,path,kind,screen,created_at) VALUES (?,?,?,?,?,?)',
                         (Path(file.filename).name,digest,path.name,'document' if ext=='.pdf' else 'photo',request.form.get('screen','unknown'),now()))
            result = dict(conn.execute('SELECT * FROM source WHERE sha256=?',(digest,)).fetchone())
            result.pop('path')
            return jsonify(result)

    @app.get('/api/sources/<int:source_id>/file')
    def source_file(source_id):
        with db() as conn:
            source = conn.execute('SELECT * FROM source WHERE id=?',(source_id,)).fetchone()
            if not source:
                return jsonify(error='Исходник не найден'),404
            response = send_file(folder/'sources'/source['path'], download_name=source['name'], as_attachment=source['kind'] in ('migration','prototype','correction','ai_json'))
            response.headers['X-Content-Type-Options'] = 'nosniff'
            return response

    @app.post('/api/docs')
    def add_doc():
        data = request.get_json()
        start = datetime.strptime(required(data,'valid_from'),'%Y-%m-%d').date().isoformat()
        end = datetime.strptime(data['valid_to'],'%Y-%m-%d').date().isoformat() if data.get('valid_to') else None
        if end and end < start:
            raise ValueError('Конец действия раньше начала')
        with db() as conn:
            overlap = conn.execute('SELECT id FROM doc_version WHERE grade_id=? AND valid_from<=? AND COALESCE(valid_to,?)>=?',(data['grade_id'],end or '9999-12-31','9999-12-31',start)).fetchone()
            if overlap:
                if not data.get('supersedes_previous'):
                    raise ValueError('Периоды техкарт одной марки не должны пересекаться')
                previous_end = (datetime.strptime(start,'%Y-%m-%d').date() - timedelta(days=1)).isoformat()
                conn.execute('UPDATE doc_version SET valid_to=? WHERE id=?',(previous_end,overlap['id']))
            cur = conn.execute('INSERT INTO doc_version(grade_id,version,valid_from,valid_to,source_id,notes) VALUES (?,?,?,?,?,?)',
                               (data['grade_id'],required(data,'version'),start,end,data['source_id'],data.get('notes','')))
            doc_id = cur.lastrowid
            for norm in data.get('norms',[]):
                low, target, high = (number(norm.get(k)) for k in ('lower_value','target','upper_value'))
                if low is None and target is None and high is None:
                    continue
                param = conn.execute('SELECT unit FROM param WHERE key=?',(norm['param_key'],)).fetchone()
                if not param or not param['unit']:
                    raise ValueError('Перед вводом нормы задайте единицу параметра')
                if target is not None and (low is not None and target<low or high is not None and target>high):
                    raise ValueError('Цель должна находиться внутри нормы')
                conn.execute('INSERT INTO norm(doc_id,param_key,lower_value,target,upper_value,section,row_number) VALUES (?,?,?,?,?,?,?)',
                             (doc_id,norm['param_key'],low,target,high,required(norm,'section'),required(norm,'row_number')))
            for recipe in data.get('recipes',[]):
                percent, mass = (number(recipe.get(k)) for k in ('percent','mass'))
                count = recipe.get('count')
                if count in ('',None):
                    count = None
                elif isinstance(count,bool) or int(count) != count:
                    raise ValueError('Количество мест в рецептуре должно быть целым числом')
                else:
                    count = int(count)
                conn.execute('INSERT INTO recipe(doc_id,zones,version,component,percent,mass,count) VALUES (?,?,?,?,?,?,?)',
                             (doc_id,required(recipe,'zones'),required(recipe,'version'),required(recipe,'component'),percent,mass,count))
            log(conn,'doc',{'id':doc_id})
        return jsonify(id=doc_id)

    @app.post('/api/observations')
    def save_observation():
        data = request.get_json()
        timestamp = datetime.fromisoformat(required(data,'timestamp'))
        if timestamp.tzinfo is not None:
            raise ValueError('Введите локальное время с экрана без часового пояса')
        stamp = timestamp.isoformat(timespec='seconds')
        if data.get('line') not in ('A','B') or data.get('a103') not in STATES or data.get('ab124') not in STATES:
            raise ValueError('Укажите линию и явное состояние оборудования')
        if data.get('suutp') not in ('on','off','unknown') or data.get('steady') is not None and type(data.get('steady')) is not bool:
            raise ValueError('Укажите состояние СУУТП и устойчивость режима')
        if not data.get('values'):
            raise ValueError('Нет полей для сохранения')
        with db() as conn:
            if not conn.execute('SELECT 1 FROM source WHERE id=?',(data.get('source_id'),)).fetchone():
                raise ValueError('Выберите исходник')
            doc_id = data.get('doc_id') or None
            if doc_id:
                doc = conn.execute('SELECT * FROM doc_version WHERE id=?',(doc_id,)).fetchone()
                if not doc or doc['grade_id']!=int(data['grade_id']) or not (doc['valid_from']<=stamp[:10]<=(doc['valid_to'] or '9999-12-31')):
                    raise ValueError('Техкарта не соответствует марке или дате замера')
            fields = ('grade_id','line','timestamp','a103','ab124','suutp','steady','doc_id','recipe12','recipe3')
            metadata = (int(data['grade_id']),data['line'],stamp,data['a103'],data['ab124'],data['suutp'],None if data.get('steady') is None else int(data['steady']),doc_id,data.get('recipe12','').strip(),data.get('recipe3','').strip())
            old = conn.execute('SELECT * FROM observation WHERE line=? AND timestamp=?',(data['line'],stamp)).fetchone()
            if old:
                if any(old[k]!=v for k,v in zip(fields,metadata)):
                    raise ValueError('Замер на это время уже есть, но его реквизиты отличаются. Сверьте марку, конфигурацию и версии.')
                obs_id = old['id']
            else:
                obs_id = conn.execute(f'INSERT INTO observation({",".join(fields)}) VALUES ({",".join("?" for _ in fields)})',metadata).lastrowid
            for zone,pump in data.get('pumps',{}).items():
                if pump in ('',None):
                    continue
                previous = conn.execute('SELECT pump FROM pump_map WHERE observation_id=? AND zone=?',(obs_id,int(zone))).fetchone()
                if previous and previous['pump']!=int(pump):
                    raise ValueError('Расстановка насосов конфликтует с существующим замером')
                conn.execute('INSERT OR IGNORE INTO pump_map VALUES (?,?,?)',(obs_id,int(zone),int(pump)))
                # OR IGNORE must not hide a pump already assigned to another zone.
                if not conn.execute('SELECT 1 FROM pump_map WHERE observation_id=? AND zone=? AND pump=?',(obs_id,int(zone),int(pump))).fetchone():
                    raise ValueError('Один насос нельзя назначить двум зонам')
            conflicts = 0
            for item in data['values']:
                key = item['param_key']
                param = conn.execute('SELECT * FROM param WHERE key=?',(key,)).fetchone()
                if not param:
                    raise ValueError('Неизвестный параметр')
                val = number(item.get('number'))
                state = 'error' if item.get('state')=='error' else ('missing' if val is None else 'number')
                if state=='error':
                    val = None
                if state=='number' and not param['unit']:
                    raise ValueError(f'Сначала задайте единицу параметра «{param["name"]}» в справочнике')
                status = item.get('status','review')
                if status not in ('confirmed','review','rejected'):
                    raise ValueError('Неверный статус проверки')
                if state!='number':
                    status='review'
                same = conn.execute('SELECT id FROM measurement WHERE observation_id=? AND param_key=? AND number IS ? AND state=?',(obs_id,key,val,state)).fetchone()
                if same:
                    value_id=same['id']
                else:
                    active = conn.execute("SELECT id FROM measurement WHERE observation_id=? AND param_key=? AND state='number' AND status!='rejected'",(obs_id,key)).fetchall()
                    if state=='number' and active:
                        conn.execute("UPDATE measurement SET status='review' WHERE observation_id=? AND param_key=? AND state='number' AND status!='rejected'",(obs_id,key))
                        status='review'
                        conflicts+=1
                    value_id=conn.execute('INSERT INTO measurement(observation_id,param_key,number,state,status) VALUES (?,?,?,?,?)',(obs_id,key,val,state,status)).lastrowid
                conn.execute('INSERT OR IGNORE INTO value_source VALUES (?,?)',(value_id,data['source_id']))
            log(conn,'observation',{'id':obs_id,'source':data['source_id'],'conflicts':conflicts})
        return jsonify(id=obs_id,conflicts=conflicts)

    @app.post('/api/observations/<int:obs_id>/context')
    def update_context(obs_id):
        data = request.get_json()
        if set(data) != {'a103','ab124','steady'}:
            raise ValueError('Разрешено изменение только А-103, АБ-124 и устойчивости')
        if data['a103'] not in STATES or data['ab124'] not in STATES or data['steady'] is not None and type(data['steady']) is not bool:
            raise ValueError('Неверные состояния оборудования или устойчивости')
        with db() as conn:
            old = conn.execute('SELECT a103,ab124,steady FROM observation WHERE id=?',(obs_id,)).fetchone()
            if not old:
                return jsonify(error='Замер не найден'),404
            conn.execute('UPDATE observation SET a103=?,ab124=?,steady=? WHERE id=?',
                         (data['a103'],data['ab124'],None if data['steady'] is None else int(data['steady']),obs_id))
            log(conn,'observation_context',{'observation_id':obs_id,'before':dict(old),'after':data})
        return jsonify(ok=True)

    @app.post('/api/values/<int:value_id>/review')
    def review(value_id):
        status = request.get_json().get('status')
        if status not in ('confirmed','review','rejected'):
            raise ValueError('Неверный статус')
        with db() as conn:
            item = conn.execute('SELECT * FROM measurement WHERE id=?',(value_id,)).fetchone()
            if not item:
                return jsonify(error='Значение не найдено'),404
            if conn.execute("SELECT 1 FROM ai_item WHERE value_id=? AND status='review'",(value_id,)).fetchone():
                raise ValueError('Значение связано с распознаванием. Откройте «Импорт JSON» и выполните сверку рядом с фото.')
            if status=='confirmed':
                if item['state']!='number':
                    raise ValueError('Пропуск или ошибку подтвердить нельзя')
                conn.execute("UPDATE measurement SET status='rejected' WHERE observation_id=? AND param_key=? AND id!=?",(item['observation_id'],item['param_key'],value_id))
            conn.execute('UPDATE measurement SET status=? WHERE id=?',(status,value_id))
            log(conn,'review',{'value_id':value_id,'status':status})
        return jsonify(ok=True)

    @app.post('/api/export')
    def export():
        with db() as conn:
            version=int(conn.execute("SELECT value FROM metadata WHERE key='package_version'").fetchone()[0])+1
            conn.execute("UPDATE metadata SET value=? WHERE key='package_version'",(str(version),))
            data=snapshot(conn,confirmed=True)
            package={'format':'rezhim-package','schema_version':2,'version':version,'built_at':now(),
                     'counts':{'grades':len(data['grade']),'observations':len(data['observation']),'values':len(data['measurement'])},'data':data}
            log(conn,'export',{'version':version})
        return jsonify(package)

    @app.post('/api/export/full')
    def export_full():
        """Portable mobile package including numeric values awaiting review."""
        with db() as conn:
            version=int(conn.execute("SELECT value FROM metadata WHERE key='package_version'").fetchone()[0])+1
            conn.execute("UPDATE metadata SET value=? WHERE key='package_version'",(str(version),))
            data=snapshot(conn,confirmed=False)
            package={'format':'rezhim-package','schema_version':2,'version':version,'built_at':now(),
                     'counts':{'grades':len(data['grade']),'observations':len(data['observation']),'values':len(data['measurement'])},'data':data,
                     'includes_unreviewed':True}
            log(conn,'export_full',{'version':version})
        return jsonify(package)

    @app.get('/api/export/ai')
    def export_ai():
        """Self-describing full dataset for analysis or a neural-network workflow."""
        with db() as conn:
            params={p['key']:dict(p) for p in conn.execute('SELECT * FROM param')}
            grades={g['id']:dict(g) for g in conn.execute('SELECT * FROM grade')}
            docs={d['id']:dict(d) for d in conn.execute('SELECT * FROM doc_version')}
            sources={s['id']:{k:v for k,v in dict(s).items() if k!='path'} for s in conn.execute('SELECT * FROM source')}
            links={}
            for link in conn.execute('SELECT * FROM value_source'):
                links.setdefault(link['value_id'],[]).append(sources.get(link['source_id']))
            values={}
            for value in conn.execute('SELECT * FROM measurement ORDER BY observation_id,param_key,id'):
                entry=dict(value)
                entry['parameter']=params.get(value['param_key'])
                entry['sources']=[source for source in links.get(value['id'],[]) if source]
                values.setdefault(value['observation_id'],[]).append(entry)
            imported={}
            for item in conn.execute('''SELECT i.*, b.id AS batch_id, b.created_at AS imported_at,
                                        o.photo_source_id FROM ai_item i
                                        JOIN ai_batch b ON b.id=i.batch_id
                                        JOIN ai_observation o ON o.batch_id=i.batch_id AND o.observation_id=i.observation_id
                                        ORDER BY i.observation_id,i.batch_id,i.position'''):
                entry=dict(item)
                entry['parameter']=params.get(item['param_key']) if item['param_key'] else None
                entry['photo_source']=sources.get(item['photo_source_id'])
                imported.setdefault(item['observation_id'],[]).append(entry)
            observations=[]
            for observation in conn.execute('SELECT * FROM observation ORDER BY timestamp,line,id'):
                record=dict(observation)
                record['grade']=grades.get(observation['grade_id'])
                record['document']=docs.get(observation['doc_id']) if observation['doc_id'] else None
                record['pump_map']=[dict(p) for p in conn.execute('SELECT * FROM pump_map WHERE observation_id=? ORDER BY zone',(observation['id'],))]
                record['values']=values.get(observation['id'],[])
                record['recognition_items']=imported.get(observation['id'],[])
                observations.append(record)
            return jsonify({
                'format':'rezhim-ai-dataset',
                'schema_version':1,
                'built_at':now(),
                'description':'Все замеры и варианты значений. Каждое поле содержит ключ, русское название, тег, единицу, статус проверки и исходники.',
                'status_legend':{'confirmed':'сверено оператором','review':'ожидает ручной сверки','rejected':'отклонённый вариант'},
                'observations':observations
            })

    from .ai_import import register_ai_routes
    register_ai_routes(app, db, folder, log)
    return app


if __name__=='__main__':
    create_app().run(host='127.0.0.1',port=int(os.environ.get('REZHIM_PORT','5000')),debug=False)
