# NHL Prediction Engine v1.9 — lokálne Docker REST API

**Cieľ:** Ponechať pôvodný analytický engine a povinný checklist z v1.8, ale sprístupniť ich cez lokálne HTTP API. **Vo Windows netreba inštalovať Python ani príkaz `nhl-predict`.** Stačí Docker Desktop v režime Linux containers.

## Spustenie na Windows (PowerShell)

Rozbaľ celý ZIP tak, aby v adresári `C:\git\nhlprediction` boli **priamo** súbory `docker-compose.yml`, `Dockerfile`, `pyproject.toml` a adresár `nhl_engine`.

```powershell
cd C:\git\nhlprediction
docker compose up --build -d
docker compose ps
```

Otvoriť v prehliadači:

- **http://localhost:8000/docs** — interaktívne Swagger API (použi tlačidlo **Try it out**)
- http://localhost:8000/health — stav služby
- http://localhost:8000/api/v1/schema — presná JSON schéma pre LLM zberač dát

API port je vystavený iba cez **127.0.0.1** (lokálny počítač, nie lokálna sieť). Nie je tu autentifikácia; **nezverejňuj port do internetu**.

## Postup A: všetko cez API/Swagger

1. `GET /api/v1/schedule/{day}?template=true` — zadaj dátum napríklad `2026-10-20`. Vráti kompletný `games.json` scaffold pre každý zápas NHL. Stiahni/ulož odpoveď ako `games.json`.
2. Pošli `games.json` a priložený **`AI_COLLECTOR_PROMPT_v1.8.md`** AI zberaču. AI musí vyplniť polia, dôkazy a stavy kontrol bez vymýšľania údajov.
3. `POST /api/v1/checklist` — vlož kompletný aktualizovaný JSON a prezri si nepreverené parametre.
4. `POST /api/v1/validate` — over schému aj úplnosť všetkých kontrol.
5. `POST /api/v1/predict/upload` — v Swaggeri nahraj `games.json` pomocou formulára **file**, nastav `sims=10000` (alebo `100000`) a klikni Execute. Dostaneš výsledok s projekciami, kontrolami, prípadne návrhom tiketu a upozorneniami.

Alternatívne: `POST /api/v1/predict` prijíma **celý JSON objekt** ako request body bez uploadu.

Ak údaje neboli preverované, výsledkom bude `CHECKLIST_INCOMPLETE`; ak chýbajú kritické vstupy, `INSUFFICIENT_DATA`; ak nič nezodpovedá filtrom, `NO_BET`. Engine **nevymýšľa kurzy, zostavy ani xG**.

## Postup B: použitie súborov cez Docker volume

`docker-compose.yml` mapuje lokálny adresár `./data` na `/data` v kontajneri. CLI je stále dostupné *vnútri kontajnera*, bez Windows Pythonu.

```powershell
# Generuje data\schedule.json a data\games.json priamo do Windows adresára
docker compose exec nhl-api nhl-predict schedule 2026-10-20 -o /data/schedule.json --template /data/games.json

# Po doplnení games.json pomocou AI zberača
docker compose exec nhl-api nhl-predict checklist /data/games.json
docker compose exec nhl-api nhl-predict validate /data/games.json
docker compose exec nhl-api nhl-predict predict /data/games.json --sims 100000 -o /data/audit.json
```

Konečný výstup bude `C:\git\nhlprediction\data\audit.json`.

### Volanie API z PowerShellu

```powershell
# Health
Invoke-RestMethod http://localhost:8000/health

# Kompletná šablóna pre LLM (odpoveď ulož do JSON súboru)
Invoke-RestMethod 'http://localhost:8000/api/v1/schedule/2026-10-20?template=true' | ConvertTo-Json -Depth 100 | Set-Content -Encoding utf8 .\data\games.json

# Priame poslanie súboru na predikciu (curl.exe, nie alias curl)
curl.exe -sS -X POST 'http://localhost:8000/api/v1/predict?sims=100000' -H 'Content-Type: application/json' --data-binary '@data/games.json' -o data/audit.json
```

Poznámka: automaticky vygenerovaná šablóna **nie je hotová na výpočet**. Obsahuje `not_checked` a `null`, ktoré musí LLM dohľadať alebo označiť ako nedostupné.

## Endpointy

| Method | Endpoint | Účel |
|---|---|---|
| GET | `/health` | Stav služby |
| GET | `/api/v1/schema` | Vstupná schéma |
| GET | `/api/v1/schedule/{day}` | Kompletný program podľa NHL API |
| GET | `/api/v1/schedule/{day}?template=true` | Prázdna šablóna checklistu |
| POST | `/api/v1/checklist` | Nezistené a nedostupné parametre |
| POST | `/api/v1/validate` | Štruktúrna aj obsahová validácia |
| POST | `/api/v1/predict` | Analýza JSON v request body |
| POST | `/api/v1/predict/upload` | Analýza nahraného `games.json` (max. 8 MiB) |

`/api/v1/predict` a `/api/v1/predict/upload` podporujú `sims=1000..250000`, `seed` a `research=true/false`. Default `sims=10000` na rýchle odpovede.

## Aktualizácia, logy, zastavenie

```powershell
docker compose logs -f nhl-api
docker compose restart nhl-api
docker compose down
```

Ak máš na počítači obsadený port 8000, v `docker-compose.yml` zmeň napríklad `127.0.0.1:8000:8000` na `127.0.0.1:8001:8000` a otváraj `http://localhost:8001/docs`.

## Čo sa nezmenilo oproti v1.8

- Povinná kontrola **všetkých zápasov a všetkých polí**, vrátane zdrojov a označenia `unavailable`.
- Rozlíšenie moneyline, výsledku po 60 minútach, handicapu, over/under a správne vyhodnotenie PUSH.
- Predbežný výpočet gólov, simulácia a citlivostné scenáre podľa pôvodnej heuristiky.
- **Tímový over 2,5 gólu je pozastavený.**
- Vstup sa musí doplniť reálnymi predzápasovými údajmi; API samo neťahá xG, odds, zostavy ani informácie o brankároch.
- Chýbajúca historická kalibrácia **neblokuje výpočet**. Model však stále nie je historicky overený a jeho odhady nie sú garantované.

## Vývojárske testy (mimo Docker Desktop)

```bash
python -m pip install -e '.[dev]'
python -m pytest -q
```

42 testov prechádza, vrátane testov REST API, uploadu, checklistu a pôvodných analytických kontrol. Docker image sa zostavuje štandardným `docker compose up --build -d`. V prostredí prípravy nebol dostupný Docker daemon, preto nebol testovaný samotný build v Docker Desktop.
