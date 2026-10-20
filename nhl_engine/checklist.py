"""Source-backed completion audit for every input and market category.

Each field is separately acknowledged. An unavailable field is different from
an unchecked one. Unavailable does not silently become zero.
"""
from __future__ import annotations
from datetime import datetime, timezone
from typing import Any

MARKET_SEARCHES = ('total_over','total_under','handicap_home','handicap_away',
                   'moneyline_home','moneyline_away','regulation_1x2','double_chance',
                   'team_total_under','team_total_over_other')

def market_matches(search: str, offer: dict) -> bool:
    kind, sel = offer['kind'], offer['selection']
    if search=='total_over': return kind=='total' and sel=='over'
    if search=='total_under': return kind=='total' and sel=='under'
    if search=='handicap_home': return kind=='handicap' and sel=='home'
    if search=='handicap_away': return kind=='handicap' and sel=='away'
    if search=='moneyline_home': return kind=='moneyline' and sel=='home'
    if search=='moneyline_away': return kind=='moneyline' and sel=='away'
    if search=='regulation_1x2': return kind=='regulation_1x2'
    if search=='double_chance': return kind=='double_chance'
    if search=='team_total_under': return kind=='team_total' and sel=='under'
    if search=='team_total_over_other': return kind=='team_total' and sel=='over' and offer.get('line')!=2.5
    raise ValueError(search)
STAT_FIELDS = ('games','points_pct','xgf_5v5_per60','xga_5v5_per60','pp_xgf_per60','pk_xga_per60',
               'pp_drawn_per_game','penalties_taken_per_game','shots_for_per_game',
               'shots_against_per_game','high_danger_for_per60','high_danger_against_per60')
GOALIE_FIELDS = ('name','status','starts_current','starts_prior','gsax_per60_current','gsax_per60_prior')
ROSTER_FIELDS = ('quantified','attack_delta_goals','defense_delta_goals_allowed','absences',
                 'returns','scratches','forward_lines','defense_pairs','power_play_units')
CONTEXT_FIELDS = ('rest_days','travel_timezones')
# These fields must be observed to compute projections; optional metrics get
# documented as unavailable but don't halt the whole game.
MODEL_REQUIRED = tuple(
    f'{side}.{period}.{name}'
    for side in ('home','away') for period in ('prior','current')
    for name in ('games','xgf_5v5_per60','xga_5v5_per60','pp_xgf_per60','pk_xga_per60',
                 'pp_drawn_per_game','penalties_taken_per_game')
) + tuple(f'{side}.context.{name}' for side in ('home','away') for name in CONTEXT_FIELDS)


def required_paths() -> list[str]:
    out=[]
    for side in ('home','away'):
        for period in ('prior','current'):
            out += [f'{side}.{period}.{f}' for f in STAT_FIELDS]
        out += [f'{side}.goalie.{f}' for f in GOALIE_FIELDS]
        out += [f'{side}.roster.{f}' for f in ROSTER_FIELDS]
        out += [f'{side}.context.{f}' for f in CONTEXT_FIELDS]
    return out


def get_path(game: dict,path: str):
    obj=game
    for segment in path.split('.'):
        obj=obj[segment]
    return obj


def parse_time(value):
    d=datetime.fromisoformat(value.replace('Z','+00:00'))
    if d.tzinfo is None:raise ValueError('Timezone missing')
    return d.astimezone(timezone.utc)


def audit_checks(data:dict) -> dict:
    evidence={s['id']:s for s in data.get('evidence',[]) if isinstance(s,dict) and 'id' in s}
    issues=[]; reports=[]; total_found=total_unavail=total_not_checked=0
    as_of=parse_time(data['as_of'])
    if len(evidence)!=len(data.get('evidence',[])):
        issues.append('DUPLICATE_EVIDENCE_ID')
    for game in data['games']:
        gid=game['game_id']; start=parse_time(game['starts_at']); checks=game.get('checks',{})
        if game['status'] in ('live','final'):
            reports.append({'game_id':gid,'status':'SKIPPED_ALREADY_STARTED',
                'unchecked':[],'unavailable':[],'model_missing':[],
                'market_categories_unchecked':[],'issues':[],'checked_count':0})
            continue
        fields=checks.get('fields',{}); searches=checks.get('market_searches',{})
        missing=[];unavailable=[]; model_missing=[]; market_unchecked=[];entry_issues=[]
        def chk(path, check, value):
            nonlocal total_found,total_unavail,total_not_checked
            status=check.get('status','not_checked') if isinstance(check,dict) else 'not_checked'
            if status=='not_checked':
                total_not_checked+=1
                missing.append(path)
                return
            if status=='found':total_found+=1
            if status=='unavailable':total_unavail+=1;unavailable.append(path)
            evid=check.get('evidence_ids',[])
            if not evid:
                entry_issues.append(f'{path}: MISSING_EVIDENCE_REFERENCE')
            else:
                for ident in evid:
                    if ident not in evidence:
                        entry_issues.append(f'{path}: UNKNOWN_EVIDENCE_ID {ident}')
                    elif parse_time(evidence[ident]['checked_at'])>min(as_of,start):
                        entry_issues.append(f'{path}: FUTURE_EVIDENCE {ident}')
            if status=='found' and value is None:
                entry_issues.append(f'{path}: MARKED_FOUND_BUT_VALUE_NULL')
            if status=='unavailable' and value is not None:
                entry_issues.append(f'{path}: MARKED_UNAVAILABLE_BUT_VALUE_PRESENT')
            if status=='unavailable' and not str(check.get('note','')).strip():
                entry_issues.append(f'{path}: UNAVAILABLE_REQUIRES_REASON')
        for path in required_paths():
            value=get_path(game,path)
            check=fields.get(path)
            chk(path,check,value)
            if path in MODEL_REQUIRED and (value is None or not check or check['status']!='found'):
                model_missing.append(path)
        for path in fields:
            if path not in required_paths():entry_issues.append(f'{path}: UNKNOWN_FIELD_CHECK')
        for kind in MARKET_SEARCHES:
            check=searches.get(kind)
            count=sum(market_matches(kind,m) for m in game['markets'])
            chk(f'market_searches.{kind}',check, True if count else None)
            # A search can be completed even with zero offers -- represent as unavailable.
            if check and check['status']=='found' and count==0:
                entry_issues.append(f'market_searches.{kind}: FOUND_BUT_NO_MARKET')
            if check and check['status']=='unavailable' and count>0:
                entry_issues.append(f'market_searches.{kind}: UNAVAILABLE_BUT_MARKETS_PRESENT')
            if check is None or check.get('status')=='not_checked':
                market_unchecked.append(kind)
        for kind in searches:
            if kind not in MARKET_SEARCHES:entry_issues.append(f'market_searches.{kind}: UNKNOWN_MARKET_CATEGORY')
        # Require explicit absence/return checks, never infer unavailable as zero effect.
        status='READY_FOR_MODEL' if not model_missing and not missing and not entry_issues and not market_unchecked else 'INCOMPLETE'
        rep={'game_id':gid,'status':status,'unchecked':missing,'unavailable':unavailable,
             'model_missing':model_missing,'market_categories_unchecked':market_unchecked,
             'issues':entry_issues,'checked_count':len(required_paths())-len([x for x in missing if not x.startswith('market_searches.')])}
        reports.append(rep)
        issues.extend(f'{gid}: {msg}' for msg in entry_issues)
    return {'games':reports,'unchecked_total':total_not_checked,'found_total':total_found,
            'unavailable_total':total_unavail,'issues':issues,
            'complete':not issues and total_not_checked==0}
