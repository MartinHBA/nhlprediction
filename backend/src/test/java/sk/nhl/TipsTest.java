package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class TipsTest {
    MemoryStore store;Workflow workflow;ObjectNode day;Instant now;String date="2099-01-01";
    static ObjectNode dataset(Instant now){
        ObjectNode data=resource("demo.json").deepCopy();data.put("as_of",now.toString()).put("synthetic_data",false);String checked=now.minusSeconds(30).toString();
        for(JsonNode e:data.path("evidence"))((ObjectNode)e).put("checked_at",checked);
        for(JsonNode g:data.path("games")){
            ((ObjectNode)g).put("starts_at","2099-01-02T00:00:00Z");
            for(String side:List.of("home","away"))for(String group:List.of("prior","current","goalie","roster"))((ObjectNode)at(g,side+"."+group+".source")).put("status","verified").put("checked_at",checked);
            for(JsonNode q:g.path("markets")){
                ((ObjectNode)q).put("id","oddize-"+text(q,"id")).put("provider","Oddize").put("bookmaker","FanDuel").put("bookmaker_code","fd").put("observed_at",checked).put("snapshot_at",checked).set("quality_warnings",arr());
                ((ObjectNode)q.path("source")).put("status","verified").put("checked_at",checked);
            }
        }return data;
    }
    @BeforeEach void setup(){
        now=Instant.now();ObjectNode data=dataset(now);assertTrue(Checks.audit(data).path("complete").asBoolean());
        day=obj("day",date,"state","READY_FOR_ANALYSIS","revision",7,"research_complete",true,"dataset",data,"official_games",data.path("games").deepCopy(),"updated_at",now.toString());
        store=new MemoryStore();store.create("days/"+date+".json",day);workflow=new Workflow(store,d->obj(),j->{throw new AssertionError("No prediction should ever be queued");});
    }
    JsonNode game(){return day.path("dataset").path("games").get(0);}
    JsonNode candidate(){
        JsonNode raw=game().path("markets").valueStream().filter(c->text(c,"kind").equals("handicap")).findFirst().orElseThrow();ObjectNode quote=raw.deepCopy();quote.put("price",number(raw,"odds")).put("odds_type","QUOTED");
        ArrayNode out=arr();MarketQuotes.add(out,game(),quote,arr(),text(raw,"id"));return out.get(0);
    }
    ObjectNode body(boolean tip){
        ArrayNode decisions=arr();int i=0;for(JsonNode g:day.path("dataset").path("games")){
            boolean select=tip&&i++==0;decisions.add(obj("game_id",text(g,"game_id"),"decision",select?"TIP":"NO_BET","candidate_id",select?text(candidate(),"candidate_id"):null,
                "confidence","MODERATE","rationale","Konkrétne analytické zhodnotenie zostáv, brankárov a údajov tohto zápasu.",
                "comparison","Porovnané gólové hranice, víťaz a handicap podľa dostupného researchu.","risks",List.of("Presnú dostupnosť zostáv a cenu treba overiť pred začiatkom."),"evidence_ids",select?List.of("demo"):List.of()));
        }return obj("day",date,"revision",day.path("revision"),"analysis_id",Tips.analysisId(day,now),"decisions",decisions);
    }
    @Test void snapshotContainsResearchAndAllPricesWithoutPredictions(){
        var result=workflow.candidates(date,"");assertEquals("READY",text(result,"state"));assertEquals(3,result.path("games").size());
        var g=result.path("games").get(0);assertTrue(g.path("home").has("roster"));assertFalse(g.path("evidence").isEmpty());assertFalse(g.path("checks").isEmpty());
        for(JsonNode c:g.path("candidates"))assertTrue(MarketQuotes.inRange(number(c,"price")));
        assertFalse(result.toString().contains("\"p_win\""));assertFalse(result.toString().contains("\"model_ev\""));assertFalse(result.has("simulations"));assertFalse(g.has("projection"));
    }
    @Test void publishesGptChoiceWithoutAnyJavaReportAndRetainsGptConfidence(){
        var result=workflow.postTips(date,body(true));assertEquals("PUBLISHED",text(result,"final_tips.state"));assertEquals("COMPLETE",text(result,"state"));assertEquals(8,result.path("revision").asInt());
        JsonNode selected=result.path("final_tips").path("games").get(0);assertEquals("handicap",text(selected,"candidate.kind"));assertEquals(number(candidate(),"price"),number(selected,"candidate.price"));assertEquals("MODERATE",text(selected,"confidence"));assertFalse(workflow.read(date).has("report"));
    }
    @Test void idempotentPostAndRevisionSnapshotGuards(){
        var args=body(true);var first=workflow.postTips(date,args);assertEquals(first,workflow.postTips(date,args));
        var different=body(false);assertThrows(BusyException.class,()->workflow.postTips(date,different));
        ObjectNode changed=day.deepCopy();((ArrayNode)changed.path("dataset").path("games").get(0).path("home").path("roster").path("absences")).add("New injury");store.put("days/"+date+".json",changed);
        assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));
    }
    @Test void rejectsMissingDuplicateAndForeignGameSelections(){
        var missing=body(true);((ArrayNode)missing.path("decisions")).remove(2);assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,missing));
        var duplicate=body(true);((ObjectNode)duplicate.path("decisions").get(1)).put("game_id",text(game(),"game_id"));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,duplicate));
        var foreign=body(true);((ObjectNode)foreign.path("decisions").get(0)).put("candidate_id",text(MarketQuotes.all(day.path("dataset").path("games").get(1),now).get(0),"candidate_id"));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,foreign));
    }
    @Test void cannotInjectPricesProbabilitiesOrInventEvidence(){
        for(String field:List.of("odds","p_win","model_ev")){var args=body(true);((ObjectNode)args.path("decisions").get(0)).put(field,1.25);assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));}
        var args=body(true);((ObjectNode)args.path("decisions").get(0)).set("evidence_ids",arr().add("invented"));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));
    }
    @Test void unverifiedPricesAndSyntheticTipsCannotPublish(){
        var args=body(true);for(JsonNode m:game().path("markets"))if(text(m,"candidate_id").equals(text(candidate(),"candidate_id")))fail("Raw markets must not be predictive candidates");
        for(JsonNode m:game().path("markets"))if(text(m,"kind").equals("handicap"))((ObjectNode)m.path("source")).put("status","unverified");
        store.put("days/"+date+".json",day);args.put("analysis_id",Tips.analysisId(day,now));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));
        ((ObjectNode)day.path("dataset")).put("synthetic_data",true);store.put("days/"+date+".json",day);args.put("analysis_id",Tips.analysisId(day,now));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));
    }
    @Test void allNoBetIsSuccessfulPublication(){var result=workflow.postTips(date,body(false));assertEquals("PUBLISHED",text(result,"final_tips.state"));assertEquals(3,result.path("final_tips").path("no_bet_count").asInt());}
    @Test void publishedDecisionSurvivesChangedWorkingDataAndNewTimestamps(){
        workflow.postTips(date,body(true));ObjectNode saved=workflow.require(date);JsonNode original=saved.path("final_tips").deepCopy();
        for(JsonNode g:saved.path("dataset").path("games"))for(JsonNode q:g.path("markets"))((ObjectNode)q.path("source")).put("checked_at",now.toString());
        store.put("days/"+date+".json",saved);assertEquals("PUBLISHED",text(workflow.read(date),"final_tips.state"));
        ((ObjectNode)saved.path("dataset").path("games").get(0).path("markets").get(0)).put("odds",1.29);store.put("days/"+date+".json",saved);
        assertEquals("PUBLISHED",text(workflow.read(date),"final_tips.state"));assertEquals(original,workflow.require(date).path("final_tips"));
    }
    @Test void publishedTipsSurviveAgingAndStartedGames(){workflow.postTips(date,body(true));var saved=workflow.require(date);assertEquals("PUBLISHED",text(Tips.view(saved,now.plusSeconds(7201)),"state"));((ObjectNode)saved.path("dataset").path("games").get(0)).put("starts_at",now.minusSeconds(1).toString());assertEquals("CURRENT",text(Tips.view(saved,now).path("games").get(0),"state"));}
    @Test void newStrategyKeepsCompleteHistoricalDataOnlyQuoteMetadata(){
        workflow.postTips(date,body(true));ObjectNode saved=workflow.require(date);((ObjectNode)saved.path("final_tips")).put("policy_version","3.0.0-data");
        JsonNode original=saved.path("final_tips").path("games").get(0).path("candidate").deepCopy();
        assertTrue(original.has("settlement_verified"));assertTrue(original.has("blocking_reasons"));
        assertEquals(original,Tips.view(saved,now.plusSeconds(86400)).path("games").get(0).path("candidate"));
    }
    @Test void incompleteResearchBlocksPublicationButNeverBlocksReadingData(){day.put("research_complete",false);store.put("days/"+date+".json",day);assertEquals("READY",text(workflow.candidates(date,""),"state"));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,body(false)));}
    @Test void newMcpDataFlowRejectsLegacySimulationParameter(){
        Mcp mcp=new Mcp(workflow);var response=mcp.call("get_analysis",obj("day",date));assertFalse(response.path("isError").asBoolean());
        assertThrows(IllegalArgumentException.class,()->mcp.call("finalize_day",obj("day",date,"sims",1000)));
        response=mcp.call("post_tips",body(true));assertFalse(response.path("isError").asBoolean());
    }
    @Test void compactSnapshotKeepsTheSameCandidateAndBasisIdsWithoutRepeatingResearch(){
        var full=workflow.candidates(date,"");var compact=Tips.snapshot(day,now);
        assertEquals(full.path("analysis_id"),compact.path("analysis_id"));
        for(int i=0;i<compact.path("games").size();i++){
            var brief=compact.path("games").get(i);assertEquals(full.path("games").get(i).path("basis_id"),brief.path("basis_id"));assertFalse(brief.has("home"));assertFalse(brief.has("evidence"));
            assertEquals(full.path("games").get(i).path("candidates").get(0).path("candidate_id"),brief.path("candidates").get(0).path("candidate_id"));
        }
        Mcp mcp=new Mcp(workflow);assertFalse(mcp.call("get_analysis",obj("day",date,"summary_only",true)).path("isError").asBoolean());
        assertTrue(mcp.call("get_analysis",obj("day",date,"summary_only",true,"game_id",text(game(),"game_id"))).path("isError").asBoolean());
    }
    @Test void migratedPredictionsAreNeverExposedAsDataOrCurrentTips(){
        workflow.postTips(date,body(true));var old=workflow.require(date);((ObjectNode)old.path("final_tips")).remove("policy_version");old.set("report",obj("games",arr().add(obj("lambda_total",5,"p_win",.9))));store.put("days/"+date+".json",old);
        var result=workflow.read(date);assertFalse(result.has("report"));assertEquals("PUBLISHED",text(result,"final_tips.state"));assertFalse(result.path("final_tips").path("games").get(0).path("candidate").has("p_win"));
    }
    @Test void researchContextCanBeSavedWithOmittedBackendPricesThenPublished(){
        ObjectNode edit=Mcp.editable(workflow.require(date),text(game(),"game_id"));
        assertTrue(edit.path("game").path("markets").isEmpty());assertTrue(edit.path("backend_prefilled_markets_omitted").asInt()>0);
        ((ObjectNode)edit.path("game")).set("analysis_context",arr().add(obj("topic","Recent form","summary","Verified contextual research for this exact game.","evidence_ids",List.of("new-proof"))));
        var proof=obj("id","new-proof","url","https://nhl.example/research","checked_at",now.minusSeconds(1).toString());
        workflow.saveGame(date,edit.path("game"),arr().add(proof),7);
        assertEquals(game().path("markets"),workflow.require(date).path("dataset").path("games").get(0).path("markets"));
        assertEquals("READY_FOR_ANALYSIS",text(workflow.finish(date),"state"));day=workflow.require(date);
        assertEquals("PUBLISHED",text(workflow.postTips(date,body(true)),"final_tips.state"));
    }
    @Test void unavailableStatisticalInputDoesNotRequireOrBlockAnyPredictionCalculation(){
        ((ObjectNode)game().path("home").path("current")).putNull("xgf_5v5_per60");
        ((ObjectNode)game().path("checks").path("fields")).set("home.current.xgf_5v5_per60",obj("status","unavailable","note","Source does not publish this input.","evidence_ids",List.of("demo")));
        store.put("days/"+date+".json",day);assertEquals("READY_FOR_ANALYSIS",text(workflow.finish(date),"state"));
        assertEquals("PUBLISHED",text(workflow.postTips(date,body(false)),"final_tips.state"));
    }
}
