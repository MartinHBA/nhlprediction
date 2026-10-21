package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class PublicationTest {
    TipsTest f;
    @BeforeEach void init(){f=new TipsTest();f.setup();}
    void publish(){f.workflow.postTips(f.date,f.body(true));}
    @Test void frozenPublicationRetainsDecisionsCountsAndInputsAcrossAgeAndWorkingChanges(){
        publish();JsonNode original=f.workflow.read(f.date).path("games").deepCopy();ObjectNode working=f.workflow.require(f.date);
        ((ObjectNode)working.path("dataset").path("games").get(0).path("home").path("current")).put("xgf_5v5_per60",4.321);
        ((ObjectNode)working.path("dataset").path("games").get(0)).put("status","live").set("markets",arr());f.store.put("days/"+f.date+".json",working);
        var result=f.workflow.read(f.date);assertEquals(original,result.path("games"));assertEquals(3,result.path("final_tips").path("reviewed_count").asInt());assertEquals(1,result.path("final_tips").path("tip_count").asInt());
        assertEquals("PUBLISHED",text(Tips.view(working,f.now.plusSeconds(86400)),"state"));assertEquals("handicap",text(result.path("final_tips").path("games").get(0),"candidate.kind"));
    }
    @Test void timerAfterPublicationOnlyFetchesScoresAndLeavesAnalysisRevisionUntouched(){
        publish();AtomicInteger stats=new AtomicInteger(),scoreCalls=new AtomicInteger();ObjectNode prior=f.workflow.require(f.date);JsonNode inputs=prior.path("dataset").deepCopy();
        DataCollector collector=new DataCollector(f.store,url->{stats.incrementAndGet();throw new AssertionError("Frozen day must not fetch statistics or prices");},Clock.systemUTC());
        collector.odds=new NhlOdds(url->{stats.incrementAndGet();throw new AssertionError("Frozen day must not fetch odds");},Clock.systemUTC());
        GameScores scores=new GameScores(url->{scoreCalls.incrementAndGet();ArrayNode games=arr();for(JsonNode g:inputs.path("games"))games.add(obj("id",text(g,"game_id"),"startTimeUTC",text(g,"starts_at"),"gameState","OFF","gameScheduleState","OK","homeTeam",obj("abbrev",text(g,"home.name"),"score",6),"awayTeam",obj("abbrev",text(g,"away.name"),"score",3),"gameOutcome",obj("lastPeriodType","REG")));return obj("currentDate",f.date,"games",games);});
        Workflow w=new Workflow(f.store,d->{throw new AssertionError("No new schedule");},j->{throw new AssertionError("No source import");},collector,scores);
        w.eveningTick(Instant.parse("2099-01-02T06:00:00Z"));var current=w.require(f.date);
        assertEquals(0,stats.get());assertEquals(1,scoreCalls.get());assertEquals(prior.path("revision"),current.path("revision"));assertEquals(inputs,current.path("dataset"));
        assertEquals("WIN",text(w.read(f.date).path("final_tips").path("games").get(0),"settlement.state"));assertEquals(1,w.read(f.date).path("final_tips").path("tip_count").asInt());
        assertFalse(w.readLive(f.date).has("games"));assertFalse(w.readLive(f.date).has("coverage"));
    }
    @Test void adHocStartRefreshesWorkingDataButOnlyNextPublicationReplacesPublicSnapshot(){
        publish();var previous=f.workflow.read(f.date).path("games").deepCopy();f.workflow.start(f.date);int revision=f.workflow.require(f.date).path("revision").asInt();f.workflow.start(f.date);assertEquals(revision,f.workflow.require(f.date).path("revision").asInt());
        ObjectNode game=f.workflow.require(f.date).path("dataset").path("games").get(0).deepCopy();((ObjectNode)game.path("home").path("current")).put("xgf_5v5_per60",4.321);
        f.workflow.saveGame(f.date,game,arr(),revision);assertEquals(previous,f.workflow.read(f.date).path("games"));
        assertEquals(4.321,f.workflow.candidates(f.date,"").path("games").get(0).path("home").path("current").path("xgf_5v5_per60").asDouble());
        f.workflow.finish(f.date);f.day=f.workflow.require(f.date);f.workflow.postTips(f.date,f.body(true));
        assertEquals(4.321,f.workflow.read(f.date).path("games").get(0).path("home").path("current").path("xgf_5v5_per60").asDouble());
        assertFalse(f.workflow.read(f.date).path("publication").path("refresh_in_progress").asBoolean());
    }
    @Test void explicitGptStartQueuesOneRefreshEvenAfterPreviouslySuccessfulImport(){
        ((ArrayNode)f.day.path("dataset").path("evidence")).add(obj("id","auto-refresh-fixture","url","https://example.org/native-stats","checked_at",f.now.minusSeconds(30).toString()));
        ((ObjectNode)f.day.path("dataset").path("games").get(0).path("checks").path("fields").path("home.current.xgf_5v5_per60")).set("evidence_ids",arr().add("auto-refresh-fixture"));f.store.put("days/"+f.date+".json",f.day);
        publish();ObjectNode saved=f.workflow.require(f.date);saved.set("enrichment",obj("version",DataCollector.VERSION,"state","DONE","updated_at",Instant.now().toString(),"warnings",arr()));f.store.put("days/"+f.date+".json",saved);
        List<ObjectNode> jobs=new ArrayList<>();AtomicInteger calls=new AtomicInteger();
        DataCollector collector=new DataCollector(f.store,url->{calls.incrementAndGet();throw new IllegalStateException("HTTP 503 from a controlled fixture source");},Clock.systemUTC());
        Workflow w=new Workflow(f.store,d->{throw new AssertionError("Official schedule must remain");},jobs::add,collector);
        assertEquals("PREFILLING",text(w.start(f.date),"state"));w.start(f.date);assertEquals(1,jobs.size());w.processImport(jobs.getFirst());
        assertTrue(calls.get()>0);assertTrue(w.require(f.date).path("dataset").path("games").get(0).path("home").path("current").path("xgf_5v5_per60").isNull());
        assertTrue(w.read(f.date).path("games").get(0).path("home").path("current").path("xgf_5v5_per60").isNumber());assertEquals("PUBLISHED",text(w.read(f.date),"final_tips.state"));
    }
    @Test void exactLegacySnapshotIsRecoveredFromHistoryWithoutReplacingGptDecisions(){
        publish();var archived=f.workflow.require(f.date);JsonNode decisions=archived.path("final_tips").path("games").deepCopy();
        ((ObjectNode)archived.path("final_tips")).remove(List.of("dataset_snapshot","odds_import_snapshot","enrichment_snapshot"));f.store.put("history/"+f.date+"/8.json",archived);
        var changed=archived.deepCopy();changed.put("revision",122);((ObjectNode)changed.path("dataset").path("games").get(0)).put("status","live").set("markets",arr());((ObjectNode)changed.path("dataset").path("games").get(0).path("home").path("current")).put("xgf_5v5_per60",4.321);f.store.put("days/"+f.date+".json",changed);
        var restored=f.workflow.require(f.date);assertTrue(Publication.frozen(restored));assertEquals(archived.path("dataset"),Publication.data(restored));assertEquals(decisions,restored.path("final_tips").path("games"));assertEquals("RECOVERED",text(restored,"final_tips.snapshot_recovery.state"));
    }
    @Test void laterNoBetForStartedGameCannotEraseItsEarlierTip(){
        ObjectNode game=(ObjectNode)f.day.path("dataset").path("games").get(0);game.put("starts_at",f.now.plusSeconds(60).toString());f.store.put("days/"+f.date+".json",f.day);publish();
        ObjectNode latest=f.workflow.require(f.date);f.day=latest;ObjectNode args=f.body(false);Instant later=f.now.plusSeconds(61);args.put("analysis_id",Tips.analysisId(latest,later));
        ObjectNode result=Tips.prepare(latest,args,later);Publication.capture(result,latest);
        assertEquals("TIP",text(result.path("games").get(0),"decision"));assertEquals("handicap",text(result.path("games").get(0),"candidate.kind"));assertTrue(result.path("games").get(0).path("retained_previous_analysis").asBoolean());
    }
}
