package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class AlternativeMarketsTest {
    static final String AS_OF="2026-10-20T18:00:00Z";ObjectNode game;
    static ObjectNode quote(String kind,String selection,double line,double odds){
        ObjectNode q=obj("id",kind+selection+line,"kind",kind,"selection",selection,"line",line,"odds",odds,"score_basis",List.of("total","team_total").contains(kind)?"regulation":"official_final","settlement_verified",true,"rules_url","https://book.example/rules","source",obj("url","https://book.example/odds","status","verified","checked_at",AS_OF));
        if(kind.equals("handicap"))q.put("team",selection);return q;
    }
    @BeforeEach void setup(){game=obj("game_id","123","status","scheduled","starts_at","2026-10-21T00:00:00Z","markets",arr().add(quote("total","over",6.5,1.9)).add(quote("total","under",6.5,1.9)).add(quote("handicap","home",-1.5,2.5)).add(quote("handicap","away",1.5,1.6)));}
    @Test void estimatesPricesWithoutAnyTeamInputsPredictionsOrMutatingQuotes(){
        JsonNode original=game.deepCopy();var estimates=AlternativeTotals.estimate(game,Instant.parse(AS_OF));assertFalse(estimates.isEmpty());assertEquals(original,game);
        for(JsonNode q:estimates){assertEquals("ESTIMATED",text(q,"odds_type"));assertFalse(q.has("p_win"));assertFalse(q.has("model_ev"));assertFalse(q.has("lambda_home"));assertNotEquals(6.5,number(q,"line"));assertEquals(2,q.path("anchor_market_ids").size());}
        var anchor=AlternativeTotals.anchor(game,Instant.parse(AS_OF),"regulation");assertEquals(1.9,1/(AlternativeTotals.under(anchor.parameter(),6.5)*anchor.margin()),1e-8);
    }
    @Test void neverEstimateHandicapsFromOnlyOneIndependentLine(){assertTrue(AlternativeHandicaps.estimate(game,Instant.parse(AS_OF)).isEmpty());}
    @Test void handicapInterpolationRequiresTwoCompatiblePairsAndStaysBetweenThem(){
        ((ArrayNode)game.path("markets")).add(quote("handicap","home",2.5,1.22)).add(quote("handicap","away",-2.5,4.4));
        var estimates=AlternativeHandicaps.estimate(game,Instant.parse(AS_OF));assertFalse(estimates.isEmpty());for(JsonNode q:estimates){assertFalse(q.has("p_win"));double h=text(q,"selection").equals("home")?number(q,"line"):-number(q,"line");assertTrue(h>=-1.5&&h<=2.5);assertEquals(4,q.path("anchor_market_ids").size());}
    }
    @Test void incompatibleFutureOrStaleQuoteCannotAnchorPrices(){
        ((ObjectNode)game.path("markets").get(1).path("source")).put("url","https://other.example");assertTrue(AlternativeTotals.estimate(game,Instant.parse(AS_OF)).isEmpty());
        ((ObjectNode)game.path("markets").get(1).path("source")).put("url","https://book.example/odds").put("checked_at","2026-10-20T18:30:00Z");assertTrue(AlternativeTotals.estimate(game,Instant.parse(AS_OF)).isEmpty());
        assertTrue(AlternativeTotals.estimate(game,Instant.parse(AS_OF).plusSeconds(10800)).isEmpty());
    }
    @Test void allQuotedPricesRemainVisibleWhileBandOnlyControlsSelection(){
        var quotes=MarketQuotes.all(game,Instant.parse(AS_OF));assertTrue(quotes.valueStream().anyMatch(q->number(q,"price")>1.35&&text(q,"odds_type").equals("QUOTED")));
        for(JsonNode q:quotes)if(q.path("selectable").asBoolean())assertTrue(MarketQuotes.inRange(number(q,"price")));
        game.put("status","live");assertTrue(MarketQuotes.all(game,Instant.parse(AS_OF)).isEmpty());
    }
    @Test void suspendedTeamOverIsNeverSelectable(){((ArrayNode)game.path("markets")).add(quote("team_total","over",2.5,1.25));var q=MarketQuotes.all(game,Instant.parse(AS_OF)).valueStream().filter(c->text(c,"kind").equals("team_total")).findFirst().orElseThrow();assertFalse(q.path("selectable").asBoolean());}
    @Test void finalScoreTotalsRemainDataButCanNeverAnchorRegulationEstimates(){
        for(JsonNode q:game.path("markets"))if(text(q,"kind").equals("total"))((ObjectNode)q).put("score_basis","official_final").put("odds",1.25);
        JsonNode original=game.deepCopy();assertTrue(AlternativeTotals.estimate(game,Instant.parse(AS_OF)).isEmpty());
        var quotes=MarketQuotes.all(game,Instant.parse(AS_OF));
        for(JsonNode q:quotes)if(text(q,"kind").equals("total")){assertFalse(q.path("selectable").asBoolean());assertTrue(strings(q.path("blocking_reasons")).contains("GOAL_TOTAL_REQUIRES_REGULATION"));assertEquals("official_final",text(q,"score_basis"));}
        assertEquals(original,game);
    }
    @Test void regulationTeamTotalsNeedVerifiedRulesAndKeepTheTeamOverBan(){
        ArrayNode markets=(ArrayNode)game.path("markets");ObjectNode quote=quote("team_total","under",3.5,1.18);quote.put("team","home");markets.add(quote);
        var good=MarketQuotes.all(game,Instant.parse(AS_OF)).valueStream().filter(q->text(q,"kind").equals("team_total")).findFirst().orElseThrow();assertTrue(good.path("selectable").asBoolean());
        quote.put("score_basis","official_final");assertFalse(MarketQuotes.all(game,Instant.parse(AS_OF)).valueStream().filter(q->text(q,"kind").equals("team_total")).findFirst().orElseThrow().path("selectable").asBoolean());
        quote.put("score_basis","regulation").put("settlement_verified",false);assertFalse(MarketQuotes.all(game,Instant.parse(AS_OF)).valueStream().filter(q->text(q,"kind").equals("team_total")).findFirst().orElseThrow().path("selectable").asBoolean());
    }
}
