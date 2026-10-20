# Nasadenie do Azure

Nasadené 20. októbra 2026 do subscription `Pay-As-You-Go`, región `westeurope`.
Projekt `C:\git\ainoviny` slúžil iba ako čítaná referencia. Nebol upravovaný.

| Súčasť | Hodnota |
| --- | --- |
| Resource group | `nhlprediction` |
| Function App / Java 21 | `nhlpredict-txocp2arkmldw` |
| Windows Consumption plán | `nhlpredict-txocp2arkmldw-plan` |
| Storage / Standard LRS | `nhlpredicttxocp2arkmldw` |
| Web | https://nhlpredicttxocp2arkmldw.z6.web.core.windows.net |
| MCP | https://nhlpredict-txocp2arkmldw.azurewebsites.net/mcp |
| Health | https://nhlpredict-txocp2arkmldw.azurewebsites.net/health |
| Microsoft app registration | `NHL Prediction MCP` |
| GitHub deployment identity | `nhlpredict-github` (user-assigned managed identity) |

Nie je nasadený Azure OpenAI, platený AI model, Bing Grounding, databáza ani
stále bežiaci Premium plán. ChatGPT vykonáva research v pripojenom chate v rámci
predplatného a jeho limitov. Azure účtuje bežnú spotrebu Functions, Storage a prenosov.
Bezplatné limity závisia aj od ostatnej spotreby v subscription.

## Pripojenie do ChatGPT

1. Vo webovom ChatGPT otvor Plugins a **Add custom MCP server**. Ak túto voľbu
   tvoj účet nemá, treba najprv vyriešiť dostupnosť tejto funkcie v účte.
2. Názov: **NHL Observatory**. Server URL:
   `https://nhlpredict-txocp2arkmldw.azurewebsites.net/mcp`.
3. Zvoľ OAuth s automatickým discovery/dynamickou registráciou. Nezadávaj
   OpenAI API kľúč ani tajomstvo Microsoft aplikácie. Server poskytuje DCR.
4. Pri pripájaní sa prihlás svojím Microsoft účtom z Azure subscription.
   Aplikácia overuje podpis, issuer, audience, nonce, čas a presné owner object ID.
   Ostatní používatelia nedostanú prístup ani v rovnakom tenante.
5. Nainštaluj plugin, vyber ho v novom chate a pošli napríklad:
   „Použi NHL Observatory, načítaj pokyny a spracuj dnešný program NHL.“
6. Najprv over jeden manuálny research a uloženie. Potom použi
   [pripravené zadanie](chatgpt-scheduled-prompt.md) pre naplánovanú úlohu.
   Plugin aj webové nástroje musia byť dostupné v konkrétnej úlohe. Potvrdenia
   zápisov, limity a obnovenie prihlásenia môžu vyžadovať zásah používateľa.

Microsoft login ani inštalácia pluginu v používateľovom ChatGPT nie sú vykonané
samotným Azure deploymentom. Plánovaná úloha zatiaľ nie je vytvorená. Azure
časovač zbiera dáta a kurzy, čistí expirované OAuth údaje a označuje zaseknuté importy; AI research
spúšťa ChatGPT. Dostupnosť a bezobslužný beh treba potvrdiť v používateľovom účte.

Oficiálne podklady: [vlastný MCP](https://developers.openai.com/api/docs/guides/custom-mcp-server),
[OAuth](https://developers.openai.com/plugins/build/auth),
[naplánované úlohy](https://learn.chatgpt.com/docs/automations).

## CI/CD z GitHubu

Workflow `.github/workflows/ci-cd.yml` sa spúšťa pri pushi do `main`, pull requeste
alebo manuálne cez Actions → CI/CD → Run workflow. Každý beh má dva build joby:
Java 21 + Maven a Node 22 + frontend testy. Otestovaný Functions ZIP a statické
súbory vrátane všetkých log sa uchovajú ako artefakty sedem dní.

Po úspechu oboch jobov sa z `main` automaticky vykoná deploy. Pull requesty
deployment nespúšťajú. Behy rovnakej vetvy sú serializované, takže starší build
nemôže prepísať novší deployment. Najprv sa nasadí Functions ZIP a overí `/health`,
potom statický web a jeho `release.json` s presným SHA commitu. Výsledok aj odkazy
sú v súhrne GitHub Actions. Workflow nevykonáva NHL research ani nemení uložené
zápasy a neprenastavuje Microsoft OAuth tajomstvo či existujúce app settings.

Azure prihlásenie používa `azure/login@v3` a krátkodobý GitHub OIDC token.
Identita `nhlpredict-github` má federáciu iba pre
`repo:MartinHBA@49184077/nhlprediction@1426535972:ref:refs/heads/main`
(presný claim overený v tomto repozitári, vrátane nemenných GitHub ID)
a tieto Azure RBAC role:

| Rola | Rozsah |
| --- | --- |
| Website Contributor | iba Function App `nhlpredict-txocp2arkmldw` |
| Storage Blob Data Contributor | iba statický kontajner `$web` |

Konfigurácia identity a rolí je v samostatnej šablóne
`infrastructure/github-oidc.bicep`. Tá nemení nastavenia existujúcej aplikácie.
Prípadné opakované vytvorenie konfigurácie vykoná správca s Azure oprávneniami:

```powershell
az deployment group create --resource-group nhlprediction --name nhl-github-oidc --template-file infrastructure/github-oidc.bicep
```

Verejné `clientId`, `tenantId` a `subscriptionId` sú nastavené priamo vo workflow.
Po vytvorení inej identity aktualizuj `AZURE_CLIENT_ID` podľa výstupu šablóny.
Nie sú to prihlasovacie tajomstvá. Publish profile, Azure client secret ani storage
connection string sa do GitHubu neukladajú. Existujúce vlastníkovo Microsoft
prihlásenie pre ChatGPT MCP zostáva samostatné.

Podklady: [GitHub OIDC pre Azure](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-azure),
[Azure Functions deployment](https://learn.microsoft.com/en-us/azure/azure-functions/functions-how-to-github-actions).

## Ručný deployment cez Azure CLI

Potrebné: Azure CLI s prihlásením, Java 21, Maven, PowerShell. Node nie je potrebný
na zostavenie webu; frontend je obyčajné HTML/CSS/JS.

```powershell
cd C:\git\nhlprediction
az account show
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/deploy.ps1
```

Skript zostaví a otestuje backend, zabalí ZIP bez local.settings.json, nasadí
Functions a nahrá štyri statické súbory. Existujúca Microsoft registrácia a tajomstvo
sa zachovajú. Šablóna Bicep sa pri existujúcom úspešnom nasadení automaticky
neprepisuje: ARM aktualizácia appSettings by mohla odstrániť následnú OAuth konfiguráciu.
Pri vedomej zmene infraštruktúry spusti `az deployment group create` a následne
znovu `scripts/configure-azure.ps1`; ak sa odstránilo tajomstvo, vytvorí sa nové.

Windows Java runtime je **21**, rovnako ako v referenčnom projekte. Azure CLI môže
zobraziť varovanie preferujúce `21.0`; toto samotné varovanie neznamená chybu deployu.

Zmenu backendu samostatne nasadíš takto:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/package-backend.ps1
az functionapp deployment source config-zip --resource-group nhlprediction --name nhlpredict-txocp2arkmldw --src .local/functions.zip
```

Iba frontend:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/configure-azure.ps1 -UploadFrontend
```

OAuth používa ročné Microsoft client secret vytvorené pri deploymente. Obnov ho
pred 20. októbrom 2027 cez `scripts/configure-azure.ps1 -RotateSecret` a v Entra
registrácii následne odstráň staré už nepoužívané poverenie. Tajomstvo sa nikdy
nevkladá do webu ani do ChatGPT. Jednorazový dočasný settings súbor skript odstráni
vo finally. `.local/` je ignorovaný a obsahuje lokálne identifikačné údaje nasadenia.

## Dáta a prevádzka

Pôvodný priamy MoneyPuck zber vracal z Azure HTTP 403. Od verzie 2.2 sa dáta
načítajú priamo v Azure z dvoch verejných GitHub zdrojov, bez komponentu na PC:

- `mattkravec/moneypuck-data`, release `data-v2`: tímové zápasy z
  `all_teams_2008_toYYYY.parquet`, záložné `goalies_YYYY.parquet`.
- `adanielmendozaz-glitch/NHL-Totals-Lab-Data`, `data/nhl_feed.json`:
  pôvodné brankárske CSV v `files["YYYY/goalies.csv"]`.

Apache Parquet číta iba potrebné stĺpce po skupinách riadkov. Prenos má limit
96 MiB, dočasný súbor sa odstráni a SHA-256 sa porovná s GitHub digestom, ak
je uvedený. Presmerovania smú viesť iba na GitHub a jeho release asset hosty.
Aktuálne exporty musia byť publikované za posledných 48 hodín; finálne minuloročné
súhrny môžu byť staršie. Cache nezmení pôvodný čas publikácie. Dostupnosť a časy
sú v `enrichment.mirror_sources`. NHL zostáva autoritou pre program, počty
vystúpení, presný čas brankárov a ich štarty. Sezónny súhrn nikdy nenahrádza
date-bounded tímovú históriu. Chyba obnovy vymaže staré automatické hodnoty,
aby sa nevydávali za nové; ručný research, zostavy a kurzy sa zachovajú.

`scripts/sync-data.ps1` zostáva voliteľný nástroj vlastníka. Windows plán sa
neregistroval. Verejný HTTP endpoint na administrátorský import nevzniká.

- `start_day` od verzie 2.1 zaradí do rovnakej fronty `predictions` aj import
  publikovaných MoneyPuck/NHL údajov. Stav `PREFILLING` blokuje súbežné zápisy
  a finalizáciu do skončenia importu. Sieťové sťahovanie prebieha mimo Blob lease.
  `start_day` opraví aj existujúci deň po nasadení novej verzie. Import s varovaním
  možno opakovať (najskôr o minútu); hotový úspešný import sa zbytočne neopakuje.
- `cache/` obsahuje odvodené štatistiky a zdieľané brankárske exporty na najviac
  šesť hodín. `history/YYYY-MM-DD/revision.json` uchová stav pred automatickou
  opravou; verejné API poskytuje aktuálnu publikáciu, nie tieto súkromné zálohy.
- MoneyPuck vstupy sa používajú s atribúciou pre osobný nekomerčný prehľad.
  Rozdiely oproti oficiálnemu počtu NHL zápasov a chyby sťahovania ostávajú
  viditeľné vo `enrichment.warnings`. Nezhoda sezónnej vzorky blokuje stávkové
  filtre cez `source.status=unverified`; číselná projekcia môže byť dostupná.

- `$web` obsahuje iba statické súbory. Web a GET `/api/days` aj `/api/days/{day}`
  sú verejné; zobrazujú NHL údaje, zdroje a predikcie. Neslúžia na ukladanie súkromných dokumentov.
- Kontajner `nhl-private` nie je verejný. Obsahuje `days/YYYY-MM-DD.json`, zámky
  a hashované identifikátory krátkodobých OAuth poverení. Azure runtime používa
  connection string v serverových nastaveniach; frontend ho nepozná.
- Zápisy do dňa používajú Blob lease a revision. Program, tímy a časy zo začiatku
  dňa nemožno prepísať cez `save_game`. Nové dôkazy nesmú meniť existujúce ID.
- Od verzie **3.0.0** je backend dátový: nemá predikčný model ani simulácie.
  Fronta s historickým názvom `predictions` slúži iba na zdrojové importy;
  `importData` staré výpočtové správy ignoruje. Žiadne nové Azure resources.
- `finalize_day(day)` odmietne neúplný checklist a synchrónne označí research
  `READY_FOR_ANALYSIS`. Nemá parameter sims a nič nepočíta ani nequeueuje.
- `get_analysis` sprístupní úplný dataset, dôkazy, dodatočný research a všetky
  ceny. `get_candidates` je kompatibilný alias. Backend nepridáva predikované
  góly, pravdepodobnosti, EV alebo preferencie. Ceny ESTIMATED sú iba trhové
  aproximácie; handicap vyžaduje dve nezávislé kompletné kotviace hranice.
- GPT môže presný nový kurz uložiť cez `save_game` s vlastnými evidence_ids,
  zdrojom a pravidlami; natívne ceny nesmie prepísať. Ďalší doložený kontext
  patrí do `analysis_context`. `post_tips` ukladá vlastné rozhodnutia GPT
  pre celý deň. COMPLETE znamená publikované rozhodnutia a musí sa overiť
  `final_tips.state=PUBLISHED`. Zmena podkladov ich označí NEEDS_REVIEW.
- Pri začatých zápasoch sa nevytvárajú nové predzápasové trhy. Poctivo nedostupné
  vstupy ostávajú označené ako nedostupné; GPT posúdi vhodnosť tipu, nevymýšľa čísla.
- Kontrola každých 15 minút označí zaseknutý import bez aktualizácie dlhšej než 2 hodiny
  ako ERROR. OAuth access token platí hodinu; refresh token sa rotuje a platí 30 dní.
- Nie je pridaný samostatný platený monitoring. Pri diagnostike použi Functions
  host logy a prehľad stavu na webe. Veľkosť úložiska a náklady sleduj v Azure.

## Overenie

Od verzie 2.3 je nasadená aj funkcia `oddsWatch`: UTC cron `0 0,30 * * * *`.
Java vyhodnocuje okno 18:00–05:30 v `Europe/Bratislava`, takže DST sa nerieši
pevným UTC posunom. Zdroj je verejný NHL/DraftKings feed `partner-game/US/now`.
Import odmietne nesprávny NHL deň, publikáciu staršiu než dve hodiny, inú
orientáciu tímov, nesúhlasný gameId/UTC začiatok a začaté zápasy. Páry over/under
a handicapov musia mať zodpovedajúce hranice; 1/X/2 musí obsahovať všetky tri
výsledky. Neznámy `MONEY_LINE_2_WAY_TNB` a chýbajúce ceny sa nedopočítavajú.

`odds_import` v dennom API ukazuje skutočný čas HTTP kontroly, čas publikácie,
deň feedu, počet zápasov a stav WAITING/PARTIAL/READY/ERROR. Pri dobehnutí kurzov
sa obnoví dátový snapshot bez predikčného výpočtu. Súbežný `save_game` chráni revision;
sieťová požiadavka prebieha mimo Blob lease. Nové Azure resources nevznikajú.
Cloudový ChatGPT research o 20:00 nastaví vlastník podľa
`docs/chatgpt-scheduled-prompt.md`; prístup jeho úlohy k MCP musí overiť Run now.

Všetkých 32 oficiálnych log NHL je v `frontend/assets/teams`. Deployment
frontendu kopíruje aj túto zložku. Pri zlyhaní obrázka UI zobrazí tímový kód.

```powershell
mvn -B -f backend/pom.xml clean verify
cd frontend
npm install --ignore-scripts
npm test
```

`scripts/smoke.ps1 -Day YYYY-MM-DD` vykoná živé overenie HTTP/OAuth/MCP a založí
skutočný oficiálny program dňa. Vyžaduje oprávnenie čítať storage key v Azure.
Jednorazový testovací authorization code vloží priamo správca; tým sa overí
PKCE/token/MCP cesta, **nie interaktívne Microsoft prihlásenie**. Všetky testovacie
client/code/access/refresh údaje sa po teste odstránia. Nezískava platené AI dáta.

Testy overujú dátový import, validáciu, overené/odhadované ceny, publikáciu
GPT bez Java reportu, konflikty snapshotov a migráciu starých výberov.
Autoritatívna JSON schéma je súčasťou Java backendu
v `backend/src/main/resources/schema.json`. GitHub Actions overuje Java backend
a frontend; po pushi do `main` nadväzuje automatické nasadenie do Azure.

Živý izolovaný test `scripts/smoke.ps1 -Day YYYY-MM-DD -AiTips` používa deň
2099-12-29, publikuje len syntetické NO_BET a potom fixture aj poverenia vyčistí.
Zdrojový NHL deň iba číta a overí jeho nezmenenosť. Nový plán úlohy používa
get_analysis a finalize_day bez sims; starý uložený prompt treba v ChatGPT nahradiť.
