package sk.nhl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static sk.nhl.Json.*;

class WebCardsTest {
    @Test void cardProjectionKeepsPublishedChoicesWithoutStatisticsOrHiddenResearch(){
        TipsTest f=new TipsTest();f.setup();f.workflow.postTips(f.date,f.body(true));
        ObjectNode before=f.workflow.require(f.date),full=f.workflow.read(f.date),cards=f.workflow.readCards(before);
        assertEquals(full.path("data_snapshot_id"),cards.path("data_snapshot_id"));
        assertFalse(cards.path("details_loaded").asBoolean());assertTrue(full.path("details_loaded").asBoolean());
        assertEquals(3,cards.path("games").size());assertEquals(full.path("final_tips").path("tip_count"),cards.path("final_tips").path("tip_count"));
        assertFalse(cards.path("games").get(0).path("home").has("current"));
        assertFalse(cards.path("games").get(0).has("checks"));assertFalse(cards.has("evidence"));
        assertFalse(cards.path("final_tips").path("games").get(0).has("rationale"));
        assertFalse(cards.path("enrichment").has("sources"));
        assertEquals(before,f.workflow.require(f.date));
        assertTrue(cards.toString().length()<full.toString().length()/2);
        ObjectNode changed=f.workflow.require(f.date);changed.put("revision",99);
        ((ObjectNode)changed.path("dataset").path("games").get(0).path("home").path("current")).put("xgf_5v5_per60",9);
        f.store.put("days/"+f.date+".json",changed);
        assertEquals(cards.path("data_snapshot_id"),f.workflow.readCards(changed).path("data_snapshot_id"));
        assertNotEquals(9,f.workflow.read(f.date).path("games").get(0).path("home").path("current").path("xgf_5v5_per60").asDouble());
    }
    @Test void archiveOnlyReturnsSmallIndexAndUnpublishedSnapshotsChangeWithRevision(){
        TipsTest f=new TipsTest();f.setup();var first=f.workflow.readCards(f.day);
        ObjectNode changed=f.day.deepCopy();changed.put("revision",8);f.store.put("days/"+f.date+".json",changed);
        assertNotEquals(first.path("data_snapshot_id"),f.workflow.readCards(changed).path("data_snapshot_id"));
        JsonNode archive=f.workflow.archive().get(0);assertEquals(4,archive.size());assertFalse(archive.has("final_tips"));assertFalse(archive.has("coverage"));
    }
}
