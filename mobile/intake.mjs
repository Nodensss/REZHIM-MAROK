import {LIMITS,parseIntake,parseNumber,rawText,timestamp,canonicalLine,exportIntake,composeMobilePackage} from './intake-domain.mjs';
export {composeMobilePackage} from './intake-domain.mjs';
const esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const clone=x=>JSON.parse(JSON.stringify(x));
const option=(value,label,selected)=>`<option value="${esc(value)}" ${value===selected?'selected':''}>${esc(label)}</option>`;

// Web Crypto is available for installed HTTPS apps and localhost. This fallback
// also permits testing intake over a private LAN HTTP address.
export async function sha256(buffer){
 if(globalThis.crypto?.subtle)return [...new Uint8Array(await crypto.subtle.digest('SHA-256',buffer))].map(x=>x.toString(16).padStart(2,'0')).join('');
 const input=new Uint8Array(buffer),length=Math.ceil((input.length+9)/64)*64,data=new Uint8Array(length);data.set(input);data[input.length]=128;
 const view=new DataView(data.buffer);view.setUint32(length-8,Math.floor(input.length/0x20000000));view.setUint32(length-4,(input.length*8)>>>0);
 const primes=[];for(let n=2;primes.length<64;n++)if(!primes.some(p=>p*p<=n&&n%p===0))primes.push(n);
 const h=primes.slice(0,8).map(p=>(Math.sqrt(p)%1*0x100000000)>>>0),k=primes.map(p=>(Math.cbrt(p)%1*0x100000000)>>>0),w=new Uint32Array(64),rotr=(x,n)=>(x>>>n)|(x<<(32-n));
 for(let offset=0;offset<length;offset+=64){
  for(let i=0;i<16;i++)w[i]=view.getUint32(offset+i*4);
  for(let i=16;i<64;i++){const a=w[i-15],b=w[i-2];w[i]=(w[i-16]+(rotr(a,7)^rotr(a,18)^(a>>>3))+w[i-7]+(rotr(b,17)^rotr(b,19)^(b>>>10)))>>>0;}
  let [a,b,c,d,e,f,g,z]=h;
  for(let i=0;i<64;i++){const t1=(z+(rotr(e,6)^rotr(e,11)^rotr(e,25))+((e&f)^(~e&g))+k[i]+w[i])>>>0,t2=((rotr(a,2)^rotr(a,13)^rotr(a,22))+((a&b)^(a&c)^(b&c)))>>>0;z=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b;b=a;a=(t1+t2)>>>0;}
  [a,b,c,d,e,f,g,z].forEach((n,i)=>h[i]=(h[i]+n)>>>0);
 }
 return h.map(n=>n.toString(16).padStart(8,'0')).join('');
}

export async function startIntake({getBasePackage,onChange}){
 const root=document.querySelector('#intake');if(!root)return {batches:[],refresh(){}};
 const notice=root.querySelector('[data-intake-notice]'),content=root.querySelector('[data-intake-content]'),fileInput=root.querySelector('#intake-file');
 const state={batches:[],refresh:render};let selected=null,obsIndex=0,busy=false,photoURL=null;
 const db=await new Promise((resolve,reject)=>{const r=indexedDB.open('rezhim-mobile-intake',1);r.onupgradeneeded=()=>{r.result.createObjectStore('batches',{keyPath:'id'});r.result.createObjectStore('photos',{keyPath:'id'});};r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
 db.onversionchange=()=>db.close();
 const read=(table,key)=>new Promise((resolve,reject)=>{const r=db.transaction(table).objectStore(table)[key===undefined?'getAll':'get'](key);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
 const write=(batch,photo)=>new Promise((resolve,reject)=>{const tx=db.transaction(['batches','photos'],'readwrite');tx.objectStore('batches').put(batch);if(photo)tx.objectStore('photos').put(photo);tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error||Error('Сохранение прервано'));});
 const message=(text,error=false)=>{notice.hidden=false;notice.textContent=text;notice.className=error?'intake-message error':'intake-message';};
 const photosOf=b=>[...new Map([...(b.photos||[]),...b.observations.flatMap(o=>[o.photo,...o.rows.map(r=>r.photo)].filter(Boolean))].map(p=>[p.id,p])).values()];
 const bytesOf=b=>b.bytes+photosOf(b).reduce((n,p)=>n+p.bytes,0);
 const active=()=>state.batches.find(b=>b.id===selected);
 const composed=()=>composeMobilePackage(getBasePackage(),state.batches);
 const save=async(next,photo)=>{await write(next,photo);const index=state.batches.findIndex(b=>b.id===next.id);if(index<0)state.batches.push(next);else state.batches[index]=next;onChange();render();};
 const audit=(batch,action,details,reason)=>batch.audit.push({at:new Date().toISOString(),action,...details,reason});
 const download=(blob,name)=>{const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
 const lock=()=>{busy=true;root.querySelectorAll('button,input,select').forEach(x=>x.disabled=true);};
 const unlock=()=>{busy=false;render();};

 async function renderPhoto(batch,o){
  if(photoURL){URL.revokeObjectURL(photoURL);photoURL=null;}
  if(!o.photo)return;
  const stored=await read('photos',o.photo.id);if(active()?.id!==batch.id||active()?.observations[obsIndex]?.id!==o.id)return;
  const image=root.querySelector('[data-intake-photo]');if(stored&&image){photoURL=URL.createObjectURL(stored.blob);image.src=photoURL;image.hidden=false;}
 }
 function render(){
  fileInput.disabled=busy;
  const batch=active(),allRows=state.batches.flatMap(b=>b.observations.flatMap(o=>o.rows));
  root.querySelector('[data-intake-counts]').textContent=`Файлов: ${state.batches.length} · требуют сверки: ${allRows.filter(r=>r.status==='review').length} · подтверждено: ${allRows.filter(r=>r.status==='confirmed').length}`;
  root.querySelector('[data-intake-batches]').innerHTML=state.batches.map(b=>`<button class="${b.id===selected?'':'secondary'}" data-batch="${esc(b.id)}">${esc(b.filename)} · ${b.observations.length} зам.</button>`).join('');
  if(!batch){content.innerHTML='<p class="muted">Выберите JSON после распознавания изображения. Он сохранится на этом устройстве; все значения начнут со статуса «Требует сверки».</p>';return;}
  const o=batch.observations[obsIndex]||batch.observations[0],model=composed(),d=model.package.data,grade=d.grade.find(g=>g.name===o.gradeName),ts=timestamp(o.ts);
  const existing=d.observation.find(x=>x.timestamp===ts&&x.line===canonicalLine(o.line));
  const docs=d.doc_version.filter(doc=>doc.grade_id===grade?.id&&ts&&ts.slice(0,10)>=doc.valid_from&&(!doc.valid_to||ts.slice(0,10)<=doc.valid_to));
  const docValue=doc=>JSON.stringify({version:doc.version,valid_from:doc.valid_from});
  content.innerHTML=`<div class="intake-actions"><button data-download="review">JSON для ПК</button><button class="secondary" data-download="original">Исходный JSON</button><button class="secondary" data-action="delete">Удалить файл с устройства</button></div><p class="muted">JSON для ПК содержит исходные поля, исправления и историю сверки. На ПК все импортированные кандидаты снова требуют проверки. Фотографию передайте отдельно.</p><label>Замер<select id="intake-observation">${batch.observations.map((m,i)=>option(String(i),`${m.gradeName||'Марка не указана'} · ${m.ts||'Время не указано'} · ${m.line||'Линия не указана'}`,String(obsIndex))).join('')}</select></label><div class="intake-workspace"><aside class="intake-photo"><label class="upload">Фото для сверки<input id="intake-photo-file" type="file" accept="image/jpeg,image/png,image/webp"></label><p class="muted">${esc(o.photo?.name||'Фото ещё не приложено. Выберите исходный снимок.')} · до 12 МБ</p><img data-intake-photo hidden alt="Исходное фото для сверки замера">${o.photo?'<button class="secondary" data-download="photo">Сохранить фото</button>':''}</aside><div class="intake-fields"><details class="card" ${!o.contextConfirmed?'open':''}><summary>Признаки замера и готовность профиля</summary><form id="intake-context"><div class="grid"><label>Марка<input name="gradeName" required maxlength="120" value="${esc(o.gradeName)}"></label><label>Дата и время<input type="datetime-local" step="1" name="ts" required value="${esc(ts||o.ts)}"></label><label>Линия<select name="line">${option('','Выберите',o.line)}${option('A','А',o.line)}${option('B','Б',o.line)}</select></label><label>А-103<select name="a103">${[['unknown','Не заполнено'],['running','Работает'],['stopped','Остановлен']].map(([v,t])=>option(v,t,o.a103)).join('')}</select></label><label>АБ-124<select name="ab124">${[['unknown','Не заполнено'],['running','Работает'],['stopped','Остановлен']].map(([v,t])=>option(v,t,o.ab124)).join('')}</select></label><label>Устойчивый режим<select name="steady">${option('','Не заполнено',o.steady===null?'':String(o.steady))}${option('1','Да',String(o.steady))}${option('0','Нет, переход',String(o.steady))}</select></label><label>Техкарта<select name="doc">${option('','Не выбрана',o.doc?docValue(o.doc):'')}${docs.map(doc=>option(docValue(doc),doc.version,o.doc?docValue(o.doc):'')).join('')}</select></label><label>Рецептура зон 1–2<input name="recipe12" list="intake-recipes12" maxlength="200" value="${esc(o.recipe12)}"></label><label>Рецептура зоны 3<input name="recipe3" list="intake-recipes3" maxlength="200" value="${esc(o.recipe3)}"></label></div><label>Причина изменения<input name="reason" maxlength="500" placeholder="Сверка с фото / уточнение признаков"></label><button>Сохранить признаки</button></form><p class="muted">${existing?'Замер с этой линией и временем уже есть: подтверждение дополняет его.':''} ${docs.length?'Для профиля задайте состояния, устойчивость, карту и обе рецептуры.':'Для профиля нужна подходящая техкарта: импортируйте пакет проверенной базы на вкладке «База».'}</p><details><summary>Реквизиты из исходного JSON</summary><pre>${esc(JSON.stringify({...batch.payload.grades[o.gradeIndex].measurements[o.measurementIndex],values:undefined,extra:undefined},null,2))}</pre></details></details><h3>Сверка значений · ${o.rows.length}</h3><p class="muted">Сверьте каждое значение с фото. При добавлении нового параметра укажите ключ, название и единицы. Подтверждённые поля попадут в профиль при заполненных признаках.</p>${o.rows.map(r=>{
   const param=d.param.find(p=>p.key===r.key),old=d.measurement.find(v=>v.observation_id===existing?.id&&v.param_key===r.key),issue=model.issues.find(i=>i.batchId===batch.id&&i.observationId===o.id&&i.rowId===r.id);
   return `<form class="intake-row ${r.status}" data-row="${r.id}"><div class="intake-row-heading"><strong>${esc(param?.name||r.label||r.tag||r.originalKey||'Новый параметр')}</strong><span class="tag">${r.status==='confirmed'?(issue?'Конфликт':'Подтверждено'):r.status==='rejected'?'Отклонено':'Требует сверки'}</span></div><p class="source">${r.kind==='extra'?'EXTRA · ':''}${esc(r.tag||r.originalKey||'Без тега')} · исходное значение: <b>${esc(rawText(r.raw))}</b> ${esc(r.unit)}</p>${issue?`<p class="warning">${esc(issue.reason)}</p>`:''}${r.error&&r.status!=='confirmed'?`<p class="warning">${esc(r.error)}</p>`:''}${old?`<p class="muted">Сейчас в базе: ${esc(old.number)} ${esc(param?.unit||'')}</p>`:''}<div class="grid"><label>Ключ параметра<input name="key" list="intake-param-keys" maxlength="61" value="${esc(r.key)}" placeholder="например, a124_rpm" required></label><label>Проверенное число<input name="number" inputmode="decimal" value="${esc(r.number??(typeof r.raw==='string'?r.raw:''))}" required></label><label>Название для нового параметра<input name="label" maxlength="200" value="${esc(r.dictionary?.name||r.label)}"></label><label>Единицы для нового параметра<input name="unit" maxlength="60" value="${esc(r.dictionary?.unit??r.unit)}"></label></div><label>Причина исправления / решения<input name="reason" maxlength="500" value="${esc(r.reason||'')}" placeholder="Обязательно при изменении числа или замене в базе"></label><div class="intake-actions"><button name="decision" value="confirmed">Подтвердить</button><button name="decision" value="rejected" class="secondary" formnovalidate>Отклонить</button>${r.status!=='review'?'<button name="decision" value="review" class="secondary" formnovalidate>Вернуть на сверку</button>':''}</div></form>`;
  }).join('')}</div></div><details class="card"><summary>Журнал решений · ${batch.audit.length}</summary>${batch.audit.slice().reverse().map(a=>`<p class="source">${esc(a.at)} · ${esc(a.action)} · ${esc(a.key||a.observationId||'')}<br>${esc(a.reason)}${a.before!==undefined?`<br>Было: ${esc(rawText(a.before))} → ${esc(rawText(a.after))}`:''}</p>`).join('')||'<p class="muted">Решений ещё нет.</p>'}</details><datalist id="intake-param-keys">${d.param.map(p=>`<option value="${esc(p.key)}">${esc(p.name)} · ${esc(p.unit)}</option>`).join('')}</datalist>${['12','3'].map(s=>`<datalist id="intake-recipes${s}">${[...new Set(d.observation.filter(x=>x.grade_id===grade?.id).map(x=>x['recipe'+s]).filter(Boolean))].map(v=>`<option value="${esc(v)}"></option>`).join('')}</datalist>`).join('')}`;
  if(busy)root.querySelectorAll('button,input,select').forEach(x=>x.disabled=true);
  renderPhoto(batch,o).catch(e=>message('Не удалось открыть фото: '+e.message,true));
 }

 fileInput.onchange=async()=>{
  const file=fileInput.files[0];if(!file||busy)return;lock();
  try{
   if(file.size>LIMITS.jsonBytes)throw Error('JSON больше 5 МБ');
   if(state.batches.length>=LIMITS.batches)throw Error('Уже сохранено 20 файлов. Выгрузите и удалите обработанные файлы');
   const batch=parseIntake(await file.text(),file.name),hash=await sha256(new TextEncoder().encode(batch.originalText));
   const duplicate=state.batches.find(b=>b.sha256===hash);if(duplicate){selected=duplicate.id;obsIndex=0;message('Этот исходный файл уже принят. Открыт сохранённый результат сверки.');return;}
   if(state.batches.reduce((n,b)=>n+bytesOf(b),0)+batch.bytes>LIMITS.storageBytes)throw Error('Лимит входящих 100 МБ. Выгрузите и удалите обработанные файлы');
   batch.id=globalThis.crypto?.randomUUID?.()||`${Date.now()}-${Math.random().toString(36).slice(2)}`;batch.sha256=hash;selected=batch.id;obsIndex=0;await save(batch);message(`Принято ${batch.observations.length} замеров. Все ${batch.observations.reduce((n,o)=>n+o.rows.length,0)} значения требуют сверки.`);navigator.storage?.persist?.().catch(()=>{});
  }catch(e){message('Импорт не выполнен: '+e.message,true);}finally{fileInput.value='';unlock();}
 };
 root.addEventListener('change',async event=>{
  if(event.target.id==='intake-observation'){obsIndex=Number(event.target.value);render();return;}
  if(event.target.id!=='intake-photo-file'||busy)return;
  const file=event.target.files[0];if(!file)return;lock();
  try{
   if(!['image/jpeg','image/png','image/webp'].includes(file.type)||file.size>LIMITS.photoBytes)throw Error('Нужна фотография JPEG, PNG или WebP до 12 МБ');
   const next=clone(active()),o=next.observations[obsIndex],used=state.batches.reduce((n,b)=>n+bytesOf(b),0);
   if(used+file.size>LIMITS.storageBytes)throw Error('Лимит входящих 100 МБ');
   const hash=await sha256(await file.arrayBuffer()),id=`${next.id}:${o.id}:${hash}`;
   audit(next,'Фото для сверки',{observationId:o.id,before:o.photo||null,after:{name:file.name,sha256:hash}},'Пользователь приложил фото');
   o.photo={id,name:file.name,sha256:hash,bytes:file.size};next.photos=[...photosOf(next)];await save(next,{id,blob:file});message('Фото сохранено на устройстве и будет доступно без сети.');
  }catch(e){message('Фото не сохранено: '+e.message,true);}finally{unlock();}
 });
 root.addEventListener('submit',async event=>{
  const form=event.target;if(form.id!=='intake-context'&&!form.matches('[data-row]'))return;event.preventDefault();if(busy)return;
  const fields=Object.fromEntries(new FormData(form)),decision=event.submitter?.value||'confirmed';lock();
  try{
   const next=clone(active()),o=next.observations[obsIndex];
   if(form.id==='intake-context'){
    if(!fields.gradeName.trim()||!timestamp(fields.ts)||!canonicalLine(fields.line))throw Error('Заполните марку, корректное время и линию');
    if(o.rows.some(r=>r.status==='confirmed')&&(fields.gradeName.trim()!==o.gradeName||timestamp(fields.ts)!==timestamp(o.ts)||fields.line!==o.line))throw Error('Перед изменением марки, времени или линии верните подтверждённые поля на сверку');
    const before={grade:o.gradeName,ts:o.ts,line:o.line,a103:o.a103,ab124:o.ab124,steady:o.steady,recipe12:o.recipe12,recipe3:o.recipe3,doc:o.doc};
    Object.assign(o,{gradeName:fields.gradeName.trim(),ts:timestamp(fields.ts),line:fields.line,a103:fields.a103,ab124:fields.ab124,steady:fields.steady===''?null:Number(fields.steady),recipe12:fields.recipe12.trim(),recipe3:fields.recipe3.trim(),doc:fields.doc?JSON.parse(fields.doc):null,contextConfirmed:true});
    audit(next,'Признаки замера',{observationId:o.id,before,after:{grade:o.gradeName,ts:o.ts,line:o.line,a103:o.a103,ab124:o.ab124,steady:o.steady,recipe12:o.recipe12,recipe3:o.recipe3,doc:o.doc}},fields.reason.trim()||'Признаки указаны пользователем на телефоне');
   }else{
    const r=o.rows.find(r=>r.id===form.dataset.row),before={status:r.status,key:r.key,number:r.number};
    if(decision==='confirmed'){
     if(!o.photo)throw Error('Приложите исходное фото перед подтверждением');
     const parsed=parseNumber(fields.number);if(parsed.error)throw Error(parsed.error);
     if(!/^[a-z][a-z0-9_]{0,60}$/.test(fields.key))throw Error('Ключ: латинские строчные буквы, цифры и подчёркивание; начинается с буквы');
     if(!o.gradeName||!timestamp(o.ts)||!canonicalLine(o.line))throw Error('Сначала заполните марку, время и линию в признаках замера');
     const d=composed().package.data,obs=d.observation.find(x=>x.timestamp===timestamp(o.ts)&&x.line===canonicalLine(o.line)),old=d.measurement.find(v=>v.observation_id===obs?.id&&v.param_key===fields.key),param=d.param.find(p=>p.key===fields.key);
     if((parseNumber(r.raw).number!==parsed.number||old&&old.number!==parsed.number)&&!fields.reason.trim())throw Error('Укажите причину исправления числа или замены существующего значения');
     if(d.param.some(p=>p.key===r.originalKey)&&fields.key!==r.originalKey)throw Error('Известный параметр нельзя переназначить');
     if(param&&r.unit&&r.unit!==param.unit)throw Error('Единицы исходного значения не совпадают со словарём');
     if(!param&&!fields.unit.trim())throw Error('Укажите единицы нового параметра');
     if(!param&&!fields.label.trim())throw Error('Укажите название нового параметра для словаря');
     if(old&&old.number!==parsed.number&&!confirm(`В замере уже ${old.number}; заменить на ${parsed.number}? Причина: ${fields.reason}`))return;
     Object.assign(r,{photo:clone(o.photo),key:fields.key,number:parsed.number,error:null,status:'confirmed',dictionary:param?null:{name:fields.label.trim(),unit:fields.unit.trim(),precision:Math.min(6,(String(parsed.number).split('.')[1]||'').length)},reason:fields.reason.trim()||'Сверено пользователем на телефоне'});
     if(old&&old.number!==parsed.number)r.replaceNumber=old.number;
    }else{r.status=decision;r.reason=fields.reason.trim()||(decision==='rejected'?'Отклонено пользователем':'Возвращено на сверку пользователем');}
    audit(next,decision==='confirmed'?'Подтверждение':decision==='rejected'?'Отклонение':'На сверку',{observationId:o.id,key:r.key,rowId:r.id,before,after:{status:r.status,key:r.key,number:r.number}},r.reason);
   }
   composeMobilePackage(getBasePackage(),state.batches.map(b=>b.id===next.id?next:b));
   await save(next);message('Решение сохранено. Подтверждённые значения учтены в справочнике; готовность профиля зависит от признаков.');
  }catch(e){message('Не сохранено: '+e.message,true);}finally{unlock();}
 });
 root.addEventListener('click',async event=>{
  const button=event.target.closest('button');if(!button||busy)return;
  if(button.dataset.batch){selected=button.dataset.batch;obsIndex=0;render();return;}
  const batch=active();if(!batch)return;
  try{
   if(button.dataset.download==='original')download(new Blob([batch.originalText],{type:'application/json'}),batch.filename);
   if(button.dataset.download==='review')download(new Blob([JSON.stringify(exportIntake(batch),null,2)],{type:'application/json'}),'review-'+batch.filename);
   if(button.dataset.download==='photo'){const o=batch.observations[obsIndex],p=await read('photos',o.photo.id);if(p)download(p.blob,o.photo.name);}
   if(button.dataset.action==='delete'&&confirm('Удалить этот входящий файл, фото и локальные решения? Подтверждения из него исчезнут из мобильного профиля. Исходный пакет ПК сохранится.')){
    await new Promise((resolve,reject)=>{const tx=db.transaction(['batches','photos'],'readwrite');tx.objectStore('batches').delete(batch.id);for(const p of photosOf(batch))tx.objectStore('photos').delete(p.id);tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);});
    state.batches=state.batches.filter(b=>b.id!==batch.id);selected=state.batches.at(-1)?.id||null;obsIndex=0;onChange();render();message('Входящий файл удалён с этого устройства.');
   }
  }catch(e){message(e.message,true);}
 });
 state.batches=(await read('batches')).sort((a,b)=>a.createdAt.localeCompare(b.createdAt));selected=state.batches.at(-1)?.id||null;render();return state;
}
