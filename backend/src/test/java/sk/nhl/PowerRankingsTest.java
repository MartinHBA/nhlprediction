package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class PowerRankingsTest {
    static final Instant NOW=Instant.parse("2026-10-24T13:00:00Z");
    ObjectNode state(){ObjectNode ratings=obj();int index=0;for(JsonNode team:resource("nhl-teams.json"))ratings.put(text(team,"code"),1500+index++-15.5);return obj("state","READY","season","20262027","cutoff_day","2026-10-24","calculated_at","2026-10-24T11:00:00Z","ratings",ratings,"results_used",List.of(obj("private","ledger")),"initial",1500,"k",20,"method","private implementation");}
    @Test void readsAllStoredTeamsSortedByExactRatingWithTheCorrectConferencesWithoutMutatingStorage(){
        MemoryStore store=new MemoryStore();ObjectNode original=state();store.put(Elo.stateKey("2026-10-24"),original);ObjectNode result=PowerRankings.view(store,NOW);
        assertEquals("READY",text(result,"state"));assertEquals("2026-10-24",text(result,"as_of_day"));assertEquals(32,result.path("teams").size());assertEquals(16,result.path("teams").valueStream().filter(t->text(t,"conference").equals("Eastern")).count());assertEquals(16,result.path("teams").valueStream().filter(t->text(t,"conference").equals("Western")).count());
        double previous=Double.POSITIVE_INFINITY;Set<String> codes=new HashSet<>();for(JsonNode team:result.path("teams")){assertTrue(codes.add(text(team,"code")));assertTrue(number(team,"rating")<=previous);previous=number(team,"rating");assertEquals(number(original,"ratings."+text(team,"code")),number(team,"rating"));}
        assertEquals(original,store.get(Elo.stateKey("2026-10-24")));assertEquals(1,store.data.size());assertTrue(result.path("teams").valueStream().anyMatch(t->text(t,"code").equals("UTA")&&text(t,"name").equals("Utah Mammoth")&&text(t,"conference").equals("Western")));
    }
    @Test void publicResponseHasNoRawHistoryCalculationParametersOrMethod(){
        MemoryStore store=new MemoryStore();store.put(Elo.stateKey("2026-10-24"),state());ObjectNode result=PowerRankings.view(store,NOW);Set<String> keys=new HashSet<>();result.fieldNames().forEachRemaining(keys::add);assertEquals(Set.of("state","season","as_of_day","updated_at","team_count","teams"),keys);
        for(JsonNode team:result.path("teams")){keys.clear();team.fieldNames().forEachRemaining(keys::add);assertEquals(Set.of("code","name","conference","rating"),keys);}assertFalse(result.toString().toLowerCase(Locale.ROOT).contains("elo"));
    }
    @Test void missingSeasonDoesNotCreateInventedStandingsOrStartAnImport(){MemoryStore store=new MemoryStore();ObjectNode result=PowerRankings.view(store,NOW);assertEquals("AWAITING_DATA",text(result,"state"));assertTrue(result.path("teams").isEmpty());assertTrue(store.data.isEmpty());}
    @Test void incompleteCorruptedAndFutureSnapshotsAreRejected(){
        for(String issue:List.of("missing","number","season","future")){MemoryStore store=new MemoryStore();ObjectNode state=state();switch(issue){case "missing"->((ObjectNode)state.path("ratings")).remove("ANA");case "number"->((ObjectNode)state.path("ratings")).put("ANA","1500");case "season"->state.put("season","20252026");case "future"->state.put("cutoff_day","2026-10-25");}store.put(Elo.stateKey("2026-10-24"),state);assertThrows(IllegalStateException.class,()->PowerRankings.view(store,NOW),issue);}
    }
}
