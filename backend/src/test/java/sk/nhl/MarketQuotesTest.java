package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class MarketQuotesTest {
    final Instant now=Instant.parse("2026-10-23T19:00:00Z");
    ObjectNode quote(){return obj("id","oddize-real","provider","Oddize","kind","total","selection","under","line",7.5,"odds",1.25,"score_basis","official_final","settlement_verified",true,"rules_url",BookmakerRules.url("fd"),"observed_at",now.minusSeconds(60).toString(),"quality_warnings",arr(),"source",obj("url",OddizeClient.BASE+"/events/nhl/123/alts?alt_type=alt_t","status","verified","checked_at",now.toString()));}
    JsonNode candidate(ObjectNode quote){return MarketQuotes.all(obj("game_id","123","status","scheduled","starts_at",now.plusSeconds(18000).toString(),"markets",arr().add(quote)),now).get(0);}
    @Test void onlyActualQuotesExistAndBothVerifiedSettlementBasesAreAllowed(){
        for(String basis:List.of("regulation","official_final")){
            ObjectNode q=quote().put("score_basis",basis);JsonNode original=q.deepCopy(),c=candidate(q);
            assertTrue(c.path("selectable").asBoolean());assertEquals("QUOTED",text(c,"odds_type"));assertEquals(original,q);assertFalse(c.has("p_win"));
        }
        var game=obj("game_id","123","status","scheduled","starts_at",now.plusSeconds(18000).toString(),"markets",arr().add(quote().put("odds",1.9)));
        assertEquals(1,MarketQuotes.all(game,now).size(),"No inferred alternative boundaries");
    }
    @Test void oldProviderManualUnverifiedUnknownAndInconsistentPricesCannotBeSelected(){
        for(ObjectNode q:List.of(quote().put("id","manual"),quote().put("provider","NHL"),quote().put("settlement_verified",false),quote().put("score_basis","unknown"),quote().put("observed_at",now.minusSeconds(7201).toString()),quote().put("observed_at",now.plusSeconds(1).toString()),quote().set("quality_warnings",arr().add("INCONSISTENT_TOTAL_PRICE_ORDER"))))assertFalse(candidate(q).path("selectable").asBoolean(),q.toString());
    }
    @Test void allPricesRemainContextButBandAndTeamOverBanControlSelection(){
        assertFalse(candidate(quote().put("odds",1.04)).path("selectable").asBoolean());
        assertFalse(candidate(quote().put("kind","team_total").put("team","home").put("selection","over").put("line",2.5)).path("selectable").asBoolean());
        assertTrue(candidate(quote().put("kind","team_total").put("team","home").put("selection","under").put("line",2.5)).path("selectable").asBoolean());
    }
}
