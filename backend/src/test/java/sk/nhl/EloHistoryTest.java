package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;
import static sk.nhl.EloTest.*;

class EloHistoryTest {
    final MemoryStore store=new MemoryStore();
    ObjectNode empty(){return obj("as_of",NOW.toString(),"synthetic_data",false,"games",arr(),"expected_game_ids",arr(),"evidence",arr());}
    ObjectNode completed=game(1,"2026-10-20",1,2,2,1,7);
    Elo initialize(){return new Elo(store,url->url.equals(Elo.TEAMS_URL)?directory():feed(completed));}
    void assertEight(String day){
        for(int offset=0;offset<8;offset++){
            String date=LocalDate.parse(day).minusDays(offset).toString();ObjectNode saved=store.get(Elo.snapshotKey(date));
            assertNotNull(saved,date);assertEquals(date,text(saved,"cutoff_day"));assertEquals(32,saved.path("ratings").size());
            assertEquals(48000,saved.path("ratings").valueStream().mapToDouble(JsonNode::asDouble).sum(),1e-8);
            for(JsonNode result:saved.path("results_used"))assertTrue(text(result,"gameDate").compareTo(date)<0,date);
        }
    }
    @Test void firstCollectionBackfillsEightCalendarDaysFromTheStoredLedgerWithoutExtraDownloads(){
        AtomicInteger downloads=new AtomicInteger();Elo elo=new Elo(store,url->{downloads.incrementAndGet();return url.equals(Elo.TEAMS_URL)?directory():feed(completed);});
        ObjectNode current=elo.collect(DAY,dataset(),NOW);assertEight(DAY);assertEquals(2,downloads.get());assertEquals(8,current.path("history").path("days").asInt());
        assertEquals(1500,number(store.get(Elo.snapshotKey("2026-10-20")),"ratings.MTL"));
        assertEquals(1510,number(store.get(Elo.snapshotKey("2026-10-21")),"ratings.MTL"));
        assertEquals("RECONSTRUCTED",text(store.get(Elo.snapshotKey("2026-10-21")),"history_origin"));
        assertFalse(store.get(Elo.snapshotKey(DAY)).has("history_origin"));
        ObjectNode season=store.get(Elo.stateKey(DAY));Map<String,ObjectNode> original=new HashMap<>();store.data.forEach((key,value)->original.put(key,value.deepCopy()));
        new Elo(store,url->{throw new AssertionError("No source refresh during history retry");}).collect(DAY,dataset(),NOW.plusSeconds(3600));
        assertEquals(original,store.data);assertEquals(season,store.get(Elo.stateKey(DAY)));
    }
    @Test void noGamesDaysCreateSnapshotsAndCarryRatingsForwardWithoutAdvancedImports(){
        Workflow workflow=new Workflow(store,day->empty(),job->{throw new AssertionError("No advanced or odds imports on empty days");},null,null,initialize());
        workflow.programTick(NOW);ObjectNode first=workflow.require(DAY);assertEquals("NO_GAMES",text(first,"state"));assertEquals("READY",text(first,"elo.state"));assertEight(DAY);
        workflow.programTick(NOW.plusSeconds(86400));String tomorrow="2026-10-25";ObjectNode next=workflow.require(tomorrow);assertEquals("NO_GAMES",text(next,"state"));assertEight(tomorrow);
        assertEquals(store.get(Elo.snapshotKey(DAY)).path("ratings"),store.get(Elo.snapshotKey(tomorrow)).path("ratings"));assertNotNull(store.get(Elo.snapshotKey("2026-10-17")),"History older than eight days is retained");
        assertEquals(0,next.path("revision").asInt());assertTrue(next.path("dataset").path("games").isEmpty());
    }
    @Test void migrationRepairsGapsForAnAlreadyReadyDayWithoutTouchingPublishedResearchOrSnapshots(){
        initialize().collect(DAY,dataset(),NOW);ObjectNode current=store.get(Elo.snapshotKey(DAY)),season=store.get(Elo.stateKey(DAY));store.data.remove(Elo.snapshotKey("2026-10-19"));
        Workflow workflow=new Workflow(store,day->dataset(),job->{throw new AssertionError("No imports during history repair");},null,null,new Elo(store,url->{throw new AssertionError("Repair uses the saved ledger");}));
        workflow.prepareSchedule(DAY,NOW);ObjectNode day=workflow.require(DAY);day.set("elo",obj("state","READY","season",season.path("season"),"games",obj()));day.put("revision",7).put("state","COMPLETE");day.set("final_tips",obj("state","PUBLISHED","published_at",NOW.toString(),"games",List.of(obj("game_id","123","decision","NO_BET")),"dataset_snapshot",day.path("dataset").deepCopy()));store.put("days/"+DAY+".json",day);JsonNode research=day.path("dataset").deepCopy(),tips=day.path("final_tips").deepCopy();
        workflow.ensureElo(DAY,NOW.plusSeconds(1800));assertEight(DAY);ObjectNode updated=store.get("days/"+DAY+".json");assertEquals(research,updated.path("dataset"));assertEquals(tips,updated.path("final_tips"));assertEquals(7,updated.path("revision").asInt());assertEquals(current,store.get(Elo.snapshotKey(DAY)));assertEquals(season,store.get(Elo.stateKey(DAY)));
    }
    @Test void unfinishedDaysAreNotMistakenForNoGamesAndAreRetriedBeforeTheNextSnapshot(){
        ObjectNode live=game(2,"2026-10-23",1,2,3,2,3);new Elo(store,url->url.equals(Elo.TEAMS_URL)?directory():feed(completed,live)).collect(DAY,empty(),NOW);
        assertEquals(1,store.get(Elo.snapshotKey(DAY)).path("games_processed").asInt());live.put("gameStateId",7);List<String> calls=new ArrayList<>();
        new Elo(store,url->{calls.add(url);return url.endsWith("2026-10-23")?EloIncrementalTest.daily("2026-10-23",live):EloIncrementalTest.daily(DAY);}).collect("2026-10-25",empty(),NOW.plusSeconds(86400));
        assertEquals(List.of(GameScores.BASE+"2026-10-23",GameScores.BASE+DAY),calls);assertEight("2026-10-25");assertEquals(2,store.get(Elo.snapshotKey("2026-10-25")).path("games_processed").asInt());assertEquals(1,store.get(Elo.snapshotKey(DAY)).path("games_processed").asInt());
    }
    @Test void eightDaysAcrossASeasonBoundaryUseSeparateSavedSeasonsAndNeverRewindEither(){
        ObjectNode old=calculate(completed);old.put("cutoff_day","2027-06-30").put("generation",4);store.put(Elo.stateKey("2027-06-30"),old);
        ObjectNode fresh=old.deepCopy();fresh.put("season","20272028").put("cutoff_day","2027-07-02").put("generation",0).put("games_processed",0);fresh.set("results_used",arr());fresh.set("pending_days",arr());fresh.path("ratings").fieldNames().forEachRemaining(code->((ObjectNode)fresh.path("ratings")).put(code,1500.0));store.put(Elo.stateKey("2027-07-02"),fresh);
        new Elo(store,url->{throw new AssertionError("Both seasonal ledgers are already saved");}).collect("2027-07-02",empty(),Instant.parse("2027-07-02T11:00:00Z"));assertEight("2027-07-02");
        assertEquals(1510,number(store.get(Elo.snapshotKey("2027-06-30")),"ratings.MTL"));assertEquals(1500,number(store.get(Elo.snapshotKey("2027-07-01")),"ratings.MTL"));assertEquals(old,store.get(Elo.stateKey("2027-06-30")));assertEquals(fresh,store.get(Elo.stateKey("2027-07-02")));
    }
    @Test void failedHistoryRepairPreservesTheReadyRatingAndCanResume(){
        initialize().collect(DAY,dataset(),NOW);String brokenKey=Elo.snapshotKey("2026-10-19");ObjectNode broken=store.get(brokenKey);((ObjectNode)broken.path("ratings")).remove("MTL");store.put(brokenKey,broken);
        Elo offline=new Elo(store,url->{throw new AssertionError("No network needed to repair history");});Workflow workflow=new Workflow(store,day->dataset(),job->{throw new AssertionError("No imports during repair");},null,null,offline);workflow.prepareSchedule(DAY,NOW);
        ObjectNode day=workflow.require(DAY),ready=store.get(Elo.snapshotKey(DAY));ready.remove(List.of("ratings","results_used","team_ids"));ready.set("games",obj());day.set("elo",ready);store.put("days/"+DAY+".json",day);
        workflow.ensureElo(DAY,NOW);assertEquals(ready,workflow.require(DAY).path("elo"));assertEquals(broken,store.get(brokenKey));
        store.data.remove(brokenKey);workflow.ensureElo(DAY,NOW);assertEight(DAY);assertEquals("READY",text(workflow.require(DAY),"elo.history.state"));
    }
}
