"""Apply the user's explicit 2025 observation corrections once, preserving history."""
import hashlib
import json
import sqlite3
from contextlib import closing
from datetime import datetime
from pathlib import Path

from .app import create_app, now

REQUEST = {
    'grade':'15803-020','timestamp':'2025-09-21T16:19:00','line':'A',
    'reason':'Корректировка пользователя после повторного чтения фото: ошибки ручного переноса.',
    'changes':[
        {'key':'a171_cap','old':174.80,'new':29.89,'status':'confirmed'},
        {'key':'a124_rpm','old':280.4,'new':313.9,'status':'confirmed'},
        {'key':'ab124_rpm','new':280.4,'status':'confirmed'},
        {'key':'a152_p','new':235.3,'status':'review'},
        {'key':'a171_p','new':235.8,'status':'review'},
        {'key':'c3_vgnd','old':1.438,'status':'review','reason':'Пользователь сверит по фото: 1.438 или 1.238. Число пока не изменять.'},
    ],
    'context':{'a103':'running','ab124':'running'},
}


def apply_corrections(folder='data'):
    folder=Path(folder)
    create_app(folder)
    raw=json.dumps(REQUEST,ensure_ascii=False,indent=2).encode('utf-8')
    digest=hashlib.sha256(raw).hexdigest()
    with closing(sqlite3.connect(folder/'rezhim.sqlite3')) as conn:
        conn.row_factory=sqlite3.Row
        conn.execute('PRAGMA foreign_keys=ON')
        marker='user-correction:'+digest
        if conn.execute('SELECT 1 FROM metadata WHERE key=?',(marker,)).fetchone():
            return {'already_applied':True}
        backups=folder/'backups';backups.mkdir(exist_ok=True)
        backup=backups/('before-corrections-'+datetime.now().strftime('%Y%m%d-%H%M%S-%f')+'.sqlite3')
        with closing(sqlite3.connect(backup)) as target:conn.backup(target)
        source_path=digest+'.json';(folder/'sources'/source_path).write_bytes(raw)
        with conn:
            o=conn.execute('SELECT o.* FROM observation o JOIN grade g ON g.id=o.grade_id WHERE g.name=? AND o.timestamp=? AND o.line=?',(REQUEST['grade'],REQUEST['timestamp'],REQUEST['line'])).fetchone()
            if not o:raise ValueError('Замер для исправления не найден')
            oid=o['id']
            sid=conn.execute('INSERT INTO source(name,sha256,path,kind,screen,created_at) VALUES (?,?,?,?,?,?)',('Корректировка пользователя — 21.09.2025 16-19.json',digest,source_path,'correction','user',now())).lastrowid
            def audit(key,before,after,reason):
                conn.execute('INSERT INTO audit(created_at,action,details) VALUES (?,?,?)',(now(),'user_correction',json.dumps({'observation_id':oid,'field':key,'before':before,'after':after,'reason':reason,'source_id':sid},ensure_ascii=False)))
            for change in REQUEST['changes']:
                key=change['key'];reason=change.get('reason',REQUEST['reason'])
                active=conn.execute("SELECT * FROM measurement WHERE observation_id=? AND param_key=? AND status!='rejected'",(oid,key)).fetchall()
                if 'old' in change and (len(active)!=1 or active[0]['number']!=change['old']):
                    raise ValueError('Исходное значение изменилось: '+key)
                if 'old' not in change and active:raise ValueError('Добавляемый параметр уже существует: '+key)
                if 'new' in change:
                    for previous in active:
                        conn.execute("UPDATE measurement SET status='rejected' WHERE id=?",(previous['id'],))
                        audit(key,dict(previous),{**dict(previous),'status':'rejected'},reason+' Старое число отклонено.')
                    vid=conn.execute("INSERT INTO measurement(observation_id,param_key,number,state,status) VALUES (?,?,?,'number',?)",(oid,key,change['new'],change['status'])).lastrowid
                    conn.execute('INSERT INTO value_source VALUES (?,?)',(vid,sid))
                    audit(key,None,dict(conn.execute('SELECT * FROM measurement WHERE id=?',(vid,)).fetchone()),reason)
                else:
                    previous=active[0];conn.execute('UPDATE measurement SET status=? WHERE id=?',(change['status'],previous['id']))
                    conn.execute('INSERT OR IGNORE INTO value_source VALUES (?,?)',(previous['id'],sid))
                    audit(key,dict(previous),{**dict(previous),'status':change['status']},reason)
            for key,value in REQUEST['context'].items():
                conn.execute(f'UPDATE observation SET {key}=? WHERE id=?',(value,oid))
                audit(key,o[key],value,REQUEST['reason']+' Уточнено состояние оборудования.')
            detail=conn.execute('SELECT payload FROM observation_details WHERE observation_id=?',(oid,)).fetchone()
            if detail:
                before=json.loads(detail['payload']);after={**before,'review_without_value':[k for k in before.get('review_without_value',[]) if k not in ('a152_p','a171_p')]}
                conn.execute('UPDATE observation_details SET payload=? WHERE observation_id=?',(json.dumps(after,ensure_ascii=False),oid))
                audit('review_without_value',before.get('review_without_value'),after['review_without_value'],'Числа a152_p и a171_p добавлены пользователем и оставлены на сверке.')
            conn.execute('INSERT INTO metadata VALUES (?,?)',(marker,now()))
        return {'observation_id':oid,'backup':str(backup),'changes':len(REQUEST['changes']),'context':REQUEST['context'],'steady':o['steady']}


if __name__=='__main__':
    print(json.dumps(apply_corrections(),ensure_ascii=False,indent=2))
