'use strict';
const $=id=>document.getElementById(id);
const api=()=>window.NHL_CONFIG?.apiBase?.replace(/\/$/,'');
const states={PREFILLING:'Načítavam štatistiky',COLLECTING:'Zber dát',QUEUED:'Čaká na výpočet',RUNNING:'Prebieha výpočet',COMPLETE:'Spracované',INCOMPLETE:'Neúplné dáta',ERROR:'Spracovanie zlyhalo',NO_GAMES:'Bez zápasov'};
const resultLabels={NO_BET:'Bez kandidáta podľa filtrov',CANDIDATE_REVIEW_REQUIRED:'Kandidát na manuálnu kontrolu',INSUFFICIENT_DATA:'Chýbajú kľúčové vstupy',CHECKLIST_INCOMPLETE:'Checklist nie je dokončený',INVALID_INPUT:'Vstupy neprešli validáciou',SYNTHETIC_DEMO_ONLY:'Syntetická ukážka'};
const teamNames={ANA:'Ducks',BOS:'Bruins',BUF:'Sabres',CAR:'Hurricanes',CBJ:'Blue Jackets',CGY:'Flames',CHI:'Blackhawks',COL:'Avalanche',DAL:'Stars',DET:'Red Wings',EDM:'Oilers',FLA:'Panthers',LAK:'Kings',MIN:'Wild',MTL:'Canadiens',NJD:'Devils',NSH:'Predators',NYI:'Islanders',NYR:'Rangers',OTT:'Senators',PHI:'Flyers',PIT:'Penguins',SEA:'Kraken',SJS:'Sharks',STL:'Blues',TBL:'Lightning',TOR:'Maple Leafs',UTA:'Mammoth',VAN:'Canucks',VGK:'Golden Knights',WPG:'Jets',WSH:'Capitals'};
const teamLocations={ANA:'ANAHEIM',BOS:'BOSTON',BUF:'BUFFALO',CAR:'CAROLINA',CBJ:'COLUMBUS',CGY:'CALGARY',CHI:'CHICAGO',COL:'COLORADO',DAL:'DALLAS',DET:'DETROIT',EDM:'EDMONTON',FLA:'FLORIDA',LAK:'LOS ANGELES',MIN:'MINNESOTA',MTL:'MONTRÉAL',NJD:'NEW JERSEY',NSH:'NASHVILLE',NYI:'NEW YORK',NYR:'NEW YORK',OTT:'OTTAWA',PHI:'PHILADELPHIA',PIT:'PITTSBURGH',SEA:'SEATTLE',SJS:'SAN JOSE',STL:'ST. LOUIS',TBL:'TAMPA BAY',TOR:'TORONTO',UTA:'UTAH',VAN:'VANCOUVER',VGK:'VEGAS',WPG:'WINNIPEG',WSH:'WASHINGTON'};
let current=null,serial=0,poll;
const POLL_INTERVAL=5*60*1000;
const statFields={games:'Odohrané zápasy',points_pct:'Bodová úspešnosť (%)',xgf_5v5_per60:'xG za 60 min · 5 na 5',xga_5v5_per60:'xG súpera za 60 min · 5 na 5',pp_xgf_per60:'xG za 60 min · presilovka',pk_xga_per60:'xG súpera za 60 min · oslabenie',pp_drawn_per_game:'Vynútené tresty na zápas',penalties_taken_per_game:'Tresty na zápas',shots_for_per_game:'Strely na zápas',shots_against_per_game:'Strely súpera na zápas',high_danger_for_per60:'Veľké šance za 60 min',high_danger_against_per60:'Veľké šance súpera za 60 min'};
const fieldGroups={prior:{label:'Minulá sezóna',fields:statFields},current:{label:'Aktuálna sezóna',fields:statFields},goalie:{label:'Brankár',fields:{name:'Meno',status:'Potvrdenie brankára',starts_current:'Štarty · aktuálna sezóna',starts_prior:'Štarty · minulá sezóna',gsax_per60_current:'GSAx za 60 min · aktuálna sezóna',gsax_per60_prior:'GSAx za 60 min · minulá sezóna'}},roster:{label:'Zostava',fields:{quantified:'Vyčíslený vplyv zostavy',attack_delta_goals:'Vplyv na strelené góly',defense_delta_goals_allowed:'Vplyv na inkasované góly',absences:'Absencie',returns:'Návraty',scratches:'Vynechaní hráči',forward_lines:'Útočné formácie',defense_pairs:'Obranné dvojice',power_play_units:'Presilovkové jednotky'}},context:{label:'Oddych a cestovanie',fields:{rest_days:'Dni oddychu',travel_timezones:'Prekonané časové pásma'}}};
const criticalStats=new Set(['games','xgf_5v5_per60','xga_5v5_per60','pp_xgf_per60','pk_xga_per60','pp_drawn_per_game','penalties_taken_per_game']);
function fieldEntries(game,criticalOnly=false){
 const entries=[];
 for(const side of ['away','home'])for(const [group,definition] of Object.entries(fieldGroups))for(const [field,label] of Object.entries(definition.fields)){
  if(criticalOnly&&group!=='context'&&!(['prior','current'].includes(group)&&criticalStats.has(field)))continue;
  const path=`${side}.${group}.${field}`,value=game[side]?.[group]?.[field],check=game.checks?.fields?.[path];
  const status=check?.status||'not_checked',found=status==='found'&&value!==null&&value!==undefined;
  entries.push({side,group,path,label,value,status,found,note:check?.note||''});
 }
 return entries;
}
function fieldValue(value){
 if(value===null||value===undefined)return '—';
 if(typeof value==='boolean')return value?'Áno':'Nie';
 if(typeof value==='number')return new Intl.NumberFormat('sk-SK',{maximumFractionDigits:4}).format(value);
 if(Array.isArray(value))return value.length?value.join('\n'):'Prázdny zoznam';
 const labels={confirmed:'Potvrdený',probable:'Predpokladaný',unknown:'Neznámy'};
 return Object.hasOwn(labels,value)?labels[value]:String(value);
}
function fieldDetails(game,criticalOnly){
 const entries=fieldEntries(game,criticalOnly),section=el('details','field-details');
 section.dataset.section=criticalOnly?'critical':'fields';
 section.append(el('summary','',`${criticalOnly?'Kľúčové vstupy':'Dostupné polia'}: ${entries.filter(e=>e.found).length}/${entries.length}`));
 section.append(el('p','subtle',criticalOnly?'Vstupy potrebné na výpočet modelovej projekcie.':'Všetky kontrolované polia vrátane nedostupných a nepreverených údajov.'));
 for(const side of ['away','home']){
  const rows=entries.filter(e=>e.side===side),team=el('details','field-team'),code=game[side].name;
  team.dataset.section=`${section.dataset.section}-${side}`;
  team.append(el('summary','',`${teamLocations[code]||''} ${teamNames[code]||code} · ${rows.filter(e=>e.found).length}/${rows.length}`.trim()));
  for(const [group,definition] of Object.entries(fieldGroups)){
   const fields=rows.filter(e=>e.group===group);if(!fields.length)continue;
   const block=el('section','field-group');block.append(el('h3','',definition.label));
   for(const field of fields){
    const row=el('div','field-row'),heading=el('div','field-heading');row.dataset.fieldPath=field.path;
    const status=field.found?'Získané':field.status==='unavailable'?'Nedostupné':field.status==='found'?'Chýba hodnota':'Nepreverené';
    heading.append(el('span','field-label',field.label),el('span','field-status '+(field.found?'good':field.status==='unavailable'?'caution':'subtle'),status));
    row.append(heading,el('p','field-value',fieldValue(field.value)));
    if(field.note)row.append(el('p','field-note',field.note));
    block.append(row);
   }
   team.append(block);
  }
  section.append(team);
 }
 return section;
}
const el=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;};
const pct=p=>Number.isFinite(p)?`${(p*100).toFixed(1)} %`:'—';
const dateLabel=day=>new Intl.DateTimeFormat('sk-SK',{day:'numeric',month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(day+'T12:00:00Z'));
const timeLabel=value=>value?new Intl.DateTimeFormat('sk-SK',{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit',timeZone:'Europe/Bratislava'}).format(new Date(value)):'';
const nhlToday=()=>new Intl.DateTimeFormat('en-CA',{timeZone:'America/New_York',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
function banner(title,detail,type='neutral',time=''){$('status').className='status-banner '+type;$('status-title').textContent=title;$('status-detail').textContent=detail;$('status-time').textContent=time;}
function empty(title,detail){const n=$('empty-template').content.cloneNode(true);n.querySelector('#empty-title').textContent=title;n.querySelector('#empty-detail').textContent=detail;$('games').replaceChildren(n);}
function teamIcon(code){const icon=el('span','team-code',code);if(Object.hasOwn(teamNames,code)){icon.classList.add('has-logo');const img=el('img','team-logo');img.src=`assets/teams/${code}.svg`;img.alt=`Logo ${teamNames[code]}`;img.width=48;img.height=48;img.loading='lazy';img.decoding='async';img.onerror=()=>{icon.classList.remove('has-logo');icon.replaceChildren(document.createTextNode(code));};icon.replaceChildren(img);}return icon;}
function reset(){current=null;for(const id of ['metric-games','metric-projected','metric-goalies','metric-checks'])$(id).textContent='—';$('ticket').hidden=true;$('coverage').hidden=true;$('coverage-detail').textContent='';$('import-warnings').hidden=true;$('slate-count').textContent='';$('games').replaceChildren();}
async function get(path){if(!api())throw new Error('Chýba konfigurácia backendu.');const r=await fetch(api()+path,{cache:'no-store',signal:AbortSignal.timeout(90000)});if(!r.ok){const e=new Error('HTTP '+r.status);e.status=r.status;throw e;}return r.json();}
function schedulePoll(){
 clearTimeout(poll);
 if(current&&(['PREFILLING','COLLECTING','QUEUED','RUNNING'].includes(current.state)||(current.odds_import?.upcoming_games||0)>0)){
  poll=setTimeout(()=>{if(document.hidden){schedulePoll();return;}return load({silent:true});},POLL_INTERVAL);
 }
}
async function load({silent=false}={}){
 const token=++serial,day=$('day').value,quiet=silent&&current?.day===day;
 clearTimeout(poll);
 if(!quiet){reset();$('refresh').disabled=true;banner('Načítavam prehľad',dateLabel(day));}
 try{
  const data=await get('/api/days/'+day);
  if(token!==serial)return;
  if(!quiet||JSON.stringify(data)!==JSON.stringify(current))render(data);
  current=data;
 }catch(e){
  if(token!==serial||quiet)return;
  if(e.status===404){banner('Tento deň zatiaľ nebol spracovaný','Po dokončení ChatGPT úlohy sa tu objavia výsledky.');empty('Na tento deň zatiaľ nemáme dáta','Vyber deň z archívu alebo počkaj na naplánovaný zber. Nezobrazujeme predikcie z iného dňa.');}
  else{banner('Backend sa nepodarilo načítať','Obnov stránku o chvíľu. Prvé načítanie po dlhšej nečinnosti môže trvať dlhšie.','error');empty('Prehľad je dočasne nedostupný','Skontroluj pripojenie a použi Obnoviť údaje.');}
 }finally{
  if(token===serial){$('refresh').disabled=false;schedulePoll();}
 }
}
function render(data){const report=data.state==='PREFILLING'?null:data.report,checks=data.checklist||report?.checklist,coverage=data.coverage,stale=Date.now()-Date.parse(data.updated_at)>18*3600000;let title=states[data.state]||data.state,detail='';let type='neutral';
 $('ticket').hidden=true;$('coverage').hidden=true;$('coverage-detail').textContent='';$('import-warnings').hidden=true;$('import-warning-list').replaceChildren();
 if(data.state==='COMPLETE'){type='';detail=resultLabels[report?.status]||'Modelový výpočet je dokončený.';}
 else if(data.state==='PREFILLING'){detail='Načítavam publikované štatistiky MoneyPuck a oficiálne údaje NHL. Prehľad sa automaticky obnoví.';}
 else if(data.state==='NO_GAMES'){detail='Oficiálny program na tento deň neobsahuje zápasy základnej časti.';}
 else if(data.state==='ERROR'){type='error';detail=data.error||'Úloha nebola dokončená.';}
 else if(data.state==='INCOMPLETE'){type='warn';detail=resultLabels[report?.status]||'Niektoré zápasy nemajú dostatok overených vstupov.';}
 else{detail='Priebežné údaje. Hotové predikcie sa zobrazia po dokončení celej kontroly.';type='warn';}
 const odds=data.odds_import;if(odds){const available=odds.covered_games||0,total=odds.upcoming_games||0;if(['WAITING','ERROR'].includes(odds.state)&&total){detail+=' Čakáme na aktuálne kurzy NHL; modelové projekcie môžu byť dostupné.';type='warn';}else if(odds.state==='PARTIAL'){detail+=` Kurzy sú dostupné pre ${available}/${total} nadchádzajúcich zápasov.`;type='warn';}}
 if(stale){type='warn';detail+=' Posledná aktualizácia je staršia než 18 hodín.';}
 banner(title,detail,type,'Aktualizované '+timeLabel(data.updated_at));
 $('metric-games').textContent=data.game_count;$('metric-projected').textContent=report?.games?.filter(g=>g.lambda_total!==undefined).length??'—';$('metric-goalies').textContent=`${(data.games||[]).reduce((n,g)=>n+['home','away'].filter(s=>g[s].goalie.status==='confirmed').length,0)} / ${data.game_count*2}`;
 if(coverage){$('metric-checks').textContent=coverage.field_total?`${Math.round(coverage.field_found_pct)} %`:'—';$('metric-check-note').textContent=`${coverage.field_found} / ${coverage.field_total} polí so získanými údajmi`;$('coverage').hidden=false;$('coverage-detail').textContent=`Kľúčové vstupy modelu: ${coverage.critical_found} / ${coverage.critical_total}. Potrebné vstupy má ${coverage.critical_ready_games} / ${coverage.scheduled_games} nadchádzajúcich zápasov. Uzavreté kontroly: ${coverage.checklist_closed} / ${coverage.checklist_total} (zahŕňajú aj nedostupné údaje).`;}
 else{$('metric-checks').textContent='—';$('metric-check-note').textContent='skutočná dostupnosť zatiaľ nevyhodnotená';}
 if(odds){$('coverage').hidden=false;const last=odds.checked_at?timeLabel(odds.checked_at):'—',source=odds.source_updated_at?timeLabel(odds.source_updated_at):'—';$('coverage-detail').textContent+=` Kurzy NHL/DraftKings: ${odds.covered_games||0}/${odds.upcoming_games||0} zápasov · ${odds.market_count||0} ponúk. Kontrola ${last}, zdroj aktualizovaný ${source}. Automatická kontrola každých 30 minút od 18:00 do 05:30 slovenského času.`;}
 const warnings=data.enrichment?.warnings||[];if(warnings.length){$('coverage').hidden=false;$('import-warnings').hidden=false;$('import-warning-list').replaceChildren(...warnings.map(w=>el('li','',w)));}
 $('slate-count').textContent=dateLabel(data.day);
 if(data.state==='NO_GAMES'){empty('Dnes má ľad voľno','V programe nie sú zápasy základnej časti NHL. Ďalšie dni nájdeš cez výber dátumu.');return;}
 const results=new Map((report?.games||[]).map(g=>[g.game_id,g]));const audits=new Map((checks?.games||[]).map(g=>[g.game_id,g]));
 const existing=new Map([...$('games').children].map(n=>[n.dataset.gameId,n]));
 const cards=(data.games||[]).map(g=>{
  const next=card(g,results.get(g.game_id),audits.get(g.game_id),data),previous=existing.get(String(g.game_id));
  if(previous){const opened=new Map([...previous.querySelectorAll('details')].map(d=>[d.dataset.section,d.open]));for(const d of next.querySelectorAll('details'))d.open=opened.get(d.dataset.section)||false;if(previous.outerHTML===next.outerHTML)return previous;}
  return next;
 });
 for(let i=0;i<cards.length;i++){
  const at=$('games').children[i];
  if(at!==cards[i])$('games').insertBefore(cards[i],at||null);
 }
 while($('games').children.length>cards.length)$('games').lastElementChild.remove();
 if(report?.ticket){$('ticket').hidden=false;$('ticket').replaceChildren(el('p','eyebrow','KANDIDÁT / VYŽADUJE KONTROLU'),el('h2','',`${report.ticket.legs.length} výbery · kombinovaný kurz ${report.ticket.odds}`),el('p','',report.ticket.legs.map(l=>`${l.matchup}: ${marketName(l)}`).join(' · ')),el('p','','Scenáre používajú zjednodušené predpoklady. Kandidát nie je pokyn na uzatvorenie stávky.'));}
}
function card(game,result,audit,data){const article=el('article','game-card'),head=el('div','game-head');article.dataset.gameId=String(game.game_id);head.append(el('span','',timeLabel(game.starts_at)+' · SEČ/SELČ'));const ready=result?.lambda_total!==undefined;head.append(el('span','badge '+(ready?'':game.status==='scheduled'?'warn':'muted'),ready?'PROJEKCIA':game.status==='scheduled'?'ČAKÁ NA DÁTA':game.status==='final'?'UKONČENÉ':'ZAČATÉ'));article.append(head);
 const matchup=el('div','matchup');for(const side of ['away','home']){if(side==='home')matchup.append(el('span','versus','VS'));const team=el('div','team '+side),code=game[side].name,icon=teamIcon(code),name=el('div','team-name');if(Object.hasOwn(teamLocations,code))name.append(el('small','',teamLocations[code]));name.append(el('strong','',teamNames[code]||code));team.append(...(side==='home'?[name,icon]:[icon,name]));matchup.append(team);}article.append(matchup);
 if(ready){const goals=el('div','goal-projection');goals.append(el('strong','',result.lambda_away.toFixed(2)),el('span','','MODELOVÉ GÓLY'),el('strong','',result.lambda_home.toFixed(2)));article.append(goals);const labels=el('div','prob-labels'),away=el('span','',game.away.name+' '),home=el('span','',game.home.name+' ');away.append(el('strong','',pct(result.p_away_win)));home.append(el('strong','',pct(result.p_home_win)));labels.append(away,home);const bar=el('div','prob-bar'),a=el('span','away'),h=el('span','home');a.style.width=(result.p_away_win*100)+'%';h.style.width=(result.p_home_win*100)+'%';bar.append(a,h);article.append(labels,bar);}
 else article.append(el('p','game-note',result?.skipped==='ALREADY_STARTED_OR_FINAL'?'Zápas už začal. Novú predzápasovú projekciu nevytvárame.':result?.missing?.length?`Chýba ${result.missing.length} kľúčových modelových vstupov.`:'Research ešte nie je dokončený. Chýbajúce hodnoty ostávajú prázdne.'));
 const meta=el('div','game-meta');const confirmed=['home','away'].filter(s=>game[s].goalie.status==='confirmed').length;const available=data.coverage?.games?.find(g=>g.game_id===game.game_id);meta.append(el('span','',`Brankári ${confirmed}/2 potvrdení`),el('span','',ready?`${result.simulations.toLocaleString('sk-SK')} simulácií`:available?`${available.field_found}/${available.field_total} dostupných polí`:'Údaje sa zbierajú'));article.append(meta);
 const detail=el('details','game-detail'),summary=el('summary','','Kontroly a trhy');detail.dataset.section='game';detail.append(summary);
 const roster=el('p','subtle',`${game.away.name}: ${game.away.goalie.name||'neznámy'} (${game.away.goalie.status}) · ${game.home.name}: ${game.home.goalie.name||'neznámy'} (${game.home.goalie.status})`);detail.append(roster);
 detail.append(fieldDetails(game,true),fieldDetails(game,false));
 if(result?.markets?.length){const wrap=el('div','market-list'),table=el('table'),thead=el('thead'),hr=el('tr');for(const label of ['Trh','Kurz','P(výhra)','EV','Stav'])hr.append(el('th','',label));thead.append(hr);const body=el('tbody');for(const m of result.markets){const row=el('tr');for(const value of [marketName(m),String(m.odds),pct(m.p_win),pct(m.ev),m.research_eligible?'Na kontrolu':'Blokované'])row.append(el('td','',value));body.append(row);if(m.issues.length){const issueRow=el('tr'),cell=el('td','caution',m.issues.join(' · '));cell.colSpan=5;issueRow.append(cell);body.append(issueRow);}}table.append(thead,body);wrap.append(table);detail.append(wrap);}else detail.append(el('p','subtle','Zatiaľ nie sú dostupné vyhodnotené trhy.'));
 if(audit){detail.append(el('p','subtle',`Preverené polia: ${audit.checked_count}/82 · Nedostupné polia a trhy: ${audit.unavailable.length} · Nepreverené položky: ${audit.unchecked.length}`));const issues=[...(audit.issues||[]),...(audit.model_missing||[]).map(p=>'Chýba: '+p),...(audit.unchecked||[]).map(p=>'Nepreverené: '+p)];if(issues.length){const list=el('ul','issue-list');issues.forEach(x=>list.append(el('li','',x)));detail.append(list);}}
 article.append(detail);return article;
}
function marketName(m){const kinds={total:'Góly spolu',team_total:'Tímové góly',handicap:'Handicap',moneyline:'Víťaz vrátane OT',regulation_1x2:'Výsledok 60 min',double_chance:'Dvojtip 60 min'};const sides={home:'domáci',away:'hostia',draw:'remíza',over:'nad',under:'pod'};return `${kinds[m.kind]||m.kind} · ${sides[m.selection]||m.selection}${m.line!==null?' '+m.line:''}${m.team?' ('+(sides[m.team]||m.team)+')':''}`;}
async function loadArchive(){try{const data=await get('/api/days');$('archive').replaceChildren();if(!data.days.length){$('archive').append(el('p','subtle','Prvý spracovaný deň sa objaví tu.'));return;}for(const day of data.days){const row=el('div','archive-row'),link=el('a','',dateLabel(day.day));link.href='?day='+day.day;link.addEventListener('click',event=>{event.preventDefault();$('day').value=day.day;select();});row.append(link,el('small','',`${day.game_count} zápasov · ${states[day.state]||day.state}`));$('archive').append(row);}}catch{$('archive').replaceChildren(el('p','subtle','Archív je dočasne nedostupný.'));}}
function select(){if(!/^\d{4}-\d{2}-\d{2}$/.test($('day').value))return;history.replaceState(null,'','?day='+$('day').value);load();loadArchive();}
function move(delta){const date=new Date($('day').value+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+delta);$('day').value=date.toISOString().slice(0,10);select();}
const query=new URLSearchParams(location.search).get('day');$('day').value=query&&/^\d{4}-\d{2}-\d{2}$/.test(query)&&!Number.isNaN(Date.parse(query))?query:nhlToday();$('prev').onclick=()=>move(-1);$('next').onclick=()=>move(1);$('day').onchange=select;$('refresh').onclick=select;load();loadArchive();
