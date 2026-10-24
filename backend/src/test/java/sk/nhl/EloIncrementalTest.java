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

class EloIncrementalTest {
    final MemoryStore store=new MemoryStore();
    final ObjectNode first=game(1,"2026-10-22",1,2,2,1,7),second=game(2,"2026-10-23",1,2,2,3,7);
    ObjectNode seed(){return new Elo(store,url->url.equals(Elo.TEAMS_URL)?directory():feed(first)).collect("2026-10-23",dataset(),NOW);}
    static ObjectNode daily(String date,JsonNode... rows){
        ArrayNode events=arr();for(JsonNode game:rows)events.add(obj("id",game.path("id"),"gameType",2,"startTimeUTC",LocalDateTime.parse(text(game,"easternStartTime")).atZone(ZoneId.of("America/New_York")).toInstant().toString(),
            "gameState",Set.of(6,7).contains(game.path("gameStateId").asInt())?"OFF":"LIVE","gameScheduleState","OK",
            "homeTeam",obj("abbrev",TEAMS.get(game.path("homeTeamId").asInt()-1),"score",game.path("homeScore")),"awayTeam",obj("abbrev",TEAMS.get(game.path("visitingTeamId").asInt()-1),"score",game.path("visitingScore"))));
        return obj("currentDate",date,"games",events);
    }
    void stored(String date,JsonNode... rows){
        ArrayNode games=arr(),scores=arr(),expected=arr();for(JsonNode event:daily(date,rows).path("games")){
            String id=text(event,"id");expected.add(id);games.add(obj("game_id",id,"starts_at",event.path("startTimeUTC"),"home",obj("name",text(event,"homeTeam.abbrev")),"away",obj("name",text(event,"awayTeam.abbrev"))));
            scores.add(obj("game_id",id,"status","final","home_score",event.path("homeTeam").path("score"),"away_score",event.path("awayTeam").path("score"),"source_url",GameScores.BASE+date));
        }
        store.put("days/"+date+".json",obj("dataset",obj("games",games,"expected_game_ids",expected),"scoreboard",obj("games",scores)));
    }
    @Test void usesPersistentRatingsAndStoredFinalScoresAcrossRestartsWithNoNetwork(){
        ObjectNode old=seed();stored("2026-10-23",second);
        Elo resumed=new Elo(store,url->{throw new AssertionError("Stored results must not be downloaded: "+url);});ObjectNode today=resumed.collect(DAY,dataset(),NOW);
        ObjectNode state=store.get(Elo.stateKey(DAY));assertEquals(2,state.path("games_processed").asInt());assertEquals(calculate(first,second).path("ratings"),state.path("ratings"));assertEquals(32,state.path("ratings").size());
        assertEquals("2026-10-24",text(state,"cutoff_day"));assertEquals(1,state.path("generation").asInt());assertEquals(old,resumed.collect("2026-10-23",dataset(),NOW));assertEquals(today,resumed.collect(DAY,dataset(),NOW));
        assertEquals(2,store.get(Elo.stateKey(DAY)).path("games_processed").asInt());
    }
    @Test void fetchesOnlyTheMissingDayAndNeverDownloadsTheSeasonAgain(){
        seed();List<String> requests=new ArrayList<>();Elo resumed=new Elo(store,url->{requests.add(url);assertEquals(GameScores.BASE+"2026-10-23",url);return daily("2026-10-23",second);});
        resumed.collect(DAY,dataset(),NOW);resumed.collect(DAY,dataset(),NOW);assertEquals(List.of(GameScores.BASE+"2026-10-23"),requests);assertEquals(calculate(first,second).path("ratings"),store.get(Elo.stateKey(DAY)).path("ratings"));
    }
    @Test void failureDoesNotAdvanceTheSeasonAndRetryReusesPreviouslySavedDays(){
        seed();AtomicInteger requests=new AtomicInteger();Elo resumed=new Elo(store,url->{requests.incrementAndGet();if(url.endsWith("2026-10-24"))throw new IllegalStateException("Temporary NHL failure");return daily("2026-10-23",second);});
        Instant next=NOW.plusSeconds(86400);assertThrows(IllegalStateException.class,()->resumed.collect("2026-10-25",dataset(),next));assertEquals("2026-10-23",text(store.get(Elo.stateKey(DAY)),"cutoff_day"));assertNull(store.get("cache/elo/v1/2026-10-25.json"));assertEquals(2,requests.get());
        new Elo(store,url->{requests.incrementAndGet();assertEquals(GameScores.BASE+"2026-10-24",url);return daily("2026-10-24");}).collect("2026-10-25",dataset(),next);
        assertEquals(3,requests.get());assertEquals(2,store.get(Elo.stateKey(DAY)).path("games_processed").asInt());
    }
    @Test void unfinishedEarlierGamesAreRetriedAndInsertedInChronologicalOrderLocally(){
        ObjectNode late=game(3,"2026-10-21",3,1,1,0,3);new Elo(store,url->url.equals(Elo.TEAMS_URL)?directory():feed(first,late)).collect("2026-10-23",dataset(),NOW);late.put("gameStateId",7);
        List<String> requests=new ArrayList<>();new Elo(store,url->{requests.add(url);return url.endsWith("2026-10-21")?daily("2026-10-21",late):daily("2026-10-23",second);}).collect(DAY,dataset(),NOW);
        ObjectNode state=store.get(Elo.stateKey(DAY));assertEquals(List.of(GameScores.BASE+"2026-10-21",GameScores.BASE+"2026-10-23"),requests);assertTrue(state.path("pending_days").isEmpty());assertEquals(calculate(late,first,second).path("ratings"),state.path("ratings"));assertEquals(3,state.path("games_processed").asInt());
    }
    @Test void correctionsAndDuplicatesNeverCountTheSameResultTwice(){
        ObjectNode baseline=calculate(first,second),correction=first.deepCopy();correction.put("homeScore",0).put("visitingScore",1);
        ObjectNode update=Elo.dailyResults("2026-10-22",daily("2026-10-22",correction),baseline,NOW),after=Elo.advance(DAY,baseline,List.of(update),NOW);
        assertEquals(calculate(correction,second).path("ratings"),after.path("ratings"));assertEquals(2,after.path("games_processed").asInt());
        ObjectNode repeated=Elo.advance("2026-10-25",after,List.of(update,update),NOW.plusSeconds(86400));assertEquals(after.path("ratings"),repeated.path("ratings"));assertEquals(2,repeated.path("games_processed").asInt());
    }
    @Test void migratesOldDailyCachesWithoutDownloadingHistoryAndBackdatedReadsDoNotRewindCurrentState(){
        ObjectNode utah=game(3,"2026-10-22",32,4,2,1,7),old=Elo.calculate("2026-10-23",feed(first,utah),directory(),NOW),teams=directory();old.remove("team_ids");((ArrayNode)teams.path("data")).add(obj("id",99,"triCode","UTA"));
        store.put("cache/elo/v1/2026-10-23.json",old);store.put("cache/elo/v1/teams-20262027.json",teams);stored("2026-10-23",second);
        Elo migrated=new Elo(store,url->{throw new AssertionError("Migration must use persisted history: "+url);});migrated.collect(DAY,dataset(),NOW);ObjectNode state=store.get(Elo.stateKey(DAY));assertEquals(calculate(first,utah,second).path("ratings"),state.path("ratings"));assertEquals("UTA",text(state,"team_ids.32"));assertFalse(state.path("team_ids").has("99"));
        ObjectNode historical=migrated.collect("2026-10-22",dataset(),NOW);assertEquals(1500,number(historical,"games.123.home"));assertEquals(state,store.get(Elo.stateKey(DAY)));assertEquals(old,store.get("cache/elo/v1/2026-10-23.json"));
    }
    @Test void incompleteOrInvalidStoredScoresFallBackToOnlyThatDaysOfficialFeed(){
        seed();stored("2026-10-23",second);ObjectNode saved=store.get("days/2026-10-23.json");((ObjectNode)saved.path("scoreboard").path("games").get(0)).put("status","live");store.put("days/2026-10-23.json",saved);
        AtomicInteger calls=new AtomicInteger();new Elo(store,url->{calls.incrementAndGet();assertEquals(GameScores.BASE+"2026-10-23",url);return daily("2026-10-23",second);}).collect(DAY,dataset(),NOW);assertEquals(1,calls.get());
        ObjectNode state=store.get(Elo.stateKey(DAY));assertThrows(IllegalArgumentException.class,()->Elo.dailyResults("2026-10-23",daily("2026-10-22",second),state,NOW));assertThrows(IllegalArgumentException.class,()->Elo.dailyResults("2026-10-23",daily("2026-10-23",second,second),state,NOW));
    }
    @Test void storedNoGamesDaysNeedNoNetworkAndMissedDaysAdvanceTogether(){
        seed();stored("2026-10-23",second);stored("2026-10-24");new Elo(store,url->{throw new AssertionError("Both days already exist in persistent storage");}).collect("2026-10-25",dataset(),NOW.plusSeconds(86400));
        ObjectNode state=store.get(Elo.stateKey(DAY));assertEquals("2026-10-25",text(state,"cutoff_day"));assertEquals(2,state.path("games_processed").asInt());assertEquals(calculate(first,second).path("ratings"),state.path("ratings"));
    }
    @Test void aConcurrentSeasonWriteIsNotOverwrittenAndRetryUsesCachedResults(){
        seed();Elo conflicting=new Elo(store,url->{ObjectNode other=store.get(Elo.stateKey(DAY));other.put("generation",1);store.put(Elo.stateKey(DAY),other);return daily("2026-10-23",second);});
        assertThrows(BusyException.class,()->conflicting.collect(DAY,dataset(),NOW));assertEquals(1,store.get(Elo.stateKey(DAY)).path("generation").asInt());assertEquals(1,store.get(Elo.stateKey(DAY)).path("games_processed").asInt());
        new Elo(store,url->{throw new AssertionError("Retry should use the downloaded day cache");}).collect(DAY,dataset(),NOW);assertEquals(2,store.get(Elo.stateKey(DAY)).path("generation").asInt());assertEquals(calculate(first,second).path("ratings"),store.get(Elo.stateKey(DAY)).path("ratings"));
    }
    @Test void aNewSeasonHasItsOwnPersistentStateAndStartsAt1500(){
        seed();ObjectNode old=store.get(Elo.stateKey(DAY)),next=feed();for(JsonNode row:next.path("data")){ObjectNode game=(ObjectNode)row;game.put("season",20272028).put("id",game.path("id").asLong()+1000000).put("gameDate","2027-11-01").put("easternStartTime","2027-11-01T19:00:00");}
        new Elo(store,url->url.equals(Elo.TEAMS_URL)?directory():next).collect("2027-10-24",dataset(),Instant.parse("2027-10-24T11:00:00Z"));
        assertEquals(old,store.get(Elo.stateKey(DAY)));ObjectNode fresh=store.get(Elo.stateKey("2027-10-24"));assertEquals(0,fresh.path("games_processed").asInt());assertEquals(32,fresh.path("ratings").size());assertTrue(fresh.path("ratings").valueStream().allMatch(n->n.asDouble()==1500));
    }
}
