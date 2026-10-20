# NHL Observatory: denná ChatGPT úloha o 20:00

## Nastavenie v aplikácii — tieto pokyny nevkladaj do promptu úlohy

Nastav **každý deň o 20:00, Europe/Bratislava**, s pripojeným pluginom
**NHL Observatory**, dostupným webovým researchom a spustením **Cloud**.
Nepoužívaj lokálny workspace ani desktopový beh vyžadujúci zapnutý PC.

Do uloženého promptu vlož iba obsah bloku `text` nižšie. Po uložení úlohy klikni
ty v jej detaile na **Run now**. Model pri tomto behu vykonáva analýzu; nepotrebuje
príkaz na spustenie ďalšej úlohy ani na ovládanie tlačidla Run now.
V histórii tohto behu over skutočné volania MCP, uloženie zápasov a dokončenie
finalizácie. Samostatný test v inom chate overí iba nástroje daného chatu.
Pripojenie pluginu samo nevytvorí úlohu
ani neoverí jej oprávnenia. Dostupnosť custom MCP a prípadné potvrdenia zápisov
v tvojom účte musí potvrdiť skutočný skúšobný beh. Úlohu si nastavíš v ChatGPT;
Azure deployment ju nevytvára.

Oficiálny postup: https://learn.chatgpt.com/docs/automations

Po aktualizácii servera otvor **ChatGPT Plugins → NHL Observatory → Refresh**
a over nové nástroje `get_candidates` a `post_tips`. Potom aktualizuj uložený
prompt úlohy podľa bloku nižšie. V novom skúšobnom behu over nielen `COMPLETE`,
ale aj `final_tips.state=PUBLISHED`.
Oficiálny postup obnovy: https://developers.openai.com/plugins/deploy/connect-chatgpt#refresh-metadata

## Prompt na vloženie do úlohy

```text
Použi pripojený plugin NHL Observatory a dokonči dnešnú NHL analýzu na webe.
Používaj MCP a webový research; nespúšťaj lokálny projekt ani platené AI API.

Pri každom spustení tohto promptu vykonaj analýzu, vrátane skúšobného behu.
Začni teraz skutočným volaním get_instructions cez pripojený plugin a pokračuj
podľa krokov nižšie. Vytvorenie rozvrhu a kliknutie na Run now rieši používateľ
v aplikácii; správa plánovaných úloh nie je súčasťou tejto analýzy.
V rámci analýzy máš moje poverenie ukladať denné dáta a research do môjho
NHL Observatory cez start_day, save_game, finalize_day a post_tips.

1. Urči dnešný kalendárny deň v America/New_York (nie slovenský dátum po polnoci).
   Riaď sa aktuálnou MCP adaptáciou z get_instructions. Podľa potreby
   zavolaj get_schema. Potom zavolaj start_day s týmto dátumom.
   NO_GAMES je platný výsledok; nenahrádzaj deň iným dátumom.

2. Pri PREFILLING volaj get_results s niekoľkosekundovými odstupmi, kým import
   skončí. Needituj zápasy počas importu. Pri chybe skontroluj stav a primerane
   zopakuj volanie; pri trvalom zlyhaní uveď konkrétny blokujúci problém.

3. Pre KAŽDÝ game_id z programu načítaj get_game. Zachovaj presne hodnoty
   backend_prefilled_fields, záznamy backend_prefilled_market_ids a natívne
   kontroly trhov. Vynechané natívne kontroly polí backend obnoví pri uložení.
   Kurzy zbiera Azure z NHL/DraftKings; nevymýšľaj iné hranice ani ceny.
   Rozsah: víťaz vrátane OT/SO, 1/X/2 po 60 minútach, over/under a handicap.
   Dvojtipy a tímové totals nemusia byť dostupné; ich neprítomnosť nesmie
   zastaviť dokončenie researchu. Tímový over 2,5 ostáva pozastavený.

4. Vyhľadaj zostávajúce údaje: aktuálne absencie a návraty, zostavy, presilovkové
   jednotky, predpokladaných a potvrdených brankárov a ďalšie nevyplnené kontroly.
   Otvor skutočné, časovo aktuálne zdroje. Probable nie je confirmed.
   Nevymýšľaj xG, číselný dopad zostavy ani potvrdenie, ktoré zdroj neposkytuje.
   Pri zmene mena brankára nastav staré starts/gsax na null a ich kontroly na
   not_checked; backend sa pokúsi doplniť históriu správneho brankára pri save_game.

5. Každý zápas ulož samostatne cez save_game s dôkazmi, URL, skutočným UTC časom
   kontroly a poslednou revision. Spracúvaj zápasy postupne. Pri konflikte revision
   znovu načítaj get_game a prenes svoj research do aktuálneho záznamu; neprepíš
   novšie backendové kurzy. Nové overenie dostane nové evidence ID.

6. Ak konkrétny údaj po poctivom hľadaní chýba, použi unavailable s konkrétnym
   dôvodom a dôkazom. Neoznačuj všetko unavailable kvôli výpadku jedného webu.
   Prázdny zoznam znamená overený prázdny zoznam. Zápasy, ktoré už začali,
   označ live/final a odstráň ich predzápasové markets.

7. Zavolaj check_day a oprav všetky validačné/evidence chyby a not_checked.
   Potom zavolaj finalize_day so sims=1000. Dokonči research aj pri
   odds_import=WAITING alebo PARTIAL; chýbajúce kurzy nevymýšľaj a nečakaj
   na ne celé hodiny. Azure ich od 18:00 každých 30 minút kontroluje a po ich
   príchode obnoví výpočet z uloženého dokončeného researchu.

8. Po finalizácii volaj get_results s niekoľkosekundovými odstupmi až do
   COMPLETE, INCOMPLETE, ERROR alebo NO_GAMES. Neskonči pri samotnom QUEUED.
   NO_GAMES je platný koniec. COMPLETE znamená iba hotový Java výpočet:
   nasledujúce analytické kroky MUSÍŠ vykonať v tom istom rune.

9. Pre každý zápas zavolaj get_candidates(day, game_id). Načítaj aktuálny
   research, dôkazy a VŠETKY kandidátske typy v pásme 1,20–1,35 vrátane.
   Pri CALCULATION_REQUIRED znovu dokonči výpočet a načítaj kandidátov.
   Posúď víťaza, výsledok po 60 minútach, handicapy, nad aj pod góly spolu
   so zostavami, brankármi, absenciami, oddychom a cestovaním.
   model_preference je iba mechanická preferencia nekalibrovaného modelu.
   Nekopíruj ju automaticky. Ak všetko vychádza under, prever spoločný dôvod
   a možné skreslenie modelu/odhadovaných cien. Nevynucuj rôznorodosť tipov.
   Backendové pravdepodobnosti a EV nemeníš a nevymýšľaš nové. Ak zvažuješ
   alternatívu, s ktorou model nesúhlasí, vysvetli konkrétny dôvod opretý
   o evidence, priznaj nesúhlas a nízku confidence; bez dostatočnej opory NO_BET.

10. Vyber najviac jeden kandidát na zápas alebo NO_BET. Zostav kompletný zoznam
    decisions pre VŠETKY game_id vrátane už začatých (tie vždy NO_BET).
    Každé rozhodnutie obsahuje game_id, decision (TIP/NO_BET), candidate_id
    (pri NO_BET null), confidence (LOW/MODERATE/HIGH), rationale, comparison,
    risks a evidence_ids. Rationale musí mať aspoň 40 znakov a comparison
    aspoň 20; porovnaj konkrétne alternatívy a uveď prečo vyhral výber.
    TIP odkazuje na selectable kandidáta a aspoň jeden jeho skutočný dôkaz.
    Nevkladaj vlastné ceny ani pravdepodobnosti; server ich prevezme z kandidáta.
    ESTIMATED je len aproximovaná cena, nie overená ponuka. Confidence je
    kvalita analytického záveru a podkladov, nie garantovaná úspešnosť.
    Pri nepotvrdených/starnúcich vstupoch, nesúhlase modelu alebo strate výhody
    v konzervatívnom scenári použi LOW a konkrétne risks. NO_BET je legitímny
    výsledok; nevymýšľaj tip len na vyplnenie každého zápasu.

11. Zavolaj post_tips(day, revision, analysis_id, decisions) s aktuálnymi
    hodnotami z get_candidates. Publikuj celý deň naraz. Pri konflikte revision
    načítaj nový snapshot; ak sa analysis_id zmenil, výbery znovu zhodnoť.
    Over odpoveď post_tips a následne get_results: final_tips.state musí byť
    PUBLISHED. Samotné COMPLETE nestačí a beh nesmie skončiť pred zápisom.
    Uveď NHL dátum, počet spracovaných zápasov, finálnych TIP a NO_BET,
    dostupnosť kurzov, chýbajúce údaje a web_url. Odlíš Java NO_BET od finálneho
    rozhodnutia ChatGPT a priznaj aproximácie aj prípadný nesúhlas modelu.

Ak nemáš prístup k MCP, webovému researchu alebo oprávneniu uložiť výsledky,
uveď to otvorene. Nepredstieraj vykonané volania ani dokončený beh.
Nevykonávaj žiadne stávky.
```

Azure kontroluje kurzy na UTC časovači každú celú a pol hodinu a okno vyhodnocuje
v `Europe/Bratislava`, vrátane DST. Kontroly bežia 18:00–05:30. O 18:00 vznikne
program a natívny prefill; po úplnom researchi sa pri nových kurzoch obnoví Java
výpočet. Azure nespúšťa ChatGPT research namiesto úlohy o 20:00.
Pri zmene podkladov sa uložený výber označí NEEDS_REVIEW. Java ho sama
nenahradí iným tipom; nové analytické rozhodnutie musí uložiť ďalší MCP run.
Nezmenené ceny a údaje pri bežnej kontrole samy osebe výber nezneplatnia.
