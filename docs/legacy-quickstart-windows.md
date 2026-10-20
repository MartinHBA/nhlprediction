# Rýchly štart v Docker Desktop (PowerShell)

Rozbaľ obsah ZIP priamo do `C:\git\nhlprediction` a skontroluj, že v tomto adresári vidíš `docker-compose.yml`.

```powershell
cd C:\git\nhlprediction
docker compose up --build -d
```

Otvoriť **http://localhost:8000/docs** — toto je lokálne Swagger UI.

### Pripraviť dáta na dnešný hrací deň

```powershell
docker compose exec nhl-api nhl-predict schedule 2026-10-20 -o /data/schedule.json --template /data/games.json
```

V `data\games.json` AI vyplní informácie podľa súboru `AI_COLLECTOR_PROMPT_v1.8.md`.

### Spustiť kontrolu a predikciu

```powershell
docker compose exec nhl-api nhl-predict checklist /data/games.json
docker compose exec nhl-api nhl-predict validate /data/games.json
docker compose exec nhl-api nhl-predict predict /data/games.json --sims 100000 -o /data/audit.json
```

Výsledok bude v `data\audit.json`. Namiesto posledného príkazu môžeš v Swagger UI použiť `POST /api/v1/predict/upload` a nahrať `games.json`.

Zastavenie: `docker compose down`.
