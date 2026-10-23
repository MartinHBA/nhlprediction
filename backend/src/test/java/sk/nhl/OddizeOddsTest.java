package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.net.http.HttpHeaders;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class OddizeOddsTest {
    static final String DAY="2026-10-23",EVENT="202610230CHI";
    static final Instant NOW=Instant.parse("2026-10-23T19:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static ObjectNode data(){
        ObjectNode d=DataCollectorTest.template(DAY,"2026-10-24T00:10:00Z");d.put("as_of",NOW.toString());
        ObjectNode g=(ObjectNode)d.path("games").get(0);((ObjectNode)g.path("away")).put("name","MTL");((ObjectNode)g.path("home")).put("name","CHI");
        for(String side:List.of("home","away"))for(String group:List.of("prior","current","goalie","roster"))((ObjectNode)g.path(side).path(group).path("source")).put("checked_at",NOW.minusSeconds(120).toString());return d;
    }
    static ObjectNode price(String book,String type,String side,double line){return obj("book",book,"book_name",book,"offer_type",type,"team",side,"line",line,"american_odds","-400","timestamp",NOW.minusSeconds(60).toString(),"ts",NOW.minusSeconds(60).getEpochSecond());}
    static ObjectNode event(){return obj("event_id",EVENT,"team1","MON","team2","CHI","start_date","2026-10-23T20:00:00-04:00","odds",arr().add(price("fd","ml","MON",0).putNull("line")).add(price("mystery","t","Under",6.5)));}
    static ObjectNode response(String path){
        if(path.startsWith("/odds/"))return obj("sport","nhl","events",arr().add(event()));
        if(path.startsWith("/props/"))return obj("sport","nhl","prop_type","tt_goals","events",arr().add(event().put("start_date","2026-10-23 20:00:00-04:00").set("players",arr().add(obj("player_name","MON","all_odds",arr().add(price("fd","tt","Under",3.5)))))));
        String type=path.endsWith("alt_t")?"alt_t":"alt_s";return obj("sport","nhl","event_id",EVENT,"alt_type",type,"lines",arr().add(obj("line",type.equals("alt_t")?7.5:1.5,"prices",arr().add(price("mgm",type,type.equals("alt_t")?"Under":"MON",type.equals("alt_t")?7.5:1.5)))));
    }
    static OddizeOdds importer(MemoryStore store,AtomicInteger calls,java.util.function.Function<String,ObjectNode> responses){
        return new OddizeOdds(new OddizeClient(store,CLOCK,"test-secret",r->{calls.incrementAndGet();assertEquals("test-secret",r.headers().firstValue("X-API-Key").orElseThrow());String path=r.uri().toString().substring(OddizeClient.BASE.length());return new OddizeProbe.Upstream(200,HttpHeaders.of(Map.of(),(a,b)->true),responses.apply(path).toString());},"test",false),CLOCK);
    }
    @Test void importsRealMainAlternatesAndBulkTeamTotalsWithRulesAndNoInventedTypes(){
        AtomicInteger calls=new AtomicInteger();var result=importer(new MemoryStore(),calls,OddizeOddsTest::response).collect(DAY,data());
        assertEquals(4,calls.get());assertEquals(7,result.path("odds_import").path("credits_used_this_attempt").asInt());assertEquals("READY",text(result,"odds_import.state"));
        JsonNode d=result.path("dataset"),g=d.path("games").get(0);assertTrue(Checks.validate(d).isEmpty(),Checks.validate(d).toString());assertTrue(Checks.audit(d).path("issues").isEmpty(),Checks.audit(d).toString());
        assertEquals(5,g.path("markets").size());assertEquals(4,MarketQuotes.all(g,NOW).valueStream().filter(q->q.path("selectable").asBoolean()).count());
        assertEquals("unavailable",text(g.path("checks").path("market_searches").path("regulation_1x2"),"status"));
        assertTrue(g.path("markets").valueStream().anyMatch(q->text(q,"score_basis").equals("unknown")&&!q.path("settlement_verified").asBoolean()));
        for(JsonNode q:g.path("markets"))assertEquals(NOW.minusSeconds(60).toString(),text(q,"observed_at"));
    }
    @Test void durableCacheReusesSuccessfulRequestsAcrossNewClientsAndKeepsOriginalTimestamps(){
        MemoryStore store=new MemoryStore();AtomicInteger calls=new AtomicInteger();var first=importer(store,calls,OddizeOddsTest::response).collect(DAY,data());var second=importer(store,calls,p->{throw new AssertionError("Must reuse cached response");}).collect(DAY,data());
        assertEquals(4,calls.get());assertEquals(0,second.path("odds_import").path("http_requests_this_attempt").asInt());assertEquals(first.path("dataset").path("games"),second.path("dataset").path("games"));
        for(String key:store.keys("cache/oddize/"))assertFalse(store.get(key).toString().contains("test-secret"));
    }
    @Test void partialFailureRetriesOnlyMissingRequestAndRejectsBadResponsesBeforeCaching(){
        MemoryStore store=new MemoryStore();AtomicInteger calls=new AtomicInteger();var first=importer(store,calls,p->p.endsWith("alt_s")?obj("sport","nhl","event_id","wrong","alt_type","alt_s","lines",arr()):response(p)).collect(DAY,data());
        assertFalse(first.path("odds_import").path("complete_snapshot").asBoolean());assertEquals(4,calls.get());
        var next=importer(store,calls,OddizeOddsTest::response).collect(DAY,data());assertEquals(5,calls.get());assertTrue(next.path("odds_import").path("complete_snapshot").asBoolean());
    }
    @Test void exactOrientationAndStartAreRequiredAndOtherDaysNeverLeak(){
        for(String bad:List.of("orientation","time","day")){
            AtomicInteger calls=new AtomicInteger();var result=importer(new MemoryStore(),calls,p->{var r=response(p);var e=(ObjectNode)r.path("events").get(0);if(bad.equals("orientation"))e.put("team1","CHI").put("team2","MON");else e.put("start_date",bad.equals("time")?"2026-10-23T21:00:00-04:00":"2026-10-24T20:00:00-04:00");return r;}).collect(DAY,data());
            assertEquals(0,result.path("odds_import").path("market_count").asInt());assertEquals(1,calls.get());
        }
    }
    @Test void staleQuotesAreNotRelabelledFreshAndBadAlternateLinesAreRejected(){
        var result=importer(new MemoryStore(),new AtomicInteger(),p->{var r=response(p);if(p.startsWith("/odds/"))for(JsonNode q:r.path("events").get(0).path("odds"))((ObjectNode)q).put("timestamp",NOW.minusSeconds(10800).toString()).put("ts",NOW.minusSeconds(10800).getEpochSecond());if(p.endsWith("alt_t"))((ObjectNode)r.path("lines").get(0)).put("line",9.5);return r;}).collect(DAY,data());
        var quotes=MarketQuotes.all(result.path("dataset").path("games").get(0),NOW);assertEquals(2,quotes.valueStream().filter(q->q.path("selectable").asBoolean()).count());
        assertTrue(quotes.valueStream().noneMatch(q->text(q,"offer_type").equals("alt_t")));assertTrue(quotes.valueStream().filter(q->text(q,"kind").equals("moneyline")).noneMatch(q->q.path("selectable").asBoolean()));
    }
    @Test void daily1945TriggerTracksDstAndHasNoSecondDailyCollection(){
        assertFalse(OddizeOdds.due("2026-10-23",Instant.parse("2026-10-23T17:44:00Z")));assertTrue(OddizeOdds.due("2026-10-23",Instant.parse("2026-10-23T17:45:00Z")));
        assertFalse(OddizeOdds.due("2026-12-23",Instant.parse("2026-12-23T18:44:00Z")));assertTrue(OddizeOdds.due("2026-12-23",Instant.parse("2026-12-23T18:45:00Z")));
        assertTrue(OddizeOdds.snapshotWindow(Instant.parse("2026-10-23T17:45:00Z")));assertFalse(OddizeOdds.snapshotWindow(Instant.parse("2026-10-23T18:45:00Z")));assertTrue(OddizeOdds.snapshotWindow(Instant.parse("2026-12-23T18:45:00Z")));
        MemoryStore store=new MemoryStore();List<ObjectNode> jobs=new ArrayList<>();DataCollector collector=new DataCollector(store,u->{throw new AssertionError();},CLOCK);collector.odds=importer(store,new AtomicInteger(),OddizeOddsTest::response);
        var w=new Workflow(store,d->data(),jobs::add,collector);store.put("days/"+DAY+".json",obj("day",DAY,"state","COLLECTING","revision",0,"dataset",data(),"official_games",data().path("games"),"odds_import",OddizeOdds.pending(DAY)));
        w.requestOdds(DAY,Instant.parse("2026-10-23T17:44:00Z"));assertTrue(jobs.isEmpty());w.requestOdds(DAY,NOW);w.requestOdds(DAY,NOW.plusSeconds(30));assertEquals(1,jobs.size());
        w.processImport(jobs.get(0));w.requestOdds(DAY,NOW.plusSeconds(3600));assertEquals(1,jobs.size());assertTrue(w.require(DAY).path("odds_import").path("complete_snapshot").asBoolean());
    }
    @Test void parsesRecordedProviderPayloadWithoutChangingItsOddsOrRules(){
        JsonNode fixture=resource("oddize-catalogue.json");var result=importer(new MemoryStore(),new AtomicInteger(),p->(ObjectNode)fixture.path(p.startsWith("/odds/")?"main":p.startsWith("/props/")?"team-totals":p.endsWith("alt_t")?"alt_t":"alt_s")).collect(DAY,data());
        assertEquals("READY",text(result,"odds_import.state"));assertTrue(Checks.validate(result.path("dataset")).isEmpty(),Checks.validate(result.path("dataset")).toString());
        var quotes=MarketQuotes.all(result.path("dataset").path("games").get(0),NOW);assertTrue(quotes.size()>100);assertTrue(quotes.valueStream().anyMatch(q->q.path("selectable").asBoolean()));
        for(JsonNode q:quotes)if(q.path("selectable").asBoolean()){assertEquals("official_final",text(q,"score_basis"));assertTrue(Set.of("fd","mgm","dk").contains(text(q,"bookmaker_code")));assertEquals("QUOTED",text(q,"odds_type"));}
    }
    @Test void longImportRebasesOntoConcurrentResearchAndCannotOverwriteIt(){
        MemoryStore store=new MemoryStore();List<ObjectNode> jobs=new ArrayList<>();DataCollector collector=new DataCollector(store,u->{throw new AssertionError();},CLOCK);
        var actual=importer(store,new AtomicInteger(),OddizeOddsTest::response);
        collector.odds=(day,input)->{
            ObjectNode result=actual.collect(day,input),latest=store.get("days/"+DAY+".json");latest.put("revision",8);
            ((ObjectNode)latest.path("dataset").path("games").get(0).path("home").path("context")).put("rest_days",3);
            store.put("days/"+DAY+".json",latest);return result;
        };
        var w=new Workflow(store,d->data(),jobs::add,collector);store.put("days/"+DAY+".json",obj("day",DAY,"state","COLLECTING","revision",0,"dataset",data(),"official_games",data().path("games"),"odds_import",OddizeOdds.pending(DAY)));
        w.requestOdds(DAY,NOW);w.processImport(jobs.get(0));var saved=w.require(DAY);
        assertEquals(9,saved.path("revision").asInt());assertEquals(3,saved.path("dataset").path("games").get(0).path("home").path("context").path("rest_days").asInt());assertFalse(saved.path("dataset").path("games").get(0).path("markets").isEmpty());
    }
    @Test void batchCutoffIncludesEvidenceReceivedAfterTheBatchStarted(){
        AtomicReference<Instant> time=new AtomicReference<>(NOW);
        Clock moving=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time.get();}};
        var client=new OddizeClient(new MemoryStore(),moving,"key",r->{time.updateAndGet(t->t.plusSeconds(2));return new OddizeProbe.Upstream(200,HttpHeaders.of(Map.of(),(a,b)->true),response(r.uri().toString().substring(OddizeClient.BASE.length())).toString());},"test",false);
        var result=new OddizeOdds(client,moving).collect(DAY,data());
        assertEquals(NOW.plusSeconds(8).toString(),text(result,"dataset.as_of"));assertEquals(4,result.path("odds_import").path("selectable_count").asInt());
        assertTrue(Checks.validate(result.path("dataset")).isEmpty(),Checks.validate(result.path("dataset")).toString());
    }
}
