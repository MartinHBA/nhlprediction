# NHL Observatory — research a analytik, stratégia v1.8 / Oddize

Azure je dátová vrstva bez predikčného modelu, simulácií, skórových predikcií,
pravdepodobností, EV či automatických tipov. Rozhoduje GPT kvalitatívne.

1. get_instructions, get_schema, start_day: NHL deň America/New_York; celý program.
   Pri PREFILLING a odds_import QUEUED/RUNNING čakaj cez get_results.
2. Pokračuj cez resume_plan: hľadaj iba unchecked/refresh polia a konkrétne chyby.
   SKIP_COMPLETE nepreveruj/neukladaj znova. Zachovaj natívne čísla a dôkazy.
   Natívne field checks, Oddize markets a market checks môžu byť v get_game
   vynechané; server ich obnoví. Ceny čítaj výhradne cez get_analysis.
3. Dopĺňaj oboch brankárov, zostavy, absencie, PP/PK a kontext z reálnych zdrojov.
   Probable nie je confirmed. Pri zmene mena zruš staré starts/gsax/save_pct.
   found znamená hodnotu, unavailable null podľa schémy s dôvodom/dôkazom;
   [] je overený prázdny zoznam. Nevymýšľaj číselný dopad zostavy či zdroje.
4. Porovnateľné štatistiky tímov a brankárov: dôraz 60 % aktuálna/40 % minulá
   sezóna, správne jednotky a vzorky. Malá vzorka znamená neistotu. Povinne
   posúď OBA útoky proti súperovej obrane/brankárovi, xGF/xGA/60, high-danger,
   strely, PP/PK a absencie. Silný útok jedného tímu sám neodôvodňuje OVER.
5. Doplnkový doložený kontext ulož do analysis_context {topic,summary,evidence_ids}.
   save_game ukladaj priebežne s revision; pri konflikte načítaj aktuálny stav.
6. Ceny sú iba backendové Oddize QUOTED zo zberu o 19:45 Europe/Bratislava.
   Ceny, hranice a pravidlá NEHĽADAJ na webe, neodhaduj a nezapisuj do markets.
   Backend uzatvára market_searches. Neprítomné typy nevyrábaj. Rešpektuj
   observed_at, snapshot_at, zdroj, bookmakera, pravidlá a blocking_reasons.
7. Všetky trhy vrátane gólov smú byť regulation ALEBO official_final podľa
   overených pravidiel. Nikdy nepreznačuj OT/SO na 60 minút. Neoverené/unknown
   vyhodnotenie je neprípustné. Tímový over 2,5 je vždy zakázaný.
8. Najprv zhodnoť zápas nezávisle od cien, potom NAVZÁJOM porovnaj všetkých
   vhodných selectable kandidátov v 1,18–1,35 vrátane: víťaz, oba handicapy,
   over, under, ďalšie dostupné typy a NO_BET. Vyber najlepšie podloženú
   kvalitatívnu šancu úspechu, nie povinné EV alebo prvý obhájiteľný trh.
   Uveď konkrétny protiargument; pred OVER nízkogólový a pred UNDER vysokogólový
   scenár. Nevynucuj rôznorodosť. LOW TIP je povolený s obhájiteľnou oporou;
   confidence nie je percento úspechu. Bez dostatočnej opory NO_BET.
9. check_day a finalize_day(day) BEZ sims uzavrú dáta; READY_FOR_ANALYSIS nie
   je publikácia. get_analysis(day) načíta spoločný snapshot. post_tips uloží
   všetky game_id naraz, najviac jeden TIP na zápas; začaté zápasy NO_BET,
   historický predzápasový tip zostane zachovaný. Použi aktuálne candidate_id,
   revision/analysis_id a relevantné evidence_ids. Over PUBLISHED cez get_results.
10. Po publikácii sa podklady zmrazia, timer do 08:00 dopĺňa iba skóre.
    Nevykonávaj stávky ani správu rozvrhu. Skutočné chyby reportuj presne,
    nevymýšľaj bezpečnostné blokovanie. Uložený research zostane resumable.

## Autoritatívna JSON Schema (zhodná s MCP get_schema)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://example.invalid/nhl-engine-v1.8.schema.json",
  "title": "NHL v1.8 enforced collection checklist",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "as_of",
    "expected_game_ids",
    "synthetic_data",
    "evidence",
    "games"
  ],
  "properties": {
    "as_of": {
      "type": "string",
      "format": "date-time"
    },
    "synthetic_data": {
      "type": "boolean"
    },
    "expected_game_ids": {
      "type": "array",
      "uniqueItems": true,
      "items": {
        "type": "string"
      },
      "minItems": 1
    },
    "games": {
      "type": "array",
      "minItems": 1,
      "items": {
        "$ref": "#/$defs/game"
      }
    },
    "evidence": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "id",
          "url",
          "checked_at"
        ],
        "properties": {
          "id": {
            "type": "string",
            "minLength": 1
          },
          "url": {
            "type": "string",
            "format": "uri",
            "pattern": "^https?://"
          },
          "checked_at": {
            "type": "string",
            "format": "date-time"
          }
        }
      }
    }
  },
  "$defs": {
    "source": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "url",
        "checked_at",
        "status"
      ],
      "properties": {
        "url": {
          "type": "string",
          "format": "uri",
          "pattern": "^https?://"
        },
        "checked_at": {
          "type": "string",
          "format": "date-time"
        },
        "status": {
          "enum": [
            "verified",
            "unverified",
            "synthetic"
          ]
        }
      }
    },
    "stats": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "games",
        "xgf_5v5_per60",
        "xga_5v5_per60",
        "pp_xgf_per60",
        "pk_xga_per60",
        "pp_drawn_per_game",
        "penalties_taken_per_game",
        "source",
        "shots_for_per_game",
        "shots_against_per_game",
        "high_danger_for_per60",
        "high_danger_against_per60",
        "points_pct"
      ],
      "properties": {
        "games": {
          "type": [
            "integer",
            "null"
          ],
          "minimum": 1,
          "maximum": 100
        },
        "xgf_5v5_per60": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 12
        },
        "xga_5v5_per60": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 12
        },
        "pp_xgf_per60": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 30
        },
        "pk_xga_per60": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 30
        },
        "pp_drawn_per_game": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 12
        },
        "penalties_taken_per_game": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 12
        },
        "source": {
          "$ref": "#/$defs/source"
        },
        "shots_for_per_game": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 100
        },
        "shots_against_per_game": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 100
        },
        "high_danger_for_per60": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 100
        },
        "high_danger_against_per60": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 100
        },
        "points_pct": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 100
        }
      }
    },
    "goalie": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "name",
        "status",
        "starts_current",
        "starts_prior",
        "gsax_per60_current",
        "gsax_per60_prior",
        "source"
      ],
      "properties": {
        "name": {
          "type": [
            "string",
            "null"
          ],
          "minLength": 1
        },
        "status": {
          "enum": [
            "confirmed",
            "probable",
            "unknown"
          ]
        },
        "starts_current": {
          "type": [
            "integer",
            "null"
          ],
          "minimum": 0
        },
        "starts_prior": {
          "type": [
            "integer",
            "null"
          ],
          "minimum": 0
        },
        "save_pct_current": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 1,
          "description": "NHL regular-season saves / shotsAgainst before the NHL day; fraction 0..1, including relief appearances."
        },
        "save_pct_prior": {
          "type": [
            "number",
            "null"
          ],
          "minimum": 0,
          "maximum": 1,
          "description": "Previous NHL regular-season saves / shotsAgainst before the NHL day; fraction 0..1."
        },
        "gsax_per60_current": {
          "type": [
            "number",
            "null"
          ],
          "minimum": -3,
          "maximum": 3
        },
        "gsax_per60_prior": {
          "type": [
            "number",
            "null"
          ],
          "minimum": -3,
          "maximum": 3
        },
        "source": {
          "$ref": "#/$defs/source"
        }
      }
    },
    "roster": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "quantified",
        "attack_delta_goals",
        "defense_delta_goals_allowed",
        "absences",
        "source",
        "returns",
        "scratches",
        "forward_lines",
        "defense_pairs",
        "power_play_units"
      ],
      "properties": {
        "quantified": {
          "type": "boolean"
        },
        "attack_delta_goals": {
          "type": [
            "number",
            "null"
          ],
          "minimum": -2,
          "maximum": 2
        },
        "defense_delta_goals_allowed": {
          "type": [
            "number",
            "null"
          ],
          "minimum": -2,
          "maximum": 2
        },
        "absences": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "source": {
          "$ref": "#/$defs/source"
        },
        "returns": {
          "type": [
            "array",
            "null"
          ],
          "items": {
            "type": "string"
          }
        },
        "scratches": {
          "type": [
            "array",
            "null"
          ],
          "items": {
            "type": "string"
          }
        },
        "forward_lines": {
          "type": [
            "array",
            "null"
          ],
          "items": {
            "type": "string"
          }
        },
        "defense_pairs": {
          "type": [
            "array",
            "null"
          ],
          "items": {
            "type": "string"
          }
        },
        "power_play_units": {
          "type": [
            "array",
            "null"
          ],
          "items": {
            "type": "string"
          }
        }
      }
    },
    "context": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "rest_days",
        "travel_timezones"
      ],
      "properties": {
        "rest_days": {
          "type": [
            "integer",
            "null"
          ],
          "minimum": 0,
          "maximum": 14
        },
        "travel_timezones": {
          "type": [
            "integer",
            "null"
          ],
          "minimum": 0,
          "maximum": 12
        }
      }
    },
    "team": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "name",
        "prior",
        "current",
        "goalie",
        "roster",
        "context"
      ],
      "properties": {
        "name": {
          "type": "string",
          "minLength": 1
        },
        "prior": {
          "$ref": "#/$defs/stats"
        },
        "current": {
          "$ref": "#/$defs/stats"
        },
        "goalie": {
          "$ref": "#/$defs/goalie"
        },
        "roster": {
          "$ref": "#/$defs/roster"
        },
        "context": {
          "$ref": "#/$defs/context"
        }
      }
    },
    "market": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "id",
        "kind",
        "selection",
        "line",
        "odds",
        "score_basis",
        "source",
        "settlement_verified",
        "rules_url"
      ],
      "properties": {
        "id": {
          "type": "string",
          "minLength": 1
        },
        "kind": {
          "enum": [
            "total",
            "team_total",
            "handicap",
            "moneyline",
            "regulation_1x2",
            "double_chance"
          ]
        },
        "selection": {
          "enum": [
            "over",
            "under",
            "home",
            "away",
            "draw",
            "1X",
            "X2",
            "12"
          ]
        },
        "line": {
          "type": [
            "number",
            "null"
          ],
          "minimum": -10,
          "maximum": 15
        },
        "team": {
          "enum": [
            "home",
            "away"
          ]
        },
        "odds": {
          "type": "number",
          "exclusiveMinimum": 1,
          "maximum": 100
        },
        "score_basis": {
          "enum": [
            "regulation",
            "official_final",
            "unknown"
          ]
        },
        "source": {
          "$ref": "#/$defs/source"
        },
        "settlement_verified": {
          "type": "boolean"
        },
        "rules_url": {
          "type": [
            "string",
            "null"
          ],
          "format": "uri"
        },
        "evidence_ids": {
          "type": "array",
          "minItems": 1,
          "maxItems": 20,
          "uniqueItems": true,
          "items": {
            "type": "string",
            "minLength": 1
          }
        },
        "provider": {
          "type": "string",
          "minLength": 1,
          "maxLength": 160
        },
        "bookmaker": {
          "type": "string",
          "minLength": 1,
          "maxLength": 160
        },
        "bookmaker_code": {
          "type": "string",
          "minLength": 1,
          "maxLength": 160
        },
        "provider_event_id": {
          "type": "string",
          "minLength": 1,
          "maxLength": 160
        },
        "offer_type": {
          "type": "string",
          "minLength": 1,
          "maxLength": 160
        },
        "observed_at": {
          "type": "string",
          "format": "date-time"
        },
        "snapshot_at": {
          "type": "string",
          "format": "date-time"
        },
        "quality_warnings": {
          "type": "array",
          "items": {
            "type": "string"
          },
          "uniqueItems": true
        }
      }
    },
    "game": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "game_id",
        "starts_at",
        "status",
        "home",
        "away",
        "markets",
        "checks"
      ],
      "properties": {
        "game_id": {
          "type": "string",
          "minLength": 1
        },
        "starts_at": {
          "type": "string",
          "format": "date-time"
        },
        "status": {
          "enum": [
            "scheduled",
            "live",
            "final"
          ]
        },
        "home": {
          "$ref": "#/$defs/team"
        },
        "away": {
          "$ref": "#/$defs/team"
        },
        "markets": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/market"
          }
        },
        "checks": {
          "$ref": "#/$defs/checks"
        },
        "analysis_context": {
          "type": "array",
          "maxItems": 32,
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "topic",
              "summary",
              "evidence_ids"
            ],
            "properties": {
              "topic": {
                "type": "string",
                "minLength": 1,
                "maxLength": 80
              },
              "summary": {
                "type": "string",
                "minLength": 1,
                "maxLength": 4000
              },
              "evidence_ids": {
                "type": "array",
                "minItems": 1,
                "maxItems": 20,
                "uniqueItems": true,
                "items": {
                  "type": "string",
                  "minLength": 1
                }
              }
            }
          }
        }
      }
    },
    "check": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "status",
        "evidence_ids",
        "note"
      ],
      "properties": {
        "status": {
          "enum": [
            "found",
            "unavailable",
            "not_checked"
          ]
        },
        "evidence_ids": {
          "type": "array",
          "uniqueItems": true,
          "items": {
            "type": "string"
          }
        },
        "note": {
          "type": "string"
        }
      }
    },
    "checks": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "fields",
        "market_searches"
      ],
      "properties": {
        "fields": {
          "type": "object",
          "additionalProperties": {
            "$ref": "#/$defs/check"
          }
        },
        "market_searches": {
          "type": "object",
          "additionalProperties": {
            "$ref": "#/$defs/check"
          }
        }
      }
    }
  }
}
```
