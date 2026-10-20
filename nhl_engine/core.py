"""Repeatable projection and checklist enforcement, not a guaranteed predictive edge.

Simulation: shrunk prior/current 5v5 and special-teams chance rates, explicit
regulation 6v5 sensitivity and simplified OT/SO resolution.  These heuristic
adjustments MUST be fitted to historical pregame data before live use.
"""
from __future__ import annotations

from datetime import datetime, timezone
from itertools import combinations
from importlib.resources import files
import json
import math
from typing import Any

import numpy as np
from jsonschema import Draft202012Validator, FormatChecker
from .checklist import audit_checks, MODEL_REQUIRED

SCHEMA = json.loads(files("nhl_engine").joinpath("schema.json").read_text(encoding="utf-8"))
PRIOR_GAMES = 25.0
GOALIE_PRIOR_STARTS = 25.0
DEFAULT_SIMULATIONS = 60_000
# Uncalibrated model constants; optimize ONLY with a chronological backtest.
HOME_ICE_XG_DELTA = 0.10
REST_XG_PER_DAY = 0.045
TRAVEL_XG_PER_TIMEZONE = 0.025
GOALIE_IMPACT_SHRINK = 0.60
GOALIE_IMPACT_CAP = 0.50
OTHER_MINUTES = 2.0
ENDGAME_MINUTES = 2.0


def parse_time(value: str) -> datetime:
    d = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if d.tzinfo is None:
        raise ValueError(f"Datetime must include timezone: {value}")
    return d.astimezone(timezone.utc)


def validate_input(dataset: dict[str, Any]) -> list[str]:
    """Structural and logical gate. No silent substitutions for missing critical data."""
    errors: list[str] = []
    validator = Draft202012Validator(SCHEMA, format_checker=FormatChecker())
    for e in sorted(validator.iter_errors(dataset), key=lambda e: str(e.path)):
        errors.append(f"SCHEMA {'.'.join(str(x) for x in e.path)}: {e.message}")
    if errors:
        return errors
    as_of = parse_time(dataset["as_of"])
    ids = [g["game_id"] for g in dataset["games"]]
    if set(ids) != set(dataset["expected_game_ids"]) or len(ids) != len(set(ids)) or len(ids) != len(dataset["expected_game_ids"]):
        errors.append("SCHEDULE_MISMATCH: game IDs do not match the complete official schedule snapshot")
    for g in dataset["games"]:
        name = g["game_id"]
        start = parse_time(g["starts_at"])
        if g["home"]["name"] == g["away"]["name"]:
            errors.append(f"{name}: home team equals away team")
        if g["status"] == "scheduled" and start <= as_of:
            errors.append(f"{name}: scheduled game starts before as_of")
        for side in ("home", "away"):
            t = g[side]
            if t["roster"]["quantified"] and (t["roster"]["attack_delta_goals"] is None or t["roster"]["defense_delta_goals_allowed"] is None):
                errors.append(f"{name}/{side}: quantified lineup cannot contain null goal deltas")
            for group in (t["prior"], t["current"], t["goalie"], t["roster"]):
                source = group["source"]
                checked = parse_time(source["checked_at"])
                if checked > as_of or (g["status"] == "scheduled" and checked > start):
                    errors.append(f"{name}/{side}: source checked after prediction cutoff: {source['url']}")
            goalie = t["goalie"]
            if goalie["starts_current"] and goalie["gsax_per60_current"] is None:
                errors.append(f"{name}/{side}: goalie current starts >0 but GSAx is null")
            if goalie["starts_prior"] and goalie["gsax_per60_prior"] is None:
                errors.append(f"{name}/{side}: goalie prior starts >0 but GSAx is null")
        if g["status"] in {"live", "final"} and g["markets"]:
            errors.append(f"{name}: live/final game must have no pregame market offers")
        market_ids = [m["id"] for m in g["markets"]]
        if len(market_ids) != len(set(market_ids)):
            errors.append(f"{name}: duplicate market id")
        for m in g["markets"]:
            kind, selection, line = m["kind"], m["selection"], m["line"]
            if parse_time(m["source"]["checked_at"]) > min(as_of, start):
                errors.append(f"{name}/{m['id']}: market odds timestamp violates pregame cutoff")
            if kind in {"total", "team_total"} and (selection not in {"over", "under"} or line is None or line < 0):
                errors.append(f"{name}/{m['id']}: invalid totals definition")
            if kind == "team_total" and "team" not in m:
                errors.append(f"{name}/{m['id']}: team_total requires team")
            if kind == "handicap" and (selection not in {"home", "away"} or line is None):
                errors.append(f"{name}/{m['id']}: invalid handicap definition")
            if kind == "handicap" and m.get("team") != m["selection"]:
                errors.append(f"{name}/{m['id']}: handicap selection and team differ")
            if m["settlement_verified"] and not m["rules_url"]:
                errors.append(f"{name}/{m['id']}: verified settlement requires rules_url")
            if kind not in {"team_total", "handicap"} and "team" in m:
                errors.append(f"{name}/{m['id']}: unexpected team on this market kind")
            if kind in {"total", "team_total", "handicap"} and m["score_basis"] not in {"regulation", "official_final"}:
                errors.append(f"{name}/{m['id']}: invalid score basis")
            if kind in {"moneyline", "regulation_1x2", "double_chance"} and "team" in m:
                errors.append(f"{name}/{m['id']}: unexpected team")
            if kind == "moneyline" and (selection not in {"home", "away"} or line is not None or m["score_basis"] != "official_final"):
                errors.append(f"{name}/{m['id']}: moneyline must use official_final, home/away, null line")
            if kind == "regulation_1x2" and (selection not in {"home", "away", "draw"} or line is not None or m["score_basis"] != "regulation"):
                errors.append(f"{name}/{m['id']}: invalid 60-minute 1X2")
            if kind == "double_chance" and (selection not in {"1X", "X2", "12"} or line is not None or m["score_basis"] != "regulation"):
                errors.append(f"{name}/{m['id']}: invalid 60-minute double chance")
            if kind in {"total", "team_total", "handicap"} and line is not None and not math.isclose(line * 2, round(line * 2), abs_tol=1e-8):
                errors.append(f"{name}/{m['id']}: support only whole or half-goal lines")
    return errors


def blend(prior: float, current: float, games: int, prior_games: float = PRIOR_GAMES) -> float:
    w = games / (games + prior_games)
    return w * current + (1-w) * prior


def team_rates(team: dict) -> dict[str, float]:
    p, c = team["prior"], team["current"]
    return {k: blend(p[k], c[k], c["games"]) for k in (
        "xgf_5v5_per60", "xga_5v5_per60", "pp_xgf_per60", "pk_xga_per60",
        "pp_drawn_per_game", "penalties_taken_per_game")}


def goalie_adjustment(goalie: dict) -> float:
    """GSAx per60 positive = prevents goals; regress recent sample very strongly."""
    prior = goalie["gsax_per60_prior"] if goalie["starts_prior"] and goalie["gsax_per60_prior"] is not None else 0.0
    current = goalie["gsax_per60_current"] if goalie["starts_current"] and goalie["gsax_per60_current"] is not None else prior
    # Prior itself regresses toward league average if few historical starts.
    prior = prior * (goalie["starts_prior"] or 0) / ((goalie["starts_prior"] or 0) + GOALIE_PRIOR_STARTS)
    w = (goalie["starts_current"] or 0) / ((goalie["starts_current"] or 0) + GOALIE_PRIOR_STARTS)
    adjusted = (w * current + (1-w) * prior) * GOALIE_IMPACT_SHRINK
    return max(-GOALIE_IMPACT_CAP, min(GOALIE_IMPACT_CAP, adjusted))


def project_lambdas(game: dict) -> tuple[float, float, dict]:
    h, a = game["home"], game["away"]
    hr, ar = team_rates(h), team_rates(a)
    # Both special-team sides are modeled as xG/60 of time in that state.
    h_pp_minutes = min(10., max(0., (hr["pp_drawn_per_game"] + ar["penalties_taken_per_game"])))  # 2 minutes × mean opportunities
    a_pp_minutes = min(10., max(0., (ar["pp_drawn_per_game"] + hr["penalties_taken_per_game"])))  # 2 minutes × mean opportunities
    even_minutes = max(30.0, 60.0 - OTHER_MINUTES - h_pp_minutes - a_pp_minutes)
    h_even = 0.5 * (hr["xgf_5v5_per60"] + ar["xga_5v5_per60"]) * even_minutes / 60.0
    a_even = 0.5 * (ar["xgf_5v5_per60"] + hr["xga_5v5_per60"]) * even_minutes / 60.0
    h_pp = 0.5 * (hr["pp_xgf_per60"] + ar["pk_xga_per60"]) * h_pp_minutes / 60
    a_pp = 0.5 * (ar["pp_xgf_per60"] + hr["pk_xga_per60"]) * a_pp_minutes / 60
    rest_delta = max(-3, min(3, h["context"]["rest_days"] - a["context"]["rest_days"])) * REST_XG_PER_DAY
    travel_delta = (a["context"]["travel_timezones"] - h["context"]["travel_timezones"]) * TRAVEL_XG_PER_TIMEZONE
    # Lineup impact is 0 only when explicitly quantified as no impact (not inferred).
    h_l = h["roster"]["attack_delta_goals"] or 0.
    a_l = a["roster"]["attack_delta_goals"] or 0.
    h_d = h["roster"]["defense_delta_goals_allowed"] or 0.
    a_d = a["roster"]["defense_delta_goals_allowed"] or 0.
    gh = goalie_adjustment(h["goalie"])
    ga = goalie_adjustment(a["goalie"])
    h_lambda = h_even + h_pp + h_l + a_d - ga + HOME_ICE_XG_DELTA + rest_delta/2 + travel_delta/2
    a_lambda = a_even + a_pp + a_l + h_d - gh - HOME_ICE_XG_DELTA - rest_delta/2 - travel_delta/2
    # Bound plausible mean; clamping is flagged in report and prevents pathological simulations.
    warnings = []
    for side,t in (("HOME",h),("AWAY",a)):
        if not t["roster"]["quantified"]:
            warnings.append(f"{side}_LINEUP_DELTAS_UNKNOWN_NEUTRAL_BASELINE_WITH_WIDER_SENSITIVITY")
        if t["goalie"]["gsax_per60_prior"] is None and t["goalie"]["gsax_per60_current"] is None:
            warnings.append(f"{side}_GOALIE_GSAX_UNKNOWN_NEUTRAL_BASELINE_ASSUMPTION")
    if min(h_lambda, a_lambda) < 0.4 or max(h_lambda, a_lambda) > 7.5:
        warnings.append("PROJECTED_LAMBDA_OUTSIDE_PLAUSIBLE_RANGE; manual review required")
    h_lambda, a_lambda = max(0.4, min(7.5, h_lambda)), max(0.4, min(7.5, a_lambda))
    return h_lambda, a_lambda, {"even_minutes": even_minutes, "home_pp_minutes": h_pp_minutes,
                                "away_pp_minutes": a_pp_minutes, "home_goalie_adjustment": gh,
                                "away_goalie_adjustment": ga, "warnings": warnings}


def simulate(lh: float, la: float, simulations: int, seed: int) -> dict[str, np.ndarray]:
    """Regulation score + transparent heuristic for 6v5, then simplified OT/SO.

    NOTE: final totals include one shootout award (official scoreboard convention).
    This is NOT necessarily the settlement rule of every sportsbook.
    """
    rng = np.random.default_rng(seed)
    n = simulations
    h = rng.poisson(lh * (60-ENDGAME_MINUTES)/60, n)
    a = rng.poisson(la * (60-ENDGAME_MINUTES)/60, n)
    h_trail = (a > h) & (a-h <= 2)
    a_trail = (h > a) & (h-a <= 2)
    # Pull probability: 70% when behind by 1, 40% when behind by 2.
    h_pull = h_trail & (rng.random(n) < np.where(a-h==1, 0.70, 0.40))
    a_pull = a_trail & (rng.random(n) < np.where(h-a==1, 0.70, 0.40))
    # Goal-scoring chance rises for team attacking 6v5; opposite side gets EN chances.
    h_rate_multiplier = np.where(h_pull, 1.6, np.where(a_pull, 3.5, 1.0))
    a_rate_multiplier = np.where(a_pull, 1.6, np.where(h_pull, 3.5, 1.0))
    rh = h + rng.poisson(lh * ENDGAME_MINUTES/60 * h_rate_multiplier)
    ra = a + rng.poisson(la * ENDGAME_MINUTES/60 * a_rate_multiplier)
    fh, fa = rh.copy(), ra.copy()
    ties = rh == ra
    # A tie produces one credited winning goal (in OT or shootout).
    p_home_ot = np.clip(0.5 + 0.28 * (lh-la) / max(lh+la, 0.01), 0.25, 0.75)
    home_ot_wins = ties & (rng.random(n) < p_home_ot)
    away_ot_wins = ties & ~home_ot_wins
    fh[home_ot_wins] += 1
    fa[away_ot_wins] += 1
    return {"rh": rh, "ra": ra, "fh": fh, "fa": fa, "h_pull": h_pull, "a_pull": a_pull}


def settle_market(m: dict, sim: dict) -> tuple[float, float, float]:
    """Returns (P_win, P_push, P_loss); exact equality handles integer totals/handicaps."""
    reg = m["score_basis"] == "regulation"
    home = sim["rh"] if reg else sim["fh"]
    away = sim["ra"] if reg else sim["fa"]
    kind, sel, line = m["kind"], m["selection"], m["line"]
    push = np.zeros(len(home), dtype=bool)
    if kind in {"total", "team_total"}:
        value = home + away if kind == "total" else (home if m["team"] == "home" else away)
        outcome = value - line if sel == "over" else line-value
        win = outcome > 0
        push = outcome == 0
    elif kind == "handicap":
        value = (home-away) if sel == "home" else (away-home)
        outcome = value + line
        win = outcome > 0
        push = outcome == 0
    elif kind == "moneyline":
        win = home > away if sel == "home" else away > home
    elif kind == "regulation_1x2":
        win = (home > away if sel == "home" else away > home if sel == "away" else home == away)
    elif kind == "double_chance":
        win = (home >= away if sel == "1X" else away >= home if sel == "X2" else home != away)
    else:
        raise ValueError(f"Unsupported market: {kind}")
    return (float(np.mean(win)), float(np.mean(push)), float(np.mean(~(win | push))))


def expected_value(probs: tuple[float, float, float], odds: float) -> float:
    p_win, _, p_lose = probs
    return p_win * (odds - 1) - p_lose


def evaluate_game(game: dict, seed: int, simulations: int = DEFAULT_SIMULATIONS) -> dict:
    h, a, detail = project_lambdas(game)
    sim = simulate(h, a, simulations, seed)
    factor = 0.18 if any(not game[side]["roster"]["quantified"] for side in ("home", "away")) else 0.12
    scenarios = [(h*(1-factor), a*(1-factor)), (h*(1+factor), a*(1+factor)),
                 (h*(1-factor), a*(1+factor)), (h*(1+factor), a*(1-factor))]
    # Scenario range is NOT a calibrated CI; covers selected ±12% shifts.
    scenario_sims = [simulate(x, y, max(10_000, simulations//3), seed+i+1)
                     for i, (x,y) in enumerate(scenarios)]
    goalies_confirmed = all(game[side]["goalie"]["status"] == "confirmed" for side in ("home", "away"))
    roster_quantified = all(game[side]["roster"]["quantified"] for side in ("home", "away"))
    sources_ok = all(group["source"]["status"] == "verified"
                     for side in ("home", "away") for group in
                     (game[side]["prior"], game[side]["current"], game[side]["goalie"], game[side]["roster"]))
    markets = []
    # Freshness gate protects against "confirmed yesterday" and stale market lines.
    # Snapshot time is passed by the caller; use dataset cutoff, not wall clock.
    checked_at = game.get("_analysis_as_of")
    for m in game["markets"]:
        blocked = m["kind"] == "team_total" and m["selection"] == "over" and m["line"] == 2.5
        p = settle_market(m, sim)
        other = [settle_market(m, s) for s in scenario_sims]
        evs = [expected_value(v, m["odds"]) for v in [p]+other]
        issues = []
        if blocked: issues.append("SUSPENDED_TEAM_OVER_2_5")
        if not goalies_confirmed: issues.append("GOALIE_NOT_CONFIRMED")
        warnings = []
        if not roster_quantified: warnings.append("LINEUP_IMPACT_UNQUANTIFIED; sensitivity widened")
        if not sources_ok: issues.append("UNVERIFIED_TEAM_INPUT")
        if m["source"]["status"] != "verified": issues.append("UNVERIFIED_ODDS")
        if not m["settlement_verified"]: issues.append("MARKET_SETTLEMENT_NOT_VERIFIED")
        if checked_at is not None:
            cutoff = parse_time(checked_at)
            odds_age_h = (cutoff - parse_time(m["source"]["checked_at"])).total_seconds()/3600
            if odds_age_h > 2.: issues.append("ODDS_STALE_OVER_2_HOURS")
            for team_side in ("home", "away"):
                for group in ("goalie", "roster"):
                    age_h = (cutoff-parse_time(game[team_side][group]["source"]["checked_at"])).total_seconds()/3600
                    if age_h > 6.:
                        issues.append(f"{team_side.upper()}_{group.upper()}_STALE_OVER_6_HOURS")
                goalie = game[team_side]["goalie"]
                if (goalie["starts_current"] or 0) + (goalie["starts_prior"] or 0) < 10:
                    issues.append(f"{team_side.upper()}_GOALIE_INSUFFICIENT_HISTORY")
            if (parse_time(game["starts_at"]) - cutoff).total_seconds()/3600 > 8.:
                issues.append("FINAL_LINEUP_WINDOW_NOT_REACHED")
        if m["odds"] > 1.30: issues.append("ABOVE_PREFERRED_SINGLE_ODDS_CAP")
        if min(evs) <= 0: issues.append("NO_CONSERVATIVE_EDGE_IN_SCENARIOS")
        if detail["warnings"]: issues.append("LAMBDA_OUT_OF_RANGE")
        markets.append({"id": m["id"], "kind": m["kind"], "selection": m["selection"],
                        "line": m["line"], "team": m.get("team"), "score_basis": m["score_basis"],
                        "odds": m["odds"], "p_win": round(p[0], 5), "p_push": round(p[1], 5),
                        "p_loss": round(p[2], 5), "p_break_even_no_push": round(1/m["odds"],5),
                        "ev": round(evs[0], 5), "scenario_ev_min": round(min(evs), 5),
                        "scenario_ev_max": round(max(evs), 5), "issues": issues, "warnings": warnings,
                        "research_eligible": not issues})
    return {"game_id": game["game_id"], "matchup": f"{game['away']['name']} @ {game['home']['name']}",
            "status": game["status"], "lambda_home": round(h, 4), "lambda_away": round(a, 4),
            "lambda_total": round(h+a, 4), "projection_details": detail,
            "goalies_confirmed": goalies_confirmed, "lineups_quantified": roster_quantified,
            "markets": markets, "simulations": simulations}


def select_combo(results: list[dict], max_combo_odds: float = 4., max_legs: int = 6) -> dict:
    """Beam-search selection, max one market per game, never assume same-game independence.

    This does NOT estimate cross-game dependence. Only uses different-game legs.
    Push-bearing markets are omitted because the combo settlement needs special modeling.
    """
    # (legs, product_odds, lower_bound_on_joint_pwin)
    beam: list[tuple[list[dict], float, float]] = [([], 1., 1.)]
    for game in results:
        if game["status"] != "scheduled":
            continue
        options = [dict(game_id=game["game_id"], matchup=game["matchup"], **m)
                   for m in game["markets"] if m["research_eligible"] and m["p_push"] < 1e-5]
        options = sorted(options, key=lambda m: m["scenario_ev_min"], reverse=True)[:3]
        next_beam = beam.copy()  # skip this game
        for legs, odds, prob in beam:
            if len(legs) >= max_legs:
                continue
            for leg in options:
                combined_odds = odds * leg["odds"]
                if combined_odds > max_combo_odds + 1e-9:
                    continue
                conservative_p = max(0, (leg["scenario_ev_min"] + 1) / leg["odds"])
                next_beam.append((legs + [leg], combined_odds, prob * conservative_p))
        # Prune conservatively by adjusted joint EV, limiting runtime on full NHL slate.
        beam = sorted(next_beam, key=lambda z: z[1] * z[2] - 1 - 0.008*max(0, len(z[0])-1),
                      reverse=True)[:800]
    candidates = [(legs, odds, prob) for legs, odds, prob in beam
                  if legs and odds * prob > 1.]
    if not candidates:
        return {"legs": [], "odds": None, "conservative_joint_ev": None}
    legs, odds, prob = max(candidates,
                           key=lambda z: z[1]*z[2] - 1 - 0.008*max(0,len(z[0])-1))
    return {"legs": legs, "odds": round(odds,5),
            "conservative_joint_ev": round(odds * prob - 1, 5)}

def run_dataset(dataset: dict, simulations: int = DEFAULT_SIMULATIONS, seed: int = 1707,
                research: bool = False) -> dict:
    """Check every game/field before allowing any ticket candidate.

    Incomplete inputs remain visible in the report. No historical validation
    flag is required to *calculate*; probabilities stay explicitly provisional.
    """
    errors = validate_input(dataset)
    if errors:
        return {"status": "INVALID_INPUT", "errors": errors, "games": [], "ticket": None}
    checklist = audit_checks(dataset)
    results=[]
    for i,game in enumerate(dataset['games']):
        rep=next(x for x in checklist['games'] if x['game_id']==game['game_id'])
        if game['status']!='scheduled':
            results.append({"game_id":game['game_id'],"status":game['status'],"matchup":f"{game['away']['name']} @ {game['home']['name']}",
                            "markets":[],"skipped":"ALREADY_STARTED_OR_FINAL"})
            continue
        if rep['model_missing']:
            results.append({"game_id":game['game_id'],"status":game['status'],"matchup":f"{game['away']['name']} @ {game['home']['name']}",
                            "markets":[],"skipped":"CRITICAL_MODEL_INPUTS_MISSING","missing":rep['model_missing']})
            continue
        try:
            res=evaluate_game({**game,'_analysis_as_of':dataset['as_of']},seed+i*200,simulations)
        except (ValueError,TypeError,ZeroDivisionError) as e:
            results.append({"game_id":game['game_id'],"status":game['status'],"markets":[],"skipped":str(e)})
            continue
        if rep['status']!='READY_FOR_MODEL':
            for m in res['markets']:
                m['issues'].append('CHECKLIST_INCOMPLETE')
                m['research_eligible']=False
        results.append(res)
    # Include comparison tables even if some fields were checked unavailable.
    combo = select_combo(results) if checklist['complete'] else {"legs":[],"odds":None,"conservative_joint_ev":None}
    if not checklist['complete']:
        status='CHECKLIST_INCOMPLETE'
        ticket=None
    elif any(g.get('skipped')=='CRITICAL_MODEL_INPUTS_MISSING' for g in results):
        status='INSUFFICIENT_DATA'
        ticket=None
    elif dataset.get('synthetic_data'):
        status='SYNTHETIC_DEMO_ONLY'
        ticket=None
    elif combo['legs']:
        status='CANDIDATE_REVIEW_REQUIRED'
        ticket=combo
    else:
        status='NO_BET'
        ticket=None
    markets=[m for g in results for m in g['markets']]
    return {"engine_version":"1.8.0", "as_of":dataset['as_of'],"status":status,
            "model_note":"Heuristic uncalibrated baseline; computed probabilities are estimates, not certified betting edges.",
            "checklist":checklist,
            "audit":{"scheduled_game_count":len(dataset['expected_game_ids']),
                "processed_game_count":len(results),
                "confirmed_goalie_count":sum(g[s]['goalie']['status']=='confirmed' for g in dataset['games'] for s in ('home','away')),
                "unconfirmed_goalie_count":sum(g[s]['goalie']['status']!='confirmed' for g in dataset['games'] for s in ('home','away')),
                "market_count":len(markets),
                "research_eligible_market_count":sum(m['research_eligible'] for m in markets),
                "blocked_team_over_2_5":sum('SUSPENDED_TEAM_OVER_2_5' in m['issues'] for m in markets)},
            "games":results,"ticket":ticket,
            "candidate_preview_not_validated":combo if research else None}
