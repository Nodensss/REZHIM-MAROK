const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function diagnosticsHtml(d){
 return `<div class="card"><h3>Готовность профиля: ${d.eligible} из ${d.episodes.length} замеров</h3>${d.missingFilters.length?`<p class="muted">Выберите фильтры: ${esc(d.missingFilters.join(', '))}</p>`:''}${d.episodes.map(o=>`<div class="diagnostic-row"><strong>№${o.id} · ${esc(o.timestamp.replace('T',' '))}</strong>${o.reasons.length?`<ul>${o.reasons.map(r=>`<li>${esc(r)}</li>`).join('')}</ul>`:'<p>Готов к профилю по выбранным признакам</p>'}</div>`).join('')||'<p>У марки нет замеров</p>'}</div>`;
}
export function cardDetails(data,id,includeNorms=false){
 const details=(data.doc_details||[]).find(d=>d.doc_id===id);
 const recipe=details?.recipe;
 const norms=includeNorms?`<h3>Нормы</h3><div class="table-wrap"><table><thead><tr><th>Параметр</th><th>НГС</th><th>Цель</th><th>ВГС</th></tr></thead><tbody>${data.norm.filter(n=>n.doc_id===id).map(n=>`<tr><td>${esc(data.param.find(p=>p.key===n.param_key)?.name||n.param_key)}</td><td>${esc(n.lower_value??'—')}</td><td>${esc(n.target??'—')}</td><td>${esc(n.upper_value??'—')}</td></tr>`).join('')}</tbody></table></div>`:'';
 if(!details)return norms;
 return `<details class="card-details"><summary>Спецификация и рецептура</summary><p class="muted">${esc(details.title)}<br>${esc(details.date_basis)}</p>${norms}<h3>Спецификация</h3>${(details.spec||[]).map(([name,value])=>`<div class="row"><span>${esc(name)}</span><span>${esc(value)}</span></div>`).join('')}${(recipe||[]).map(block=>`<h3>${esc(block.zone)}</h3><div class="table-wrap"><table><thead><tr><th>Компонент</th><th>%</th><th>кг</th><th>шт.</th></tr></thead><tbody>${block.rows.map(row=>`<tr>${row.map(value=>`<td>${esc(value)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`).join('')}<h3>Примечания карты</h3>${(details.card_notes||[]).map(t=>`<p class="muted">${esc(t)}</p>`).join('')}</details>`;
}
