# Windows — NHL Observatory

Web je už v Azure:
https://nhlpredicttxocp2arkmldw.z6.web.core.windows.net

Na prezeranie výsledkov netreba Docker ani Python.

## Zapnutie zberu cez ChatGPT

1. Vo webovom ChatGPT pridaj vlastný MCP ako plugin **NHL Observatory**:
   `https://nhlpredict-txocp2arkmldw.azurewebsites.net/mcp`.
2. Použi OAuth a prihlás sa svojím Microsoft účtom z Azure subscription.
3. V chate s pluginom požiadaj o spracovanie celého dnešného programu NHL.
4. Po úspešnom ručnom behu vytvor pravidelnú úlohu podľa
   [pripraveného zadania](docs/chatgpt-scheduled-prompt.md).

Na účet ChatGPT sa už nevkladá `games.json` ručne. MCP ukladá dáta po zápasoch
a Java backend počíta výsledky. Dostupnosť pluginov, webového researchu a
bezobslužných zápisov treba overiť v konkrétnom účte.

## Zmena a opakované nasadenie

Potrebuješ Azure CLI, Java 21 a Maven:

```powershell
cd C:\git\nhlprediction
az account show
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/deploy.ps1
```

Používa sa resource group `nhlprediction`, región `westeurope`.
Skript nevytvára Azure OpenAI ani nevolá platené LLM API.

Podrobnosti: [Azure deployment](docs/azure-deployment.md).
Pôvodné lokálne Docker spúšťanie: [archivovaný quickstart](docs/legacy-quickstart-windows.md).
