# Tipsport: stav integrácie

Implementácia zberu nie je dokončená ani nasadená. Pri bežnom HTTP overení
20. 10. 2026 o 09:26 UTC vrátil verejný NHL zoznam Tipsport.sk HTTP 403 cez
Cloudflare, bez kurzových dát. Z Azure sa dostupnosť zatiaľ nepotvrdila.
Vyhľadávačom indexované kurzy nie sú aktuálny predzápasový feed.

Pred implementáciou parsera je potrebný bežne dostupný alebo poskytovateľom
povolený dátový vstup. Nepoužívať získavanie ochranných cookies, riešenie CAPTCHA,
maskovanie klienta, proxy ani zmenu výstupnej IP na prekonanie odmietnutia.
Tento pokus nemenil vtedy nasadený backend 2.2 ani publikované dáta.
Od verzie 2.3 je automatický import kurzov postavený na verejnom NHL/DraftKings
feede; Tipsport scraping zostáva nezapojený.

## Pripravené kontroly párovania

`OddsGameMatcher` prijíma normalizované NHL kódy, UTC začiatok udalosti,
stav live/pregame a skutočný čas získania kurzov. Používa ho aj import NHL
kurzov; samotný matcher žiadne kurzy nesťahuje. NHL import navyše vyžaduje
presný gameId a presnú zhodu UTC začiatku.

- Deň programu sa určuje v `America/New_York`. Zápas po polnoci slovenského
  času môže patriť do predchádzajúceho NHL dňa.
- Preferovať epoch/offset timestamp zo zdroja. Lokálny čas vyžaduje výslovne
  známu zónu; neexistujúce a dvojznačné časy pri zmene DST sa odmietnu.
- Presná zhoda domáceho a hosťujúceho tímu; žiadne automatické prehodenie strán.
- Najviac desať minút rozdielu od NHL začiatku (ponuka môže uvádzať napr. :07).
  Viac zodpovedajúcich zápasov znamená odmietnutie.
- Predzápasový import končí skorším z NHL a bookmakerovho začiatku. Stav live,
  uplynutý začiatok, budúci čas zberu a kurzy staršie než dve hodiny sa odmietnu.

Parser musí následne overiť pravidlá každého trhu: základný čas oproti OT/SO,
celkové oproti tímovým gólom, hranice a vratky pri celej hranici. Akciové kurzy
nesmú nahradiť štandardnú ponuku. Tímový over 2,5 ostáva pozastavený.
