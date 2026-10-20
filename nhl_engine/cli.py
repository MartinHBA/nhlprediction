from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys
from .core import run_dataset, validate_input
from .backtest import backtest
from .schedule import fetch_schedule, create_input_template
from .checklist import audit_checks


def main(argv=None):
    parser=argparse.ArgumentParser(description="NHL v1.9 checklist-enforced pregame engine")
    sub=parser.add_subparsers(dest="cmd",required=True)
    sch=sub.add_parser("schedule",help="Fetch official NHL calendar-day slate; optionally generate empty pregame data template")
    sch.add_argument("date",help="NHL calendar date YYYY-MM-DD")
    sch.add_argument("--output","-o",required=True)
    sch.add_argument("--template",default=None,help="Optional incomplete input template path (must fill actual data)")
    v=sub.add_parser("validate",help="Validate JSON schema and pregame cutoffs")
    v.add_argument("input")
    ck=sub.add_parser("checklist",help="Show per-game field checks and missing evidence")
    ck.add_argument("input")
    p=sub.add_parser("predict",help="Calculate market probabilities, EV, ticket research audit")
    p.add_argument("input")
    p.add_argument("--output","-o",default=None)
    p.add_argument("--sims",type=int,default=60_000)
    p.add_argument("--seed",type=int,default=1707)
    p.add_argument("--research",action="store_true",help="Include labeled exploratory combo even when NO BET")
    b=sub.add_parser("backtest",help="Evaluate archived pregame snapshots (JSONL)")
    b.add_argument("input")
    b.add_argument("--output","-o",default=None)
    b.add_argument("--sims",type=int,default=25_000)
    args=parser.parse_args(argv)
    if args.cmd=="schedule":
        snapshot=fetch_schedule(args.date)
        Path(args.output).write_text(json.dumps(snapshot,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        if args.template:
            template=create_input_template(snapshot)
            Path(args.template).write_text(json.dumps(template,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print(f"NHL schedule: {len(snapshot['games'])} regular-season games; saved {args.output}")
        if args.template: print(f"Incomplete template created: {args.template} (will NOT validate until filled)")
        return 0
    if args.cmd in ("validate","predict","checklist"):

        data=json.loads(Path(args.input).read_text(encoding="utf-8"))
        errors=validate_input(data)
        if args.cmd=="validate":
            audit=audit_checks(data) if not errors else None
            report={"valid":not errors and audit["complete"],"schema_valid":not errors,
                    "errors":errors,"checklist":audit,
                    "expected_games":len(data.get("expected_game_ids",[])),"actual_games":len(data.get("games",[]))}
        elif args.cmd=="checklist":
            report={"status":"INVALID_INPUT","errors":errors} if errors else audit_checks(data)
        else:
            if args.sims<1_000:
                parser.error("--sims must be >= 1000")
            report=run_dataset(data,args.sims,args.seed,args.research)
    else:
        lines=Path(args.input).read_text(encoding="utf-8").splitlines()
        report=backtest([json.loads(x) for x in lines if x.strip()],args.sims)
    formatted=json.dumps(report,ensure_ascii=False,indent=2)
    if getattr(args,"output",None):
        Path(args.output).write_text(formatted+"\n",encoding="utf-8")
        print(f"Wrote {args.output} ({report.get('status', 'report')})")
    else:
        print(formatted)
    if report.get("status")=="INVALID_INPUT" or report.get("valid") is False or (args.cmd=="checklist" and not report.get("complete",False)):
        return 2
    return 0

if __name__=="__main__":
    sys.exit(main())
