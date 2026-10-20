package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class AlternativeMarketsTest {
    static final String AS_OF="2026-10-20T18:00:00Z";
    ObjectNode game;Engine.Scores scores;
    @BeforeEach void init(){
        game=obj("status","scheduled","starts_at","2026-10-21T00:00:00Z","markets",List.of(
            quote("total","over",6.5,1.9),quote("total","under",6.5,1.9),
            quote("handicap","home",-1.5,2.5),quote("handicap","away",1.5,1.6)));
        scores=Engine.simulate(3.2,2.7,10000,42);
    }
    static ObjectNode quote(String kind,String selection,double line,double odds){return obj("id",kind+selection,"kind",kind,"selection",selection,"line",line,"odds",odds,"score_basis","official_final","settlement_verified",true,"rules_url","https://book.example/rules","source",obj("url","https://book.example/odds","status","verified","checked_at",AS_OF));}
    @Test void totalAnchorRecoversActualPairWithoutUsingModelProbability(){
        var anchor=AlternativeTotals.anchor(game,Checks.time(AS_OF),"official_final");assertNotNull(anchor);
        double p=AlternativeTotals.under(anchor.lambda(),6.5);
        assertEquals(1.9,1/(p*anchor.margin()),1e-10);assertEquals(1.9,1/((1-p)*anchor.margin()),1e-10);
        assertTrue(AlternativeTotals.under(anchor.lambda(),8.5)>p);
        var low=AlternativeTotals.estimate(game,Engine.simulate(1.5,1.5,1000,1),List.of(),AS_OF);
        var high=AlternativeTotals.estimate(game,Engine.simulate(5,5,1000,1),List.of(),AS_OF);
        assertEquals(low.get(0).path("estimated_odds"),high.get(0).path("estimated_odds"));
        assertNotEquals(low.get(0).path("p_win"),high.get(0).path("p_win"));
    }
    @Test void totalsAreIndicativeAndDoNotInventRealMarkets(){
        JsonNode before=game.deepCopy();var estimates=AlternativeTotals.estimate(game,scores,List.of(Engine.simulate(4,3.5,10000,3)),AS_OF);
        assertEquals(16,estimates.size());assertEquals(before,game);
        for(JsonNode e:estimates){assertEquals("ESTIMATED",text(e,"odds_type"));assertFalse(e.path("research_eligible").asBoolean());assertNotEquals(6.5,number(e,"line"));assertTrue(number(e,"p_win_min")<=number(e,"p_win"));assertTrue(number(e,"p_win")<=number(e,"p_win_max"));}
        JsonNode under85=estimates.valueStream().filter(e->text(e,"selection").equals("under")&&number(e,"line")==8.5).findFirst().orElseThrow();
        assertEquals(1/number(under85,"p_win_min"),number(under85,"minimum_odds_conservative"),.0001);
    }
    @Test void noPricesWithoutFreshCompatibleCompletePair(){
        ((ObjectNode)game.path("markets").get(1).path("source")).put("url","https://other.example/");
        assertTrue(AlternativeTotals.estimate(game,scores,List.of(),AS_OF).isEmpty());
        ((ObjectNode)game.path("markets").get(3).path("source")).put("checked_at","2026-10-20T14:00:00Z");
        assertTrue(AlternativeHandicaps.estimate(game,scores,List.of(),AS_OF).isEmpty());
    }
    @Test void neverBlendDifferentSettlementOrFuturePublication(){
        ((ObjectNode)game.path("markets").get(1)).put("score_basis","regulation");
        assertTrue(AlternativeTotals.estimate(game,scores,List.of(),AS_OF).isEmpty());
        ((ObjectNode)game.path("markets").get(3).path("source")).put("checked_at","2026-10-20T18:30:00Z");
        assertTrue(AlternativeHandicaps.estimate(game,scores,List.of(),AS_OF).isEmpty());
    }
    @Test void noNewPredictionsAfterStart(){
        game.put("starts_at",AS_OF);assertTrue(AlternativeTotals.estimate(game,scores,List.of(),AS_OF).isEmpty());assertTrue(AlternativeHandicaps.estimate(game,scores,List.of(),AS_OF).isEmpty());
    }
    @Test void handicapFitRecoversQuoteAndComplementaryProbabilities(){
        var histogram=AlternativeHandicaps.histogram(scores,"official_final");
        double tilt=AlternativeHandicaps.fit(histogram,-1.5,.4);
        assertEquals(.4,AlternativeHandicaps.probability(histogram,tilt,-1.5),1e-10);
        assertTrue(AlternativeHandicaps.probability(histogram,tilt,2.5)>.4);
        var estimates=AlternativeHandicaps.estimate(game,scores,List.of(),AS_OF);assertEquals(18,estimates.size());
        JsonNode h=estimates.valueStream().filter(e->text(e,"selection").equals("home")&&number(e,"line")==2.5).findFirst().orElseThrow();
        JsonNode a=estimates.valueStream().filter(e->text(e,"selection").equals("away")&&number(e,"line")== -2.5).findFirst().orElseThrow();
        assertEquals(1,number(h,"p_win")+number(a,"p_win"),1e-9);
        assertEquals(1,1/number(h,"market_fair_odds")+1/number(a,"market_fair_odds"),.00001);
        assertFalse(h.path("research_eligible").asBoolean());
    }
    @Test void unsupportedIntegerLinesAndExcessiveOverroundAreNotExtrapolated(){
        ((ObjectNode)game.path("markets").get(0)).put("line",6);((ObjectNode)game.path("markets").get(1)).put("line",6);
        assertTrue(AlternativeTotals.estimate(game,scores,List.of(),AS_OF).isEmpty());
        ((ObjectNode)game.path("markets").get(2)).put("odds",1.1);((ObjectNode)game.path("markets").get(3)).put("odds",1.1);
        assertTrue(AlternativeHandicaps.estimate(game,scores,List.of(),AS_OF).isEmpty());
    }
}
