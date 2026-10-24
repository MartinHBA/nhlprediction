package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class EloTest {
    static final String DAY="2026-10-24";
    static final Instant NOW=Instant.parse("2026-10-24T11:00:00Z");
    static final List<String> TEAMS=List.of("MTL","SJS","TBL","COL","CHI","BOS","BUF","NJD","NYI","NYR","PHI","PIT","WSH","CAR","FLA","OTT","TOR","DET","WPG","NSH","MIN","DAL","STL","CGY","EDM","VAN","SEA","LAK","ANA","VGK","CBJ","UTA");
    static ObjectNode directory(){ArrayNode teams=arr();for(int i=0;i<TEAMS.size();i++)teams.add(obj("id",i+1,"triCode",TEAMS.get(i)));return obj("data",teams,"total",32);}
    static ObjectNode game(int number,String date,int home,int away,int h,int a,int state){return obj("id",2026020000L+number,"season",20262027,"gameType",2,"gameDate",date,"easternStartTime",date+"T19:00:00","homeTeamId",home,"visitingTeamId",away,"homeScore",h,"visitingScore",a,"gameStateId",state,"gameScheduleStateId",1);}
    static ObjectNode feed(JsonNode... completed){ArrayNode games=arr();for(JsonNode game:completed)games.add(game);for(int i=1;i<=32;i+=2)games.add(game(1000+i,"2026-11-01",i,i+1,0,0,1));return obj("data",games,"total",games.size());}
    static ObjectNode calculate(JsonNode... completed){return Elo.calculate(DAY,feed(completed),directory(),NOW);}
    static ObjectNode dataset(){ObjectNode data=DataCollectorTest.template(DAY,"2026-10-25T00:00:00Z");ObjectNode game=(ObjectNode)data.path("games").get(0);((ObjectNode)game.path("home")).put("name","MTL");((ObjectNode)game.path("away")).put("name","SJS");return data;}
    @Test void classicFormulaConservesPointsAndAwardsAnUpsetMoreThanAnExpectedWin(){
        assertEquals(10,Elo.change(1500,1500),1e-12);assertEquals(20.0/11,Elo.change(1700,1300),1e-12);assertEquals(200.0/11,Elo.change(1300,1700),1e-12);
        ObjectNode result=calculate(game(1,"2026-10-01",1,2,2,1,7));assertEquals(1510,number(result,"ratings.MTL"));assertEquals(1490,number(result,"ratings.SJS"));assertEquals(1500,number(result,"ratings.UTA"));
        assertEquals(48000,result.path("ratings").valueStream().mapToDouble(JsonNode::asDouble).sum(),1e-8);assertEquals(32,result.path("team_count").asInt());
    }
    @Test void overtimeShootoutGoalMarginAndHomeIceNeverChangeTheWinLossAward(){
        ObjectNode reg=game(1,"2026-10-01",1,2,2,1,7),ot=reg.deepCopy(),so=reg.deepCopy(),rout=reg.deepCopy(),away=reg.deepCopy();
        ot.put("period",4);so.put("period",5);rout.put("homeScore",9);away.put("homeTeamId",2).put("visitingTeamId",1).put("homeScore",0).put("visitingScore",1);
        for(JsonNode g:List.of(ot,so,rout,away))assertEquals(calculate(reg).path("ratings"),calculate(g).path("ratings"));
    }
    @Test void replaysChronologicallyWithoutRoundingAndExcludesTodaysFutureLiveAndOtherGameTypes(){
        ObjectNode first=game(1,"2026-10-01",1,2,1,0,7),second=game(2,"2026-10-02",2,3,1,0,6),third=game(3,"2026-10-03",3,1,1,0,7);
        ObjectNode today=game(4,DAY,1,2,9,0,7),live=game(5,"2026-10-23",1,2,1,0,3),preseason=game(6,"2026-10-01",1,2,9,0,7);preseason.put("gameType",1);
        ObjectNode result=calculate(third,today,live,first,preseason,second),ordered=calculate(first,second,third);
        assertEquals(ordered.path("ratings"),result.path("ratings"));assertEquals(3,result.path("games_processed").asInt());assertEquals(first,result.path("results_used").get(0));
        double delta2=Elo.change(1490,1500),delta3=Elo.change(1500-delta2,1510);assertEquals(1510-delta3,number(result,"ratings.MTL"),1e-10);
        assertNotEquals(Math.rint(number(result,"ratings.MTL")),number(result,"ratings.MTL"));
    }
    @Test void aNewSeasonStartsAllTeamsAt1500AndBadOrPartialHistoryIsRejected(){
        assertTrue(calculate().path("ratings").valueStream().allMatch(n->n.asDouble()==1500));assertEquals("20262027",Elo.season("2027-01-01"));assertEquals("20272028",Elo.season("2027-07-01"));
        var valid=game(1,"2026-10-01",1,2,1,0,7);ObjectNode partial=feed(valid);partial.put("total",999);assertThrows(IllegalArgumentException.class,()->Elo.calculate(DAY,partial,directory(),NOW));
        assertThrows(IllegalArgumentException.class,()->calculate(valid,valid));
        ObjectNode tie=valid.deepCopy();tie.put("visitingScore",1);assertThrows(IllegalArgumentException.class,()->calculate(tie));
        ObjectNode wrong=valid.deepCopy();wrong.put("season",20252026);assertThrows(IllegalArgumentException.class,()->calculate(wrong));
        ObjectNode unknown=valid.deepCopy();unknown.put("homeTeamId",999);assertThrows(IllegalArgumentException.class,()->calculate(unknown));
        assertThrows(IllegalArgumentException.class,()->Elo.calculate(DAY,feed(valid),directory(),Instant.parse("2026-10-01T21:00:00Z")));
    }
    @Test void successfulDailySnapshotIsCachedAndNeverIncludesThatNightsResult(){
        MemoryStore store=new MemoryStore();AtomicInteger calls=new AtomicInteger();Elo elo=new Elo(store,url->{calls.incrementAndGet();return url.equals(Elo.TEAMS_URL)?directory():feed(game(1,"2026-10-01",1,2,1,0,7));});
        ObjectNode initial=elo.collect(DAY,dataset(),NOW);assertEquals(2,calls.get());assertEquals(1510,number(initial,"games.123.home"));
        ObjectNode again=new Elo(store,url->{throw new AssertionError("Daily Elo is immutable and must use cache");}).collect(DAY,dataset(),NOW.plusSeconds(86400));assertEquals(initial,again);assertFalse(initial.has("ratings"));assertFalse(initial.has("results_used"));
        assertThrows(IllegalArgumentException.class,()->elo.collect("2026-10-25",dataset(),NOW));
    }
    @Test void programCreatesEloWithNoAdvancedImportAndFailureCanResumeWithoutChangingResearch(){
        MemoryStore store=new MemoryStore();AtomicInteger calls=new AtomicInteger();Elo elo=new Elo(store,url->{if(calls.getAndIncrement()==0)throw new IllegalStateException("Temporary source failure");return url.equals(Elo.TEAMS_URL)?directory():feed(game(1,"2026-10-01",1,2,1,0,7));});
        Workflow w=new Workflow(store,day->dataset(),job->{throw new AssertionError("No advanced jobs at 13:00");},null,null,elo);
        w.programTick(NOW);ObjectNode before=w.require(DAY);assertEquals("ERROR",text(before,"elo.state"));JsonNode original=before.path("dataset").deepCopy();String fingerprint=Tips.analysisId(before,NOW);
        w.programTick(NOW.plusSeconds(1800));ObjectNode ready=w.require(DAY);assertEquals("READY",text(ready,"elo.state"));assertEquals(original,ready.path("dataset"));assertEquals(0,ready.path("revision").asInt());assertEquals(fingerprint,Tips.analysisId(ready,NOW));assertEquals("SCHEDULED",text(ready,"enrichment.state"));
        int after=calls.get();w.programTick(NOW.plusSeconds(3600));assertEquals(after,calls.get());assertEquals(ready.path("elo"),w.readCards(ready).path("elo"));
    }
}
