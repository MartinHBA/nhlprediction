# Nasadenie do Azure

Nasadené 20. októbra 2026 do subscription `Pay-As-You-Go`, región `westeurope`.
Projekt `C:\git\ainoviny` slúžil iba ako čítaná referencia. Nebol upravovaný.

| Súčasť | Hodnota |
| --- | --- |
| Resource group | `nhlprediction` |
| Function App / Java 21 | `nhlpredict-txocp2arkmldw` |
| Windows Consumption plán | `nhlpredict-txocp2arkmldw-plan` |
| Storage / Standard LRS | `nhlpredicttxocp2arkmldw` |
| Static Web App / Free | `nhl-analyzer-online` |
| Web | https://www.nhlanalyzer.online |
| Azure webová adresa | https://orange-mud-0c78ff503.1.azurestaticapps.net |
| MCP | https://nhlpredict-txocp2arkmldw.azurewebsites.net/mcp |
| Health | https://nhlpredict-txocp2arkmldw.azurewebsites.net/health |
| Microsoft app registration | `NHL Prediction MCP` |
| GitHub deployment identity | `nhlpredict-github` (user-assigned managed identity) |

Nie je nasadený Azure OpenAI, platený AI model, Bing Grounding, databáza ani
stále bežiaci Premium plán. ChatGPT vykonáva research v pripojenom chate v rámci
predplatného a jeho limitov. Azure účtuje bežnú spotrebu Functions, Storage a prenosov.
Bezplatné limity závisia aj od ostatnej spotreby v subscription.

## Pripojenie do ChatGPT

Frontend sa od 21. októbra 2026 nasadzuje do Azure Static Web Apps Free ako
**NHL Analyzer Online**. MCP pripojenie má naďalej názov NHL Observatory;
jeho adresa, OAuth registrácia a uložené dáta sa migráciou frontendu nemenia.

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

### Vlastná doména

DNS spravuje Spaceship. Existujúci CNAME `www` treba zmeniť na
`orange-mud-0c78ff503.1.azurestaticapps.net`, bez protokolu a lomky.
Host `www.nhlanalyzer.online` sa potom pripojí k `nhl-analyzer-online`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/complete-domain.ps1
```

Skript overí CNAME, začne overovanie domény a po stave Ready nastaví backendový
WEB_URL na vlastnú doménu. Spravovaný HTTPS certifikát vydá a obnovuje Azure.
Kým DNS/certifikát nie sú pripravené, výsledky odkazujú na funkčnú Azure adresu.
CORS povoľuje vlastnú doménu aj predvolenú Azure adresu. Doména bez www sa
nepripája automaticky; registrátor ju môže presmerovať na www.

### Pipeline

Workflow `.github/workflows/ci-cd.yml` sa spúšťa pri pushi do `main`, pull requeste
alebo manuálne cez Actions → CI/CD → Run workflow. Každý beh má dva build joby:
Java 21 + Maven a Node 22 + frontend testy. Otestovaný Functions ZIP a statické
súbory vrátane všetkých log sa uchovajú ako artefakty sedem dní.

Po úspechu oboch jobov sa z `main` automaticky vykoná deploy. Pull requesty
deployment nespúšťajú. Behy rovnakej vetvy sú serializované, takže starší build
nemôže prepísať novší deployment. Najprv sa nasadí Functions ZIP a overí `/health`,
potom frontend cez `Azure/static-web-apps-deploy@v1` do Static Web Apps a jeho
`release.json` s presným SHA commitu. Výsledok aj odkazy
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
| NHL Analyzer Static Website Deployment Token | iba Static Web App `nhl-analyzer-online`; read a listsecrets |

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
SWA deployment token sa načíta až po OIDC prihlásení, zamaskuje v logoch a odovzdá
deploy akcii. Nie je uložený ako GitHub secret ani v repozitári. Šablóna
`infrastructure/static-web.bicep` vytvára hosting a jeho úzko vymedzenú rolu.

Podklady: [GitHub OIDC pre Azure](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-azure),
[Azure Functions deployment](https://learn.microsoft.com/en-us/azure/azure-functions/functions-how-to-github-actions).

## Ručný deployment cez Azure CLI

Potrebné: Azure CLI s prihlásením, Java 21, Maven, PowerShell a Node 22.
Frontend je obyčajné HTML/CSS/JS; Node spúšťa SWA CLI pri ručnom nasadení.

```powershell
cd C:\git\nhlprediction
az account show
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/deploy.ps1
```

Skript zostaví a otestuje backend, zabalí ZIP bez local.settings.json, nasadí
Functions a nahrá frontend cez SWA CLI 2.1.0. Existujúca Microsoft registrácia a tajomstvo
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
  góly, pravdepodobnosti, EV alebo preferencie. Od verzie 4.0 sú kandidáti iba
  skutočné backendové Oddize QUOTED ceny; aproximácie sú odstránené.
- GPT ceny nehľadá a nemôže ich meniť ani pridávať cez `save_game`. Doložený športový kontext
  patrí do `analysis_context`. `post_tips` ukladá vlastné rozhodnutia GPT
  pre celý deň. COMPLETE znamená publikované rozhodnutia a musí sa overiť
  `final_tips.state=PUBLISHED`. Publikovaný snapshot sa pri automatických
  behoch nemení; ďalší ad-hoc research ho nahradí až úspešnou publikáciou.
- Pri začatých zápasoch sa nevytvárajú nové predzápasové trhy. Poctivo nedostupné
  vstupy ostávajú označené ako nedostupné; GPT posúdi vhodnosť tipu, nevymýšľa čísla.
- Kontrola každých 15 minút označí zaseknutý import bez aktualizácie dlhšej než 2 hodiny
  ako ERROR. OAuth access token platí hodinu; refresh token sa rotuje a platí 30 dní.
- Nie je pridaný samostatný platený monitoring. Pri diagnostike použi Functions
  host logy a prehľad stavu na webe. Veľkosť úložiska a náklady sleduj v Azure.

## Overenie

Od verzie **4.0.0** zbiera kurzy samostatná funkcia `oddizeSnapshot`:
UTC cron `0 45 * * * *`, lokálny filter **19:45 Europe/Bratislava** vrátane DST.
Backend vyžaduje app setting **ODDIZE_API_KEY**; kľúč je iba v serverovom HTTP
headeri, nie v dátach, odpovediach, frontende ani diagnostických výstupoch.

`oddsWatch` má UTC cron `0 0,30 * * * *` a lokálne okno **17:45–08:00**.
Od verzie 4.2 má aj ľahké okno 12:45–17:45: prvý beh o 13:00 pripraví
oficiálny program so stavom enrichment=SCHEDULED. Od verzie 4.3 zároveň
pripraví samostatný denný Elo snapshot z NHL Stats API (všetci 1500 od začiatku
aktuálnej sezóny, K=20, iba finálne výsledky pred týmto NHL dňom). Cache
`cache/elo/v1/YYYY-MM-DD.json` uchová presné ratingy aj použité výsledky;
`day.elo` obsahuje menší prehľad pre karty. Úspešný snapshot sa neobnovuje
počas noci ani pri opakovanom MCP behu a nemení revision, research či tipy.
Výpadok Elo neblokuje karty ani pokročilý import; ďalší timer ho zopakuje.
O 18:00 zaradí pokročilé
štatistiky; po publikácii obnovuje iba skóre. Explicitný MCP start_day môže
štatistiky vyžiadať aj skôr; automatický programový beh to nerobí.
Predstih chráni tesne skorý trigger 18:00. Dokončený Oddize katalóg už neobnovuje.
Pri nedokončenom importe môže zaradiť retry; úspešné jednotlivé API odpovede
z privátnej cache `cache/oddize/v1/snapshot/YYYY-MM-DD/...` sa znovu neplatia.
Nie je potrebný nový Azure resource. Fronta `predictions` spracúva aj kind=oddize.
Sieť prebieha mimo denného Blob lease; výsledok sa spojí s najnovším researchom.

`odds_import` obsahuje SCHEDULED/QUEUED/RUNNING/READY/PARTIAL/ERROR,
čas plánovaného/uskutočneného zberu, čas ceny od poskytovateľa, počty ponúk,
pokrytie a náklady aktuálneho pokusu. Pravidlá `regulation` aj `official_final`
sú povolené iba s overením bookmakera. Neznáme pravidlá a neaktuálne alebo
nekonzistentné ponuky sú blokované, nie preoznačené. Pásmo je 1,18–1,35.

Cloudový research o 20:00 nastaví vlastník podľa
[denného promptu](chatgpt-scheduled-prompt.md); po deployi obnov MCP tools a
vlož nový text do úlohy. Azure prompt v účte automaticky nemení. GPT dopĺňa
športový research, ale ceny ani bookmakerove pravidlá už nevyhľadáva.
Diagnostika `/api/diagnostics/oddize?mode=catalogue&day=YYYY-MM-DD` vyžaduje
master key; používa oddelenú preview cache a NEMENÍ denné dáta ani publikáciu.
Spotrebuje najviac 1 + 4×počet zápasov + 2 kredity pri prvom behu.

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

Testy overujú dátový import, validáciu, backendové overené ceny, publikáciu
GPT bez Java reportu, konflikty snapshotov a migráciu starých výberov.
Autoritatívna JSON schéma je súčasťou Java backendu
v `backend/src/main/resources/schema.json`. GitHub Actions overuje Java backend
a frontend; po pushi do `main` nadväzuje automatické nasadenie do Azure.

Živý izolovaný test `scripts/smoke.ps1 -Day YYYY-MM-DD -AiTips` používa deň
2099-12-29, publikuje len syntetické NO_BET a potom fixture aj poverenia vyčistí.
Zdrojový NHL deň iba číta a overí jeho nezmenenosť. Nový plán úlohy používa
get_analysis a finalize_day bez sims; starý uložený prompt treba v ChatGPT nahradiť.

## Publikácie a výsledky od 3.1

Po post_tips zostáva dataset_snapshot pri publikácii nemenný. Automatický timer
na publikovaný deň už neobnovuje štatistiky/kurzy. Sťahuje iba NHL /v1/score/{day}
a ukladá scoreboard bez zmeny analytickej revision. Okno končí vrátane 08:00
Europe/Bratislava a ráno cieli na predošlý hrací deň. /api/days/{day}/live vracia
iba stav publikácie, skóre a výsledky tipov. Vyhodnotenie používa presnú hranicu
a score_basis, rozlišuje OT/SO a 60 minút; neznámy výsledok nesmie byť LOSS/WIN.

Nový MCP start_day otvorí ad-hoc pracovný import; existujúci verejný snapshot
zostáva až do ďalšieho post_tips. Už začaté zápasy si zachovajú skorší TIP.
Historické podklady obnovuje RepairPublishedDay iba pri presnej zhode fingerprintu
s publication analysis_id v čase publikácie. --apply vytvorí súkromnú zálohu,
uloží snapshot a skóre a overí nezmenenosť rozhodnutí GPT.

## Postupné načítanie a SV% od 3.2

GET /api/days/{day}/cards načíta prehľad bez štatistík, dôkazov a skrytých
zdôvodnení. Po vykreslení kariet browser na pozadí načíta /api/days/{day};
zhodu podkladov kontroluje data_snapshot_id. Obsah rozbaľovačov sa vytvára až
pri otvorení. Archív vracia iba malý zoznam dní. Verejné čítanie nič nezapisuje.

Brankárske save_pct_current/prior sú zlomky 0–1 odvodené z NHL game logov pred
hracím dňom. Sú súčasťou nového zberu a schémy, ale staré nemenné publikácie
ich nemusia obsahovať. Verzia kolektora 2.4 obnoví cache pri ďalšom importe;
časovač po publikácii naďalej sťahuje iba skóre.
