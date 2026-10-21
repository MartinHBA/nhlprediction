package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class GameScoresTest {
    static final Instant NOW=Instant.parse("2026-10-21T06:00:00Z");
    static ObjectNode game(){return obj("game_id","123","starts_at","2026-10-20T23:00:00Z","home",obj("name","MTL"),"away",obj("name","SJS"));}
    static ObjectNode event(String state,String end,int home,int away){return obj("id",123,"startTimeUTC","2026-10-20T23:00:00Z","gameState",state,"gameScheduleState","OK","homeTeam",obj("abbrev","MTL","score",home),"awayTeam",obj("abbrev","SJS","score",away),"periodDescriptor",obj("number",end.equals("REG")?3:4,"periodType",end),"gameOutcome",obj("lastPeriodType",end));}
    static JsonNode collect(ObjectNode event){return new GameScores(url->obj("currentDate","2026-10-20","games",List.of(event))).collect("2026-10-20",obj("games",List.of(game())),obj(),NOW).path("games").get(0);}
    static ObjectNode decision(String kind,String selection,Double line,String basis){return obj("decision","TIP","candidate",obj("kind",kind,"selection",selection,"team",selection,"line",line,"score_basis",basis));}
    @Test void derivesRegulationTieFromOfficialOtOrShootoutResult(){for(String end:List.of("OT","SO")){var score=collect(event("OFF",end,3,4));assertEquals(3,score.path("regulation_home_score").asInt());assertEquals(3,score.path("regulation_away_score").asInt());assertEquals("WIN",text(TipSettlement.evaluate(decision("regulation_1x2","draw",null,"regulation"),score),"state"));assertEquals("LOSS",text(TipSettlement.evaluate(decision("moneyline","home",null,"official_final"),score),"state"));}}
    @Test void gradesWinnerHandicapAndTotalsAgainstTheirActualScoreBasis(){
        var score=collect(event("FINAL","OT",3,4));
        assertEquals("WIN",text(TipSettlement.evaluate(decision("total","under",6.5,"regulation"),score),"state"));
        assertEquals("LOSS",text(TipSettlement.evaluate(decision("total","under",6.5,"official_final"),score),"state"));
        assertEquals("WIN",text(TipSettlement.evaluate(decision("handicap","home",1.5,"official_final"),score),"state"));
        assertEquals("PUSH",text(TipSettlement.evaluate(decision("total","over",7.0,"official_final"),score),"state"));
        assertEquals("WIN",text(TipSettlement.evaluate(decision("double_chance","1X",null,"regulation"),score),"state"));
    }
    @Test void noBetIsNeverGradedAndLiveScoresNeverSettleTips(){assertEquals("NO_BET",text(TipSettlement.evaluate(obj("decision","NO_BET"),collect(event("OFF","REG",3,1))),"state"));assertEquals("PENDING",text(TipSettlement.evaluate(decision("moneyline","home",null,"official_final"),collect(event("LIVE","REG",3,1))),"state"));}
    @Test void unknownRegulationResultAndMalformedMarketsNeverGetFalseGrades(){var score=collect(event("OFF","",3,4));assertEquals("UNAVAILABLE",text(TipSettlement.evaluate(decision("total","under",6.5,"regulation"),score),"state"));assertEquals("UNAVAILABLE",text(TipSettlement.evaluate(decision("total","bogus",6.5,"official_final"),score),"state"));}
    @Test void mismatchedTeamsAndWrongDateAreRejectedAndFinalScoresNeverRegress(){
        var data=obj("games",List.of(game()));var bad=event("OFF","REG",3,1);((ObjectNode)bad.path("homeTeam")).put("abbrev","BOS");
        assertTrue(new GameScores(url->obj("games",List.of(bad))).collect("2026-10-20",data,obj(),NOW).path("games").isEmpty());
        assertThrows(IllegalArgumentException.class,()->new GameScores(url->obj("currentDate","2026-10-21","games",arr())).collect("2026-10-20",data,obj(),NOW));
        var previous=obj("games",List.of(collect(event("OFF","REG",3,1))));var next=new GameScores(url->obj("games",List.of(event("LIVE","REG",0,0)))).collect("2026-10-20",data,previous,NOW);
        assertEquals("final",text(next.path("games").get(0),"status"));assertEquals(3,next.path("games").get(0).path("home_score").asInt());
    }
    @Test void eightOClockPollIncludesNormalTimerJitterButNotTheFollowingMinute(){
        assertTrue(NhlOdds.pollingWindow(Instant.parse("2026-10-21T06:00:35Z")));
        assertFalse(NhlOdds.pollingWindow(Instant.parse("2026-10-21T06:01:00Z")));
        assertEquals("2026-10-20",NhlOdds.pollDay(Instant.parse("2026-10-21T06:00:35Z")));
    }
}
