import copy
import math
from datetime import datetime
import numpy as np
from jsonschema import Draft202012Validator
from nhl_engine.core import (
    SCHEMA, validate_input, blend, goalie_adjustment, project_lambdas, simulate,
    settle_market, expected_value, run_dataset, select_combo
)
from nhl_engine.backtest import actual_settlement, backtest


def test_schema_is_valid():
    Draft202012Validator.check_schema(SCHEMA)


def test_synthetic_demo_validates(data):
    assert validate_input(data)==[]


def test_schedule_mismatch_refused(data):
    data["expected_game_ids"].pop()
    assert any("SCHEDULE_MISMATCH" in e for e in validate_input(data))


def test_lookahead_refused(data):
    data["games"][0]["home"]["goalie"]["source"]["checked_at"]="2026-10-22T01:00:00Z"
    assert any("cutoff" in e for e in validate_input(data))


def test_market_definition_refused(data):
    m=data["games"][0]["markets"][3]
    m["score_basis"]="regulation"
    assert any("moneyline" in e for e in validate_input(data))


def test_poisson_and_probability_consistency():
    sim=simulate(3.4,2.8,150_000,123)
    total={"kind":"total","selection":"over","line":4.5,"score_basis":"regulation"}
    p=settle_market(total,sim)
    assert len(p)==3 and math.isclose(sum(p),1.,abs_tol=1e-10)
    assert .70 < p[0] < .80


def test_60_min_vs_full_time_moneyline():
    sim={"rh":np.array([2,2,1]),"ra":np.array([2,1,2]),
         "fh":np.array([3,2,1]),"fa":np.array([2,1,2])}
    pml=settle_market({"kind":"moneyline","selection":"home","line":None,"score_basis":"official_final"},sim)
    px=settle_market({"kind":"regulation_1x2","selection":"draw","line":None,"score_basis":"regulation"},sim)
    assert math.isclose(pml[0],2/3)
    assert math.isclose(px[0],1/3)


def test_integer_total_push_ev():
    sim={"rh":np.array([2,2,3]),"ra":np.array([2,3,3]),
         "fh":np.array([2,2,3]),"fa":np.array([2,3,3])}
    m={"kind":"total","selection":"over","line":5,"score_basis":"official_final"}
    p=settle_market(m,sim)
    assert p==(1/3,1/3,1/3)
    assert abs(expected_value(p,1.9)-(-1/30))<1e-9


def test_short_sample_goalie_regression():
    g={"starts_current":2,"starts_prior":80,"gsax_per60_current":2.5,"gsax_per60_prior":0.05}
    assert abs(goalie_adjustment(g))<0.15
    assert blend(2.4,7.2,2)<3.0


def test_team_over_two_point_five_suspended(data):
    x=run_dataset(data,simulations=2_000)
    assert x["status"]=="SYNTHETIC_DEMO_ONLY"
    assert x["audit"]["blocked_team_over_2_5"]==3
    for game in x["games"]:
        assert any("SUSPENDED_TEAM_OVER_2_5" in m["issues"] for m in game["markets"])
    assert x["ticket"] is None


def test_research_eligible_requires_verified_sources(data):
    for g in data["games"]:
        for side in ("home","away"):
            for obj in [g[side]["prior"],g[side]["current"],g[side]["goalie"],g[side]["roster"]]:
                obj["source"]["status"]="verified"
        for m in g["markets"]: m["source"]["status"]="verified"
    x=run_dataset(data,simulations=2_000,research=True)
    assert x["status"]=="SYNTHETIC_DEMO_ONLY" # demo can't become live ticket
    assert x["ticket"] is None


def test_combo_one_game_no_stacking():
    m1={"id":"m1","p_push":0,"p_win":.87,"odds":1.25,"scenario_ev_min":.06,"research_eligible":True}
    m2={"id":"m2","p_push":0,"p_win":.90,"odds":1.22,"scenario_ev_min":.07,"research_eligible":True}
    games=[{"status":"scheduled","game_id":"1","matchup":"A @ B","markets":[m1,m2]},
           {"status":"scheduled","game_id":"2","matchup":"C @ D","markets":[m1,m2]}]
    t=select_combo(games)
    assert len({leg["game_id"] for leg in t["legs"]})==len(t["legs"])
    assert t["odds"]<=4.


def test_backtest_settlement(data):
    m=data["games"][0]["markets"][0]
    outcome={"reg_home":2,"reg_away":2,"final_home":3,"final_away":2}
    assert actual_settlement(m,outcome)==1 # over 4.5 includes credited OT goal
    m["score_basis"]="regulation"
    assert actual_settlement(m,outcome)==-1


def test_backtest_with_demo(data):
    outcomes={g["game_id"]:{"reg_home":3,"reg_away":2,"final_home":3,"final_away":2}
              for g in data["games"]}
    result=backtest([{"pregame":data,"outcomes":outcomes}],simulations=1200)
    assert result["evaluated_markets"]>0
    assert 0<=result["brier_score"]<=1


def test_extract_schedule_and_template():
    from nhl_engine.schedule import extract_games,create_input_template
    response={"gameWeek":[{"date":"2026-10-21","games":[
        {"id":123,"gameType":2,"startTimeUTC":"2026-10-22T00:00:00Z","gameState":"FUT",
         "homeTeam":{"abbrev":"BOS"},"awayTeam":{"abbrev":"COL"}},
        {"id":456,"gameType":1,"startTimeUTC":"2026-10-22T01:00:00Z","gameState":"OFF",
         "homeTeam":{"abbrev":"NJD"},"awayTeam":{"abbrev":"OTT"}}]}]}
    games=extract_games(response,"2026-10-21")
    assert len(games)==1 and games[0]["game_id"]=="123"
    snap={"source_url":"https://api-web.nhle.com/v1/schedule/2026-10-21",
          "as_of":"2026-10-20T08:00:00Z","expected_game_ids":["123"],"games":games}
    template=create_input_template(snap)
    assert template["games"][0]["home"]["name"]=="BOS"
    assert validate_input(template)==[]  # structurally sound, but data collection is incomplete
    from nhl_engine.checklist import audit_checks
    assert not audit_checks(template)["complete"]


def test_unconfirmed_goalie_disqualifies(data):
    data["games"][0]["away"]["goalie"]["status"]="probable"
    result=run_dataset(data,simulations=2000)
    assert all("GOALIE_NOT_CONFIRMED" in m["issues"] for m in result["games"][0]["markets"])


def test_odds_staleness_disqualifies(data):
    data["games"][0]["markets"][0]["source"]["checked_at"]="2026-10-20T01:00:00Z"
    result=run_dataset(data,simulations=2000)
    assert "ODDS_STALE_OVER_2_HOURS" in result["games"][0]["markets"][0]["issues"]


def test_early_prediction_no_final_check(data):
    data["as_of"]="2026-10-20T01:00:00Z"
    # Move all source timestamps back to avoid lookahead; starts are still far ahead.
    for game in data["games"]:
        for side in ("home","away"):
            for obj in (game[side]["prior"],game[side]["current"],game[side]["goalie"],game[side]["roster"]):
                obj["source"]["checked_at"]=data["as_of"]
        for m in game["markets"]: m["source"]["checked_at"]=data["as_of"]
    r=run_dataset(data,simulations=2000)
    assert "FINAL_LINEUP_WINDOW_NOT_REACHED" in r["games"][0]["markets"][0]["issues"]
