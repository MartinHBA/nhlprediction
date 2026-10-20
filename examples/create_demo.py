"""Generate synthetic demonstration input. NOT real teams, lines or prices."""
import json
from pathlib import Path
from nhl_engine.checklist import required_paths, MARKET_SEARCHES, market_matches, get_path

AS_OF="2026-10-20T12:00:00Z"
S="https://example.org/synthetic-not-real-data"

def src(): return {"url":S,"checked_at":AS_OF,"status":"synthetic"}

def team(name, gf, ga,pp=7.,pk=7., goalieg=.05):
    prior={"games":82,"xgf_5v5_per60":gf,"xga_5v5_per60":ga,
           "pp_xgf_per60":pp,"pk_xga_per60":pk,
           "pp_drawn_per_game":3.1,"penalties_taken_per_game":3.2,"shots_for_per_game":31.,"shots_against_per_game":29.,"high_danger_for_per60":11.,"high_danger_against_per60":9.,"points_pct":0.59,"source":src()}
    current={**prior,"games":5,"xgf_5v5_per60":gf+0.2,"xga_5v5_per60":ga+0.15,
             "pp_xgf_per60":pp+1.5,"source":src()}
    return {"name":name,"prior":prior,"current":current,
            "goalie":{"name":f"{name} DEMO GOALIE","status":"confirmed","starts_current":3,
                      "starts_prior":30,"gsax_per60_current":0.4,"gsax_per60_prior":goalieg,
                      "source":src()},
            "roster":{"quantified":True,"attack_delta_goals":0.0,"defense_delta_goals_allowed":0.0,
                      "absences":[],"returns":[],"scratches":[],"forward_lines":["DEMO ONLY"],"defense_pairs":["DEMO ONLY"],"power_play_units":["DEMO ONLY"],"source":src()},
            "context":{"rest_days":2,"travel_timezones":0}}

def m(id,kind,selection,odds,line=None,team=None,score_basis="official_final"):
    x={"id":id,"kind":kind,"selection":selection,"odds":odds,"line":line,
       "score_basis":score_basis,"settlement_verified":True,"rules_url":S,"source":src()}
    if team is not None: x["team"]=team
    return x

def demo():
    games=[]
    for i in range(3):
        games.append({"game_id":f"DEMO-{i+1}","starts_at":f"2026-10-21T0{1+i}:00:00Z",
          "status":"scheduled","home":team(f"Demo Home {i+1}",3.2,2.5,7.8,6.2),
          "away":team(f"Demo Away {i+1}",2.6,3.1,6.9,7.7),
          "markets":[
             m(f"total-over45-{i}","total","over",1.25,4.5),
             m(f"total-under75-{i}","total","under",1.28,7.5),
             m(f"home-hcp-{i}","handicap","home",1.27,-0.5,team="home"),
             m(f"home-moneyline-{i}","moneyline","home",1.25),
             m(f"home-team-over25-{i}","team_total","over",1.28,2.5,team="home"),
             m(f"home-60min-{i}","regulation_1x2","home",1.26,score_basis="regulation"),
          ]})
    for game in games:
        checks={"fields":{},"market_searches":{}}
        for path in required_paths():
            value=get_path(game,path)
            checks["fields"][path]={"status":"found" if value is not None else "unavailable",
                "evidence_ids":["demo"],"note":"" if value is not None else "Not available in demo"}
        for kind in MARKET_SEARCHES:
            found=any(market_matches(kind,m) for m in game["markets"])
            checks["market_searches"][kind]={"status":"found" if found else "unavailable",
                "evidence_ids":["demo"],"note":"" if found else "No demo quotes for this market"}
        game["checks"]=checks
    return {"as_of":AS_OF,"expected_game_ids":[g["game_id"] for g in games],
            "synthetic_data":True,"evidence":[{"id":"demo","url":S,"checked_at":AS_OF}],
            "games":games}

if __name__=="__main__":
    out=Path(__file__).parent/"demo_games.json"
    out.write_text(json.dumps(demo(),ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(out)
