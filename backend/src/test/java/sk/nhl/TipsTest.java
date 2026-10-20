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
    @BeforeEach void setup(){
        now=Instant.now();ObjectNode dataset=resource("demo.json").deepCopy();dataset.put("as_of",now.toString());dataset.put("synthetic_data",false);
        String checked=now.minusSeconds(30).toString();for(JsonNode e:dataset.path("evidence"))((ObjectNode)e).put("checked_at",checked);
        for(JsonNode g:dataset.path("games")){
            ((ObjectNode)g).put("starts_at","2099-01-02T00:00:00Z");
            for(String side:List.of("home","away"))for(String group:List.of("prior","current","goalie","roster"))((ObjectNode)at(g,side+"."+group+".source")).put("status","verified").put("checked_at",checked);
            for(JsonNode q:g.path("markets"))((ObjectNode)q.path("source")).put("status","verified").put("checked_at",checked);
        }
        ObjectNode report=Engine.run(dataset,1000,1707,false);assertTrue(report.path("checklist").path("complete").asBoolean());
        day=obj("day",date,"state","COMPLETE","revision",7,"research_complete",true,"dataset",dataset,"report",report,"updated_at",now.toString());
        store=new MemoryStore();store.create("days/"+date+".json",day);workflow=new Workflow(store,d->obj(),j->{});
    }
    JsonNode game(){return day.path("dataset").path("games").get(0);}
    JsonNode candidate(){return day.path("report").path("games").get(0).path("candidates").valueStream().filter(c->text(c,"kind").equals("handicap")).findFirst().orElseThrow();}
    ObjectNode body(boolean tip){
        ArrayNode decisions=arr();int i=0;for(JsonNode g:day.path("dataset").path("games")){
            boolean select=tip&&i++==0;
            decisions.add(obj("game_id",text(g,"game_id"),"decision",select?"TIP":"NO_BET","candidate_id",select?text(candidate(),"candidate_id"):null,
                "confidence","HIGH","rationale","Konkrétne zhodnotenie uložených zostáv a brankárov pre tento zápas.",
                "comparison","Porovnal som víťaza, handicap aj gólové hranice a ich neistoty.",
                "risks",List.of("Model výber nepodporuje dostatočne; cena a výhoda potrebujú kontrolu."),"evidence_ids",select?List.of("demo"):List.of()));
        }
        return obj("day",date,"revision",7,"analysis_id",Tips.analysisId(day,now),"decisions",decisions);
    }
    @Test void exposesAllBandCandidatesIncludingModelDisagreementAndResearch(){
        var result=workflow.candidates(date,"");assertEquals("READY",text(result,"state"));assertEquals(3,result.path("games").size());
        var g=result.path("games").get(0);assertTrue(g.path("home").has("roster"));assertFalse(g.path("evidence").isEmpty());
        assertTrue(g.path("candidates").valueStream().anyMatch(c->text(c,"kind").equals("moneyline")));
        assertTrue(g.path("candidates").valueStream().anyMatch(c->!c.path("model_positive_ev").asBoolean()));
        for(JsonNode c:g.path("candidates"))assertTrue(Recommendations.inRange(number(c,"price")));
    }
    @Test void publishesExplicitAiSelectionRatherThanModelPreferenceAndRetainsCanonicalPrice(){
        ObjectNode args=body(true);JsonNode originalReport=day.path("report").deepCopy();
        var result=workflow.postTips(date,args);assertEquals("PUBLISHED",text(result,"final_tips.state"));assertEquals(8,result.path("revision").asInt());
        JsonNode decision=result.path("final_tips").path("games").get(0);assertEquals("handicap",text(decision,"candidate.kind"));assertEquals(number(candidate(),"price"),number(decision,"candidate.price"));
        assertEquals("LOW",text(decision,"confidence"));assertEquals("CHATGPT",text(decision,"chosen_by"));
        assertEquals(originalReport,workflow.require(date).path("report"));assertEquals(1,result.path("final_tips").path("tip_count").asInt());
    }
    @Test void repeatedIdenticalPostIsIdempotentEvenWithOriginalRevision(){
        ObjectNode args=body(true);var first=workflow.postTips(date,args);var saved=workflow.require(date);
        assertEquals(first,workflow.postTips(date,args));assertEquals(saved,workflow.require(date));
    }
    @Test void rejectsStaleRevisionAndChangedAnalysisSnapshot(){
        ObjectNode args=body(true),changed=day.deepCopy();changed.put("revision",8);store.put("days/"+date+".json",changed);
        assertThrows(BusyException.class,()->workflow.postTips(date,args));assertFalse(workflow.require(date).has("final_tips"));
        changed.put("revision",7);((ArrayNode)changed.path("dataset").path("games").get(0).path("home").path("roster").path("absences")).add("New injury");store.put("days/"+date+".json",changed);
        assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));
    }
    @Test void rejectsUnknownDuplicateMissingGamesAndCrossGameCandidate(){
        ObjectNode missing=body(true);((ArrayNode)missing.path("decisions")).remove(2);assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,missing));
        ObjectNode duplicate=body(true);((ObjectNode)duplicate.path("decisions").get(1)).put("game_id",text(game(),"game_id"));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,duplicate));
        ObjectNode foreign=body(true);((ObjectNode)foreign.path("decisions").get(0)).put("candidate_id",text(day.path("report").path("games").get(1).path("candidates").get(0),"candidate_id"));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,foreign));
    }
    @Test void cannotSubmitPricesProbabilitiesOrUnknownEvidence(){
        ObjectNode args=body(true);((ObjectNode)args.path("decisions").get(0)).put("odds",1.25);assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));
        ObjectNode evidence=body(true);((ObjectNode)evidence.path("decisions").get(0)).set("evidence_ids",arr().add("invented"));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,evidence));
    }
    @Test void blocksUnselectableCandidateAndSyntheticTip(){
        ObjectNode args=body(true);ObjectNode modified=day.deepCopy();String id=text(candidate(),"candidate_id");
        for(JsonNode c:modified.path("report").path("games").get(0).path("candidates"))if(text(c,"candidate_id").equals(id))((ObjectNode)c).put("selectable",false);
        store.put("days/"+date+".json",modified);assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));
        modified=day.deepCopy();((ObjectNode)modified.path("dataset")).put("synthetic_data",true);store.put("days/"+date+".json",modified);
        args.put("analysis_id",Tips.analysisId(modified,now));assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,args));
    }
    @Test void noBetForEveryGameIsSuccessfulPublication(){
        var result=workflow.postTips(date,body(false));assertEquals("PUBLISHED",text(result,"final_tips.state"));assertEquals(0,result.path("final_tips").path("tip_count").asInt());assertEquals(3,result.path("final_tips").path("no_bet_count").asInt());
    }
    @Test void changedOddsRequireReviewAndNeverOverwriteAiSelection(){
        workflow.postTips(date,body(true));JsonNode original=workflow.require(date).path("final_tips").deepCopy();
        ObjectNode changed=workflow.require(date);((ObjectNode)changed.path("dataset").path("games").get(0).path("markets").get(0)).put("odds",1.29);
        changed.put("state","QUEUED").put("job_id","recompute");store.put("days/"+date+".json",changed);workflow.calculate(obj("day",date,"job_id","recompute","sims",1000));
        assertEquals(original,workflow.require(date).path("final_tips"));assertEquals("NEEDS_REVIEW",text(workflow.read(date),"final_tips.state"));
    }
    @Test void unchangedRecalculationAndRefreshedTimestampsPreserveAiDecisions(){
        workflow.postTips(date,body(true));ObjectNode refreshed=workflow.require(date);
        for(JsonNode g:refreshed.path("dataset").path("games"))for(JsonNode q:g.path("markets"))((ObjectNode)q.path("source")).put("checked_at",now.toString());
        refreshed.put("state","QUEUED").put("job_id","same-data");store.put("days/"+date+".json",refreshed);workflow.calculate(obj("day",date,"job_id","same-data","sims",1000));
        assertEquals("PUBLISHED",text(workflow.read(date),"final_tips.state"));
    }
    @Test void staleOddsAndStartedGamesAreNotShownAsCurrent(){
        workflow.postTips(date,body(true));ObjectNode saved=workflow.require(date);
        assertEquals("NEEDS_REVIEW",text(Tips.view(saved,now.plusSeconds(7201)),"state"));
        ((ObjectNode)saved.path("dataset").path("games").get(0)).put("starts_at",now.minusSeconds(1).toString());
        assertEquals("STARTED",Tips.view(saved,now).path("games").get(0).path("state").asText());
    }
    @Test void missingOrOldEngineCalculationRequiresFinalization(){
        day.putNull("report");store.put("days/"+date+".json",day);assertEquals("CALCULATION_REQUIRED",text(workflow.candidates(date,""),"state"));
        assertThrows(IllegalArgumentException.class,()->workflow.postTips(date,body(false)));
    }
    @Test void newMcpToolsRoundTripTheAnalysisAndPublication(){
        Mcp mcp=new Mcp(workflow);JsonNode response=mcp.call("get_candidates",obj("day",date,"game_id",text(game(),"game_id")));
        assertFalse(response.path("isError").asBoolean());JsonNode snapshot=parse(response.path("content").get(0).path("text").asText());assertEquals(1,snapshot.path("games").size());
        response=mcp.call("post_tips",body(true));assertFalse(response.path("isError").asBoolean());
        assertEquals("PUBLISHED",text(parse(response.path("content").get(0).path("text").asText()),"final_tips.state"));
    }
    @Test void changedCheckStatusInvalidatesSelectionButNewNotesAloneDoNot(){
        workflow.postTips(date,body(true));ObjectNode modified=workflow.require(date);
        ObjectNode check=(ObjectNode)modified.path("dataset").path("games").get(0).path("checks").path("fields").path("home.current.games");
        check.put("note","Same value independently rechecked.");assertEquals("PUBLISHED",text(Tips.view(modified,now),"state"));
        check.put("status","not_checked");assertEquals("NEEDS_REVIEW",text(Tips.view(modified,now),"state"));
    }
}
