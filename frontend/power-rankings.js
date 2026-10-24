'use strict';
(()=>{
 const i18n=window.NHL_I18N,t=i18n.t,$=id=>document.getElementById(id),element=(tag,cls,text)=>{const node=document.createElement(tag);if(cls)node.className=cls;if(text!==undefined)node.textContent=text;return node;};
 let data=null,state='loading',view=new URLSearchParams(location.search).get('view')==='conferences'?'conferences':'league';
 const sorted=teams=>[...teams].sort((a,b)=>b.rating-a.rating||a.name.localeCompare(b.name));
 function table(title,teams){
  const panel=element('section','standings-panel'),heading=element('div','standings-heading');heading.append(element('h2','',title),element('span','',t('{count} tímov',{count:teams.length})));panel.append(heading);
  const table=element('table','standings-table');table.append(element('caption','visually-hidden',title+' – Power Rankings'));const columns=document.createElement('colgroup');columns.append(element('col','rank-column'),element('col',''),element('col','rating-column'));table.append(columns);
  const head=document.createElement('thead'),labels=document.createElement('tr');for(const label of ['#',t('TÍM'),'POWER RANKING']){const cell=element('th','',label);cell.scope='col';labels.append(cell);}head.append(labels);table.append(head);
  const body=document.createElement('tbody');sorted(teams).forEach((team,index)=>{
   const row=document.createElement('tr');row.dataset.team=team.code;row.dataset.rating=team.rating;row.append(element('td','team-rank'+(index<3?' leading':''),index+1));const cell=document.createElement('td'),copy=element('div','standings-team'),logo=element('span','standings-logo'),image=document.createElement('img');image.src='assets/teams/'+team.code+'.svg';image.alt='';image.width=40;image.height=40;image.decoding='async';logo.append(image);copy.append(logo,element('span','standings-team-name',team.name));cell.append(copy);row.append(cell,element('td','team-rating',String(Math.round(team.rating))));body.append(row);
  });table.append(body);panel.append(table);return panel;
 }
 function render(){
  const ready=state==='ready',conferences=view==='conferences';$('standings').hidden=!ready;$('ranking-status').hidden=ready;$('ranking-meta').hidden=!ready;$('ranking-retry').hidden=state==='loading';$('ranking-retry').disabled=state==='loading';$('standings').classList.toggle('conferences',conferences);
  document.querySelectorAll('[data-view]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.view===view)));
  $('ranking-season').textContent=data?.season&&/^\d{8}$/.test(data.season)?data.season.slice(0,4)+' / '+data.season.slice(6):'—';
  if(!ready){$('standings').replaceChildren();$('ranking-meta').replaceChildren();$('view-status').textContent='';$('ranking-status-text').textContent=t(state==='error'?'Rebríček sa nepodarilo načítať.':state==='empty'?'Power Rankings zatiaľ nie sú pripravené.':'Načítavam Power Rankings...');return;}
  $('standings').replaceChildren(...(conferences?[table(t('Východná konferencia'),data.teams.filter(team=>team.conference==='Eastern')),table(t('Západná konferencia'),data.teams.filter(team=>team.conference==='Western'))]:[table(t('Celá liga'),data.teams)]));
  const date=new Intl.DateTimeFormat(i18n.locale,{day:'numeric',month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(data.as_of_day+'T12:00:00Z'));
  $('ranking-meta').replaceChildren(element('span','',t('Stav pred hracím dňom {date}',{date})));$('view-status').textContent=t(conferences?'Zobrazené dve konferencie, každá so 16 tímami.':'Zobrazená celá liga, 32 tímov.');
 }
 async function load(){
  state='loading';render();
  try{
   const api=window.NHL_CONFIG?.apiBase?.replace(/\/$/,'');if(!api)throw new Error('Missing API configuration');const response=await fetch(api+'/api/power-rankings',{cache:'no-store',signal:AbortSignal.timeout(60000)});if(!response.ok)throw new Error('HTTP '+response.status);const incoming=await response.json();
   if(incoming.state==='AWAITING_DATA'){data=incoming;state='empty';}
   else{
    const teams=incoming.teams;if(incoming.state!=='READY'||!Array.isArray(teams)||teams.length!==32||new Set(teams.map(team=>team.code)).size!==32||!teams.every(team=>/^[A-Z]{3}$/.test(team.code)&&typeof team.name==='string'&&team.name.trim()&&Number.isFinite(team.rating))||['Eastern','Western'].some(conference=>teams.filter(team=>team.conference===conference).length!==16)||!/^\d{4}-\d{2}-\d{2}$/.test(incoming.as_of_day)||Number.isNaN(Date.parse(incoming.as_of_day)))throw new Error('Incomplete rankings');
    data=incoming;state='ready';
   }
  }catch{data=null;state='error';}
  render();
 }
 document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',()=>{view=button.dataset.view;const url=new URL(location.href);url.searchParams.set('view',view);history.replaceState(null,'',url.pathname+url.search+url.hash);render();}));
 $('ranking-retry').onclick=load;i18n.init(render);load();
})();
