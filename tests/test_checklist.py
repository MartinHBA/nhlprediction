import copy
from nhl_engine.core import validate_input,run_dataset
from nhl_engine.checklist import audit_checks

def test_full_demo_checks_pass(data):
    a=audit_checks(data)
    assert a['complete']
    assert len(a['games'])==3
    assert all(g['status']=='READY_FOR_MODEL' for g in a['games'])

def test_missing_one_field_check_blocks_all_ticket(data):
    first=data['games'][0]
    first['checks']['fields'].pop('home.current.xgf_5v5_per60')
    assert validate_input(data)==[]
    a=audit_checks(data)
    assert not a['complete']
    assert 'home.current.xgf_5v5_per60' in a['games'][0]['unchecked']
    r=run_dataset(data,simulations=1000)
    assert r['status']=='CHECKLIST_INCOMPLETE'
    assert r['ticket'] is None

def test_unavailable_field_not_missing_check(data):
    g=data['games'][0]
    g['home']['current']['points_pct']=None
    g['checks']['fields']['home.current.points_pct']={'status':'unavailable','evidence_ids':['demo'],'note':'Source has no values'}
    assert validate_input(data)==[]
    a=audit_checks(data)
    assert a['complete']
    assert 'home.current.points_pct' in a['games'][0]['unavailable']
    assert not a['games'][0]['model_missing']

def test_model_critical_missing_marks_insufficient(data):
    g=data['games'][0]
    g['home']['current']['xga_5v5_per60']=None
    g['checks']['fields']['home.current.xga_5v5_per60']={'status':'unavailable','evidence_ids':['demo'],'note':'not published'}
    assert validate_input(data)==[]
    a=audit_checks(data)
    assert a['complete']
    assert 'home.current.xga_5v5_per60' in a['games'][0]['model_missing']
    r=run_dataset(data,simulations=1000)
    assert r['status']=='INSUFFICIENT_DATA'
    assert r['ticket'] is None

def test_found_null_inconsistent(data):
    g=data['games'][0]
    g['home']['current']['points_pct']=None
    a=audit_checks(data)
    assert any('MARKED_FOUND_BUT_VALUE_NULL' in x for x in a['issues'])
    assert not a['complete']

def test_market_category_not_checked_prevents_tip(data):
    data['games'][0]['checks']['market_searches']['handicap_home']['status']='not_checked'
    a=audit_checks(data)
    assert not a['complete']
    assert 'handicap_home' in a['games'][0]['market_categories_unchecked']

def test_no_fabrication_when_unknown_absences(data):
    g=data['games'][0]
    g['home']['roster']['forward_lines']=None
    g['checks']['fields']['home.roster.forward_lines']={'status':'unavailable','evidence_ids':['demo'],'note':'not announced'}
    assert audit_checks(data)['complete']
    assert validate_input(data)==[]

def test_suspended_team_total_even_when_checklist_passes(data):
    r=run_dataset(data,simulations=1200)
    assert r['audit']['blocked_team_over_2_5']==3
    assert all('SUSPENDED_TEAM_OVER_2_5' in next(m for m in g['markets'] if m['kind']=='team_total')['issues'] for g in r['games'])

def test_missing_evidence_id_is_detected(data):
    data['games'][0]['checks']['fields']['away.prior.games']['evidence_ids']=['fake']
    assert any('UNKNOWN_EVIDENCE_ID' in e for e in audit_checks(data)['issues'])

def test_spoofed_market_settlement_is_blocked(data):
    data['games'][0]['markets'][0]['settlement_verified']=False
    report=run_dataset(data,simulations=1200)
    assert 'MARKET_SETTLEMENT_NOT_VERIFIED' in report['games'][0]['markets'][0]['issues']


def test_started_game_does_not_block_upcoming(data):
    data['games'][0]['status']='live'
    data['games'][0]['markets']=[]
    data['games'][0]['checks']['fields'].clear()
    # Source checked_at may be after started; it is never used to predict a live match.
    data['games'][0]['starts_at']='2026-10-20T11:00:00Z'
    assert validate_input(data)==[]
    a=audit_checks(data)
    assert a['complete']
    assert a['games'][0]['status']=='SKIPPED_ALREADY_STARTED'
    r=run_dataset(data,simulations=1000)
    assert r['games'][0]['skipped']=='ALREADY_STARTED_OR_FINAL'

def test_no_historical_validation_flag_needed_to_run(data):
    assert 'model_validation' not in data
    r=run_dataset(data,simulations=1000)
    assert r['status']=='SYNTHETIC_DEMO_ONLY'
    assert all('lambda_total' in g for g in r['games'])


def test_settlement_true_requires_documented_rules(data):
    data['games'][0]['markets'][0]['rules_url']=None
    assert any('requires rules_url' in x for x in validate_input(data))
