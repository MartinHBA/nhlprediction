'use strict';
const $=id=>document.getElementById(id);
const api=()=>window.NHL_CONFIG?.apiBase?.replace(/\/$/,'');
const i18n=window.NHL_I18N,t=i18n.t;
const states={PREFILLING:'Načítavam štatistiky',COLLECTING:'Zber dát',READY_FOR_ANALYSIS:'Dáta pripravené na analýzu',COMPLETE:'Analýza uložená',ERROR:'Zber dát zlyhal',NO_GAMES:'Bez zápasov'};
const teamNames={ANA:'Ducks',BOS:'Bruins',BUF:'Sabres',CAR:'Hurricanes',CBJ:'Blue Jackets',CGY:'Flames',CHI:'Blackhawks',COL:'Avalanche',DAL:'Stars',DET:'Red Wings',EDM:'Oilers',FLA:'Panthers',LAK:'Kings',MIN:'Wild',MTL:'Canadiens',NJD:'Devils',NSH:'Predators',NYI:'Islanders',NYR:'Rangers',OTT:'Senators',PHI:'Flyers',PIT:'Penguins',SEA:'Kraken',SJS:'Sharks',STL:'Blues',TBL:'Lightning',TOR:'Maple Leafs',UTA:'Mammoth',VAN:'Canucks',VGK:'Golden Knights',WPG:'Jets',WSH:'Capitals'};
const teamLocations={ANA:'ANAHEIM',BOS:'BOSTON',BUF:'BUFFALO',CAR:'CAROLINA',CBJ:'COLUMBUS',CGY:'CALGARY',CHI:'CHICAGO',COL:'COLORADO',DAL:'DALLAS',DET:'DETROIT',EDM:'EDMONTON',FLA:'FLORIDA',LAK:'LOS ANGELES',MIN:'MINNESOTA',MTL:'MONTRÉAL',NJD:'NEW JERSEY',NSH:'NASHVILLE',NYI:'NEW YORK',NYR:'NEW YORK',OTT:'OTTAWA',PHI:'PHILADELPHIA',PIT:'PITTSBURGH',SEA:'SEATTLE',SJS:'SAN JOSE',STL:'ST. LOUIS',TBL:'TAMPA BAY',TOR:'TORONTO',UTA:'UTAH',VAN:'VANCOUVER',VGK:'VEGAS',WPG:'WINNIPEG',WSH:'WASHINGTON'};
let current=null,serial=0,poll,detailRequest=null,archiveData=null,archiveFailed=false,loadFailure=null;
let countdownData=null,countdownKnown=false;
const POLL_INTERVAL=5*60*1000;
const MIN_PICK_ODDS=1.18,MAX_PICK_ODDS=1.35;
function currentRecommendation(result){const m=result?.recommendation,price=m?.price??(m?.odds_type==='ESTIMATED'?m?.estimated_odds:m?.odds);return typeof price==='number'&&Number.isFinite(price)&&price>=MIN_PICK_ODDS&&price<=MAX_PICK_ODDS?m:null;}
const statFields={games:'Odohrané zápasy',points_pct:'Bodová úspešnosť (%)',xgf_5v5_per60:'xG za 60 min · 5 na 5',xga_5v5_per60:'xG súpera za 60 min · 5 na 5',pp_xgf_per60:'xG za 60 min · presilovka',pk_xga_per60:'xG súpera za 60 min · oslabenie',pp_drawn_per_game:'Vynútené tresty na zápas',penalties_taken_per_game:'Tresty na zápas',shots_for_per_game:'Strely na zápas',shots_against_per_game:'Strely súpera na zápas',high_danger_for_per60:'Veľké šance za 60 min',high_danger_against_per60:'Veľké šance súpera za 60 min'};
const fieldDefinitions={prior:{label:'Minulá sezóna',fields:statFields},current:{label:'Aktuálna sezóna',fields:statFields},goalie:{label:'Brankár',fields:{name:'Meno',status:'Potvrdenie brankára',starts_current:'Štarty · aktuálna sezóna',starts_prior:'Štarty · minulá sezóna',gsax_per60_current:'GSAx za 60 min · aktuálna sezóna',gsax_per60_prior:'GSAx za 60 min · minulá sezóna',save_pct_current:'SV% · aktuálna sezóna',save_pct_prior:'SV% · minulá sezóna'}},roster:{label:'Zostava',fields:{quantified:'Vyčíslený vplyv zostavy',attack_delta_goals:'Vplyv na strelené góly',defense_delta_goals_allowed:'Vplyv na inkasované góly',absences:'Absencie',returns:'Návraty',scratches:'Vynechaní hráči',forward_lines:'Útočné formácie',defense_pairs:'Obranné dvojice',power_play_units:'Presilovkové jednotky'}},context:{label:'Oddych a cestovanie',fields:{rest_days:'Dni oddychu',travel_timezones:'Prekonané časové pásma'}}};
const fieldGroups=Object.fromEntries(["goalie","roster","context","current","prior"].map(key=>[key,fieldDefinitions[key]]));
// Direction describes this individual metric, not an overall team rating.
const statDirections={games:0,points_pct:1,xgf_5v5_per60:1,xga_5v5_per60:-1,pp_xgf_per60:1,pk_xga_per60:-1,pp_drawn_per_game:1,penalties_taken_per_game:-1,shots_for_per_game:1,shots_against_per_game:-1,high_danger_for_per60:1,high_danger_against_per60:-1};
const fieldDirections={prior:statDirections,current:statDirections,goalie:{name:0,status:0,starts_current:0,starts_prior:0,gsax_per60_current:1,gsax_per60_prior:1,save_pct_current:1,save_pct_prior:1},roster:{quantified:0,attack_delta_goals:1,defense_delta_goals_allowed:-1,absences:0,returns:0,scratches:0,forward_lines:0,defense_pairs:0,power_play_units:0},context:{rest_days:1,travel_timezones:-1}};
function fieldComparison(pair,game){
 const [,group,key]=pair[0].path.split('.'),direction=fieldDirections[group]?.[key]||0;
 const digits=pair.some(field=>field.found&&typeof field.value==='number'&&Number.isFinite(field.value)&&!Number.isInteger(field.value))?3:0;
 const values=pair.map(field=>field.found?fieldValue(field.value,field.path,digits):'N/A');
 let note=t(direction>0?'Vyššia hodnota je priaznivejšia.':direction<0?'Nižšia hodnota je priaznivejšia.':'Tento údaj nemá jednoznačne lepšiu vyššiu alebo nižšiu hodnotu.');
 if(key==='games'||key.startsWith('starts_'))note=t('Počet zápasov vyjadruje veľkosť vzorky, nie lepšiu výkonnosť.');
 if(group==='goalie'&&key==='status')note=t('Potvrdenie vyjadruje istotu štartu, nie kvalitu brankára.');
 if(group==='roster'&&direction)note+=' '+t('Porovnávajú sa iba vyčíslené dopady zostáv oboch tímov.');
 const comparable=direction&&pair.every(field=>field.found&&typeof field.value==='number'&&Number.isFinite(field.value))&&(group!=='roster'||['away','home'].every(side=>game[side]?.roster?.quantified===true&&game.checks?.fields?.[`${side}.roster.quantified`]?.status==='found'));
 let preferred=null;
 if(comparable&&values[0]!==values[1])preferred=(pair[0].value-pair[1].value)*direction>0?'away':'home';
 return {note,preferred,values};
}
function fieldEntries(game){
 const entries=[];
 for(const side of ['away','home'])for(const [group,definition] of Object.entries(fieldGroups))for(const [field,label] of Object.entries(definition.fields)){
  const path=`${side}.${group}.${field}`,value=game[side]?.[group]?.[field],check=game.checks?.fields?.[path];
  const status=check?.status||'not_checked',found=status==='found'&&value!==null&&value!==undefined;
  entries.push({side,group,path,label:t(label),value,status,found,note:check?.note||''});
 }
 return entries;
}
function fieldValue(value,path='',digits=3){
 if(value===null||value===undefined)return 'N/A';
 if(typeof value==='boolean')return t(value?'Áno':'Nie');
 if(typeof value==='number'){
  if(!Number.isFinite(value))return 'N/A';
  const percent=path.includes('.save_pct_');
  const text=new Intl.NumberFormat('en-US',{useGrouping:false,minimumFractionDigits:digits,maximumFractionDigits:digits}).format(percent?value*100:value).replace(/^-0(\.0+)?$/,'0$1');
  return text+(percent?' %':'');
 }
 if(Array.isArray(value))return value.length?value.join('\n'):t('Prázdny zoznam');
 const labels={confirmed:'Potvrdený',probable:'Predpokladaný',unknown:'Neznámy'};
 return Object.hasOwn(labels,value)?t(labels[value]):String(value);
}
function disclosure(label,id,cls,build){
 const section=el('details',cls);section.dataset.section=id;section.append(el('summary','',t(label)));let built=false;
 section.prepare=()=>{if(!built){built=true;build(section);}};
 section.ontoggle=()=>{if(section.open)section.prepare();};return section;
}
function pendingDetails(label,id,cls,data){return disclosure(label,id,cls,section=>{
 section.append(el('p','subtle',t(data.details_error?'Podrobné dáta sa nepodarilo načítať.':'Načítavam podrobné dáta…')));
 if(data.details_error){const retry=el('button','detail-retry',t('Skúsiť znovu'));retry.onclick=()=>load({silent:true,overview:true});section.append(retry);}
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
  const block=el('details','field-group');block.dataset.section=`${section.dataset.section}-${group}`;block.append(el('summary','',t(definition.label)));
  for(const away of fields){
   const home=entries.find(e=>e.path===away.path.replace(/^away\./,'home.')),pair=[away,home];
   const row=el('div','field-row'),heading=el('div','field-heading'),values=el('div','field-values');row.dataset.metricPath=`${group}.${away.path.split('.').at(-1)}`;
   const comparison=fieldComparison(pair,game);
   const explanation=[comparison.note,...pair.filter(field=>field.note).map(field=>`${game[field.side].name}: ${field.note}`)].join('\n\n');
   const label=el(explanation?'button':'span','field-label',away.label);heading.append(label);row.append(heading);
   for(const [index,field] of pair.entries()){
    const cell=el('div','field-cell '+field.side);
    cell.dataset.fieldPath=field.path;cell.setAttribute('role','group');cell.setAttribute('aria-label',`${game[field.side].name}: ${field.label}`);
    const value=el('p','field-value'),text=comparison.values[index];
    if(comparison.preferred===field.side){value.append(el('strong','',text));cell.setAttribute('aria-label',`${game[field.side].name}: ${field.label} – ${t('priaznivejšia hodnota')}`);}else value.textContent=text;
    cell.append(value);values.append(cell);
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
const dateLabel=day=>new Intl.DateTimeFormat(i18n.locale,{day:'numeric',month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(day+'T12:00:00Z'));
const timeLabel=value=>value?new Intl.DateTimeFormat(i18n.locale,{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23',timeZone:'Europe/Bratislava'}).format(new Date(value)):'';
const nhlToday=()=>new Intl.DateTimeFormat('en-CA',{timeZone:'America/New_York',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
const scheduleZone=new Intl.DateTimeFormat('en-GB',{timeZone:'Europe/Bratislava',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
function defaultDisplayDay(){
 const parts=Object.fromEntries(scheduleZone.formatToParts(new Date()).map(p=>[p.type,p.value]));
 // Shift the calendar date in UTC, not by 24 local hours across DST changes.
 return new Date(Date.UTC(+parts.year,+parts.month-1,+parts.day-(+parts.hour<13?1:0))).toISOString().slice(0,10);
}
const scheduleTargets=new Map();
function dailyTarget(day,hour){
 const key=day+'|'+hour;if(scheduleTargets.has(key))return scheduleTargets.get(key);
 const [year,month,date]=day.split('-').map(Number),wall=Date.UTC(year,month-1,date,hour);
 let target=wall;
 for(let i=0;i<2;i++){const parts=Object.fromEntries(scheduleZone.formatToParts(new Date(target)).map(p=>[p.type,p.value]));target+=wall-Date.UTC(+parts.year,+parts.month-1,+parts.day,+parts.hour,+parts.minute,+parts.second);}
 scheduleTargets.set(key,target);return target;
}
const countdownClock=seconds=>[Math.floor(seconds/3600),Math.floor(seconds/60)%60,seconds%60].map(value=>String(value).padStart(2,'0')).join(':');
function inlineCountdown(stage){const node=el('span','inline-countdown card-countdown');node.dataset.countdownStage=stage;node.hidden=true;node.setAttribute('aria-live','off');node.title=t(stage==='data'?'Načítanie dát o {time}':'Začiatok analýzy o {time}',{time:stage==='data'?'18:00':'20:00'});return node;}
function updateCardCountdowns(root=$('games'),data=countdownData){
 const now=Date.now(),day=data?.day,today=countdownKnown&&day===$('day').value&&day===nhlToday()&&data?.state!=='NO_GAMES';
 const published=data?.final_tips?.state==='PUBLISHED'||data?.publication?.frozen,dataReady=published||!!data?.games?.length&&data.state!=='PREFILLING'&&!['SCHEDULED','QUEUED','RUNNING','ERROR'].includes(data.enrichment?.state);
 for(const node of root.querySelectorAll('.card-countdown')){
  const id=node.closest('.game-card')?.dataset.gameId,game=data?.games?.find(game=>String(game.game_id)===id),score=data?.scoreboard?.games?.find(game=>String(game.game_id)===id),stage=node.dataset.countdownStage;
  const upcoming=game&&(score?.status||game.status)==='scheduled'&&Date.parse(game.starts_at)>now,decided=published||data?.final_tips?.games?.some(game=>String(game.game_id)===id);
  const remaining=today?Math.max(0,Math.ceil((dailyTarget(day,stage==='data'?18:20)-now)/1000)):0;
  node.hidden=!today||!upcoming||(stage==='data'?dataReady:decided||remaining===0);
  if(!node.hidden){const text=t('Prosím počkajte...')+(remaining?' '+countdownClock(remaining):'');if(node.textContent!==text)node.textContent=text;}
 }
}
function updateCountdowns(){
 updateCardCountdowns();
 const data=countdownData,visible=countdownKnown&&$('day').value===nhlToday()&&data?.state!=='NO_GAMES';
 $('daily-countdowns').hidden=!visible;$('status').classList.toggle('has-countdowns',visible);
 if(!visible)return;
 const published=data?.final_tips?.state==='PUBLISHED',dataReady=published||!!data?.games?.length&&data.state!=='PREFILLING'&&data.enrichment?.state!=='SCHEDULED';
 for(const [stage,hour,ready,label,done,waiting] of [['data',18,dataReady,'Zápasy a prvé dáta','Dáta načítané','Čakáme na dáta'],['analysis',20,published,'Podrobná analýza a tipy','Analýza dokončená','Čakáme na analýzu']]){
  const remaining=Math.max(0,Math.ceil((dailyTarget($('day').value,hour)-Date.now())/1000)),node=$(stage+'-countdown');
  const text=ready?t(done):remaining?countdownClock(remaining):t(waiting);
  if(node.textContent!==text)node.textContent=text;
  node.classList.toggle('is-state',ready||!remaining);node.classList.toggle('is-complete',ready);
  $(stage+'-countdown-label').textContent=t(stage==='data'&&data?.enrichment?.state==='SCHEDULED'?'Rozšírené štatistiky':label);
  $(stage+'-countdown-note').textContent=t(stage==='data'?'Dnes o {time} · slovenský čas':'Začiatok o {time} · slovenský čas',{time:hour+':00'});
 }
}
function setCountdowns(data,known=true){countdownData=data;countdownKnown=known;updateCountdowns();}
function banner(title,detail,type='neutral',time=''){$('status').className='status-banner '+type;$('status-title').textContent=t(title);$('status-detail').textContent=t(detail);$('status-time').textContent=time;updateCountdowns();}
function empty(title,detail){const n=$('empty-template').content.cloneNode(true);n.querySelector('#empty-title').textContent=t(title);n.querySelector('#empty-detail').textContent=t(detail);$('games').replaceChildren(n);}
function teamIcon(code){const icon=el('span','team-code',code);if(Object.hasOwn(teamNames,code)){icon.classList.add('has-logo');const img=el('img','team-logo');img.src=`assets/teams/${code}.svg`;img.alt=t('Logo {team}',{team:teamNames[code]});img.width=48;img.height=48;img.loading='lazy';img.decoding='async';img.onerror=()=>{icon.classList.remove('has-logo');icon.replaceChildren(document.createTextNode(code));};icon.replaceChildren(img);}return icon;}
function reset(){current=null;setCountdowns(null,false);for(const id of ['metric-games','metric-reviewed','metric-goalies','metric-checks'])$(id).textContent='—';$('ticket').hidden=true;$('coverage').hidden=true;$('coverage-detail').textContent='';$('import-warnings').hidden=true;$('slate-count').textContent='';$('games').replaceChildren();}
async function get(path){if(!api())throw new Error(t('Chýba konfigurácia backendu.'));const r=await fetch(api()+path,{cache:'no-store',signal:AbortSignal.timeout(90000)});if(!r.ok){const e=new Error('HTTP '+r.status);e.status=r.status;throw e;}return r.json();}
function schedulePoll(){
 clearTimeout(poll);
 if(loadFailure==='missing'&&$('day').value===nhlToday()){
  const untilProgram=dailyTarget($('day').value,13)-Date.now();
  const delay=untilProgram>0?Math.min(POLL_INTERVAL,untilProgram):untilProgram>-5*60*1000?15000:POLL_INTERVAL;
  poll=setTimeout(()=>{if(document.hidden){schedulePoll();return;}return load({silent:true});},delay);return;
 }
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
 const token=++serial,day=$('day').value,quiet=silent&&(current?.day===day||loadFailure==='missing');
 clearTimeout(poll);
 if(!quiet){loadFailure=null;reset();$('refresh').disabled=true;banner('Načítavam prehľad',dateLabel(day));}
 try{
  let data;
  if(quiet&&current?.publication?.frozen&&!overview){
   const live=await get('/api/days/'+day+'/live');
   const samePublication=live.publication?.published_at===current.publication?.published_at;
   data=samePublication?{...current,...live}:await get('/api/days/'+day+'/cards');
  }else data=await get('/api/days/'+day+'/cards');
  if(token!==serial)return;
  loadFailure=null;
  if(!quiet||JSON.stringify(data)!==JSON.stringify(current))render(data);
  current=data;
  queueDetails(token,data);
 }catch(e){
  if(token!==serial||quiet)return;
  loadFailure=e.status===404?'missing':'error';showLoadFailure();
 }finally{
  if(token===serial){$('refresh').disabled=false;updateCountdowns();schedulePoll();}
 }
}
function showLoadFailure(){
 setCountdowns(null,loadFailure==='missing');
 if(loadFailure==='missing'){banner('Tento deň zatiaľ nebol spracovaný','Po dokončení dennej analýzy sa tu objavia výsledky.');empty('Na tento deň zatiaľ nemáme dáta','Vyber deň z archívu alebo počkaj na naplánovaný zber. Nezobrazujeme predikcie z iného dňa.');}
 else{banner('Backend sa nepodarilo načítať','Obnov stránku o chvíľu. Prvé načítanie po dlhšej nečinnosti môže trvať dlhšie.','error');empty('Prehľad je dočasne nedostupný','Skontroluj pripojenie a použi Obnoviť údaje.');}
}
function render(data){const coverage=data.coverage,stale=Date.now()-Date.parse(data.updated_at)>18*3600000;let title=states[data.state]||data.state,detail='';let type='neutral';
 setCountdowns(data);
 $('ticket').hidden=true;$('coverage').hidden=true;$('coverage-detail').textContent='';$('import-warnings').hidden=true;$('import-warning-list').replaceChildren();
 if(data.enrichment?.state==='SCHEDULED'&&data.state!=='NO_GAMES'){title='Program zápasov je pripravený';detail=t('Rozšírené štatistiky sa načítajú o 18:00 slovenského času.');}
 else if(data.state==='PREFILLING'){detail=t('Načítavam publikované štatistiky MoneyPuck a oficiálne údaje NHL.');}
 else if(data.state==='NO_GAMES'){detail=t('Oficiálny program na tento deň neobsahuje zápasy základnej časti.');}
 else if(data.state==='ERROR'){type='error';detail=data.error||t('Zber nebol dokončený.');}
 else{title='Čaká na finálnu analýzu';detail=t(data.research_complete?'Research je pripravený. Finálne výbery ešte nie sú uložené.':'Priebežné údaje. Prebieha dopĺňanie researchu a posúdenie zápasov.');}
 const final=data.final_tips;
 if(final?.state==='PUBLISHED'||(data.state!=='PREFILLING'&&data.state!=='NO_GAMES'&&data.state!=='ERROR')){
  if(final?.state==='PUBLISHED'){title='Analýza uložená';detail=t('Výbery v pásme 1,18–1,35: {tips} · Bez tipu: {noBet}.',{tips:final.tip_count||0,noBet:final.no_bet_count||0});type='';if(data.publication?.refresh_in_progress)detail+=' '+t('Pripravuje sa nová analýza; zatiaľ zostáva zobrazená posledná publikovaná.');}
  else if(final?.state==='NEEDS_REVIEW'){title='Výbery čakajú na nové overenie';detail=t('Podklady sa zmenili. Uložené výbery vyžadujú nové zhodnotenie.');type='warn';}
 }
 const odds=data.odds_import;if(odds&&!data.publication?.frozen){const available=odds.covered_games||0,total=odds.upcoming_games||0;if(odds.provider==='Oddize'&&odds.state==='SCHEDULED'){detail+=' '+t('Kurzy sa načítajú o 19:45 slovenského času.');}else if(odds.provider==='Oddize'&&['QUEUED','RUNNING'].includes(odds.state)){detail+=' '+t('Načítavam denný katalóg kurzov Oddize.');}else if(odds.provider==='Oddize'&&odds.state==='ERROR'){detail+=' '+t('Zber kurzov Oddize zlyhal; športový research môže pokračovať.');type='warn';}else if(['WAITING','ERROR'].includes(odds.state)&&total){detail+=' '+t('Čakáme na aktuálne kurzy NHL; zostávajúci research môže pokračovať.');type='warn';}else if(odds.state==='PARTIAL'){detail+=' '+t('Kurzy sú dostupné pre {available}/{total} nadchádzajúcich zápasov.',{available,total});type='warn';}}
 if(stale&&!data.publication?.frozen){type='warn';detail+=' '+t('Posledná aktualizácia je staršia než 18 hodín.');}
 banner(title,detail,type,t('Aktualizované {time}',{time:timeLabel(data.updated_at)}));
 $('metric-games').textContent=data.game_count;$('metric-reviewed').textContent=(final?.games||[]).length;$('metric-goalies').textContent=`${(data.games||[]).reduce((n,g)=>n+['home','away'].filter(s=>g[s].goalie.status==='confirmed').length,0)} / ${data.game_count*2}`;
 if(coverage){$('metric-checks').textContent=coverage.field_total?`${Math.round(coverage.field_found_pct)} %`:'—';$('metric-check-note').textContent=t('{found} / {total} polí so získanými údajmi',{found:coverage.field_found,total:coverage.field_total});$('coverage').hidden=false;$('coverage-detail').textContent=t('Dostupné údaje: {found} / {total}. Uzavreté kontroly: {closed} / {checks} (zahŕňajú aj nedostupné údaje).',{found:coverage.field_found,total:coverage.field_total,closed:coverage.checklist_closed,checks:coverage.checklist_total});}
 else{$('metric-checks').textContent='—';$('metric-check-note').textContent=t('skutočná dostupnosť zatiaľ nevyhodnotená');}
 if(odds){$('coverage').hidden=false;const last=odds.checked_at?timeLabel(odds.checked_at):'—',source=odds.source_updated_at?timeLabel(odds.source_updated_at):'—';$('coverage-detail').textContent+=' '+t(odds.provider==='Oddize'?'Kurzy Oddize: {covered}/{total} zápasov · {markets} ponúk. Denný zber o 19:45 slovenského času. Načítané {checked}, najnovšia cena {source}.':'Kurzy NHL/DraftKings: {covered}/{total} zápasov · {markets} ponúk. Kontrola {checked}, zdroj aktualizovaný {source}. Kontrola každých 30 minút od 18:00 do 08:00 slovenského času.',{covered:odds.covered_games||0,total:odds.upcoming_games||0,markets:odds.market_count||0,checked:last,source});}
 if(data.publication?.frozen){$('coverage').hidden=false;$('coverage-detail').textContent+=' '+t('Podklady a kurzy uložené pri analýze: {time}. Ďalej sa obnovuje iba skóre do 08:00 slovenského času.',{time:timeLabel(data.publication.published_at)});}
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
 updateCardCountdowns();
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
 if(!final){const waiting=el('p','subtle',t('Čaká na finálny výber.'));waiting.append(inlineCountdown('analysis'));box.append(waiting);return box;}
 const needsReview=final.state==='NEEDS_REVIEW';
 const outcome=final.settlement?.state,labels={WIN:'SPRÁVNY TIP',LOSS:'NESPRÁVNY TIP',PUSH:'NA HRANICI · PUSH',UNAVAILABLE:'VÝSLEDOK SA NEDÁ URČIŤ'};
 if(final.decision==='TIP'&&labels[outcome]){box.classList.add('outcome-'+outcome.toLowerCase());box.append(el('p','settlement '+outcome.toLowerCase(),t(labels[outcome])));}
 if(needsReview){box.classList.add('needs-review');box.append(el('p','caution',t('Podklady sa zmenili – výber vyžaduje nové overenie.')));}
 if(final.decision==='NO_BET'){box.append(el('p','eyebrow',t(needsReview?'BEZ TIPU / NA NOVÉ OVERENIE':'BEZ TIPU')));const text=noBetCopy(final.rationale);if(text)box.append(el('p','subtle',text));}
 else{
  const m=currentRecommendation({recommendation:final.candidate});
  if(!m){box.append(el('p','subtle',t('Bez aktuálneho výberu v pásme 1,18–1,35.')));return box;}
  const estimated=m.odds_type==='ESTIMATED';
  box.append(el('p','eyebrow',t(needsReview?'VÝBER / NA NOVÉ OVERENIE':estimated?'PREDBEŽNÝ TIP':'TIP')));
  const selection={...m};if(m.kind==='handicap'){selection.selection=game[m.selection]?.name||m.selection;selection.team=null;}if(m.kind==='moneyline'||m.kind==='regulation_1x2')selection.selection=game[m.selection]?.name||m.selection;
  box.append(el('h3','',marketName(selection)));
  const facts=el('div','recommendation-facts'),price=m.price??(estimated?m.estimated_odds:m.odds);
  const odds=el('span','',t(estimated?'Odhad kurzu ≈{price}':'Kurz {price}',{price:price.toLocaleString(i18n.locale,{minimumFractionDigits:2,maximumFractionDigits:2})}));
  odds.title=t(estimated?'Približná cena odvodená z kurzov, nie overená ponuka bookmakera.':'Overený kurz so zdrojom a časom kontroly.');
  const quality={LOW:'nízka',MODERATE:'stredná',HIGH:'vysoká'},confidence=el('span','recommendation-confidence',t('Confidence: {quality}',{quality:t(quality[final.confidence||m.confidence]||'neurčená')}));
  confidence.title=t('Kvalita analytických podkladov a záveru, nie vypočítaná pravdepodobnosť alebo garantovaná úspešnosť.');
  facts.append(odds,confidence);
  if(m.provider==='Oddize')facts.append(el('span','',t(m.score_basis==='regulation'?'60 minút':'Vrátane OT/SO')));
  box.append(facts);
 }
 const copy=detailCopy(final);
 if(data.details_loaded===false)box.append(pendingDetails('Zdôvodnenie','tip-analysis','tip-analysis',data));
 else if(copy.paragraphs.length||copy.risks.length)box.append(disclosure('Zdôvodnenie','tip-analysis','tip-analysis',section=>{
  copy.paragraphs.forEach(text=>section.append(el('p','subtle',text)));
  if(copy.risks.length){const risks=el('ul','issue-list');copy.risks.forEach(risk=>risks.append(el('li','',risk)));section.append(risks);}
 }));return box;
}
function card(game,data){
 const article=el('article','game-card'),head=el('div','game-head');article.dataset.gameId=String(game.game_id);head.append(el('span','',timeLabel(game.starts_at)+' · '+t('SEČ/SELČ')));
 const decision=data.final_tips?.games?.find(g=>g.game_id===String(game.game_id)),currentDecision=!!decision,score=data.scoreboard?.games?.find(g=>g.game_id===String(game.game_id)),gameStatus=score?.status||game.status;
 article.renderKey=JSON.stringify([game,decision,data.elo?.games?.[String(game.game_id)],data.goalie_appearances?.[String(game.game_id)],data.details_loaded,data.details_error,i18n.language]);
 const label=gameStatus==='final'?'UKONČENÉ':gameStatus==='live'?'PREBIEHA':gameStatus==='postponed'?'ODLOŽENÉ':currentDecision?(decision.decision==='TIP'?'TIP':'BEZ TIPU'):'RESEARCH';
 head.append(el('span','badge '+(currentDecision?'':game.status==='scheduled'?'warn':'muted'),t(label)));article.append(head);
 const matchup=el('div','matchup');for(const side of ['away','home']){if(side==='home')matchup.append(el('span','versus','VS'));const team=el('div','team '+side),code=game[side].name,icon=teamIcon(code),name=el('div','team-name'),rating=data.elo?.games?.[String(game.game_id)]?.[side];if(data.elo?.state==='READY'&&typeof rating==='number'&&Number.isFinite(rating)){const elo=el('span','team-elo',String(Math.round(rating)));elo.title=t('Power Ranking pred zápasom: {rating} · sezóna {season}',{rating:Math.round(rating),season:data.elo.season});icon.append(elo);}if(Object.hasOwn(teamLocations,code))name.append(el('small','',teamLocations[code]));name.append(el('strong','',teamNames[code]||code));team.append(...(side==='home'?[name,icon]:[icon,name]));matchup.append(team);}article.append(matchup);
 const goalies=el('div','goalie-summary');for(const side of ['away','home']){const goalie=game[side].goalie||{},entry=el('div','goalie-preview '+side),count=data.goalie_appearances?.[String(game.game_id)]?.[side],knownCount=Number.isInteger(count)&&count>=0,name=el('span','goalie-name',goalie.name?`${goalie.name} (${knownCount?count:'N/A'})`:'N/A');name.title=t('Počet zápasov v aktuálnej sezóne pred týmto zápasom, vrátane striedaní.');entry.append(name,el('small','goalie-status',t({confirmed:'Potvrdený',probable:'Predpokladaný',unknown:'Neznámy'}[goalie.status]||'Neznámy')));goalies.append(entry);}article.append(goalies);
 if(score&&Number.isInteger(score.home_score)&&Number.isInteger(score.away_score)&&['live','final'].includes(gameStatus)){const row=el('div','match-score'),extra=score.last_period_type==='OT'?t(gameStatus==='final'?'PP':'PREDĹŽENIE'):score.last_period_type==='SO'?t(gameStatus==='final'?'SN':'NÁJAZDY'):gameStatus==='live'&&score.period?t('{period}. tretina',{period:score.period}):'';row.append(el('strong','',String(score.away_score)),el('span','',t(gameStatus==='final'?'KONEČNÉ SKÓRE':'PRIEBEŽNÉ SKÓRE')+(extra?' · '+extra:'')),el('strong','',String(score.home_score)));article.insertBefore(row,goalies);}
 if(decision||gameStatus==='scheduled')article.append(recommendationBlock(decision,game,data));
 let details;
 if(data.details_loaded===false)details=pendingDetails('Dáta','fields','field-details',data);
 else if(data.publication?.frozen&&data.publication.snapshot_available===false){details=el('details','field-details');details.dataset.section='fields';details.append(el('summary','',t('Dáta')),el('p','subtle',t('Presné pôvodné podklady analýzy sa nepodarilo obnoviť. Aktuálne štatistiky ich nenahrádzajú.')));}else details=fieldDetails(game);
 const summary=details.querySelector('summary'),copy=el('span','field-summary-copy');copy.append(...summary.childNodes,inlineCountdown('data'));summary.replaceChildren(copy);
 const available=data.coverage?.games?.find(g=>String(g.game_id)===String(game.game_id));
 if(available)details.querySelector('summary').after(el('p','subtle fields-coverage',t('{found}/{total} dostupných polí',{found:available.field_found,total:available.field_total})));
 article.append(details);
 if(game.has_analysis_context||game.analysis_context?.length)article.append(data.details_loaded===false?pendingDetails('Doplňujúci research','analysis-context','field-details',data):disclosure('Doplňujúci research','analysis-context','field-details',section=>{for(const item of game.analysis_context||[])section.append(el('strong','',item.topic),el('p','subtle',item.summary));}));
 updateCardCountdowns(article,data);return article;
}
function marketName(m){const kinds={total:'Góly spolu',team_total:'Tímové góly',handicap:'Handicap',moneyline:'Víťaz vrátane OT',regulation_1x2:'Výsledok 60 min',double_chance:'Dvojtip 60 min'};const sides={home:'domáci',away:'hostia',draw:'remíza',over:'nad',under:'pod'};return `${t(kinds[m.kind]||m.kind)} · ${t(sides[m.selection]||m.selection)}${m.line!==null&&m.line!==undefined?' '+(m.kind==='handicap'&&m.line>0?'+':'')+Number(m.line).toLocaleString(i18n.locale,{useGrouping:false}):''}${m.team?' ('+t(sides[m.team]||m.team)+')':''}`;}
function renderArchive(){
 $('archive').replaceChildren();
 if(archiveFailed){$('archive').append(el('p','subtle',t('Archív je dočasne nedostupný.')));return;}
 if(!archiveData){$('archive').append(el('p','subtle',t('Načítavam…')));return;}
 if(!archiveData.days.length){$('archive').append(el('p','subtle',t('Prvý spracovaný deň sa objaví tu.')));return;}
 for(const day of archiveData.days){const row=el('div','archive-row'),link=el('a','',dateLabel(day.day)),url=new URL(location.href);url.searchParams.set('day',day.day);link.href=url.pathname+url.search;link.addEventListener('click',event=>{event.preventDefault();$('day').value=day.day;select();});row.append(link,el('small','',t('{count} zápasov · {state}',{count:day.game_count,state:t(states[day.state]||day.state)})));$('archive').append(row);}
}
async function loadArchive(){try{archiveData=await get('/api/days');archiveFailed=false;}catch{archiveFailed=true;}renderArchive();}
function select(){if(!/^\d{4}-\d{2}-\d{2}$/.test($('day').value))return;const url=new URL(location.href);url.searchParams.set('day',$('day').value);history.replaceState(null,'',url.pathname+url.search+url.hash);load();loadArchive();}
function move(delta){const date=new Date($('day').value+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+delta);$('day').value=date.toISOString().slice(0,10);select();}
const query=new URLSearchParams(location.search).get('day');$('day').value=query&&/^\d{4}-\d{2}-\d{2}$/.test(query)&&!Number.isNaN(Date.parse(query))?query:defaultDisplayDay();$('prev').onclick=()=>move(-1);$('next').onclick=()=>move(1);$('day').onchange=select;$('refresh').onclick=select;
i18n.init(()=>{if(current)render(current);else{ $('metric-check-note').textContent=t('skutočne vyplnené polia');if(loadFailure)showLoadFailure();else banner('Načítavam prehľad',dateLabel($('day').value));}renderArchive();});$('metric-check-note').textContent=t('skutočne vyplnené polia');load();loadArchive();
setInterval(updateCountdowns,1000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)updateCountdowns();});
