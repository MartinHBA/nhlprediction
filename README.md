# NHL Observatory — Azure dáta + predikčná analýza GPT

Azure Java backend pripravuje analytické dáta a kurzy. ChatGPT cez MCP doplní
research, porovná možnosti podľa stratégie a uloží vlastný finálny výber.
**Backend nemá predikčný model, simulácie, očakávané skóre, pravdepodobnosti,
EV ani automatický výber tipov. Nevolá platené LLM API.**

**Web:** https://nhlpredicttxocp2arkmldw.z6.web.core.windows.net

**MCP:** https://nhlpredict-txocp2arkmldw.azurewebsites.net/mcp

**Azure resource group:** `nhlprediction` · `westeurope`.

```text
18:00 Europe/Bratislava → Azure: program, štatistiky, prvá kontrola kurzov
18:30, 19:00, ... 08:00 → Azure: kurzy pred publikáciou, potom už iba skóre
20:00 → ChatGPT Cloud úloha: MCP dáta + webový research
  → save_game: zostavy, brankári, kontext, prípadne presné nové kurzy
  → check_day + finalize_day: iba validácia a uzavretie researchu
  → get_analysis: spoločný aktuálny dátový snapshot
  → analytické rozhodnutie GPT → post_tips → web
```

Denný flow nevyžaduje zapnutý PC. Azure nikdy nespúšťa ChatGPT research;
naplánovaná úloha používa tvoj ChatGPT účet a jeho limity.

## Pripojenie a prompt

Pripoj NHL Observatory ako vlastný MCP server s vyššie uvedenou URL a OAuth.
Prihlás sa Microsoft účtom vlastníka Azure aplikácie. OpenAI API kľúč ani
Microsoft client secret sa do ChatGPT nezadávajú.

Po aktualizácii na **3.1.0** obnov nástroje pripojenia a over `get_analysis`.
Použi [denný prompt so stratégiou](docs/chatgpt-scheduled-prompt.md); do úlohy
vlož iba jeho blok `text`. Azure deployment uložený prompt úlohy nemení.
Najprv over skutočný skúšobný beh s prístupom k MCP, researchu a zápisom.

## MCP nástroje

| Nástroj | Úloha |
| --- | --- |
| `get_instructions` | Aktuálny postup a pravidlá zberu a publikácie |
| `get_schema` | Autoritatívna schéma vrátane kontextu a dokladovaných kurzov |
| `start_day` | Úplný oficiálny program a asynchrónny import dát |
| `get_game` | Uložený zápas, natívne chránené polia, dôkazy a revision |
| `save_game` | Research, dodatočný kontext a nové overené ponuky s dôkazmi |
| `check_day` | Kontrola všetkých polí, kategórií trhov a dôkazov |
| `finalize_day` | Synchrónna validácia a označenie researchu za dokončený, bez `sims` |
| `get_analysis` | Analytický dataset a všetky dostupné ceny; GPT posúdi a rozhodne |
| `get_candidates` | Kompatibilný alias dátového `get_analysis` |
| `post_tips` | Celý deň rozhodnutí GPT: TIP/NO_BET, dôvody, alternatívy, riziká, confidence |
| `get_results` | Stav zberu a publikácie, dostupnosť dát/kurzov a odkaz na web |

`finalize_day` vracia `READY_FOR_ANALYSIS`; neexistuje fronta výpočtu ani čakanie
na model. `post_tips` uloží rozhodnutia a deň prejde na `COMPLETE`.
Rozhodujúce overenie je **`final_tips.state=PUBLISHED`**, aj pri všetkých NO_BET.
Počas `PREFILLING` sa čaká len na zdrojový import.

## Dáta pre analytika

Každý nový zápas má 86 kontrolovaných polí pre oba tímy: minulé/aktuálne počty zápasov,
points%, 5v5 xGF/xGA za 60 minút, PP/PK xG, disciplínu, strely, high-danger metriky,
meno a potvrdenie brankára, štarty, GSAx/60 a SV% za obe sezóny, absencie, návraty, scratches, formácie,
obranné páry, PP jednotky, oddych a časové pásma. Každý údaj má kontroly a dôkazy.

Povinné polia neurčujú požiadavku na Java predikciu. `unavailable` s konkrétnym
dôvodom uzavrie poctivú kontrolu, ale nezvýši počet dostupných údajov. GPT posúdi,
či medzera bráni konkrétnemu tipu. Nepreverené `not_checked` a chybné dôkazy
blokujú uzavretie researchu a finálnu publikáciu.

Dodatočné doložené informácie (napr. posledné zápasy, sila súperov či oddych
brankára) možno uložiť do `analysis_context: [{topic, summary, evidence_ids}]`.
Nedopĺňajú sa vymyslené číselné vplyvy. Schéma je v
`backend/src/main/resources/schema.json` a cez `get_schema`.

### Automatické zdroje

| Zdroj | Použitie |
| --- | --- |
| NHL API | Oficiálny program, presné ID/tímy/UTC časy, game logy, štarty a SV% brankárov |
| [mattkravec/moneypuck-data](https://github.com/mattkravec/moneypuck-data/releases/tag/data-v2) | Parquet história tímových zápasov a záložné brankárske súhrny |
| [NHL-Totals-Lab-Data](https://github.com/adanielmendozaz-glitch/NHL-Totals-Lab-Data) | Brankárske exporty v JSON feede |
| NHL/DraftKings partner feed | Skutočne publikované predzápasové kurzy |

MoneyPuck mirrory nahrádzajú priamy prístup, ktorý z Azure vracal HTTP 403.
Aktuálne exporty musia mať publikáciu najviac 48 hodín starú; cache najviac
šesť hodín. Uzavretý minuloročný súhrn môže byť starší. Budúce záznamy,
nesprávna sezóna a nezhodné vzorky sa nepoužijú ako overené údaje.

xG/60 používa správny situačný čas (5v5, 5v4, 4v5). Štarty nie sú vystúpenia;
GSAx/60 sa odvodzuje z overenej histórie a času na ľade. SV% je podiel súčtu
zákrokov a striel proti brankárovi zo všetkých vystúpení pred NHL dňom vrátane
striedaní, nie priemer zápasových percent. V API je `save_pct_current/prior`
zlomok 0–1; web ho zobrazí ako percento. Pri chýbajúcich dátach alebo nulovom
počte striel zostáva N/A. Historické publikované snapshoty sa spätne nedopĺňajú.
Tieto prepočty vstupných
štatistík sú opis historických dát, nie predikčný model. Oddych = rozdiel dátumov
mínus jeden; cestovanie je proxy rozdielu UTC posunov arén, nie sledovanie letu.
Pri zmene mena brankára sa história overí pre správnu osobu. Probable sa tým
nikdy automaticky nemení na confirmed. Zlyhanie zdroja nevytvorí nuly.

Natívne polia/kurzy sú chránené pred prepísaním. JSON `5` a `5.0` sú rovnocenné,
zaokrúhlenie hodnoty sa odmietne. Pri konflikte revision sa znovu načíta zápas.
Nové overenie má nové evidence ID; existujúci dôkaz sa spätne nemení.

## Kurzy a ich aproximácie

`oddsWatch` sa spúšťa každú celú a pol hodinu UTC, časové okno vyhodnocuje
v **Europe/Bratislava 18:00–08:00** vrátane DST. Založí deň, načíta štatistiky
alebo obnoví NHL feed. Žiadny predikčný výpočet nespúšťa.

Feed `https://api-web.nhle.com/v1/partner-game/US/now` sa páruje podľa presného
NHL dňa, ID, tímov a UTC začiatku. Publikácia musí byť najviac dve hodiny stará.
Čas stiahnutia neobnovuje čas publikácie. Podporované sú moneyline vrátane OT/SO,
1/X/2 po 60 minútach, celkové góly a handicap. Začaté zápasy sa vyradia.
`odds_import` rozlišuje WAITING/PARTIAL/READY/ERROR/NO_UPCOMING_GAMES.

`get_analysis` obsahuje všetky dostupné ceny vrátane tých mimo preferovaného
pásma; `candidates` sú iba **1,20–1,35 vrátane**. Žiadne poradie neznamená
odporúčanie. `selectable` kontroluje cenu, aktuálnosť a pravidlá; neposudzuje
predikčnú výhodu ani kvalitu tímu. **Tímový over 2,5 je pozastavený.**

- **QUOTED:** skutočne overená ponuka so zdrojom, časom a pravidlami trhu.
- **ESTIMATED total:** cenová krivka z čerstvej kompletnej dvojice over/under
  rovnakého bookmakera, publikácie a pravidiel. Po odstránení marže sa prispôsobí
  Poissonov tvar cenovej krivky a pre ďalšie polgólové hranice sa predpokladá
  rovnaká marža. Ide iba o aproximáciu ceny z trhu, nie model tímov či skóre.
- **ESTIMATED handicap:** iba interpolácia medzi **dvoma nezávislými kompletnými
  hranicami** tej istej publikácie/pravidiel; bez simulovaného rozdielu skóre.
  Jedna protichodná dvojica nestačí, vtedy backend odhad nevytvorí.

Presný kurz môže GPT dohľadať pri zvažovaní tipu a pridať cez `save_game` do
`markets` s vlastným ID, `evidence_ids`, overeným source a rules_url. Odhady sa
ako overené ponuky neukladajú. Dodatočný reálny kurz môže pokryť aj kategóriu,
ktorú NHL feed označil za nedostupnú; natívne ponuky zostávajú chránené.

## Finálne výbery a web

GPT načíta kompletné dáta, podľa stratégie porovná trhy, zvolí najviac jeden TIP
alebo NO_BET na zápas a cez `post_tips` publikuje celý deň. Odovzdá aktuálnu
`revision`, `analysis_id`, quote `candidate_id`, confidence, rationale, comparison,
risks a evidence_ids. Backend iba validuje a uloží; nevytvára protitip ani EV.
Confidence je analytické hodnotenie GPT, nie vypočítaná úspešnosť.

Pri post_tips sa uloží nemenný dataset aj kurzy a dôkazy použité GPT. Timer
potom pre tento deň nenačítava štatistiky ani kurzy, iba oficiálne skóre cez
NHL score feed. Stav a skóre sú mimo analytickej revision, takže nekolidujú
s researchom. Kontroly bežia do 08:00 Europe/Bratislava vrátane tohto behu.
Ráno sa sleduje predchádzajúci hrací deň aj po polnoci v New Yorku.

Nový MCP start_day výslovne otvorí ad-hoc obnovu ešte nezačatých zápasov.
Opakované volanie počas tej istej session import neduplikuje. Pracovné dáta
môže GPT doplniť, ale web zobrazuje predchádzajúci snapshot až po novom post_tips.
Pri už začatých zápasoch sa zachovajú pôvodné tipy aj ich podklady; nový NO_BET
ich nevymaže. História starších publikácií zostáva v history/tips.

Tip ostane viditeľný aj počas hry a po finále. Backend porovná uložený trh
s oficiálnym výsledkom: WIN/LOSS/PUSH; nejde o predikčný model. Rozlišuje 60 minút
od výsledku vrátane OT/SO. Pri neznámom podklade je UNAVAILABLE, nie vymyslené
vyhodnotenie. NO_BET zápasy ukážu iba skóre. Počítadlo vyhodnotených GPT zahŕňa
všetky uložené rozhodnutia aj po začiatku a konci zápasov.

Verejné /api/days/{day}/cards vracia malý prehľad kariet, tipov a skóre.
Browser ho zobrazí ako prvý; až po vykreslení načíta kompletné údaje z
/api/days/{day}. Tie sa musia zhodovať podľa data_snapshot_id. Rozbalené detaily
sa vytvoria až pri otvorení; chyba detailov neodstráni karty a umožní opakovanie.
Verejné /api/days/{day} vracia zmrazené štatistiky s oddeleným scoreboard.
Browser po publikovaní každých päť minút načíta iba /api/days/{day}/live;
štatistiky znovu stiahne až po novej publikácii alebo ručnom obnovení.
Po konečnom skóre všetkých zápasov tiché kontroly prestanú. Skryté karty
sa preskakujú a rozbalené detaily zostávajú otvorené.

Web zachováva oficiálne logá všetkých 32 tímov. Karta má sekcie Dáta
a Doplňujúci research. Dáta majú poradie **Brankár, Zostava, Oddych a cestovanie,
Aktuálna sezóna, Minulá sezóna**. Metriky sú vedľa seba za oba tímy;
skratky tímov sú počas scrollovania v rozbalených štatistikách pripnuté.
Zdrojové poznámky fungujú na hover/focus/tap; samostatný zoznam URL nie je v karte.

Staré publikácie bez uloženého datasetu možno obnoviť z history revízie,
iba ak jej fingerprint presne sedí s analysis_id pri published_at. Nespárované
aktuálne štatistiky sa nevydávajú za pôvodné. Owner utility RepairPublishedDay
robí najprv read-only kontrolu; --apply uloží presnú obnovu a oficiálne skóre,
s predchádzajúcou zálohou a overením, že rozhodnutia GPT zostali nezmenené.

## Vývoj a deployment

Java 21 + Maven a Node 22:

```powershell
mvn -B -f backend/pom.xml clean verify
cd frontend
npm ci --ignore-scripts
npm test
```

Push do `main` spustí GitHub CI/CD: testy, balíky, OIDC prihlásenie a deploy
Java Functions aj statického webu. Pull requesty iba testujú. Azure nepotrebuje
nové resources ani platené AI API. `/health` overuje nasadenú verziu a webový
`release.json` presný commit. Frontend nepotrebuje build.

[Deployment, autentifikácia a prevádzka](docs/azure-deployment.md) opisujú
existujúce Azure zdroje a oprávnenia. Priamy ručný deploy umožňuje
`scripts/deploy.ps1`; bežné zmeny idú cez CI/CD.

Voliteľné `scripts/sync-data.ps1` slúži iba na ručný import vlastníka. Denný zber
je v Azure. `-Finalize` len validuje a uzavrie research. Registrácia lokálnej
úlohy sa pri deploymentoch nespúšťa a nie je súčasťou denného cloudového flow.
