package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class MemoryStore implements Store {
    final Map<String,ObjectNode>data=new java.util.concurrent.ConcurrentHashMap<>();
    public ObjectNode get(String k){return data.containsKey(k)?data.get(k).deepCopy():null;}
    public void put(String k,ObjectNode v){data.put(k,v.deepCopy());}
    public void create(String k,ObjectNode v){if(data.containsKey(k))throw new IllegalStateException("Exists");put(k,v);}
    public ObjectNode take(String k){return data.remove(k);}
    public List<String>keys(String p){return data.keySet().stream().filter(k->k.startsWith(p)).toList();}
    public synchronized <T>T locked(String k,Supplier<T> s){return s.get();}
}
class WorkflowTest {
    MemoryStore store;Workflow workflow;List<ObjectNode>jobs;String day="2099-01-01";
    @BeforeEach void init(){store=new MemoryStore();jobs=new ArrayList<>();workflow=new Workflow(store,d->Schedule.template(obj("gameWeek",List.of(obj("date",d,"games",List.of(obj("id",123,"gameType",2,"startTimeUTC","2099-01-02T00:00:00Z","gameState","FUT","homeTeam",obj("abbrev","BOS"),"awayTeam",obj("abbrev","COL")))))),d,Instant.now().toString(),"https://api-web.nhle.com/v1/schedule/"+d),jobs::add);}
    @Test void startIsIdempotentAndKeepsOfficialSlate(){assertEquals("COLLECTING",text(workflow.start(day),"state"));assertEquals(1,workflow.start(day).path("game_count").asInt());assertEquals(1,store.keys("days/").size());assertEquals("123",workflow.require(day).path("dataset").path("expected_game_ids").get(0).asText());}
    @Test void incompleteCannotQueue(){workflow.start(day);assertEquals("CHECKLIST_INCOMPLETE",text(workflow.finish(day),"status"));assertTrue(jobs.isEmpty());}
    @Test void saveUsesRevisionAndPreservesOfficialTeams(){workflow.start(day);ObjectNode game=workflow.require(day).path("dataset").path("games").get(0).deepCopy();assertEquals(1,workflow.saveGame(day,game,arr(),0).path("revision").asInt());assertThrows(BusyException.class,()->workflow.saveGame(day,game,arr(),0));((ObjectNode)game.path("home")).put("name","FAKE");assertThrows(IllegalArgumentException.class,()->workflow.saveGame(day,game,arr(),1));}
    @Test void noGamesIsSuccessfulEmptyDay(){Workflow empty=new Workflow(store,d->Schedule.template(obj("gameWeek",List.of(obj("date",d,"games",arr()))),d,Instant.now().toString(),"https://example.org"),jobs::add);assertEquals("NO_GAMES",text(empty.start(day),"state"));assertEquals("NO_GAMES",text(empty.finish(day),"state"));assertTrue(jobs.isEmpty());}
    @Test void finalizationValidatesSynchronouslyAndNeverQueuesPredictions(){
        workflow.start(day);ObjectNode d=workflow.require(day),data=TipsTest.dataset(Instant.now());ObjectNode game=data.path("games").get(0).deepCopy();game.put("game_id","123");((ObjectNode)game.path("home")).put("name","BOS");((ObjectNode)game.path("away")).put("name","COL");data.set("games",arr().add(game));data.set("expected_game_ids",arr().add("123"));d.set("dataset",data);store.put("days/"+day+".json",d);
        assertEquals("READY_FOR_ANALYSIS",text(workflow.finish(day),"state"));assertTrue(jobs.isEmpty());assertFalse(workflow.read(day).has("report"));
        workflow.processImport(obj("day",day,"job_id","old-prediction","sims",100000));assertTrue(jobs.isEmpty());assertFalse(workflow.read(day).has("report"));
    }
    @Test void mcpInitializationAndValidation(){Mcp mcp=new Mcp(workflow);assertEquals("nhl-prediction",text(mcp.handle(obj("jsonrpc","2.0","id",1,"method","initialize","params",obj("protocolVersion","2025-06-18"))),"result.serverInfo.name"));assertEquals(11,mcp.handle(obj("jsonrpc","2.0","id",2,"method","tools/list")).path("result").path("tools").size());assertEquals(-32602,mcp.handle(obj("jsonrpc","2.0","id",3,"method","tools/call","params",obj("name","start_day","arguments",obj("day","bad")))).path("error").path("code").asInt());}
}
