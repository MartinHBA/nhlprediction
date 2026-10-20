package sk.nhl;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class RecommendationsTest {
    ObjectNode game;
    @BeforeEach void init(){game=resource("demo.json").path("games").get(0).deepCopy();for(String side:List.of("home","away"))for(String group:List.of("prior","current","goalie","roster"))((ObjectNode)game.path(side).path(group).path("source")).put("status","verified");}
    ObjectNode estimate(String kind,double odds,double p,double minimum){return obj("kind",kind,"line",kind.equals("total")?8.5:2.5,"selection",kind.equals("total")?"under":"away","odds_type","ESTIMATED","estimated_odds",odds,"p_win",p,"p_win_min",minimum,"minimum_odds_conservative",1/minimum,"research_eligible",false);}
    @Test void picksOneBestConservativeValueAcrossActualTotalsAndHandicaps(){
        var actual=obj("kind","moneyline","selection","home","odds",1.25,"p_win",.9,"ev",.125,"scenario_ev_min",.01,"research_eligible",true);
        var totals=estimate("total",1.25,.95,.88);var handicap=estimate("handicap",1.3,.98,.9);
        var choice=Recommendations.select(game,arr().add(actual),arr().add(totals).add(handicap),obj("warnings",arr()),"2026-10-20T18:00:00Z");
        assertEquals("handicap",text(choice,"kind"));assertEquals("PRELIMINARY",text(choice,"recommendation_status"));assertFalse(choice.path("research_eligible").asBoolean());assertEquals(.17,number(choice,"estimated_scenario_ev_min"),1e-9);
        assertTrue(Engine.combo(arr().add(obj("game_id","x","status","scheduled","markets",arr(),"recommendation",choice))).path("legs").isEmpty());
    }
    @Test void noValueOrPriceAboveCapGivesNoRecommendation(){
        var estimates=arr().add(estimate("total",1.36,.99,.99)).add(estimate("handicap",1.25,.79,.7));
        assertNull(Recommendations.select(game,arr(),estimates,obj("warnings",arr()),"2026-10-20T18:00:00Z"));
    }
    @Test void confidenceDoesNotPretendToBeCalibratedWinProbability(){
        ((ObjectNode)game.path("away").path("goalie")).put("status","probable");
        var choice=Recommendations.select(game,arr(),arr().add(estimate("total",1.25,.95,.9)),obj("warnings",arr()),"2026-10-20T18:00:00Z");
        assertEquals("LOW",text(choice,"confidence"));assertEquals("INPUT_QUALITY_NOT_CALIBRATED_ACCURACY",text(choice,"confidence_basis"));assertTrue(strings(choice.path("confidence_reasons")).contains("AWAY_GOALIE_NOT_CONFIRMED"));
    }
    @Test void invalidProjectionAndUnverifiedInputsNeverProduceAnEstimatedRecommendation(){
        var estimates=arr().add(estimate("total",1.25,.95,.9));
        assertNull(Recommendations.select(game,arr(),estimates,obj("warnings",List.of("PROJECTED_LAMBDA_OUTSIDE_PLAUSIBLE_RANGE; manual review required")),"2026-10-20T18:00:00Z"));
        ((ObjectNode)game.path("home").path("prior").path("source")).put("status","unverified");
        assertNull(Recommendations.select(game,arr(),estimates,obj("warnings",arr()),"2026-10-20T18:00:00Z"));
    }
    @Test void positiveBaselineWithNegativeSensitivityIsOnlyLowConfidencePreliminary(){
        var choice=Recommendations.select(game,arr(),arr().add(estimate("total",1.25,.9,.7)),obj("warnings",arr()),"2026-10-20T18:00:00Z");
        assertNotNull(choice);assertEquals("PRELIMINARY",text(choice,"recommendation_status"));assertEquals("LOW",text(choice,"confidence"));
        assertFalse(choice.path("robust_edge").asBoolean());assertTrue(strings(choice.path("confidence_reasons")).contains("SCENARIO_EDGE_NOT_ROBUST"));
        assertFalse(choice.path("research_eligible").asBoolean());
    }
    @Test void actualQuoteWithUncertaintyCanBePreviewedButInvalidOffersCannot(){
        var actual=obj("kind","moneyline","selection","home","odds",1.25,"p_win",.9,"ev",.125,"scenario_ev_min",-.03,"research_eligible",false,"issues",List.of("GOALIE_NOT_CONFIRMED","NO_CONSERVATIVE_EDGE_IN_SCENARIOS"));
        var choice=Recommendations.select(game,arr().add(actual),arr(),obj("warnings",arr()),"2026-10-20T18:00:00Z");
        assertNotNull(choice);assertEquals("QUOTED",text(choice,"odds_type"));assertEquals("PRELIMINARY",text(choice,"recommendation_status"));assertFalse(choice.path("research_eligible").asBoolean());
        actual.set("issues",arr().add("UNVERIFIED_ODDS"));assertNull(Recommendations.select(game,arr().add(actual),arr(),obj("warnings",arr()),"2026-10-20T18:00:00Z"));
        actual.set("issues",arr().add("SUSPENDED_TEAM_OVER_2_5"));assertNull(Recommendations.select(game,arr().add(actual),arr(),obj("warnings",arr()),"2026-10-20T18:00:00Z"));
    }
    @Test void appliesInclusive120To135BandToBothActualAndEstimatedPrices(){
        for(double price:new double[]{1.04,1.19999,1.20,1.25,1.35,1.35001,2.0}){
            boolean expected=price>=1.20&&price<=1.35;
            var actual=obj("kind","moneyline","selection","home","odds",price,"p_win",.99,"ev",.99*price-1,"scenario_ev_min",.98*price-1,"research_eligible",true);
            var quoted=Recommendations.select(game,arr().add(actual),arr(),obj("warnings",arr()),"2026-10-20T18:00:00Z");
            var indicative=Recommendations.select(game,arr(),arr().add(estimate("handicap",price,.99,.98)),obj("warnings",arr()),"2026-10-20T18:00:00Z");
            assertEquals(expected,quoted!=null,"actual price="+price);assertEquals(expected,indicative!=null,"estimated price="+price);
        }
    }
}
