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
    @Test void thirteenOClockCreatesOnlyTheProgramAndEighteenOClockQueuesAdvancedDataOnce(){
        MemoryStore store=new MemoryStore();List<ObjectNode> jobs=new ArrayList<>();AtomicInteger schedules=new AtomicInteger();
        DataCollector collector=new DataCollector(store,url->{throw new AssertionError("No advanced source should be fetched by the program timer");},Clock.fixed(afternoon,ZoneOffset.UTC));
        collector.odds=(date,data)->{throw new AssertionError("No price collection at 13:00");};
        Workflow w=new Workflow(store,date->{schedules.incrementAndGet();return DataCollectorTest.template(date,"2099-01-02T00:00:00Z");},jobs::add,collector);
        w.programTick(afternoon);w.programTick(afternoon.plusSeconds(1800));
        ObjectNode saved=w.require(day);assertEquals(1,schedules.get());assertTrue(jobs.isEmpty());assertEquals("SCHEDULED",text(saved,"enrichment.state"));
        assertEquals("2099-01-01T17:00:00Z",text(saved,"enrichment.scheduled_at"));assertTrue(saved.path("dataset").path("games").get(0).path("markets").isEmpty());
        assertTrue(saved.path("dataset").path("games").get(0).path("home").path("current").path("xgf_5v5_per60").isNull());
        assertEquals("SCHEDULED",text(w.readCards(saved),"enrichment.state"));assertEquals(1,w.readCards(saved).path("games").size());
        w.eveningTick(Instant.parse("2099-01-01T17:00:00Z"));w.eveningTick(Instant.parse("2099-01-01T17:30:00Z"));
        assertEquals(1,jobs.size());assertEquals("prefill",text(jobs.get(0),"kind"));assertEquals("PREFILLING",text(w.require(day),"state"));
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
