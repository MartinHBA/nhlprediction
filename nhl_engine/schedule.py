"""Public NHL API schedule acquisition and non-fabricating pregame input scaffold.

Network is used ONLY when the user runs `nhl-predict schedule` locally. No
betting odds, expected goals or goalie confirmations are invented/fetched.
"""
from __future__ import annotations
from datetime import datetime, timezone
import json
from urllib.request import Request, urlopen
from .checklist import required_paths, MARKET_SEARCHES


def extract_games(payload: dict, day: str) -> list[dict]:
    entries = next((d.get("games",[]) for d in payload.get("gameWeek",[]) if d.get("date")==day),None)
    if entries is None:
        raise ValueError(f"API response does not contain requested calendar date {day}; cannot certify full slate")
    seen=set()
    games=[]
    for g in entries:
        if int(g.get("gameType",2)) != 2:
            continue  # filter preseason and playoffs when requesting regular season
        gid=str(g["id"])
        if gid in seen: raise ValueError(f"Duplicate game ID from NHL API: {gid}")
        seen.add(gid)
        st=g.get("gameState","UNKNOWN")
        status="scheduled" if st in ("FUT","PRE") else "final" if st in ("OFF","FINAL") else "live"
        games.append({"game_id":gid,"starts_at":g["startTimeUTC"],"status":status,
                      "home_name":g["homeTeam"]["abbrev"],"away_name":g["awayTeam"]["abbrev"]})
    return games


def fetch_schedule(day: str) -> dict:
    datetime.strptime(day,"%Y-%m-%d")
    url=f"https://api-web.nhle.com/v1/schedule/{day}"
    req=Request(url,headers={"User-Agent":"nhl-prediction-engine/1.9 (checklist)"})
    with urlopen(req,timeout=20) as resp:
        payload=json.load(resp)
    as_of=datetime.now(timezone.utc).isoformat().replace("+00:00","Z")
    games=extract_games(payload,day)
    return {"requested_day":day,"as_of":as_of,"source_url":url,
            "expected_game_ids":[g["game_id"] for g in games],"games":games}


def create_input_template(snapshot: dict) -> dict:
    """Deliberately invalid until actual data are supplied. No dummy odds or xG!"""
    as_of=snapshot["as_of"]
    def source():
        return {"url":snapshot["source_url"],"checked_at":as_of,"status":"unverified"}
    def side(name: str):
        def stats():
            return {"games":None,"xgf_5v5_per60":None,"xga_5v5_per60":None,
                    "pp_xgf_per60":None,"pk_xga_per60":None,"pp_drawn_per_game":None,
                    "penalties_taken_per_game":None,"shots_for_per_game":None,"shots_against_per_game":None,"high_danger_for_per60":None,"high_danger_against_per60":None,"points_pct":None,"source":source()}
        return {"name":name,"prior":stats(),"current":stats(),
                "goalie":{"name":"UNKNOWN","status":"unknown","starts_current":0,"starts_prior":0,
                          "gsax_per60_current":None,"gsax_per60_prior":None,"source":source()},
                "roster":{"quantified":False,"attack_delta_goals":None,
                          "defense_delta_goals_allowed":None,"absences":[],"returns":None,"scratches":None,"forward_lines":None,"defense_pairs":None,"power_play_units":None,"source":source()},
                "context":{"rest_days":None,"travel_timezones":None}}
    def check():return {"status":"not_checked","evidence_ids":[],"note":""}
    def checks():return {"fields":{key:check() for key in required_paths()},
                         "market_searches":{kind:check() for kind in MARKET_SEARCHES}}
    return {"as_of":as_of,"synthetic_data":False,"evidence":[],
            "expected_game_ids":snapshot["expected_game_ids"],
            "games":[{"game_id":g["game_id"],"starts_at":g["starts_at"],
                      "status":g["status"],"home":side(g["home_name"]),
                      "away":side(g["away_name"]),"markets":[],"checks":checks()}
                     for g in snapshot["games"]]}
