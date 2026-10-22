package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class ResearchProgressTest {
    Instant now=Instant.parse("2026-10-22T18:30:00Z");
    ObjectNode day(){return obj("day","2099-01-01","state","COLLECTING","revision",7,"dataset",TipsTest.dataset(now));}
    ObjectNode game(ObjectNode d,int index){return (ObjectNode)d.path("dataset").path("games").get(index);}
    ObjectNode fields(ObjectNode game){return (ObjectNode)game.path("checks").path("fields");}
    JsonNode first(ObjectNode d){return ResearchProgress.plan(d,now).path("games").get(0);}
    @Test void mixedSavedResearchOnlyRequestsMissingWorkAndDoesNotMutateAnything(){
        ObjectNode d=day();fields(game(d,1)).set("away.roster.scratches",Schedule.check());
        ((ObjectNode)game(d,1).path("checks").path("market_searches")).set("total_over",Schedule.check());
        ObjectNode before=d.deepCopy(),plan=ResearchProgress.plan(d,now);
        assertEquals(1,plan.path("games_requiring_collection").asInt());assertEquals(2,plan.path("games_reusing_research").asInt());
        assertEquals("SKIP_COMPLETE",text(plan.path("games").get(0),"action"));
        JsonNode pending=plan.path("games").get(1);assertEquals("RESEARCH_PENDING",text(pending,"action"));
        assertEquals(List.of("away.roster.scratches"),strings(pending.path("unchecked_fields")));
        assertEquals(List.of("total_over"),strings(pending.path("unchecked_market_searches")));assertEquals(before,d);
        assertEquals("FINALIZE_AND_ANALYZE",text(ResearchProgress.plan(day(),now),"next_step"));
    }
    @Test void closedUnavailableDataIsReusedButEvidenceErrorsMustBeRepaired(){
        ObjectNode d=day(),g=game(d,0);int closed=first(d).path("closed_unavailable_count").asInt();((ObjectNode)g.path("home").path("current")).putNull("points_pct");
        fields(g).set("home.current.points_pct",obj("status","unavailable","evidence_ids",List.of("demo"),"note","Source does not publish this statistic."));
        assertEquals("SKIP_COMPLETE",text(first(d),"action"));assertEquals(closed+1,first(d).path("closed_unavailable_count").asInt());
        ((ObjectNode)fields(g).path("home.current.points_pct")).set("evidence_ids",arr().add("missing-proof"));
        assertEquals("REPAIR",text(first(d),"action"));assertFalse(first(d).path("issues").isEmpty());
    }
    @Test void staleGoalieAndLineupsAreTargetedWithoutRepeatingHistoricalStatistics(){
        ObjectNode d=day();for(JsonNode e:d.path("dataset").path("evidence"))((ObjectNode)e).put("checked_at",now.minusSeconds(21600).toString());
        String id=text(game(d,0),"game_id");d.set("auto_fields",obj(id,List.of("home.goalie.name")));
        JsonNode progress=first(d);assertEquals("REFRESH_TIME_SENSITIVE",text(progress,"action"));
        var refresh=strings(progress.path("refresh_fields"));assertTrue(refresh.contains("away.goalie.status"));assertTrue(refresh.contains("home.roster.forward_lines"));
        assertFalse(refresh.contains("home.goalie.name"));assertFalse(refresh.stream().anyMatch(p->p.contains(".prior.")||p.contains(".current.")||p.contains("gsax")||p.contains("save_pct")||p.contains("delta")||p.endsWith("quantified")));
        assertTrue(progress.path("unchecked_fields").isEmpty());
    }
    @Test void freshnessTightensNearStartAndStartedGamesAreSkipped(){
        ObjectNode d=day(),g=game(d,0);g.put("starts_at",now.plusSeconds(5401).toString());
        for(JsonNode e:d.path("dataset").path("evidence"))((ObjectNode)e).put("checked_at",now.minusSeconds(1800).toString());
        assertEquals("SKIP_COMPLETE",text(first(d),"action"));g.put("starts_at",now.plusSeconds(5400).toString());
        assertEquals("REFRESH_TIME_SENSITIVE",text(first(d),"action"));g.put("starts_at",now.toString());
        assertEquals("SKIP_STARTED",text(first(d),"action"));assertTrue(first(d).path("refresh_fields").isEmpty());
    }
    @Test void futureEvidenceIsNeverAcceptedAsCurrentResearch(){
        ObjectNode d=day();((ObjectNode)d.path("dataset").path("evidence").get(0)).put("checked_at",now.plusSeconds(1).toString());
        assertEquals("REPAIR",text(first(d),"action"));assertFalse(ResearchProgress.plan(d,now).path("checklist_issues").isEmpty());
    }
    @Test void mcpResumeReusesSavedImportDespiteWarningsAndReturnsOnlyAnMcpPlan(){
        ObjectNode d=day();d.put("updated_at",now.toString());d.set("enrichment",obj("version",DataCollector.VERSION,"state","DONE","updated_at",now.toString(),"warnings",List.of("One optional source unavailable")));
        MemoryStore store=new MemoryStore();store.put("days/2099-01-01.json",d);AtomicInteger downloads=new AtomicInteger();List<ObjectNode> jobs=new ArrayList<>();
        DataCollector collector=new DataCollector(store,url->{downloads.incrementAndGet();throw new AssertionError("A normal resume must not fetch sources");},Clock.systemUTC());
        Workflow w=new Workflow(store,date->{throw new AssertionError("Do not reset the official schedule");},jobs::add,collector);Mcp mcp=new Mcp(w);
        JsonNode result=parse(text(mcp.call("start_day",obj("day","2099-01-01")).path("content").get(0),"text"));
        assertTrue(result.has("resume_plan"));assertEquals(7,result.path("revision").asInt());assertTrue(jobs.isEmpty());assertEquals(0,downloads.get());assertEquals(d,w.require("2099-01-01"));
        assertTrue(parse(text(mcp.call("get_results",obj("day","2099-01-01")).path("content").get(0),"text")).has("resume_plan"));
        assertFalse(w.summary(d).has("resume_plan"));assertFalse(w.readCards(d).has("resume_plan"));
        JsonNode refresh=parse(text(mcp.call("start_day",obj("day","2099-01-01","refresh_sources",true)).path("content").get(0),"text"));
        assertEquals("PREFILLING",text(refresh,"state"));assertEquals("WAIT_FOR_IMPORT",text(refresh,"resume_plan.next_step"));assertEquals(1,jobs.size());
        mcp.call("start_day",obj("day","2099-01-01","refresh_sources",true));assertEquals(1,jobs.size());assertEquals(0,downloads.get());
    }
    @Test void publishedResearchStaysFrozenUntilAnExplicitNewSession(){
        TipsTest f=new TipsTest();f.setup();f.workflow.postTips(f.date,f.body(true));ObjectNode published=f.workflow.require(f.date);
        assertEquals("KEEP_PUBLISHED",text(ResearchProgress.plan(published,f.now),"next_step"));
        published.set("research_session",obj("active",true));fields(game(published,0)).set("home.roster.returns",Schedule.check());
        assertEquals("RESEARCH_PENDING",text(ResearchProgress.plan(published,f.now).path("games").get(0),"action"));
    }
}
