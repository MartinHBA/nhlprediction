package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class EngineTest {
    ObjectNode data;
    @BeforeEach void init(){data=resource("demo.json").deepCopy();}
    @Test void schemaAndChecklistMatchReference(){assertEquals(List.of(),Checks.validate(data));assertTrue(Checks.audit(data).path("complete").asBoolean());assertEquals(82,Checks.PATHS.size());assertEquals(Checks.audit(data),resource("reference-report.json").path("checklist"));}
    @Test void projectionMatchesReference(){JsonNode ref=resource("reference-report.json");for(int i=0;i<3;i++){var p=Engine.project(data.path("games").get(i));assertEquals(number(ref.path("games").get(i),"lambda_home"),Engine.round(p.home(),4),1e-9);assertEquals(number(ref.path("games").get(i),"lambda_away"),Engine.round(p.away(),4),1e-9);}}
    @Test void simulationProbabilityAndReproducibility(){var sim=Engine.simulate(3.4,2.8,150000,123);var m=obj("kind","total","selection","over","line",4.5,"score_basis","regulation");var p=Engine.settle(m,sim);assertEquals(1,p[0]+p[1]+p[2],1e-10);assertTrue(p[0]>.70&&p[0]<.80);assertArrayEquals(sim.fh(),Engine.simulate(3.4,2.8,150000,123).fh());for(int i=0;i<sim.fh().length;i++)assertNotEquals(sim.fh()[i],sim.fa()[i]);}
    @Test void integerPushAndEv(){var sim=new Engine.Scores(new int[]{2,2,3},new int[]{2,3,3},new int[]{2,2,3},new int[]{2,3,3});var p=Engine.settle(obj("kind","total","selection","over","line",5,"score_basis","official_final"),sim);assertArrayEquals(new double[]{1.0/3,1.0/3,1.0/3},p);assertEquals(-1.0/30,Engine.ev(p,1.9),1e-10);}
    @Test void regulationAndMoneylineDiffer(){var s=new Engine.Scores(new int[]{2,2,1},new int[]{2,1,2},new int[]{3,2,1},new int[]{2,1,2});assertEquals(2.0/3,Engine.settle(obj("kind","moneyline","selection","home","score_basis","official_final"),s)[0]);assertEquals(1.0/3,Engine.settle(obj("kind","regulation_1x2","selection","draw","score_basis","regulation"),s)[0]);}
    @Test void incompleteChecklistBlocksTicket(){((ObjectNode)at(data,"games").get(0).path("checks").path("fields")).remove("home.current.xgf_5v5_per60");assertEquals("CHECKLIST_INCOMPLETE",text(Engine.run(data,1000,1,false),"status"));}
    @Test void unknownEvidenceBlocksCompletion(){// Check names are literal dotted keys.
        ObjectNode c=(ObjectNode)data.path("games").get(0).path("checks").path("fields").path("home.current.games");c.set("evidence_ids",M.valueToTree(List.of("fake")));assertFalse(Checks.audit(data).path("complete").asBoolean());assertTrue(Checks.audit(data).path("issues").toString().contains("UNKNOWN_EVIDENCE_ID"));}
    @Test void nullableOptionalFieldsAreDifferentFromMissingCritical(){ObjectNode g=(ObjectNode)data.path("games").get(0);((ObjectNode)at(g,"home.current")).putNull("points_pct");((ObjectNode)g.path("checks").path("fields")).set("home.current.points_pct",obj("status","unavailable","evidence_ids",List.of("demo"),"note","Not published"));assertTrue(Checks.audit(data).path("complete").asBoolean());assertEquals("SYNTHETIC_DEMO_ONLY",text(Engine.run(data,1000,1,false),"status"));((ObjectNode)at(g,"home.current")).putNull("xga_5v5_per60");((ObjectNode)g.path("checks").path("fields")).set("home.current.xga_5v5_per60",obj("status","unavailable","evidence_ids",List.of("demo"),"note","Not published"));assertEquals("INSUFFICIENT_DATA",text(Engine.run(data,1000,1,false),"status"));}
    @Test void foundNullAndUnavailablePresentFailAudit(){ObjectNode g=(ObjectNode)data.path("games").get(0);((ObjectNode)at(g,"home.current")).putNull("points_pct");assertFalse(Checks.audit(data).path("complete").asBoolean());}
    @Test void syntheticNeverProducesTicketAndSuspensionPreserved(){var r=Engine.run(data,1000,1707,true);assertEquals("SYNTHETIC_DEMO_ONLY",text(r,"status"));assertTrue(r.path("ticket").isNull());assertEquals(3,r.path("audit").path("blocked_team_over_2_5").asInt());}
    @Test void timestampAndScheduleFraudRejected(){((ObjectNode)at(data.path("games").get(0),"home.goalie.source")).put("checked_at","2026-10-22T01:00:00Z");assertTrue(Checks.validate(data).stream().anyMatch(x->x.contains("cutoff")));((ArrayNode)data.path("expected_game_ids")).remove(0);assertTrue(Checks.validate(data).contains("SCHEDULE_MISMATCH"));}
    @Test void invalidMarketAndSettlementRejected(){ObjectNode m=(ObjectNode)data.path("games").get(0).path("markets").get(3);m.put("score_basis","regulation");m.putNull("rules_url");assertTrue(Checks.validate(data).stream().anyMatch(x->x.contains("moneyline")));assertTrue(Checks.validate(data).stream().anyMatch(x->x.contains("rules_url")));}
    @Test void probableGoalieAndStaleOddsAreBlocked(){ObjectNode g=(ObjectNode)data.path("games").get(0);((ObjectNode)at(g,"away.goalie")).put("status","probable");((ObjectNode)at(g.path("markets").get(0),"source")).put("checked_at","2026-10-20T01:00:00Z");var r=Engine.run(data,1000,1,false);String issues=r.path("games").get(0).path("markets").get(0).path("issues").toString();assertTrue(issues.contains("GOALIE_NOT_CONFIRMED"));assertTrue(issues.contains("ODDS_STALE_OVER_2_HOURS"));}
    @Test void liveGamesAreSkipped(){ObjectNode g=(ObjectNode)data.path("games").get(0);g.put("status","live");g.set("markets",arr());((ObjectNode)g.path("checks").path("fields")).removeAll();assertTrue(Checks.audit(data).path("complete").asBoolean());assertEquals("ALREADY_STARTED_OR_FINAL",text(Engine.run(data,1000,1,false).path("games").get(0),"skipped"));}
    @Test void beamNeverStacksSameGame(){ArrayNode games=arr();for(int i=0;i<3;i++)games.add(obj("game_id","g"+i,"status","scheduled","matchup","A @ B","markets",List.of(obj("id","a","p_push",0,"p_win",.9,"odds",1.25,"scenario_ev_min",.1,"research_eligible",true),obj("id","b","p_push",0,"p_win",.95,"odds",1.22,"scenario_ev_min",.12,"research_eligible",true))));var c=Engine.combo(games);Set<String>seen=new HashSet<>();for(JsonNode l:c.path("legs"))assertTrue(seen.add(text(l,"game_id")));assertTrue(number(c,"odds")<=4);}
    @Test void goalieSmallSampleShrinks(){assertTrue(Math.abs(Engine.goalie(obj("starts_current",2,"starts_prior",80,"gsax_per60_current",2.5,"gsax_per60_prior",.05)))<.15);assertTrue(Engine.blend(2.4,7.2,2)<3);}
    @Test void uncertainLineupIsNotAnOutOfRangeLambda(){
        ObjectNode g=(ObjectNode)data.path("games").get(0);((ObjectNode)g.path("home").path("roster")).put("quantified",false);
        var result=Engine.evaluate(g,text(data,"as_of"),1000,42);
        assertTrue(result.path("projection_details").path("warnings").toString().contains("LINEUP_DELTAS_UNKNOWN"));
        for(var market:result.path("markets"))assertFalse(strings(market.path("issues")).contains("LAMBDA_OUT_OF_RANGE"));
        ((ObjectNode)g.path("home").path("roster")).put("attack_delta_goals",30);
        result=Engine.evaluate(g,text(data,"as_of"),1000,42);
        for(var market:result.path("markets"))assertTrue(strings(market.path("issues")).contains("LAMBDA_OUT_OF_RANGE"));
        assertTrue(result.path("model_preference").isNull());
    }
    @Test void alternativeCalculationsOnlyExposeCountAndSingleSelection(){
        ObjectNode g=(ObjectNode)data.path("games").get(0);
        g.set("markets",arr().add(AlternativeMarketsTest.quote("total","over",6.5,1.9)).add(AlternativeMarketsTest.quote("total","under",6.5,1.9))
            .add(AlternativeMarketsTest.quote("handicap","home",-1.5,2.5)).add(AlternativeMarketsTest.quote("handicap","away",1.5,1.6)));
        var result=Engine.evaluate(g,AlternativeMarketsTest.AS_OF,10000,42);
        assertEquals(34,result.path("alternative_market_count").asInt());assertEquals(4,result.path("markets").size());
        assertFalse(result.has("estimated_totals"));assertFalse(result.has("estimated_handicaps"));assertTrue(result.has("model_preference"));
    }
    @Test void realMarketFiltersShareTheRecommendationPriceBand(){
        ObjectNode g=(ObjectNode)data.path("games").get(0),quote=(ObjectNode)g.path("markets").get(0);
        for(double price:new double[]{1.19,1.20,1.35,1.36}){
            quote.put("odds",price);var result=Engine.evaluate(g,text(data,"as_of"),1000,42);
            var issues=strings(result.path("markets").get(0).path("issues"));
            assertEquals(price<1.20,issues.contains("BELOW_PREFERRED_SINGLE_ODDS_FLOOR"));
            assertEquals(price>1.35,issues.contains("ABOVE_PREFERRED_SINGLE_ODDS_CAP"));
        }
    }
    @Test void largerLegacyRequestsAreLimitedTo1000SimulationsPerGame(){
        var result=Engine.run(data,100000,42,false);
        for(var game:result.path("games"))assertEquals(1000,game.path("simulations").asInt());
    }
}

