'use strict';
window.NHL_I18N=(()=>{
 const languages={sk:{name:'Slovenčina',locale:'sk-SK',flag:'sk'},en:{name:'English',locale:'en-GB',flag:'gb'},cs:{name:'Čeština',locale:'cs-CZ',flag:'cz'},sv:{name:'Svenska',locale:'sv-SE',flag:'se'},fi:{name:'Suomi',locale:'fi-FI',flag:'fi'},de:{name:'Deutsch',locale:'de-DE',flag:'de'}};
 // Columns: Slovak source, English, Czech, Swedish, Finnish, German.
 const rows=[
 ['Prosím počkajte...','Please wait...','Prosím počkejte...','Vänligen vänta...','Odota...','Bitte warten...'],
 ['Začiatok analýzy o {time}','Analysis starts at {time}','Začátek analýzy v {time}','Analysen startar kl. {time}','Analyysi alkaa klo {time}','Analysebeginn um {time}'],
 ['Načítanie dát o {time}','Data collection at {time}','Načtení dat v {time}','Data hämtas kl. {time}','Tiedot haetaan klo {time}','Datenabruf um {time}'],
 ['Elo pred zápasom: {rating} · sezóna {season}','Pregame Elo: {rating} · season {season}','Elo před zápasem: {rating} · sezóna {season}','Elo före match: {rating} · säsong {season}','Elo ennen ottelua: {rating} · kausi {season}','Elo vor dem Spiel: {rating} · Saison {season}'],
 ['Program zápasov je pripravený','Game schedule is ready','Program zápasů je připraven','Spelschemat är klart','Otteluohjelma on valmis','Der Spielplan ist bereit'],
 ['Rozšírené štatistiky','Advanced statistics','Pokročilé statistiky','Fördjupad statistik','Tarkemmat tilastot','Erweiterte Statistiken'],
 ['Rozšírené štatistiky sa načítajú o 18:00 slovenského času.','Advanced statistics will be loaded at 18:00 Slovakia time.','Pokročilé statistiky se načtou v 18:00 slovenského času.','Fördjupad statistik hämtas kl. 18:00 slovakisk tid.','Tarkemmat tilastot haetaan klo 18:00 Slovakian aikaa.','Erweiterte Statistiken werden um 18:00 Uhr slowakischer Zeit geladen.'],
 ["Kurzy sa načítajú o 19:45 slovenského času.","Odds will be collected at 19:45 Slovakia time.","Kurzy se načtou v 19:45 slovenského času.","Odds hämtas kl. 19:45 slovakisk tid.","Kertoimet haetaan klo 19:45 Slovakian aikaa.","Quoten werden um 19:45 Uhr slowakischer Zeit geladen."],
 ["Načítavam denný katalóg kurzov Oddize.","Loading the daily Oddize odds catalogue.","Načítám denní katalog kurzů Oddize.","Hämtar dagens oddskatalog från Oddize.","Ladataan päivän Oddize-kertoimia.","Der tägliche Oddize-Quotenkatalog wird geladen."],
 ["Zber kurzov Oddize zlyhal; športový research môže pokračovať.","Oddize odds collection failed; sports research can continue.","Sběr kurzů Oddize selhal; sportovní rešerše může pokračovat.","Hämtningen av Oddize-odds misslyckades; övrig research kan fortsätta.","Oddize-kertoimien haku epäonnistui; muu taustatutkimus voi jatkua.","Der Abruf der Oddize-Quoten ist fehlgeschlagen; die Sportrecherche kann fortgesetzt werden."],
 ["Kurzy Oddize: {covered}/{total} zápasov · {markets} ponúk. Denný zber o 19:45 slovenského času. Načítané {checked}, najnovšia cena {source}.","Oddize odds: {covered}/{total} games · {markets} offers. Daily collection at 19:45 Slovakia time. Loaded {checked}, latest price {source}.","Kurzy Oddize: {covered}/{total} zápasů · {markets} nabídek. Denní sběr v 19:45 slovenského času. Načteno {checked}, nejnovější cena {source}.","Oddize-odds: {covered}/{total} matcher · {markets} erbjudanden. Daglig hämtning kl. 19:45 slovakisk tid. Hämtat {checked}, senaste pris {source}.","Oddize-kertoimet: {covered}/{total} ottelua · {markets} tarjousta. Päivittäinen haku klo 19:45 Slovakian aikaa. Ladattu {checked}, uusin kerroin {source}.","Oddize-Quoten: {covered}/{total} Spiele · {markets} Angebote. Täglicher Abruf um 19:45 Uhr slowakischer Zeit. Geladen {checked}, neueste Quote {source}."],
 ["60 minút","60 minutes","60 minut","60 minuter","60 minuuttia","60 Minuten"],
 ["Vrátane OT/SO","Including OT/SO","Včetně OT/SO","Inklusive förlängning/straffar","Sisältää jatkoajan/voittolaukaukset","Inklusive Verlängerung/Penaltyschießen"],
 ['Zápasy a prvé dáta','Games and initial data','Zápasy a první data','Matcher och första data','Ottelut ja ensimmäiset tiedot','Spiele und erste Daten'],
 ['Podrobná analýza a tipy','In-depth analysis and picks','Podrobná analýza a tipy','Fördjupad analys och tips','Perusteellinen analyysi ja vihjeet','Ausführliche Analyse und Tipps'],
 ['Dáta načítané','Data loaded','Data načtena','Data inlästa','Tiedot ladattu','Daten geladen'],
 ['Analýza dokončená','Analysis complete','Analýza dokončena','Analysen är klar','Analyysi valmis','Analyse abgeschlossen'],
 ['Čakáme na dáta','Waiting for data','Čekáme na data','Väntar på data','Odotetaan tietoja','Warten auf Daten'],
 ['Čakáme na analýzu','Waiting for analysis','Čekáme na analýzu','Väntar på analys','Odotetaan analyysia','Warten auf die Analyse'],
 ['Dnes o {time} · slovenský čas','Today at {time} · Slovakia time','Dnes v {time} · slovenský čas','I dag kl. {time} · slovakisk tid','Tänään klo {time} · Slovakian aikaa','Heute um {time} · slowakische Zeit'],
 ['Začiatok o {time} · slovenský čas','Starts at {time} · Slovakia time','Začátek v {time} · slovenský čas','Start kl. {time} · slovakisk tid','Alkaa klo {time} · Slovakian aikaa','Beginn um {time} · slowakische Zeit'],
 ['Jazyk stránky','Website language','Jazyk stránky','Webbplatsens språk','Sivuston kieli','Sprache der Website'],
 ['Logo {team}','{team} logo','Logo {team}','{team}-logotyp','{team}-logo','Logo von {team}'],
 ['NHL Analyzer Online domov','NHL Analyzer Online home','NHL Analyzer Online domů','NHL Analyzer Online startsida','NHL Analyzer Online etusivu','NHL Analyzer Online Startseite'],
 ['NHL Analyzer Online — Denné predikcie','NHL Analyzer Online — Daily picks','NHL Analyzer Online — Denní tipy','NHL Analyzer Online — Dagens tips','NHL Analyzer Online — Päivän vihjeet','NHL Analyzer Online — Tägliche Tipps'],
 ['NHL Analyzer Online – denný NHL research a finálne analytické výbery.','NHL Analyzer Online – daily NHL research and analytical picks.','NHL Analyzer Online – denní NHL rešerše a analytické tipy.','NHL Analyzer Online – daglig NHL-research och analytiska speltips.','NHL Analyzer Online – päivittäiset NHL-analyysit ja pelivihjeet.','NHL Analyzer Online – tägliche NHL-Recherche und analytische Tipps.'],
 ['DÁTA PRED ZÁPASOM','PREGAME DATA','DATA PŘED ZÁPASEM','DATA INFÖR MATCH','TIEDOT ENNEN OTTELUA','DATEN VOR DEM SPIEL'],
 ['DENNÝ PREHĽAD / NHL','DAILY OVERVIEW / NHL','DENNÍ PŘEHLED / NHL','DAGENS ÖVERSIKT / NHL','PÄIVÄN KATSAUS / NHL','TAGESÜBERSICHT / NHL'],
 ['Večer začína','The evening starts','Večer začíná','Kvällen börjar','Ilta alkaa','Der Abend beginnt'],
 ['dobrými dátami.','with good data.','dobrými daty.','med bra data.','hyvillä tiedoilla.','mit guten Daten.'],
 ['Program, analytické dáta a tipy na zápasy.','Schedules, analytical data and game picks.','Program, analytická data a tipy na zápasy.','Spelschema, analysdata och matchtips.','Otteluohjelma, tilastot ja pelivihjeet.','Spielplan, Analysedaten und Spieltipps.'],
 ['Všetky zápasy. Každý zdroj. Na jednom mieste.','Every game. Every source. In one place.','Všechny zápasy. Každý zdroj. Na jednom místě.','Alla matcher. Alla källor. På ett ställe.','Kaikki ottelut. Kaikki lähteet. Samassa paikassa.','Alle Spiele. Alle Quellen. An einem Ort.'],
 ['HRACÍ DEŇ NHL','NHL GAME DAY','HRACÍ DEN NHL','NHL-MATCHDAG','NHL-PELIPÄIVÄ','NHL-SPIELTAG'],
 ['Hrací deň NHL','NHL game day','Hrací den NHL','NHL-matchdag','NHL-pelipäivä','NHL-Spieltag'],
 ['Predchádzajúci deň','Previous day','Předchozí den','Föregående dag','Edellinen päivä','Vorheriger Tag'],
 ['Nasledujúci deň','Next day','Následující den','Nästa dag','Seuraava päivä','Nächster Tag'],
 ['Dátum podľa kalendára NHL · New York','NHL calendar date · New York','Datum podle kalendáře NHL · New York','Datum enligt NHL-kalendern · New York','NHL-kalenterin päivämäärä · New York','Datum nach NHL-Kalender · New York'],
 ['↻ Obnoviť údaje','↻ Refresh data','↻ Obnovit údaje','↻ Uppdatera data','↻ Päivitä tiedot','↻ Daten aktualisieren'],
 ['Súhrn dňa','Day summary','Souhrn dne','Dagens sammanfattning','Päivän yhteenveto','Tageszusammenfassung'],
 ['ZÁPASY','GAMES','ZÁPASY','MATCHER','OTTELUT','SPIELE'],
 ['kompletný program','complete schedule','kompletní program','hela spelschemat','koko otteluohjelma','vollständiger Spielplan'],
 ['VYHODNOTENÉ ZÁPASY','ANALYSED GAMES','VYHODNOCENÉ ZÁPASY','ANALYSERADE MATCHER','ANALYSOIDUT OTTELUT','ANALYSIERTE SPIELE'],
 ['uložené rozhodnutia','saved decisions','uložená rozhodnutí','sparade beslut','tallennetut valinnat','gespeicherte Entscheidungen'],
 ['POTVRDENÍ BRANKÁRI','CONFIRMED GOALIES','POTVRZENÍ BRANKÁŘI','BEKRÄFTADE MÅLVAKTER','VAHVISTETUT MAALIVAHDIT','BESTÄTIGTE TORHÜTER'],
 ['z oboch tímov','across both teams','z obou týmů','för båda lagen','molemmista joukkueista','beider Teams'],
 ['DOSTUPNÉ ÚDAJE','AVAILABLE DATA','DOSTUPNÉ ÚDAJE','TILLGÄNGLIGA DATA','SAATAVILLA OLEVAT TIEDOT','VERFÜGBARE DATEN'],
 ['skutočne vyplnené polia','fields with actual data','skutečně vyplněná pole','fält med faktiska data','kentät, joissa on tietoa','tatsächlich ausgefüllte Felder'],
 ['ĽAD POD LUPOU','ON THE ICE','LED POD LUPOU','ISEN UNDER LUPP','JÄÄ SUURENNUSLASIN ALLA','DAS EIS IM FOKUS'],
 ['Zápasy a analýza','Games and analysis','Zápasy a analýza','Matcher och analys','Ottelut ja analyysi','Spiele und Analyse'],
 ['Dostupnosť dát','Data availability','Dostupnost dat','Datatillgänglighet','Tietojen saatavuus','Datenverfügbarkeit'],
 ['Upozornenia zo zdrojov','Source warnings','Upozornění ze zdrojů','Varningar från källor','Lähteiden varoitukset','Quellenhinweise'],
 ['Archív dní','Day archive','Archiv dní','Dagarkiv','Päiväarkisto','Tagesarchiv'],
 ['HISTÓRIA','HISTORY','HISTORIE','HISTORIK','HISTORIA','VERLAUF'],
 ['Načítavam…','Loading…','Načítám…','Laddar…','Ladataan…','Wird geladen…'],
 ['AKO ČÍTAŤ VÝSLEDKY','HOW TO READ THE RESULTS','JAK ČÍST VÝSLEDKY','SÅ TOLKAR DU RESULTATEN','NÄIN LUET TULOKSIA','SO LIEST DU DIE ERGEBNISSE'],
 ['Dáta majú zdroje.','Data has sources.','Data mají zdroje.','Data har källor.','Tiedoilla on lähteet.','Daten haben Quellen.'],
 ['Tipy majú dôvody.','Picks have reasons.','Tipy mají důvody.','Tips har skäl.','Vihjeillä on perustelut.','Tipps haben Gründe.'],
 ['Štatistiky, research a kurzy tvoria podklady pre finálny výber so zdôvodnením a confidence. Confidence vyjadruje kvalitu podkladov, nie garantovanú úspešnosť.','Statistics, research and odds support the final pick, its reasoning and confidence level. Confidence describes the quality of the evidence, not a guaranteed success rate.','Statistiky, rešerše a kurzy tvoří podklady pro finální tip, jeho zdůvodnění a míru důvěry. Důvěra vyjadřuje kvalitu podkladů, nikoli zaručenou úspěšnost.','Statistik, research och odds ligger till grund för det slutliga tipset, motiveringen och tillförlitligheten. Tillförlitligheten beskriver underlagets kvalitet, inte en garanterad träffsäkerhet.','Tilastot, taustatutkimus ja kertoimet tukevat lopullista vihjettä, sen perusteluja ja luottamustasoa. Luottamus kuvaa aineiston laatua, ei taattua osumatarkkuutta.','Statistiken, Recherche und Quoten bilden die Grundlage für den endgültigen Tipp, seine Begründung und die Vertrauenseinschätzung. Diese beschreibt die Qualität der Belege, keine garantierte Trefferquote.'],
 ['„Bez tipu“ je platný výsledok. Odhadovaný kurz nie je overená ponuka. Nepotvrdené zostavy, staré kurzy a neúplné kontroly ostávajú viditeľné.','“No pick” is a valid outcome. Estimated odds are not a verified offer. Unconfirmed lineups, stale odds and incomplete checks remain visible.','„Bez tipu“ je platný výsledek. Odhadovaný kurz není ověřená nabídka. Nepotvrzené sestavy, staré kurzy a neúplné kontroly zůstávají viditelné.','”Inget tips” är ett giltigt resultat. Uppskattade odds är inget verifierat erbjudande. Obekräftade laguppställningar, inaktuella odds och ofullständiga kontroller visas fortfarande.','”Ei vihjettä” on kelvollinen tulos. Arvioitu kerroin ei ole vahvistettu tarjous. Vahvistamattomat kokoonpanot, vanhentuneet kertoimet ja keskeneräiset tarkistukset pysyvät näkyvissä.','„Kein Tipp“ ist ein gültiges Ergebnis. Geschätzte Quoten sind kein verifiziertes Angebot. Unbestätigte Aufstellungen, veraltete Quoten und unvollständige Prüfungen bleiben sichtbar.'],
 ['Tímový over 2,5 gólu je pozastavený.','Team totals over 2.5 goals are suspended.','Týmový over 2,5 gólu je pozastaven.','Lagtotal över 2,5 mål används inte för närvarande.','Joukkueen yli 2,5 maalin vihjeet ovat poissa käytöstä.','Teamwetten über 2,5 Tore sind ausgesetzt.'],
 ['vlastný analytický prehľad','independent analytical overview','vlastní analytický přehled','egen analysöversikt','oma analyysikatsaus','eigene Analyseübersicht'],
 ['Štatistiky xG a GSAx:','xG and GSAx statistics:','Statistiky xG a GSAx:','xG- och GSAx-statistik:','xG- ja GSAx-tilastot:','xG- und GSAx-Statistiken:'],
 ['Program a štarty: NHL','Schedule and starts: NHL','Program a starty: NHL','Spelschema och starter: NHL','Otteluohjelma ja aloitukset: NHL','Spielplan und Starts: NHL'],
 ['Časy zápasov:','Game times:','Časy zápasů:','Matchtider:','Otteluajat:','Spielzeiten:'],
 ['Načítavam štatistiky','Loading statistics','Načítám statistiky','Laddar statistik','Ladataan tilastoja','Statistiken werden geladen'],
 ['Zber dát','Collecting data','Sběr dat','Samlar in data','Tietojen keruu','Datenerfassung'],
 ['Dáta pripravené na analýzu','Data ready for analysis','Data připravena k analýze','Data redo för analys','Tiedot valmiina analyysiin','Daten zur Analyse bereit'],
 ['Analýza uložená','Analysis saved','Analýza uložena','Analysen sparad','Analyysi tallennettu','Analyse gespeichert'],
 ['Zber dát zlyhal','Data collection failed','Sběr dat selhal','Datainsamlingen misslyckades','Tietojen keruu epäonnistui','Datenerfassung fehlgeschlagen'],
 ['Bez zápasov','No games','Bez zápasů','Inga matcher','Ei otteluita','Keine Spiele'],
 ['Brankár','Goalie','Brankář','Målvakt','Maalivahti','Torhüter'],
 ['Zostava','Lineup','Sestava','Laguppställning','Kokoonpano','Aufstellung'],
 ['Oddych a cestovanie','Rest and travel','Odpočinek a cestování','Vila och resor','Lepo ja matkustaminen','Erholung und Reisen'],
 ['Aktuálna sezóna','Current season','Aktuální sezóna','Pågående säsong','Kuluva kausi','Aktuelle Saison'],
 ['Minulá sezóna','Previous season','Minulá sezóna','Föregående säsong','Edellinen kausi','Vorherige Saison'],
 ['Odohrané zápasy','Games played','Odehrané zápasy','Spelade matcher','Pelatut ottelut','Absolvierte Spiele'],
 ['Bodová úspešnosť (%)','Points percentage (%)','Bodová úspěšnost (%)','Poängprocent (%)','Pisteprosentti (%)','Punktausbeute (%)'],
 ['xG za 60 min · 5 na 5','xG per 60 min · 5-on-5','xG za 60 min · 5 na 5','xG per 60 min · 5 mot 5','xG / 60 min · 5 vastaan 5','xG pro 60 Min. · 5 gegen 5'],
 ['xG súpera za 60 min · 5 na 5','xG against per 60 min · 5-on-5','xG soupeře za 60 min · 5 na 5','xG emot per 60 min · 5 mot 5','Vastustajan xG / 60 min · 5 vastaan 5','Gegnerische xG pro 60 Min. · 5 gegen 5'],
 ['xG za 60 min · presilovka','xG per 60 min · power play','xG za 60 min · přesilovka','xG per 60 min · powerplay','xG / 60 min · ylivoima','xG pro 60 Min. · Überzahl'],
 ['xG súpera za 60 min · oslabenie','xG against per 60 min · penalty kill','xG soupeře za 60 min · oslabení','xG emot per 60 min · boxplay','Vastustajan xG / 60 min · alivoima','Gegnerische xG pro 60 Min. · Unterzahl'],
 ['Vynútené tresty na zápas','Penalties drawn per game','Vynucené tresty na zápas','Framkallade utvisningar per match','Hankitut jäähyt / ottelu','Herausgeholte Strafen pro Spiel'],
 ['Tresty na zápas','Penalties taken per game','Tresty na zápas','Tagna utvisningar per match','Otetut jäähyt / ottelu','Erhaltene Strafen pro Spiel'],
 ['Strely na zápas','Shots for per game','Střely na zápas','Skott framåt per match','Laukaukset / ottelu','Eigene Schüsse pro Spiel'],
 ['Strely súpera na zápas','Shots against per game','Střely soupeře na zápas','Skott emot per match','Vastustajan laukaukset / ottelu','Gegnerische Schüsse pro Spiel'],
 ['Veľké šance za 60 min','High-danger chances per 60 min','Velké šance za 60 min','Farliga målchanser per 60 min','Vaaralliset maalipaikat / 60 min','Hochkarätige Chancen pro 60 Min.'],
 ['Veľké šance súpera za 60 min','High-danger chances against per 60 min','Velké šance soupeře za 60 min','Farliga målchanser emot per 60 min','Vastustajan vaaralliset maalipaikat / 60 min','Gegnerische Großchancen pro 60 Min.'],
 ['Meno','Name','Jméno','Namn','Nimi','Name'],
 ['Potvrdenie brankára','Goalie confirmation','Potvrzení brankáře','Målvaktsbekräftelse','Maalivahdin vahvistus','Torhüterbestätigung'],
 ['Štarty · aktuálna sezóna','Starts · current season','Starty · aktuální sezóna','Starter · pågående säsong','Aloitukset · kuluva kausi','Starts · aktuelle Saison'],
 ['Štarty · minulá sezóna','Starts · previous season','Starty · minulá sezóna','Starter · föregående säsong','Aloitukset · edellinen kausi','Starts · vorherige Saison'],
 ['GSAx za 60 min · aktuálna sezóna','GSAx per 60 min · current season','GSAx za 60 min · aktuální sezóna','GSAx per 60 min · pågående säsong','GSAx / 60 min · kuluva kausi','GSAx pro 60 Min. · aktuelle Saison'],
 ['GSAx za 60 min · minulá sezóna','GSAx per 60 min · previous season','GSAx za 60 min · minulá sezóna','GSAx per 60 min · föregående säsong','GSAx / 60 min · edellinen kausi','GSAx pro 60 Min. · vorherige Saison'],
 ['SV% · aktuálna sezóna','SV% · current season','SV% · aktuální sezóna','SV% · pågående säsong','Torjunta-% · kuluva kausi','Fangquote · aktuelle Saison'],
 ['SV% · minulá sezóna','SV% · previous season','SV% · minulá sezóna','SV% · föregående säsong','Torjunta-% · edellinen kausi','Fangquote · vorherige Saison'],
 ['Vyčíslený vplyv zostavy','Quantified lineup impact','Vyčíslený vliv sestavy','Kvantifierad påverkan av laguppställningen','Kokoonpanon määrällinen vaikutus','Quantifizierter Aufstellungseinfluss'],
 ['Vplyv na strelené góly','Impact on goals scored','Vliv na vstřelené góly','Påverkan på gjorda mål','Vaikutus tehtyihin maaleihin','Einfluss auf erzielte Tore'],
 ['Vplyv na inkasované góly','Impact on goals conceded','Vliv na inkasované góly','Påverkan på insläppta mål','Vaikutus päästettyihin maaleihin','Einfluss auf Gegentore'],
 ['Absencie','Absences','Absence','Frånvarande spelare','Poissaolot','Ausfälle'],
 ['Návraty','Returning players','Návraty','Återvändande spelare','Palaavat pelaajat','Rückkehrer'],
 ['Vynechaní hráči','Scratched players','Vynechaní hráči','Spelare utanför matchtruppen','Kokoonpanosta sivussa','Nicht eingesetzte Spieler'],
 ['Útočné formácie','Forward lines','Útočné formace','Anfallskedjor','Hyökkäysketjut','Angriffsreihen'],
 ['Obranné dvojice','Defence pairs','Obranné dvojice','Backpar','Puolustajaparit','Verteidigerpaare'],
 ['Presilovkové jednotky','Power-play units','Přesilovkové jednotky','Powerplayformationer','Ylivoimakoostumukset','Überzahlformationen'],
 ['Dni oddychu','Rest days','Dny odpočinku','Vilodagar','Lepopäivät','Ruhetage'],
 ['Prekonané časové pásma','Time zones crossed','Překonaná časová pásma','Passerade tidszoner','Ylitetyt aikavyöhykkeet','Überquerte Zeitzonen'],
 ['Vyššia hodnota je priaznivejšia.','A higher value is more favorable.','Vyšší hodnota je příznivější.','Ett högre värde är mer gynnsamt.','Suurempi arvo on suotuisampi.','Ein höherer Wert ist günstiger.'],
 ['Nižšia hodnota je priaznivejšia.','A lower value is more favorable.','Nižší hodnota je příznivější.','Ett lägre värde är mer gynnsamt.','Pienempi arvo on suotuisampi.','Ein niedrigerer Wert ist günstiger.'],
 ['Tento údaj nemá jednoznačne lepšiu vyššiu alebo nižšiu hodnotu.','Neither a higher nor a lower value is inherently better for this field.','U tohoto údaje není vyšší ani nižší hodnota jednoznačně lepší.','Varken ett högre eller ett lägre värde är entydigt bättre för detta fält.','Tässä tiedossa suurempi tai pienempi arvo ei ole yksiselitteisesti parempi.','Bei diesem Feld ist weder ein höherer noch ein niedrigerer Wert grundsätzlich besser.'],
 ['Počet zápasov vyjadruje veľkosť vzorky, nie lepšiu výkonnosť.','The game count describes sample size, not better performance.','Počet zápasů vyjadřuje velikost vzorku, nikoli lepší výkonnost.','Antalet matcher anger urvalets storlek, inte bättre prestation.','Ottelumäärä kuvaa otoskokoa, ei parempaa suorituskykyä.','Die Spielanzahl beschreibt die Stichprobengröße, keine bessere Leistung.'],
 ['Potvrdenie vyjadruje istotu štartu, nie kvalitu brankára.','Confirmation describes certainty of starting, not goalie quality.','Potvrzení vyjadřuje jistotu startu, nikoli kvalitu brankáře.','Bekräftelsen anger hur säker starten är, inte målvaktens kvalitet.','Vahvistus kertoo aloituksen varmuudesta, ei maalivahdin tasosta.','Die Bestätigung beschreibt die Sicherheit des Einsatzes, nicht die Qualität des Torhüters.'],
 ['Porovnávajú sa iba vyčíslené dopady zostáv oboch tímov.','Only quantified lineup impacts for both teams are compared.','Porovnávají se pouze vyčíslené vlivy sestav obou týmů.','Endast kvantifierad påverkan av båda lagens uppställningar jämförs.','Vain molempien joukkueiden määrällisesti arvioituja kokoonpanovaikutuksia verrataan.','Verglichen werden nur quantifizierte Aufstellungseinflüsse beider Teams.'],
 ['priaznivejšia hodnota','more favorable value','příznivější hodnota','mer gynnsamt värde','suotuisampi arvo','günstigerer Wert'],
 ['Áno','Yes','Ano','Ja','Kyllä','Ja'],['Nie','No','Ne','Nej','Ei','Nein'],
 ['Prázdny zoznam','Empty list','Prázdný seznam','Tom lista','Tyhjä luettelo','Leere Liste'],
 ['Potvrdený','Confirmed','Potvrzený','Bekräftad','Vahvistettu','Bestätigt'],
 ['Predpokladaný','Probable','Předpokládaný','Trolig','Todennäköinen','Voraussichtlich'],
 ['Neznámy','Unknown','Neznámý','Okänd','Ei tiedossa','Unbekannt'],
 ['Počet zápasov v aktuálnej sezóne pred týmto zápasom, vrátane striedaní.','Current-season appearances before this game, including relief appearances.','Počet zápasů v aktuální sezóně před tímto zápasem, včetně střídání.','Antal matcher den här säsongen före denna match, inklusive inhopp.','Tämän kauden ottelut ennen tätä ottelua, myös vaihdosta tulleena.','Einsätze in dieser Saison vor diesem Spiel, einschließlich Einwechslungen.'],
 ['Podrobné dáta sa nepodarilo načítať.','Detailed data could not be loaded.','Podrobná data se nepodařilo načíst.','Detaljerade data kunde inte laddas.','Yksityiskohtaisia tietoja ei voitu ladata.','Detaildaten konnten nicht geladen werden.'],
 ['Načítavam podrobné dáta…','Loading detailed data…','Načítám podrobná data…','Laddar detaljerade data…','Ladataan yksityiskohtaisia tietoja…','Detaildaten werden geladen…'],
 ['Skúsiť znovu','Try again','Zkusit znovu','Försök igen','Yritä uudelleen','Erneut versuchen'],
 ['Dáta','Data','Data','Data','Tiedot','Daten'],
 ['Chýba konfigurácia backendu.','Backend configuration is missing.','Chybí konfigurace backendu.','Backend-konfiguration saknas.','Taustapalvelun määritykset puuttuvat.','Backend-Konfiguration fehlt.'],
 ['Načítavam prehľad','Loading overview','Načítám přehled','Laddar översikt','Ladataan katsausta','Übersicht wird geladen'],
 ['Pripájam sa k dennej analýze.','Connecting to the daily analysis.','Připojuji se k denní analýze.','Ansluter till dagens analys.','Yhdistetään päivän analyysiin.','Verbindung zur Tagesanalyse wird hergestellt.'],
 ['Tento deň zatiaľ nebol spracovaný','This day has not been processed yet','Tento den zatím nebyl zpracován','Den här dagen har inte behandlats ännu','Tätä päivää ei ole vielä käsitelty','Dieser Tag wurde noch nicht verarbeitet'],
 ['Po dokončení dennej analýzy sa tu objavia výsledky.','Results will appear here once the daily analysis is complete.','Po dokončení denní analýzy se zde objeví výsledky.','Resultaten visas här när dagens analys är klar.','Tulokset näkyvät täällä, kun päivän analyysi on valmis.','Die Ergebnisse erscheinen hier nach Abschluss der Tagesanalyse.'],
 ['Na tento deň zatiaľ nemáme dáta','No data for this day yet','Pro tento den zatím nemáme data','Inga data för den här dagen ännu','Tälle päivälle ei ole vielä tietoja','Für diesen Tag liegen noch keine Daten vor'],
 ['Vyber deň z archívu alebo počkaj na naplánovaný zber. Nezobrazujeme predikcie z iného dňa.','Choose a day from the archive or wait for scheduled collection. Picks from other days are not substituted.','Vyber den z archivu nebo počkej na naplánovaný sběr. Nezobrazujeme tipy z jiného dne.','Välj en dag i arkivet eller vänta på den schemalagda insamlingen. Vi visar inte tips från en annan dag.','Valitse päivä arkistosta tai odota ajastettua keruuta. Muiden päivien vihjeitä ei näytetä tämän päivän sijasta.','Wähle einen Tag aus dem Archiv oder warte auf die geplante Erfassung. Tipps anderer Tage werden nicht ersatzweise angezeigt.'],
 ['Backend sa nepodarilo načítať','Backend could not be reached','Backend se nepodařilo načíst','Kunde inte ansluta till backend','Taustapalveluun ei saatu yhteyttä','Backend konnte nicht erreicht werden'],
 ['Obnov stránku o chvíľu. Prvé načítanie po dlhšej nečinnosti môže trvať dlhšie.','Try again shortly. The first load after a long idle period may take longer.','Obnov stránku za chvíli. První načtení po delší nečinnosti může trvat déle.','Försök igen om en stund. Första laddningen efter en längre inaktivitet kan ta längre tid.','Yritä pian uudelleen. Ensimmäinen lataus pitkän tauon jälkeen voi kestää kauemmin.','Versuche es gleich erneut. Das erste Laden nach längerer Inaktivität kann länger dauern.'],
 ['Prehľad je dočasne nedostupný','Overview temporarily unavailable','Přehled je dočasně nedostupný','Översikten är tillfälligt otillgänglig','Katsaus ei ole tilapäisesti saatavilla','Übersicht vorübergehend nicht verfügbar'],
 ['Skontroluj pripojenie a použi Obnoviť údaje.','Check your connection and select Refresh data.','Zkontroluj připojení a použij Obnovit údaje.','Kontrollera anslutningen och välj Uppdatera data.','Tarkista yhteys ja valitse Päivitä tiedot.','Prüfe die Verbindung und wähle Daten aktualisieren.'],
 ['Načítavam publikované štatistiky MoneyPuck a oficiálne údaje NHL.','Loading published MoneyPuck statistics and official NHL data.','Načítám zveřejněné statistiky MoneyPuck a oficiální údaje NHL.','Laddar publicerad MoneyPuck-statistik och officiella NHL-data.','Ladataan julkaistuja MoneyPuck-tilastoja ja NHL:n virallisia tietoja.','Veröffentlichte MoneyPuck-Statistiken und offizielle NHL-Daten werden geladen.'],
 ['Oficiálny program na tento deň neobsahuje zápasy základnej časti.','The official schedule has no regular-season games for this day.','Oficiální program pro tento den neobsahuje zápasy základní části.','Det officiella spelschemat innehåller inga grundseriematcher den här dagen.','Virallisessa otteluohjelmassa ei ole runkosarjaotteluita tälle päivälle.','Der offizielle Spielplan enthält für diesen Tag keine Hauptrundenspiele.'],
 ['Zber nebol dokončený.','Collection was not completed.','Sběr nebyl dokončen.','Insamlingen slutfördes inte.','Keruuta ei saatu valmiiksi.','Die Erfassung wurde nicht abgeschlossen.'],
 ['Čaká na finálnu analýzu','Awaiting final analysis','Čeká na finální analýzu','Väntar på slutlig analys','Odottaa lopullista analyysiä','Wartet auf die endgültige Analyse'],
 ['Research je pripravený. Finálne výbery ešte nie sú uložené.','Research is ready. Final picks have not been saved yet.','Rešerše je připravena. Finální tipy ještě nejsou uloženy.','Underlaget är klart. Slutliga tips har inte sparats ännu.','Taustatutkimus on valmis. Lopullisia vihjeitä ei ole vielä tallennettu.','Die Recherche ist bereit. Endgültige Tipps wurden noch nicht gespeichert.'],
 ['Priebežné údaje. Prebieha dopĺňanie researchu a posúdenie zápasov.','Preliminary data. Research and game assessment are in progress.','Průběžná data. Probíhá doplňování rešerše a posouzení zápasů.','Preliminära data. Research och matchbedömning pågår.','Alustavat tiedot. Taustatutkimus ja otteluarviointi ovat käynnissä.','Vorläufige Daten. Recherche und Spielbewertung laufen.'],
 ['Výbery v pásme 1,18–1,35: {tips} · Bez tipu: {noBet}.','Picks at odds 1.18–1.35: {tips} · No pick: {noBet}.','Tipy v pásmu 1,18–1,35: {tips} · Bez tipu: {noBet}.','Tips inom oddsintervallet 1,18–1,35: {tips} · Inget tips: {noBet}.','Vihjeitä kertoimilla 1,18–1,35: {tips} · Ei vihjettä: {noBet}.','Tipps im Quotenbereich 1,18–1,35: {tips} · Kein Tipp: {noBet}.'],
 ['Pripravuje sa nová analýza; zatiaľ zostáva zobrazená posledná publikovaná.','A new analysis is being prepared; the latest published version remains visible.','Připravuje se nová analýza; zatím zůstává zobrazena poslední zveřejněná.','En ny analys förbereds; den senast publicerade versionen visas tills vidare.','Uutta analyysiä valmistellaan; viimeisin julkaistu versio pysyy näkyvissä.','Eine neue Analyse wird vorbereitet; die zuletzt veröffentlichte Version bleibt sichtbar.'],
 ['Výbery čakajú na nové overenie','Picks await review','Tipy čekají na nové ověření','Tipsen behöver granskas igen','Vihjeet odottavat tarkistusta','Tipps warten auf erneute Prüfung'],
 ['Podklady sa zmenili. Uložené výbery vyžadujú nové zhodnotenie.','The underlying data changed. Saved picks need reassessment.','Podklady se změnily. Uložené tipy vyžadují nové posouzení.','Underlaget har ändrats. Sparade tips behöver bedömas på nytt.','Lähtötiedot muuttuivat. Tallennetut vihjeet on arvioitava uudelleen.','Die Datengrundlage hat sich geändert. Gespeicherte Tipps müssen neu bewertet werden.'],
 ['Čakáme na aktuálne kurzy NHL; zostávajúci research môže pokračovať.','Waiting for current NHL odds; other research can continue.','Čekáme na aktuální kurzy NHL; zbývající rešerše může pokračovat.','Väntar på aktuella NHL-odds; övrig research kan fortsätta.','Odotetaan ajantasaisia NHL-kertoimia; muu taustatutkimus voi jatkua.','Aktuelle NHL-Quoten werden erwartet; die übrige Recherche kann fortgesetzt werden.'],
 ['Kurzy sú dostupné pre {available}/{total} nadchádzajúcich zápasov.','Odds are available for {available}/{total} upcoming games.','Kurzy jsou dostupné pro {available}/{total} nadcházejících zápasů.','Odds finns för {available}/{total} kommande matcher.','Kertoimet ovat saatavilla {available}/{total} tulevaan otteluun.','Quoten sind für {available}/{total} kommende Spiele verfügbar.'],
 ['Posledná aktualizácia je staršia než 18 hodín.','The last update is more than 18 hours old.','Poslední aktualizace je starší než 18 hodin.','Den senaste uppdateringen är äldre än 18 timmar.','Edellisestä päivityksestä on yli 18 tuntia.','Die letzte Aktualisierung ist mehr als 18 Stunden alt.'],
 ['Aktualizované {time}','Updated {time}','Aktualizováno {time}','Uppdaterat {time}','Päivitetty {time}','Aktualisiert {time}'],
 ['{found} / {total} polí so získanými údajmi','{found} / {total} fields with data','{found} / {total} polí se získanými údaji','{found} / {total} fält med data','{found} / {total} kenttää, joissa on tietoa','{found} / {total} Felder mit Daten'],
 ['Dostupné údaje: {found} / {total}. Uzavreté kontroly: {closed} / {checks} (zahŕňajú aj nedostupné údaje).','Available data: {found} / {total}. Completed checks: {closed} / {checks} (including unavailable data).','Dostupné údaje: {found} / {total}. Uzavřené kontroly: {closed} / {checks} (zahrnují i nedostupné údaje).','Tillgängliga data: {found} / {total}. Avslutade kontroller: {closed} / {checks} (inklusive otillgängliga data).','Saatavilla olevat tiedot: {found} / {total}. Tehdyt tarkistukset: {closed} / {checks} (sisältää myös puuttuvat tiedot).','Verfügbare Daten: {found} / {total}. Abgeschlossene Prüfungen: {closed} / {checks} (einschließlich nicht verfügbarer Daten).'],
 ['skutočná dostupnosť zatiaľ nevyhodnotená','actual availability not yet assessed','skutečná dostupnost zatím nevyhodnocena','faktisk tillgänglighet har ännu inte bedömts','todellista saatavuutta ei ole vielä arvioitu','tatsächliche Verfügbarkeit noch nicht bewertet'],
 ['Kurzy NHL/DraftKings: {covered}/{total} zápasov · {markets} ponúk. Kontrola {checked}, zdroj aktualizovaný {source}. Kontrola každých 30 minút od 18:00 do 08:00 slovenského času.','NHL/DraftKings odds: {covered}/{total} games · {markets} offers. Checked {checked}, source updated {source}. Checked every 30 minutes from 18:00 to 08:00 Europe/Bratislava time.','Kurzy NHL/DraftKings: {covered}/{total} zápasů · {markets} nabídek. Kontrola {checked}, zdroj aktualizován {source}. Kontrola každých 30 minut od 18:00 do 08:00 slovenského času.','NHL/DraftKings-odds: {covered}/{total} matcher · {markets} erbjudanden. Kontrollerat {checked}, källan uppdaterad {source}. Kontroll var 30:e minut från 18:00 till 08:00, tidszon Europe/Bratislava.','NHL/DraftKings-kertoimet: {covered}/{total} ottelua · {markets} tarjousta. Tarkistettu {checked}, lähde päivitetty {source}. Tarkistus 30 minuutin välein klo 18.00–08.00 aikavyöhykkeellä Europe/Bratislava.','NHL/DraftKings-Quoten: {covered}/{total} Spiele · {markets} Angebote. Geprüft {checked}, Quelle aktualisiert {source}. Prüfung alle 30 Minuten von 18:00 bis 08:00 Uhr, Zeitzone Europe/Bratislava.'],
 ['Podklady a kurzy uložené pri analýze: {time}. Ďalej sa obnovuje iba skóre do 08:00 slovenského času.','Analysis data and odds saved at {time}. Only scores are updated afterwards, until 08:00 Europe/Bratislava time.','Podklady a kurzy uloženy při analýze: {time}. Dále se obnovuje pouze skóre do 08:00 slovenského času.','Analysunderlag och odds sparade {time}. Därefter uppdateras endast resultat fram till 08:00, tidszon Europe/Bratislava.','Analyysin tiedot ja kertoimet tallennettu {time}. Sen jälkeen päivitetään vain tuloksia klo 08.00 asti aikavyöhykkeellä Europe/Bratislava.','Analysedaten und Quoten gespeichert: {time}. Danach werden nur Spielstände bis 08:00 Uhr in der Zeitzone Europe/Bratislava aktualisiert.'],
 ['Dnes má ľad voľno','No action on the ice today','Dnes má led volno','Isen vilar i dag','Jäällä on tänään vapaapäivä','Heute ruht das Eis'],
 ['V programe nie sú zápasy základnej časti NHL. Ďalšie dni nájdeš cez výber dátumu.','No NHL regular-season games are scheduled. Use the date selector to browse other days.','V programu nejsou zápasy základní části NHL. Další dny najdeš ve výběru data.','Inga NHL-grundseriematcher är schemalagda. Välj ett annat datum för fler dagar.','Ohjelmassa ei ole NHL:n runkosarjaotteluita. Valitse toinen päivä päivämäärävalitsimesta.','Es sind keine NHL-Hauptrundenspiele angesetzt. Weitere Tage findest du über die Datumsauswahl.'],
 ['Čaká na finálny výber.','Awaiting final pick.','Čeká na finální tip.','Väntar på slutligt tips.','Odottaa lopullista vihjettä.','Wartet auf den endgültigen Tipp.'],
 ['SPRÁVNY TIP','WINNING PICK','SPRÁVNÝ TIP','VINNANDE TIPS','OSUNUT VIHJE','RICHTIGER TIPP'],
 ['NESPRÁVNY TIP','LOSING PICK','NESPRÁVNÝ TIP','FÖRLORANDE TIPS','VÄÄRÄ VIHJE','FALSCHER TIPP'],
 ['NA HRANICI · PUSH','PUSH · STAKE RETURNED','NA HRANICI · PUSH','PUSH · INSATSEN ÅTER','PUSH · PANOS PALAUTETAAN','PUSH · EINSATZ ZURÜCK'],
 ['VÝSLEDOK SA NEDÁ URČIŤ','RESULT UNAVAILABLE','VÝSLEDEK NELZE URČIT','RESULTATET KAN INTE AVGÖRAS','TULOSTA EI VOIDA MÄÄRITTÄÄ','ERGEBNIS NICHT BESTIMMBAR'],
 ['Podklady sa zmenili – výber vyžaduje nové overenie.','The data changed – this pick needs review.','Podklady se změnily – tip vyžaduje nové ověření.','Underlaget har ändrats – tipset behöver granskas igen.','Tiedot muuttuivat – vihje on tarkistettava uudelleen.','Die Datengrundlage hat sich geändert – der Tipp muss erneut geprüft werden.'],
 ['BEZ TIPU / NA NOVÉ OVERENIE','NO PICK / REVIEW REQUIRED','BEZ TIPU / K NOVÉMU OVĚŘENÍ','INGET TIPS / NY GRANSKNING','EI VIHJETTÄ / TARKISTETTAVA','KEIN TIPP / ERNEUT PRÜFEN'],
 ['BEZ TIPU','NO PICK','BEZ TIPU','INGET TIPS','EI VIHJETTÄ','KEIN TIPP'],
 ['Bez aktuálneho výberu v pásme 1,18–1,35.','No current pick in the 1.18–1.35 odds range.','Bez aktuálního tipu v pásmu 1,18–1,35.','Inget aktuellt tips i oddsintervallet 1,18–1,35.','Ei nykyistä vihjettä kerroinvälillä 1,18–1,35.','Kein aktueller Tipp im Quotenbereich 1,18–1,35.'],
 ['VÝBER / NA NOVÉ OVERENIE','PICK / REVIEW REQUIRED','TIP / K NOVÉMU OVĚŘENÍ','TIPS / NY GRANSKNING','VIHJE / TARKISTETTAVA','TIPP / ERNEUT PRÜFEN'],
 ['PREDBEŽNÝ TIP','PROVISIONAL PICK','PŘEDBĚŽNÝ TIP','PRELIMINÄRT TIPS','ALUSTAVA VIHJE','VORLÄUFIGER TIPP'],
 ['TIP','PICK','TIP','TIPS','VIHJE','TIPP'],
 ['Odhad kurzu ≈{price}','Estimated odds ≈{price}','Odhad kurzu ≈{price}','Uppskattat odds ≈{price}','Arvioitu kerroin ≈{price}','Geschätzte Quote ≈{price}'],
 ['Kurz {price}','Odds {price}','Kurz {price}','Odds {price}','Kerroin {price}','Quote {price}'],
 ['Približná cena odvodená z kurzov, nie overená ponuka bookmakera.','Approximate odds derived from market anchors, not a verified bookmaker offer.','Přibližný kurz odvozený z tržních kurzů, nikoli ověřená nabídka sázkové kanceláře.','Ungefärligt odds härlett från marknadsodds, inget verifierat erbjudande från ett spelbolag.','Markkinakertoimista johdettu arvio, ei vedonvälittäjän vahvistettu tarjous.','Aus Marktquoten abgeleitete Schätzung, kein verifiziertes Buchmacherangebot.'],
 ['Overený kurz so zdrojom a časom kontroly.','Verified odds with a source and check time.','Ověřený kurz se zdrojem a časem kontroly.','Verifierat odds med källa och kontrolltid.','Vahvistettu kerroin, jolla on lähde ja tarkistusaika.','Verifizierte Quote mit Quelle und Prüfzeitpunkt.'],
 ['nízka','low','nízká','låg','matala','niedrig'],['stredná','moderate','střední','måttlig','kohtalainen','mittel'],['vysoká','high','vysoká','hög','korkea','hoch'],['neurčená','unspecified','neurčená','ej angiven','määrittelemätön','nicht angegeben'],
 ['Confidence: {quality}','Confidence: {quality}','Důvěra: {quality}','Tillförlitlighet: {quality}','Luottamus: {quality}','Vertrauen: {quality}'],
 ['Kvalita analytických podkladov a záveru, nie vypočítaná pravdepodobnosť alebo garantovaná úspešnosť.','Quality of the analytical evidence and conclusion, not a calculated probability or guaranteed success rate.','Kvalita analytických podkladů a závěru, nikoli vypočtená pravděpodobnost nebo zaručená úspěšnost.','Kvaliteten på analysunderlaget och slutsatsen, inte en beräknad sannolikhet eller garanterad träffsäkerhet.','Analyysin aineiston ja johtopäätöksen laatu, ei laskettu todennäköisyys tai taattu osumatarkkuus.','Qualität der analytischen Belege und Schlussfolgerung, keine berechnete Wahrscheinlichkeit oder garantierte Trefferquote.'],
 ['Zdôvodnenie','Reasoning','Zdůvodnění','Motivering','Perustelut','Begründung'],
 ['SEČ/SELČ','CET/CEST','SEČ/SELČ','CET/CEST','CET/CEST','MEZ/MESZ'],
 ['UKONČENÉ','FINAL','UKONČENO','SLUT','PÄÄTTYNYT','BEENDET'],
 ['PREBIEHA','LIVE','PROBÍHÁ','PÅGÅR','KÄYNNISSÄ','LIVE'],
 ['ODLOŽENÉ','POSTPONED','ODLOŽENO','UPPSKJUTEN','SIIRRETTY','VERSCHOBEN'],
 ['RESEARCH','RESEARCH','REŠERŠE','RESEARCH','TAUSTATUTKIMUS','RECHERCHE'],
 ['KONEČNÉ SKÓRE','FINAL SCORE','KONEČNÉ SKÓRE','SLUTRESULTAT','LOPPUTULOS','ENDSTAND'],
 ['PRIEBEŽNÉ SKÓRE','LIVE SCORE','PRŮBĚŽNÉ SKÓRE','AKTUELLT RESULTAT','TILANNE','SPIELSTAND'],
 ['PP','OT','PP','ÖT','JA','NV'],['SN','SO','SN','STR','VL','PS'],
 ['PREDĹŽENIE','OVERTIME','PRODLOUŽENÍ','ÖVERTID','JATKOAIKA','VERLÄNGERUNG'],
 ['NÁJAZDY','SHOOTOUT','NÁJEZDY','STRAFFLÄGGNING','VOITTOLAUKAUKSET','PENALTYSCHIESSEN'],
 ['{period}. tretina','Period {period}','{period}. třetina','Period {period}','{period}. erä','{period}. Drittel'],
 ['Brankári {count}/2 potvrdení','Goalies {count}/2 confirmed','Brankáři {count}/2 potvrzeni','Målvakter {count}/2 bekräftade','Maalivahdit {count}/2 vahvistettu','Torhüter {count}/2 bestätigt'],
 ['{found}/{total} dostupných polí','{found}/{total} fields available','{found}/{total} dostupných polí','{found}/{total} tillgängliga fält','{found}/{total} kenttää saatavilla','{found}/{total} Felder verfügbar'],
 ['Analytické údaje','Analytical data','Analytické údaje','Analysdata','Analyysitiedot','Analysedaten'],
 ['Presné pôvodné podklady analýzy sa nepodarilo obnoviť. Aktuálne štatistiky ich nenahrádzajú.','The original analysis snapshot could not be restored. Current statistics are not substituted.','Přesné původní podklady analýzy se nepodařilo obnovit. Aktuální statistiky je nenahrazují.','Det ursprungliga analysunderlaget kunde inte återställas. Aktuell statistik används inte som ersättning.','Alkuperäistä analyysiaineistoa ei voitu palauttaa. Nykyisiä tilastoja ei käytetä sen tilalla.','Die ursprüngliche Analysedatengrundlage konnte nicht wiederhergestellt werden. Aktuelle Statistiken ersetzen sie nicht.'],
 ['Doplňujúci research','Additional research','Doplňující rešerše','Ytterligare research','Täydentävä taustatutkimus','Ergänzende Recherche'],
 ['Góly spolu','Total goals','Góly celkem','Totalt antal mål','Maalit yhteensä','Gesamttore'],
 ['Tímové góly','Team goals','Týmové góly','Lagets mål','Joukkueen maalit','Teamtore'],
 ['Handicap','Handicap','Handicap','Handicap','Tasoitus','Handicap'],
 ['Víťaz vrátane OT','Winner incl. OT','Vítěz včetně prodloužení','Vinnare inkl. övertid','Voittaja sis. jatkoajan','Sieger inkl. Verlängerung'],
 ['Výsledok 60 min','Result after 60 min','Výsledek po 60 min','Resultat efter 60 min','Tulos 60 min jälkeen','Ergebnis nach 60 Min.'],
 ['Dvojtip 60 min','Double chance · 60 min','Dvojtip 60 min','Dubbelchans · 60 min','Kaksoismahdollisuus · 60 min','Doppelte Chance · 60 Min.'],
 ['domáci','home','domácí','hemmalag','kotijoukkue','Heimteam'],['hostia','away','hosté','bortalag','vierasjoukkue','Auswärtsteam'],['remíza','draw','remíza','oavgjort','tasapeli','Unentschieden'],['nad','over','nad','över','yli','über'],['pod','under','pod','under','alle','unter'],
 ['Prvý spracovaný deň sa objaví tu.','The first processed day will appear here.','První zpracovaný den se objeví zde.','Den första behandlade dagen visas här.','Ensimmäinen käsitelty päivä näkyy täällä.','Der erste verarbeitete Tag erscheint hier.'],
 ['{count} zápasov · {state}','{count} games · {state}','{count} zápasů · {state}','{count} matcher · {state}','{count} ottelua · {state}','{count} Spiele · {state}'],
 ['Archív je dočasne nedostupný.','Archive temporarily unavailable.','Archiv je dočasně nedostupný.','Arkivet är tillfälligt otillgängligt.','Arkisto ei ole tilapäisesti saatavilla.','Archiv vorübergehend nicht verfügbar.']
 ];
 const codes=Object.keys(languages),catalog=Object.fromEntries(rows.map(row=>[row[0],Object.fromEntries(codes.map((code,index)=>[code,row[index]]))]));
 let language='sk',changed=()=>{};
 const query=new URLSearchParams(location.search).get('lang');let saved=null;
 try{saved=localStorage.getItem('nhl-language');}catch{}
 language=Object.hasOwn(languages,query)?query:Object.hasOwn(languages,saved)?saved:'sk';
 function t(source,values={}){const text=catalog[source]?.[language]??source;return String(text).replace(/\{(\w+)\}/g,(match,key)=>Object.hasOwn(values,key)?String(values[key]):match);}
 function apply(){
  document.documentElement.lang=language;
  for(const node of document.querySelectorAll('[data-i18n]'))node.textContent=t(node.dataset.i18n);
  for(const attribute of ['aria-label','content'])for(const node of document.querySelectorAll(`[data-i18n-${attribute}]`))node.setAttribute(attribute,t(node.getAttribute(`data-i18n-${attribute}`)));
  const toggle=document.getElementById('language-toggle');if(!toggle)return;
  toggle.setAttribute('aria-label',`${t('Jazyk stránky')}: ${languages[language].name}`);
  toggle.querySelector('img').src=`assets/flags/${languages[language].flag}.svg`;
  toggle.querySelector('.language-name').textContent=languages[language].name;
  toggle.querySelector('.language-code').textContent=language.toUpperCase();
  for(const button of document.querySelectorAll('[data-language]'))button.setAttribute('aria-pressed',String(button.dataset.language===language));
 }
 function close(){const toggle=document.getElementById('language-toggle'),menu=document.getElementById('language-menu');if(menu)menu.hidden=true;if(toggle)toggle.setAttribute('aria-expanded','false');}
 function setLanguage(code){
  if(!Object.hasOwn(languages,code))return;
  const different=language!==code;language=code;
  try{localStorage.setItem('nhl-language',code);}catch{}
  const url=new URL(location.href);url.searchParams.set('lang',code);history.replaceState(null,'',url.pathname+url.search+url.hash);
  close();apply();if(different)changed();
 }
 function init(onChange){
  changed=onChange;const host=document.getElementById('language-switcher');
  if(host){
   const toggle=document.createElement('button');toggle.id='language-toggle';toggle.className='language-toggle';toggle.type='button';toggle.setAttribute('aria-expanded','false');toggle.setAttribute('aria-controls','language-menu');
   const flag=document.createElement('img');flag.className='language-flag';flag.alt='';flag.width=24;flag.height=16;
   const name=document.createElement('span');name.className='language-name';const code=document.createElement('span');code.className='language-code';code.setAttribute('aria-hidden','true');
   const arrow=document.createElementNS('http://www.w3.org/2000/svg','svg');arrow.classList.add('language-arrow');arrow.setAttribute('viewBox','0 0 16 16');arrow.setAttribute('width','14');arrow.setAttribute('height','14');arrow.setAttribute('aria-hidden','true');arrow.setAttribute('focusable','false');const chevron=document.createElementNS('http://www.w3.org/2000/svg','path');chevron.setAttribute('d','M4 6l4 4 4-4');arrow.append(chevron);toggle.append(flag,name,code,arrow);
   const menu=document.createElement('div');menu.id='language-menu';menu.className='language-menu';menu.hidden=true;menu.setAttribute('role','group');menu.setAttribute('data-i18n-aria-label','Jazyk stránky');
   for(const [code,definition] of Object.entries(languages)){
    const button=document.createElement('button');button.type='button';button.dataset.language=code;button.lang=code;
    const icon=flag.cloneNode();icon.src=`assets/flags/${definition.flag}.svg`;
    const label=document.createElement('span');label.textContent=definition.name;
    const check=document.createElement('span');check.className='language-check';check.textContent='✓';check.setAttribute('aria-hidden','true');button.append(icon,label,check);button.onclick=()=>{setLanguage(code);toggle.focus();};menu.append(button);
   }
   toggle.onclick=()=>{const open=menu.hidden;menu.hidden=!open;toggle.setAttribute('aria-expanded',String(open));};
   toggle.onkeydown=event=>{if(event.key==='ArrowDown'){event.preventDefault();menu.hidden=false;toggle.setAttribute('aria-expanded','true');menu.querySelector('[aria-pressed="true"]').focus();}};
   menu.onkeydown=event=>{if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();const buttons=[...menu.querySelectorAll('button')],at=buttons.indexOf(document.activeElement);buttons[event.key==='Home'?0:event.key==='End'?buttons.length-1:(at+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length].focus();}};
   host.append(toggle,menu);
   document.addEventListener('pointerdown',event=>{if(!host.contains(event.target))close();});
   document.addEventListener('focusin',event=>{if(!host.contains(event.target))close();});
   document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!menu.hidden){close();toggle.focus();}});
  }
  apply();
 }
 return {t,init,setLanguage,get language(){return language;},get locale(){return languages[language].locale;},languages,catalog};
})();
