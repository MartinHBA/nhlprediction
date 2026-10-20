package sk.nhl;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OddsGameMatcherTest {
    static final LocalDate DAY=LocalDate.parse("2026-10-20");
    static final Instant START=Instant.parse("2026-10-21T02:00:00Z"),NOW=START.minusSeconds(3600);
    static OddsGameMatcher.OfficialGame official(String id,Instant start,boolean scheduled){return new OddsGameMatcher.OfficialGame(id,"VAN","COL",start,scheduled);}
    static OddsGameMatcher.BookEvent event(Instant start,boolean live,Instant checked){return new OddsGameMatcher.BookEvent("tipsport-event","NHL","VAN","COL",start,live,checked);}
    @Test void slovakNextCalendarDayBelongsToPreviousNhlDay(){
        Instant book=OddsGameMatcher.localTime(LocalDate.parse("2026-10-21"),LocalTime.parse("04:07"),ZoneId.of("Europe/Bratislava"));
        var match=OddsGameMatcher.match(DAY,List.of(official("nhl-game",START,true)),event(book,false,NOW),NOW).orElseThrow();
        assertEquals("nhl-game",match.gameId());assertEquals(420,match.startDifferenceSeconds());assertEquals(START,match.pregameCutoff());
    }
    @Test void startsAtOfficialTimeEvenIfBookmakerShowsSevenMinutesLater(){
        assertTrue(OddsGameMatcher.match(DAY,List.of(official("g",START,true)),event(START.plusSeconds(420),false,START),START).isEmpty());
    }
    @Test void earlierBookmakerStartAlsoClosesPregameImport(){
        Instant book=START.minusSeconds(420);
        assertTrue(OddsGameMatcher.match(DAY,List.of(official("g",START,true)),event(book,false,book),book).isEmpty());
    }
    @Test void wrongDayOrRemoteStartIsNeverMatchedByTeamNamesAlone(){
        var slate=List.of(official("g",START,true));
        assertTrue(OddsGameMatcher.match(DAY.plusDays(1),slate,event(START,false,NOW),NOW).isEmpty());
        assertTrue(OddsGameMatcher.match(DAY,slate,event(START.plusSeconds(601),false,NOW),NOW).isEmpty());
        assertTrue(OddsGameMatcher.match(DAY,slate,event(START.plusSeconds(86400),false,NOW),NOW).isEmpty());
    }
    @Test void reversedTeamsAndAnotherLeagueAreRejected(){
        var slate=List.of(official("g",START,true));
        assertTrue(OddsGameMatcher.match(DAY,slate,new OddsGameMatcher.BookEvent("b","NHL","COL","VAN",START,false,NOW),NOW).isEmpty());
        assertTrue(OddsGameMatcher.match(DAY,slate,new OddsGameMatcher.BookEvent("b","AHL","VAN","COL",START,false,NOW),NOW).isEmpty());
    }
    @Test void liveOrOfficiallyStartedEventsAreRejected(){
        assertTrue(OddsGameMatcher.match(DAY,List.of(official("g",START,true)),event(START,true,NOW),NOW).isEmpty());
        assertTrue(OddsGameMatcher.match(DAY,List.of(official("g",START,false)),event(START,false,NOW),NOW).isEmpty());
    }
    @Test void futureAndStaleChecksAreRejected(){
        var slate=List.of(official("g",START,true));
        assertTrue(OddsGameMatcher.match(DAY,slate,event(START,false,NOW.plusSeconds(1)),NOW).isEmpty());
        assertTrue(OddsGameMatcher.match(DAY,slate,event(START,false,NOW.minusSeconds(7201)),NOW).isEmpty());
    }
    @Test void ambiguousGamesAndDstLocalTimesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->OddsGameMatcher.match(DAY,List.of(official("a",START,true),official("b",START.plusSeconds(60),true)),event(START,false,NOW),NOW));
        var zone=ZoneId.of("Europe/Bratislava");
        assertThrows(IllegalArgumentException.class,()->OddsGameMatcher.localTime(LocalDate.parse("2026-03-29"),LocalTime.parse("02:30"),zone));
        assertThrows(IllegalArgumentException.class,()->OddsGameMatcher.localTime(LocalDate.parse("2026-10-25"),LocalTime.parse("02:30"),zone));
    }
}
