"""Explicit, transactional import of rezhim-migration/1. Never execute the HTML."""
import argparse
import hashlib
import json
import re
import shutil
import sqlite3
from contextlib import closing
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from .app import create_app, now


def prototype_seed(text):
    match = re.search(r'var SEED\s*=\s*([\s\S]*?);\s*var LS_KEY',text)
    if not match:
        raise ValueError('Не найден объект SEED в прототипе')
    # Restricted data-literal parser: quoted strings, numbers, keys and punctuation.
    # No JS evaluation, function calls, comments or expressions are allowed.
    raw = match[1]
    pattern = re.compile(r'\s*("(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|[A-Za-z_$][\w$]*|[{}\[\]:,])')
    output, pos = [], 0
    while pos < len(raw.rstrip()):
        token = pattern.match(raw,pos)
        if not token:
            raise ValueError('Прототип содержит неподдерживаемое выражение в SEED')
        value = token[1]
        if re.fullmatch(r'[A-Za-z_$][\w$]*',value) and value not in ('true','false','null'):
            if not raw[token.end():].lstrip().startswith(':'):
                raise ValueError('Допускаются только данные в SEED')
            value = json.dumps(value)
        output.append(value)
        pos = token.end()
    return json.loads(''.join(output),parse_float=Decimal,parse_int=Decimal)


def inspect_inputs(migration_path, prototype_path):
    raw = migration_path.read_bytes()
    data = json.loads(raw.decode('utf-8-sig'),parse_float=Decimal,parse_int=Decimal)
    if data.get('schema')!='rezhim-migration/1':
        raise ValueError('Ожидается rezhim-migration/1')
    old = prototype_seed(prototype_path.read_text(encoding='utf-8-sig'))
    if len(data['grades'])!=len(old['grades']):
        raise ValueError('Количество марок не совпадает с прототипом')
    for grade in data['grades']:
        original = next(g for g in old['grades'] if g['id']==grade['id'])
        for field in ('norms','spec','recipe','note'):
            if grade[field]!=original[field]:
                raise ValueError(f'Данные {grade["id"]}/{field} расходятся с прототипом')
        if grade['card_notes']!=original['notes']:
            raise ValueError('Примечания отличаются от прототипа')
        if len(grade['measurements'])!=len(original['measurements']):
            raise ValueError('Количество замеров отличается от прототипа')
        for obs in grade['measurements']:
            before = next(x for x in original['measurements'] if x['ts']==obs['ts'] and x['line']==obs['line'])
            if obs['values']!=before['params'] or obs['needs_review']!=before['check'] or obs.get('pumps')!=before.get('pumps') or obs['source']!=before['source']:
                raise ValueError('Замер отличается от прототипа: '+obs['ts'])
    return data, hashlib.sha256(raw).hexdigest()


def decimal_number(raw):
    if raw is None or raw=='—':
        return None
    d = Decimal(str(raw).replace(',','.'))
    if not d.is_finite():
        raise ValueError('Неконечное число')
    value = float(d)
    if Decimal(str(value))!=d:
        raise ValueError('Число нельзя перенести без потери точности: '+str(raw))
    return value


def migrate(migration_path, prototype_path, data_dir):
    migration_path, prototype_path, data_dir = map(Path,(migration_path,prototype_path,data_dir))
    data,digest = inspect_inputs(migration_path,prototype_path)
    create_app(data_dir)
    conn = sqlite3.connect(data_dir/'rezhim.sqlite3')
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys=ON')
    try:
        existing=conn.execute('SELECT report FROM migration_run WHERE sha256=?',(digest,)).fetchone()
        if existing:
            return {**json.loads(existing['report']),'already_imported':True}
        backups=data_dir/'backups'
        backups.mkdir(exist_ok=True)
        backup=backups/('before-data-migration-'+datetime.now().strftime('%Y%m%d-%H%M%S-%f')+'.sqlite3')
        with closing(sqlite3.connect(backup)) as copy:
            conn.backup(copy)
        report={'sha256':digest,'prototype_verified':True,'backup':str(backup.resolve()),'grades':0,'norms':0,'recipe_rows':0,'observations':[], 'values':0,'confirmed':0,'review':0,'warnings':[]}
        with conn:
            source_ids=[]
            for path,kind in [(migration_path,'migration'),(prototype_path,'prototype')]:
                checksum=hashlib.sha256(path.read_bytes()).hexdigest()
                target=checksum+path.suffix
                shutil.copyfile(path,data_dir/'sources'/target)
                conn.execute('INSERT OR IGNORE INTO source(name,sha256,path,kind,screen,created_at) VALUES (?,?,?,?,?,?)',(path.name,checksum,target,kind,'legacy',now()))
                source_ids.append(conn.execute('SELECT id FROM source WHERE sha256=?',(checksum,)).fetchone()[0])
            groups={g['key']:g['title'] for g in data['groups']}
            for p in data['params']:
                key=p['key']
                existing=conn.execute('SELECT 1 FROM measurement WHERE param_key=? UNION ALL SELECT 1 FROM norm WHERE param_key=?',(key,key)).fetchone()
                if existing:
                    raise ValueError('Параметр уже используется; требуется явное сопоставление: '+key)
                decimals=[-n.as_tuple().exponent for g in data['grades'] for m in g['measurements'] for k,n in m['values'].items() if k==key]
                decimals += [-n.as_tuple().exponent for g in data['grades'] for n in g['norms'].get(key,{}).values()]
                precision=max([2]+decimals)
                if precision>6:
                    raise ValueError('Точность больше 6 знаков: '+key)
                role='setpoint' if key.endswith('_sp') or key=='valve' else 'controller_output' if key.endswith('_out') else 'lab' if key=='mfr_lab' else 'suutp' if key.endswith('_est') else 'measurement'
                dependency={'база':'base','тянется':'scales','не тянется':'independent'}[p['load_dependency']]
                if key=='load': dependency='independent'
                if key=='c2_flow': dependency='base'
                conn.execute('''INSERT INTO param(key,name,tag,unit,precision,role,load_dependency,group_name) VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT(key) DO UPDATE SET name=excluded.name,tag=excluded.tag,unit=excluded.unit,precision=excluded.precision,
                    role=excluded.role,load_dependency=excluded.load_dependency,group_name=excluded.group_name''',
                    (key,p['label'],p['tag'],p['unit'] or '',precision,role,dependency,groups[p['group']]))
            for g in data['grades']:
                gid=conn.execute('INSERT INTO grade(name,aliases) VALUES (?,?)',(g['id'],', '.join(g['aliases']))).lastrowid
                report['grades']+=1
                doc=g['doc_version']
                did=conn.execute('INSERT INTO doc_version(grade_id,version,valid_from,source_id,notes) VALUES (?,?,?,?,?)',
                    (gid,doc['number'],doc['date'],source_ids[0],g['note']+'\n'+'\n'.join(g['card_notes']))).lastrowid
                conn.execute('INSERT INTO doc_details VALUES (?,?)',(did,json.dumps({'title':doc['title'],'spec':g['spec'],'recipe':g['recipe'],'card_notes':g['card_notes'],'date_basis':'Дата документа из миграции; отдельный период действия не указан.'},ensure_ascii=False)))
                for key,n in g['norms'].items():
                    conn.execute('INSERT INTO norm(doc_id,param_key,lower_value,target,upper_value,section,row_number) VALUES (?,?,?,?,?,?,?)',
                        (did,key,decimal_number(n.get('lo')),decimal_number(n.get('tg')),decimal_number(n.get('hi')),'Не указан в исходнике','Не указана в исходнике'))
                    report['norms']+=1
                versions=[]
                for block in g['recipe']:
                    # The source identifies the recipe by its card version and zone block.
                    version=doc['number']+' / '+block['zone']
                    versions.append(version)
                    for component,percent,mass,count in block['rows']:
                        conn.execute('INSERT INTO recipe(doc_id,zones,version,component,percent,mass,count) VALUES (?,?,?,?,?,?,?)',
                            (did,block['zone'],version,component,decimal_number(percent),decimal_number(mass),decimal_number(count)))
                        report['recipe_rows']+=1
                if len(versions)!=2:
                    raise ValueError('Ожидается два блока рецептуры по зонам')
                for m in g['measurements']:
                    if m['equipment']!={'a103':'неизвестно','ab124':'неизвестно'} or m['steady']!='не указано':
                        raise ValueError('Этот импорт ожидает явные заглушки конфигурации и устойчивости')
                    stamp=datetime.fromisoformat(m['ts']).isoformat(timespec='seconds')
                    oid=conn.execute('''INSERT INTO observation(grade_id,line,timestamp,a103,ab124,suutp,steady,doc_id,recipe12,recipe3)
                         VALUES (?,?,?,'unknown','unknown','unknown',NULL,?,?,?)''',
                         (gid,{'А':'A','Б':'B'}[m['line']],stamp,did,*versions)).lastrowid
                    absent=[k for k in m['needs_review'] if k not in m['values']]
                    conn.execute('INSERT INTO observation_details VALUES (?,?)',(oid,json.dumps({'source_description':m['source'],'pumps_text':m.get('pumps'),'review_without_value':absent,'migration_sha256':digest},ensure_ascii=False)))
                    for zone,pump in re.findall(r'зона (\d) — А-329/(\d)',m.get('pumps') or ''):
                        conn.execute('INSERT INTO pump_map VALUES (?,?,?)',(oid,int(zone),int(pump)))
                    record={'id':oid,'timestamp':stamp,'values':len(m['values']),'review':0,'confirmed':0,'review_without_value':absent}
                    for key,num in m['values'].items():
                        status='review' if key in m['needs_review'] else 'confirmed'
                        vid=conn.execute("INSERT INTO measurement(observation_id,param_key,number,state,status) VALUES (?,?,?,'number',?)",(oid,key,decimal_number(num),status)).lastrowid
                        for sid in source_ids:
                            conn.execute('INSERT INTO value_source VALUES (?,?)',(vid,sid))
                        record[status]+=1
                    for key in ('values','confirmed','review'): report[key]+=record[key]
                    report['observations'].append(record)
                    if absent: report['warnings'].append(stamp+': пометка сверки без числа: '+', '.join(absent))
            if conn.execute('PRAGMA foreign_key_check').fetchall():
                raise ValueError('Нарушена ссылочная целостность')
            # Verify every saved number before committing.
            for g in data['grades']:
                for m in g['measurements']:
                    saved=conn.execute('''SELECT v.param_key,v.number FROM measurement v JOIN observation o ON o.id=v.observation_id
                        JOIN grade g ON g.id=o.grade_id WHERE g.name=? AND o.timestamp=?''',(g['id'],datetime.fromisoformat(m['ts']).isoformat(timespec='seconds'))).fetchall()
                    if {r['param_key']:Decimal(str(r['number'])) for r in saved}!=m['values']:
                        raise ValueError('Проверка сохранённых чисел не пройдена')
            conn.execute('INSERT INTO migration_run VALUES (?,?,?)',(digest,now(),json.dumps(report,ensure_ascii=False)))
            conn.execute('INSERT INTO audit(created_at,action,details) VALUES (?,?,?)',(now(),'legacy_migration',json.dumps(report,ensure_ascii=False)))
        return report
    finally:
        conn.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('migration')
    parser.add_argument('prototype')
    parser.add_argument('--data-dir',default='data')
    parser.add_argument('--report',default='migration-result.json')
    args=parser.parse_args()
    report=migrate(args.migration,args.prototype,args.data_dir)
    Path(args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
