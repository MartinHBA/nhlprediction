# Windows — NHL Analyzer Online

Web je už v Azure:
https://www.nhlanalyzer.online

Azure adresa: https://orange-mud-0c78ff503.1.azurestaticapps.net

Výsledky otvoríš priamo v prehliadači.

## Zapnutie zberu cez ChatGPT

1. Vo webovom ChatGPT pridaj vlastný MCP ako plugin **NHL Observatory**:
   `https://nhlpredict-txocp2arkmldw.azurewebsites.net/mcp`.
2. Použi OAuth a prihlás sa svojím Microsoft účtom z Azure subscription.
3. V chate s pluginom požiadaj o spracovanie celého dnešného programu NHL.
4. Po úspešnom ručnom behu vytvor pravidelnú úlohu podľa
   [pripraveného zadania](docs/chatgpt-scheduled-prompt.md).

Na účet ChatGPT sa už nevkladá `games.json` ručne. MCP ukladá dáta po zápasoch
a backend pripravuje dáta a vyhodnocuje uložené tipy podľa výsledkov zápasov.
Dostupnosť pluginov, webového researchu a
bezobslužných zápisov treba overiť v konkrétnom účte.

## Zmena a opakované nasadenie

Commit a push do `main` spustí GitHub workflow **CI/CD**: Java a frontend testy,
zostavenie balíkov, nasadenie do Azure a overenie backendu aj webu. Pri zlyhaní
testov sa deploy nespustí. Pull requesty iba testujú.

Na ručné nasadenie mimo pipeline potrebuješ Azure CLI, Java 21, Maven a Node 22:

```powershell
cd C:\git\nhlprediction
az account show
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/deploy.ps1
```

Používa sa resource group `nhlprediction`, región `westeurope`.
Skript nevytvára Azure OpenAI ani nevolá platené LLM API.

Podrobnosti: [Azure deployment](docs/azure-deployment.md).
