export function validatePackage(p){
 if(!p||p.format!=='rezhim-package'||![1,2].includes(p.schema_version)||!Number.isSafeInteger(p.version)||p.version<1||typeof p.built_at!=='string'||!Number.isFinite(Date.parse(p.built_at)))throw Error('Неподдерживаемый формат или версия пакета');
 const d=p.data, tables=['grade','param','doc_version','norm','recipe','observation','pump_map','source','measurement','value_source'];
 if(!d||tables.some(t=>!Array.isArray(d[t])))throw Error('Неполный пакет');
 const maps={};
 for(const t of ['grade','doc_version','norm','recipe','observation','source','measurement']){maps[t]=new Map();for(const row of d[t]){if(!row||!Number.isSafeInteger(row.id)||row.id<1||maps[t].has(row.id))throw Error('Повторный или неверный ID: '+t);maps[t].set(row.id,row);}}
 maps.param=new Map();for(const x of d.param){if(typeof x.key!=='string'||maps.param.has(x.key)||typeof x.name!=='string'||typeof x.unit!=='string'||!Number.isInteger(x.precision)||x.precision<0||x.precision>6)throw Error('Неверный справочник параметров');maps.param.set(x.key,x);}
 for(const x of d.grade)if(typeof x.name!=='string'||!x.name.trim())throw Error('Неверная марка');
 for(const x of d.source)if(typeof x.name!=='string'||! /^[a-f0-9]{64}$/.test(x.sha256))throw Error('Неверный источник');
 const episodes=new Set();for(const o of d.observation){if(!maps.grade.has(o.grade_id)||!['A','B'].includes(o.line)||!['running','stopped','unknown'].includes(o.a103)||!['running','stopped','unknown'].includes(o.ab124)||![null,0,1].includes(o.steady)||typeof o.recipe12!=='string'||typeof o.recipe3!=='string'||typeof o.timestamp!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(o.timestamp)||!Number.isFinite(Date.parse(o.timestamp)))throw Error('Неверные реквизиты замера');const key=o.line+'|'+o.timestamp;if(episodes.has(key))throw Error('Повторный эпизод');episodes.add(key);if(o.doc_id!==null){const doc=maps.doc_version.get(o.doc_id);if(!doc||doc.grade_id!==o.grade_id||o.timestamp.slice(0,10)<doc.valid_from||doc.valid_to&&o.timestamp.slice(0,10)>doc.valid_to)throw Error('Техкарта не соответствует замеру');}}
 for(const doc of d.doc_version)if(!maps.grade.has(doc.grade_id)||!maps.source.has(doc.source_id)||typeof doc.version!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(doc.valid_from)||doc.valid_to!==null&&(!/^\d{4}-\d{2}-\d{2}$/.test(doc.valid_to)||doc.valid_to<doc.valid_from))throw Error('Неверная версия техкарты');
 const normKeys=new Set();for(const n of d.norm){const key=n.doc_id+'|'+n.param_key;if(normKeys.has(key)||!maps.doc_version.has(n.doc_id)||!maps.param.has(n.param_key)||['lower_value','target','upper_value'].some(k=>n[k]!==null&&(typeof n[k]!=='number'||!Number.isFinite(n[k])))||n.lower_value!==null&&n.upper_value!==null&&n.lower_value>n.upper_value)throw Error('Неверная норма');normKeys.add(key);}
 for(const v of d.measurement){if(!maps.observation.has(v.observation_id)||!maps.param.has(v.param_key)||!['confirmed','review','rejected'].includes(v.status)||!['number','missing','error'].includes(v.state)||(v.state==='number'&&(typeof v.number!=='number'||!Number.isFinite(v.number)))||(v.state!=='number'&&v.number!==null))throw Error('Неверное значение в пакете');}
 // Review/rejected values and gaps are allowed only in the full base (includes_unreviewed); the reference package holds confirmed numbers only.
 const full=p.includes_unreviewed===true,confirmedKeys=new Set();for(const v of d.measurement){if(v.status!=='confirmed'){if(!full)throw Error('Непроверенное значение в пакете справочника');continue;}if(v.state!=='number')throw Error('Подтверждён пропуск или ошибка');const key=v.observation_id+'|'+v.param_key;if(confirmedKeys.has(key))throw Error('Конфликтующие подтверждённые числа');confirmedKeys.add(key);}
 const sourced=new Set();for(const s of d.value_source){if(!maps.measurement.has(s.value_id)||!maps.source.has(s.source_id))throw Error('Разорванная ссылка на источник');sourced.add(s.value_id);}if(d.measurement.some(v=>!sourced.has(v.id)))throw Error('Число без источника');
 const zones=new Set(),pumps=new Set();for(const p of d.pump_map){const z=p.observation_id+'|'+p.zone,k=p.observation_id+'|'+p.pump;if(!maps.observation.has(p.observation_id)||![1,2,3].includes(p.zone)||![1,2,3,4].includes(p.pump)||zones.has(z)||pumps.has(k))throw Error('Неверная расстановка насосов');zones.add(z);pumps.add(k);}
 for(const r of d.recipe)if(!maps.doc_version.has(r.doc_id))throw Error('Рецептура без техкарты');
 if(!p.counts||p.counts.grades!==d.grade.length||p.counts.observations!==d.observation.length||p.counts.values!==d.measurement.length)throw Error('Счётчики пакета не совпадают');return p;
}
export function quantile(sorted,p){const index=(sorted.length-1)*p,lo=Math.floor(index);return sorted[lo]+(sorted[Math.ceil(index)]-sorted[lo])*(index-lo);}
export function profileDiagnostics(data,filter){
 const labels={line:'Линия',a103:'А-103',ab124:'АБ-124',doc_id:'Техкарта',recipe12:'Рецептура зон 1–2',recipe3:'Рецептура зоны 3'};
 const missingFilters=Object.entries(labels).filter(([k])=>!filter[k]).map(([,v])=>v);
 const episodes=data.observation.filter(o=>o.grade_id===filter.grade_id).map(o=>{
  const reasons=[];
  if(o.a103==='unknown')reasons.push('Не заполнено состояние А-103');
  if(o.ab124==='unknown')reasons.push('Не заполнено состояние АБ-124');
  if(o.steady===null)reasons.push('Не указан признак устойчивого режима');
  else if(o.steady!==1)reasons.push('Указан переход, а не устойчивый режим');
  for(const k of ['doc_id','recipe12','recipe3'])if(!o[k])reasons.push('Не указано: '+labels[k]);
  for(const [k,label] of Object.entries(labels))if(filter[k]&&o[k]!==filter[k]&&o[k]!=='unknown')reasons.push(label+': не совпадает с фильтром');
  if(!data.measurement.some(v=>v.observation_id===o.id&&v.status==='confirmed'&&v.state==='number'))reasons.push('Нет подтверждённых чисел');
  return {id:o.id,timestamp:o.timestamp,reasons,eligible:reasons.length===0};
 });
 return {missingFilters,episodes,eligible:episodes.filter(o=>o.eligible).length};
}
export function profile(data,filter){
 // No inferred equipment state, recipe compatibility or cross-line pooling.
 if(!filter.grade_id||!['A','B'].includes(filter.line)||!['running','stopped'].includes(filter.a103)||!['running','stopped'].includes(filter.ab124)||!filter.recipe12||!filter.recipe3||!filter.doc_id)return [];
 const observations=data.observation.filter(o=>o.grade_id===filter.grade_id&&o.line===filter.line&&o.a103===filter.a103&&o.ab124===filter.ab124&&o.recipe12===filter.recipe12&&o.recipe3===filter.recipe3&&o.doc_id===filter.doc_id&&o.steady===1);
 return data.param.flatMap(param=>{const vals=data.measurement.filter(v=>v.param_key===param.key&&v.status==='confirmed'&&v.state==='number'&&observations.some(o=>o.id===v.observation_id));if(!vals.length)return [];const sorted=vals.map(v=>v.number).sort((a,b)=>a-b),loads=data.measurement.filter(v=>['production','prod'].includes(v.param_key)&&v.status==='confirmed'&&v.state==='number'&&vals.some(x=>x.observation_id===v.observation_id)).map(v=>v.number);const norm=data.norm.find(n=>n.doc_id===filter.doc_id&&n.param_key===param.key);const median=quantile(sorted,.5);return [{param,median,min:sorted[0],max:sorted.at(-1),q25:quantile(sorted,.25),q75:quantile(sorted,.75),n:vals.length,load:loads.length?[Math.min(...loads),Math.max(...loads)]:null,observation_ids:vals.map(v=>v.observation_id),norm,outside:!!norm&&(norm.lower_value!==null&&median<norm.lower_value||norm.upper_value!==null&&median>norm.upper_value)}];});
}
