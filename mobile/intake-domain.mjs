import {validatePackage} from './domain.mjs';

export const LIMITS = {jsonBytes:5*1024*1024, batches:20, observations:100, values:10000, photoBytes:12*1024*1024, storageBytes:100*1024*1024};
const object=x=>x!==null&&typeof x==='object'&&!Array.isArray(x);
const copy=x=>JSON.parse(JSON.stringify(x));
export const rawText=x=>typeof x==='string'?x:JSON.stringify(x)??String(x);
export function parseNumber(value){
 if(typeof value==='number')return Number.isFinite(value)&&(!Number.isInteger(value)||Number.isSafeInteger(value))?{number:value,error:null}:{number:null,error:'Число вне поддерживаемого диапазона'};
 if(typeof value==='string')value=value.trim().replace(',','.');
 if(typeof value!=='string'||! /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value.trim()))return {number:null,error:'Нужен числовой формат с точкой, без единиц и других символов'};
 const number=Number(value.trim());
 return Number.isFinite(number)&&(!Number.isInteger(number)||Number.isSafeInteger(number))&&!(number===0&&/[1-9]/.test(value.trim().split(/[eE]/)[0]))?{number,error:null}:{number:null,error:'Число вне поддерживаемого диапазона'};
}
export function timestamp(value){
 if(typeof value!=='string'||! /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value))return null;
 const full=value.length===16?value+':00':value;
 const date=new Date(full+'Z');
 return Number.isFinite(date.getTime())&&date.toISOString().slice(0,19)===full?full:null;
}
export const canonicalLine=value=>({A:'A','А':'A',B:'B','Б':'B'}[value]||null);

export function parseIntake(text,filename='замер.json'){
 if(typeof text!=='string'||new TextEncoder().encode(text).length>LIMITS.jsonBytes)throw Error('JSON больше 5 МБ');
 let payload;try{payload=JSON.parse(text.replace(/^\uFEFF/,''));}catch{throw Error('Не удалось прочитать JSON: проверьте формат файла');}
 if(!object(payload)||!Array.isArray(payload.grades)||!payload.grades.length)throw Error('Нужен JSON с массивом grades → measurements');
 if(payload.extra!==undefined){if(!Array.isArray(payload.extra)||payload.grades.reduce((n,g)=>n+(g.measurements?.length||0),0)!==1)throw Error('extra в корне допустим только для одного замера');const m=payload.grades.find(g=>g.measurements?.length)?.measurements[0];if(m)m.extra=[...(m.extra||[]),...payload.extra];delete payload.extra;}
 const definitions=new Map((Array.isArray(payload.params)?payload.params:[]).filter(object).map(p=>[p.key,p]));
 let count=0;const observations=[];
 for(let gi=0;gi<payload.grades.length;gi++){
  const grade=payload.grades[gi];if(!object(grade)||!Array.isArray(grade.measurements))throw Error(`Марка ${gi+1}: нет массива measurements`);
  const gradeName=String(grade.id??grade.name??'').trim();
  for(let mi=0;mi<grade.measurements.length;mi++){
   const m=grade.measurements[mi];if(!object(m)||!object(m.values)||m.extra!==undefined&&!Array.isArray(m.extra))throw Error(`Замер ${mi+1}: нужны values и необязательный массив extra`);
   const rows=Object.entries(m.values).map(([key,raw],i)=>{const d=definitions.get(key)||{};return {id:`v${i}`,kind:'value',originalKey:key,key,label:String(d.label??d.name??key),tag:String(d.tag??''),unit:String(d.unit??''),raw,...parseNumber(raw),status:'review'};});
   for(const [ei,e] of (m.extra||[]).entries()){
    if(!object(e)||!Object.hasOwn(e,'value'))throw Error(`Замер ${mi+1}: extra ${ei+1} должен содержать value`);
    rows.push({id:`e${ei}`,kind:'extra',extraIndex:ei,key:'',label:String(e.label??e.tag??''),tag:String(e.tag??''),unit:String(e.unit??''),raw:e.value,...parseNumber(e.value),status:'review'});
   }
   count+=rows.length;if(count>LIMITS.values||observations.length>=LIMITS.observations)throw Error('В одном файле разрешено до 100 замеров и 10 000 значений');
   observations.push({id:`${gi}-${mi}`,gradeIndex:gi,measurementIndex:mi,gradeName,ts:String(m.ts??m.timestamp??''),line:canonicalLine(m.line)||'',a103:'unknown',ab124:'unknown',steady:null,recipe12:'',recipe3:'',doc:null,contextConfirmed:false,rows});
  }
 }
 if(!observations.length)throw Error('В JSON нет замеров');
 return {filename,originalText:text,payload,observations,createdAt:new Date().toISOString(),audit:[],bytes:new TextEncoder().encode(text).length};
}

export function exportIntake(batch){
 const payload=copy(batch.payload);
 for(const o of batch.observations){
  const m=payload.grades[o.gradeIndex].measurements[o.measurementIndex];
  m.ts=o.ts;m.line=o.line||m.line;
  for(const r of o.rows){
   const number=r.status==='confirmed'?r.number:parseNumber(r.raw).number;
   if(r.kind==='value')m.values[r.originalKey]=number??r.raw;
   else m.extra[r.extraIndex].value=number??r.raw;
   if(r.status==='confirmed'&&r.key!==r.originalKey)m.values[r.key]=r.number;
  }
  if(o.contextConfirmed){m.equipment={...(m.equipment||{}),a103:o.a103,ab124:o.ab124};m.steady=o.steady===null?null:!!o.steady;m.recipe12=o.recipe12;m.recipe3=o.recipe3;if(o.doc)m.doc_version=o.doc.version;}
  m.needs_review=[...new Set([...(Array.isArray(m.needs_review)?m.needs_review.filter(x=>typeof x==='string'):[]),...Object.keys(m.values)])];
  m.mobile_review={status:'requires_review_on_desktop',rows:o.rows.map(r=>({id:r.id,key:r.key,raw:r.raw,status:r.status,number:r.number,reason:r.reason||'',dictionary:r.dictionary||null})),photo:o.photo?{name:o.photo.name,sha256:o.photo.sha256}:null};
 }
 payload.mobile_intake={status:'requires_review',original_sha256:batch.sha256,imported_at:batch.createdAt,audit:copy(batch.audit)};
 return payload;
}

const empty=()=>({format:'rezhim-package',schema_version:2,version:1,built_at:'2000-01-01T00:00:00Z',counts:{grades:0,observations:0,values:0},data:Object.fromEntries(['grade','param','doc_version','norm','recipe','observation','pump_map','source','measurement','value_source'].map(k=>[k,[]]))});
export function composeMobilePackage(base,batches){
 const result=base?copy(base):empty(),d=result.data,issues=[];let applied=0;
 const next=table=>1+Math.max(0,...d[table].map(x=>x.id));
 const source=(name,sha256)=>{let s=d.source.find(s=>s.sha256===sha256);if(!s){s={id:next('source'),name,sha256};d.source.push(s);}return s.id;};
 for(const batch of batches){
  for(const o of batch.observations){
   const confirmed=o.rows.filter(r=>r.status==='confirmed');
   if(!confirmed.length)continue;
   const fail=(r,reason)=>issues.push({batchId:batch.id,observationId:o.id,rowId:r.id,reason});
   if(!o.gradeName||!canonicalLine(o.line)||!timestamp(o.ts)){for(const r of confirmed)fail(r,'Заполните марку, время и линию');continue;}
   const existing=d.observation.find(x=>x.line===canonicalLine(o.line)&&x.timestamp===timestamp(o.ts));
   let grade=d.grade.find(g=>g.name===o.gradeName);
   if(existing&&(!grade||existing.grade_id!==grade.id)){for(const r of confirmed)fail(r,'В это время на линии уже есть другая марка');continue;}
   if(!grade){grade={id:next('grade'),name:o.gradeName};d.grade.push(grade);}
   let obs=existing;
   if(!obs){obs={id:next('observation'),grade_id:grade.id,line:canonicalLine(o.line),timestamp:timestamp(o.ts),a103:'unknown',ab124:'unknown',steady:null,recipe12:'',recipe3:'',doc_id:null,suutp:'unknown',notes:'Подтверждено на телефоне'};d.observation.push(obs);}
   if(o.contextConfirmed){
    obs.a103=o.a103;obs.ab124=o.ab124;obs.steady=o.steady;obs.recipe12=o.recipe12;obs.recipe3=o.recipe3;
    obs.doc_id=o.doc?d.doc_version.find(doc=>doc.grade_id===grade.id&&doc.version===o.doc.version&&doc.valid_from===o.doc.valid_from&&obs.timestamp.slice(0,10)>=doc.valid_from&&(!doc.valid_to||obs.timestamp.slice(0,10)<=doc.valid_to))?.id??null:null;
   }
   for(const r of confirmed){
    if(!r.photo){fail(r,'Приложите фото и подтвердите значение заново');continue;}
    if(parseNumber(r.number).error||!r.key){fail(r,'Нужно число и параметр словаря');continue;}
    let param=d.param.find(p=>p.key===r.key);
    if(!param&&r.dictionary){param={...r.dictionary,key:r.key};d.param.push(param);}
    if(!param){fail(r,'Добавьте параметр в словарь');continue;}
    if(r.unit&&param.unit!==r.unit){fail(r,'Единицы не совпадают со словарём');continue;}
    let v=d.measurement.find(v=>v.observation_id===obs.id&&v.param_key===r.key);
    if(v&&v.number!==r.number&&r.replaceNumber!==v.number){fail(r,`В базе другое значение: ${v.number}. Требуется явная замена с причиной`);continue;}
    if(!v){v={id:next('measurement'),observation_id:obs.id,param_key:r.key,number:r.number,status:'confirmed',state:'number'};d.measurement.push(v);}
    else if(v.number!==r.number){v.number=r.number;d.value_source=d.value_source.filter(s=>s.value_id!==v.id);}
    const sources=[source(batch.filename,batch.sha256)];if(r.photo)sources.push(source(r.photo.name,r.photo.sha256));
    for(const id of sources)if(!d.value_source.some(s=>s.value_id===v.id&&s.source_id===id))d.value_source.push({value_id:v.id,source_id:id});
    applied++;
   }
  }
 }
 result.schema_version=2;result.counts={grades:d.grade.length,observations:d.observation.length,values:d.measurement.length};
 return {package:validatePackage(result),issues,applied};
}
