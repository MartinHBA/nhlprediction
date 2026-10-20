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

## Prompt na vloženie do úlohy

```text
Použi pripojený plugin NHL Observatory a dokonči dnešnú NHL analýzu na webe.
Používaj MCP a webový research; nespúšťaj lokálny projekt ani platené AI API.

Pri každom spustení tohto promptu vykonaj analýzu, vrátane skúšobného behu.
Začni teraz skutočným volaním get_instructions cez pripojený plugin a pokračuj
podľa krokov nižšie. Vytvorenie rozvrhu a kliknutie na Run now rieši používateľ
v aplikácii; správa plánovaných úloh nie je súčasťou tejto analýzy.
V rámci analýzy máš moje poverenie ukladať denné dáta a research do môjho
NHL Observatory cez start_day, save_game a finalize_day.

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
   Potom zavolaj finalize_day so sims=100000. Dokonči research aj pri
   odds_import=WAITING alebo PARTIAL; chýbajúce kurzy nevymýšľaj a nečakaj
   na ne celé hodiny. Azure ich od 18:00 každých 30 minút kontroluje a po ich
   príchode obnoví výpočet z uloženého dokončeného researchu.

8. Po finalizácii volaj get_results s niekoľkosekundovými odstupmi až do
   COMPLETE, INCOMPLETE, ERROR alebo NO_GAMES. Neskonči pri samotnom QUEUED.
   Uveď NHL dátum, počet spracovaných zápasov, stav researchu, počet zápasov
   s aktuálnymi kurzami, podstatné chýbajúce údaje a web_url z odpovede.
   Rozlišuj dokončený výpočet, NO_BET a čakanie na aktuálny NHL feed.
   Backend počíta pravdepodobnosti a EV; nenahrádzaj ich vlastnými odhadmi.

Ak nemáš prístup k MCP, webovému researchu alebo oprávneniu uložiť výsledky,
uveď to otvorene. Nepredstieraj vykonané volania ani dokončený beh.
Nevykonávaj žiadne stávky.
```

Azure kontroluje kurzy na UTC časovači každú celú a pol hodinu a okno vyhodnocuje
v `Europe/Bratislava`, vrátane DST. Kontroly bežia 18:00–05:30. O 18:00 vznikne
program a natívny prefill; po úplnom researchi sa pri nových kurzoch obnoví Java
výpočet. Azure nespúšťa ChatGPT research namiesto úlohy o 20:00.
