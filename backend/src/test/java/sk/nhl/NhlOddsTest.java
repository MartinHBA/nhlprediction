package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class NhlOddsTest {
    static final Instant NOW=Instant.now().minusSeconds(1),START=NOW.plusSeconds(18000);
    static final String DAY=NhlOdds.day(START);
    static ObjectNode quote(String type,double value,String qualifier){return obj("description",type,"value",value,"qualifier",qualifier);}
    static ObjectNode feed(){return obj("currentOddsDate",DAY,"lastUpdatedUTC",NOW.minusSeconds(60).toString(),"bettingPartner",obj("partnerId",9,"name","DraftKings"),"games",List.of(obj("gameId",123,"gameType",2,"startTimeUTC",START.toString(),"homeTeam",obj("abbrev","BOS","odds",List.of(quote("MONEY_LINE_2_WAY",-200,""),quote("MONEY_LINE_3_WAY",120,""),quote("MONEY_LINE_3_WAY",350,"Draw"),quote("OVER_UNDER",105,"O5.5"),quote("PUCK_LINE",-110,"-1.5"),quote("MONEY_LINE_2_WAY_TNB",-1000,""))),"awayTeam",obj("abbrev","COL","odds",List.of(quote("MONEY_LINE_2_WAY",170,""),quote("MONEY_LINE_3_WAY",210,""),quote("MONEY_LINE_3_WAY",350,"Draw"),quote("OVER_UNDER",-125,"U5.5"),quote("PUCK_LINE",-110,"+1.5"))))));}
    static ObjectNode data(){ObjectNode data=DataCollectorTest.template(DAY,START.toString());data.put("as_of",NOW.toString());for(JsonNode g:data.path("games"))for(String side:List.of("home","away"))for(String group:List.of("current","prior","goalie","roster"))((ObjectNode)g.path(side).path(group).path("source")).put("checked_at",NOW.minusSeconds(120).toString());return data;}
    static NhlOdds importer(JsonNode feed){return new NhlOdds(url->new DataCollector.Download(feed.toString(),url,NOW.toString()),Clock.fixed(NOW,ZoneOffset.UTC));}
    @Test void importsAllFourSupportedTypesWithActualPublicationAndDeduplicatedDraw(){
        var result=importer(feed()).collect(DAY,data());var game=result.path("dataset").path("games").get(0);assertEquals(9,game.path("markets").size());assertEquals("READY",text(result,"odds_import.state"));assertEquals(1,result.path("odds_import").path("covered_games").asInt());
        for(JsonNode market:game.path("markets")){assertEquals(NOW.minusSeconds(60).toString(),text(market,"source.checked_at"));assertFalse(text(market,"id").contains("TNB"));assertTrue(market.path("settlement_verified").asBoolean());}
        assertEquals(7,game.path("checks").path("market_searches").valueStream().filter(c->text(c,"status").equals("found")).count());
        assertEquals("unavailable",text(game.path("checks").path("market_searches").path("team_total_under"),"status"));assertTrue(Checks.validate(result.path("dataset")).isEmpty());assertTrue(Checks.audit(result.path("dataset")).path("issues").isEmpty());
    }
    @Test void convertsAmericanPricesWithoutGuessingDecimalOrMissingOdds(){assertEquals(1.5,NhlOdds.decimal(-200));assertEquals(2.7,NhlOdds.decimal(170));assertThrows(IllegalArgumentException.class,()->NhlOdds.decimal(1.9));assertThrows(IllegalArgumentException.class,()->NhlOdds.decimal(Double.NaN));}
    @Test void staleWrongDayAndFutureFeedCannotYieldPregameQuotes(){for(ObjectNode feed:List.of(feed().put("currentOddsDate",LocalDate.parse(DAY).minusDays(1).toString()),feed().put("lastUpdatedUTC",NOW.minusSeconds(7201).toString()),feed().put("lastUpdatedUTC",NOW.plusSeconds(1).toString()))){var result=importer(feed).collect(DAY,data());assertEquals("WAITING",text(result,"odds_import.state"));assertTrue(result.path("dataset").path("games").get(0).path("markets").isEmpty());}}
    @Test void exactGameIdAlsoRequiresTeamOrientationAndExactUtcStart(){
        for(String field:List.of("id","teams","time")){var f=feed();ObjectNode event=(ObjectNode)f.path("games").get(0);if(field.equals("id"))event.put("gameId",999);if(field.equals("teams"))((ObjectNode)event.path("homeTeam")).put("abbrev","COL");if(field.equals("time"))event.put("startTimeUTC",START.plusSeconds(60).toString());assertEquals(0,importer(f).collect(DAY,data()).path("odds_import").path("market_count").asInt());}
    }
    @Test void unavailableRefreshRemovesPreviousNativeQuotesAndNeverRelabelsThemAsFresh(){var imported=importer(feed()).collect(DAY,data());var failing=new NhlOdds(url->{throw new IllegalStateException("HTTP 503");},Clock.fixed(NOW.plusSeconds(10),ZoneOffset.UTC));var result=failing.collect(DAY,imported.path("dataset"));assertTrue(result.path("dataset").path("games").get(0).path("markets").isEmpty());assertEquals("ERROR",text(result,"odds_import.state"));}
    @Test void differentTotalLinesAndMissingCounterpartsAreNotCombined(){var f=feed();((ObjectNode)f.path("games").get(0).path("awayTeam").path("odds").get(3)).put("qualifier","U6.5");var markets=importer(f).collect(DAY,data()).path("dataset").path("games").get(0).path("markets");assertEquals(7,markets.size());assertTrue(markets.valueStream().noneMatch(m->text(m,"kind").equals("total")));}
    @Test void liveFeedAndAlreadyStartedOfficialGameAreExcluded(){var f=feed();((ObjectNode)f.path("games").get(0)).put("gameState","LIVE");assertEquals(0,importer(f).collect(DAY,data()).path("odds_import").path("market_count").asInt());var late=new NhlOdds(url->new DataCollector.Download(feed().toString(),url,START.toString()),Clock.fixed(START,ZoneOffset.UTC));var result=late.collect(DAY,data());assertEquals("live",text(result.path("dataset").path("games").get(0),"status"));assertTrue(result.path("dataset").path("games").get(0).path("markets").isEmpty());}
    @Test void duplicateEventsOrConflictingDrawRejectTheWholeEvent(){var f=feed();((ArrayNode)f.path("games")).add(f.path("games").get(0).deepCopy());assertEquals(0,importer(f).collect(DAY,data()).path("odds_import").path("market_count").asInt());f=feed();((ObjectNode)f.path("games").get(0).path("awayTeam").path("odds").get(2)).put("value",400);assertEquals(0,importer(f).collect(DAY,data()).path("odds_import").path("market_count").asInt());}
    @Test void pollingWindowFollowsBratislavaSummerWinterAndNhlMidnight(){
        assertFalse(NhlOdds.pollingWindow(Instant.parse("2026-10-20T15:30:00Z")));assertTrue(NhlOdds.pollingWindow(Instant.parse("2026-10-20T16:00:00Z")));assertFalse(NhlOdds.pollingWindow(Instant.parse("2026-10-21T04:00:00Z")));
        assertFalse(NhlOdds.pollingWindow(Instant.parse("2026-12-20T16:30:00Z")));assertTrue(NhlOdds.pollingWindow(Instant.parse("2026-12-20T17:00:00Z")));assertEquals("2026-10-20",NhlOdds.day(Instant.parse("2026-10-21T02:00:00Z")));
    }
    @Test void automaticRefreshPreservesResearchAndRequeuesOnlyAfterCompletedChecklist(){
        MemoryStore store=new MemoryStore();List<ObjectNode> jobs=new ArrayList<>();AtomicReference<JsonNode> source=new AtomicReference<>(feed());DataCollector collector=new DataCollector(store,url->{throw new IllegalStateException("No native network in fixture");},Clock.fixed(NOW,ZoneOffset.UTC));collector.odds=new NhlOdds(url->new DataCollector.Download(source.get().toString(),url,NOW.toString()),Clock.fixed(NOW,ZoneOffset.UTC));
        Workflow workflow=new Workflow(store,d->data(),jobs::add,collector);ObjectNode dataset=data();ObjectNode stored=obj("day",DAY,"state","COLLECTING","revision",0,"dataset",dataset,"official_games",dataset.path("games"),"report",null,"enrichment",obj("version",DataCollector.VERSION));store.put("days/"+DAY+".json",stored);
        workflow.refreshOdds(DAY);assertEquals(1,workflow.require(DAY).path("revision").asInt());assertTrue(jobs.isEmpty());assertEquals(9,workflow.require(DAY).path("dataset").path("games").get(0).path("markets").size());
        ObjectNode editable=workflow.require(DAY).path("dataset").path("games").get(0).deepCopy();((ObjectNode)editable.path("markets").get(0)).put("odds",5);assertThrows(IllegalArgumentException.class,()->workflow.saveGame(DAY,editable,arr(),1));
        ObjectNode ready=store.get("days/"+DAY+".json");ObjectNode demo=resource("demo.json").deepCopy();ObjectNode game=(ObjectNode)demo.path("games").get(0);game.put("game_id","123");game.put("starts_at",START.toString());((ObjectNode)game.path("home")).put("name","BOS");((ObjectNode)game.path("away")).put("name","COL");demo.set("games",arr().add(game));demo.set("expected_game_ids",arr().add("123"));demo.put("synthetic_data",false);demo.put("as_of",NOW.toString());ready.set("dataset",demo);ready.put("research_complete",true);ready.put("last_sims",1000);store.put("days/"+DAY+".json",ready);
        ready=store.get("days/"+DAY+".json");ObjectNode normalized=(ObjectNode)ready.path("dataset");for(JsonNode e:normalized.path("evidence"))((ObjectNode)e).put("checked_at",NOW.minusSeconds(120).toString());for(JsonNode g:normalized.path("games")){for(String side:List.of("home","away"))for(String group:List.of("current","prior","goalie","roster"))((ObjectNode)g.path(side).path(group).path("source")).put("checked_at",NOW.minusSeconds(120).toString());for(JsonNode m:g.path("markets"))((ObjectNode)m.path("source")).put("checked_at",NOW.minusSeconds(120).toString());}store.put("days/"+DAY+".json",ready);
        workflow.refreshOdds(DAY);assertEquals("QUEUED",text(workflow.require(DAY),"state"));assertEquals(1,jobs.size());assertEquals(1000,jobs.getFirst().path("sims").asInt());
    }
}
