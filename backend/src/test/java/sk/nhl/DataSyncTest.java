package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class DataSyncTest {
    MemoryStore store;Workflow workflow;ObjectNode imported;String day="2099-01-01";
    @BeforeEach void init(){
        store=new MemoryStore();workflow=new Workflow(store,d->DataCollectorTest.template(d,"2099-01-02T00:00:00Z"),j->{});workflow.start(day);ObjectNode current=workflow.require(day);
        ((ObjectNode)current.path("dataset").path("games").get(0).path("home").path("roster")).set("returns",M.valueToTree(List.of("fresh research")));store.put("days/"+day+".json",current);
        ObjectNode data=current.path("dataset").deepCopy(),game=(ObjectNode)data.path("games").get(0);String now=Instant.now().toString();data.put("as_of",now);JsonNode source=obj("id","auto-native","url","https://moneypuck.com/data.htm","checked_at",now);DataCollector.mergeEvidence(data,source);DataCollector.set(game,"home.current.xgf_5v5_per60",M.valueToTree(3),source,"fixture rate",arr());((ObjectNode)game.path("home").path("current")).set("source",obj("url","https://moneypuck.com/data.htm","checked_at",now,"status","unverified"));((ObjectNode)game.path("home").path("roster")).set("returns",M.valueToTree(List.of("stale research")));
        imported=obj("version",DataCollector.VERSION,"dataset",data,"auto_fields",obj("123",List.of("home.current.xgf_5v5_per60","home.current.source")),"warnings",List.of("export lag"),"sources",List.of(source));
    }
    @Test void ownerImportMergesOnlyNativeDataAndPreservesResearchAndHistory(){ObjectNode result=DataSync.apply(store,day,imported,0);assertEquals(1,result.path("revision").asInt());assertEquals(3,at(result.path("dataset").path("games").get(0),"home.current.xgf_5v5_per60").asDouble());assertEquals("fresh research",at(result.path("dataset").path("games").get(0),"home.roster.returns").get(0).asText());assertEquals("owner_upload",text(result,"enrichment.transport"));assertNotNull(store.get("history/"+day+"/0.json"));assertThrows(BusyException.class,()->DataSync.apply(store,day,imported,0));}
    @Test void unsafeOrDifferentScheduleCannotBeImported(){ObjectNode changed=imported.deepCopy();((ObjectNode)changed.path("dataset").path("games").get(0)).put("starts_at","2099-01-02T01:00:00Z");assertThrows(IllegalArgumentException.class,()->DataSync.apply(store,day,changed,0));ObjectNode unexpected=imported.deepCopy();((ArrayNode)unexpected.path("auto_fields").path("123")).add("home.goalie.status");assertThrows(IllegalArgumentException.class,()->DataSync.apply(store,day,unexpected,0));ObjectNode demo=imported.deepCopy();((ObjectNode)demo.path("dataset")).put("synthetic_data",true);assertThrows(IllegalArgumentException.class,()->DataSync.apply(store,day,demo,0));assertEquals(0,workflow.require(day).path("revision").asInt());}
    @Test void compactMcpGameRoundTripRestoresNativeChecksWithoutEchoingThem(){DataSync.apply(store,day,imported,0);ObjectNode editable=Mcp.editable(workflow.require(day),"123");assertFalse(editable.path("game").path("checks").path("fields").has("home.current.xgf_5v5_per60"));assertTrue(editable.path("evidence").isEmpty());workflow.saveGame(day,editable.path("game"),editable.path("evidence"),1);assertEquals("found",text(workflow.read(day).path("games").get(0).path("checks").path("fields").path("home.current.xgf_5v5_per60"),"status"));assertTrue(Checks.validate(workflow.require(day).path("dataset")).isEmpty());JsonNode result=new Mcp(workflow).call("get_results",obj("day",day));JsonNode content=parse(result.path("content").get(0).path("text").asText());assertFalse(content.has("games"));assertTrue(content.has("coverage"));ObjectNode changed=editable.path("game").deepCopy();((ObjectNode)changed.path("home").path("current").path("source")).put("status","verified");assertThrows(IllegalArgumentException.class,()->workflow.saveGame(day,changed,arr(),2));}

    void importPenalty(double value){
        ObjectNode game=(ObjectNode)imported.path("dataset").path("games").get(0);
        DataCollector.set(game,"home.current.penalties_taken_per_game",M.valueToTree(value),imported.path("sources").get(0),"published penalty rate",arr());
        ((ArrayNode)imported.path("auto_fields").path("123")).add("home.current.penalties_taken_per_game");
        DataSync.apply(store,day,imported,0);
    }
    @Test void mcpAcceptsEquivalentIntegerNotationAndKeepsCanonicalNativeNumber(){
        importPenalty(5.0);Mcp mcp=new Mcp(workflow);
        JsonNode response=mcp.call("get_game",obj("day",day,"game_id","123"));
        ObjectNode editable=(ObjectNode)parse(response.path("content").get(0).path("text").asText());
        ObjectNode game=(ObjectNode)editable.path("game");
        ((ObjectNode)game.path("home").path("current")).put("penalties_taken_per_game",5);
        JsonNode saved=mcp.call("save_game",parse(obj("day",day,"game",game,"evidence",editable.path("evidence"),"revision",1).toString()));
        assertFalse(saved.path("isError").asBoolean(),saved.toString());
        JsonNode stored=workflow.read(day).path("games").get(0);
        assertEquals(DoubleNode.valueOf(5.0),at(stored,"home.current.penalties_taken_per_game"));
        assertEquals("found",text(stored.path("checks").path("fields").path("home.current.penalties_taken_per_game"),"status"));
    }
    @Test void nativePenaltyChangesIncludingTinyRoundingAreRejected(){
        importPenalty(3.5555555555555554);
        ObjectNode editable=Mcp.editable(workflow.require(day),"123"),game=(ObjectNode)editable.path("game");
        for(JsonNode value:List.of(DoubleNode.valueOf(3.56),DoubleNode.valueOf(Math.nextUp(3.5555555555555554)),IntNode.valueOf(4),NullNode.instance,TextNode.valueOf("3.5555555555555554"))){
            ((ObjectNode)game.path("home").path("current")).set("penalties_taken_per_game",value);
            IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->workflow.saveGame(day,game,arr(),1));
            assertTrue(error.getMessage().contains("Backend-prefilled field is immutable"));
            assertEquals(1,workflow.require(day).path("revision").asInt());
        }
    }
}
