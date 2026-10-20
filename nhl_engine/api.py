"""Local-only NHL prediction engine HTTP API.

Wraps the v1.8 checklist and math without changing the predictive model.
The service is intended for localhost use behind Docker Desktop port publishing.
"""
from __future__ import annotations

from datetime import date
from typing import Any, Annotated
import json

from fastapi import FastAPI, HTTPException, Query, UploadFile, File
from fastapi.responses import RedirectResponse

from .checklist import audit_checks
from .core import SCHEMA, run_dataset, validate_input
from .schedule import fetch_schedule, create_input_template

app = FastAPI(
    title="NHL Prediction Engine API",
    description=(
        "Local REST API for NHL data validation, mandatory source checklist and "
        "heuristic betting-market analysis. All odds and pregame information must "
        "be provided; this API does not collect bookmaker lines or goalie updates. "
        "Model estimates are provisional, not established profitable betting edges."
    ),
    version="1.9.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

MAX_JSON_BYTES = 8 * 1024 * 1024


@app.get("/", include_in_schema=False)
def root():
    return RedirectResponse(url="/docs")


@app.get("/health", tags=["System"])
def health() -> dict:
    return {"status": "ok", "api_version": "1.9.0", "model_version": "1.8.0"}


@app.get("/api/v1/schema", tags=["Schema"])
def schema() -> dict:
    """Exact input JSON Schema. Use alongside the LLM collector prompt."""
    return SCHEMA


@app.get("/api/v1/schedule/{day}", tags=["Schedule"])
def schedule(day: date, template: bool = Query(False, description="Return prefilled games.json checklist scaffold")) -> dict:
    """Fetch all regular-season NHL games for one NHL calendar date."""
    try:
        snapshot = fetch_schedule(day.isoformat())
    except (OSError, ValueError, KeyError, TimeoutError) as e:
        raise HTTPException(status_code=502, detail=f"NHL schedule source unavailable or invalid: {e}") from e
    if template:
        return create_input_template(snapshot)
    return snapshot


def validation_report(data: dict[str, Any]) -> dict[str, Any]:
    errors = validate_input(data)
    checks = audit_checks(data) if not errors else None
    return {
        "valid": not errors and bool(checks and checks["complete"]),
        "schema_valid": not errors,
        "errors": errors,
        "checklist": checks,
        "expected_games": len(data.get("expected_game_ids", [])),
        "actual_games": len(data.get("games", [])),
    }


@app.post("/api/v1/validate", tags=["Analysis"])
def validate(data: dict[str, Any]) -> dict[str, Any]:
    """Validate structure, field evidence and checklist completeness."""
    return validation_report(data)


@app.post("/api/v1/checklist", tags=["Analysis"])
def checklist(data: dict[str, Any]) -> dict[str, Any]:
    """Show missing/unavailable parameters for every scheduled game."""
    errors = validate_input(data)
    return {"status": "INVALID_INPUT", "errors": errors} if errors else audit_checks(data)


def _predict(data: dict[str, Any], sims: int, seed: int, research: bool) -> dict:
    return run_dataset(data, simulations=sims, seed=seed, research=research)


@app.post("/api/v1/predict", tags=["Analysis"])
def predict(
    data: dict[str, Any],
    sims: Annotated[int, Query(ge=1000, le=250000)] = 10000,
    seed: int = 1707,
    research: bool = False,
) -> dict:
    """Validate, audit all games and calculate probabilities/market suitability.

    No bet is produced if critical checks are missing; no calibration is claimed.
    """
    return _predict(data, sims, seed, research)


@app.post("/api/v1/predict/upload", tags=["Analysis"])
async def predict_uploaded_file(
    file: Annotated[UploadFile, File(description="games.json; upload from Swagger or Postman")],
    sims: Annotated[int, Query(ge=1000, le=250000)] = 10000,
    seed: int = 1707,
    research: bool = False,
) -> dict:
    """Upload games.json directly instead of pasting its JSON into the request body."""
    raw = await file.read(MAX_JSON_BYTES + 1)
    if len(raw) > MAX_JSON_BYTES:
        raise HTTPException(status_code=413, detail="JSON file too large (8 MiB max)")
    try:
        data = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeError, json.JSONDecodeError) as e:
        raise HTTPException(status_code=400, detail=f"Invalid UTF-8 JSON: {e}") from e
    if not isinstance(data, dict):
        raise HTTPException(status_code=400, detail="Expected top-level JSON object")
    return _predict(data, sims, seed, research)
