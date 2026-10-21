const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {JSDOM}=require('jsdom');
const html=fs.readFileSync(path.join(__dirname,'index.html'),'utf8');
const script=fs.readFileSync(path.join(__dirname,'app.js'),'utf8');
async function boot(dayResponse,status=200,setup=()=>{}){
  const dom=new JSDOM(html,{url:'https://nhl.example/?day=2026-10-20',runScripts:'outside-only'});
  const w=dom.window;
  w.NHL_CONFIG={apiBase:'https://api.nhl.example'};
  w.AbortSignal=AbortSignal;
  w.fetch=async url=>url.endsWith('/api/days')?{ok:true,json:async()=>({days:[]})}:{ok:status===200,status,json:async()=>structuredClone(dayResponse)};
  setup(w);
  w.eval(script);
  await new Promise(resolve=>setTimeout(resolve,15));
  return dom;
}
const fixture=()=>{
  const dataset=JSON.parse(fs.readFileSync(path.join(__dirname,'../examples/demo_games.json'),'utf8'));
  const report={status:'NO_BET',games:dataset.games.map(g=>({game_id:g.game_id,lambda_home:3,lambda_away:2,lambda_total:5}))};
  report.games.forEach(g=>{g.p_home_win=.6;g.p_away_win=.4;});
  return {checklist:report.checklist,day:'2026-10-20',state:'COMPLETE',game_count:3,updated_at:new Date().toISOString(),games:dataset.games,evidence:dataset.evidence,report};
};
test('renders research while ignoring all legacy model projections',async()=>{const d=await boot(fixture());try{const doc=d.window.document;assert.equal(doc.querySelectorAll('.game-card').length,3);assert.equal(doc.getElementById('metric-reviewed').textContent,'0');assert.equal(doc.querySelectorAll('.goal-projection,.prob-bar').length,0);assert.doesNotMatch(doc.getElementById('games').textContent,/simulácií|P\(model\)/);assert.equal(doc.querySelectorAll('.market-list,table').length,0);assert.equal(doc.getElementById('day').value,'2026-10-20');}finally{d.window.close();}});

function publish(f,index,candidate,extra={}){
 f.final_tips={state:'PUBLISHED',tip_count:1,no_bet_count:f.games.length-1,games:f.games.map(g=>({game_id:String(g.game_id),decision:'NO_BET',state:'CURRENT',rationale:'Pre tento zápas nevidím dostatočne podloženú výhodu.'}))};
 f.final_tips.games[index]={game_id:String(f.games[index].game_id),decision:'TIP',state:'CURRENT',candidate,rationale:'Research podporuje tento výber po posúdení zostáv a brankárov.',comparison:'Porovnanie víťaza, gólových hraníc a handicapu; ostatné možnosti majú väčšiu neistotu.',risks:['Kurz je potrebné overiť.'],...extra};
}

test('waits for AI publication and never presents a model preference as the final tip',async()=>{
 const f=fixture();f.report.games[0].recommendation={kind:'total',selection:'under',line:9.5,odds:1.04};f.report.games[0].model_preference={kind:'total',selection:'under',line:8.5,odds:1.25};
 const d=await boot(f);try{const doc=d.window.document;assert.match(doc.getElementById('status-title').textContent,/Čaká na finálnu analýzu ChatGPT/);assert.equal(doc.querySelectorAll('.recommendation h3').length,0);assert.match(doc.querySelector('.recommendation').textContent,/Čaká na finálny výber ChatGPT/);}finally{d.window.close();}
});

test('shows the AI choice, rationale and confidence independently of the model preference',async()=>{
 const f=fixture();f.report.status='NO_BET';f.report.games[0].model_preference={kind:'total',selection:'under',line:8.5};
 publish(f,0,{kind:'handicap',selection:'away',team:'away',line:2.5,score_basis:'official_final',odds_type:'ESTIMATED',estimated_odds:1.25,p_win:.84,p_win_min:.75,minimum_odds_conservative:1/.75,recommendation_status:'PRELIMINARY',confidence:'LOW',confidence_reasons:['ESTIMATED_ODDS_NOT_VERIFIED','HOME_GOALIE_NOT_CONFIRMED'],robust_edge:false});
 const d=await boot(f);try{
  const doc=d.window.document,tip=doc.querySelector('.recommendation');assert.match(tip.textContent,/PREDBEŽNÝ TIP CHATGPT/);assert.match(tip.textContent,/Handicap/);assert.match(tip.textContent,/\+2,5/);assert.doesNotMatch(tip.textContent,/pod 8,5/);assert.match(tip.textContent,/≈1,25/);assert.doesNotMatch(tip.textContent,/84\.0 %|P\(model\)/);assert.match(tip.textContent,/Confidence: nízka/);
  assert.match(tip.querySelector('.recommendation-confidence').title,/Kvalita analytických/);assert.match(tip.querySelector('.tip-analysis').textContent,/Research podporuje/);
  assert.equal(doc.querySelectorAll('.recommendation h3').length,1);assert.equal(doc.querySelectorAll('table').length,0);assert.equal(tip.closest('details'),null);
  assert.match(doc.getElementById('status-title').textContent,/Analýza ChatGPT uložená/);assert.match(doc.getElementById('status-detail').textContent,/1,20–1,35: 1/);assert.equal(doc.getElementById('ticket').hidden,true);
  assert.match(doc.querySelectorAll('.recommendation')[1].textContent,/CHATGPT · BEZ TIPU/);
 }finally{d.window.close();}
});

test('actual quote and AI explanation are safe and use the requested price band',async()=>{
 const f=fixture();publish(f,0,{kind:'handicap',selection:'away',team:'away',line:2.5,score_basis:'official_final',odds_type:'QUOTED',odds:1.28,p_win:.9,scenario_ev_min:.05,minimum_odds_conservative:1.2,recommendation_status:'REVIEW_REQUIRED',confidence:'MODERATE',confidence_reasons:[]},{rationale:'<img src=x onerror=alert(1)> Analysis text'});
 const d=await boot(f);try{const tip=d.window.document.querySelector('.recommendation');assert.match(tip.textContent,/VÝBER CHATGPT/);assert.match(tip.textContent,/Kurz 1,28/);assert.match(tip.querySelector('h3').textContent,/\+2,5/);assert.doesNotMatch(tip.textContent,/≈/);assert.match(tip.querySelector('h3').textContent,new RegExp(f.games[0].away.name));assert.doesNotMatch(tip.textContent,/P\(model\)|EV|konzervatív/);assert.equal(tip.querySelectorAll('img').length,0);}finally{d.window.close();}
});

test('changed inputs mark the saved AI choice for review without replacing it',async()=>{
 const f=fixture();publish(f,0,{kind:'total',selection:'under',line:8.5,odds_type:'ESTIMATED',estimated_odds:1.25,p_win:.9,minimum_odds_conservative:1.45,robust_edge:false,recommendation_status:'PRELIMINARY',confidence:'LOW',confidence_reasons:['SCENARIO_EDGE_NOT_ROBUST']},{state:'NEEDS_REVIEW'});f.final_tips.state='NEEDS_REVIEW';
 const d=await boot(f);try{const doc=d.window.document,tip=doc.querySelector('.recommendation');assert.match(doc.getElementById('status-title').textContent,/nové overenie/);assert.ok(tip.classList.contains('needs-review'));assert.match(tip.textContent,/Podklady sa zmenili/);assert.match(tip.textContent,/pod 8,5/);assert.doesNotMatch(tip.textContent,/P\(model\)|konzervatívnom scenári/);assert.doesNotMatch(tip.textContent,/od 1,46 do 1,35/);}finally{d.window.close();}
});

test('hides any saved selection outside the inclusive 1.20 to 1.35 price band',async()=>{
 for(const price of [1.04,1.19999,1.20,1.35,1.35001]){
  const f=fixture();publish(f,0,{kind:'total',selection:'under',line:8.5,odds_type:'ESTIMATED',estimated_odds:price,p_win:.95,robust_edge:false,recommendation_status:'PRELIMINARY',confidence:'LOW',confidence_reasons:[]});
  const d=await boot(f);try{const inside=price>=1.20&&price<=1.35;assert.equal(d.window.document.querySelectorAll('.recommendation h3').length,inside?1:0,'price='+price);}finally{d.window.close();}
 }
});

test('all NO_BET decisions still finish AI analysis successfully',async()=>{
 const f=fixture();publish(f,0,null,{decision:'NO_BET'});f.final_tips.tip_count=0;f.final_tips.no_bet_count=3;
 const d=await boot(f);try{assert.match(d.window.document.getElementById('status-title').textContent,/Analýza ChatGPT uložená/);assert.equal(d.window.document.querySelectorAll('.recommendation h3').length,0);assert.match(d.window.document.getElementById('status-detail').textContent,/Bez tipu: 3/);}finally{d.window.close();}
});
test('does not reuse another day when selected date is absent',async()=>{const d=await boot({},404);try{assert.match(d.window.document.getElementById('status-title').textContent,/nebol spracovaný/);assert.equal(d.window.document.querySelectorAll('.game-card').length,0);}finally{d.window.close();}});
test('clears results and exposes backend failure',async()=>{const d=await boot({},503);try{assert.match(d.window.document.getElementById('status-title').textContent,/nepodarilo/);assert.equal(d.window.document.getElementById('metric-reviewed').textContent,'—');}finally{d.window.close();}});
test('empty official schedule is a successful day, not an error',async()=>{const d=await boot({day:'2026-10-20',state:'NO_GAMES',game_count:0,updated_at:new Date().toISOString(),games:[],evidence:[]});try{assert.match(d.window.document.getElementById('games').textContent,/Dnes má ľad voľno/);assert.equal(d.window.document.getElementById('metric-games').textContent,'0');}finally{d.window.close();}});
test('research text is rendered safely and unsafe source links are omitted',async()=>{const f=fixture();f.games[0].away.name='<img src=x onerror=alert(1)>';f.evidence[0].url='javascript:alert(1)';const d=await boot(f);try{assert.equal(d.window.document.querySelector('.team.away').querySelectorAll('img').length,0);assert.equal(d.window.document.querySelectorAll('a[href^="javascript:"]').length,0);assert.match(d.window.document.getElementById('games').textContent,/<img src=x/);for(const image of d.window.document.querySelectorAll('#games img'))assert.match(image.getAttribute('src'),/^assets\/teams\/[A-Z]{3}\.svg$/);}finally{d.window.close();}});
test('missing analytical data stays visible without blocking a Java model',async()=>{const f=fixture();f.state='COLLECTING';f.games[0].home.current.xgf_5v5_per60=null;f.games[0].checks.fields['home.current.xgf_5v5_per60']={status:'unavailable',note:'Source does not publish it'};const d=await boot(f);try{const doc=d.window.document;assert.match(doc.getElementById('status-title').textContent,/finálnu analýzu/);assert.equal(doc.querySelectorAll('.goal-projection,.prob-bar').length,0);assert.equal(doc.getElementById('metric-reviewed').textContent,'0');assert.match(doc.querySelector('[data-field-path="home.current.xgf_5v5_per60"]').textContent,/Nedostupné/);}finally{d.window.close();}});
test('closed unavailable checks never display as 100 percent available data',async()=>{const f=fixture();f.coverage={field_total:246,field_found:0,field_found_pct:0,critical_found:0,critical_total:96,critical_ready_games:0,scheduled_games:3,checklist_closed:276,checklist_total:276,games:[]};const d=await boot(f);try{const doc=d.window.document;assert.equal(doc.getElementById('metric-checks').textContent,'0 %');assert.match(doc.getElementById('coverage-detail').textContent,/276 \/ 276/);assert.match(doc.getElementById('coverage-detail').textContent,/0 \/ 246/);}finally{d.window.close();}});
test('prefill state hides old projections and shows source warnings safely',async()=>{const f=fixture();f.state='PREFILLING';f.enrichment={warnings:['<b>Source delayed</b>']};const d=await boot(f);try{const doc=d.window.document;assert.match(doc.getElementById('status-title').textContent,/Načítavam štatistiky/);assert.equal(doc.querySelectorAll('.goal-projection').length,0);assert.equal(doc.querySelectorAll('#import-warning-list b').length,0);assert.match(doc.getElementById('import-warning-list').textContent,/Source delayed/);}finally{d.window.close();}});
test('all NHL logos are available locally and failed images fall back to team code',async()=>{const f=fixture(),codes=['BOS','COL','MTL','SJS','UTA','SEA'];f.games.forEach((g,i)=>{g.home.name=codes[2*i];g.away.name=codes[2*i+1];});const d=await boot(f);try{const doc=d.window.document,images=doc.querySelectorAll('.team-logo');assert.equal(images.length,6);assert.equal(fs.readdirSync(path.join(__dirname,'assets/teams')).filter(f=>f.endsWith('.svg')).length,32);for(const img of images)assert.ok(fs.existsSync(path.join(__dirname,img.getAttribute('src'))));const img=images[0],parent=img.parentElement;img.dispatchEvent(new d.window.Event('error'));assert.equal(parent.querySelector('img'),null);assert.match(parent.textContent,/^[A-Z]{3}$/);}finally{d.window.close();}});
test('waiting for fresh NHL odds is distinct from a completed no-bet analysis',async()=>{const f=fixture();f.odds_import={state:'WAITING',covered_games:0,upcoming_games:3,market_count:0,checked_at:new Date().toISOString(),source_updated_at:new Date().toISOString()};const d=await boot(f);try{assert.match(d.window.document.getElementById('status-detail').textContent,/Čakáme na aktuálne kurzy/);assert.match(d.window.document.getElementById('coverage-detail').textContent,/0\/3 zápasov/);assert.match(d.window.document.getElementById('coverage-detail').textContent,/18:00/);}finally{d.window.close();}});

async function pollingPage(){
 const data=fixture();data.odds_import={state:'WAITING',upcoming_games:3};
 let scheduled;
 const dom=await boot(data,200,w=>{
  Object.defineProperty(w.document,'hidden',{configurable:true,value:false});
  w.setTimeout=(run,delay)=>{scheduled={run,delay};return 1;};
  w.clearTimeout=()=>{scheduled=null;};
 });
 return {dom,data,next:()=>scheduled};
}

test('automatic refresh waits five minutes and preserves visible cards while fetching unchanged data',async()=>{
 const {dom,data,next}=await pollingPage();
 try{
  const w=dom.window,doc=w.document,card=doc.querySelector('.game-card'),title=doc.getElementById('status-title').textContent;
  card.querySelector('details').open=true;
  let respond;
  w.fetch=()=>new Promise(resolve=>{respond=resolve;});
  assert.equal(next().delay,300000);
  const pending=next().run();
  assert.equal(doc.querySelector('.game-card'),card);
  assert.equal(doc.getElementById('status-title').textContent,title);
  assert.equal(doc.getElementById('refresh').disabled,false);
  respond({ok:true,json:async()=>structuredClone(data)});
  await pending;
  assert.equal(doc.querySelector('.game-card'),card);
  assert.equal(card.querySelector('details').open,true);
  assert.equal(next().delay,300000);
 }finally{dom.window.close();}
});

test('changed data updates only affected cards and preserves expanded details',async()=>{
 const {dom,data,next}=await pollingPage();
 try{
  const doc=dom.window.document,before=[...doc.querySelectorAll('.game-card')];
  before[0].querySelectorAll('details').forEach(detail=>{detail.open=true;});
  const changed=structuredClone(data);changed.games[0].home.goalie.name="Updated goalie";changed.enrichment={warnings:['Source delayed']};
  dom.window.fetch=async()=>({ok:true,json:async()=>changed});
  await next().run();
  const after=[...doc.querySelectorAll('.game-card')];
  assert.notEqual(after[0],before[0]);assert.match(after[0].textContent,/Updated goalie/);
  assert.equal(after[0].querySelector('details').open,true);
  for(const detail of after[0].querySelectorAll('details'))assert.equal(detail.open,true);
  assert.equal(after[1],before[1]);assert.equal(after[2],before[2]);
  assert.equal(doc.getElementById('import-warnings').hidden,false);
  dom.window.fetch=async()=>({ok:true,json:async()=>structuredClone(data)});
  await next().run();
  assert.equal(doc.getElementById('import-warnings').hidden,true);
  assert.equal(doc.querySelectorAll('#import-warning-list li').length,0);
  assert.equal(doc.querySelectorAll('.game-card').length,3);
 }finally{dom.window.close();}
});

test('temporary automatic refresh failure keeps the last results and retries later',async()=>{
 const {dom,next}=await pollingPage();
 try{
  const doc=dom.window.document,card=doc.querySelector('.game-card'),title=doc.getElementById('status-title').textContent;
  dom.window.fetch=async()=>{throw new Error('Offline');};
  await next().run();
  assert.equal(doc.querySelector('.game-card'),card);
  assert.equal(doc.getElementById('status-title').textContent,title);
  assert.equal(next().delay,300000);
 }finally{dom.window.close();}
});

test('automatic refresh skips hidden tabs and resumes on the next visible check',async()=>{
 const {dom,data,next}=await pollingPage();
 try{
  let requests=0;
  dom.window.fetch=async()=>{requests++;return {ok:true,json:async()=>structuredClone(data)};};
  Object.defineProperty(dom.window.document,'hidden',{configurable:true,value:true});
  await next().run();assert.equal(requests,0);assert.equal(next().delay,300000);
  Object.defineProperty(dom.window.document,'hidden',{configurable:true,value:false});
  await next().run();assert.equal(requests,1);
 }finally{dom.window.close();}
});

test('a pending automatic response cannot overwrite a newly selected day',async()=>{
 const {dom,data,next}=await pollingPage();
 try{
  const w=dom.window,doc=w.document;
  let respond;
  w.fetch=()=>new Promise(resolve=>{respond=resolve;});
  const pending=next().run();
  w.fetch=async url=>url.endsWith('/api/days')?{ok:true,json:async()=>({days:[]})}:{ok:false,status:404};
  doc.getElementById('day').value='2026-10-21';
  doc.getElementById('day').dispatchEvent(new w.Event('change'));
  await new Promise(resolve=>setTimeout(resolve,15));
  respond({ok:true,json:async()=>structuredClone(data)});await pending;
  assert.equal(doc.getElementById('day').value,'2026-10-21');
  assert.equal(doc.querySelectorAll('.game-card').length,0);
  assert.match(doc.getElementById('status-title').textContent,/nebol spracovaný/);
  assert.equal(next(),null);
 }finally{dom.window.close();}
});

test('published predictions and reviewed counts survive final games with scores and win/loss badges',async()=>{
 const f=fixture();f.publication={frozen:true,snapshot_available:true,published_at:'2026-10-20T21:16:00Z'};
 publish(f,0,{kind:'total',selection:'under',line:8.5,score_basis:'official_final',odds_type:'QUOTED',odds:1.25,confidence:'MODERATE'});
 f.final_tips.games[0].state='PUBLISHED';f.final_tips.games[0].settlement={state:'WIN'};
 f.scoreboard={games:f.games.map(g=>({game_id:String(g.game_id),status:'final',away_score:4,home_score:3,last_period_type:'OT'}))};
 const d=await boot(f);try{const doc=d.window.document;assert.equal(doc.getElementById('metric-reviewed').textContent,'3');assert.match(doc.querySelector('.recommendation').textContent,/SPRÁVNY TIP/);assert.equal(doc.querySelectorAll('.match-score').length,3);assert.match(doc.querySelector('.match-score').textContent,/KONEČNÉ SKÓRE/);assert.match(doc.querySelectorAll('.recommendation')[1].textContent,/BEZ TIPU/);assert.equal(doc.querySelectorAll('.recommendation')[1].querySelector('.settlement'),null);}finally{d.window.close();}
});
test('statistics have one disclosure in the requested order with sticky team labels',async()=>{
 const f=fixture();f.publication={frozen:false,snapshot_available:false};const d=await boot(f);try{const doc=d.window.document,card=doc.querySelector('.game-card'),fields=card.querySelector('[data-section="fields"]');assert.equal(card.querySelector('[data-section="critical"]'),null);assert.deepEqual([...fields.querySelectorAll('.field-group>summary')].map(n=>n.textContent),['Brankár','Zostava','Oddych a cestovanie','Aktuálna sezóna','Minulá sezóna']);assert.equal(fields.querySelectorAll('.comparison-team').length,2);assert.match(fs.readFileSync(path.join(__dirname,'styles.css'),'utf8'),/\.comparison-teams\{position:sticky;top:0/);}finally{d.window.close();}
});
test('after publication silent refresh fetches scores only and preserves frozen statistics',async()=>{
 const {dom,data,next}=await pollingPage();try{
  const doc=dom.window.document;data.publication={frozen:true,snapshot_available:true,published_at:'2026-10-20T21:16:00Z'};publish(data,0,null,{decision:'NO_BET'});dom.window.fetch=async()=>({ok:true,json:async()=>structuredClone(data)});await next().run();
  const fields=doc.querySelector('[data-section="fields"]'),before=fields.textContent,requests=[];
  dom.window.fetch=async url=>{requests.push(url);return {ok:true,json:async()=>({day:data.day,state:'COMPLETE',publication:data.publication,final_tips:data.final_tips,scoreboard:{games:[{game_id:String(data.games[0].game_id),status:'live',home_score:2,away_score:1,period:2}]}})};};
  await next().run();assert.equal(requests.length,1);assert.match(requests[0],/\/live$/);assert.equal(doc.querySelector('[data-section="fields"]').textContent,before);assert.match(doc.querySelector('.match-score').textContent,/PRIEBEŽNÉ SKÓRE/);
 }finally{dom.window.close();}
});
test('new GPT publication reloads statistics exactly once after live snapshot signals the change',async()=>{
 const {dom,data,next}=await pollingPage();try{
  data.publication={frozen:true,snapshot_available:true,published_at:'2026-10-20T21:16:00Z'};dom.window.fetch=async()=>({ok:true,json:async()=>structuredClone(data)});await next().run();
  const updated=structuredClone(data);updated.publication.published_at='2026-10-20T22:00:00Z';updated.games[0].home.goalie.name='New publication goalie';const requests=[];
  dom.window.fetch=async url=>{requests.push(url);return {ok:true,json:async()=>url.endsWith('/live')?{day:data.day,publication:updated.publication}:updated};};await next().run();assert.equal(requests.length,2);assert.match(requests[0],/\/live$/);assert.match(dom.window.document.querySelector('.game-card').textContent,/New publication goalie/);
 }finally{dom.window.close();}
});
