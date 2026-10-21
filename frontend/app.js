'use strict';
const $=id=>document.getElementById(id);
const api=()=>window.NHL_CONFIG?.apiBase?.replace(/\/$/,'');
const states={PREFILLING:'Načítavam štatistiky',COLLECTING:'Zber dát',READY_FOR_ANALYSIS:'Dáta pripravené na analýzu',COMPLETE:'Analýza uložená',ERROR:'Zber dát zlyhal',NO_GAMES:'Bez zápasov'};
const teamNames={ANA:'Ducks',BOS:'Bruins',BUF:'Sabres',CAR:'Hurricanes',CBJ:'Blue Jackets',CGY:'Flames',CHI:'Blackhawks',COL:'Avalanche',DAL:'Stars',DET:'Red Wings',EDM:'Oilers',FLA:'Panthers',LAK:'Kings',MIN:'Wild',MTL:'Canadiens',NJD:'Devils',NSH:'Predators',NYI:'Islanders',NYR:'Rangers',OTT:'Senators',PHI:'Flyers',PIT:'Penguins',SEA:'Kraken',SJS:'Sharks',STL:'Blues',TBL:'Lightning',TOR:'Maple Leafs',UTA:'Mammoth',VAN:'Canucks',VGK:'Golden Knights',WPG:'Jets',WSH:'Capitals'};
const teamLocations={ANA:'ANAHEIM',BOS:'BOSTON',BUF:'BUFFALO',CAR:'CAROLINA',CBJ:'COLUMBUS',CGY:'CALGARY',CHI:'CHICAGO',COL:'COLORADO',DAL:'DALLAS',DET:'DETROIT',EDM:'EDMONTON',FLA:'FLORIDA',LAK:'LOS ANGELES',MIN:'MINNESOTA',MTL:'MONTRÉAL',NJD:'NEW JERSEY',NSH:'NASHVILLE',NYI:'NEW YORK',NYR:'NEW YORK',OTT:'OTTAWA',PHI:'PHILADELPHIA',PIT:'PITTSBURGH',SEA:'SEATTLE',SJS:'SAN JOSE',STL:'ST. LOUIS',TBL:'TAMPA BAY',TOR:'TORONTO',UTA:'UTAH',VAN:'VANCOUVER',VGK:'VEGAS',WPG:'WINNIPEG',WSH:'WASHINGTON'};
let current=null,serial=0,poll,detailRequest=null;
const POLL_INTERVAL=5*60*1000;
const MIN_PICK_ODDS=1.20,MAX_PICK_ODDS=1.35;
function currentRecommendation(result){const m=result?.recommendation,price=m?.price??(m?.odds_type==='ESTIMATED'?m?.estimated_odds:m?.odds);return typeof price==='number'&&Number.isFinite(price)&&price>=MIN_PICK_ODDS&&price<=MAX_PICK_ODDS?m:null;}
const statFields={games:'Odohrané zápasy',points_pct:'Bodová úspešnosť (%)',xgf_5v5_per60:'xG za 60 min · 5 na 5',xga_5v5_per60:'xG súpera za 60 min · 5 na 5',pp_xgf_per60:'xG za 60 min · presilovka',pk_xga_per60:'xG súpera za 60 min · oslabenie',pp_drawn_per_game:'Vynútené tresty na zápas',penalties_taken_per_game:'Tresty na zápas',shots_for_per_game:'Strely na zápas',shots_against_per_game:'Strely súpera na zápas',high_danger_for_per60:'Veľké šance za 60 min',high_danger_against_per60:'Veľké šance súpera za 60 min'};
const fieldDefinitions={prior:{label:'Minulá sezóna',fields:statFields},current:{label:'Aktuálna sezóna',fields:statFields},goalie:{label:'Brankár',fields:{name:'Meno',status:'Potvrdenie brankára',starts_current:'Štarty · aktuálna sezóna',starts_prior:'Štarty · minulá sezóna',gsax_per60_current:'GSAx za 60 min · aktuálna sezóna',gsax_per60_prior:'GSAx za 60 min · minulá sezóna',save_pct_current:'SV% · aktuálna sezóna',save_pct_prior:'SV% · minulá sezóna'}},roster:{label:'Zostava',fields:{quantified:'Vyčíslený vplyv zostavy',attack_delta_goals:'Vplyv na strelené góly',defense_delta_goals_allowed:'Vplyv na inkasované góly',absences:'Absencie',returns:'Návraty',scratches:'Vynechaní hráči',forward_lines:'Útočné formácie',defense_pairs:'Obranné dvojice',power_play_units:'Presilovkové jednotky'}},context:{label:'Oddych a cestovanie',fields:{rest_days:'Dni oddychu',travel_timezones:'Prekonané časové pásma'}}};
const fieldGroups=Object.fromEntries(["goalie","roster","context","current","prior"].map(key=>[key,fieldDefinitions[key]]));
function fieldEntries(game){
 const entries=[];
 for(const side of ['away','home'])for(const [group,definition] of Object.entries(fieldGroups))for(const [field,label] of Object.entries(definition.fields)){
  const path=`${side}.${group}.${field}`,value=game[side]?.[group]?.[field],check=game.checks?.fields?.[path];
  const status=check?.status||'not_checked',found=status==='found'&&value!==null&&value!==undefined;
  entries.push({side,group,path,label,value,status,found,note:check?.note||''});
 }
 return entries;
}
function fieldValue(value,path=''){
 if(value===null||value===undefined)return 'N/A';
 if(typeof value==='boolean')return value?'Áno':'Nie';
 if(typeof value==='number')return path.includes('.save_pct_')?new Intl.NumberFormat('sk-SK',{style:'percent',minimumFractionDigits:2,maximumFractionDigits:2}).format(value):new Intl.NumberFormat('sk-SK',{maximumFractionDigits:4}).format(value);
 if(Array.isArray(value))return value.length?value.join('\n'):'Prázdny zoznam';
 const labels={confirmed:'Potvrdený',probable:'Predpokladaný',unknown:'Neznámy'};
 return Object.hasOwn(labels,value)?labels[value]:String(value);
}
function disclosure(label,id,cls,build){
 const section=el('details',cls);section.dataset.section=id;section.append(el('summary','',label));let built=false;
 section.prepare=()=>{if(!built){built=true;build(section);}};
 section.ontoggle=()=>{if(section.open)section.prepare();};return section;
}
function pendingDetails(label,id,cls,data){return disclosure(label,id,cls,section=>{
 section.append(el('p','subtle',data.details_error?'Podrobné dáta sa nepodarilo načítať.':'Načítavam podrobné dáta…'));
 if(data.details_error){const retry=el('button','detail-retry','Skúsiť znovu');retry.onclick=()=>load({silent:true,overview:true});section.append(retry);}
});}
function fieldDetails(game){
 return disclosure('Dáta','fields','field-details',section=>{
 const entries=fieldEntries(game);
 const teams=el('div','comparison-teams');
 for(const side of ['away','home']){
  const team=el('div','comparison-team '+side),code=game[side].name;
  team.append(el('strong','',code));teams.append(team);
 }
 section.append(teams);
 for(const [group,definition] of Object.entries(fieldGroups)){
  const fields=entries.filter(e=>e.side==='away'&&e.group===group);if(!fields.length)continue;
  const block=el('details','field-group');block.dataset.section=`${section.dataset.section}-${group}`;block.append(el('summary','',definition.label));
  for(const away of fields){
   const home=entries.find(e=>e.path===away.path.replace(/^away\./,'home.')),pair=[away,home];
   const row=el('div','field-row'),heading=el('div','field-heading'),values=el('div','field-values');row.dataset.metricPath=`${group}.${away.path.split('.').at(-1)}`;
   const explanation=pair.filter(field=>field.note).map(field=>`${game[field.side].name}: ${field.note}`).join('\n\n');
   const label=el(explanation?'button':'span','field-label',away.label);heading.append(label);row.append(heading);
   for(const field of pair){
    const cell=el('div','field-cell '+field.side);
    cell.dataset.fieldPath=field.path;cell.setAttribute('role','group');cell.setAttribute('aria-label',`${game[field.side].name}: ${field.label}`);
    cell.append(el('p','field-value',field.found?fieldValue(field.value,field.path):'N/A'));values.append(cell);
   }
   row.append(values);
   if(explanation){
     const note=el('span','field-tooltip',explanation);note.id=`field-note-${game.game_id}-${section.dataset.section}-${row.dataset.metricPath.replaceAll('.','-')}`;note.setAttribute('role','tooltip');
     row.classList.add('has-note');label.type='button';label.setAttribute('aria-describedby',note.id);
     label.onfocus=()=>row.classList.remove('note-dismissed');
     label.onclick=()=>{const open=row.classList.toggle('note-open');row.classList.toggle('note-dismissed',!open);if(!open)label.blur();};
     row.onmouseleave=()=>row.classList.remove('note-dismissed');
     row.append(note);
   }
   block.append(row);
  }
  section.append(block);
 }
 });
}
function dismissFieldNotes(except,blur=true){
 for(const row of document.querySelectorAll('.field-row.note-open,.field-row.has-note:hover,.field-row.has-note:focus-within')){
  if(except&&row.contains(except))continue;
  if(row.classList.contains('note-open')||row.matches(':hover')||row.contains(document.activeElement)){
   row.classList.remove('note-open');row.classList.add('note-dismissed');
   if(blur&&row.contains(document.activeElement))document.activeElement.blur();
  }
 }
}
document.addEventListener('pointerdown',event=>dismissFieldNotes(event.target));
document.addEventListener('keydown',event=>{if(event.key==='Escape')dismissFieldNotes(null,false);});
const el=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;};
const pct=p=>Number.isFinite(p)?`${(p*100).toFixed(1)} %`:'—';
const dateLabel=day=>new Intl.DateTimeFormat('sk-SK',{day:'numeric',month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(day+'T12:00:00Z'));
const timeLabel=value=>value?new Intl.DateTimeFormat('sk-SK',{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit',timeZone:'Europe/Bratislava'}).format(new Date(value)):'';
const nhlToday=()=>new Intl.DateTimeFormat('en-CA',{timeZone:'America/New_York',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
function banner(title,detail,type='neutral',time=''){$('status').className='status-banner '+type;$('status-title').textContent=title;$('status-detail').textContent=detail;$('status-time').textContent=time;}
function empty(title,detail){const n=$('empty-template').content.cloneNode(true);n.querySelector('#empty-title').textContent=title;n.querySelector('#empty-detail').textContent=detail;$('games').replaceChildren(n);}
function teamIcon(code){const icon=el('span','team-code',code);if(Object.hasOwn(teamNames,code)){icon.classList.add('has-logo');const img=el('img','team-logo');img.src=`assets/teams/${code}.svg`;img.alt=`Logo ${teamNames[code]}`;img.width=48;img.height=48;img.loading='lazy';img.decoding='async';img.onerror=()=>{icon.classList.remove('has-logo');icon.replaceChildren(document.createTextNode(code));};icon.replaceChildren(img);}return icon;}
function reset(){current=null;for(const id of ['metric-games','metric-reviewed','metric-goalies','metric-checks'])$(id).textContent='—';$('ticket').hidden=true;$('coverage').hidden=true;$('coverage-detail').textContent='';$('import-warnings').hidden=true;$('slate-count').textContent='';$('games').replaceChildren();}
async function get(path){if(!api())throw new Error('Chýba konfigurácia backendu.');const r=await fetch(api()+path,{cache:'no-store',signal:AbortSignal.timeout(90000)});if(!r.ok){const e=new Error('HTTP '+r.status);e.status=r.status;throw e;}return r.json();}
function schedulePoll(){
 clearTimeout(poll);
 if(current&&(['PREFILLING','COLLECTING'].includes(current.state)||(!current.publication?.frozen&&current.state==='READY_FOR_ANALYSIS')||current.publication?.refresh_in_progress||(current.publication?.frozen?!(current.scoreboard?.games?.length===current.game_count&&current.scoreboard.games.every(g=>g.status==='final')):(current.odds_import?.upcoming_games||0)>0))){
  poll=setTimeout(()=>{if(document.hidden){schedulePoll();return;}return load({silent:true});},POLL_INTERVAL);
 }
}
function queueDetails(token,data){
 if(data.details_loaded!==false||data.details_error||!data.games?.length)return;
 const run=()=>loadDetails(token,data);
 if(window.requestAnimationFrame)window.requestAnimationFrame(()=>window.requestAnimationFrame(run));else window.queueMicrotask(run);
}
async function loadDetails(token,data){
 if(token!==serial||current?.day!==data.day||current.details_loaded!==false)return;
 const key=data.day+'|'+data.data_snapshot_id;
 if(detailRequest?.key===key&&detailRequest.token===token)return;
 const request={key,token};detailRequest=request;
 try{
  const full=await get('/api/days/'+data.day);
  if(token!==serial||current?.data_snapshot_id!==data.data_snapshot_id)return;
  if(full.data_snapshot_id!==data.data_snapshot_id){current.details_error=true;render(current);return;}
  current={...full,details_loaded:true};render(current);
 }catch{
  if(token===serial&&current?.day===data.day){current={...current,details_error:true};render(current);}
 }finally{if(detailRequest===request)detailRequest=null;}
}
async function load({silent=false,overview=false}={}){
 const token=++serial,day=$('day').value,quiet=silent&&current?.day===day;
 clearTimeout(poll);
 if(!quiet){reset();$('refresh').disabled=true;banner('Načítavam prehľad',dateLabel(day));}
 try{
  let data;
  if(quiet&&current.publication?.frozen&&!overview){
   const live=await get('/api/days/'+day+'/live');
   const samePublication=live.publication?.published_at===current.publication?.published_at;
   data=samePublication?{...current,...live}:await get('/api/days/'+day+'/cards');
  }else data=await get('/api/days/'+day+'/cards');
  if(token!==serial)return;
  if(!quiet||JSON.stringify(data)!==JSON.stringify(current))render(data);
  current=data;
  queueDetails(token,data);
 }catch(e){
  if(token!==serial||quiet)return;
  if(e.status===404){banner('Tento deň zatiaľ nebol spracovaný','Po dokončení dennej analýzy sa tu objavia výsledky.');empty('Na tento deň zatiaľ nemáme dáta','Vyber deň z archívu alebo počkaj na naplánovaný zber. Nezobrazujeme predikcie z iného dňa.');}
  else{banner('Backend sa nepodarilo načítať','Obnov stránku o chvíľu. Prvé načítanie po dlhšej nečinnosti môže trvať dlhšie.','error');empty('Prehľad je dočasne nedostupný','Skontroluj pripojenie a použi Obnoviť údaje.');}
 }finally{
  if(token===serial){$('refresh').disabled=false;schedulePoll();}
 }
}
function render(data){const coverage=data.coverage,stale=Date.now()-Date.parse(data.updated_at)>18*3600000;let title=states[data.state]||data.state,detail='';let type='neutral';
 $('ticket').hidden=true;$('coverage').hidden=true;$('coverage-detail').textContent='';$('import-warnings').hidden=true;$('import-warning-list').replaceChildren();
 if(data.state==='PREFILLING'){detail='Načítavam publikované štatistiky MoneyPuck a oficiálne údaje NHL.';}
 else if(data.state==='NO_GAMES'){detail='Oficiálny program na tento deň neobsahuje zápasy základnej časti.';}
 else if(data.state==='ERROR'){type='error';detail=data.error||'Zber nebol dokončený.';}
 else{title='Čaká na finálnu analýzu';detail=data.research_complete?'Research je pripravený. Finálne výbery ešte nie sú uložené.':'Priebežné údaje. Prebieha dopĺňanie researchu a posúdenie zápasov.';}
 const final=data.final_tips;
 if(final?.state==='PUBLISHED'||(data.state!=='PREFILLING'&&data.state!=='NO_GAMES'&&data.state!=='ERROR')){
  if(final?.state==='PUBLISHED'){title='Analýza uložená';detail=`Výbery v pásme 1,20–1,35: ${final.tip_count||0} · Bez tipu: ${final.no_bet_count||0}.`;type='';if(data.publication?.refresh_in_progress)detail+=' Pripravuje sa nová analýza; zatiaľ zostáva zobrazená posledná publikovaná.';}
  else if(final?.state==='NEEDS_REVIEW'){title='Výbery čakajú na nové overenie';detail='Podklady sa zmenili. Uložené výbery vyžadujú nové zhodnotenie.';type='warn';}
 }
 const odds=data.odds_import;if(odds&&!data.publication?.frozen){const available=odds.covered_games||0,total=odds.upcoming_games||0;if(['WAITING','ERROR'].includes(odds.state)&&total){detail+=' Čakáme na aktuálne kurzy NHL; zostávajúci research môže pokračovať.';type='warn';}else if(odds.state==='PARTIAL'){detail+=` Kurzy sú dostupné pre ${available}/${total} nadchádzajúcich zápasov.`;type='warn';}}
 if(stale&&!data.publication?.frozen){type='warn';detail+=' Posledná aktualizácia je staršia než 18 hodín.';}
 banner(title,detail,type,'Aktualizované '+timeLabel(data.updated_at));
 $('metric-games').textContent=data.game_count;$('metric-reviewed').textContent=(final?.games||[]).length;$('metric-goalies').textContent=`${(data.games||[]).reduce((n,g)=>n+['home','away'].filter(s=>g[s].goalie.status==='confirmed').length,0)} / ${data.game_count*2}`;
 if(coverage){$('metric-checks').textContent=coverage.field_total?`${Math.round(coverage.field_found_pct)} %`:'—';$('metric-check-note').textContent=`${coverage.field_found} / ${coverage.field_total} polí so získanými údajmi`;$('coverage').hidden=false;$('coverage-detail').textContent=`Dostupné údaje: ${coverage.field_found} / ${coverage.field_total}. Uzavreté kontroly: ${coverage.checklist_closed} / ${coverage.checklist_total} (zahŕňajú aj nedostupné údaje).`;}
 else{$('metric-checks').textContent='—';$('metric-check-note').textContent='skutočná dostupnosť zatiaľ nevyhodnotená';}
 if(odds){$('coverage').hidden=false;const last=odds.checked_at?timeLabel(odds.checked_at):'—',source=odds.source_updated_at?timeLabel(odds.source_updated_at):'—';$('coverage-detail').textContent+=` Kurzy NHL/DraftKings: ${odds.covered_games||0}/${odds.upcoming_games||0} zápasov · ${odds.market_count||0} ponúk. Kontrola ${last}, zdroj aktualizovaný ${source}. Kontrola každých 30 minút od 18:00 do 08:00 slovenského času.`;}
 if(data.publication?.frozen){$('coverage').hidden=false;$('coverage-detail').textContent+=` Podklady a kurzy uložené pri analýze: ${timeLabel(data.publication.published_at)}. Ďalej sa obnovuje iba skóre do 08:00 slovenského času.`;}
 const warnings=data.enrichment?.warnings||[];if(warnings.length){$('coverage').hidden=false;$('import-warnings').hidden=false;$('import-warning-list').replaceChildren(...warnings.map(w=>el('li','',w)));}
 $('slate-count').textContent=dateLabel(data.day);
 if(data.state==='NO_GAMES'){empty('Dnes má ľad voľno','V programe nie sú zápasy základnej časti NHL. Ďalšie dni nájdeš cez výber dátumu.');return;}
 const existing=new Map([...$('games').children].map(n=>[n.dataset.gameId,n]));
 const cards=(data.games||[]).map(g=>{
  const next=card(g,data),previous=existing.get(String(g.game_id));
  if(previous){const opened=new Map([...previous.querySelectorAll('details')].map(d=>[d.dataset.section,d.open]));for(const d of next.querySelectorAll('details'))if(opened.get(d.dataset.section)){d.prepare?.();d.open=true;}for(const d of next.querySelectorAll('details'))d.open=opened.get(d.dataset.section)||false;if(previous.renderKey===next.renderKey&&previous.outerHTML===next.outerHTML)return previous;}
  return next;
 });
 for(let i=0;i<cards.length;i++){
  const at=$('games').children[i];
  if(at!==cards[i])$('games').insertBefore(cards[i],at||null);
 }
 while($('games').children.length>cards.length)$('games').lastElementChild.remove();

}
function noBetCopy(value){return String(value??'').replace(/^\s*[*`]*NO[_\s-]*BET\b[*`]*\s*(?:[:;,.–—-]\s*)?/iu,'').trim();}
function detailCopy(final){
 const noBet=final.decision==='NO_BET',clean=value=>noBet?noBetCopy(value):String(value??'').trim();
 const split=value=>clean(value).split(/(?<=[.!?])\s+|\n+/u).map(s=>s.trim()).filter(Boolean);
 const key=value=>value.normalize('NFKC').toLocaleLowerCase('sk').replace(/\s+/gu,' ').replace(/[.!?]+$/u,'').trim();
 const seen=new Set(noBet?split(final.rationale).map(key):[]);
 const fresh=value=>split(value).filter(sentence=>{const k=key(sentence);if(seen.has(k))return false;seen.add(k);return true;}).join(' ');
 return {paragraphs:[noBet?'':fresh(final.rationale),fresh(final.comparison)].filter(Boolean),risks:(final.risks||[]).map(fresh).filter(Boolean)};
}
function recommendationBlock(final,game,data){
 const box=el('div','recommendation');
 if(!final){box.append(el('p','subtle','Čaká na finálny výber.'));return box;}
 const needsReview=final.state==='NEEDS_REVIEW';
 const outcome=final.settlement?.state,labels={WIN:'SPRÁVNY TIP',LOSS:'NESPRÁVNY TIP',PUSH:'NA HRANICI · PUSH',UNAVAILABLE:'VÝSLEDOK SA NEDÁ URČIŤ'};
 if(final.decision==='TIP'&&labels[outcome]){box.classList.add('outcome-'+outcome.toLowerCase());box.append(el('p','settlement '+outcome.toLowerCase(),labels[outcome]));}
 if(needsReview){box.classList.add('needs-review');box.append(el('p','caution','Podklady sa zmenili – výber vyžaduje nové overenie.'));}
 if(final.decision==='NO_BET'){box.append(el('p','eyebrow',needsReview?'BEZ TIPU / NA NOVÉ OVERENIE':'BEZ TIPU'));const text=noBetCopy(final.rationale);if(text)box.append(el('p','subtle',text));}
 else{
  const m=currentRecommendation({recommendation:final.candidate});
  if(!m){box.append(el('p','subtle','Bez aktuálneho výberu v pásme 1,20–1,35.'));return box;}
  const estimated=m.odds_type==='ESTIMATED';
  box.append(el('p','eyebrow',needsReview?'VÝBER / NA NOVÉ OVERENIE':estimated?'PREDBEŽNÝ TIP':'TIP'));
  const selection={...m};if(m.kind==='handicap'){selection.selection=game[m.selection]?.name||m.selection;selection.team=null;}if(m.kind==='moneyline'||m.kind==='regulation_1x2')selection.selection=game[m.selection]?.name||m.selection;
  box.append(el('h3','',marketName(selection).replace(/(\d)\.(\d)/g,'$1,$2')));
  const facts=el('div','recommendation-facts'),price=m.price??(estimated?m.estimated_odds:m.odds);
  const odds=el('span','',`${estimated?'Odhad kurzu ≈':'Kurz '}${price.toLocaleString('sk-SK',{minimumFractionDigits:2,maximumFractionDigits:2})}`);
  odds.title=estimated?'Približná cena odvodená z kurzov, nie overená ponuka bookmakera.':'Overený kurz so zdrojom a časom kontroly.';
  const quality={LOW:'nízka',MODERATE:'stredná',HIGH:'vysoká'},confidence=el('span','recommendation-confidence',`Confidence: ${quality[final.confidence||m.confidence]||'neurčená'}`);
  confidence.title='Kvalita analytických podkladov a záveru, nie vypočítaná pravdepodobnosť alebo garantovaná úspešnosť.';
  facts.append(odds,confidence);box.append(facts);
 }
 const copy=detailCopy(final);
 if(data.details_loaded===false)box.append(pendingDetails('Zdôvodnenie','tip-analysis','tip-analysis',data));
 else if(copy.paragraphs.length||copy.risks.length)box.append(disclosure('Zdôvodnenie','tip-analysis','tip-analysis',section=>{
  copy.paragraphs.forEach(text=>section.append(el('p','subtle',text)));
  if(copy.risks.length){const risks=el('ul','issue-list');copy.risks.forEach(risk=>risks.append(el('li','',risk)));section.append(risks);}
 }));return box;
}
function card(game,data){
 const article=el('article','game-card'),head=el('div','game-head');article.dataset.gameId=String(game.game_id);head.append(el('span','',timeLabel(game.starts_at)+' · SEČ/SELČ'));
 const decision=data.final_tips?.games?.find(g=>g.game_id===String(game.game_id)),currentDecision=!!decision,score=data.scoreboard?.games?.find(g=>g.game_id===String(game.game_id)),gameStatus=score?.status||game.status;
 article.renderKey=JSON.stringify([game,decision,data.details_loaded,data.details_error]);
 const label=gameStatus==='final'?'UKONČENÉ':gameStatus==='live'?'PREBIEHA':gameStatus==='postponed'?'ODLOŽENÉ':currentDecision?(decision.decision==='TIP'?'TIP':'BEZ TIPU'):'RESEARCH';
 head.append(el('span','badge '+(currentDecision?'':game.status==='scheduled'?'warn':'muted'),label));article.append(head);
 const matchup=el('div','matchup');for(const side of ['away','home']){if(side==='home')matchup.append(el('span','versus','VS'));const team=el('div','team '+side),code=game[side].name,icon=teamIcon(code),name=el('div','team-name');if(Object.hasOwn(teamLocations,code))name.append(el('small','',teamLocations[code]));name.append(el('strong','',teamNames[code]||code));team.append(...(side==='home'?[name,icon]:[icon,name]));matchup.append(team);}article.append(matchup);
 if(score&&Number.isInteger(score.home_score)&&Number.isInteger(score.away_score)&&['live','final'].includes(gameStatus)){const row=el('div','match-score');row.append(el('strong','',String(score.away_score)),el('span','',gameStatus==='final'?`KONEČNÉ SKÓRE${score.last_period_type==='OT'?' · PP':score.last_period_type==='SO'?' · SN':''}`:`PRIEBEŽNÉ SKÓRE${score.last_period_type==='OT'?' · PREDĹŽENIE':score.last_period_type==='SO'?' · NÁJAZDY':score.period?' · '+score.period+'. tretina':''}`),el('strong','',String(score.home_score)));article.append(row);}
 const meta=el('div','game-meta'),confirmed=['home','away'].filter(s=>game[s].goalie.status==='confirmed').length,available=data.coverage?.games?.find(g=>g.game_id===game.game_id);
 meta.append(el('span','',`Brankári ${confirmed}/2 potvrdení`),el('span','',available?`${available.field_found}/${available.field_total} dostupných polí`:'Analytické údaje'));article.append(meta);
 if(decision||gameStatus==='scheduled')article.append(recommendationBlock(decision,game,data));
 if(data.details_loaded===false)article.append(pendingDetails('Dáta','fields','field-details',data));
 else if(data.publication?.frozen&&data.publication.snapshot_available===false){const detail=el('details','field-details');detail.dataset.section='fields';detail.append(el('summary','','Dáta'),el('p','subtle','Presné pôvodné podklady analýzy sa nepodarilo obnoviť. Aktuálne štatistiky ich nenahrádzajú.'));article.append(detail);}else article.append(fieldDetails(game));
 if(game.has_analysis_context||game.analysis_context?.length)article.append(data.details_loaded===false?pendingDetails('Doplňujúci research','analysis-context','field-details',data):disclosure('Doplňujúci research','analysis-context','field-details',section=>{for(const item of game.analysis_context||[])section.append(el('strong','',item.topic),el('p','subtle',item.summary));}));
 return article;
}
function marketName(m){const kinds={total:'Góly spolu',team_total:'Tímové góly',handicap:'Handicap',moneyline:'Víťaz vrátane OT',regulation_1x2:'Výsledok 60 min',double_chance:'Dvojtip 60 min'};const sides={home:'domáci',away:'hostia',draw:'remíza',over:'nad',under:'pod'};return `${kinds[m.kind]||m.kind} · ${sides[m.selection]||m.selection}${m.line!==null?' '+(m.kind==='handicap'&&m.line>0?'+':'')+m.line:''}${m.team?' ('+(sides[m.team]||m.team)+')':''}`;}
async function loadArchive(){try{const data=await get('/api/days');$('archive').replaceChildren();if(!data.days.length){$('archive').append(el('p','subtle','Prvý spracovaný deň sa objaví tu.'));return;}for(const day of data.days){const row=el('div','archive-row'),link=el('a','',dateLabel(day.day));link.href='?day='+day.day;link.addEventListener('click',event=>{event.preventDefault();$('day').value=day.day;select();});row.append(link,el('small','',`${day.game_count} zápasov · ${states[day.state]||day.state}`));$('archive').append(row);}}catch{$('archive').replaceChildren(el('p','subtle','Archív je dočasne nedostupný.'));}}
function select(){if(!/^\d{4}-\d{2}-\d{2}$/.test($('day').value))return;history.replaceState(null,'','?day='+$('day').value);load();loadArchive();}
function move(delta){const date=new Date($('day').value+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+delta);$('day').value=date.toISOString().slice(0,10);select();}
const query=new URLSearchParams(location.search).get('day');$('day').value=query&&/^\d{4}-\d{2}-\d{2}$/.test(query)&&!Number.isNaN(Date.parse(query))?query:nhlToday();$('prev').onclick=()=>move(-1);$('next').onclick=()=>move(1);$('day').onchange=select;$('refresh').onclick=select;load();loadArchive();
