"""Untrusted recognition results: retain raw input and require explicit review."""
import hashlib
import json
from datetime import datetime
from pathlib import Path

from flask import jsonify, request

from .app import now, number, required


def register_ai_routes(app, db, folder, log):
    def source(conn, raw, name):
        digest=hashlib.sha256(raw).hexdigest()
        path=digest+'.json'
        (folder/'sources'/path).write_bytes(raw)
        conn.execute('INSERT OR IGNORE INTO source(name,sha256,path,kind,screen,created_at) VALUES (?,?,?,?,?,?)',(name,digest,path,'ai_json','recognition',now()))
        return conn.execute('SELECT id FROM source WHERE sha256=?',(digest,)).fetchone()[0]

    def audit_value(conn, before, after, reason, obs_id):
        log(conn,'ai_value',{'observation_id':obs_id,'before':before,'after':after,'reason':reason})

    def merge_candidate(conn, obs_id, key, value, source_ids, reason):
        same=conn.execute("SELECT * FROM measurement WHERE observation_id=? AND param_key=? AND number=? AND state='number'",(obs_id,key,value)).fetchone()
        others=conn.execute("SELECT * FROM measurement WHERE observation_id=? AND param_key=? AND number!=? AND state='number' AND status!='rejected'",(obs_id,key,value)).fetchall()
        for previous in others:
            if previous['status']!='review':
                conn.execute("UPDATE measurement SET status='review' WHERE id=?",(previous['id'],))
                audit_value(conn,dict(previous),{**dict(previous),'status':'review'},reason+' Расхождение с импортом.',obs_id)
        if same:
            vid=same['id']
            # A separate AI item always stays in review. An identical, already
            # verified historical value is not silently re-confirmed or overwritten.
        else:
            vid=conn.execute("INSERT INTO measurement(observation_id,param_key,number,state,status) VALUES (?,?,?,'number','review')",(obs_id,key,value)).lastrowid
            audit_value(conn,None,dict(conn.execute('SELECT * FROM measurement WHERE id=?',(vid,)).fetchone()),reason,obs_id)
            for sid in source_ids:conn.execute('INSERT OR IGNORE INTO value_source VALUES (?,?)',(vid,sid))
        return vid, bool(others)

    def batch_data(conn,batch_id):
        batch=conn.execute('SELECT * FROM ai_batch WHERE id=?',(batch_id,)).fetchone()
        if not batch:raise ValueError('Импорт не найден')
        return {**dict(batch),'observations':[dict(r) for r in conn.execute('SELECT * FROM ai_observation WHERE batch_id=?',(batch_id,))],
                'items':[dict(r) for r in conn.execute('SELECT * FROM ai_item WHERE batch_id=? ORDER BY observation_id,position',(batch_id,))]}

    @app.get('/api/ai-imports')
    def batches():
        with db() as conn:
            return jsonify([dict(r) for r in conn.execute("""SELECT b.id,b.created_at,s.name,COUNT(i.id) AS items,
                SUM(CASE WHEN i.status='review' THEN 1 ELSE 0 END) AS pending FROM ai_batch b
                JOIN source s ON s.id=b.source_id LEFT JOIN ai_item i ON i.batch_id=b.id GROUP BY b.id ORDER BY b.id DESC""")])

    @app.get('/api/ai-imports/<int:batch_id>')
    def batch_detail(batch_id):
        with db() as conn:return jsonify(batch_data(conn,batch_id))

    @app.post('/api/ai-imports')
    def import_json():
        file=request.files.get('file')
        if not file:raise ValueError('Выберите JSON распознанного замера')
        raw=file.read()
        try:data=json.loads(raw.decode('utf-8-sig'),parse_constant=lambda v:(_ for _ in ()).throw(ValueError('Неконечное число: '+v)))
        except (UnicodeError,json.JSONDecodeError) as e:raise ValueError('Не удалось прочитать JSON: '+str(e)) from e
        if not isinstance(data,dict) or not isinstance(data.get('grades'),list) or not data['grades']:
            raise ValueError('Ожидается grades → measurements')
        if 'extra' in data and not isinstance(data['extra'],list):raise ValueError('extra должен быть массивом')
        total=sum(len(g.get('measurements',[])) for g in data['grades'] if isinstance(g,dict) and isinstance(g.get('measurements'),list))
        if total==0:raise ValueError('В JSON нет замеров')
        if total>100:raise ValueError('За один импорт допускается до 100 замеров')
        if data.get('extra') and total!=1:raise ValueError('Корневой extra неоднозначен: разместите его внутри нужного measurements')
        with db() as conn:
            sid=source(conn,raw,Path(file.filename or 'recognition.json').name)
            existing=conn.execute('SELECT id FROM ai_batch WHERE source_id=?',(sid,)).fetchone()
            if existing:return jsonify({**batch_data(conn,existing['id']),'already_imported':True})
            bid=conn.execute('INSERT INTO ai_batch(source_id,created_at) VALUES (?,?)',(sid,now())).lastrowid
            for grade in data['grades']:
                if not isinstance(grade,dict) or not isinstance(grade.get('measurements'),list):raise ValueError('Неверный объект марки')
                name=required(grade,'id')
                g=conn.execute('SELECT * FROM grade WHERE name=?',(name,)).fetchone()
                if not g:
                    gid=conn.execute('INSERT INTO grade(name) VALUES (?)',(name,)).lastrowid
                    log(conn,'ai_grade',{'grade_id':gid,'reason':'Марка из распознанного JSON; числам требуется сверка.'})
                else:gid=g['id']
                for m in grade['measurements']:
                    if not isinstance(m,dict) or not isinstance(m.get('values'),dict):raise ValueError('У замера должен быть объект values')
                    stamp=datetime.fromisoformat(required(m,'ts'))
                    if stamp.tzinfo:raise ValueError('Ожидается локальное время с экрана без часового пояса')
                    stamp=stamp.isoformat(timespec='seconds')
                    line={'А':'A','Б':'B','A':'A','B':'B'}.get(m.get('line'))
                    if not line:raise ValueError('Укажите линию А или Б')
                    old=conn.execute('SELECT * FROM observation WHERE timestamp=? AND line=?',(stamp,line)).fetchone()
                    warnings=[]
                    if old:
                        if old['grade_id']!=gid:raise ValueError(f'{stamp}, линия {line}: существующая марка отличается от JSON')
                        oid=old['id']
                        warnings.append('Существующий замер дополнен. Его реквизиты и расстановка насосов не заменены.')
                    else:
                        oid=conn.execute("INSERT INTO observation(grade_id,line,timestamp,a103,ab124,suutp,steady) VALUES (?,?,?,'unknown','unknown','unknown',NULL)",(gid,line,stamp)).lastrowid
                        warnings.append('Реквизиты оборудования, устойчивости и рецептуры из нейросети не подтверждены. Заполните их вручную.')
                    if conn.execute('SELECT 1 FROM ai_observation WHERE batch_id=? AND observation_id=?',(bid,oid)).fetchone():raise ValueError('Один момент и линия повторяются внутри файла. Объедините поля в один объект замера.')
                    conn.execute('INSERT INTO ai_observation(batch_id,observation_id,metadata_json,warnings_json) VALUES (?,?,?,?)',
                                 (bid,oid,json.dumps({k:v for k,v in m.items() if k not in ('values','extra')},ensure_ascii=False),json.dumps(warnings,ensure_ascii=False)))
                    entries=[{'kind':'value','key':k,'value':v} for k,v in m['values'].items()]
                    extra=m.get('extra',[])
                    if not isinstance(extra,list):raise ValueError('extra должен быть массивом')
                    if data.get('extra'):extra=extra+data['extra']
                    for e in extra:
                        if not isinstance(e,dict):raise ValueError('Элемент extra должен быть объектом')
                        entries.append({**e,'kind':'extra','key':''})
                    if len(entries)>2000:raise ValueError('Слишком много полей в замере')
                    for position,entry in enumerate(entries):
                        key=entry.get('key','');p=conn.execute('SELECT * FROM param WHERE key=?',(key,)).fetchone() if key else None
                        val=None;error=''
                        try:
                            val=number(entry.get('value'))
                            if val is None:error='Пустое значение: подтвердить как число нельзя'
                        except ValueError as e:error=str(e)
                        vid=None
                        if p and val is not None:
                            vid,conflict=merge_candidate(conn,oid,key,val,[sid],'Импорт нейросетевого распознавания: обязательная сверка')
                            if conflict:warnings.append('Расхождение параметра '+key+': варианты отправлены на сверку.')
                        conn.execute('''INSERT INTO ai_item(batch_id,observation_id,position,kind,key_hint,param_key,tag,label,unit,raw_value,number,parse_error,status,value_id)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'review',?)''',
                            (bid,oid,position,entry['kind'],key,p['key'] if p else None,str(entry.get('tag',p['tag'] if p else '') or ''),
                             str(entry.get('label',p['name'] if p else key) or ''),str(entry.get('unit',p['unit'] if p else '') or ''),
                             json.dumps(entry.get('value'),ensure_ascii=False),val,error,vid))
                    conn.execute('UPDATE ai_observation SET warnings_json=? WHERE batch_id=? AND observation_id=?',(json.dumps(warnings,ensure_ascii=False),bid,oid))
                    log(conn,'ai_import',{'observation_id':oid,'batch_id':bid,'items':len(entries),'reason':'Все распознанные поля сохранены для ручной сверки; JSON сохранён как источник.'})
            return jsonify(batch_data(conn,bid))

    @app.post('/api/ai-imports/<int:bid>/observations/<int:oid>/photo')
    def attach_photo(bid,oid):
        data=request.get_json()
        with db() as conn:
            record=conn.execute('SELECT * FROM ai_observation WHERE batch_id=? AND observation_id=?',(bid,oid)).fetchone()
            photo=conn.execute("SELECT * FROM source WHERE id=? AND kind IN ('photo','document')",(data.get('source_id'),)).fetchone()
            if not record or not photo:raise ValueError('Выберите фото или PDF для существующего импортированного замера')
            conn.execute('UPDATE ai_observation SET photo_source_id=? WHERE batch_id=? AND observation_id=?',(photo['id'],bid,oid))
            log(conn,'ai_photo',{'observation_id':oid,'batch_id':bid,'before':record['photo_source_id'],'after':photo['id'],'reason':'Оператор выбрал исходник для сверки распознанных значений.'})
        return jsonify(ok=True)

    @app.post('/api/ai-items/<int:item_id>/review')
    def decide(item_id):
        data=request.get_json();reason=required(data,'reason');decision=data.get('status')
        if decision not in ('confirmed','rejected'):raise ValueError('Выберите подтверждение или отклонение')
        with db() as conn:
            item=conn.execute('SELECT * FROM ai_item WHERE id=?',(item_id,)).fetchone()
            if not item:raise ValueError('Поле импорта не найдено')
            if item['status']!='review':raise ValueError('Поле уже обработано. Обновите список сверки.')
            before=dict(item);oid=item['observation_id']
            if decision=='confirmed':
                p=conn.execute('SELECT * FROM param WHERE key=?',(data.get('param_key'),)).fetchone()
                if not p:raise ValueError('Выберите параметр словаря или сначала создайте его')
                if item['param_key'] and item['param_key']!=p['key']:
                    raise ValueError('Известный ключ параметра нельзя менять при сверке. Отклоните ошибочное поле и создайте корректное.')
                val=number(data.get('number'))
                if val is None:raise ValueError('Пустое значение нельзя подтвердить')
                if item['unit'] and p['unit']!=item['unit']:raise ValueError('Единица extra/импорта не совпадает с единицей выбранного параметра; автоматического пересчёта нет')
                record=conn.execute('SELECT * FROM ai_observation WHERE batch_id=? AND observation_id=?',(item['batch_id'],oid)).fetchone()
                if not record['photo_source_id']:raise ValueError('Сначала прикрепите фото или PDF для сверки этого замера')
                batch=conn.execute('SELECT * FROM ai_batch WHERE id=?',(item['batch_id'],)).fetchone()
                sid=[batch['source_id'],record['photo_source_id']]
                vid,_=merge_candidate(conn,oid,p['key'],val,sid,reason)
                # Reject competing values explicitly, retaining their history.
                competitors=conn.execute("SELECT * FROM measurement WHERE observation_id=? AND param_key=? AND id!=? AND status!='rejected'",(oid,p['key'],vid)).fetchall()
                for other in competitors:
                    conn.execute("UPDATE measurement SET status='rejected' WHERE id=?",(other['id'],))
                    audit_value(conn,dict(other),{**dict(other),'status':'rejected'},reason+' Выбран другой вариант при сверке.',oid)
                previous=dict(conn.execute('SELECT * FROM measurement WHERE id=?',(vid,)).fetchone())
                conn.execute("UPDATE measurement SET status='confirmed' WHERE id=?",(vid,))
                for source_id in sid:conn.execute('INSERT OR IGNORE INTO value_source VALUES (?,?)',(vid,source_id))
                audit_value(conn,previous,{**previous,'status':'confirmed'},reason,oid)
                conn.execute("UPDATE ai_item SET param_key=?,number=?,parse_error='',value_id=?,status='confirmed' WHERE id=?",(p['key'],val,vid,item_id))
            else:
                conn.execute("UPDATE ai_item SET status='rejected' WHERE id=?",(item_id,))
                if item['value_id']:
                    value=conn.execute('SELECT * FROM measurement WHERE id=?',(item['value_id'],)).fetchone()
                    other_pending=conn.execute("SELECT 1 FROM ai_item WHERE value_id=? AND id!=? AND status IN ('review','confirmed')",(item['value_id'],item_id)).fetchone()
                    sources=conn.execute('SELECT s.kind FROM source s JOIN value_source vs ON vs.source_id=s.id WHERE vs.value_id=?',(item['value_id'],)).fetchall()
                    if value and value['status']=='review' and not other_pending and all(s['kind']=='ai_json' for s in sources):
                        conn.execute("UPDATE measurement SET status='rejected' WHERE id=?",(value['id'],))
                        audit_value(conn,dict(value),{**dict(value),'status':'rejected'},reason,oid)
            after=dict(conn.execute('SELECT * FROM ai_item WHERE id=?',(item_id,)).fetchone())
            log(conn,'ai_review',{'observation_id':oid,'before':before,'after':after,'reason':reason})
        return jsonify(ok=True)

    @app.post('/api/ai-imports/<int:bid>/observations/<int:oid>/confirm-all')
    def confirm_all(bid,oid):
        data=request.get_json();reason=required(data,'reason')
        with db() as conn:
            record=conn.execute('SELECT * FROM ai_observation WHERE batch_id=? AND observation_id=?',(bid,oid)).fetchone()
            if not record:raise ValueError('Замер этого импорта не найден')
            if not record['photo_source_id']:raise ValueError('Сначала прикрепите фото или PDF для сверки этого замера')
            items=conn.execute("SELECT * FROM ai_item WHERE batch_id=? AND observation_id=? AND status='review' ORDER BY position",(bid,oid)).fetchall()
            if not items:raise ValueError('В этом замере нет полей, ожидающих сверки')
            unsupported=[]
            for item in items:
                if item['kind']!='value' or not item['param_key'] or item['number'] is None or item['parse_error']:
                    unsupported.append(item['label'] or item['key_hint'] or 'дополнительное поле')
            if unsupported:
                raise ValueError('Нельзя подтвердить всё одним действием: проверьте и сопоставьте отдельно: '+', '.join(unsupported))
            batch=conn.execute('SELECT * FROM ai_batch WHERE id=?',(bid,)).fetchone()
            source_ids=[batch['source_id'],record['photo_source_id']]
            for item in items:
                before=dict(item)
                value,_=merge_candidate(conn,oid,item['param_key'],item['number'],source_ids,reason)
                competitors=conn.execute("SELECT * FROM measurement WHERE observation_id=? AND param_key=? AND id!=? AND status!='rejected'",(oid,item['param_key'],value)).fetchall()
                for other in competitors:
                    conn.execute("UPDATE measurement SET status='rejected' WHERE id=?",(other['id'],))
                    audit_value(conn,dict(other),{**dict(other),'status':'rejected'},reason+' Выбран вариант, сверенный одной операцией.',oid)
                previous=dict(conn.execute('SELECT * FROM measurement WHERE id=?',(value,)).fetchone())
                conn.execute("UPDATE measurement SET status='confirmed' WHERE id=?",(value,))
                for source_id in source_ids:conn.execute('INSERT OR IGNORE INTO value_source VALUES (?,?)',(value,source_id))
                audit_value(conn,previous,{**previous,'status':'confirmed'},reason,oid)
                conn.execute("UPDATE ai_item SET value_id=?,status='confirmed' WHERE id=?",(value,item['id']))
                after=dict(conn.execute('SELECT * FROM ai_item WHERE id=?',(item['id'],)).fetchone())
                log(conn,'ai_review_all',{'observation_id':oid,'before':before,'after':after,'reason':reason})
        return jsonify(ok=True,confirmed=len(items))

    @app.post('/api/ai-imports/<int:bid>/observations/<int:oid>/context')
    def review_context(bid,oid):
        data=request.get_json();reason=required(data,'reason')
        if data.get('a103') not in ('unknown','running','stopped') or data.get('ab124') not in ('unknown','running','stopped'):
            raise ValueError('Неверное состояние оборудования')
        if data.get('steady') is not None and type(data.get('steady')) is not bool:raise ValueError('Неверный признак устойчивости')
        with db() as conn:
            o=conn.execute('SELECT o.* FROM observation o JOIN ai_observation a ON a.observation_id=o.id WHERE a.batch_id=? AND o.id=?',(bid,oid)).fetchone()
            if not o:raise ValueError('Замер импорта не найден')
            did=data.get('doc_id') or None
            if did:
                doc=conn.execute('SELECT * FROM doc_version WHERE id=?',(did,)).fetchone()
                if not doc or doc['grade_id']!=o['grade_id'] or not(doc['valid_from']<=o['timestamp'][:10]<=(doc['valid_to'] or '9999-12-31')):
                    raise ValueError('Техкарта не соответствует марке или времени замера')
            after={'a103':data['a103'],'ab124':data['ab124'],'steady':None if data.get('steady') is None else int(data['steady']),
                   'doc_id':did,'recipe12':data.get('recipe12','').strip(),'recipe3':data.get('recipe3','').strip()}
            conn.execute('UPDATE observation SET a103=?,ab124=?,steady=?,doc_id=?,recipe12=?,recipe3=? WHERE id=?',(*after.values(),oid))
            for key,value in after.items():
                if o[key]!=value:log(conn,'ai_context_review',{'observation_id':oid,'field':key,'before':o[key],'after':value,'reason':reason})
        return jsonify(ok=True)
