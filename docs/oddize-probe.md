# Manuálny test Oddize z Azure

Function App: `nhlpredict-txocp2arkmldw`, funkcia `diagnosticsOddize`.
Je to samostatná diagnostika; neukladá zápasy, research ani kurzy a nespúšťa
denný flow. Existujúci NHL import ostáva nezmenený.

Endpoint:

```text
GET https://nhlpredict-txocp2arkmldw.azurewebsites.net/api/diagnostics/oddize
```

Vyžaduje **master key** (`authLevel=admin`). V Azure Portal otvor funkciu,
Code + Test → Test/Run, zvoľ GET a master key. Mimo portálu posielaj master key
v hlavičke `x-functions-key`. Bežný function key nestačí.

## Režimy

| Query | Oddize volanie | Cena jedného testu |
| --- | --- | --- |
| `mode=connection` (predvolené) | `/api/v1/sports` | 0 kreditov |
| `mode=usage` | `/api/v1/account/usage` | 0 kreditov |
| `mode=nhl&day=2026-10-23` | `/api/v1/odds/latest?sport=nhl&date=2026-10-23&limit=3` | 1 kredit |

`day` je voliteľný NHL dátum v America/New_York. Bez neho sa použije aktuálny
NHL deň. Každé ručné opakovanie `mode=nhl` môže stáť ďalší kredit.
Automatické opakovanie ani časovač tejto funkcie neexistujú.

Na autentifikovaný test pridaj v Function App → Settings → Environment variables
→ App settings premennú **`ODDIZE_API_KEY`**, ulož a aplikuj zmeny.
Oddize kľúč sa neposiela v URL, tele požiadavky ani ako parameter diagnostiky.
Master key patrí Azure; `ODDIZE_API_KEY` je samostatný kľúč poskytovateľa.
Nedávaj ich do repozitára ani do verejného frontend configu.

## Interpretácia výsledku

- `reachable=true`, `oddize_http_status=200`, `success=true`, `result=OK`:
  z Azure prišla očakávaná API odpoveď. Pri NHL sú priložené počty a malá vzorka.
- `reachable=true`, `oddize_http_status=401`, `result=API_KEY_REQUIRED`:
  Azure sa dostalo k Oddize, ale v aplikácii zatiaľ chýba Oddize kľúč.
  Autentifikovaný prístup ani pokrytie NHL to ešte nepotvrdzuje.
- `API_KEY_REJECTED`: nakonfigurovaný kľúč Oddize odmietlo.
- `CREDITS_EXHAUSTED` / `RATE_LIMITED`: Oddize odpovedalo 402 / 429.
- `ACCESS_DENIED`: Oddize odpovedalo 403; diagnostika nehádá jeho príčinu.
- `NETWORK_ERROR`: neposkytla sa HTTP odpoveď; `error_type` rozlíši napríklad timeout.
- `INVALID_JSON_RESPONSE` / `UNEXPECTED_RESPONSE`: odpoveď nie je očakávaný API JSON.

HTTP 200 samotnej diagnostiky znamená, že test dobehol. Výsledok spojenia čítaj
z `success`, `reachable` a `oddize_http_status`. HTTP 400 znamená chybné parametre.
HTTP 401 od samotnej Function App bez diagnostického JSON znamená problém s Azure
master key, nie odpoveď Oddize. Žiadne surové chybové telo poskytovateľa ani
hodnota API kľúča sa nevracajú.

Dokumentácia: https://oddize.com/api/v1/docs · https://oddize.com/odds-api

## Skúška kompletného katalógu (od 4.0)

GET `/api/diagnostics/oddize?mode=catalogue&day=YYYY-MM-DD` s master key
otestuje produkčný parser a párovanie s oficiálnym NHL programom v Azure.
Výstup obsahuje počty, dostupné typy, niekoľko cien a validačné chyby.
Denný research ani publikáciu nemení. Ukladá iba privátnu preview cache,
oddelenú od produkčného denného katalógu. Prvý beh stojí najviac 1 + 4×N + 2
kredity; opakovaný beh toho istého dňa úspešné odpovede znovu nesťahuje.
