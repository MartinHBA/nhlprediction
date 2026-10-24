package sk.nhl;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class ProgramScheduleTest {
    final String day="2099-01-01";final Instant afternoon=Instant.parse("2099-01-01T12:00:00Z");
    @Test void thirteenOClockQueuesStatisticsAndRepeatedAfternoonAndEveningTicksReuseTheCompletedImport(){
        MemoryStore store=new MemoryStore();List<ObjectNode> jobs=new ArrayList<>();AtomicInteger schedules=new AtomicInteger(),sources=new AtomicInteger();
        DataCollector collector=new DataCollector(store,url->{sources.incrementAndGet();String content;
            if(url.endsWith(".csv")){String team=url.contains("BOS")?"BOS":"COL";content=DataCollectorTest.header+DataCollectorTest.gameRows(team,2098,"20981230",1)+DataCollectorTest.gameRows(team,2097,"20971001",1);}
            else content=obj("games",List.of(DataCollectorTest.official("prev","2098-12-30","2098-12-30T22:00:00Z","America/Denver"),DataCollectorTest.official("123",day,"2099-01-02T00:00:00Z","America/New_York"))).toString();
            return new DataCollector.Download(content,url,afternoon.toString());},Clock.fixed(afternoon,ZoneOffset.UTC));
        collector.odds=new OddizeOdds(store);
        Workflow w=new Workflow(store,date->{schedules.incrementAndGet();return DataCollectorTest.template(date,"2099-01-02T00:00:00Z");},jobs::add,collector);
        w.programTick(afternoon);w.programTick(afternoon.plusSeconds(1800));
        ObjectNode saved=w.require(day);assertEquals(1,schedules.get());assertEquals(1,jobs.size());assertEquals("QUEUED",text(saved,"enrichment.state"));
        assertEquals("PREFILLING",text(saved,"state"));assertTrue(saved.path("dataset").path("games").get(0).path("markets").isEmpty());
        assertTrue(saved.path("dataset").path("games").get(0).path("home").path("current").path("xgf_5v5_per60").isNull());
        assertEquals("QUEUED",text(w.readCards(saved),"enrichment.state"));assertEquals(1,w.readCards(saved).path("games").size());
        w.processImport(jobs.get(0));ObjectNode imported=w.require(day);assertEquals("DONE",text(imported,"enrichment.state"));
        for(String side:List.of("home","away")){var team=imported.path("dataset").path("games").get(0).path(side);assertTrue(team.path("current").path("xgf_5v5_per60").isNumber());assertTrue(team.path("prior").path("xgf_5v5_per60").isNumber());assertTrue(team.path("context").path("rest_days").isNumber());assertTrue(team.path("context").path("travel_timezones").isNumber());}
        assertEquals(32,Checks.coverage(imported.path("dataset")).path("critical_found").asInt());assertEquals(0,((OddizeOdds)collector.odds).client.requests);int fetched=sources.get();
        w.programTick(afternoon.plusSeconds(3600));
        w.eveningTick(Instant.parse("2099-01-01T17:00:00Z"));w.eveningTick(Instant.parse("2099-01-01T17:30:00Z"));
        assertEquals(1,jobs.size());assertEquals("prefill",text(jobs.get(0),"kind"));assertEquals("DONE",text(w.require(day),"enrichment.state"));assertEquals(fetched,sources.get());assertEquals("SCHEDULED",text(w.require(day),"odds_import.state"));
    }
    @Test void statisticsScheduleIsThirteenInBothSummerAndWinter(){
        for(String date:List.of("2026-10-24","2026-10-25")){
            MemoryStore store=new MemoryStore();Workflow w=new Workflow(store,d->DataCollectorTest.template(d,d+"T23:00:00Z"),job->{throw new AssertionError("Preparing metadata does not import");});
            ObjectNode prepared=w.prepareSchedule(date,afternoon);assertEquals(LocalDate.parse(date).atTime(13,0).atZone(ZoneId.of("Europe/Bratislava")).toInstant().toString(),text(w.require(date),"enrichment.scheduled_at"));
        }
    }
    @Test void noGamesNeverQueuesStatisticsOrPrices(){
        MemoryStore store=new MemoryStore();DataCollector collector=new DataCollector(store,url->{throw new AssertionError("No sources on an empty programme");},Clock.fixed(afternoon,ZoneOffset.UTC));collector.odds=new OddizeOdds(store);
        Workflow w=new Workflow(store,d->Schedule.template(obj("gameWeek",List.of(obj("date",d,"games",arr()))),d,afternoon.toString(),"https://example.org"),job->{throw new AssertionError("No jobs for NO_GAMES");},collector);
        w.programTick(afternoon);assertEquals("NO_GAMES",text(w.require(day),"state"));
    }
    @Test void programWindowAcceptsEarlyThirteenTriggerInBothSeasonsAndStopsBeforeAdvancedWindow(){
        assertFalse(Schedule.programWindow(Instant.parse("2026-10-24T10:44:59Z")));assertTrue(Schedule.programWindow(Instant.parse("2026-10-24T10:45:00Z")));
        assertTrue(Schedule.programWindow(Instant.parse("2026-10-24T11:00:00Z")));assertFalse(NhlOdds.pollingWindow(Instant.parse("2026-10-24T11:00:00Z")));
        assertFalse(Schedule.programWindow(Instant.parse("2026-10-25T11:44:59Z")));assertTrue(Schedule.programWindow(Instant.parse("2026-10-25T11:45:00Z")));
        assertTrue(Schedule.programWindow(Instant.parse("2026-10-25T12:00:00Z")));assertFalse(Schedule.programWindow(Instant.parse("2026-10-25T16:45:00Z")));assertTrue(NhlOdds.pollingWindow(Instant.parse("2026-10-25T16:45:00Z")));
        assertFalse(Schedule.programWindow(Instant.parse("2026-10-25T07:00:00Z")));
    }
    @Test void programTimerNeverReopensAnExistingPublishedDay(){
        TipsTest fixture=new TipsTest();fixture.setup();fixture.workflow.postTips(fixture.date,fixture.body(true));ObjectNode original=fixture.store.get("days/"+fixture.date+".json");
        Workflow w=new Workflow(fixture.store,date->{throw new AssertionError("Published schedule must not be fetched again");},job->{throw new AssertionError("Published day must not queue imports");});
        w.programTick(afternoon);assertEquals(original,fixture.store.get("days/"+fixture.date+".json"));
    }
}
