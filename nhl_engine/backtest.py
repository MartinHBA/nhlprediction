"""Chronological out-of-sample backtest diagnostics (input snapshots REQUIRED).

Each JSONL line: {"pregame": <dataset>, "outcomes": {<game_id>: {
  "reg_home":N, "reg_away":N, "final_home":N, "final_away":N }} }
Only pregame sources with cutoff timestamps pass core.validate_input.
"""
from __future__ import annotations
import math
import numpy as np
from .core import run_dataset


def actual_settlement(m: dict, scores: dict) -> int:
    """+1 win, 0 push, -1 loss. Assumes supplied official_final score follows bookmaker rules."""
    reg = m["score_basis"] == "regulation"
    h, a = (scores["reg_home"], scores["reg_away"]) if reg else (scores["final_home"], scores["final_away"])
    kind, side, line = m["kind"], m["selection"], m["line"]
    if kind in ("total", "team_total"):
        val = h+a if kind == "total" else h if m["team"]=="home" else a
        diff = val-line if side=="over" else line-val
    elif kind=="handicap":
        diff = (h-a if side=="home" else a-h) + line
    elif kind=="moneyline":
        diff = h-a if side=="home" else a-h
    elif kind=="regulation_1x2":
        return 1 if (h>a if side=="home" else a>h if side=="away" else h==a) else -1
    elif kind=="double_chance":
        return 1 if (h>=a if side=="1X" else a>=h if side=="X2" else h!=a) else -1
    else: raise ValueError(kind)
    return 1 if diff>0 else 0 if diff==0 else -1


def backtest(records: list[dict], simulations: int = 25_000) -> dict:
    diagnostics=[]
    for idx, rec in enumerate(records):
        data = rec["pregame"]
        predictions = run_dataset(data, seed=2048+idx*100, simulations=simulations, research=True)
        if predictions["status"]=="INVALID_INPUT":
            raise ValueError(f"Record {idx} invalid: {predictions['errors']}")
        source_games={g["game_id"]:g for g in data["games"]}
        for game in predictions["games"]:
            scores=rec["outcomes"].get(game["game_id"])
            if scores is None:
                raise ValueError(f"Missing outcome for {game['game_id']}")
            orig_m={m["id"]:m for m in source_games[game["game_id"]]["markets"]}
            for m in game["markets"]:
                if "SUSPENDED_TEAM_OVER_2_5" in m["issues"]:
                    continue
                outcome=actual_settlement(orig_m[m["id"]], scores)
                diagnostics.append({"game_id":game["game_id"],"market_id":m["id"],
                                    "kind":m["kind"], "outcome":outcome,
                                    "p_win":m["p_win"], "p_push":m["p_push"], "odds":m["odds"],
                                    "research_eligible":m["research_eligible"]})
    settled=[x for x in diagnostics if x["outcome"]!=0]
    if not settled: return {"markets":len(diagnostics),"settled_markets":0,"warning":"No settled markets"}
    probs=np.clip(np.array([x["p_win"]/max(1e-9, 1-x["p_push"]) for x in settled]),1e-6,1-1e-6)
    y=np.array([1 if x["outcome"]==1 else 0 for x in settled],dtype=float)
    brier=float(np.mean((probs-y)**2))
    logloss=float(-np.mean(y*np.log(probs)+(1-y)*np.log(1-probs)))
    calibration=[]
    for lo in (0.,.2,.4,.6,.8):
        chosen=(probs>=lo)&(probs<(lo+.2 if lo<.8 else 1.000001))
        if np.any(chosen):
            calibration.append({"range":f"{lo:.1f}–{lo+.2:.1f}", "n":int(sum(chosen)),
                                "mean_pred":float(np.mean(probs[chosen])),"actual_win_rate":float(np.mean(y[chosen]))})
    picked=[x for x in diagnostics if x["research_eligible"]]
    pnl=sum((x["odds"]-1 if x["outcome"]==1 else -1 if x["outcome"]==-1 else 0) for x in picked)
    return {"evaluated_markets":len(diagnostics),"settled_markets":len(settled),
            "brier_score":round(brier,5),"log_loss":round(logloss,5),
            "calibration":calibration, "research_eligible_count":len(picked),
            "flat_stake_roi_on_research_candidates":round(pnl/len(picked),5) if picked else None,
            "limitations":"Overlapping markets are correlated; market-level metrics are NOT independent observations. No automatic validation certificate; use dated, truly out-of-sample seasons and closing-line comparison."}
