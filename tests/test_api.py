import json
from fastapi.testclient import TestClient
from nhl_engine.api import app, validation_report
from nhl_engine.checklist import audit_checks
from nhl_engine.core import run_dataset
from nhl_engine.schedule import create_input_template

client = TestClient(app)


def test_health_and_swagger():
    assert client.get('/health').json()['status'] == 'ok'
    assert client.get('/docs').status_code == 200
    assert client.get('/api/v1/schema').json()['title'].startswith('NHL')


def test_schedule_template_mock(monkeypatch):
    snapshot = {
        'source_url':'https://api-web.nhle.com/v1/schedule/2026-10-21',
        'as_of':'2026-10-20T08:00:00Z',
        'expected_game_ids':['123'],
        'games':[{'game_id':'123','starts_at':'2026-10-22T00:00:00Z','status':'scheduled',
                  'home_name':'BOS','away_name':'COL'}],
    }
    monkeypatch.setattr('nhl_engine.api.fetch_schedule', lambda day: snapshot)
    r=client.get('/api/v1/schedule/2026-10-21')
    assert r.status_code==200 and r.json()['expected_game_ids']==['123']
    r=client.get('/api/v1/schedule/2026-10-21?template=true')
    assert r.status_code==200 and r.json()['games'][0]['home']['name']=='BOS'
    assert len(r.json()['games'][0]['checks']['fields'])>=70


def test_invalid_schedule_date():
    assert client.get('/api/v1/schedule/nope').status_code==422


def test_schedule_source_down(monkeypatch):
    def fail(day): raise OSError('test source outage')
    monkeypatch.setattr('nhl_engine.api.fetch_schedule', fail)
    assert client.get('/api/v1/schedule/2026-10-21').status_code==502


def test_validate_and_checklist(data):
    r=client.post('/api/v1/validate', json=data)
    assert r.status_code==200
    assert r.json()==validation_report(data)
    assert r.json()['valid'] is True
    r=client.post('/api/v1/checklist', json=data)
    assert r.status_code==200 and r.json()==audit_checks(data)


def test_schema_invalid_report(data):
    data['expected_game_ids']=[]
    r=client.post('/api/v1/validate', json=data)
    assert r.status_code==200 and not r.json()['schema_valid']
    r=client.post('/api/v1/predict', json=data)
    assert r.status_code==200 and r.json()['status']=='INVALID_INPUT'


def test_predict_same_as_python_core(data):
    response=client.post('/api/v1/predict?sims=2000&seed=99', json=data)
    assert response.status_code==200
    assert response.json()==run_dataset(data, simulations=2000, seed=99)
    assert response.json()['status']=='SYNTHETIC_DEMO_ONLY'


def test_upload(data):
    raw=json.dumps(data).encode('utf-8')
    r=client.post('/api/v1/predict/upload?sims=2000',files={'file':('games.json',raw,'application/json')})
    assert r.status_code==200 and r.json()['status']=='SYNTHETIC_DEMO_ONLY'


def test_upload_invalid_json():
    r=client.post('/api/v1/predict/upload',files={'file':('bad.json',b'not-json','application/json')})
    assert r.status_code==400


def test_bounded_simulations(data):
    assert client.post('/api/v1/predict?sims=999',json=data).status_code==422
    assert client.post('/api/v1/predict?sims=250001',json=data).status_code==422


def test_template_incomplete_checklist():
    snap={'source_url':'https://api-web.nhle.com/v1/schedule/2026-10-21',
          'as_of':'2026-10-20T08:00:00Z','expected_game_ids':['123'],
          'games':[{'game_id':'123','starts_at':'2026-10-22T00:00:00Z','status':'scheduled','home_name':'BOS','away_name':'COL'}]}
    t=create_input_template(snap)
    assert client.post('/api/v1/validate',json=t).json()['valid'] is False
    assert client.post('/api/v1/predict?sims=1000',json=t).json()['status']=='CHECKLIST_INCOMPLETE'
