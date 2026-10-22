package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class NativeMarketTest {
    MemoryStore store;Workflow workflow;Mcp mcp;String day=NhlOddsTest.DAY;
    @BeforeEach void init(){
        store=new MemoryStore();workflow=new Workflow(store,d->NhlOddsTest.data(),j->{});mcp=new Mcp(workflow);
        ObjectNode dataset=(ObjectNode)NhlOddsTest.importer(NhlOddsTest.feed()).collect(day,NhlOddsTest.data()).path("dataset");
        for(JsonNode market:dataset.path("games").get(0).path("markets"))if(text(market,"selection").equals("draw"))((ObjectNode)market).put("odds",4.0);
        store.put("days/"+day+".json",obj("day",day,"state","COLLECTING","revision",0,"dataset",dataset,"official_games",dataset.path("games").deepCopy(),"auto_fields",obj()));
    }
    ObjectNode editable(){return (ObjectNode)parse(mcp.call("get_game",obj("day",day,"game_id","123")).path("content").get(0).path("text").asText());}
    ObjectNode draw(JsonNode game){for(JsonNode market:game.path("markets"))if(text(market,"selection").equals("draw"))return (ObjectNode)market;throw new AssertionError("Missing draw market");}
    @Test void unchangedMcpMarketAcceptsIntegerNotationAndRetainsTheOriginalRecord(){
        ObjectNode original=draw(workflow.require(day).path("dataset").path("games").get(0)).deepCopy(),edit=editable();
        draw(edit.path("game")).put("odds",4);
        JsonNode result=mcp.call("save_game",parse(obj("day",day,"game",edit.path("game"),"evidence",edit.path("evidence"),"revision",0).toString()));
        assertFalse(result.path("isError").asBoolean(),result.toString());
        assertEquals(original,draw(workflow.require(day).path("dataset").path("games").get(0)));
        assertEquals(1,workflow.require(day).path("revision").asInt());
    }
    @Test void equivalentDecimalNodesAndIntegralLinesRetainCanonicalNumbers(){
        ObjectNode stored=workflow.require(day);for(JsonNode market:stored.path("dataset").path("games").get(0).path("markets"))if(text(market,"kind").equals("total"))((ObjectNode)market).put("line",6.0);store.put("days/"+day+".json",stored);
        ObjectNode edit=editable();draw(edit.path("game")).set("odds",DecimalNode.valueOf(new BigDecimal("4.000")));
        for(JsonNode market:edit.path("game").path("markets"))if(text(market,"kind").equals("total"))((ObjectNode)market).put("line",6);
        workflow.saveGame(day,edit.path("game"),edit.path("evidence"),0);
        for(JsonNode market:workflow.require(day).path("dataset").path("games").get(0).path("markets"))if(text(market,"kind").equals("total"))assertEquals(DoubleNode.valueOf(6.0),market.path("line"));
        assertEquals(DoubleNode.valueOf(4.0),draw(workflow.require(day).path("dataset").path("games").get(0)).path("odds"));
    }
    @Test void realChangesToNativePriceRulesAndEvidenceRemainRejectedWithoutSaving(){
        List<Consumer<ObjectNode>> changes=List.of(m->m.put("odds",Math.nextUp(4.0)),m->m.putNull("line"),m->m.put("score_basis","official_final"),m->((ObjectNode)m.path("source")).put("url","https://example.org/other"),m->m.set("evidence_ids",arr()),m->m.put("settlement_verified",false));
        for(int i=0;i<changes.size();i++){
            ObjectNode edit=editable(),market=draw(edit.path("game"));if(i==1)market.put("line",.5);else changes.get(i).accept(market);
            assertThrows(IllegalArgumentException.class,()->workflow.saveGame(day,edit.path("game"),edit.path("evidence"),0));
            assertEquals(0,workflow.require(day).path("revision").asInt());
        }
    }
    @Test void missingAndForgedNativeRecordsRemainRejected(){
        ObjectNode missing=editable();ArrayNode markets=(ArrayNode)missing.path("game").path("markets");markets.remove(0);
        assertThrows(IllegalArgumentException.class,()->workflow.saveGame(day,missing.path("game"),missing.path("evidence"),0));
        ObjectNode edit=editable();ObjectNode forged=((ObjectNode)edit.path("game").path("markets").get(0)).deepCopy();forged.put("id",NhlOdds.PREFIX+"forged");((ArrayNode)edit.path("game").path("markets")).add(forged);
        ObjectNode forgedEdit=edit;assertThrows(IllegalArgumentException.class,()->workflow.saveGame(day,forgedEdit.path("game"),forgedEdit.path("evidence"),0));
        assertEquals(0,workflow.require(day).path("revision").asInt());
    }
    @Test void newerVerifiedBookmakerPriceCanBeSavedAndUpdatedWithoutChangingNativeQuotes(){
        ObjectNode edit=editable(),nativeQuote=draw(edit.path("game")).deepCopy(),quote=nativeQuote.deepCopy();
        quote.put("id","research-current-price").put("odds",1.27).set("evidence_ids",arr().add("research-price-proof"));
        ((ObjectNode)quote.path("source")).put("url","https://example.org/bookmaker/game/123").put("checked_at",Instant.now().minusSeconds(1).toString());
        ((ArrayNode)edit.path("game").path("markets")).add(quote);
        ((ArrayNode)edit.path("evidence")).add(obj("id","research-price-proof","url","https://example.org/bookmaker/game/123","checked_at",text(quote,"source.checked_at")));
        workflow.saveGame(day,edit.path("game"),edit.path("evidence"),0);
        assertEquals(nativeQuote,draw(workflow.require(day).path("dataset").path("games").get(0)));
        ObjectNode next=editable();ObjectNode revised=(ObjectNode)next.path("game").path("markets").get(next.path("game").path("markets").size()-1);
        revised.put("odds",1.29).set("evidence_ids",arr().add("research-price-proof-new"));
        ((ObjectNode)revised.path("source")).put("checked_at",Instant.now().toString());
        ((ArrayNode)next.path("evidence")).add(obj("id","research-price-proof-new","url",text(revised,"source.url"),"checked_at",text(revised,"source.checked_at")));
        workflow.saveGame(day,next.path("game"),next.path("evidence"),1);
        JsonNode current=workflow.require(day).path("dataset").path("games").get(0);assertEquals(nativeQuote,draw(current));
        assertTrue(MarketQuotes.all(current,Instant.now()).valueStream().anyMatch(c->text(c,"id").equals("research-current-price")&&c.path("price").asDouble()==1.29&&c.path("selectable").asBoolean()));
    }
}
