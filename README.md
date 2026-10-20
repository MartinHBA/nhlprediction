# NHL Observatory — Azure + Java + ChatGPT MCP

Denný NHL research v tvojom ChatGPT predplatnom, výpočty v Java 21 Azure Functions
a statický web na prezeranie výsledkov. Backend **nevolá platené LLM API**.

**Web:** https://nhlpredicttxocp2arkmldw.z6.web.core.windows.net

**MCP:** https://nhlpredict-txocp2arkmldw.azurewebsites.net/mcp

**Azure resource group:** `nhlprediction` · `westeurope`.

Zber od verzie **2.3** používa NHL feed kurzov a dva verejné GitHub mirrory MoneyPuck dát priamo
z Azure. Tvoj PC nie je súčasťou denného riešenia. Pôvodný MoneyPuck server
pri priamom zbere z Azure vracal HTTP 403; nové zdroje sú opísané nižšie.

```text
18:00 Europe/Bratislava → Azure: program, štatistiky, prvá kontrola kurzov
18:30, 19:00, ... 05:30 → Azure: kontrola NHL kurzov každých 30 minút
20:00 → tvoja ChatGPT Cloud úloha: MCP, zostavy/brankári/research, save_game
  → check_day + finalize_day → Java simulácie → get_candidates
  → finálne zhodnotenie ChatGPT → post_tips → web
  → ak kurzy prídu neskôr, Azure obnoví výpočet dokončeného researchu
```

## Prvý krok: pripoj ChatGPT

Vo webovom ChatGPT otvor **Plugins → Add custom MCP server**, pomenuj ho
**NHL Observatory**, vlož MCP URL a použi automatické OAuth discovery.
Prihlás sa svojím Microsoft účtom z Azure. Prístup k zápisu má iba nakonfigurovaný
vlastník. OpenAI API kľúč ani Microsoft client secret sa do ChatGPT nezadávajú.

Potom v chate s pluginom napíš:

> Použi NHL Observatory. Načítaj pokyny, spracuj celý dnešný NHL program podľa
> checklistu, dokonči výpočet, zhodnoť kandidátov cez get_candidates a publikuj
> finálne rozhodnutia cez post_tips. Over final_tips.state=PUBLISHED.

Na pravidelný večerný beh je pripravené
[zadanie pre naplánovanú úlohu](docs/chatgpt-scheduled-prompt.md).
Inštalácia MCP do tvojho účtu a plánovaná úloha sa nenastavia samotným Azure
deploymentom. Najprv treba overiť manuálny beh, prístup k webovému researchu,
potvrdenia zápisov a dostupnosť nástrojov v tvojej plánovanej úlohe.

## Čo je nasadené

- Java 21 Function App na Windows Consumption; HTTP/MCP, queue worker a údržba.
- Jedno Standard LRS úložisko pre súkromné pracovné dáta, frontu a statický web.
- OAuth s Microsoft prihlásením, PKCE, jednorazovými kódmi a rotáciou refresh tokenov.
- Verejný web s dennými zápasmi, modelovými gólmi, pravdepodobnosťami, výbermi ChatGPT,
  rozbaliteľnými kontrolami údajov, časom aktualizácie a archívom. Stavy neúplného alebo zlyhaného zberu
  sa zobrazujú otvorene. Vybraný dátum sa nenahrádza staršími výsledkami.

Azure OpenAI ani platený AI model sa nevytvára. ChatGPT research čerpá tvoje
predplatné a jeho limity. Azure hosting a prípadné externé platené športové dáta
majú vlastné náklady.

## MCP nástroje

| Nástroj | Úloha |
| --- | --- |
| `get_instructions` | Pôvodný zberný prompt a postup pre MCP |
| `get_schema` | Autoritatívna JSON schéma |
| `start_day` | Oficiálny program + automatický import štatistík; opraví aj starší neúplný deň |
| `get_game` | Šablóna alebo uložený zápas, dôkazy a revision |
| `save_game` | Jeden celý zápas + dôkazy; kontrola schémy a revision |
| `check_day` | Všetky nepreverené polia, trhy a chyby dôkazov |
| `finalize_day` | Povinné kontroly a zaradenie simulácie do fronty |
| `get_results` | Stav výpočtu, výsledky a odkaz na web |
| `get_candidates` | Finálny research a všetky kandidátske typy v pásme 1,20–1,35; snapshot pre rozhodnutie AI |
| `post_tips` | Atomické uloženie TIP/NO_BET pre každý zápas, zdôvodnenia, porovnania, dôkazov a confidence |

Program používa kalendárny deň NHL **America/New_York**. Web zobrazuje časy
v **Europe/Bratislava**. Kontroly zdrojov sa ukladajú v UTC.

## Automatické dátové vstupy (v2.3)

Backend načíta MoneyPuck dáta z verejných kópií a oficiálny NHL program
a brankárske game logy. Po `start_day` počkaj cez
`get_results`, kým skončí stav `PREFILLING`. Následné `get_game` vráti vyplnené
údaje aj `backend_prefilled_fields`, ktoré sa pri ukladaní zachovajú presne.
Číselné zápisy ako `5` a `5.0` sú rovnocenné; backend ponechá svoj pôvodný
zápis. Zmenu hodnoty vrátane zaokrúhlenia odmietne.
Natívne kontroly a ich dôkazy sa v MCP šablóne neopakujú; backend ich pri ukladaní
obnoví. `get_results` vracia stav a výpočet bez opakovania celého research datasetu.
ChatGPT vyhľadáva zostávajúce údaje, zostavy, potvrdenia brankárov a kurzy.
História sa doplní aj pri uložení novo pomenovaného brankára; jeho potvrdenie
na konkrétny zápas sa tým nemení.

| Zdroj | Použitie |
| --- | --- |
| [mattkravec/moneypuck-data](https://github.com/mattkravec/moneypuck-data/releases/tag/data-v2) | Parquet história tímových zápasov; záložné sezónne brankárske súhrny |
| [NHL-Totals-Lab-Data](https://github.com/adanielmendozaz-glitch/NHL-Totals-Lab-Data) | Pôvodné brankárske CSV v JSON feede, aktuálna aj minulá sezóna |

Aktuálne dáta musia mať čas publikácie najviac **48 hodín** starý; budúce časy
sa odmietnu. Uzavretý minuloročný Parquet súhrn môže byť starší. Cache platí
najviac šesť hodín a neobnovuje čas publikácie. `enrichment.mirror_sources`
ukazuje použité URL, časy publikácie a dostupnosť. Pri chybe primárneho
brankárskeho feedu sa skúsi Parquet; pri nezhode s NHL aj alternatívny súhrn.
Nedostupná história ostane neprijatá. Zápasová história tímov sa nenahrádza
nedatovaným sezónnym súhrnom. Dostupnosť cudzích mirrorov nie je garantovaná.

- Minulá/aktuálna sezóna sú oddelené; použijú sa iba regular-season zápasy
  s dátumom **pred zvoleným NHL dňom**. Sezóna sa určuje od júla, vrátane
  prípadného septembrového začiatku súťaže.
- xG/60 používa skutočný čas danej situácie: 5v5, 5v4 a 4v5 majú vlastný
  menovateľ v sekundách. Počet trestov zahŕňa minor/major tresty; ide o proxy
  vynútených presiloviek, nie o počet presilovkových príležitostí.
- Štarty brankára pochádzajú z NHL `gamesStarted`; počet vystúpení ich nenahrádza.
  GSAx/60 = `(xGoals − goals) × 3600 / icetime`. Spoločný sezónny export sa použije
  iba pri zhode počtu vystúpení a presného času s NHL a bez zápasov po dátumovom
  limite. Inak sa použije filtrovaná história jednotlivých zápasov.
- Oddych = rozdiel dátumov − 1 (back-to-back = 0). Cestovanie je uvedený
  rozdiel UTC posunov predchádzajúcej a aktuálnej arény; skutočný let sa nesleduje.
- Každé číslo má dôkaz a poznámku so vzorcom/vzorkou. Nezhoda vzorky MoneyPuck
  s NHL sa ukáže ako upozornenie a vstup ostáva `unverified` pre stávkové filtre.
  Zlyhanie zdroja nevytvára nuly ani falošné potvrdenie dostupnosti.
- Sťahovanie má obmedzené tempo, rešpektuje HTTP 429/Retry-After a úspešné dáta
  dočasne ukladá do existujúceho Blob Storage. Nové Azure zdroje ani AI volania
  nepribúdajú. Pred opravou dňa zostane jeho predošlá revízia v súkromnej histórii.

Web rozlišuje **dostupné údaje**, **kľúčové vstupy modelu** a **uzavreté kontroly**.
Uzavretá kontrola `unavailable` nezvyšuje dostupnosť dát. MoneyPuck údaje sú
použité pre osobný nekomerčný prehľad s uvedením zdroja na webe.

## NHL kurzy a večerný plán

`oddsWatch` beží každú celú a pol hodinu UTC; v kóde kontroluje časové okno
**18:00–05:30 Europe/Bratislava**, vrátane letného/zimného času. Pri prvom behu
založí oficiálny program a zaradí prefill. Ďalšie behy načítajú
`https://api-web.nhle.com/v1/partner-game/US/now` a po dokončenom researchi
automaticky zaradia nový Java výpočet. ChatGPT research robí tvoja úloha o 20:00.

Podporované sú skutočne publikované DraftKings kurzy: moneyline vrátane OT/SO,
1/X/2 po 60 minútach, celkové góly over/under a handicap. Dvojtipy, tímové totals
sa nepovažujú za dostupné bez skutočnej ponuky. Neznámy typ `TNB` sa ignoruje.
Páruje sa presný NHL gameId, tímy a UTC začiatok. Feed musí pokrývať požadovaný
NHL deň a byť najviac dve hodiny starý. Čas stiahnutia neobnovuje čas publikácie.
Začaté zápasy sa vyradia; chýbajúca protistrana alebo konfliktný kurz sa nepoužije.

`odds_import` odlišuje `WAITING`, `PARTIAL`, `READY`, `ERROR` a `NO_UPCOMING_GAMES`.
Web zobrazuje počet zápasov s kurzami a čas kontroly aj publikácie zdroja.
Výpadok alebo starý feed odstráni predošlé automatické kurzy. Natívne kurzy sú
pri `save_game` chránené proti prepísaniu; konflikt revision vyžaduje nové `get_game`.

Pre chýbajúce alternatívne celkové góly 2,5–10,5 sa interne počítajú **označené odhady**.
Z čerstvej kompletnej dvojice over/under
na rovnakej hranici, s rovnakým zdrojom, časom a pravidlami sa odstráni marža
normalizáciou recipročných kurzov. Poissonov priemer sa prispôsobí tejto trhovej
pravdepodobnosti; na ďalších hraniciach sa predpokladá rovnaký overround.
Je to aproximácia tvaru rozdelenia aj cien; ponuka bookmakera sa tým neoveruje.
Pri celom zápase sa Poissonovo rozdelenie približuje aj súčtu vrátane OT/SO.
Príliš extrémne hranice, ktorým by vychádzal kurz ≤ 1, cenu odhadu nedostanú.
Náš model sa na tieto trhové ceny neprekalibruje. Zo svojich simulácií nezávisle
uvádza pravdepodobnosť, férový kurz a minimálny skutočný kurz pre kladné EV aj
v najhoršom citlivostnom scenári. Pri alternatívnych handicapoch ±0,5 až ±4,5
sa rozdelenie rozdielu skóre zo simulácií exponenciálne
nakloní tak, aby zodpovedalo pravdepodobnosti aktuálnej dvojice handicapov po
odstránení marže. Z tvaru tohto rozdelenia a rovnakej marže sa odhadnú iné hranice.
Ide o dodatočný predpoklad ceny, nie o nezávislú validáciu modelu.
Odhady nevstupujú do skutočných `markets` ani počítadiel pokrytia. Java pripravuje
`report.games[].candidates`: všetky možnosti v pásme **1,20–1,35 vrátane**,
vrátane možností so záporným modelovým EV. Každá má stabilné `candidate_id`,
modelové pravdepodobnosti/EV, informáciu o aproximácii a `selectable` s dôvodmi
prípadného blokovania. `model_preference` ostáva iba mechanickým poradím modelu.

Finálny výber robí ChatGPT v tom istom rune po zbere dát a výpočte. Cez
`get_candidates(day, game_id)` načíta porovnanie a uložený research, vyhodnotí
víťaza, 1/X/2, handicap aj over/under a cez `post_tips` zapíše **pre každý zápas**
jedno rozhodnutie TIP alebo NO_BET. Odovzdá `analysis_id`, aktuálnu `revision`,
`candidate_id` (pri NO_BET null), confidence, rationale, comparison, risks a
platné evidence_ids. Kurzy a pravdepodobnosti neposiela; backend ich preberá
z existujúceho kandidáta. Neoverené/neaktuálne kurzy, pozastavené trhy a neúplný
checklist nemožno publikovať ako TIP. Pri modelovom nesúhlase alebo neistých
podkladoch backend obmedzí confidence na LOW a vyžaduje pomenované riziká.
Odhad kurzu sám osebe dovoľuje najviac MODERATE; nejde o kalibrovanú úspešnosť.

Výbery sú uložené mimo Java reportu v `final_tips`. Stav COMPLETE označuje iba
výpočet, `final_tips.state=PUBLISHED` dokončenú analýzu AI (aj keď sú všetky
rozhodnutia NO_BET). Web čaká na túto publikáciu; nezobrazuje mechanický výber
ako rozhodnutie ChatGPT. Pri tipe sú kurz/odhad, P(model), confidence a rozbaliteľné
zdôvodnenie s porovnaním alternatív a rizikami. Zoznam trhov sa nezobrazuje.

Snapshot je viazaný na skutočný obsah dát a politiku modelu. Zmena ceny, brankára,
zostavy alebo zostarnutie podkladov označí uložený výber `NEEDS_REVIEW`; automatický
prepočet ho neprepíše. Nezmenený poll ani obnovenie časových pečiatok s rovnakými
hodnotami výber samo nezneplatní. Začatý zápas má uzavretý predzápasový výber.
Konfliktný snapshot/revision sa odmietne a rovnaký opakovaný post je idempotentný.
Staršie výbery majú súkromnú históriu v `history/tips/`.

Po deployi obnov metadata pripojenia NHL Observatory cez **Plugins → Refresh**,
aby ChatGPT videl `get_candidates` a `post_tips`, a použi aktuálny prompt z
`docs/chatgpt-scheduled-prompt.md`. Azure nemení uložený prompt ChatGPT úlohy.
Oficiálny postup: https://developers.openai.com/plugins/deploy/connect-chatgpt#refresh-metadata

Podklad k marži: https://support.pinnacle.bet/hc/en-us/articles/44854189442065-What-is-a-betting-margin

Web obsahuje oficiálne SVG logá všetkých 32 tímov v `frontend/assets/teams`;
pri chybe načítania zostane čitateľná tímová skratka.
Pri spracovaní alebo nadchádzajúcich zápasoch web potichu kontroluje údaje každých
5 minút. V skrytej karte kontrolu preskočí. Nezmenené karty neprekresľuje,
zachová rozbalené detaily a pri výpadku pripojenia ponechá posledné výsledky.
Pod „Kontroly a trhy“ má každý zápas samostatne rozbaliteľné kľúčové vstupy
a dostupné polia, rozdelené podľa oblasti. Každá metrika má v jednom riadku
vľavo hodnotu a stav prvého tímu a vpravo druhého, rovnako ako v hlavičke zápasu;
poznámka sa ukáže po podržaní myši nad metrikou, pri fokuse klávesnicou alebo
ťuknutí na názov. Zoznam zdrojových odkazov sa v detaile zápasu nezobrazuje.

## Zachované analytické pravidlá

Každý zápas má 82 kontrolovaných polí a 10 kategórií trhov. `unavailable` znamená
poctivo hľadaný a nedostupný údaj s dôvodom a dôkazom; `not_checked` blokuje
dokončenie checklistu. Chýbajúce kritické vstupy blokujú model daného zápasu.

Java prenáša pôvodné miešanie minulej/aktuálnej sezóny, brankársku regresiu,
5v5 a špeciálne tímy, oddych/cestovanie, 6v5 a OT/SO heuristiku, citlivostné
scenáre, PUSH a samostatné vyhodnotenie 60 minút oproti celému zápasu.
Zachované sú kontroly aktuálnosti, pravidiel vyhodnotenia aj výber najviac
jedného trhu na zápas. **Tímový over 2,5 je pozastavený.**

Model je heuristický a historicky nekalibrovaný. Predikcia nie je dôkaz úspešnosti.
Produkčný výpočet používa **1 000 simulácií na zápas** a 1 000 pre každý
citlivostný scenár. Rovnaký limit platí pre MCP aj automatické obnovenie po
importe kurzov. Staršie požiadavky so `sims=100000` sa prijmú, ale znížia na 1 000.
`NO_BET` je normálny výsledok. Výpočty nezačínajú vymýšľať údaje, keď zdroj chýba.
Java beh je s rovnakým seedom reprodukovateľný. Projekcie a checklist sa
overujú aj voči uloženým referenčným výsledkom.

## Vývoj a deployment

Java 21 + Maven:

```powershell
mvn -B -f backend/pom.xml verify
```

Autoritatívna JSON schéma je v `backend/src/main/resources/schema.json`.
GitHub Actions po pushi alebo pull requeste overí Java backend a frontend.
Push do `main` po úspešných testoch automaticky nasadí oba otestované balíky
do Azure. Pull requesty iba testujú. Workflow **CI/CD** možno spustiť aj ručne
cez **Actions → CI/CD → Run workflow** na `main`.

Nasadenie používa GitHub OIDC a identitu `nhlpredict-github`; do GitHubu sa
neukladá Azure heslo, publish profile ani storage key. Stav backendu a publikovaný
web sa overia na konci pipeline. `release.json` na webe uvádza nasadený commit.

Frontend nepotrebuje build. Jeho DOM testy používajú Node 22:

```powershell
cd frontend
npm install --ignore-scripts
npm test
```

Ručné nasadenie mimo CI/CD po `az login`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/deploy.ps1
```

[Podrobný deployment, autentifikácia a prevádzka](docs/azure-deployment.md)
obsahujú presné zdroje, nastavenia a živé smoke overenie. Microsoft client secret
treba obnoviť pred jeho ročnou expiráciou; postup je v dokumentácii.

## Voliteľný ručný import vlastníka

Bežný denný zber beží v Azure. Nasledujúce skripty zostávajú iba ako nástroje
na ručný import; žiadna Windows plánovaná úloha nie je zaregistrovaná.

```powershell
# Načíta oficiálny program, publikovaný hromadný CSV a dva brankárske exporty.
# Uloží natívne polia aj spoločné indexy; ChatGPT potom doplní zvyšný research.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/sync-data.ps1
```

Pri `-Finalize` sa prepočet zaradí do Azure fronty iba po splnení povinného
checklistu. Čerstvý deň so zostávajúcimi nepreverenými položkami sa nefinalizuje.
`-PrefillFile` umožní import overeného výstupu zberača mladšieho než šesť hodín.
Import mení iba povolené natívne polia a zodpovedajúcu históriu brankára;
zostavy, kurzy a potvrdenie brankára zachová. Používa aktuálnu revision a súkromnú zálohu.

Voliteľnú dennú úlohu vytvorí `scripts/register-data-sync.ps1 -At 19:30`.
Vyžaduje zapnutý PC, prihláseného používateľa, internet a platné `az login`.
Nevytvára nové prihlasovacie tajomstvá; storage connection sa drží iba v procese.
Log je v `.local/data-sync-YYYY-MM-DD.log`. Úloha sa nespúšťa ako administrátor,
neprebúdza PC a nekoliduje s druhou vlastnou inštanciou. Tento skript sa pri
deploymentoch automaticky **nespúšťa**.
